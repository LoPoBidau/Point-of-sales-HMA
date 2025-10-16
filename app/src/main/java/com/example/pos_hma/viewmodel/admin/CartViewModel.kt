package com.example.pos_hma.ui.role.admin

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.MediatorLiveData
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

    // Subtotal barang (tidak termasuk service)
    val goodsSubTotal: LiveData<Long> =
        _lines.map { it.values.filter { l -> !l.product.isService }
            .sumOf { l -> l.product.salePrice * l.qty } }

    // Biaya jasa opsional (ditentukan kasir di keranjang)
    private val _serviceFee = MutableLiveData<Long>(0L)
    val serviceFee: LiveData<Long> = _serviceFee

    private val _serviceDescription = MutableLiveData<String>("")
    val serviceDescription: LiveData<String> = _serviceDescription

    // Total akhir = subtotal barang + biaya service
    val grandTotal: LiveData<Long> = MediatorLiveData<Long>().apply {
        fun recalc() {
            val goods = goodsSubTotal.value ?: 0L
            val svc = serviceFee.value ?: 0L
            value = goods + svc
        }
        addSource(goodsSubTotal) { recalc() }
        addSource(serviceFee) { recalc() }
    }

    fun setServiceFee(amount: Long) {
        _serviceFee.value = amount.coerceAtLeast(0L)
        if (amount <= 0L) {
            _serviceDescription.value = ""
        }
    }

    fun setServiceDescription(text: String) {
        val cleaned = text.trim()
        _serviceDescription.value = cleaned
    }

    fun clearServiceFee() {
        _serviceFee.value = 0L
        _serviceDescription.value = ""
    }

    fun plus(p: Product) {
        val key = p.sku.ifBlank { p.id }
        // Gunakan LinkedHashMap agar urutan stabil
        val map = LinkedHashMap(_lines.value ?: linkedMapOf())
        val current = map[key]?.qty ?: 0
        if (p.trackStock && current >= p.stock) return
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

    fun clear() {
        _lines.value = linkedMapOf()
        _serviceFee.value = 0L
        _serviceDescription.value = ""
    }
}
