package com.example.pos_hma.ui.role.admin

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.ImageView
import coil.load
import com.example.pos_hma.R
import com.example.pos_hma.data.Product
import com.example.pos_hma.databinding.FragmentAdminCashierGoodsBinding
import com.example.pos_hma.databinding.ItemProductSuperAdminBinding
import com.example.pos_hma.utils.SnapshotDisposable
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlin.math.roundToInt

class AdminCashierGoodsFragment : Fragment(), SnapshotDisposable {

    private var _b: FragmentAdminCashierGoodsBinding? = null
    private val b get() = _b!!

    private val db by lazy { FirebaseFirestore.getInstance() }
    private var reg: ListenerRegistration? = null
    private val all = mutableListOf<Product>()
    private val adapter = GoodsAdapter { p -> onGoodsClicked(p) }
    private val filterCategories = linkedSetOf<String>()
    private var availableCategories: List<String> = emptyList()

    private companion object {
        private const val MAX_PRODUCTS = 200L
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentAdminCashierGoodsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        b.rvGoods.layoutManager = GridLayoutManager(requireContext(), 2)
        b.rvGoods.adapter = adapter
        b.etSearch.addTextChangedListener(object: TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { applyFilter() }
        })
        b.tilSearch.setStartIconOnClickListener { showFilterDialog() }
        b.tilSearch.setEndIconOnClickListener { applyFilter() }
        listen()
    }

    override fun onDestroyView() {
        disposeSnapshots()
        _b = null
        super.onDestroyView()
    }

    override fun disposeSnapshots() {
        reg?.remove()
        reg = null
    }

    private fun listen() {
        reg?.remove(); reg = null
        reg = db.collection("products")
            .orderBy("nameLowercase")
            .limit(MAX_PRODUCTS)
            .addSnapshotListener { snap, e ->
                if (e != null) { Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show(); return@addSnapshotListener }
                val items = snap!!.documents.mapNotNull { d ->
                    d.toObject(Product::class.java)?.copy(id = d.id)
                }.filter { !it.isService }
                all.clear(); all.addAll(items)
                availableCategories = items.mapNotNull { it.categoryName.takeIf { name -> name.isNotBlank() } }
                    .distinct()
                    .sorted()
                filterCategories.retainAll(availableCategories)
                applyFilter()
            }
    }

    private fun applyFilter() {
        val q = b.etSearch.text?.toString()?.trim()?.lowercase().orEmpty()
        val selectedCats = filterCategories
        val list = all
            .filter { p ->
                val matchName = q.isEmpty() || p.nameLowercase.contains(q) || p.sku.lowercase().contains(q)
                val matchCategory = selectedCats.isEmpty() || selectedCats.contains(p.categoryName)
                matchName && matchCategory
            }
            .sortedWith(compareBy<Product> { p ->
                val trackable = p.trackStock && !p.isService
                if (trackable && p.stock <= 0L) 1 else 0
            }.thenBy { it.nameLowercase })
        adapter.submit(list)
    }

    private fun showFilterDialog() {
        val ctx = requireContext()
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_catalog_filter, null, false)
        val chipGroup = view.findViewById<ChipGroup>(R.id.chipGroupCategories)

        chipGroup.removeAllViews()
        val anyChip = Chip(ctx).apply {
            text = "Semua"
            isCheckable = true
            isChecked = filterCategories.isEmpty()
            setOnClickListener {
                if (isChecked) {
                    for (i in 0 until chipGroup.childCount) {
                        val c = chipGroup.getChildAt(i)
                        if (c != this && c is Chip) c.isChecked = false
                    }
                }
            }
        }
        chipGroup.addView(anyChip)

        val categories = if (availableCategories.isNotEmpty()) availableCategories
        else all.mapNotNull { it.categoryName.takeIf { name -> name.isNotBlank() } }.distinct().sorted()

        categories.forEach { cat ->
            val chip = Chip(ctx).apply {
                text = cat
                tag = cat
                isCheckable = true
                isChecked = filterCategories.contains(cat)
                setOnCheckedChangeListener { _, checked -> if (checked) anyChip.isChecked = false }
            }
            chipGroup.addView(chip)
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle("Filter Barang")
            .setView(view)
            .setNegativeButton("Batal", null)
            .setNeutralButton("Reset") { _, _ ->
                filterCategories.clear()
                applyFilter()
            }
            .setPositiveButton("Terapkan") { _, _ ->
                val selected = linkedSetOf<String>()
                for (i in 0 until chipGroup.childCount) {
                    val c = chipGroup.getChildAt(i)
                    if (c is Chip && c != anyChip && c.isChecked) {
                        (c.tag as? String)?.let { selected.add(it) }
                    }
                }
                filterCategories.clear()
                filterCategories.addAll(selected)
                applyFilter()
            }
            .show()
    }

    private fun onGoodsClicked(p: Product) {
        val ctx = requireContext()
        val view = layoutInflater.inflate(R.layout.dialog_adjust_request, null, false)
        val etQty = view.findViewById<TextInputEditText>(R.id.etQty)
        val tilQty = view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilQty)
        val etReason = view.findViewById<TextInputEditText>(R.id.etReason)
        val tvProduct = view.findViewById<android.widget.TextView>(R.id.tvProduct)
        val tvInfo = view.findViewById<android.widget.TextView>(R.id.tvInfo)
        val rbTambah = view.findViewById<android.widget.RadioButton>(R.id.rbTambah)
        val rbKurang = view.findViewById<android.widget.RadioButton>(R.id.rbKurang)

        // Header: Name • SKU (SKU tanpa label) dan baris bawah hanya stok
        tvProduct.text = "${p.name} • ${p.sku.ifBlank { p.id }}"
        tvInfo.text = "Stok: ${p.stock}"

        // Jika stok 0, hanya boleh tambah: sembunyikan opsi Kurang
        if (p.stock <= 0) {
            rbKurang.visibility = View.GONE
            rbTambah.isChecked = true
        } else {
            rbKurang.visibility = View.VISIBLE
        }

        // Build dialog; intercept positive click for validation
        val dlg = MaterialAlertDialogBuilder(ctx)
            .setTitle("Ajukan Penyesuaian Stok")
            .setView(view)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Ajukan", null)
            .create()

        dlg.setOnShowListener {
            dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            val btn = dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            btn.setOnClickListener {
                tilQty.error = null
                val qty = etQty.text?.toString()?.trim()?.toLongOrNull() ?: 0L
                val reason = etReason.text?.toString()?.trim().orEmpty()
                if (qty <= 0) {
                    tilQty.error = "Jumlah harus lebih dari 0"
                    return@setOnClickListener
                }
                if (rbKurang.isChecked && qty > p.stock) {
                    tilQty.error = "Tidak boleh melebihi stok (${p.stock})"
                    return@setOnClickListener
                }
                // Larangan stok negatif secara eksplisit (perlindungan tambahan UI)
                if (rbKurang.isChecked && p.stock - qty < 0) {
                    tilQty.error = "Stok tidak boleh negatif"
                    return@setOnClickListener
                }
                if (reason.isBlank()) {
                    Toast.makeText(ctx, "Alasan harus diisi", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
                val delta = if (rbKurang.isChecked) -qty else qty
                val req = hashMapOf(
                    "sku" to (p.sku.ifBlank { p.id }),
                    "productName" to p.name,
                    "requestedDelta" to delta,
                    "reason" to reason,
                    "status" to "PENDING",
                    "requestedBy" to uid,
                    "createdAt" to FieldValue.serverTimestamp()
                )
                btn.isEnabled = false
                db.collection("stock_adjust_requests")
                    .add(req)
                    .addOnSuccessListener { ref ->
                        // Kirim notifikasi in-app untuk Super Admin agar muncul real-time
                        try {
                            val notif = hashMapOf(
                                "toRole" to "super-admin",
                                "type" to "ADJUSTMENT_REQUEST",
                                "title" to "Permintaan Penyesuaian Stok",
                                "message" to "${p.name} (SKU ${p.sku.ifBlank { p.id }}) diminta ${if (delta >= 0) "+" else ""}$delta",
                                "read" to false,
                                "createdAt" to FieldValue.serverTimestamp(),
                                // metadata tambahan untuk navigasi
                                "requestId" to ref.id,
                                "sku" to (p.sku.ifBlank { p.id })
                            )
                            db.collection("notifications").add(notif)
                        } catch (_: Throwable) { /* abaikan kegagalan notifikasi */ }

                        Toast.makeText(ctx, "Permintaan dikirim", Toast.LENGTH_SHORT).show()
                        dlg.dismiss()
                    }
                    .addOnFailureListener { e ->
                        btn.isEnabled = true
                        Toast.makeText(ctx, e.message ?: "Gagal mengirim", Toast.LENGTH_LONG).show()
                    }
            }
        }
        dlg.show()
    }
}

private class GoodsAdapter(
    val onClick: (Product) -> Unit
) : RecyclerView.Adapter<GoodsAdapter.VH>() {
    private val items = mutableListOf<Product>()
    fun submit(list: List<Product>) { items.clear(); items.addAll(list); notifyDataSetChanged() }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemProductSuperAdminBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b, onClick)
    }
    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    class VH(
        private val b: ItemProductSuperAdminBinding,
        private val onClick: (Product) -> Unit
    ) : RecyclerView.ViewHolder(b.root) {
        fun bind(p: Product) {
            // Image & basic info
            val imageUrl = p.images.firstOrNull()?.takeIf { it.isNotBlank() }
            val padding = (16 * b.root.resources.displayMetrics.density).roundToInt()
            if (imageUrl == null) {
                b.img.alpha = 0.7f
                b.img.scaleType = ImageView.ScaleType.CENTER_INSIDE
                b.img.setPadding(padding, padding, padding, padding)
            } else {
                b.img.alpha = 1f
                b.img.setPadding(0, 0, 0, 0)
                b.img.scaleType = ImageView.ScaleType.CENTER_CROP
            }
            b.img.load(imageUrl) {
                placeholder(R.drawable.ic_product_placeholder)
                error(R.drawable.ic_product_placeholder)
                fallback(R.drawable.ic_product_placeholder)
            }
            b.tvName.text = p.name
            b.tvCategory.text = if (p.categoryName.isNotBlank()) "Kategori : ${p.categoryName}" else "Kategori : -"
            b.tvStock.text = "Stok: ${p.stock}"
            b.tvPrice.text = "Rp ${java.text.NumberFormat.getInstance(java.util.Locale("in","ID")).format(p.salePrice)}"

            // Hide cost row to remove extra gap between category and price
            b.tvCost.visibility = View.GONE

            // Badge for stock status
            val out = p.stock <= 0
            b.tvBadge.visibility = View.VISIBLE
            b.tvBadge.text = if (out) "Stok Habis" else "Stok Tersedia"
            b.tvBadge.setBackgroundResource(if (out) R.drawable.bg_badge_red else R.drawable.bg_badge_green)

            // Hide pending button in cashier view
            b.btnPending.visibility = View.GONE
            b.btnStockOpname.visibility = View.GONE
            val params = b.btnAdd.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            params.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            params.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            params.startToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            params.endToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            b.btnAdd.layoutParams = params

            // This fragment uses adjust request, not delete/receive like super admin
            b.btnDelete.visibility = View.GONE
            b.btnAdd.text = "Ajukan\nPenyesuaian"
            // Remove icon for admin cashier card action button
            try { b.btnAdd.icon = null } catch (_: Throwable) {}
            b.btnAdd.setOnClickListener { onClick(p) }

            b.root.setOnClickListener { onClick(p) }
        }
    }
}

