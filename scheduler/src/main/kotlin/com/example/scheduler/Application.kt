package com.example.scheduler

import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.Timestamp
import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import com.google.cloud.firestore.Transaction
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.FileInputStream
import java.time.Instant

fun main(args: Array<String>): Unit = EngineMain.main(args)

fun Application.module() {
    install(CallLogging)
    install(ContentNegotiation) {
        json()
    }

    val interval = environment.config.propertyOrNull("scheduling.intervalMs")?.getString()?.toLongOrNull() ?: 60_000L
    val batchLimit = environment.config.propertyOrNull("scheduling.batchLimit")?.getString()?.toIntOrNull() ?: 20
    val projectId = environment.config.propertyOrNull("firebase.projectId")?.getString()?.takeIf { it.isNotBlank() }

    val firestore = initFirestore(projectId)
    val scheduler = StockSchedulingService(firestore, interval, batchLimit)

    environment.monitor.subscribe(ApplicationStarted) {
        scheduler.start()
    }
    environment.monitor.subscribe(ApplicationStopping) {
        scheduler.stop()
    }

    routing {
        get("/health") {
            call.respondText("OK")
        }
        get("/metrics/pending") {
            val count = withContext(Dispatchers.IO) {
                firestore.collection(COLLECTION_PENDING)
                    .whereEqualTo("status", "pending")
                    .limit(5)
                    .get()
                    .get()
                    .size()
            }
            call.respondText("pending_count $count")
        }
    }
}

private fun initFirestore(projectId: String?): Firestore {
    if (FirestoreOptions.getDefaultInstance() != null && FirestoreOptions.getDefaultInstance().service != null) {
        // Already initialized via env GOOGLE_APPLICATION_CREDENTIALS and default app.
    }
    val builder = FirestoreOptions.getDefaultInstance().toBuilder()
    if (projectId != null) {
        builder.setProjectId(projectId)
    }

    if (System.getenv("GOOGLE_APPLICATION_CREDENTIALS").isNullOrBlank()) {
        LOGGER.warn("GOOGLE_APPLICATION_CREDENTIALS not set. Firestore will rely on default credentials.")
    } else {
        builder.setCredentials(GoogleCredentials.fromStream(FileInputStream(System.getenv("GOOGLE_APPLICATION_CREDENTIALS"))))
    }

    return builder.build().service
}

private class StockSchedulingService(
    private val firestore: Firestore,
    private val intervalMs: Long,
    private val batchLimit: Int
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start() {
        if (job != null) return
        LOGGER.info("Starting stock scheduling service. Interval=${intervalMs}ms limit=$batchLimit")
        job = scope.launch {
            while (isActive) {
                try {
                    processCycle()
                } catch (t: Throwable) {
                    LOGGER.error("Scheduler cycle failed", t)
                }
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        scope.cancel()
        LOGGER.info("Stock scheduling service stopped")
    }

    private suspend fun processCycle() {
        val dueDocs = withContext(Dispatchers.IO) {
            firestore.collection(COLLECTION_PENDING)
                .whereEqualTo("status", "pending")
                .whereLessThanOrEqualTo("scheduledAt", Timestamp.now())
                .orderBy("scheduledAt")
                .limit(batchLimit)
                .get()
                .get()
                .documents
        }

        if (dueDocs.isEmpty()) {
            LOGGER.debug("No pending stock due at ${Instant.now()}")
            return
        }

        LOGGER.info("Processing ${dueDocs.size} pending stock receipts")
        for (doc in dueDocs) {
            try {
                val result = withContext(Dispatchers.IO) {
                    firestore.runTransaction { trx ->
                        processDocument(trx, doc)
                    }.get()
                }
                if (result != null && !result.holdNewStock) {
                    markNotification(result)
                }
            } catch (t: Throwable) {
                LOGGER.error("Failed to process pending ${doc.id}", t)
            }
        }
    }

    private fun processDocument(trx: Transaction, doc: com.google.cloud.firestore.DocumentSnapshot): ProcessingResult? {
        val docRef = doc.reference
        val pendingSnap = trx.get(docRef).get()
        if (!pendingSnap.exists()) {
            return null
        }

        val data = pendingSnap.data ?: return null
        val status = data["status"] as? String ?: "pending"
        if (status != "pending") {
            return null
        }

        val sku = (data["sku"] as? String)?.trim().orEmpty()
        require(sku.isNotBlank()) { "SKU kosong" }

        val qty = (data["qty"] as? Number)?.toLong() ?: 0L
        require(qty > 0) { "Qty invalid" }

        val unitCost = (data["unitCost"] as? Number)?.toLong() ?: 0L
        val newSalePrice = (data["newSalePrice"] as? Number)?.toLong() ?: 0L
        val invoiceNo = (data["invoiceNo"] as? String)?.trim().orEmpty()
        val supplierName = (data["supplierName"] as? String).orEmpty()
        val supplierId = (data["supplierId"] as? String).orEmpty()
        val termDays = (data["termDays"] as? Number)?.toLong() ?: 0L
        val purchaseId = (data["purchaseId"] as? String).orEmpty()
        val productNameFallback = (data["productName"] as? String)?.ifBlank { sku } ?: sku
        val scheduledAt = (data["scheduledAt"] as? Timestamp)
            ?: (data["dueDate"] as? Timestamp)
            ?: Timestamp.now()

        val productRef = firestore.collection(COLLECTION_PRODUCTS).document(sku)
        val productSnap = trx.get(productRef).get()
        require(productSnap.exists()) { "Produk $sku tidak ditemukan" }

        val nowField = FieldValue.serverTimestamp()
        trx.update(docRef, mapOf("status" to "processing", "processingAt" to nowField))

        val productData = productSnap.data ?: emptyMap<String, Any?>()
        val productName = (productData["name"] as? String)?.ifBlank { productNameFallback } ?: productNameFallback
        val stockOld = (productData["stock"] as? Number)?.toLong() ?: 0L
        val newStock = stockOld + qty
        val currentSalePrice = (productData["salePrice"] as? Number)?.toLong() ?: 0L
        val currentCost = (productData["lastCost"] as? Number)?.toLong() ?: unitCost
        val previousIncoming = (productData["stagedIncomingQty"] as? Number)?.toLong() ?: 0L

        val stageCost = qty > 0 && stockOld > 0 && unitCost > 0 && unitCost < currentCost
        val stagePrice = qty > 0 && stockOld > 0 && newSalePrice > 0 && newSalePrice < currentSalePrice
        val holdNewStock = stageCost || stagePrice
        val batchSalePrice = if (newSalePrice > 0) newSalePrice else currentSalePrice

        val productUpdates = mutableMapOf<String, Any?>("updatedAt" to nowField)
        if (holdNewStock) {
            productUpdates["stock"] = stockOld
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
            productUpdates["stagedOldQty"] = stockOld
        }
        trx.update(productRef, productUpdates)

        val batchRef = firestore.collection(COLLECTION_STOCK_BATCHES).document()
        trx.set(batchRef, mapOf(
            "sku" to sku,
            "productName" to productName,
            "unitCost" to unitCost,
            "receivedQty" to qty,
            "remainingQty" to qty,
            "receivedAt" to nowField,
            "purchaseId" to purchaseId,
            "invoiceNo" to invoiceNo,
            "supplierName" to supplierName,
            "supplierId" to supplierId,
            "dueDate" to scheduledAt,
            "termDays" to termDays,
            "state" to if (holdNewStock) "HOLD" else "OPEN",
            "salePrice" to batchSalePrice,
        ))

        val movementRef = firestore.collection(COLLECTION_MOVEMENTS).document()
        trx.set(movementRef, mapOf(
            "sku" to sku,
            "type" to "PURCHASE",
            "qtyDelta" to qty,
            "unitCost" to unitCost,
            "createdAt" to nowField,
            "refId" to purchaseId,
            "batchId" to batchRef.id,
            "note" to if (holdNewStock) {
                "Auto post tertahan menunggu stok lama habis"
            } else {
                "Auto post jatuh tempo"
            }
        ))

        if (purchaseId.isNotBlank()) {
            val poRef = firestore.collection(COLLECTION_PURCHASES).document(purchaseId)
            trx.update(poRef, mapOf(
                "stockPosted" to true,
                "stockPostedAt" to nowField,
                "pendingStockId" to FieldValue.delete()
            ))
        }

        trx.update(docRef, mapOf(
            "status" to "posted",
            "postedAt" to nowField,
            "processingAt" to FieldValue.delete(),
            "notificationSent" to holdNewStock,
            "held" to holdNewStock
        ))

        return ProcessingResult(
            docId = doc.id,
            holdNewStock = holdNewStock,
            productName = productName,
            qty = qty,
            invoiceNo = invoiceNo,
            scheduledAt = scheduledAt,
            sku = sku
        )
    }

    private fun markNotification(result: ProcessingResult) {
        try {
            firestore.collection(COLLECTION_NOTIFICATIONS).document()
                .set(
                    mapOf(
                        "type" to "stock_posted",
                        "sku" to result.sku,
                        "productName" to result.productName,
                        "qty" to result.qty,
                        "invoiceNo" to if (result.invoiceNo.isBlank()) null else result.invoiceNo,
                        "scheduledAt" to result.scheduledAt,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "read" to false
                    )
                ).get()

            firestore.collection(COLLECTION_PENDING).document(result.docId)
                .update("notificationSent", true)
                .get()
        } catch (t: Throwable) {
            LOGGER.error("Gagal membuat notifikasi untuk ${result.docId}", t)
        }
    }
}

private data class ProcessingResult(
    val docId: String,
    val holdNewStock: Boolean,
    val productName: String,
    val qty: Long,
    val invoiceNo: String,
    val scheduledAt: Timestamp,
    val sku: String
)

private const val COLLECTION_PENDING = "pending_stock_receipts"
private const val COLLECTION_PRODUCTS = "products"
private const val COLLECTION_PURCHASES = "purchases"
private const val COLLECTION_STOCK_BATCHES = "stock_batches"
private const val COLLECTION_MOVEMENTS = "inventory_movements"
private const val COLLECTION_NOTIFICATIONS = "notifications"

private val LOGGER = LoggerFactory.getLogger("StockScheduler")





