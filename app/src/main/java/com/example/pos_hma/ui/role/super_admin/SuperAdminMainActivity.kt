package com.example.pos_hma.ui.role.super_admin

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.NavigationUI
import com.example.pos_hma.R
import com.example.pos_hma.databinding.ActivitySuperAdminMainBinding
import com.example.pos_hma.ui.login.LoginActivity
import com.example.pos_hma.utils.AppFlags
import com.example.pos_hma.utils.SnapshotDisposable
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import de.hdodenhof.circleimageview.CircleImageView

class SuperAdminMainActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySuperAdminMainBinding
    private lateinit var navController: NavController
    private lateinit var appBarConfig: AppBarConfiguration

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySuperAdminMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        ensureRoleAllowedOrExit(setOf("owner", "super-admin", "superadmin")) {
            val host = supportFragmentManager.findFragmentById(R.id.nav_host_owner) as NavHostFragment
            navController = host.navController

            appBarConfig = AppBarConfiguration(setOf(
                R.id.ownerDashboardFragment,
                R.id.ownerProductsFragment
            ))

            NavigationUI.setupWithNavController(binding.toolbar, navController, appBarConfig)
            NavigationUI.setupWithNavController(binding.bottomNav, navController)
        }
    }

    override fun onStart() {
        super.onStart()
        // pastikan listener boleh jalan
        AppFlags.isLoggingOut = false
    }

    // ===== AppBar avatar bulat =====
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_owner_appbar, menu)
        val item = menu.findItem(R.id.action_profile)
        val actionView: View? = item.actionView
        val ivAvatar = actionView?.findViewById<CircleImageView>(R.id.ivAvatar)
        actionView?.setOnClickListener { showProfileDialog() }
        ivAvatar?.setOnClickListener { showProfileDialog() }
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
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_owner_profile, null, false)
        val tvEmail = v.findViewById<TextView>(R.id.tvEmails)
        val tvRole  = v.findViewById<TextView>(R.id.tvRoles)
        val btnLogout = v.findViewById<MaterialButton>(R.id.btnLogouts)

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
            // cegah listener nge-log error saat signOut
            AppFlags.isLoggingOut = true
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

    override fun onSupportNavigateUp(): Boolean {
        return if (::navController.isInitialized) {
            NavigationUI.navigateUp(navController, appBarConfig)
        } else super.onSupportNavigateUp()
    }
}
