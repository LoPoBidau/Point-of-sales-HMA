package com.example.pos_hma.ui.role.super_admin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.example.pos_hma.databinding.FragmentSuperAdminReportBinding
import com.example.pos_hma.databinding.ItemReportTabBinding
import com.google.android.material.tabs.TabLayoutMediator

class SuperAdminReportFragment : Fragment() {

    private var _binding: FragmentSuperAdminReportBinding? = null
    private val binding get() = _binding!!

    private val tabs = listOf("Penjualan", "Pembelian", "Stok & Valuasi")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentSuperAdminReportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        // simple pager tanpa child-fragment untuk ringan & cepat
        binding.pager.adapter = ReportPagerAdapter(tabs)
        TabLayoutMediator(binding.tabs, binding.pager) { tab, pos ->
            tab.text = tabs[pos]
        }.attach()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    /** Adapter ViewPager2 yang hanya meng-inflate layout sederhana per tab */
    private class ReportPagerAdapter(private val titles: List<String>) :
        RecyclerView.Adapter<ReportPagerAdapter.VH>() {

        inner class VH(val b: ItemReportTabBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemReportTabBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun getItemCount() = titles.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val title = titles[position]
            holder.b.tvTitle.text = title
            // Placeholder konten; nanti sambungkan ke query Firestore / export, dsb.
            holder.b.tvDesc.text = when (title) {
                "Penjualan" -> "Ringkasan transaksi, omzet, top produk. (coming soon)"
                "Pembelian" -> "Ringkasan pembelian/PO, biaya, supplier. (coming soon)"
                else        -> "Nilai persediaan, pergerakan stok, valuasi FIFO. (coming soon)"
            }
        }
    }
}
