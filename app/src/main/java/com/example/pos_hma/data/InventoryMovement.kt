package com.example.pos_hma.data

import com.google.firebase.Timestamp

data class InventoryMovement(
    var id: String = "",
    var sku: String = "",
    var type: String = "",           // PURCHASE, ADJUSTMENT, …
    var qtyDelta: Long = 0,
    var unitCost: Long = 0,
    var createdAt: Timestamp? = null,
    var note: String? = null,
    var refId: String? = null
)
