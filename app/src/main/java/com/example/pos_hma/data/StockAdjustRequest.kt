package com.example.pos_hma.data

import com.google.firebase.Timestamp

data class StockAdjustRequest(
    var id: String = "",
    var sku: String = "",
    var productName: String = "",
    var requestedDelta: Long = 0,     // +3 / -3
    var reason: String = "",
    var status: String = "PENDING",   // PENDING, APPROVED, REJECTED
    var requestedBy: String = "",
    var createdAt: Timestamp? = null,
    var decidedAt: Timestamp? = null,
    var decidedBy: String? = null
)
