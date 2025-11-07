package com.example.pos_hma.ui.role.super_admin

// Noted: Activity utama super admin yang mengatur navigasi bottom tabs serta lifecycle global role super admin.

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.activity.OnBackPressedCallback
import androidx.core.app.NotificationManagerCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.navigation.NavController
import androidx.navigation.NavDeepLinkBuilder
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.NavOptions
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.NavigationUI
import com.example.pos_hma.R
import com.example.pos_hma.databinding.ActivitySuperAdminMainBinding
import com.example.pos_hma.data.SaleReturnStatus
import com.example.pos_hma.ui.login.LoginActivity
import com.example.pos_hma.utils.AppFlags
import com.example.pos_hma.utils.SnapshotDisposable
import com.example.pos_hma.utils.NetworkUtil
import com.google.android.material.button.MaterialButton
import com.example.pos_hma.ui.role.super_admin.SuperAdminRequestsFragment.Companion.TAB_STOCK_ADJUST
import com.example.pos_hma.ui.role.super_admin.SuperAdminRequestsFragment.Companion.TAB_RETURNS
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import de.hdodenhof.circleimageview.CircleImageView

// Class Note:
// - Menjadi host NavController untuk seluruh tab super admin (Dashboard, Inventori, Produk, dll) dan menyiapkan bottom navigation + toolbar.
// - Mengatur izin penting seperti notification/Bluetooth printer serta menangani logout jika token kadaluarsa.
// - Mengelola back press supaya pengguna tidak keluar tanpa disengaja dan menjaga state fragment via AppBarConfiguration.
// Class Note (Deklarasi):
// Merangkum semua tanggung jawab Activity super admin, mulai dari nav graph, permission, sampai monitoring notifikasi/pending request.
class SuperAdminMainActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySuperAdminMainBinding
    private lateinit var navController: NavController
    private lateinit var appBarConfig: AppBarConfiguration
    private val db by lazy { FirebaseFirestore.getInstance() }
    private var regPending: ListenerRegistration? = null
    private var regReturn: ListenerRegistration? = null
    private var regNotif: ListenerRegistration? = null
    private var firstPendingLoad = true
    private var firstReturnLoad = true
    private var firstNotifLoad = true
    private var updatingBottomNav = false
    private var pendingAdjustCount: Int = 0
    private var pendingReturnCount: Int = 0
    private val CHANNEL_ID = "adjust_req"
    private val CHANNEL_SALES = "sale_success"
    private var unreadNotifCount: Int = 0
    private var tvNotifBadge: TextView? = null
    private var netCb: android.net.ConnectivityManager.NetworkCallback? = null
    private val notifPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            try {
                android.widget.Toast.makeText(this, "Izin notifikasi ditolak", android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySuperAdminMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val baseToolbarPadding = binding.toolbar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = baseToolbarPadding + systemBars.top)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNav) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = systemBars.bottom)
            insets
        }
        val baseContentPadding = binding.navHostOwner.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.navHostOwner) { view, insets ->
            view.updatePadding(bottom = baseContentPadding)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)

        // Jika tidak ada internet, kembali ke login
        if (!NetworkUtil.isOnline(this)) {
            android.widget.Toast.makeText(this, "Tidak ada akses internet", android.widget.Toast.LENGTH_SHORT).show()
            goLogin()
            return
        }

        setSupportActionBar(binding.toolbar)
        maybeRequestPostNotifications()

        ensureRoleAllowedOrExit(setOf("owner", "super-admin", "superadmin")) {
            val host = supportFragmentManager.findFragmentById(R.id.nav_host_owner) as NavHostFragment
            navController = host.navController
            navController.setGraph(R.navigation.nav_super_admin)

            val topLevelDestinations = setOf(
                R.id.superAdminDashboardFragment,
                R.id.superAdminInventoryFragment,
                R.id.superAdminRequestsFragment,
                R.id.superAdminReportFragment
            )

            appBarConfig = AppBarConfiguration(topLevelDestinations)

            NavigationUI.setupWithNavController(binding.toolbar, navController, appBarConfig)

            val navOpts = NavOptions.Builder()
                .setLaunchSingleTop(true)
                .setRestoreState(true)
                .setPopUpTo(navController.graph.startDestinationId, false)
                .build()
            binding.bottomNav.setOnItemSelectedListener { item ->
                if (updatingBottomNav) return@setOnItemSelectedListener true
                val destId = when (item.itemId) {
                    R.id.superAdminDashboardFragment -> R.id.superAdminDashboardFragment
                    R.id.superAdminInventoryFragment -> R.id.superAdminInventoryFragment
                    R.id.superAdminRequestsFragment -> R.id.superAdminRequestsFragment
                    R.id.superAdminReportFragment -> R.id.superAdminReportFragment
                    else -> return@setOnItemSelectedListener false
                }
                if (navController.currentDestination?.id == destId) {
                    true
                } else {
                    try {
                        navController.navigate(destId, null, navOpts)
                        true
                    } catch (_: IllegalArgumentException) {
                        false
                    }
                }
            }
            binding.bottomNav.setOnItemReselectedListener { /* no-op */ }
            updatingBottomNav = true
            binding.bottomNav.selectedItemId = navController.currentDestination?.id ?: R.id.superAdminDashboardFragment
            updatingBottomNav = false

            reschedulePendingStockReceipts()

            navController.addOnDestinationChangedListener { _, dest, _ ->
                invalidateOptionsMenu()
                val hideBottomOn = setOf(R.id.superAdminNotificationFragment)
                binding.bottomNav.isVisible = dest.id !in hideBottomOn
                if (dest.id in topLevelDestinations) {
                    updatingBottomNav = true
                    binding.bottomNav.selectedItemId = dest.id
                    updatingBottomNav = false
                }
            }

            createNotificationChannel()
            startPendingRequestListener()
            startPendingReturnListener()
            startNotifBadgeListener()
        }

        // Konfirmasi keluar saat berada di root (tidak ada back stack untuk dipop)
        onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (this@SuperAdminMainActivity::navController.isInitialized && navController.popBackStack()) return
                MaterialAlertDialogBuilder(this@SuperAdminMainActivity)
                    .setTitle("Tutup aplikasi?")
                    .setMessage("Apakah Anda yakin ingin keluar dari aplikasi?")
                    .setNegativeButton("Batal", null)
                    .setPositiveButton("Keluar") { _, _ -> finishAffinity() }
                    .show()
            }
        })

        // Observasi konektivitas: jika hilang, paksa balik ke Login
        netCb = NetworkUtil.registerNetworkCallback(
            context = this,
            onAvailable = { /* no-op */ },
            onLost = {
                runOnUiThread {
                    android.widget.Toast.makeText(this@SuperAdminMainActivity, "Tidak ada akses internet", android.widget.Toast.LENGTH_SHORT).show()
                    goLogin()
                }
            }
        )
    }

    override fun onStart() {
        super.onStart()
        // pastikan listener boleh jalan
        AppFlags.isLoggingOut = false
        // Opsional: kirim notifikasi ringkasan bulan ini sekali per hari
        maybeNotifyMonthlySummary()
    }

    override fun onResume() {
        super.onResume()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val pref = getSharedPreferences("notif_prefs", MODE_PRIVATE)
            val flagged = pref.getBoolean("needs_notif_permission", false)
            if (!canPostNotifications() || flagged) {
                pref.edit().putBoolean("needs_notif_permission", false).apply()
                maybeRequestPostNotifications(force = true)
            }
        }
    }

    override fun onDestroy() {
        disposeSelfSnapshotListeners()
        // Unregister network callback if registered
        NetworkUtil.unregisterNetworkCallback(this, netCb)
        netCb = null
        super.onDestroy()
    }

    // ===== AppBar avatar bulat =====
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_super_admin_appbar, menu)
        val profileItem = menu.findItem(R.id.action_profile)
        val actionView: View? = profileItem.actionView
        val ivAvatar = actionView?.findViewById<CircleImageView>(R.id.ivAvatar)
        actionView?.setOnClickListener { showProfileDialog() }
        ivAvatar?.setOnClickListener { showProfileDialog() }
        // Tint profile icon based on theme (dark -> light tint, light -> dark tint)
        try {
            val color = com.google.android.material.color.MaterialColors.getColor(binding.toolbar, com.google.android.material.R.attr.colorOnSurface)
            ivAvatar?.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
        } catch (_: Throwable) { }
        // Tint notification icon according to theme
        val notifItem = menu.findItem(R.id.action_notifications)
        // Hide notif & profile icons on Notification fragment
        val destId = if (::navController.isInitialized) navController.currentDestination?.id else null
        val isOnNotif = destId == R.id.superAdminNotificationFragment
        notifItem.isVisible = !isOnNotif
        profileItem.isVisible = !isOnNotif

        // Wire action view for notifications (icon + badge)
        val notifView: View? = notifItem.actionView
        notifView?.setOnClickListener { navController.navigate(R.id.superAdminNotificationFragment) }
        tvNotifBadge = notifView?.findViewById<TextView>(R.id.tvNotifBadge)
        // Optional: ensure icon tint matches theme
        try {
            val iv = notifView?.findViewById<android.widget.ImageView>(R.id.ivNotif)
            val tint = com.google.android.material.color.MaterialColors.getColor(binding.toolbar, com.google.android.material.R.attr.colorOnSurface)
            iv?.setColorFilter(tint)
        } catch (_: Throwable) {}
        // Apply current unread count to badge
        updateNotifBadgeUI()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_notifications -> { navController.navigate(R.id.superAdminNotificationFragment); true }
            R.id.action_profile -> { showProfileDialog(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showProfileDialog() {
        val user = FirebaseAuth.getInstance().currentUser ?: run { goLogin(); return }
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_owner_profile, null, false)
        val tvEmail = v.findViewById<TextView>(R.id.tvEmails)
        val tvRole  = v.findViewById<TextView>(R.id.tvRoles)
        val btnLogout = v.findViewById<MaterialButton>(R.id.btnLogouts)

        tvEmail.text = user.email ?: "-"

        tvRole.text = "Loading..."
        user.getIdToken(false)
            .addOnSuccessListener { tok ->
                val r = normalize(tok.claims["role"] as? String)
                if (!r.isNullOrEmpty()) {
                    tvRole.text = prettyRole(r)
                } else {
                    FirebaseFirestore.getInstance().collection("users").document(user.uid).get()
                        .addOnSuccessListener { s -> tvRole.text = prettyRole(normalize(s.getString("role"))) }
                        .addOnFailureListener { tvRole.text = "-" }
                }
            }
            .addOnFailureListener {
                FirebaseFirestore.getInstance().collection("users").document(user.uid).get()
                    .addOnSuccessListener { s -> tvRole.text = prettyRole(normalize(s.getString("role"))) }
                    .addOnFailureListener { tvRole.text = "-" }
            }

        val dlg = MaterialAlertDialogBuilder(this)
            .setView(v)
            .setPositiveButton("Tutup", null)
            .create()

        btnLogout.setOnClickListener {
            // cegah listener nge-log error saat signOut
            AppFlags.isLoggingOut = true
            disposeSelfSnapshotListeners()
            disposeAllChildSnapshotListeners()
            FirebaseAuth.getInstance().signOut()
            dlg.dismiss()
            goLogin()
            // tidak perlu reset flag di sini karena activity akan finish
        }

        dlg.show()
    }

    private fun disposeAllChildSnapshotListeners() {
        // dispose di semua fragment (navhost & anak-anak)
        val roots = supportFragmentManager.fragments
        for (f in roots) {
            (f as? SnapshotDisposable)?.disposeSnapshots()
            val childs = f.childFragmentManager.fragments
            for (c in childs) {
                (c as? SnapshotDisposable)?.disposeSnapshots()
                val gchilds = c.childFragmentManager.fragments
                for (gc in gchilds) {
                    (gc as? SnapshotDisposable)?.disposeSnapshots()
                }
            }
        }
    }

    private fun disposeSelfSnapshotListeners() {
        regPending?.remove(); regPending = null
        regReturn?.remove(); regReturn = null
        regNotif?.remove(); regNotif = null
    }

    private fun ensureRoleAllowedOrExit(allowed: Set<String>, onOk: () -> Unit) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) { goLogin(); return }

        user.getIdToken(false)
            .addOnSuccessListener { tok ->
                val claimsRole = normalize(tok.claims["role"] as? String)
                if (claimsRole != null && allowed.contains(claimsRole)) {
                    onOk()
                } else {
                    FirebaseFirestore.getInstance().collection("users").document(user.uid).get()
                        .addOnSuccessListener { snap ->
                            val fsRole = normalize(snap.getString("role"))
                            if (fsRole != null && allowed.contains(fsRole)) onOk() else goLogin()
                        }
                        .addOnFailureListener { goLogin() }
                }
            }
            .addOnFailureListener {
                FirebaseFirestore.getInstance().collection("users").document(user.uid).get()
                    .addOnSuccessListener { snap ->
                        val fsRole = normalize(snap.getString("role"))
                        if (fsRole != null && allowed.contains(fsRole)) onOk() else goLogin()
                    }
                    .addOnFailureListener { goLogin() }
            }
    }

    private fun startPendingRequestListener() {
        regPending?.remove(); regPending = null
        firstPendingLoad = true
        regPending = db.collection("stock_adjust_requests")
            .whereEqualTo("status", "PENDING")
            .addSnapshotListener { snap, e ->
                if (AppFlags.isLoggingOut) return@addSnapshotListener
                if (e != null || snap == null) return@addSnapshotListener

                pendingAdjustCount = snap.size()
                updateRequestsBadge()

                // Notify on newly added docs (skip initial load)
                if (!firstPendingLoad) {
                    for (chg in snap.documentChanges) {
                        if (chg.type == DocumentChange.Type.ADDED) notifyNewRequest(chg.document)
                    }
                }
                firstPendingLoad = false
            }
    }

    private fun startPendingReturnListener() {
        regReturn?.remove(); regReturn = null
        firstReturnLoad = true
        regReturn = db.collection("sale_return_requests")
            .whereEqualTo("status", SaleReturnStatus.PENDING)
            .addSnapshotListener { snap, e ->
                if (AppFlags.isLoggingOut) return@addSnapshotListener
                if (e != null || snap == null) return@addSnapshotListener

                pendingReturnCount = snap.size()
                updateRequestsBadge()

                if (!firstReturnLoad) {
                    for (chg in snap.documentChanges) {
                        if (chg.type == DocumentChange.Type.ADDED) notifyNewReturnRequest(chg.document)
                    }
                }
                firstReturnLoad = false
            }
    }

    private fun updateRequestsBadge() {
        val total = pendingAdjustCount + pendingReturnCount
        val badge = binding.bottomNav.getOrCreateBadge(R.id.superAdminRequestsFragment)
        if (total > 0) {
            badge.isVisible = true
            try { badge.clearNumber() } catch (_: Throwable) { }
            try {
                val color = com.google.android.material.color.MaterialColors.getColor(binding.bottomNav, com.google.android.material.R.attr.colorError)
                badge.backgroundColor = color
            } catch (_: Throwable) { }
        } else {
            badge.isVisible = false
        }
    }

    fun showReportBadge() {
        val badge = binding.bottomNav.getOrCreateBadge(R.id.superAdminReportFragment)
        badge.isVisible = true
        try { badge.clearNumber() } catch (_: Throwable) { }
        try {
            val color = com.google.android.material.color.MaterialColors.getColor(binding.bottomNav, com.google.android.material.R.attr.colorPrimary)
            badge.backgroundColor = color
        } catch (_: Throwable) { }
    }

    private fun startNotifBadgeListener() {
        regNotif?.remove(); regNotif = null
        firstNotifLoad = true
        regNotif = db.collection("notifications")
            .whereEqualTo("toRole", "super-admin")
            .whereEqualTo("read", false)
            .addSnapshotListener { snap, e ->
                if (AppFlags.isLoggingOut) return@addSnapshotListener
                if (e != null) return@addSnapshotListener
                unreadNotifCount = snap?.size() ?: 0
                updateNotifBadgeUI()
                if (snap != null && !firstNotifLoad) {
                    for (chg in snap.documentChanges) {
                        if (chg.type == DocumentChange.Type.ADDED) {
                            when (chg.document.getString("type")) {
                                "SALE_SUCCESS" -> notifySaleSuccess(chg.document)
                            }
                        }
                    }
                }
                firstNotifLoad = false
            }
    }

    private fun updateNotifBadgeUI() {
        val destId = if (::navController.isInitialized) navController.currentDestination?.id else null
        val isOnNotif = destId == R.id.superAdminNotificationFragment
        val v = tvNotifBadge
        if (v == null) return
        if (!isOnNotif && unreadNotifCount > 0) {
            v.visibility = View.VISIBLE
            v.text = if (unreadNotifCount > 99) "99+" else unreadNotifCount.toString()
        } else {
            v.visibility = View.GONE
        }
    }

    private fun notifyNewRequest(d: DocumentSnapshot) {
        val sku = d.getString("sku") ?: ""
        val name = d.getString("productName") ?: sku
        val delta = (d.getLong("requestedDelta") ?: 0L).toString()
        val text = "$name (${if (delta.startsWith("-")) delta else "+$delta"})"

        val args = Bundle().apply { putInt("initialTab", TAB_STOCK_ADJUST) }
        val pi = NavDeepLinkBuilder(this)
            .setGraph(R.navigation.nav_super_admin)
            .setDestination(R.id.superAdminRequestsFragment)
            .setArguments(args)
            .createPendingIntent()

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Permintaan penyesuaian stok baru")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        if (canPostNotifications()) {
            try {
                NotificationManagerCompat.from(this).notify(d.id.hashCode(), notif)
            } catch (_: SecurityException) {
                maybeRequestPostNotifications(force = true)
            }
        } else {
            maybeRequestPostNotifications(force = true)
        }

        // Persist to in-app notifications list for Super Admin
        try {
            val createdAt = d.getTimestamp("createdAt")
            val data = mutableMapOf<String, Any>(
                "type" to "ADJUSTMENT_REQUEST",
                "title" to "Permintaan penyesuaian stok",
                "message" to text,
                "sku" to sku,
                "toRole" to "super-admin",
                "read" to false
            )
            if (createdAt != null) data["createdAt"] = createdAt else data["createdAt"] = com.google.firebase.firestore.FieldValue.serverTimestamp()
            FirebaseFirestore.getInstance().collection("notifications")
                .document("sa_req_${d.id}")
                .set(data, SetOptions.merge())
        } catch (_: Throwable) {}
    }

    private fun notifyNewReturnRequest(d: DocumentSnapshot) {
        val saleNo = d.getString("saleNo") ?: d.getString("saleId") ?: d.id
        val reason = d.getString("reason").orEmpty()
        val message = if (reason.isNotBlank()) {
            "Retur $saleNo • $reason"
        } else {
            "Retur $saleNo menunggu persetujuan"
        }

        val args = Bundle().apply { putInt("initialTab", TAB_RETURNS) }
        val pi = NavDeepLinkBuilder(this)
            .setGraph(R.navigation.nav_super_admin)
            .setDestination(R.id.superAdminRequestsFragment)
            .setArguments(args)
            .createPendingIntent()

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Permintaan retur baru")
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        if (canPostNotifications()) {
            try {
                NotificationManagerCompat.from(this).notify(("ret_${d.id}").hashCode(), notif)
            } catch (_: SecurityException) {
                maybeRequestPostNotifications(force = true)
            }
        } else {
            maybeRequestPostNotifications(force = true)
        }

        try {
            val createdAt = d.getTimestamp("createdAt")
        val data = mutableMapOf<String, Any>(
                "type" to "SALE_RETURN_REQUEST",
                "title" to "Permintaan retur baru",
                "message" to message,
                "saleId" to (d.getString("saleId") ?: d.id),
                "toRole" to "super-admin",
                "read" to false
            )
            if (createdAt != null) data["createdAt"] = createdAt else data["createdAt"] = FieldValue.serverTimestamp()
            FirebaseFirestore.getInstance().collection("notifications")
                .document("sa_return_${d.id}")
                .set(data, SetOptions.merge())
        } catch (_: Throwable) {}
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val requestChannel = NotificationChannel(CHANNEL_ID, "Permintaan Persetujuan", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Notifikasi permintaan penyesuaian stok"
                enableLights(true)
                lightColor = Color.RED
            }
            nm.createNotificationChannel(requestChannel)

            val salesChannel = NotificationChannel(CHANNEL_SALES, "Transaksi Berhasil", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Notifikasi transaksi kasir yang berhasil"
            }
            nm.createNotificationChannel(salesChannel)
        }
    }

    private fun notifySaleSuccess(d: DocumentSnapshot) {
        val saleDocId = d.getString("saleDocId") ?: return
        val title = d.getString("title") ?: "Transaksi berhasil"
        val message = d.getString("message") ?: "Transaksi kasir berhasil."

        if (!canPostNotifications()) {
            maybeRequestPostNotifications(force = true)
            return
        }

        val args = Bundle().apply {
            putString(SuperAdminReportFragment.ARG_INITIAL_SALE_DOC_ID, saleDocId)
        }
        val pi = NavDeepLinkBuilder(this)
            .setGraph(R.navigation.nav_super_admin)
            .setDestination(R.id.superAdminReportFragment)
            .setArguments(args)
            .createPendingIntent()

        val notif = NotificationCompat.Builder(this, CHANNEL_SALES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(("sale_$saleDocId").hashCode(), notif)
        } catch (_: SecurityException) {
            maybeRequestPostNotifications(force = true)
        }
    }

    private fun maybeNotifyMonthlySummary() {
        try {
            val pref = getSharedPreferences("sa_prefs", MODE_PRIVATE)
            val key = "mtd_notif_day"
            val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
            val last = pref.getString(key, null)
            if (today == last) return

            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            val start = cal.time; val end = java.util.Date(System.currentTimeMillis()+1)
            db.collection("sales")
                .whereGreaterThanOrEqualTo("createdAt", start)
                .whereLessThan("createdAt", end)
                .get()
                .addOnSuccessListener { qs ->
                    var omzet = 0L; var cost = 0L
                    for (d in qs.documents) {
                        val t = d.getLong("total") ?: 0L; omzet += t
                        val items = (d.get("items") as? List<Map<String, Any?>>).orEmpty()
                        items.forEach { m ->
                            val isSvc = (m["isService"] as? Boolean) ?: false
                            if (!isSvc) {
                                val qty = (m["qty"] as? Number)?.toLong() ?: 0L
                                val unitCost = (m["unitCost"] as? Number)?.toLong() ?: 0L
                                cost += qty * unitCost
                            }
                        }
                    }
                    sendMonthlySummaryNotification(omzet, omzet - cost)
                    pref.edit().putString(key, today).apply()
                }
        } catch (_: Throwable) {}
    }

    private fun sendMonthlySummaryNotification(omzet: Long, laba: Long) {
        if (!canPostNotifications()) {
            maybeRequestPostNotifications(force = true)
            return
        }
        val nf = java.text.NumberFormat.getInstance(java.util.Locale("in","ID"))
        val text = "Omzet: Rp ${nf.format(omzet)}  |  Laba: Rp ${nf.format(laba)}"
        val pi = NavDeepLinkBuilder(this)
            .setGraph(R.navigation.nav_super_admin)
            .setDestination(R.id.superAdminNotificationFragment)
            .createPendingIntent()
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Ringkasan bulan ini")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(900001, notif)
        } catch (_: SecurityException) { /* ignore if permission denied */ }

        // Persist monthly summary to notification list once per day
        try {
            val dayKey = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
            val data = mapOf(
                "type" to "MONTHLY_SUMMARY",
                "title" to "Ringkasan bulan ini",
                "message" to text,
                "toRole" to "super-admin",
                "read" to false,
                "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
            )
            FirebaseFirestore.getInstance().collection("notifications")
                .document("sa_monthly_$dayKey")
                .set(data, SetOptions.merge())
        } catch (_: Throwable) {}
    }

    private fun normalize(r: String?) =
        r?.trim()?.lowercase()?.replace('_', '-')?.replace(' ', '-')

    private fun prettyRole(r: String?): String = when (r) {
        "owner" -> "Owner"
        "super-admin", "superadmin" -> "Super Admin"
        "admin" -> "Admin"
        else -> r ?: "-"
    }

    private fun goLogin() {
        val i = Intent(this, LoginActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(i)
        finish()
    }

    private fun canPostNotifications(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun maybeRequestPostNotifications(force: Boolean = false) {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        if (canPostNotifications()) {
            getSharedPreferences("notif_prefs", MODE_PRIVATE).edit().putBoolean("needs_notif_permission", false).apply()
            return
        }

        val pref = getSharedPreferences("sa_prefs", MODE_PRIVATE)
        val lastPrompt = pref.getLong("notif_perm_last_prompt", 0L)
        if (!force) {
            val cooldown = 6 * 60 * 60 * 1000L // 6 hours
            if (System.currentTimeMillis() - lastPrompt < cooldown) return
        }
        pref.edit().putLong("notif_perm_last_prompt", System.currentTimeMillis()).apply()

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle("Aktifkan Notifikasi")
            .setMessage("Aktifkan notifikasi agar Anda tidak melewatkan permintaan penyesuaian stok, ringkasan, dan info penting lainnya.")
            .setNegativeButton("Nanti", null)
            .setPositiveButton("Izinkan") { _, _ ->
                try { notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) } catch (_: Throwable) {}
            }

        val shouldShowSettings = !shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)
        if (shouldShowSettings) {
            builder.setNeutralButton("Pengaturan") { _, _ -> openNotificationSettings() }
        }

        builder.show()
    }

    

    private fun openNotificationSettings() {
        val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
        }
        try {
            startActivity(intent)
        } catch (_: Exception) { }
    }


    private fun reschedulePendingStockReceipts() {
        try {
            db.collection("pending_stock_receipts")
                .whereEqualTo("status", "pending")
                .get()
                .addOnSuccessListener { snap ->
                    val pendingCount = snap.size()
                    if (pendingCount > 0) {
                        Log.d(
                            "SuperAdminMainActivity",
                            "Server scheduler akan memproses $pendingCount pending stock secara otomatis"
                        )
                    }
                }
                .addOnFailureListener { e ->
                    Log.w("SuperAdminMainActivity", "Gagal menjadwalkan ulang pending stok", e)
                }
        } catch (e: Exception) {
            Log.w("SuperAdminMainActivity", "Exception menjadwalkan ulang pending stok", e)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        return if (::navController.isInitialized) {
            NavigationUI.navigateUp(navController, appBarConfig)
        } else super.onSupportNavigateUp()
    }
}
