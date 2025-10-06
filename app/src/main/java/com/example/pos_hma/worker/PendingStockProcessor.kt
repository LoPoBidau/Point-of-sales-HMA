package com.example.pos_hma.worker

import android.content.Context
import android.util.Log
import com.example.pos_hma.data.BatchState
import com.example.pos_hma.utils.StockNotificationHelper
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import kotlin.math.max

object PendingStockProcessor {

    enum class Source { WORKER, FOREGROUND }
    enum class Outcome { SUCCESS, RETRY, FAILURE }

    suspend fun process(
        context: Context,
        db: FirebaseFirestore,
        docId: String,
        source: Source = Source.WORKER
    ): Outcome {
        val docRef = db.collection(COLLECTION_PENDING).document(docId)
        val snapshot = try {
            docRef.get().await()
        } catch (e: Exception) {
            Log.w(TAG, "Gagal mengambil data pending ($source)", e)
            return Outcome.RETRY
        }

        if (!snapshot.exists()) {
            Log.d(TAG, "Pending $docId sudah tidak ada ($source)")
            return Outcome.SUCCESS
        }

        val status = snapshot.getString("status") ?: "pending"
        if (status == "posted") {
            val qtyPosted = snapshot.getLong("qty") ?: 0L
            if (qtyPosted > 0L && snapshot.getBoolean("notificationSent") != true) {
                val sku = snapshot.getString("sku").orEmpty()
                val name = snapshot.getString("productName").orEmpty().ifBlank { sku }
                val notified = StockNotificationHelper.notifyStockPosted(
                    context = context,
                    db = db,
                    docKey = docId,
                    productName = name,
                    qty = qtyPosted,
                    dueDate = snapshot.getTimestamp("dueDate"),
                    sku = sku,
                    invoiceNo = snapshot.getString("invoiceNo")
                )
                if (notified) {
                    try {
                        docRef.update("notificationSent", true).await()
                    } catch (e: Exception) {
                        Log.w(TAG, "Gagal menandai notifikasi terkirim (posted) ($source)", e)
                    }
                }
            }
            return Outcome.SUCCESS
        }

        if (status == "processing") {
            Log.d(TAG, "Pending $docId sedang diproses oleh worker lain ($source)")
            return Outcome.RETRY
        }

        val scheduledTs = snapshot.getTimestamp("scheduledAt") ?: snapshot.getTimestamp("dueDate")
        if (scheduledTs == null) {
            Log.w(TAG, "scheduledAt kosong, tandai sebagai posted untuk $docId ($source)")
            return try {
                docRef.update(
                    mapOf(
                        "status" to "posted",
                        "postedAt" to FieldValue.serverTimestamp(),
                        "notificationSent" to false
                    )
                ).await()
                Outcome.SUCCESS
            } catch (e: Exception) {
                Log.w(TAG, "Gagal update pending tanpa scheduledAt ($source)", e)
                Outcome.RETRY
            }
        }

        val nowMillis = System.currentTimeMillis()
        val scheduledMillis = scheduledTs.toDate().time
        if (scheduledMillis > nowMillis) {
            val remaining = scheduledMillis - nowMillis
            Log.d(TAG, "Jadwal $docId masih $remaining ms ke depan, menunggu scheduler server ($source)")
            return Outcome.SUCCESS
        }

        val sku = snapshot.getString("sku").orEmpty()
        if (sku.isBlank()) {
            Log.e(TAG, "SKU kosong untuk pending $docId ($source)")
            return Outcome.FAILURE
        }

        val qty = snapshot.getLong("qty") ?: return Outcome.FAILURE
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
                trx.update(
                    docRef,
                    mapOf(
                        "status" to "processing",
                        "processingAt" to nowField
                    )
                )

                val pRef = db.collection("products").document(sku)
                val pSnap = trx.get(pRef)
                require(pSnap.exists()) { "Produk tidak ditemukan" }
                require((pSnap.getBoolean("trackStock") ?: true)) { "Jasa tidak pakai stok" }

                val oldStock = pSnap.getLong("stock") ?: 0L
                val newStock = oldStock + qty
                val currentSalePrice = pSnap.getLong("salePrice") ?: 0L
                val currentCost = pSnap.getLong("lastCost") ?: unitCost

                // Tahankan (HOLD) bila harga baru < harga lama & stok lama masih ada
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
                    "productName" to productName, // ditambahkan agar sesuai schema
                    "unitCost" to unitCost,
                    "receivedQty" to qty,
                    "remainingQty" to qty,
                    "receivedAt" to nowField,
                    "purchaseId" to purchaseId,
                    "invoiceNo" to invoiceNo,
                    "supplierName" to supplierName,
                    "supplierId" to supplierId,
                    "dueDate" to scheduledTs,
                    "termDays" to termDays,
                    "state" to if (holdNewStock) BatchState.HOLD.name else BatchState.OPEN.name,
                    "salePrice" to batchSalePrice
                )
                trx.set(batchRef, batchData)
                val targetBatchId = batchRef.id

                val movement = mutableMapOf<String, Any>(
                    "sku" to sku,
                    "type" to "PURCHASE",
                    "qtyDelta" to qty,
                    "unitCost" to unitCost,
                    "createdAt" to nowField,
                    "refId" to purchaseId,
                    "note" to if (holdNewStock) "Auto post tertahan menunggu stok lama habis" else "Auto post jatuh tempo",
                    "batchId" to targetBatchId
                )
                trx.set(mvRef, movement)

                if (purchaseId.isNotBlank()) {
                    val poRef = db.collection("purchases").document(purchaseId)
                    trx.update(
                        poRef,
                        mutableMapOf<String, Any>(
                            "stockPosted" to true,
                            "stockPostedAt" to nowField,
                            "pendingStockId" to FieldValue.delete()
                        )
                    )
                }

                // Tandai posted. Jika di-HOLD, langsung set notificationSent=true supaya tidak ada notifikasi "Stock Posted"
                trx.update(
                    docRef,
                    mapOf(
                        "status" to "posted",
                        "postedAt" to nowField,
                        "processingAt" to FieldValue.delete(),
                        "notificationSent" to holdNewStock // true jika HOLD; false jika OPEN (akan dinotifikasi di bawah)
                    )
                )

                null
            }.await()
        } catch (e: Exception) {
            Log.w(TAG, "Transaksi auto post gagal ($source)", e)
            return Outcome.RETRY
        }

        // Jika tidak HOLD, kirim notifikasi "stok otomatis ditambahkan"
        if (!holdNewStockResult) {
            val notified = StockNotificationHelper.notifyStockPosted(
                context = context,
                db = db,
                docKey = docId,
                productName = productName,
                qty = qty,
                dueDate = scheduledTs,
                sku = sku,
                invoiceNo = invoiceNo.takeIf { it.isNotBlank() } // ← perbaikan: pakai invoiceNo, bukan purchaseId
            )
            if (notified) {
                try {
                    docRef.update("notificationSent", true).await()
                } catch (e: Exception) {
                    Log.w(TAG, "Gagal menandai notifikasi terkirim ($source)", e)
                }
            }
        }

        return Outcome.SUCCESS
    }

    private const val TAG = "PendingStockProcessor"
    private const val COLLECTION_PENDING = "pending_stock_receipts"

    /**
     * Utility untuk menjadwalkan ulang secara manual (opsional).
     */
    fun reenqueueIfFuture(context: Context, docId: String, ts: Timestamp?) {
        ts ?: return
        val delay = max(0L, ts.toDate().time - System.currentTimeMillis())
        if (delay > 0L) {
            Log.d(TAG, "Server akan memproses $docId pada ${ts.toDate()}")
        }
    }
}

