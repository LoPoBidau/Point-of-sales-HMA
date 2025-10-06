package com.example.pos_hma.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.example.pos_hma.utils.StockNotificationHelper
import com.example.pos_hma.data.BatchState
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
            val wasHeld = snapshot.getBoolean("held") == true
            if (qtyPosted > 0L && !wasHeld) {
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
        val scheduledMillis = scheduledTs.toDate().time
        if (scheduledMillis > nowMillis) {
            val remaining = scheduledMillis - nowMillis
            Log.d(TAG, "Jadwal $docId masih $remaining ms ke depan, jadwalkan ulang")
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

        val mvRef = db.collection("inventory_movements").document()
        val batchRef = db.collection("stock_batches").document()
        var holdNewStockResult = false

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
                val stageCost = unitCost > 0 && oldStock > 0 && unitCost < currentCost
                val stagePrice = newSalePrice > 0 && oldStock > 0 && newSalePrice < currentSalePrice
                val holdNewStock = stageCost || stagePrice
                holdNewStockResult = holdNewStock
                val batchSalePrice = if (newSalePrice > 0) newSalePrice else currentSalePrice
                val previousIncoming = pSnap.getLong("stagedIncomingQty") ?: 0L
                val productUpdates = mutableMapOf<String, Any?>(
                    "updatedAt" to nowField
                )
                if (holdNewStock) {
                    productUpdates["stock"] = oldStock
                    productUpdates["stagedIncomingQty"] = previousIncoming + qty
                } else {
                    productUpdates["stock"] = newStock
                }
                if (stageCost) {
                    productUpdates["stagedLastCost"] = unitCost
                } else if (unitCost > 0) {
                    productUpdates["lastCost"] = unitCost
                }
                if (stagePrice) {
                    productUpdates["stagedSalePrice"] = newSalePrice
                } else if (!holdNewStock && newSalePrice > 0 && newSalePrice != currentSalePrice) {
                    productUpdates["salePrice"] = newSalePrice
                }
                if (stageCost || stagePrice) {
                    productUpdates["stagedOldQty"] = oldStock
                }
                trx.update(pRef, productUpdates)

                val batchData = mutableMapOf<String, Any>(
                    "sku" to sku,
                    "unitCost" to unitCost,
                    "remainingQty" to qty,
                    "receivedQty" to qty,
                    "receivedAt" to nowField,
                    "purchaseId" to purchaseId,
                    "invoiceNo" to invoiceNo,
                    "supplierName" to supplierName,
                    "supplierId" to supplierId,
                    "dueDate" to scheduledTs,
                    "termDays" to termDays,
                    "state" to if (holdNewStock) BatchState.HOLD.name else BatchState.OPEN.name
                )
                batchData["salePrice"] = batchSalePrice
                trx.set(batchRef, batchData)
                val targetBatchId = batchRef.id

                val movement = mutableMapOf<String, Any>(
                    "sku" to sku,
                    "type" to "PURCHASE",
                    "qtyDelta" to qty,
                    "unitCost" to unitCost,
                    "createdAt" to nowField,
                    "refId" to purchaseId,
                    "note" to "Auto post jatuh tempo",
                    "batchId" to targetBatchId
                )
                if (holdNewStock) {
                    movement["note"] = "Auto post tertahan menunggu stok lama habis"
                }
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
                    "notificationSent" to false,
                    "held" to holdNewStock
                ))

                null
            }.await()
        } catch (e: Exception) {
            Log.w(TAG, "Transaksi auto post gagal", e)
            return Result.retry()
        }

        // Diubah: Logika notifikasi
        val notified = if (holdNewStockResult) {
            StockNotificationHelper.notifyStockHeld(
                applicationContext,
                db,
                docId,
                productName,
                qty,
                scheduledTs,
                sku,
                invoiceNo.takeIf { it.isNotBlank() }
            )
        } else {
            StockNotificationHelper.notifyStockPosted(
                applicationContext,
                db,
                docId,
                productName,
                qty,
                scheduledTs,
                sku,
                invoiceNo.takeIf { it.isNotBlank() }
            )
        }

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
        private const val COLLECTION_PENDING = "pending_stock_receipts"
        private const val KEY_DOC_ID = "doc_id"

        fun enqueue(context: Context, docId: String, scheduledAt: Timestamp) {
            val delay = maxOf(0L, scheduledAt.toDate().time - System.currentTimeMillis())
            val data = workDataOf(KEY_DOC_ID to docId)
            val builder = OneTimeWorkRequestBuilder<ScheduledStockPostingWorker>()
                .setInputData(data)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .addTag("pending_stock_$docId")
            if (delay == 0L) {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            val request = builder.build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "pending_stock_$docId",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}

