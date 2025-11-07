package com.example.pos_hma.ui.role.super_admin

// Noted:
// Layar Inventori memberikan gambaran stok terkini (jumlah unit, nilai, dan batch FIFO) sehingga super admin dapat memantau kesehatan gudang
// tanpa harus membuka tiap produk satu per satu.

// Class Note:
// - Mengambil data dari koleksi stok/batch, kemudian menghitung ringkasan seperti total nilai stok dan daftar batch mendekati kadaluarsa.
// - Menampilkan hasil ke recycler view dengan format kartu ringkas agar mudah dipindai.
// - Bekerja beriringan dengan modul penyesuaian/penerimaan stok sehingga angka selalu up to date.

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.pos_hma.databinding.FragmentSuperAdminInventoryBinding
import com.google.android.material.tabs.TabLayoutMediator

// Class Note (Deklarasi):
// Fragment ini menggunakan ViewPager2 untuk memperlihatkan sub-modul inventori (barang, kategori, supplier) dalam satu layar tab.
class SuperAdminInventoryFragment : Fragment() {

    private var _b: FragmentSuperAdminInventoryBinding? = null
    private val b get() = _b!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentSuperAdminInventoryBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 3
            override fun createFragment(position: Int): Fragment = when (position) {
                0 -> SuperAdminProductFragment.newInstance("goods")    // Barang
                1 -> SuperAdminCategoryFragment()                       // Kategori
                else -> SuperAdminSupplierFragment()                    // Supplier
            }
        }
        TabLayoutMediator(b.tabLayout, b.pager) { tab, pos ->
            tab.text = arrayOf("Barang", "Kategori", "Supplier")[pos]
        }.attach()
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }
}
