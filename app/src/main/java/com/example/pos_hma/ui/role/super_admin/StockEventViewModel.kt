package com.example.pos_hma.ui.role.super_admin

// Noted:
// ViewModel ringan ini menjadi “pengeras suara” untuk semua perubahan stok yang terjadi di modul super admin. Dengan cara tersebut,
// setiap fragment (produk, penyesuaian, laporan) tidak perlu saling referensi, cukup mengamati event LiveData yang disediakan.
// Ketika sebuah aksi stok selesai (approve penyesuaian, terima barang, dll) cukup memanggil emitAdjustmentEvent sehingga seluruh UI yang relevan ikut memperbarui data.

// Class Note:
// - Menyimpan MutableLiveData<Boolean> bernama adjustmentEvent.
// - emitAdjustmentEvent() akan mengirim nilai true dan segera mereset ke false supaya observer hanya bereaksi sekali per event.
// - Fragment apa pun cukup memanggil stockEventVm.adjustmentEvent.observe(...) untuk mengikat ulang query ketika stok berubah.

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

// Class Note (Deklarasi):
// Kelas ini hanya berisi dua SharedFlow (purchase & adjustment). Fragment lain cukup memanggil emitPurchaseEvent()/emitAdjustmentEvent()
// setelah transaksi selesai agar semua observer bisa memanggil ulang query mereka.
class StockEventViewModel : ViewModel() {
    private val _purchaseEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val purchaseEvents = _purchaseEvents.asSharedFlow()

    private val _adjustmentEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val adjustmentEvents = _adjustmentEvents.asSharedFlow()

    fun emitPurchaseEvent() {
        _purchaseEvents.tryEmit(Unit)
    }

    fun emitAdjustmentEvent() {
        _adjustmentEvents.tryEmit(Unit)
    }
}
