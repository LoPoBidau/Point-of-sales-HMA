package com.example.pos_hma.ui.login

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.example.pos_hma.databinding.ActivityLoginBinding
import com.example.pos_hma.ui.role.admin.AdminCashierMainActivity
import com.example.pos_hma.ui.role.super_admin.SuperAdminMainActivity
import com.example.pos_hma.utils.AppFlags
import com.example.pos_hma.utils.toUserMessage
import com.example.pos_hma.utils.NetworkUtil
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore

class LoginActivity : AppCompatActivity() {
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var binding: ActivityLoginBinding
    private var netCb: android.net.ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        binding.btnLogin.setOnClickListener { doLogin() }
        // pastikan flag reset saat layar ini tampil
        AppFlags.isLoggingOut = false
    }

    override fun onStart() {
        super.onStart()
        // Jika online, langsung route; jika offline, matikan tombol login
        if (NetworkUtil.isOnline(this)) {
            setLoginOnlineUi(true)
            auth.currentUser?.let { routeByRole(it, false) }
        } else {
            setLoginOnlineUi(false)
        }

        // Observasi perubahan konektivitas untuk mengaktifkan/menonaktifkan tombol
        netCb = NetworkUtil.registerNetworkCallback(
            context = this,
            onAvailable = { runOnUiThread { setLoginOnlineUi(true) } },
            onLost = { runOnUiThread { setLoginOnlineUi(false) } }
        )
    }

    override fun onStop() {
        super.onStop()
        NetworkUtil.unregisterNetworkCallback(this, netCb)
        netCb = null
    }

    private fun doLogin() {
        if (!NetworkUtil.isOnline(this)) {
            Toast.makeText(this, "Tidak ada akses internet", Toast.LENGTH_SHORT).show()
            setLoginOnlineUi(false)
            return
        }
        val email = binding.etEmail.text?.toString()?.trim().orEmpty()
        val pass = binding.etPassword.text?.toString()?.trim().orEmpty()
        if (email.isEmpty() || pass.isEmpty()) {
            Toast.makeText(this, "Email & Password wajib diisi", Toast.LENGTH_SHORT).show(); return
        }
        setLoading(true)
        auth.signInWithEmailAndPassword(email, pass)
            .addOnSuccessListener { routeByRole(it.user, true) }
            .addOnFailureListener { e ->
                setLoading(false); Toast.makeText(this, e.toUserMessage("Login gagal. Silakan coba lagi."), Toast.LENGTH_SHORT).show()
            }
    }

    private fun routeByRole(user: FirebaseUser?, forceRefresh: Boolean) {
        if (user == null) { setLoading(false); return }
        user.getIdToken(forceRefresh)
            .addOnSuccessListener { res ->
                val role = (res.claims["role"] as? String)?.lowercase()
                if (!role.isNullOrBlank()) goToDashboard(role) else fetchRole(user.uid)
            }
            .addOnFailureListener { fetchRole(user.uid) }
    }

    private fun fetchRole(uid: String) {
        db.collection("users").document(uid).get()
            .addOnSuccessListener { snap ->
                val role = snap.getString("role")?.lowercase()
                if (role.isNullOrBlank()) {
                    setLoading(false)
                    Toast.makeText(this, "Akun belum diaktifkan (role kosong).", Toast.LENGTH_SHORT).show()
                    FirebaseAuth.getInstance().signOut()
                } else goToDashboard(role)
            }
            .addOnFailureListener { e ->
                setLoading(false); Toast.makeText(this, e.toUserMessage("Gagal mengambil data pengguna."), Toast.LENGTH_SHORT).show()
            }
    }

    private fun goToDashboard(role: String) {
        setLoading(false)
        AppFlags.isLoggingOut = false   // penting: biar listener aktif lagi
        val intent = when (role) {
            "superadmin", "super-admin", "owner" -> Intent(this, SuperAdminMainActivity::class.java)
            "admin" -> Intent(this, AdminCashierMainActivity::class.java)
            else -> { Toast.makeText(this, "Role tidak dikenali: $role", Toast.LENGTH_SHORT).show(); return }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
    }

    private fun setLoading(show: Boolean) {
        if (show) {
            binding.loadingOverlay.apply {
                if (visibility != View.VISIBLE) {
                    alpha = 0f
                    visibility = View.VISIBLE
                    bringToFront()
                }
                animate().cancel()
                animate()
                    .alpha(1f)
                    .setDuration(200L)
                    .setListener(null)
                    .start()
            }
            binding.loadingContainer.apply {
                animate().cancel()
                scaleX = 0.9f
                scaleY = 0.9f
                alpha = 0f
                animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(220L)
                    .setInterpolator(FastOutSlowInInterpolator())
                    .start()
            }
        } else {
            binding.loadingContainer.animate().cancel()
            binding.loadingOverlay.animate().cancel()
            binding.loadingContainer.animate()
                .alpha(0f)
                .scaleX(0.9f)
                .scaleY(0.9f)
                .setDuration(180L)
                .setInterpolator(FastOutSlowInInterpolator())
                .withEndAction {
                    binding.loadingContainer.alpha = 1f
                    binding.loadingContainer.scaleX = 1f
                    binding.loadingContainer.scaleY = 1f
                }
                .start()
            binding.loadingOverlay.animate()
                .alpha(0f)
                .setDuration(200L)
                .withEndAction {
                    binding.loadingOverlay.visibility = View.GONE
                    binding.loadingOverlay.alpha = 1f
                }
                .start()
        }

        binding.btnLogin.isEnabled = !show
        binding.tilEmail.isEnabled = !show
        binding.tilPassword.isEnabled = !show
        binding.etEmail.isEnabled = !show
        binding.etPassword.isEnabled = !show
    }

    private fun setLoginOnlineUi(online: Boolean) {
        if (online) {
            binding.btnLogin.isEnabled = true
            // Kembalikan teks default tombol jika sebelumnya berubah
            binding.btnLogin.text = "Masuk"
        } else {
            binding.btnLogin.isEnabled = false
            binding.btnLogin.text = "Tidak ada akses internet"
        }
    }
}
