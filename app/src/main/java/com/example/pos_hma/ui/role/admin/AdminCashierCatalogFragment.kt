package com.example.pos_hma.ui.role.admin

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.pos_hma.R
import com.example.pos_hma.data.Product
import com.example.pos_hma.databinding.FragmentAdminCashierCatalogBinding
import com.example.pos_hma.databinding.ItemProductCatalogGoodsBinding
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.*

private val ID_LOCALE = java.util.Locale("in","ID")
private fun rupiah(v: Long) = java.text.NumberFormat.getInstance(ID_LOCALE).format(v)

class AdminCashierCatalogFragment : Fragment() {

    private var _binding: FragmentAdminCashierCatalogBinding? = null
    private val binding get() = _binding!!

    private val db by lazy { FirebaseFirestore.getInstance() }
    private var reg: ListenerRegistration? = null
    private val all = mutableListOf<Product>()

    private val cartVm: CartViewModel by activityViewModels()

    private lateinit var svcAdapter: CashierItemAdapter
    private lateinit var goodsAdapter: CashierGoodsRowAdapter

    // Filter state
    private var filterShowServices: Boolean = true
    private var filterShowGoods: Boolean = true
    private val filterCategories = linkedSetOf<String>()
    private var availableCategories: List<String> = emptyList()

    // App bar cart action view + badge (untuk animasi)
    private var cartActionView: View? = null
    private var cartIcon: ImageView? = null
    private var tvCartBadge: TextView? = null
    private var lastBadgeCount: Int = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentAdminCashierCatalogBinding.inflate(inflater, container, false)
        setHasOptionsMenu(true)
        return binding.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        svcAdapter = CashierItemAdapter(
            getQty = { key -> cartVm.lines.value?.get(key)?.qty ?: 0 },
            onPlus = { p -> cartVm.plus(p); animateCartIcon() },
            onMinus = { cartVm.minus(it) },
            layoutRes = R.layout.item_product_service
        )
        goodsAdapter = CashierGoodsRowAdapter(
            getQty = { key -> cartVm.lines.value?.get(key)?.qty ?: 0 },
            onPlus = { p -> cartVm.plus(p); animateCartIcon() },
            onMinus = { cartVm.minus(it) }
        )

        // Services: horizontal swipe-only list
        binding.rvServices.layoutManager = GridLayoutManager(requireContext(), 1, LinearLayoutManager.HORIZONTAL, false)
        binding.rvServices.adapter = svcAdapter
        // Goods/spare parts: use compact cart-row layout vertically
        binding.rvGoods.layoutManager = LinearLayoutManager(requireContext())
        binding.rvGoods.adapter = goodsAdapter

        // search -> filter
        binding.etSearch.addTextChangedListener(object: TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { applyFilter() }
        })

        // Open filter dialog from filter icon
        binding.tilSearch.setStartIconOnClickListener { showFilterDialog() }

        attachProductsListener()

        // Swipe-to-refresh
        binding.swipeRefresh.setOnRefreshListener {
            reg?.remove(); reg = null
            attachProductsListener()
        }

        // observe cart: update cart bar + refresh steppers
        cartVm.lines.observe(viewLifecycleOwner) {
            svcAdapter.notifyDataSetChanged()
            goodsAdapter.notifyDataSetChanged()
        }
        cartVm.totalItems.observe(viewLifecycleOwner) { n ->
            binding.cartBar.isVisible = n > 0
            binding.btnPay.isEnabled = n > 0
            binding.tvCartItems.text = "Total Items: $n"
        }
        cartVm.totalAmount.observe(viewLifecycleOwner) { amt ->
            binding.tvCartTotal.text = "Rp ${rupiah(amt)}"
        }

        // Ensure initial cart bar state is correct (won't disappear on refresh)
        val initItems = cartVm.totalItems.value ?: 0
        binding.cartBar.isVisible = initItems > 0
        binding.btnPay.isEnabled = initItems > 0
        binding.tvCartItems.text = "Total Items: $initItems"
        binding.tvCartTotal.text = "Rp ${rupiah(cartVm.totalAmount.value ?: 0)}"

        binding.btnPay.setOnClickListener {
            findNavController().navigate(R.id.adminCartFragment)
        }
    }

    override fun onDestroyView() {
        reg?.remove(); reg = null
        _binding = null
        super.onDestroyView()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_cashier_appbar, menu)
        val item = menu.findItem(R.id.action_cart)
        cartActionView = item.actionView
        cartIcon = cartActionView?.findViewById(R.id.ivCart)
        tvCartBadge = cartActionView?.findViewById(R.id.tvBadgeCount)

        updateBadge(cartVm.totalItems.value ?: 0)
        cartVm.totalItems.observe(viewLifecycleOwner) { n -> updateBadge(n) }

        cartActionView?.setOnClickListener {
            findNavController().navigate(R.id.adminCartFragment)
        }
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_cart -> { findNavController().navigate(R.id.adminCartFragment); true }
            R.id.action_profile -> { showProfileDialog(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun attachProductsListener() {
        binding.swipeRefresh.isRefreshing = true
        reg?.remove(); reg = null
        reg = db.collection("products")
            .orderBy("nameLowercase")
            .addSnapshotListener { snap, e ->
                if (e != null) {
                    Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show()
                    binding.swipeRefresh.isRefreshing = false
                    return@addSnapshotListener
                }
                val items = snap!!.documents.map { d ->
                    d.toObject(Product::class.java)?.copy(id = d.id)
                        ?: Product(id = d.id, sku = d.id, name = d.getString("name") ?: d.id)
                }
                all.clear(); all.addAll(items)
                // Update available categories from dataset
                availableCategories = all.mapNotNull { it.categoryName.takeIf { n -> n.isNotBlank() } }
                    .distinct().sorted()
                applyFilter()
                binding.swipeRefresh.isRefreshing = false
            }
    }

    private fun updateBadge(n: Int) {
        lastBadgeCount = n
        val tv = tvCartBadge ?: return
        if (n <= 0) {
            tv.visibility = View.GONE
        } else {
            tv.visibility = View.VISIBLE
            tv.text = n.toString()
        }
    }

    private fun animateCartIcon() {
        val v = cartIcon ?: return
        v.animate().cancel()
        v.scaleX = 1f; v.scaleY = 1f
        v.animate().scaleX(1.15f).scaleY(1.15f).setDuration(120)
            .withEndAction { v.animate().scaleX(1f).scaleY(1f).setDuration(120).start() }
            .start()
        tvCartBadge?.let { b ->
            b.animate().cancel()
            b.scaleX = 0.9f; b.scaleY = 0.9f; b.alpha = 0.9f
            b.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(180).start()
        }
    }

    private fun applyFilter() {
        val q = binding.etSearch.text?.toString()?.trim()?.lowercase().orEmpty()
        fun nameOk(p: Product) = q.isEmpty() || p.nameLowercase.contains(q)
        fun catOk(p: Product) = filterCategories.isEmpty() || filterCategories.contains(p.categoryName)

        val svc = all.filter { it.isService && filterShowServices && nameOk(it) && catOk(it) }
        val goods = all.filter { !it.isService && filterShowGoods && nameOk(it) && catOk(it) }
        svcAdapter.submit(svc)
        goodsAdapter.submit(goods)
        binding.tvSectionService.isVisible = svc.isNotEmpty()
        binding.tvSectionGoods.isVisible = goods.isNotEmpty()
    }

    private fun showFilterDialog() {
        val ctx = requireContext()
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_catalog_filter, null)
        val cbSvc = view.findViewById<MaterialCheckBox>(R.id.cbServices)
        val cbGoods = view.findViewById<MaterialCheckBox>(R.id.cbGoods)
        val chipGroup = view.findViewById<ChipGroup>(R.id.chipGroupCategories)

        cbSvc.isChecked = filterShowServices
        cbGoods.isChecked = filterShowGoods

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
        val cats = if (availableCategories.isNotEmpty()) availableCategories
        else all.mapNotNull { it.categoryName.takeIf { n -> n.isNotBlank() } }.distinct().sorted()
        cats.forEach { cat ->
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
            .setTitle("Filter Katalog")
            .setView(view)
            .setNegativeButton("Batal", null)
            .setNeutralButton("Reset") { _, _ ->
                filterShowServices = true
                filterShowGoods = true
                filterCategories.clear()
                applyFilter()
            }
            .setPositiveButton("Terapkan") { _, _ ->
                filterShowServices = cbSvc.isChecked
                filterShowGoods = cbGoods.isChecked
                val selected = linkedSetOf<String>()
                for (i in 0 until chipGroup.childCount) {
                    val c = chipGroup.getChildAt(i)
                    if (c is Chip && c != anyChip && c.isChecked) {
                        (c.tag as? String)?.let { selected.add(it) }
                    }
                }
                filterCategories.clear(); filterCategories.addAll(selected)
                applyFilter()
            }
            .show()
    }

    private fun showProfileDialog() {
        val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(user.email ?: "Profil")
            .setMessage("Keluar dari akun ini?")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Keluar") { _, _ ->
                com.example.pos_hma.utils.AppFlags.isLoggingOut = true
                com.google.firebase.auth.FirebaseAuth.getInstance().signOut()
                requireActivity().finish()
            }.show()
    }
}

/* ===== Adapter item katalog (no multi-select) ===== */
private class CashierItemAdapter(
    private val getQty: (key: String) -> Int,
    private val onPlus: (Product) -> Unit,
    private val onMinus: (Product) -> Unit,
    private val layoutRes: Int = R.layout.item_product_cashier
) : RecyclerView.Adapter<CashierItemAdapter.VH>() {

    private val items = mutableListOf<Product>()
    fun submit(list: List<Product>) { items.clear(); items.addAll(list); notifyDataSetChanged() }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return VH(v, getQty, onPlus, onMinus)
    }
    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount(): Int = items.size

    class VH(
        v: View,
        val getQty: (String) -> Int,
        val onPlus: (Product) -> Unit,
        val onMinus: (Product) -> Unit
    ) : RecyclerView.ViewHolder(v) {

        private val img: android.widget.ImageView = v.findViewById(R.id.img)
        private val overlay: View = v.findViewById(R.id.overlayOutOfStock)
        private val tvBadge: android.widget.TextView = v.findViewById(R.id.tvBadge)
        private val tvName: android.widget.TextView = v.findViewById(R.id.tvName)
        private val tvStock: android.widget.TextView = v.findViewById(R.id.tvStock)
        private val tvCategory: android.widget.TextView = v.findViewById(R.id.tvCategory)
        private val tvPrice: android.widget.TextView = v.findViewById(R.id.tvPrice)
        private val btnMinus: com.google.android.material.button.MaterialButton = v.findViewById(R.id.btnMinus)
        private val btnPlus: com.google.android.material.button.MaterialButton = v.findViewById(R.id.btnPlus)
        private val tvQty: android.widget.TextView = v.findViewById(R.id.tvQtyInCart)
        private val card: com.google.android.material.card.MaterialCardView = v.findViewById(R.id.cardRoot)

        fun bind(p: Product) {
            val key = p.sku.ifBlank { p.id }

            img.load(p.images.firstOrNull() ?: R.drawable.store)
            val inStock = p.isService || p.stock > 0
            tvBadge.text = if (p.isService) "Jasa" else if (inStock) "Stock Tersedia" else "Stock Habis"
            tvBadge.setBackgroundResource(
                if (p.isService) R.drawable.bg_badge_green
                else if (inStock) R.drawable.bg_badge_green else R.drawable.bg_badge_red
            )

            tvName.text = p.name
            tvStock.text = if (p.isService) "Jasa (tanpa stok)" else "Stock      : ${p.stock}"
            tvCategory.text = if (p.categoryName.isNotBlank()) "Kategori : ${p.categoryName}" else "Kategori : -"
            tvPrice.text = if (p.isService) "Harga: input kasir" else "Rp ${rupiah(p.salePrice)}"

            val out = !p.isService && p.stock <= 0
            overlay.visibility = if (out) View.VISIBLE else View.GONE
            card.alpha = if (out) .6f else 1f

            val qty = getQty(key)
            tvQty.text = qty.toString()
            btnMinus.isEnabled = qty > 0
            btnPlus.isEnabled = if (p.isService) qty < 1 else qty < p.stock

            btnMinus.setOnClickListener { onMinus(p) }
            btnPlus.setOnClickListener {
                if (!p.isService && qty >= p.stock) return@setOnClickListener
                onPlus(p)
            }
        }
    }
}

/* ===== Adapter barang di katalog menggunakan item_cart_line_cashier ===== */
private class CashierGoodsRowAdapter(
    private val getQty: (key: String) -> Int,
    private val onPlus: (Product) -> Unit,
    private val onMinus: (Product) -> Unit
) : RecyclerView.Adapter<CashierGoodsRowAdapter.VH>() {

    private val items = mutableListOf<Product>()
    fun submit(list: List<Product>) { items.clear(); items.addAll(list); notifyDataSetChanged() }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemProductCatalogGoodsBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b, getQty, onPlus, onMinus)
    }
    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount(): Int = items.size

    class VH(
        private val b: ItemProductCatalogGoodsBinding,
        val getQty: (String) -> Int,
        val onPlus: (Product) -> Unit,
        val onMinus: (Product) -> Unit
    ) : RecyclerView.ViewHolder(b.root) {
        fun bind(p: Product) {
            val key = p.sku.ifBlank { p.id }
            b.img.load(p.images.firstOrNull() ?: R.drawable.store)
            b.tvName.text = p.name
            b.tvCategory.text = if (p.categoryName.isNotBlank()) "Kategori : ${p.categoryName}" else "Kategori : -"
            b.tvStock.text = "Stok: ${p.stock}"
            b.tvPrice.text = "Rp ${rupiah(p.salePrice)}"

            val qty = getQty(key)
            b.tvQty.text = qty.toString()

            val out = p.stock <= 0
            b.tvBadge.visibility = View.VISIBLE
            b.tvBadge.text = if (out) "Stok Habis" else "Stok Tersedia"
            b.tvBadge.setBackgroundResource(if (out) R.drawable.bg_badge_red else R.drawable.bg_badge_green)
            val normalColor = androidx.core.content.ContextCompat.getColor(b.root.context, R.color.md_theme_onSurface)
            val errorColor = androidx.core.content.ContextCompat.getColor(b.root.context, R.color.md_theme_error)
            b.tvStock.setTextColor(if (out) errorColor else normalColor)

            // Enablement according to stock rule
            b.btnMinus.isEnabled = qty > 0
            val canPlus = !out && qty < p.stock
            b.btnPlus.isEnabled = canPlus
            b.btnPlus.alpha = if (canPlus) 1f else 0.4f
            b.btnMinus.alpha = if (qty > 0) 1f else 0.4f

            b.btnMinus.setOnClickListener { onMinus(p) }
            b.btnPlus.setOnClickListener {
                if (!p.isService && qty >= p.stock) return@setOnClickListener
                onPlus(p)
            }
        }
    }
}
