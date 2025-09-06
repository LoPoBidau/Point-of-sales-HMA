package com.example.pos_hma.ui.role.super_admin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.pos_hma.databinding.FragmentSuperAdminInventoryBinding
import com.google.android.material.tabs.TabLayoutMediator

class SuperAdminInventoryFragment : Fragment() {

    private var _b: FragmentSuperAdminInventoryBinding? = null
    private val b get() = _b!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentSuperAdminInventoryBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 2
            override fun createFragment(position: Int): Fragment = when (position) {
                0 -> SuperAdminProductFragment()    // Barang
                else -> SuperAdminCategoryFragment()// Kategori
            }
        }
        TabLayoutMediator(b.tabLayout, b.pager) { tab, pos ->
            tab.text = arrayOf("Barang", "Kategori")[pos]
        }.attach()
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }
}
