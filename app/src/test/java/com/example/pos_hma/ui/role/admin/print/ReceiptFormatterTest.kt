package com.example.pos_hma.ui.role.admin.print

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class ReceiptFormatterTest {

    private val store = ReceiptFormatter.StoreInfo(
        name = "Toko Contoh",
        address1 = "Jl. Raya Contoh No. 123",
        phone = "081234567890"
    )

    private val sale = ReceiptFormatter.SaleInfo(
        saleId = "INV-20231008-XYZ",
        date = Date(1700000000000L),
        total = 150_000,
        paid = 200_000
    )

    private val items = listOf(
        ReceiptFormatter.Item(
            name = "Produk Sangat Panjang Sekali Hingga Lebih Dari Tiga Puluh Dua Karakter",
            qty = 2,
            unitPrice = 25_000
        ),
        ReceiptFormatter.Item(
            name = "Layanan Servis Premium",
            qty = 1,
            unitPrice = 50_000,
            isService = true
        )
    )

    @Test
    fun screenReceipt_keepsLinesWithinWidth() {
        val receipt = ReceiptFormatter.buildForScreenPlain(
            store = store,
            sale = sale,
            items = items,
            serviceFee = 10_000
        )

        val lines = receipt.split('\n')
        lines.forEach { line ->
            assertTrue("Screen line too long (${line.length}): '$line'", line.length <= 36)
        }

        val amountLines = lines.filter { it.contains("Rp") }
        amountLines.forEach { line ->
            val rightSegment = line.takeLast(14)
            assertTrue(
                "Amount column misaligned for '$line'",
                rightSegment.trimStart().startsWith("Rp")
            )
        }
    }

    @Test
    fun printerReceipt_wrapsLongItemNamesWithoutEllipsis() {
        val receipt = ReceiptFormatter.buildForPrinter(
            store = store,
            sale = sale,
            items = items,
            serviceFee = 10_000
        )

        val lines = receipt.split('\n')
        lines.forEach { line ->
            assertTrue("Printer line too long (${line.length}): '$line'", line.length <= 32)
        }

        val wrappedNameLines = lines.filter { it.contains("Produk Sangat Panjang") || it.contains("Karakter") }
        assertTrue("Expected long name to wrap across lines", wrappedNameLines.size >= 2)
        assertFalse("Printer output should not ellipsize long names", wrappedNameLines.any { it.contains("...") })
    }
}
