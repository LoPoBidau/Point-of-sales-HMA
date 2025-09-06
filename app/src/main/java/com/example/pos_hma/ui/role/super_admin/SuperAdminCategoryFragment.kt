package com.example.pos_hma.ui.role.super_admin

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.pos_hma.R
import com.example.pos_hma.data.Category
import com.example.pos_hma.databinding.DialogCategoryFormBinding
import com.example.pos_hma.databinding.FragmentSuperAdminCategoryBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

class SuperAdminCategoryFragment : Fragment() {

    private var _binding: FragmentSuperAdminCategoryBinding? = null
    private val binding get() = _binding!!

    private val db by lazy { FirebaseFirestore.getInstance() }
    private var catsReg: ListenerRegistration? = null

    private lateinit var adapter: CategoryAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentSuperAdminCategoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        setupUi()
        listenCategories()
    }

    override fun onStop() { super.onStop(); catsReg?.remove(); catsReg = null }
    override fun onDestroyView() {
        catsReg?.remove(); catsReg = null
        _binding = null
        super.onDestroyView()
    }

    private fun setupUi() {
        binding.rvCategories.layoutManager = LinearLayoutManager(requireContext())
        adapter = CategoryAdapter(
            onDelete = { confirmDelete(it) }
        )
        binding.rvCategories.adapter = adapter
        binding.fabAdd.setOnClickListener { openAddCategoryDialog() }
        binding.swipeRefresh.setOnRefreshListener { refreshOnce() }
    }

    private fun listenCategories() {
        catsReg?.remove()
        binding.swipeRefresh.isRefreshing = true
        catsReg = db.collection("categories")
            .addSnapshotListener { snap, e ->
                if (e != null) { toast("Gagal memuat: ${e.message}"); return@addSnapshotListener }
                val list = snap?.documents?.map { d ->
                    d.toObject(Category::class.java)?.copy(id = d.id) ?: Category(
                        id = d.id,
                        name = d.getString("name") ?: d.id,
                        slug = d.getString("slug") ?: d.id,
                        forType = d.getString("forType") ?: "barang & jasa",
                        isActive = d.getBoolean("isActive") ?: true,
                        nameLowercase = d.getString("nameLowercase") ?: (d.getString("name") ?: d.id).lowercase(),
                        sortOrder = d.getLong("sortOrder") ?: 0L,
                        order = d.getLong("order")
                    )
                }?.sortedWith(compareBy<Category> { it.effectiveOrder }.thenBy { it.nameLowercase }) ?: emptyList()

                adapter.submitList(list)
                binding.tvCount.text = "Total: ${list.size}"
                binding.swipeRefresh.isRefreshing = false
            }
    }

    private fun openAddCategoryDialog() {
        val cat = DialogCategoryFormBinding.inflate(layoutInflater)

        (cat.actCatType as MaterialAutoCompleteTextView).apply {
            inputType = InputType.TYPE_NULL
            keyListener = null
            isCursorVisible = false
            setOnClickListener { showDropDown() }
            setSimpleItems(arrayOf("Barang", "Jasa", "Barang & Jasa"))
            setText("Barang & Jasa", false)
        }

        val dlg = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Kategori baru")
            .setView(cat.root)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan", null)
            .create()

        dlg.setOnShowListener {
            val btn = dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            btn.setOnClickListener {
                cat.tilCatName.error = null

                val name = cat.etCatName.text?.toString()?.trim().orEmpty()
                val type = cat.actCatType.text?.toString()?.trim()?.lowercase().orEmpty().ifBlank { "barang & jasa" }
                if (name.isBlank()) { cat.tilCatName.error = "Harus diisi"; return@setOnClickListener }
                val slug = slugify(name)
                if (slug.isBlank()) { cat.tilCatName.error = "Nama tidak valid"; return@setOnClickListener }

                val data = mapOf(
                    "name" to name,
                    "slug" to slug,
                    "forType" to type,
                    "isActive" to true,
                    "nameLowercase" to name.lowercase(),
                    "sortOrder" to System.currentTimeMillis(),
                    "createdAt" to FieldValue.serverTimestamp()
                )

                // Pakai slug sebagai id dokumen agar unik
                val ref = db.collection("categories").document(slug)
                ref.get().addOnSuccessListener { doc ->
                    if (doc.exists()) {
                        cat.tilCatName.error = "Nama kategori sudah ada"
                    } else {
                        ref.set(data)
                            .addOnSuccessListener {
                                toast("Kategori ditambahkan")
                                dlg.dismiss()
                                // ambil data baru segera
                                refreshOnce()
                            }
                            .addOnFailureListener { toast("Gagal simpan: ${it.message}") }
                    }
                }.addOnFailureListener { toast("Gagal cek: ${it.message}") }
            }
        }
        dlg.show()
    }

    private fun confirmDelete(c: Category) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Hapus ${c.name}?")
            .setMessage("Data tidak bisa dikembalikan.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ ->
                db.collection("categories").document(c.id).delete()
                    .addOnSuccessListener { toast("Dihapus") }
                    .addOnFailureListener { toast("Gagal: ${it.message}") }
            }.show()
    }

    private fun slugify(s: String) = s.trim().lowercase()
        .replace("[^a-z0-9\\s-]".toRegex(), "")
        .replace("\\s+".toRegex(), "-")

    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()

    private fun refreshOnce() {
        binding.swipeRefresh.isRefreshing = true
        db.collection("categories").get()
            .addOnSuccessListener { qs ->
                val list = qs.documents.map { d ->
                    d.toObject(Category::class.java)?.copy(id = d.id) ?: Category(
                        id = d.id,
                        name = d.getString("name") ?: d.id,
                        slug = d.getString("slug") ?: d.id,
                        forType = d.getString("forType") ?: "barang & jasa",
                        isActive = d.getBoolean("isActive") ?: true,
                        nameLowercase = d.getString("nameLowercase") ?: (d.getString("name") ?: d.id).lowercase(),
                        sortOrder = d.getLong("sortOrder") ?: 0L,
                        order = d.getLong("order")
                    )
                }.sortedWith(compareBy<Category> { it.effectiveOrder }.thenBy { it.nameLowercase })
                adapter.submitList(list)
                binding.tvCount.text = "Total: ${list.size}"
            }
            .addOnFailureListener { toast("Gagal memuat: ${it.message}") }
            .addOnCompleteListener { binding.swipeRefresh.isRefreshing = false }
    }
}

private class CategoryAdapter(
    val onDelete: (Category) -> Unit
) : ListAdapter<Category, CategoryAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Category>() {
            override fun areItemsTheSame(a: Category, b: Category) = a.id == b.id
            override fun areContentsTheSame(a: Category, b: Category) = a == b
        }
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName: TextView = v.findViewById(R.id.tvName)
        val tvType: TextView = v.findViewById(R.id.tvType)
        val btnDelete: ImageButton = v.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(p: ViewGroup, vt: Int): VH {
        val v = LayoutInflater.from(p.context).inflate(R.layout.item_category, p, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val c = getItem(position)
        h.tvName.text = c.name
        h.tvType.text = "Untuk: ${c.forType}"
        h.btnDelete.setOnClickListener { onDelete(c) }
    }
}
