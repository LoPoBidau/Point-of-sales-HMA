package com.example.pos_hma.data

/**
 * Represents a single row captured inside a sale document. Parsed from the map that is persisted
 * by `AdminCashierPaymentFragment` so the data can be reused by the return workflows.
 */
data class SaleLineItem(
    val sku: String,
    val productId: String?,
    val name: String,
    val qty: Long,
    val unitPrice: Long,
    val unitCost: Long,
    val isService: Boolean,
    val consumptions: List<SaleLineConsumption>
) {
    val isGoods: Boolean get() = !isService
    val lineTotal: Long get() = qty * unitPrice

    companion object {
        fun fromAny(any: Any?): SaleLineItem? {
            val map = any as? Map<*, *> ?: return null
            val sku = (map["sku"] as? String).orEmpty()
            val productId = (map["productId"] as? String)?.takeIf { it.isNotBlank() }
            val name = (map["name"] as? String).orEmpty()
            val qty = (map["qty"] as? Number)?.toLong() ?: 0L
            val unitPrice = (map["unitPrice"] as? Number)?.toLong() ?: 0L
            val unitCost = (map["unitCost"] as? Number)?.toLong() ?: 0L
            val isService = (map["isService"] as? Boolean) ?: false
            val consumptionsRaw = map["consumptions"] as? List<*>
            val consumptions = consumptionsRaw
                ?.mapNotNull { SaleLineConsumption.fromAny(it) }
                ?.ifEmpty { emptyList() }
                ?: emptyList()
            return SaleLineItem(
                sku = sku,
                productId = productId,
                name = name,
                qty = qty,
                unitPrice = unitPrice,
                unitCost = unitCost,
                isService = isService,
                consumptions = consumptions
            )
        }
    }
}

/**
 * Represents a single stock batch consumption that happened during the sale (FIFO records).
 */
data class SaleLineConsumption(
    val batchId: String?,
    val qty: Long,
    val unitCost: Long,
    val salePrice: Long?
) {
    companion object {
        fun fromAny(any: Any?): SaleLineConsumption? {
            val map = any as? Map<*, *> ?: return null
            val qty = (map["qty"] as? Number)?.toLong() ?: return null
            if (qty <= 0L) return null
            val unitCost = (map["unitCost"] as? Number)?.toLong() ?: 0L
            val batchId = (map["batchId"] as? String)?.takeIf { it.isNotBlank() }
            val salePrice = (map["salePrice"] as? Number)?.toLong()
            return SaleLineConsumption(
                batchId = batchId,
                qty = qty,
                unitCost = unitCost,
                salePrice = salePrice
            )
        }
    }
}

fun List<*>?.toSaleLineItems(): List<SaleLineItem> {
    if (this == null) return emptyList()
    return mapNotNull { SaleLineItem.fromAny(it) }
}
