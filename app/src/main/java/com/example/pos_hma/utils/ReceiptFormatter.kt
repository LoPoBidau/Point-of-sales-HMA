package com.example.pos_hma.ui.role.admin.print

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ReceiptFormatter {

    // Lebar karakter kertas 58mm = 32; kertas 80mm = 40
    private const val WIDTH = 32

    // Kolom kanan utk nominal. Sisanya kolom kiri utk label/teks.
    private const val RIGHT_COL = 14
    private const val LEFT_COL = WIDTH - RIGHT_COL

    private val LOCALE_ID = Locale("in", "ID")
    private val CURR = NumberFormat.getInstance(LOCALE_ID)
    private val DATE_FMT = SimpleDateFormat("dd MMM yy HH:mm", LOCALE_ID)

    data class StoreInfo(
        val name: String,
        val address1: String = "",
        val address2: String = "",
        val phone: String = ""
    )

    data class Item(
        val name: String,
        val qty: Long,
        val unitPrice: Long,
        val isService: Boolean = false
    )

    data class SaleInfo(
        val saleId: String,
        val date: Date,
        val total: Long,
        val paid: Long
    ) { val change: Long get() = paid - total }

    // === utils ===
    private fun money(v: Long) = CURR.format(v)
    private fun sep() = "-".repeat(WIDTH)

    private fun center(s: String): String {
        val t = s.take(WIDTH)
        val pad = ((WIDTH - t.length).coerceAtLeast(0)) / 2
        return " ".repeat(pad) + t
    }

    /** Bungkus kata ke beberapa baris sesuai lebar kertas */
    private fun wrap(text: String): List<String> {
        val words = text.trim().split(Regex("\\s+"))
        val out = mutableListOf<String>()
        var line = StringBuilder()
        for (w in words) {
            val extra = if (line.isEmpty()) 0 else 1
            if ((line.length + w.length + extra) > WIDTH) {
                out += line.toString(); line = StringBuilder(w)
            } else {
                if (line.isNotEmpty()) line.append(' ')
                line.append(w)
            }
        }
        if (line.isNotEmpty()) out += line.toString()
        return if (out.isEmpty()) listOf("") else out
    }

    /** Baris dua kolom: kiri teks, kanan nominal (rata kanan) */
    private fun rowAmount(left: String, amount: String): String {
        val leftClean = left.take(LEFT_COL - 1).padEnd(LEFT_COL - 1, ' ')
        return leftClean + " " + amount.take(RIGHT_COL).padStart(RIGHT_COL, ' ')
    }

    private fun moneyRow(label: String, value: Long) =
        rowAmount(label.replaceFirstChar { if (it.isLowerCase()) it.titlecase(LOCALE_ID) else it.toString() }, "Rp ${money(value)}")

    /** Detail item: "qty x harga" di kiri, subtotal di kanan */
    private fun itemDetailRow(qty: Long, price: Long, isService: Boolean): String {
        return if (isService) {
            rowAmount("Jasa x $qty", "")
        } else {
            rowAmount("$qty x ${money(price)}", "Rp ${money(qty * price)}")
        }
    }

    // Info rows with aligned colon (e.g., "No. Nota" and "Waktu")
    private const val INFO_KEY_COL = 10
    private fun wrapWidth(text: String, maxWidth: Int): List<String> {
        val words = text.trim().split(Regex("\\s+"))
        val out = mutableListOf<String>()
        var line = StringBuilder()
        for (w in words) {
            val extra = if (line.isEmpty()) 0 else 1
            if ((line.length + w.length + extra) > maxWidth) {
                out += line.toString(); line = StringBuilder(w)
            } else {
                if (line.isNotEmpty()) line.append(' ')
                line.append(w)
            }
        }
        if (line.isNotEmpty()) out += line.toString()
        return if (out.isEmpty()) listOf("") else out
    }
    private fun infoLines(label: String, value: String): List<String> {
        val key = label.padEnd(INFO_KEY_COL, ' ')
        val prefix = "$key: "
        val rem = (WIDTH - prefix.length).coerceAtLeast(8)
        val parts = wrapWidth(value, rem)
        return if (parts.isEmpty()) listOf(prefix) else parts.mapIndexed { idx, s ->
            if (idx == 0) prefix + s else " ".repeat(prefix.length) + s
        }
    }

    // ========== PLAIN TEXT (untuk printer) ==========
    fun buildForPrinter(
        store: StoreInfo,
        sale: SaleInfo,
        items: List<Item>,
        serviceFee: Long = 0L,
        totalCost: Long? = null,
        unitCost: Long? = null
    ): String {
        val b = StringBuilder()

        // Header toko (nama uppercase, alamat normal)
        b.appendLine(center(store.name.uppercase(Locale.ROOT)))
        if (store.address1.isNotBlank()) wrap(store.address1).forEach { b.appendLine(center(it)) }
        if (store.address2.isNotBlank()) wrap(store.address2).forEach { b.appendLine(center(it)) }
        // Phone line removed per request
        b.appendLine(sep())

        // Info transaksi: No. Nota di atas, lalu waktu (sejajar titik dua)
        infoLines("No. Nota", sale.saleId).forEach { b.appendLine(it) }
        infoLines("Waktu", DATE_FMT.format(sale.date)).forEach { b.appendLine(it) }
        b.appendLine(sep())

        // Items
        items.forEach { itx ->
            wrap(itx.name).forEach { b.appendLine(it) }
            b.appendLine(itemDetailRow(itx.qty, itx.unitPrice, itx.isService))
        }
        val printedItems = items.isNotEmpty()

        // Ringkasan: subtotal (dengan jumlah barang) + biaya service + total
        val goods = items.filter { !it.isService }
        val hasGoods = goods.isNotEmpty()
        val goodsQty = goods.sumOf { it.qty }
        val subtotal = goods.sumOf { it.qty * it.unitPrice }
        if (printedItems) b.appendLine(sep())
        val subLabel = if (hasGoods && goodsQty > 0L) "Subtotal ${goodsQty} Barang" else "Subtotal"
        b.appendLine(moneyRow(subLabel, subtotal))
        if (serviceFee > 0) b.appendLine(moneyRow("Biaya service", serviceFee))
        b.appendLine(moneyRow("Total", sale.total))

        // Seksi pembayaran: Total Bayar & Kembalian
        b.appendLine(sep())
        b.appendLine(moneyRow("Total bayar", sale.paid))
        b.appendLine(moneyRow("Kembalian", sale.change))
        // Section informasi modal (setelah pembayaran, sebelum footer)
        if ((unitCost ?: 0L) > 0L || (totalCost ?: 0L) > 0L) {
            b.appendLine(sep())
            if ((unitCost ?: 0L) > 0L) b.appendLine(moneyRow("Modal/Unit  :", unitCost!!))
            if ((totalCost ?: 0L) > 0L) b.appendLine(moneyRow("Harga modal total", totalCost!!))
            b.appendLine(sep())
        } else {
            b.appendLine(sep())
        }

        // Footer tanpa tanda tangan
        b.appendLine("Perhatian")
        wrap("Barang-barang yang sudah dibeli tidak dapat dikembalikan/ditukar").forEach { b.appendLine(it) }

        return b.toString().trimEnd()
    }

    // ========== SPANNABLE (untuk layar Ã¢â‚¬â€ judul sedikit lebih besar/bold) ==========
    fun buildForScreen(
        store: StoreInfo,
        sale: SaleInfo,
        items: List<Item>,
        serviceFee: Long = 0L,
        totalCost: Long? = null,
        unitCost: Long? = null
    ): CharSequence {
        val txt = buildForPrinter(store, sale, items, serviceFee, totalCost, unitCost)
        val lines = txt.split('\n')
        val sb = SpannableStringBuilder()
        for (i in lines.indices) {
            val start = sb.length
            sb.append(lines[i]).append('\n')
            if (i in 0..2 && lines[i].isNotBlank()) {
                sb.setSpan(StyleSpan(Typeface.BOLD), start, start + lines[i].length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(RelativeSizeSpan(1.22f), start, start + lines[i].length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }
}
