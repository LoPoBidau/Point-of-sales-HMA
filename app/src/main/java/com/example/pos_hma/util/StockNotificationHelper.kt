package com.example.pos_hma.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.navigation.NavDeepLinkBuilder
import com.example.pos_hma.R
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.text.NumberFormat
import java.util.Locale

object StockNotificationHelper {
    const val CHANNEL_ID = "stock_auto_posted"

    private val locale = Locale("in", "ID")
    private val numberFormat = NumberFormat.getInstance(locale)

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Stok Otomatis",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifikasi ketika stok otomatis ditambahkan pada tanggal jatuh tempo"
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildMessage(productName: String, qty: Long): String {
        return "$productName bertambah ${numberFormat.format(qty)} unit."
    }

    // Ditambahkan: Fungsi untuk membuat pesan notifikasi stok ditahan
    private fun buildHeldMessage(productName: String, qty: Long): String {
        return "Stok $productName sebanyak ${numberFormat.format(qty)} unit telah diterima namun ditahan. Stok akan tersedia setelah stok lama habis."
    }

    private fun persistInApp(
        db: FirebaseFirestore,
        docKey: String,
        productName: String,
        qty: Long,
        dueDate: Timestamp?,
        sku: String,
        invoiceNo: String?,
        isHeld: Boolean = false // Ditambahkan: Parameter untuk menandai stok ditahan
    ) {
        // Diubah: Judul dan pesan notifikasi disesuaikan jika stok ditahan
        val title = if (isHeld) "Stok baru ditahan" else "Stok otomatis ditambahkan"
        val message = if (isHeld) buildHeldMessage(productName, qty) else buildMessage(productName, qty)

        val data = mutableMapOf<String, Any>(
            "type" to "STOCK_POSTED",
            "title" to title,
            "message" to message,
            "toRole" to "super-admin",
            "read" to false,
            "referenceId" to docKey,
            "sku" to sku,
            "qty" to qty,
            "createdAt" to FieldValue.serverTimestamp()
        )
        dueDate?.let { data["dueDate"] = it }
        invoiceNo?.let { data["invoiceNo"] = it }
        db.collection("notifications")
            .document("sa_stock_posted_$docKey")
            .set(data, SetOptions.merge())
    }

    private fun showSystemNotification(
        context: Context,
        docKey: String,
        productName: String,
        qty: Long,
        isHeld: Boolean = false // Ditambahkan: Parameter untuk menandai stok ditahan
    ): Boolean {
        val notifier = NotificationManagerCompat.from(context)
        if (!notifier.areNotificationsEnabled()) return false

        ensureChannel(context)

        val pendingIntent = NavDeepLinkBuilder(context)
            .setGraph(R.navigation.nav_super_admin)
            .setDestination(R.id.superAdminInventoryFragment)
            .createPendingIntent()

        // Diubah: Judul dan pesan notifikasi disesuaikan jika stok ditahan
        val title = if (isHeld) "Stok baru ditahan" else "Stok otomatis ditambahkan"
        val message = if (isHeld) buildHeldMessage(productName, qty) else buildMessage(productName, qty)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        return try {
            notifier.notify(("stock_posted_$docKey").hashCode(), notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun notifyStageActivated(
        context: Context,
        db: FirebaseFirestore,
        sku: String,
        productName: String,
        newSalePrice: Long?,
        newLastCost: Long?,
        incomingQty: Long
    ): Boolean {
        val parts = mutableListOf<String>()
        if (incomingQty > 0) {
            parts += "Stok baru ${numberFormat.format(incomingQty)} unit siap digunakan."
        }
        newLastCost?.let { if (it > 0) parts += "Modal baru Rp ${numberFormat.format(it)}" }
        newSalePrice?.let { if (it > 0) parts += "Harga jual baru Rp ${numberFormat.format(it)}" }
        val detail = if (parts.isEmpty()) "Stok baru siap digunakan." else parts.joinToString(" ")
        val title = "Stok Baru Aktif"
        val message = "$productName: $detail"

        val data = mutableMapOf<String, Any>(
            "type" to "STOCK_STAGE_ACTIVATED",
            "title" to title,
            "message" to message,
            "toRole" to "super-admin",
            "read" to false,
            "referenceId" to "stage_$sku",
            "sku" to sku,
            "incomingQty" to incomingQty,
            "createdAt" to FieldValue.serverTimestamp()
        )
        db.collection("notifications")
            .document("sa_stage_${sku}_${System.currentTimeMillis()}")
            .set(data, SetOptions.merge())

        val notifier = NotificationManagerCompat.from(context)
        if (!notifier.areNotificationsEnabled()) return false
        ensureChannel(context)
        val pendingIntent = NavDeepLinkBuilder(context)
            .setGraph(R.navigation.nav_super_admin)
            .setDestination(R.id.superAdminInventoryFragment)
            .createPendingIntent()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        return try {
            notifier.notify(("stage_$sku").hashCode(), notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    // Ditambahkan: Fungsi notifikasi untuk stok yang ditahan
    fun notifyStockHeld(
        context: Context,
        db: FirebaseFirestore,
        docKey: String,
        productName: String,
        qty: Long,
        dueDate: Timestamp?,
        sku: String,
        invoiceNo: String?
    ): Boolean {
        persistInApp(db, docKey, productName, qty, dueDate, sku, invoiceNo, isHeld = true)
        return showSystemNotification(context, docKey, productName, qty, isHeld = true)
    }

    fun notifyStockPosted(
        context: Context,
        db: FirebaseFirestore,
        docKey: String,
        productName: String,
        qty: Long,
        dueDate: Timestamp?,
        sku: String,
        invoiceNo: String?
    ): Boolean {
        persistInApp(db, docKey, productName, qty, dueDate, sku, invoiceNo)
        return showSystemNotification(context, docKey, productName, qty)
    }
}