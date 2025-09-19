package com.example.pos_hma.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.IgnoreExtraProperties

@IgnoreExtraProperties
data class Supplier(
    var id: String = "",
    var name: String = "",
    var nameLowercase: String = "",
    var phone: String? = null,
    var address: String? = null,
    var isActive: Boolean = true,
    var paymentTermDays: Long = 0L,
    var createdAt: Timestamp? = null,
    var updatedAt: Timestamp? = null
)

