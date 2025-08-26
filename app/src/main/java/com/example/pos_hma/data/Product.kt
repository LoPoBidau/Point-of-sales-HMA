package com.example.pos_hma.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.Exclude

data class Product(
    @get:Exclude @set:Exclude var id: String = "",
    var name: String = "",
    var nameLowercase: String = "",
    var sku: String = "",
    var categoryId: String = "",
    var categoryName: String = "",
    var type: String = "goods",         // "goods"|"service"
    var trackStock: Boolean = true,     // service = false
    var stock: Long = 0L,
    var minStock: Long = 0L,
    var lastCost: Long = 0L,            // harga beli terakhir (dipakai COGS ke depan)
    var salePrice: Long = 0L,
    var isActive: Boolean = true,
    var images: List<String> = emptyList(),
    var createdAt: Timestamp? = null,
    var updatedAt: Timestamp? = null
) {
    @get:Exclude
    val isService: Boolean get() = type.equals("service", true) || !trackStock
}
