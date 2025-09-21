package com.example.pos_hma.ui.role.super_admin

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

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
