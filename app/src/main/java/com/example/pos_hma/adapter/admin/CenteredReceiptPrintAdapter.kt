package com.example.pos_hma.ui.role.admin.print

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.pdf.PrintedPdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.FileOutputStream

/**
 * Mencetak teks monospasi (hasil ReceiptFormatter) dan
 * memusatkan *blok* struk di tengah halaman apa pun.
 *
 * @param desiredContentWidthMm Lebar blok konten (mm).
 *   58mm roll  → 58f
 *   80mm roll  → 72f–76f (ada margin tepi)
 */
class CenteredReceiptPrintAdapter(
    private val context: Context,
    private val text: String,
    private val desiredContentWidthMm: Float = 72f
) : PrintDocumentAdapter() {

    private var pdf: PrintedPdfDocument? = null

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?
    ) {
        pdf?.close()
        pdf = PrintedPdfDocument(context, newAttributes)
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled(); return
        }
        callback.onLayoutFinished(
            PrintDocumentInfo.Builder("receipt.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(1)
                .build(),
            true
        )
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback
    ) {
        val doc = pdf ?: return callback.onWriteFailed("No document")
        val page = doc.startPage(0)

        val canvas = page.canvas
        val pageW = page.info.pageWidth.toFloat()
        val pageH = page.info.pageHeight.toFloat()
        val guard = 12f

        val desiredPt = desiredContentWidthMm / 25.4f * 72f
        val contentW = desiredPt.coerceAtMost(pageW - guard * 2)

        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10.5f
            color = Color.BLACK
        }

        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, contentW.toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()

        val startX = ((pageW - contentW) / 2f).coerceAtLeast(guard)
        // pusat vertikal; bila kepanjangan, jatuhkan ke guard atas
        val startY = (((pageH - layout.height) / 2f).coerceAtLeast(guard))

        canvas.save()
        canvas.translate(startX, startY)
        layout.draw(canvas)
        canvas.restore()

        doc.finishPage(page)
        runCatching {
            doc.writeTo(FileOutputStream(destination.fileDescriptor))
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        }.onFailure { callback.onWriteFailed(it.message) }
            .also { doc.close(); pdf = null }
    }
}
