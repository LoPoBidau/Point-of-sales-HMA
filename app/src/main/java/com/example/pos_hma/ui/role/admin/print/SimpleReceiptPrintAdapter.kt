package com.example.pos_hma.ui.role.admin.print

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import com.example.pos_hma.utils.toUserMessage
import java.io.FileOutputStream

class SimpleReceiptPrintAdapter(
    private val context: Context,
    private val content: String
) : PrintDocumentAdapter() {

    private var pdfDocument: PdfDocument? = null

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: android.os.Bundle?
    ) {
        pdfDocument = PdfDocument()
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder("receipt.pdf")
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .build()
        callback.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pageRanges: Array<out PageRange>?,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback
    ) {
        val pageInfo = PdfDocument.PageInfo.Builder(300, 600, 1).create()
        val page = pdfDocument!!.startPage(pageInfo)
        val canvas = page.canvas
        val paint = Paint().apply {
            isAntiAlias = true
            textSize = 10f
        }

        val lines = content.split('\n')
        var y = 14f
        lines.forEach { line ->
            canvas.drawText(line, 10f, y, paint)
            y += 14f
        }

        pdfDocument!!.finishPage(page)

        try {
            FileOutputStream(destination.fileDescriptor).use { out ->
                pdfDocument!!.writeTo(out)
            }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback.onWriteFailed(e.toUserMessage("Gagal mencetak dokumen."))
        } finally {
            pdfDocument?.close()
            pdfDocument = null
        }
    }
}
