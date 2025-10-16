package com.example.pos_hma.ui.role.admin.print

import android.graphics.Typeface
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.StyleSpan
import android.view.View
import android.widget.TextView
import java.io.Serializable
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ReceiptFormatter {

    private const val MIN_SCREEN_COLUMNS = 24
    private const val MAX_SCREEN_COLUMNS = 64

    private data class FormatConfig(
        val width: Int,
        val rightCol: Int,
        val infoKeyCol: Int = 10
    ) {
        val leftCol: Int = (width - rightCol).coerceAtLeast(8)
        val clampedInfoKeyCol: Int = infoKeyCol.coerceAtLeast(6).coerceAtMost(leftCol - 2)
    }

    private val PRINTER_CONFIG = FormatConfig(width = 32, rightCol = 14, infoKeyCol = 12)
    private val SCREEN_CONFIG_LEGACY = FormatConfig(width = 32, rightCol = 14, infoKeyCol = 12)
    private val SCREEN_CONFIG_MODERN = FormatConfig(width = 36, rightCol = 14, infoKeyCol = 14)

    private fun screenConfig(columnsOverride: Int? = null): FormatConfig {
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) SCREEN_CONFIG_MODERN else SCREEN_CONFIG_LEGACY
        val width = columnsOverride?.coerceIn(MIN_SCREEN_COLUMNS, MAX_SCREEN_COLUMNS) ?: base.width
        val right = base.rightCol.coerceIn(12, width - 8)
        val info = base.infoKeyCol.coerceIn(8, width - right - 2)
        return FormatConfig(width = width, rightCol = right, infoKeyCol = info)
    }

    private val LOCALE_ID = Locale("in", "ID")
    private val CURR = NumberFormat.getInstance(LOCALE_ID)
    private val DATE_FMT = SimpleDateFormat("dd MMM yy HH:mm", LOCALE_ID)

    data class StoreInfo(
        val name: String,
        val address1: String = "",
        val address2: String = "",
        val phone: String = ""
    ) : Serializable

    data class Item(
        val name: String,
        val qty: Long,
        val unitPrice: Long,
        val isService: Boolean = false
    ) : Serializable

    data class SaleInfo(
        val saleId: String,
        val date: Date,
        val total: Long,
        val paid: Long
    ) : Serializable {
        val change: Long get() = paid - total
    }

    data class Payload(
        val store: StoreInfo,
        val sale: SaleInfo,
        val items: List<Item>,
        val serviceFee: Long = 0L,
        val serviceDescription: String? = null,
        val totalCost: Long? = null,
        val unitCost: Long? = null
    ) : Serializable

    private fun money(v: Long) = CURR.format(v)

    private fun sep(cfg: FormatConfig) = "-".repeat(cfg.width)

    private fun center(text: String, cfg: FormatConfig): String {
        val trimmed = text.take(cfg.width)
        val pad = ((cfg.width - trimmed.length).coerceAtLeast(0)) / 2
        return " ".repeat(pad) + trimmed
    }

    private fun wrapToWidth(text: String, width: Int): List<String> {
        if (text.isBlank() || width <= 0) return listOf("")
        val words = text.trim().split(Regex("\\s+"))
        val out = mutableListOf<String>()
        var line = StringBuilder()
        for (word in words) {
            val extra = if (line.isEmpty()) 0 else 1
            if ((line.length + word.length + extra) > width) {
                out += line.toString()
                line = StringBuilder(word)
            } else {
                if (line.isNotEmpty()) line.append(' ')
                line.append(word)
            }
        }
        if (line.isNotEmpty()) out += line.toString()
        if (out.isEmpty()) out += ""
        return out
    }

    private fun fitLines(text: String, width: Int, wrap: Boolean): List<String> {
        if (text.isBlank()) return listOf("")
        return if (wrap) {
            wrapToWidth(text, width)
        } else {
            listOf(ellipsize(text, width))
        }
    }

    private fun ellipsize(text: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        if (text.length <= maxChars) return text
        if (maxChars <= 3) return text.take(maxChars)
        return text.take(maxChars - 3) + "..."
    }

    private fun rowAmount(left: String, amount: String, cfg: FormatConfig): String {
        val leftWidth = cfg.leftCol
        val rightWidth = cfg.rightCol
        val cleanLeft = ellipsize(left.trim(), leftWidth).padEnd(leftWidth, ' ')
        val cleanRight = ellipsize(amount.trim(), rightWidth).padStart(rightWidth, ' ')
        return cleanLeft + cleanRight
    }

    private fun moneyRow(label: String, value: Long, cfg: FormatConfig): String {
        val title = label.replaceFirstChar { if (it.isLowerCase()) it.titlecase(LOCALE_ID) else it.toString() }
        return rowAmount(title, "Rp ${money(value)}", cfg)
    }

    private fun itemDetailRow(qty: Long, price: Long, isService: Boolean, cfg: FormatConfig): String {
        return if (isService) {
            rowAmount("Jasa x $qty", "", cfg)
        } else {
            rowAmount("$qty x ${money(price)}", "Rp ${money(qty * price)}", cfg)
        }
    }

    private fun infoLines(label: String, value: String, cfg: FormatConfig, wrapValue: Boolean): List<String> {
        val trimmedLabel = ellipsize(label.trim(), cfg.clampedInfoKeyCol)
        val key = trimmedLabel.padEnd(cfg.clampedInfoKeyCol, ' ')
        val prefix = "$key: "
        val available = (cfg.width - prefix.length).coerceAtLeast(8)
        val normalizedValue = value.trim()
        if (wrapValue) {
            val parts = wrapToWidth(normalizedValue, available)
            return if (parts.isEmpty()) {
                listOf(prefix.trimEnd().padEnd(cfg.width, ' '))
            } else {
                parts.mapIndexed { index, line ->
                    val segment = if (index == 0) {
                        prefix + line
                    } else {
                        " ".repeat(prefix.length) + line
                    }
                    segment.padEnd(cfg.width, ' ')
                }
            }
        } else {
            val rendered = if (normalizedValue.isEmpty()) {
                prefix.trimEnd()
            } else {
                prefix + ellipsize(normalizedValue, available)
            }
            return listOf(rendered.padEnd(cfg.width, ' '))
        }
    }

    private fun buildReceipt(
        cfg: FormatConfig,
        store: StoreInfo,
        sale: SaleInfo,
        items: List<Item>,
        serviceFee: Long = 0L,
        serviceDescription: String? = null,
        totalCost: Long? = null,
        unitCost: Long? = null,
        wrapItems: Boolean = true,
        wrapMeta: Boolean = true,
        wrapNotice: Boolean = true
    ): String {
        val b = StringBuilder()

        b.appendLine(center(store.name.uppercase(Locale.ROOT), cfg))
        if (store.address1.isNotBlank()) fitLines(store.address1, cfg.width, wrapMeta).forEach { b.appendLine(center(it, cfg)) }
        if (store.address2.isNotBlank()) fitLines(store.address2, cfg.width, wrapMeta).forEach { b.appendLine(center(it, cfg)) }
        b.appendLine(sep(cfg))

        infoLines("No. Nota", sale.saleId, cfg, wrapMeta).forEach { b.appendLine(it) }
        infoLines("Waktu", DATE_FMT.format(sale.date), cfg, wrapMeta).forEach { b.appendLine(it) }
        b.appendLine(sep(cfg))

        items.forEach { item ->
            val nameLines = if (wrapItems) {
                wrapToWidth(item.name, cfg.width)
            } else {
                listOf(ellipsize(item.name, cfg.width))
            }
            nameLines.forEach { b.appendLine(it) }
            b.appendLine(itemDetailRow(item.qty, item.unitPrice, item.isService, cfg))
        }
        if (items.isNotEmpty()) b.appendLine(sep(cfg))

        val goods = items.filterNot { it.isService }
        val goodsQty = goods.sumOf { it.qty }
        val subtotal = goods.sumOf { it.qty * it.unitPrice }
        val hasGoods = goodsQty > 0L
        val serviceDescText = serviceDescription?.trim().orEmpty()
        val hasServiceItems = items.any { it.isService }
        val hasService = (serviceFee > 0L) || hasServiceItems || serviceDescText.isNotEmpty()

        if (hasGoods) {
            val subLabel = "Subtotal ${goodsQty} Barang"
            b.appendLine(moneyRow(subLabel, subtotal, cfg))
        }
        if (serviceFee > 0L) {
            b.appendLine(moneyRow("Biaya jasa", serviceFee, cfg))
        }
        b.appendLine(moneyRow("Total", sale.total, cfg))

        if (serviceDescText.isNotEmpty()) {
            b.appendLine(sep(cfg))
            b.appendLine("Deskripsi jasa")
            fitLines(serviceDescText, cfg.width, wrapNotice).forEach { b.appendLine(it) }
        }

        b.appendLine(sep(cfg))
        b.appendLine(moneyRow("Total bayar", sale.paid, cfg))
        b.appendLine(moneyRow("Kembalian", sale.change, cfg))

        val hasCostInfo = (unitCost ?: 0L) > 0L || (totalCost ?: 0L) > 0L
        if (hasCostInfo) {
            b.appendLine(sep(cfg))
            b.appendLine("Rincian modal")
            if ((unitCost ?: 0L) > 0L) b.appendLine(moneyRow("Modal/Unit", unitCost!!, cfg))
            if ((totalCost ?: 0L) > 0L) b.appendLine(moneyRow("Total modal", totalCost!!, cfg))
        }
        b.appendLine(sep(cfg))

        val closingLines: List<String> = when {
            hasGoods && hasService -> listOf(
                "Barang-barang yang sudah dibeli tidak dapat dikembalikan/ditukar, terima kasih telah menggunakan dan mempercayai jasa bengkel kami"
            )
            hasGoods -> listOf("Barang-barang yang sudah dibeli tidak dapat dikembalikan/ditukar")
            hasService -> listOf("Terima kasih telah menggunakan dan mempercayai jasa bengkel kami")
            else -> listOf("Terima kasih telah menggunakan dan mempercayai jasa bengkel kami")
        }
        closingLines.forEach { message ->
            fitLines(message, cfg.width, wrapNotice).forEach { b.appendLine(it) }
        }

        return b.toString().trimEnd()
    }

    fun buildForPrinter(
        store: StoreInfo,
        sale: SaleInfo,
        items: List<Item>,
        serviceFee: Long = 0L,
        serviceDescription: String? = null,
        totalCost: Long? = null,
        unitCost: Long? = null
    ): String = buildReceipt(
        PRINTER_CONFIG,
        store,
        sale,
        items,
        serviceFee,
        serviceDescription,
        totalCost,
        unitCost,
        wrapItems = true,
        wrapMeta = true,
        wrapNotice = true
    )

    fun buildForPrinter(payload: Payload): String =
        buildForPrinter(
            payload.store,
            payload.sale,
            payload.items,
            payload.serviceFee,
            payload.serviceDescription,
            payload.totalCost,
            payload.unitCost
        )

    fun buildForScreenPlain(
        store: StoreInfo,
        sale: SaleInfo,
        items: List<Item>,
        serviceFee: Long = 0L,
        serviceDescription: String? = null,
        totalCost: Long? = null,
        unitCost: Long? = null,
        columns: Int? = null
    ): String {
        val cfg = screenConfig(columns)
        return buildReceipt(
            cfg,
            store,
            sale,
            items,
            serviceFee,
            serviceDescription,
            totalCost,
            unitCost,
            wrapItems = true,
            wrapMeta = true,
            wrapNotice = true
        )
    }

    fun buildForScreenPlain(
        payload: Payload,
        columns: Int? = null
    ): String = buildForScreenPlain(
        store = payload.store,
        sale = payload.sale,
        items = payload.items,
        serviceFee = payload.serviceFee,
        serviceDescription = payload.serviceDescription,
        totalCost = payload.totalCost,
        unitCost = payload.unitCost,
        columns = columns
    )

    fun buildForScreen(
        store: StoreInfo,
        sale: SaleInfo,
        items: List<Item>,
        serviceFee: Long = 0L,
        serviceDescription: String? = null,
        totalCost: Long? = null,
        unitCost: Long? = null,
        columns: Int? = null
    ): CharSequence = buildForScreen(
        Payload(store, sale, items, serviceFee, serviceDescription, totalCost, unitCost),
        columns
    )

    fun buildForScreen(
        payload: Payload,
        columns: Int? = null
    ): CharSequence {
        val txt = buildForScreenPlain(payload, columns)
        val sb = SpannableStringBuilder()
        val lines = txt.split('\n')
        for (index in lines.indices) {
            val start = sb.length
            sb.append(lines[index])
            if (index < lines.lastIndex) sb.append('\n')
            if (index in 0..2 && lines[index].isNotBlank()) {
                sb.setSpan(StyleSpan(Typeface.BOLD), start, start + lines[index].length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }

    fun estimateColumns(paint: TextPaint, availablePx: Int): Int {
        if (availablePx <= 0) return screenConfig().width
        val charWidth = paint.measureText("0").takeIf { it > 0f } ?: return screenConfig().width
        val raw = (availablePx / charWidth).toInt().coerceAtLeast(1)
        val min = if (raw >= MIN_SCREEN_COLUMNS) MIN_SCREEN_COLUMNS else raw
        return raw.coerceIn(min, MAX_SCREEN_COLUMNS)
    }

    fun estimateColumns(target: TextView): Int {
        val parent = target.parent as? View
        val grandParent = parent?.parent as? View
        val widest = listOfNotNull(grandParent?.width, parent?.width, target.width).maxOrNull() ?: target.width
        val padding = target.paddingLeft + target.paddingRight +
            (parent?.paddingLeft ?: 0) + (parent?.paddingRight ?: 0) +
            (grandParent?.paddingLeft ?: 0) + (grandParent?.paddingRight ?: 0)
        val available = (widest - padding).coerceAtLeast(0)
        return estimateColumns(target.paint, available)
    }
}
