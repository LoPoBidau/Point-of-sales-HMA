package com.example.pos_hma.ui.role.super_admin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.pos_hma.databinding.FragmentSuperAdminRequestsBinding
import com.google.android.material.tabs.TabLayoutMediator

class SuperAdminRequestsFragment : Fragment() {

    companion object {
        private const val ARG_INITIAL_TAB = "initialTab"
        const val TAB_STOCK_ADJUST = 0
        const val TAB_RETURNS = 1
    }

    private var _binding: FragmentSuperAdminRequestsBinding? = null
    private val binding get() = _binding!!
    private var mediator: TabLayoutMediator? = null

    private val initialTab: Int
        get() = (arguments?.getInt(ARG_INITIAL_TAB, TAB_STOCK_ADJUST) ?: TAB_STOCK_ADJUST)
            .coerceIn(TAB_STOCK_ADJUST, TAB_RETURNS)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSuperAdminRequestsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val adapter = RequestsPagerAdapter(this)
        binding.viewPager.adapter = adapter
        mediator = TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Penyesuaian Stok"
                else -> "Retur Barang"
            }
        }.also { it.attach() }
        binding.viewPager.setCurrentItem(initialTab, false)
    }

    override fun onDestroyView() {
        mediator?.detach()
        mediator = null
        _binding = null
        super.onDestroyView()
    }

    private class RequestsPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = 2
        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> SuperAdminAdjustRequestFragment()
            else -> SuperAdminReturnRequestFragment()
        }
    }
}
