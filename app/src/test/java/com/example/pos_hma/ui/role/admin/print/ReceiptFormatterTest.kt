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

    private val serviceDesc = "Servis umum kendaraan"

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
            serviceFee = 10_000,
            serviceDescription = serviceDesc
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
        assertTrue("Service description header should be printed", receipt.contains("Deskripsi jasa"))
        assertTrue("Service description text should be printed", normalizeWhitespace(receipt).contains(serviceDesc))
        assertTrue("Modal section header should be printed when cost exists", receipt.contains("Rincian modal"))
        assertTrue("Modal per unit should be printed", receipt.contains("Modal/Unit"))
        assertTrue(
            "Combined closing message should appear",
            normalizeWhitespace(receipt).contains(
                "Barang-barang yang sudah dibeli tidak dapat dikembalikan/ditukar, terima kasih telah menggunakan dan mempercayai jasa bengkel kami"
            )
        )
    }

    @Test
    fun printerReceipt_wrapsLongItemNamesWithoutEllipsis() {
        val receipt = ReceiptFormatter.buildForPrinter(
            store = store,
            sale = sale,
            items = items,
            serviceFee = 10_000,
            serviceDescription = serviceDesc
        )
        val normalizedPrinter = normalizeWhitespace(receipt)

        val lines = receipt.split('\n')
        lines.forEach { line ->
            assertTrue("Printer line too long (${line.length}): '$line'", line.length <= 32)
        }

        val wrappedNameLines = lines.filter { it.contains("Produk Sangat Panjang") || it.contains("Karakter") }
        assertTrue("Expected long name to wrap across lines", wrappedNameLines.size >= 2)
        assertFalse("Printer output should not ellipsize long names", wrappedNameLines.any { it.contains("...") })
        assertTrue(
            "Service description header should be printed",
            normalizedPrinter.contains("Deskripsi jasa")
        )
        assertTrue(
            "Service description text should be printed",
            normalizedPrinter.contains(serviceDesc)
        )
        val feeIndex = lines.indexOfFirst { it.contains("Biaya jasa") }
        val descHeaderIndex = lines.indexOfFirst { it.contains("Deskripsi jasa") }
        val descTextIndex = lines.indexOfFirst { it.contains(serviceDesc) }
        val modalHeaderIndex = lines.indexOfFirst { it.contains("Rincian modal") }
        val modalUnitIndex = lines.indexOfFirst { it.contains("Modal/Unit") }
        val totalModalIndex = lines.indexOfFirst { it.contains("Total modal") }
        assertTrue("Description header should appear after service fee row", descHeaderIndex > feeIndex && feeIndex >= 0)
        assertTrue("Description text should appear after description header", descTextIndex > descHeaderIndex && descHeaderIndex >= 0)
        assertTrue("Modal section header should appear after payment rows", modalHeaderIndex > lines.indexOfFirst { it.contains("Kembalian") } && modalHeaderIndex >= 0)
        assertTrue("Modal per unit should appear in modal section", modalUnitIndex > modalHeaderIndex && modalHeaderIndex >= 0)
        assertTrue("Total modal should appear in modal section", totalModalIndex > modalHeaderIndex && modalHeaderIndex >= 0)
        assertTrue(
            "Combined closing message should appear",
            normalizedPrinter.contains("Barang-barang yang sudah dibeli tidak dapat dikembalikan/ditukar, terima kasih telah menggunakan dan mempercayai jasa bengkel kami")
        )
    }

    @Test
    fun serviceOnlyReceipt_hidesGoodsSubtotal() {
        val serviceOnlySale = sale.copy(total = 50_000, paid = 50_000)
        val receipt = ReceiptFormatter.buildForPrinter(
            store = store,
            sale = serviceOnlySale,
            items = emptyList(),
            serviceFee = 50_000,
            serviceDescription = "Cuci AC"
        )
        val lines = receipt.split('\n')
        assertFalse("Subtotal should be hidden when only service items exist", lines.any { it.contains("Subtotal") })
        assertTrue("Service description header should be visible", normalizeWhitespace(receipt).contains("Deskripsi jasa"))
        assertTrue("Service description text should be visible", normalizeWhitespace(receipt).contains("Cuci AC"))
        assertTrue(
            "Service-only closing message should appear",
            normalizeWhitespace(receipt).contains("Terima kasih telah menggunakan dan mempercayai jasa bengkel kami")
        )
        assertFalse(
            "Service-only receipt should not mention barang warning",
            normalizeWhitespace(receipt).contains("Barang-barang yang sudah dibeli tidak dapat dikembalikan/ditukar")
        )
    }

    private fun normalizeWhitespace(value: String): String =
        value.replace("\\s+".toRegex(), " ").trim()
}
