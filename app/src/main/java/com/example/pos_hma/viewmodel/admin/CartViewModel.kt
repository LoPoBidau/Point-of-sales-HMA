package com.example.pos_hma.ui.role.admin

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.map
import com.example.pos_hma.data.Product

data class CartLine(val product: Product, val qty: Int)

class CartViewModel : ViewModel() {

    // Simpan sebagai Map supaya kompatibel dengan LiveData<Map<…>>
    private val _lines = MutableLiveData<Map<String, CartLine>>(linkedMapOf())
    val lines: LiveData<Map<String, CartLine>> = _lines

    val totalItems: LiveData<Int> =
        _lines.map { it.values.sumOf { line -> line.qty } }

    val totalAmount: LiveData<Long> =
        _lines.map { it.values.filter { l -> !l.product.isService }
            .sumOf { l -> l.product.salePrice * l.qty } }

    fun plus(p: Product) {
        val key = p.sku.ifBlank { p.id }
        // Gunakan LinkedHashMap agar urutan stabil
        val map = LinkedHashMap(_lines.value ?: linkedMapOf())
        val current = map[key]?.qty ?: 0
        if (p.isService) {
            if (current >= 1) return
        } else {
            if (current >= p.stock) return
        }
        map[key] = CartLine(p, current + 1)
        _lines.value = map        // OK: LinkedHashMap adalah Map
    }

    fun minus(p: Product) {
        val key = p.sku.ifBlank { p.id }
        val map = LinkedHashMap(_lines.value ?: linkedMapOf())
        val current = map[key]?.qty ?: 0
        if (current <= 1) map.remove(key) else map[key] = CartLine(p, current - 1)
        _lines.value = map
    }

    fun clear() { _lines.value = linkedMapOf() }
}
