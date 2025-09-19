package com.example.pos_hma.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.IgnoreExtraProperties

@IgnoreExtraProperties
data class StockBatch(
    var id: String = "",
    var sku: String = "",
    var unitCost: Long = 0L,
    var remainingQty: Long = 0L,
    var receivedAt: Timestamp? = null,
    var purchaseId: String? = null,
    var invoiceNo: String? = null,
    var dueDate: Timestamp? = null
)

