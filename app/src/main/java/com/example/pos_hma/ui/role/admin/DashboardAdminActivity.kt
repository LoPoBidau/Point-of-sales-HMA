package com.example.pos_hma.ui.role.admin

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.pos_hma.databinding.ActivityDashboardAdminBinding
import com.example.pos_hma.ui.login.LoginActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

class DashboardAdminActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardAdminBinding
    private val db by lazy { FirebaseFirestore.getInstance() }
    private var goodsReg: ListenerRegistration? = null
    private var svcReg: ListenerRegistration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Guard role: hanya admin/kasir
        ensureRoleAllowedOrExit(setOf("admin")) {

        }
    }

//    private fun setupUiAfterGuard() {
//        binding.rvGoods.layoutManager = GridLayoutManager(this, 2)
//        binding.rvServices.layoutManager = LinearLayoutManager(this)
//
//        // TODO: set adapter nyata (goodsAdapter, servicesAdapter)
//
//        binding.etSearch.addTextChangedListener(object: TextWatcher {
//            override fun afterTextChanged(s: Editable?) {}
//            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
//            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
//                // TODO: filter adapter goods/services by name/category
//            }
//        })
//    }

    override fun onStart() {
        super.onStart()
        // Listen goods
        goodsReg = db.collection("products")
            .whereEqualTo("type", "goods")
            .whereEqualTo("isActive", true)
            .addSnapshotListener { snap, e ->
                if (e != null) { toast("Goods gagal: ${e.message}"); return@addSnapshotListener }
                // TODO: map ke adapter goods
            }
        // Listen services
        svcReg = db.collection("products")
            .whereEqualTo("type", "service")
            .whereEqualTo("isActive", true)
            .addSnapshotListener { snap, e ->
                if (e != null) { toast("Jasa gagal: ${e.message}"); return@addSnapshotListener }
                // TODO: map ke adapter services
            }
    }

    override fun onStop() {
        goodsReg?.remove(); goodsReg = null
        svcReg?.remove(); svcReg = null
        super.onStop()
    }

    // ===== Guard role (claims → Firestore fallback) =====
    private fun ensureRoleAllowedOrExit(allowed: Set<String>, onOk: () -> Unit) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) { goLogin("Sesi habis. Silakan login lagi."); return }

        user.getIdToken(false)
            .addOnSuccessListener { tok ->
                val claimsRole = normalizeRole(tok.claims["role"] as? String)
                if (claimsRole != null && claimsRole in allowed) { onOk(); return@addOnSuccessListener }
                // Fallback Firestore
                FirebaseFirestore.getInstance().collection("users").document(user.uid).get()
                    .addOnSuccessListener { snap ->
                        val fsRole = normalizeRole(snap.getString("role"))
                        if (fsRole != null && fsRole in allowed) { onOk() } else { goLogin("Hanya kasir/admin.") }
                    }
                    .addOnFailureListener { goLogin("Gagal baca role. ${it.message}") }
            }
            .addOnFailureListener {
                FirebaseFirestore.getInstance().collection("users").document(user.uid).get()
                    .addOnSuccessListener { snap ->
                        val fsRole = normalizeRole(snap.getString("role"))
                        if (fsRole != null && fsRole in allowed) { onOk() } else { goLogin("Hanya kasir/admin.") }
                    }
                    .addOnFailureListener { goLogin("Gagal baca role. ${it.message}") }
            }
    }

    private fun normalizeRole(raw: String?): String? =
        raw?.trim()?.lowercase()?.replace('_','-')?.replace(' ','-')

    private fun goLogin(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
