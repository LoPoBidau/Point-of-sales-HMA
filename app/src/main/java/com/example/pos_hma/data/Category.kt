package com.example.pos_hma.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.Exclude

data class Category(
    @get:Exclude @set:Exclude var id: String = "",
    var name: String = "",
    var slug: String = "",
    var forType: String = "goods",  // "goods"|"service"|"both"
    var isActive: Boolean = true,
    var sortOrder: Long = 0L,
    var createdAt: Timestamp? = null,
    var updatedAt: Timestamp? = null
)
