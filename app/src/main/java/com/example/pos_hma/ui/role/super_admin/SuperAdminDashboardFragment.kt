package com.example.pos_hma.ui.role.super_admin

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.pos_hma.databinding.FragmentSuperAdminDashboardBinding
import com.example.pos_hma.databinding.FragmentSuperAdminProductBinding
import com.example.pos_hma.ui.login.LoginActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class SuperAdminDashboardFragment : Fragment() {

    private var _binding: FragmentSuperAdminDashboardBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentSuperAdminDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        ensureRoleAllowedOrExit(setOf("owner","super-admin","superadmin")) {
            binding.tvSummary.text = "Halo Owner! Nanti kita taruh chart & KPI penjualan di sini."
        }
    }

    private fun ensureRoleAllowedOrExit(allowed: Set<String>, onOk: () -> Unit) {
        val user = FirebaseAuth.getInstance().currentUser ?: return goLogin()
        user.getIdToken(false).addOnSuccessListener { tok ->
            val r = normalize(tok.claims["role"] as? String)
            if (r != null && allowed.contains(r)) onOk() else {
                FirebaseFirestore.getInstance().collection("users").document(user.uid).get()
                    .addOnSuccessListener { s ->
                        val rr = normalize(s.getString("role"))
                        if (rr != null && allowed.contains(rr)) onOk() else goLogin()
                    }.addOnFailureListener { goLogin() }
            }
        }.addOnFailureListener { goLogin() }
    }

    private fun normalize(r: String?) = r?.trim()?.lowercase()?.replace('_','-')?.replace(' ','-')
    private fun goLogin() { startActivity(Intent(requireContext(), LoginActivity::class.java)); requireActivity().finish() }

    override fun onDestroyView() { _binding = null; super.onDestroyView() }
}
