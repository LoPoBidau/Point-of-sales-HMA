package com.example.pos_hma.ui.role.admin

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.example.pos_hma.R
import com.example.pos_hma.databinding.ActivityAdminCashierMainBinding
import com.example.pos_hma.ui.login.LoginActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.color.MaterialColors
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import de.hdodenhof.circleimageview.CircleImageView
import com.example.pos_hma.utils.AppFlags
import com.example.pos_hma.utils.SnapshotDisposable

class AdminCashierMainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminCashierMainBinding
    private lateinit var navController: NavController
    private lateinit var appBarConfig: AppBarConfiguration

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityAdminCashierMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1) Toolbar
        setSupportActionBar(binding.toolbar)

        // 2) Ambil NavHostFragment (ID HARUS: nav_host_cashier)
        val host = supportFragmentManager.findFragmentById(R.id.nav_host_cashier) as? NavHostFragment
            ?: error("NavHost R.id.nav_host_cashier tidak ditemukan di layout Activity!")

        // 3) NavController
        navController = host.navController

        // 4) Top-level destinations (ID harus match dengan nav graph)
        appBarConfig = AppBarConfiguration(
            setOf(
                // Top-level: Katalog dan Pembayaran (Payment tanpa ikon back)
                R.id.adminCashierCatalogFragment,
                R.id.adminCashierPaymentFragment
            )
        )

        // 5) Hubungkan Toolbar & BottomNav
        setupActionBarWithNavController(navController, appBarConfig)
        binding.bottomNav.setupWithNavController(navController)

        // 6) Refresh options menu + toggle bottom nav visibility on destination changes
        navController.addOnDestinationChangedListener { _, dest, _ ->
            invalidateOptionsMenu()
            val hideBottomNavOn = setOf(R.id.adminCartFragment, R.id.adminCashierPaymentFragment)
            val shouldHide = dest.id in hideBottomNavOn
            binding.bottomNav.visibility = if (shouldHide) View.GONE else View.VISIBLE
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        // gunakan extension navigateUp
        return navController.navigateUp(appBarConfig) || super.onSupportNavigateUp()
    }

    // ==== menu profil/logout ====
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_admin_cashier_appbar, menu)
        val item = menu.findItem(R.id.action_profile)

        // Hide profile on cart & payment screens
        val destId = navController.currentDestination?.id
        val hideOn = setOf(R.id.adminCartFragment, R.id.adminCashierPaymentFragment)
        val shouldHide = destId in hideOn
        item.isVisible = !shouldHide

        if (!shouldHide) {
            val actionView: View? = item.actionView
            val ivAvatar = actionView?.findViewById<CircleImageView>(R.id.ivAvatar)

            // klik membuka dialog profil/logout
            actionView?.setOnClickListener { showProfileDialog() }
            ivAvatar?.setOnClickListener { showProfileDialog() }

            // tint sesuai tema (dark/light)
            val color = MaterialColors.getColor(binding.toolbar, com.google.android.material.R.attr.colorOnSurface)
            ivAvatar?.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_profile -> { showProfileDialog(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showProfileDialog() {
        val user = FirebaseAuth.getInstance().currentUser ?: run { goLogin(); return }
        val v = layoutInflater.inflate(R.layout.dialog_owner_profile, null, false)
        val tvEmail = v.findViewById<android.widget.TextView>(R.id.tvEmails)
        val tvRole  = v.findViewById<android.widget.TextView>(R.id.tvRoles)
        val btnLogout = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnLogouts)

        tvEmail.text = user.email ?: "-"
        tvRole.text = "Loading…"

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
            AppFlags.isLoggingOut = true
            disposeAllChildSnapshotListeners()
            FirebaseAuth.getInstance().signOut()
            dlg.dismiss()
            goLogin()
        }

        dlg.show()
    }

    private fun disposeAllChildSnapshotListeners() {
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
}
