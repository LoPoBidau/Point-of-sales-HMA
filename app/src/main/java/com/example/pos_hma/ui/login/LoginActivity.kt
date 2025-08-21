package com.example.pos_hma.ui.login

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.pos_hma.R
import com.example.pos_hma.databinding.ActivityLoginBinding
import com.example.pos_hma.ui.role.admin.DashboardAdminActivity
import com.example.pos_hma.ui.role.super_admin.DashboardSuperAdminActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore

class LoginActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.loginActivity)
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        binding.btnLogin.setOnClickListener { doLogin() }
    }

    override fun onStart() {
        super.onStart()
        auth.currentUser?.let { routeByRole(it, forceRefreshClaims = false) }
    }

    private fun doLogin() {
        val email = binding.etEmail.text?.toString()?.trim().orEmpty()
        val pass = binding.etPassword.text?.toString()?.trim().orEmpty()
        if (email.isEmpty() || pass.isEmpty()) {
            Toast.makeText(this, "Email & Password wajib diisi", Toast.LENGTH_SHORT).show(); return
        }
        setLoading(true)
        auth.signInWithEmailAndPassword(email, pass)
            .addOnSuccessListener { routeByRole(it.user, forceRefreshClaims = true) }
            .addOnFailureListener { e ->
                setLoading(false); Toast.makeText(this,"Login gagal: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun routeByRole(user: FirebaseUser?, forceRefreshClaims: Boolean) {
        if (user == null) { setLoading(false); return }

        // 1) Coba baca dari custom claims (kalau nanti kamu aktifkan server Kotlin)
        user.getIdToken(forceRefreshClaims)
            .addOnSuccessListener { res ->
                val roleFromClaims = res.claims["role"] as? String
                if (roleFromClaims != null) {
                    setLoading(false); goToDashboard(roleFromClaims)
                } else {
                    // 2) Fallback: Firestore users/{uid}
                    fetchRoleFromFirestore(user.uid)
                }
            }
            .addOnFailureListener {
                fetchRoleFromFirestore(user.uid)
            }
    }

    private fun fetchRoleFromFirestore(uid: String) {
        db.collection("users").document(uid).get()
            .addOnSuccessListener { snap ->
                val role = snap.getString("role")
                if (role.isNullOrBlank()) {
                    setLoading(false)
                    Toast.makeText(this, "Akun belum diaktifkan (role kosong). Hubungi owner.", Toast.LENGTH_SHORT).show()
                    FirebaseAuth.getInstance().signOut()
                } else {
                    setLoading(false); goToDashboard(role)
                }
            }
            .addOnFailureListener { e ->
                setLoading(false); Toast.makeText(this, "Gagal membaca role: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun goToDashboard(role: String) {
        when (role.lowercase()) {
            "super-admin" -> {
                startActivity(Intent(this, DashboardSuperAdminActivity::class.java))
            }
            "admin" -> {
                startActivity(Intent(this, DashboardAdminActivity::class.java))
            }
            else -> {
                Toast.makeText(this, "Role tidak dikenali: $role", Toast.LENGTH_SHORT).show(); return
            }
        }
        finish()
    }

    private fun setLoading(bool: Boolean) {
        binding.progress.visibility = if (bool) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !bool
    }
}
