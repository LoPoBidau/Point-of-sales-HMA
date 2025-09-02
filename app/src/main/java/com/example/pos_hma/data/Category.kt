package com.example.pos_hma.data

import com.google.firebase.firestore.IgnoreExtraProperties

@IgnoreExtraProperties
data class Category(
    var id: String = "",
    var name: String = "",
    var slug: String = "",
    var forType: String = "both", // goods / service / both

    // Tambahkan field yang memicu warning di logcat
    var isActive: Boolean = true,
    var nameLowercase: String = "",

    // Proyek terbaru pakai sortOrder; beberapa dokumen lama pakai "order"
    var sortOrder: Long = 0L,
    var order: Long? = null
) {
    // Biar aman saat sort, kalau sortOrder kosong pakai order lama
    val effectiveOrder: Long get() = if (sortOrder != 0L) sortOrder else (order ?: 0L)
}
