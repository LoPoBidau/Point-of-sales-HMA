package com.example.pos_hma.ui.role.admin

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.pos_hma.R
import com.example.pos_hma.databinding.ActivityDashboardAdminBinding
import com.example.pos_hma.ui.login.LoginActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging

class DashboardAdminActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardAdminBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityDashboardAdminBinding.inflate(layoutInflater)
        setContentView(binding.main)

        binding.fabLogout.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Logout")
                .setMessage("Yakin mau keluar dari akun ini?")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Logout") { _, _ -> logout() }
                .show()
            true
        }
    }

    private fun logout() {
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser

        // 1) (opsional) lepaskan topik notifikasi
        FirebaseMessaging.getInstance().unsubscribeFromTopic("owners")

        // 2) (opsional) hapus token aktif dari Firestore agar tidak dapat push setelah logout
        //    Aman dipanggil walau gagal—tetap lanjut signOut.
        if (user != null) {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                FirebaseFirestore.getInstance().collection("users").document(user.uid)
                    .update("fcmTokens", FieldValue.arrayRemove(token))
                    .addOnCompleteListener { doSignOut() }
            }.addOnFailureListener { doSignOut() }
        } else {
            doSignOut()
        }
    }

    private fun doSignOut() {
        FirebaseAuth.getInstance().signOut()

        // 3) Kembali ke Login & bersihkan back stack supaya tidak bisa back ke dashboard
        val intent = Intent(this, LoginActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        finish()
    }
}