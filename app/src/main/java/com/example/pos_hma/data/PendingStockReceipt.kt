package com.example.pos_hma.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.ServerTimestamp

// Data class untuk merepresentasikan dokumen dari koleksi 'pending_stock_receipts' di Firestore.
data class PendingStockReceipt(
    val sku: String? = null,
    val productName: String? = null,
    val qty: Long? = null,
    val unitCost: Long? = null,
    val newSalePrice: Long? = null,
    val invoiceNo: String? = null,
    val supplierName: String? = null,
    val supplierId: String? = null,
    val termDays: Long? = null,
    val purchaseId: String? = null,
    val status: String? = "pending", // status bisa: pending, processing, posted
    val held: Boolean? = false,
    val notificationSent: Boolean? = false,
    val dueDate: Timestamp? = null,
    val scheduledAt: Timestamp? = null,
    @ServerTimestamp
    val createdAt: Timestamp? = null,
    @ServerTimestamp
    val processingAt: Timestamp? = null,
    @ServerTimestamp
    val postedAt: Timestamp? = null
)
