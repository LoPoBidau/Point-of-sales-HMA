package com.example.pos_hma.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.IgnoreExtraProperties
import java.util.Date

@IgnoreExtraProperties
data class StockBatch(
    var id: String = "",
    var sku: String = "",
    var productName: String = "",
    var unitCost: Long = 0L,
    var receivedQty: Long = 0L,
    var remainingQty: Long = 0L,
    var receivedAt: Timestamp? = null,
    var purchaseId: String? = null,
    var invoiceNo: String? = null,
    var dueDate: Timestamp? = null,
    var supplierName: String? = null,
    var supplierId: String? = null,
    var termDays: Long = 0L,
    var state: String = BatchState.OPEN.name
) {
    val consumedQty: Long get() = (receivedQty - remainingQty).coerceAtLeast(0)

    fun dueStatus(now: Date = Date()): DueStatus {
        if (state.equals(BatchState.CLEARED.name, ignoreCase = true) || remainingQty <= 0) {
            return DueStatus.CLEARED
        }
        val due = dueDate?.toDate() ?: return DueStatus.NO_DUE
        val todayStart = now.stripTime()
        val todayEnd = todayStart.endOfDay()
        return when {
            due.before(todayStart) -> DueStatus.OVERDUE
            due.after(todayEnd) -> DueStatus.UPCOMING
            else -> DueStatus.DUE_TODAY
        }
    }
}

enum class BatchState { OPEN, CLEARED }

enum class DueStatus { UPCOMING, DUE_TODAY, OVERDUE, NO_DUE, CLEARED }

private fun Date.stripTime(): Date {
    val cal = java.util.Calendar.getInstance()
    cal.time = this
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
    cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    return cal.time
}

private fun Date.endOfDay(): Date {
    val cal = java.util.Calendar.getInstance()
    cal.time = this
    cal.set(java.util.Calendar.HOUR_OF_DAY, 23)
    cal.set(java.util.Calendar.MINUTE, 59)
    cal.set(java.util.Calendar.SECOND, 59)
    cal.set(java.util.Calendar.MILLISECOND, 999)
    return cal.time
}
