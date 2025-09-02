package com.example.pos_hma.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.IgnoreExtraProperties

@IgnoreExtraProperties
data class Product(
    var id: String = "",
    var sku: String = "",
    var name: String = "",
    var nameLowercase: String = "",

    var categoryId: String = "",
    var categoryName: String = "",

    // Firestore menyimpan "type" = "goods" / "service"
    var type: String = "goods",
    var trackStock: Boolean = true,

    var stock: Long = 0L,
    var lastCost: Long = 0L,
    var salePrice: Long = 0L,

    var images: List<String> = emptyList(),

    // FIELD YANG HILANG DI LOGCAT
    var isActive: Boolean = true,

    var createdAt: Timestamp? = null,
    var updatedAt: Timestamp? = null
) {
    val isService: Boolean get() = type == "service"
}
