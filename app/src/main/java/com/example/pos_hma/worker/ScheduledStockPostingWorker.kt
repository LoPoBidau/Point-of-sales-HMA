package com.example.pos_hma.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.example.pos_hma.util.StockNotificationHelper
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

class ScheduledStockPostingWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val db by lazy { FirebaseFirestore.getInstance() }

    override suspend fun doWork(): Result {
        val docId = inputData.getString(KEY_DOC_ID) ?: return Result.failure()
        val docRef = db.collection(COLLECTION_PENDING).document(docId)

        val snapshot = try {
            docRef.get().await()
        } catch (e: Exception) {
            Log.w(TAG, "Gagal mengambil data pending", e)
            return Result.retry()
        }

        if (!snapshot.exists()) {
            Log.d(TAG, "Pending $docId sudah tidak ada")
            return Result.success()
        }

        val status = snapshot.getString("status") ?: "pending"
        if (status == "posted") {
            val qtyPosted = snapshot.getLong("qty") ?: 0L
            if (qtyPosted > 0L) {
                val skuSnapshot = snapshot.getString("sku").orEmpty()
                val nameSnapshot = snapshot.getString("productName").orEmpty().ifBlank { skuSnapshot }
                val posted = StockNotificationHelper.notifyStockPosted(
                    applicationContext,
                    db,
                    docId,
                    nameSnapshot,
                    qtyPosted,
                    snapshot.getTimestamp("dueDate"),
                    skuSnapshot,
                    snapshot.getString("invoiceNo")
                )
                if (posted && snapshot.getBoolean("notificationSent") != true) {
                    try {
                        docRef.update("notificationSent", true).await()
                    } catch (e: Exception) {
                        Log.w(TAG, "Gagal menandai notifikasi terkirim (posted)", e)
                    }
                }
            }
            return Result.success()
        }
        if (status == "processing") {
            Log.d(TAG, "Pending $docId sedang diproses oleh worker lain")
            return Result.retry()
        }

        val scheduledTs = snapshot.getTimestamp("scheduledAt") ?: snapshot.getTimestamp("dueDate")
        if (scheduledTs == null) {
            Log.w(TAG, "scheduledAt kosong, tandai sebagai posted untuk $docId")
            try {
                docRef.update(mapOf(
                    "status" to "posted",
                    "postedAt" to FieldValue.serverTimestamp(),
                    "notificationSent" to false
                )).await()
            } catch (e: Exception) {
                Log.w(TAG, "Gagal update pending tanpa scheduledAt", e)
            }
            return Result.success()
        }

        val nowMillis = System.currentTimeMillis()
        val dueMillis = scheduledTs.toDate().time
        if (dueMillis - nowMillis > TimeUnit.MINUTES.toMillis(1)) {
            Log.d(TAG, "Jadwal $docId masih ${dueMillis - nowMillis} ms ke depan, jadwalkan ulang")
            enqueue(applicationContext, docId, scheduledTs)
            return Result.success()
        }

        val sku = snapshot.getString("sku").orEmpty()
        if (sku.isBlank()) {
            Log.e(TAG, "SKU kosong untuk pending $docId")
            return Result.failure()
        }
        val qty = snapshot.getLong("qty") ?: return Result.failure()
        val unitCost = snapshot.getLong("unitCost") ?: 0L
        val newSalePrice = snapshot.getLong("newSalePrice") ?: 0L
        val invoiceNo = snapshot.getString("invoiceNo").orEmpty()
        val supplierName = snapshot.getString("supplierName").orEmpty()
        val supplierId = snapshot.getString("supplierId").orEmpty()
        val termDays = snapshot.getLong("termDays") ?: 0L
        val purchaseId = snapshot.getString("purchaseId").orEmpty()
        val productName = snapshot.getString("productName").orEmpty().ifBlank { sku }

        val lastBatchDoc = try {
            db.collection("stock_batches")
                .whereEqualTo("sku", sku)
                .orderBy("receivedAt", Query.Direction.DESCENDING)
                .limit(1)
                .get()
                .await()
                .documents
                .firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Gagal mengambil batch terakhir", e)
            null
        }

        val mvRef = db.collection("inventory_movements").document()
        val batchRef = db.collection("stock_batches").document()

        try {
            db.runTransaction { trx ->
                val pendingSnap = trx.get(docRef)
                val currentStatus = pendingSnap.getString("status") ?: "pending"
                require(currentStatus == "pending") { "Pending sudah diproses" }

                val nowField = FieldValue.serverTimestamp()
                trx.update(docRef, mapOf(
                    "status" to "processing",
                    "processingAt" to nowField
                ))

                val pRef = db.collection("products").document(sku)
                val pSnap = trx.get(pRef)
                require(pSnap.exists()) { "Produk tidak ditemukan" }
                require((pSnap.getBoolean("trackStock") ?: true)) { "Jasa tidak pakai stok" }

                val oldStock = pSnap.getLong("stock") ?: 0L
                val newStock = oldStock + qty
                val currentSalePrice = pSnap.getLong("salePrice") ?: 0L
                val currentCost = pSnap.getLong("lastCost") ?: unitCost
                val shouldStageSalePrice = newSalePrice > 0 && newSalePrice != currentSalePrice && oldStock > 0 && unitCost < currentCost
                val batchSalePrice = if (newSalePrice > 0) newSalePrice else currentSalePrice
                val productUpdates = mutableMapOf<String, Any?>(
                    "stock" to newStock,
                    "updatedAt" to nowField
                )
                if (unitCost > 0) productUpdates["lastCost"] = unitCost
                if (!shouldStageSalePrice && newSalePrice > 0 && newSalePrice != currentSalePrice) {
                    productUpdates["salePrice"] = newSalePrice
                }
                trx.update(pRef, productUpdates)

                val lastBatchSnap = if (lastBatchDoc != null) trx.get(lastBatchDoc.reference) else null
                var merged = false
                var targetBatchId: String? = null
                if (lastBatchSnap != null) {
                    val lastCost = lastBatchSnap.getLong("unitCost") ?: 0L
                    if (unitCost >= lastCost) {
                        val remain = lastBatchSnap.getLong("remainingQty") ?: 0L
                        val newRemain = remain + qty
                        val weighted = if (newRemain > 0) ((lastCost * remain + unitCost * qty) / newRemain) else unitCost
                        val batchUpdates = mutableMapOf<String, Any>(
                            "remainingQty" to newRemain,
                            "unitCost" to weighted,
                            "receivedAt" to nowField
                        )
                        if (newSalePrice > 0) batchUpdates["salePrice"] = newSalePrice
                        trx.update(lastBatchSnap.reference, batchUpdates)
                        merged = true
                        targetBatchId = lastBatchSnap.id
                    }
                }
                if (!merged) {
                    val batchData = mutableMapOf<String, Any>(
                        "sku" to sku,
                        "unitCost" to unitCost,
                        "remainingQty" to qty,
                        "receivedAt" to nowField,
                        "purchaseId" to purchaseId,
                        "invoiceNo" to invoiceNo,
                        "supplierName" to supplierName,
                        "supplierId" to supplierId,
                        "dueDate" to scheduledTs,
                        "termDays" to termDays
                    )
                    batchData["salePrice"] = batchSalePrice
                    trx.set(batchRef, batchData)
                    targetBatchId = batchRef.id
                }

                val movement = mutableMapOf<String, Any>(
                    "sku" to sku,
                    "type" to "PURCHASE",
                    "qtyDelta" to qty,
                    "unitCost" to unitCost,
                    "createdAt" to nowField,
                    "refId" to purchaseId,
                    "note" to "Auto post jatuh tempo"
                )
                targetBatchId?.let { movement["batchId"] = it }
                trx.set(mvRef, movement)

                if (purchaseId.isNotBlank()) {
                    val poRef = db.collection("purchases").document(purchaseId)
                    trx.update(poRef, mutableMapOf<String, Any>(
                        "stockPosted" to true,
                        "stockPostedAt" to nowField,
                        "pendingStockId" to FieldValue.delete()
                    ))
                }

                trx.update(docRef, mapOf(
                    "status" to "posted",
                    "postedAt" to nowField,
                    "notificationSent" to false
                ))

                null
            }.await()
        } catch (e: Exception) {
            Log.w(TAG, "Transaksi auto post gagal", e)
            return Result.retry()
        }

        val notified = StockNotificationHelper.notifyStockPosted(
            applicationContext,
            db,
            docId,
            productName,
            qty,
            scheduledTs,
            sku,
            purchaseId.takeIf { it.isNotBlank() }
        )
        if (notified) {
            try {
                docRef.update("notificationSent", true).await()
            } catch (e: Exception) {
                Log.w(TAG, "Gagal menandai notifikasi terkirim", e)
            }
        }

        return Result.success()
    }

    companion object {
        private const val TAG = "ScheduledStockWorker"
        private const val CHANNEL_ID = "stock_auto_posted"
        private const val COLLECTION_PENDING = "pending_stock_receipts"
        private const val KEY_DOC_ID = "doc_id"

        fun enqueue(context: Context, docId: String, scheduledAt: Timestamp) {
            val delay = maxOf(0L, scheduledAt.toDate().time - System.currentTimeMillis())
            val data = workDataOf(KEY_DOC_ID to docId)
            val request = OneTimeWorkRequestBuilder<ScheduledStockPostingWorker>()
                .setInputData(data)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .addTag("pending_stock_$docId")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "pending_stock_$docId",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
