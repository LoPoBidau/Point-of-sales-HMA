package com.example.pos_hma.ui.role.super_admin

import android.app.DatePickerDialog
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.*
import android.widget.*
import android.graphics.drawable.GradientDrawable
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.pos_hma.R
import com.example.pos_hma.data.Category
import com.example.pos_hma.data.InventoryMovement
import com.example.pos_hma.data.Product
import com.example.pos_hma.databinding.*
import com.example.pos_hma.worker.ScheduledStockPostingWorker
import com.example.pos_hma.util.StockNotificationHelper
import com.example.pos_hma.utils.AppFlags
import com.example.pos_hma.utils.SnapshotDisposable
import com.google.android.material.color.MaterialColors
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import com.google.firebase.storage.FirebaseStorage
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import java.util.Calendar

// ===== formatter uang "10.000" =====
private val ID_LOCALE = Locale("in", "ID")
private fun rupiah(v: Long): String = NumberFormat.getInstance(ID_LOCALE).format(v)

private fun CharSequence?.digitsOnly(): String = this?.toString()?.filter { it.isDigit() } ?: ""
private fun CharSequence?.asCleanLongOrNull(): Long? {
    val digits = digitsOnly()
    if (digits.isEmpty()) return null
    return digits.toLongOrNull()
}
private fun CharSequence?.asCleanLong(): Long = asCleanLongOrNull() ?: 0L

private fun EditText.attachRupiahFormatter() {
    var selfChange = false
    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (selfChange) return
            val raw = s?.toString().orEmpty()
            val digits = raw.filter { it.isDigit() }
            if (digits.isEmpty()) {
                selfChange = true
                setText("")
                selfChange = false
                return
            }
            val value = digits.toLongOrNull() ?: return
            val formatted = rupiah(value)
            if (formatted == raw) return
            selfChange = true
            setText(formatted)
            setSelection(formatted.length)
            selfChange = false
        }
    })
}

class SuperAdminProductFragment : Fragment(), SnapshotDisposable {

    private var _binding: FragmentSuperAdminProductBinding? = null
    private val binding get() = _binding!!

    private val db by lazy { FirebaseFirestore.getInstance() }
    private val stockEventVm by activityViewModels<StockEventViewModel>()

    // role
    private var currentRole: String = "superadmin"
    private fun loadCurrentRole() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        db.collection("users").document(uid).get()
            .addOnSuccessListener { currentRole = (it.getString("role") ?: "superadmin").lowercase() }
    }
    private fun requiresApprovalForAdjustment() = currentRole in listOf("admin")

    // realtime products
    private var productsReg: ListenerRegistration? = null
    private val allProducts = mutableListOf<Product>()
    private var currentDialog: AlertDialog? = null

    // image picker utk form
    private var selectedImageUri: Uri? = null
    private var formImgPreview: ImageView? = null
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) { selectedImageUri = uri; formImgPreview?.load(uri) }
    }

    // kategori
    private val catFilterList = mutableListOf<Category>()
    private val catFormList = mutableListOf<Category>()
    private var selectedFilterCategory: Category? = null
    private var categoriesReg: ListenerRegistration? = null
    private var activeCategoryIds: MutableSet<String> = mutableSetOf()

    private lateinit var adapter: ProductsAdapter
    private var forTypeTab: String = "goods" // "goods" or "service"

    companion object {
        private const val ARG_FOR_TYPE = "for_type"
        fun newInstance(forType: String): SuperAdminProductFragment = SuperAdminProductFragment().apply {
            arguments = Bundle().apply { putString(ARG_FOR_TYPE, forType) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentSuperAdminProductBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        forTypeTab = when (arguments?.getString(ARG_FOR_TYPE)?.lowercase()) {
            "service", "jasa" -> "service"
            else -> "goods"
        }
        setupUi()
        loadCurrentRole()
        listenProducts()
        listenCategoriesForUi()
    }

    override fun onStop() { super.onStop(); disposeSnapshots() }
    override fun onDestroyView() {
        disposeSnapshots()
        categoriesReg?.remove(); categoriesReg = null
        currentDialog?.dismiss(); currentDialog = null
        _binding = null
        super.onDestroyView()
    }
    override fun disposeSnapshots() { productsReg?.remove(); productsReg = null }

    // ================= UI utama =================
    private fun setupUi() {
        binding.rvProducts.layoutManager = GridLayoutManager(requireContext(), 2)
        val allowLongPress = forTypeTab != "service"
        adapter = ProductsAdapter(
            onReceive = { product, anchor -> openReceiveFlow(product, anchor) },
            onEdit    = { openForm(it) },
            onDelete  = { confirmDelete(it) },
            onMore    = { showProductActions(it) },  // long press
            allowLongPress = allowLongPress
        )
        binding.rvProducts.adapter = adapter
        binding.fabAdd.setOnClickListener { openForm(null) }

        binding.etSearch.addTextChangedListener(object: TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { applyFilter() }
        })

        // Filter kategori (exposed dropdown di layar utama)
        binding.actCategory.inputType = InputType.TYPE_NULL
        binding.actCategory.keyListener = null
        binding.actCategory.isCursorVisible = false
        binding.actCategory.setOnClickListener { binding.actCategory.showDropDown() }
        binding.tilCategory.setEndIconOnClickListener { binding.actCategory.showDropDown() }
        binding.actCategory.setOnItemClickListener { _, _, pos, _ ->
            selectedFilterCategory = if (pos == 0) null else catFilterList.getOrNull(pos - 1)
            applyFilter()
        }
        loadCategoriesForFilter()

        binding.swipeRefresh.setOnRefreshListener { refreshOnce() }
    }

    private fun loadCategoriesForFilter() {
        db.collection("categories").get()
            .addOnSuccessListener { qs ->
                if (!isAdded || view == null) return@addOnSuccessListener
                if (!viewLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return@addOnSuccessListener
                val b = _binding ?: return@addOnSuccessListener
                val list = qs.documents.mapNotNull { d ->
                    val isActive = d.getBoolean("isActive") ?: true
                    if (!isActive) return@mapNotNull null
                    d.toObject(Category::class.java)?.copy(id = d.id) ?: Category(
                        id = d.id,
                        name = d.getString("name") ?: d.id,
                        slug = d.getString("slug") ?: d.id,
                        forType = d.getString("forType") ?: "both",
                        isActive = isActive,
                        sortOrder = d.getLong("sortOrder") ?: 0L,
                        order = d.getLong("order")
                    )
                }.sortedWith(compareBy<Category> { it.effectiveOrder }.thenBy { it.name })

                val prevId = selectedFilterCategory?.id
                catFilterList.clear(); catFilterList.addAll(list)
                activeCategoryIds = list.map { it.id }.toMutableSet()
                val names = mutableListOf("Semua").apply { addAll(list.map { it.name }) }
                b.actCategory.setSimpleItems(names.toTypedArray())
                val idx = if (prevId != null) list.indexOfFirst { it.id == prevId } else -1
                if (idx >= 0) {
                    b.actCategory.setText(names[idx + 1], false)
                    selectedFilterCategory = list[idx]
                } else {
                    b.actCategory.setText(names.first(), false)
                    selectedFilterCategory = null
                }
                adapter.updateKnownCategoryIds(activeCategoryIds)
                applyFilter()
            }
            .addOnFailureListener { e -> context?.let { Toast.makeText(it, "Kategori gagal dimuat: ${e.message}", Toast.LENGTH_LONG).show() } }
    }

    private fun listenCategoriesForUi() {
        categoriesReg?.remove()
        categoriesReg = db.collection("categories").addSnapshotListener { snap, e ->
            if (_binding == null) return@addSnapshotListener
            if (e != null) return@addSnapshotListener
            if (snap == null) return@addSnapshotListener

            val activeList = snap.documents.mapNotNull { d ->
                val isActive = d.getBoolean("isActive") ?: true
                if (!isActive) return@mapNotNull null
                d.toObject(Category::class.java)?.copy(id = d.id) ?: Category(
                    id = d.id,
                    name = d.getString("name") ?: d.id,
                    slug = d.getString("slug") ?: d.id,
                    forType = d.getString("forType") ?: "both",
                    isActive = isActive,
                    sortOrder = d.getLong("sortOrder") ?: 0L,
                    order = d.getLong("order")
                )
            }.sortedWith(compareBy<Category> { it.effectiveOrder }.thenBy { it.name })

            val newIds = activeList.map { it.id }.toMutableSet()
            val removedActive = (activeCategoryIds - newIds).toSet()
            val removedDocs = snap.documentChanges.filter { it.type == DocumentChange.Type.REMOVED }.map { it.document.id }
            val removedAll = (removedActive + removedDocs).toSet()

            val prevId = selectedFilterCategory?.id
            activeCategoryIds = newIds
            catFilterList.clear(); catFilterList.addAll(activeList)
            val names = mutableListOf("Semua").apply { addAll(activeList.map { it.name }) }
            val b = _binding ?: return@addSnapshotListener
            b.actCategory.setSimpleItems(names.toTypedArray())
            val idx = if (prevId != null) activeList.indexOfFirst { it.id == prevId } else -1
            if (idx >= 0) {
                b.actCategory.setText(names[idx + 1], false)
                selectedFilterCategory = activeList[idx]
            } else {
                b.actCategory.setText(names.first(), false)
                selectedFilterCategory = null
            }
            adapter.updateKnownCategoryIds(activeCategoryIds)
            applyFilter()

            removedAll.forEach { cid -> handleCategoryRemoved(cid) }
        }
    }

    private fun handleCategoryRemoved(catId: String) {
        db.collection("products").whereEqualTo("categoryId", catId).get()
            .addOnSuccessListener { qs ->
                if (qs.isEmpty) return@addOnSuccessListener
                val batch = db.batch()
                qs.documents.forEach { d -> batch.update(d.reference, mapOf("categoryId" to "", "categoryName" to "")) }
                batch.commit()
            }
    }

    // ===== realtime products =====
    private fun listenProducts() {
        disposeSnapshots()
        productsReg = db.collection("products")
            .orderBy("nameLowercase")
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, e ->
                if (_binding == null) return@addSnapshotListener
                if (e != null) {
                    if (e is FirebaseFirestoreException &&
                        e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED &&
                        (AppFlags.isLoggingOut || FirebaseAuth.getInstance().currentUser == null)) return@addSnapshotListener
                    toast("Gagal: ${e.message}"); return@addSnapshotListener
                }
                val items = snap!!.documents.map { d ->
                    val p = d.toObject(Product::class.java)
                    val base = p?.copy(id = d.id) ?: Product(
                        id = d.id,
                        sku = d.getString("sku") ?: d.id,
                        name = d.getString("name") ?: d.id
                    )
                    if (base.nameLowercase.isBlank()) base.copy(nameLowercase = base.name.lowercase()) else base
                }
                allProducts.clear(); allProducts.addAll(items)
                applyFilter()
            }
    }

    private fun refreshOnce() {
        db.collection("products").orderBy("nameLowercase").get()
            .addOnSuccessListener { qs ->
                allProducts.clear()
                allProducts.addAll(qs.documents.map { d -> d.toObject(Product::class.java)!!.copy(id = d.id) })
                applyFilter()
            }
            .addOnFailureListener { e -> toast("Refresh gagal: ${e.message}") }
            .addOnCompleteListener { _binding?.swipeRefresh?.isRefreshing = false }
    }

    private fun applyFilter() {
        val b = _binding ?: return
        val q = b.etSearch.text?.toString()?.trim()?.lowercase().orEmpty()
        val catId = selectedFilterCategory?.id.orEmpty()
        val filtered = allProducts.filter { p ->
            val okName = q.isEmpty() || p.nameLowercase.contains(q)
            val okCat  = selectedFilterCategory == null || p.categoryId == catId
            val okType = if (forTypeTab == "service") p.isService else !p.isService
            okName && okCat && okType
        }
        val sorted = filtered.sortedWith(compareBy<Product> { p ->
            val trackable = p.trackStock && !p.isService
            if (trackable && p.stock <= 0L) 1 else 0
        }.thenBy { it.nameLowercase })
        adapter.submitList(sorted)
        b.tvCount.text = "Total: ${sorted.size}"
    }

    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_LONG).show()
    private fun normalizeSku(s: String) = s.trim().uppercase().replace("\\s+".toRegex(), "-")
    private fun slugify(s: String) = s.trim().lowercase().replace("[^a-z0-9\\s-]".toRegex(), "").replace("\\s+".toRegex(), "-")

    // ====== Normalisasi tipe kategori (ID/EN -> key) ======
    private fun toTypeKey(raw: String?): String {
        val s = raw?.trim()?.lowercase().orEmpty()
        return when (s) {
            "both", "semua", "all", "barang & jasa", "barang dan jasa", "barang+jasa" -> "both"
            "goods", "barang", "product", "produk" -> "goods"
            "service", "services", "jasa" -> "service"
            else -> if (s.isBlank()) "both" else s
        }
    }

    // ====== MUAT KATEGORI AKTIF untuk FORM ======
    private fun loadCategoriesForForm(forType: String, onDone: (() -> Unit)? = null) {
        val want = toTypeKey(forType)
        db.collection("categories")
            .get()
            .addOnSuccessListener { qs ->
                val all = qs.documents.mapNotNull { d ->
                    val active = d.getBoolean("isActive") ?: true
                    if (!active) return@mapNotNull null
                    val c = d.toObject(Category::class.java)?.copy(id = d.id) ?: Category(
                        id = d.id,
                        name = d.getString("name") ?: d.id,
                        slug = d.getString("slug") ?: d.id,
                        forType = d.getString("forType") ?: "both",
                        isActive = active,
                        sortOrder = d.getLong("sortOrder") ?: 0L,
                        order = d.getLong("order")
                    )
                    c.copy(forType = toTypeKey(c.forType))
                }

                val filtered = all.filter { it.forType == "both" || it.forType == want }
                    .sortedWith(compareBy<Category> { it.effectiveOrder }.thenBy { it.name })

                catFormList.clear()
                catFormList.addAll(filtered)
                onDone?.invoke()
            }
            .addOnFailureListener { toast("Kategori gagal dimuat: ${it.message}") }
    }

    // ====== Dialog pemilih kategori (dipakai di FORM) ======
    private fun openCategoryPickerDialog(
        forType: String,
        preselectedId: String?,
        onPicked: (Category?) -> Unit
    ) {
        loadCategoriesForForm(forType) {
            val names = catFormList.map { it.name }.toTypedArray()
            if (names.isEmpty()) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Pilih Kategori")
                    .setMessage("Tidak ada kategori aktif untuk tipe ini")
                    .setPositiveButton("Tutup", null)
                    .show()
                return@loadCategoriesForForm
            }
            var selectedIndex = if (preselectedId.isNullOrEmpty()) -1 else catFormList.indexOfFirst { it.id == preselectedId }
            var chosen: Category? = if (selectedIndex >= 0) catFormList[selectedIndex] else null

            val dlg = MaterialAlertDialogBuilder(requireContext())
                .setTitle("Pilih Kategori")
                .setSingleChoiceItems(names, selectedIndex) { _, which ->
                    selectedIndex = which
                    chosen = catFormList.getOrNull(which)
                }
                .setNegativeButton("Batal", null)
                .setPositiveButton("Pilih") { d, _ ->
                    onPicked(chosen)
                    d.dismiss()
                }
                .create()
            dlg.show()
        }
    }

    // ================= FORM PRODUK =================
    private fun openForm(p: Product?) {
        selectedImageUri = null
        val form = DialogProductFormBinding.inflate(layoutInflater)
        formImgPreview = form.imgPreview

        // Field kategori: non-keyboard, klik => buka dialog
        val actCat = (form.actCategory as MaterialAutoCompleteTextView).apply {
            inputType = InputType.TYPE_NULL
            keyListener = null
            isCursorVisible = false
            isFocusable = true
            isFocusableInTouchMode = true
            setText("", false) // placeholder
        }

        var selectedFormCategory: Category? = null
        val isEditing = p != null
        val fixedIsService = p?.isService ?: (forTypeTab == "service")

        // ===== Prefill non-kategori =====
        if (isEditing) {
            form.swService.visibility = View.GONE
            form.tilStock.visibility = View.GONE
            form.tilInitCost.visibility = View.GONE

            form.etName.setText(p!!.name)
            form.etSKU.setText(p.sku); form.etSKU.isEnabled = false
            form.etPrice.setText(rupiah(p.salePrice))

            if (fixedIsService) { form.tilPrice.hint = "Harga ditentukan kasir"; form.etPrice.isEnabled = false }
            else { form.tilPrice.hint = "Harga jual (Rp)"; form.etPrice.isEnabled = true }

            if (p.images.firstOrNull().isNullOrBlank()) {
                form.imgPreview.setImageResource(R.drawable.store); form.imgPreview.alpha = .25f
            } else { form.imgPreview.alpha = 1f; form.imgPreview.load(p.images.first()) }
        } else {
            // Non-editing: lock to current tab type
            form.imgPreview.setImageResource(R.drawable.store); form.imgPreview.alpha = .25f
            if (forTypeTab == "service") {
                // Service tab: create service only
                form.swService.visibility = View.GONE
                form.tilStock.visibility = View.GONE
                form.tilInitCost.visibility = View.GONE
                form.tilPrice.hint = "Harga ditentukan kasir"
                form.etPrice.setText("")
                form.etPrice.isEnabled = false
            } else {
                // Goods tab: create goods only
                form.swService.visibility = View.GONE
                form.tilStock.visibility = View.VISIBLE
                form.tilInitCost.visibility = View.VISIBLE
                form.tilPrice.hint = "Harga jual (Rp)"
                form.etPrice.isEnabled = true
            }
        }

        // Helper tipe saat ini (goods/service)
        fun currentTypeKey(): String = if (isEditing) { if (fixedIsService) "service" else "goods" } else { forTypeTab }

        // Muat awal + preselect untuk EDIT
        loadCategoriesForForm(currentTypeKey()) {
            if (isEditing && p!!.categoryId.isNotBlank()) {
                val idx = catFormList.indexOfFirst { it.id == p.categoryId }
                if (idx >= 0) {
                    selectedFormCategory = catFormList[idx]
                    actCat.setText(selectedFormCategory!!.name, false)
                }
            }
        }

        // Tap di field/ikon => buka dialog pemilih kategori
        fun openCatDialog() {
            openCategoryPickerDialog(currentTypeKey(), selectedFormCategory?.id) { picked ->
                selectedFormCategory = picked
                if (picked != null) {
                    actCat.setText(picked.name, false)
                    form.tilCategory.error = null
                } else {
                    actCat.setText("", false)
                }
            }
        }
        actCat.setOnClickListener { openCatDialog() }
        form.tilCategory.setEndIconOnClickListener { openCatDialog() }

        // CREATE: ubah daftar saat switch jasa/barang berubah
        if (!isEditing) {
            form.swService.setOnCheckedChangeListener { _, isSvc ->
                form.tilStock.visibility = if (isSvc) View.GONE else View.VISIBLE
                form.tilInitCost.visibility = if (isSvc) View.GONE else View.VISIBLE
                if (isSvc) {
                    form.tilPrice.hint = "Harga ditentukan kasir"
                    form.etPrice.setText("")
                    form.etPrice.isEnabled = false
                } else {
                    form.tilPrice.hint = "Harga jual (Rp)"
                    form.etPrice.isEnabled = true
                }
                // Muat kategori sesuai tipe baru dan reset pilihan
                loadCategoriesForForm(currentTypeKey()) {
                    actCat.setText("", false)
                    selectedFormCategory = null
                }
            }
        }

        // Pasang formatter rupiah
        form.etPrice.attachRupiahFormatter()
        form.etInitCost.attachRupiahFormatter()

        // Foto
        form.btnPickImage.setOnClickListener { pickImage.launch("image/*") }
        form.btnRemoveImage.setOnClickListener {
            selectedImageUri = null
            form.imgPreview.setImageResource(R.drawable.store); form.imgPreview.alpha = .25f
        }

        // Build dialog & tombol SIMPAN
        val dlg = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (isEditing) "Edit Produk" else "Produk Baru")
            .setView(form.root)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan", null)
            .create()

        currentDialog?.dismiss(); currentDialog = dlg
        dlg.setOnDismissListener { currentDialog = null }

        dlg.setOnShowListener {
            val btn = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
            btn.setOnClickListener {
                // reset error
                listOf(form.tilName, form.tilSKU, form.tilCategory, form.tilPrice, form.tilStock, form.tilInitCost).forEach { it.error = null }
                form.tvPhotoError.visibility = View.GONE; form.cardImage.strokeWidth = 0

                var ok = true
                if (form.etName.text.isNullOrBlank()) { form.tilName.error = "Harus diisi"; ok = false }
                if (!isEditing && form.etSKU.text.isNullOrBlank()) { form.tilSKU.error = "Harus diisi"; ok = false }

                val isService = if (isEditing) fixedIsService else form.swService.isChecked
                val salePrice = if (isService) 0L else form.etPrice.text.asCleanLong()
                if (!isService && salePrice <= 0) { form.tilPrice.error = "Harus diisi"; ok = false }

                val initStock = if (isService) 0L else (form.etStock.text.toString().toLongOrNull() ?: 0L)
                val initCost  = if (isService) 0L else form.etInitCost.text.asCleanLong()
                if (!isService) {
                    if (initStock < 0) { form.tilStock.error = "Tidak boleh negatif"; ok = false }
                    if (initStock > 0 && initCost <= 0) { form.tilInitCost.error = "Harus diisi"; ok = false }
                }

                val needPhoto = !isEditing
                if (needPhoto && selectedImageUri == null) {
                    form.tvPhotoError.visibility = View.VISIBLE
                    val errColor = MaterialColors.getColor(form.cardImage, com.google.android.material.R.attr.colorError)
                    form.cardImage.setStrokeColor(errColor)
                    form.cardImage.strokeWidth = (2 * resources.displayMetrics.density).toInt()
                    ok = false
                }
                if (!ok) return@setOnClickListener

                val name = form.etName.text.toString().trim()
                val cat = selectedFormCategory
                val catId = cat?.id.orEmpty()
                val catName = cat?.name.orEmpty()

                if (!isEditing) {
                    val sku = normalizeSku(form.etSKU.text.toString())
                    val pRef = db.collection("products").document(sku)
                    db.runTransaction { trx ->
                        if (trx.get(pRef).exists()) throw IllegalStateException("EXISTS")
                        val now = FieldValue.serverTimestamp()
                        trx.set(pRef, mapOf(
                            "name" to name, "nameLowercase" to name.lowercase(),
                            "sku" to sku, "categoryId" to catId, "categoryName" to catName,
                            "type" to if (isService) "service" else "goods",
                            "trackStock" to !isService,
                            "stock" to (if (isService) 0L else initStock),
                            "lastCost" to (if (isService) 0L else if (initStock>0) initCost else 0L),
                            "salePrice" to salePrice, "images" to emptyList<String>(),
                            "isActive" to true, "createdAt" to now, "updatedAt" to now
                        ))
                        if (!isService && initStock > 0) {
                            val now2 = FieldValue.serverTimestamp()
                            // Log movement for initial stock
                            val mv = db.collection("inventory_movements").document()
                            trx.set(mv, mapOf(
                                "sku" to sku, "type" to "PURCHASE",
                                "qtyDelta" to initStock, "unitCost" to initCost,
                                "createdAt" to now2, "refId" to "INIT"
                            ))
                            // Create a minimal purchase record
                            val po = db.collection("purchases").document()
                            trx.set(po, mapOf(
                                "date" to now2, "createdBy" to FirebaseAuth.getInstance().currentUser?.uid,
                                "items" to listOf(mapOf("sku" to sku, "name" to name, "qty" to initStock,
                                    "unitCost" to initCost, "lineCost" to initStock * initCost)),
                                "note" to "Initial stock"
                            ))
                            // Ensure there is a corresponding stock batch so sales (FIFO) can consume it
                            val batchRef = db.collection("stock_batches").document()
                            trx.set(batchRef, mapOf(
                                "sku" to sku,
                                "unitCost" to initCost,
                                "remainingQty" to initStock,
                                "receivedAt" to now2,
                                "purchaseId" to po.id,
                                "invoiceNo" to "",
                                "supplierName" to "",
                                "supplierId" to "",
                                "dueDate" to com.google.firebase.Timestamp.now(),
                                "salePrice" to salePrice
                            ))
                        }
                        null
                    }.addOnSuccessListener {
                        if (selectedImageUri != null) {
                            uploadImageThen(sku, selectedImageUri!!) { url ->
                                pRef.update("images", listOf(url))
                                    .addOnSuccessListener { toast("Produk + foto tersimpan") }
                                    .addOnFailureListener { toast("Produk tersimpan, foto gagal: ${it.message}") }
                            }
                        }
                        dlg.dismiss()
                    }.addOnFailureListener { e ->
                        if (e is IllegalStateException && e.message == "EXISTS") {
                            form.tilSKU.error = "SKU sudah ada"
                        } else toast("Gagal simpan: ${e.message}")
                    }
                } else {
                    val docId = p!!.sku.ifBlank { p.id }
                    val updates = mutableMapOf<String, Any>(
                        "name" to name, "nameLowercase" to name.lowercase(),
                        "categoryId" to catId, "categoryName" to catName,
                        "salePrice" to salePrice, "updatedAt" to FieldValue.serverTimestamp()
                    )
                    if (selectedImageUri != null) {
                        uploadImageThen(docId, selectedImageUri!!) { url ->
                            updates["images"] = listOf(url)
                            db.collection("products").document(docId)
                                .update(updates).addOnSuccessListener { toast("Diupdate + foto baru"); dlg.dismiss() }
                                .addOnFailureListener { toast("Update gagal: ${it.message}") }
                        }
                    } else {
                        db.collection("products").document(docId)
                            .update(updates).addOnSuccessListener { toast("Diupdate"); dlg.dismiss() }
                            .addOnFailureListener { toast("Update gagal: ${it.message}") }
                    }
                }
            }
        }
        dlg.show()
    }

    // ===== Tambah kategori (tetap sesuai UI kamu) =====
    private fun openAddCategoryDialog(
        defaultType: String,
        onAdded: (Category) -> Unit
    ) {
        val cat = DialogCategoryFormBinding.inflate(layoutInflater)

        (cat.actCatType as MaterialAutoCompleteTextView).apply {
            inputType = InputType.TYPE_NULL
            keyListener = null
            isCursorVisible = false
            setOnClickListener { showDropDown() }
            setSimpleItems(arrayOf("Barang", "Jasa", "Barang & Jasa"))
            val def = when (defaultType.trim().lowercase()) {
                "barang", "goods" -> "Barang"
                "jasa", "service" -> "Jasa"
                "barang & jasa", "both" -> "Barang & Jasa"
                else -> "Barang & Jasa"
            }
            setText(def, false)
        }

        val dlg = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Kategori baru")
            .setView(cat.root)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan", null)
            .create()

        dlg.setOnShowListener {
            val btn = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
            btn.setOnClickListener {
                cat.tilCatName.error = null

                val name = cat.etCatName.text?.toString()?.trim().orEmpty()
                val forType = (cat.actCatType.text?.toString()?.trim()).orEmpty().lowercase().let {
                    when (it) {
                        "barang" -> "barang"
                        "jasa" -> "jasa"
                        "barang & jasa" -> "barang & jasa"
                        else -> "barang & jasa"
                    }
                }

                if (name.isEmpty()) { cat.tilCatName.error = "Harus diisi"; return@setOnClickListener }

                val slug = slugify(name)
                val now = FieldValue.serverTimestamp()
                val ref = db.collection("categories").document(slug)

                ref.set(
                    mapOf(
                        "name" to name,
                        "slug" to slug,
                        "forType" to forType,
                        "isActive" to true,
                        "nameLowercase" to name.lowercase(),
                        "sortOrder" to System.currentTimeMillis(),
                        "createdAt" to now,
                        "updatedAt" to now
                    )
                ).addOnSuccessListener {
                    onAdded(Category(id = slug, name = name, slug = slug, forType = forType, isActive = true))
                    dlg.dismiss()
                }.addOnFailureListener { e ->
                    cat.tilCatName.error = e.message ?: "Gagal menyimpan"
                }
            }
        }
        dlg.show()
    }

    // ===== Menu aksi produk (long press) =====
    private fun showProductActions(p: Product) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += "Riwayat Stok" to { openHistoryDialog(p) }
        if (!p.isService && p.trackStock) {
            actions += "Antrian Pending" to { openPendingQueueDialog(p) }
        }
        actions += "Penyesuaian Stok" to { openAdjustStockDialog(p) }
        actions += "Ubah Harga" to { openQuickUpdatePriceDialog(p) }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(p.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.second?.invoke()
            }
            .show()
    }

    private fun openQuickUpdatePriceDialog(p: Product) {
        val priceBinding = DialogUpdatePricesBinding.inflate(layoutInflater)
        if (p.lastCost > 0) priceBinding.etUnitCost.setText(p.lastCost.toString())
        if (p.salePrice > 0) priceBinding.etNewSalePrice.setText(p.salePrice.toString())
        val dlg = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Ubah Harga ${p.name}")
            .setView(priceBinding.root)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan", null)
            .create()
        showDialogOnce(dlg)
        dlg.setOnShowListener {
            val save = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
            save.setOnClickListener {
                val unitCost = priceBinding.etUnitCost.text.asCleanLong()
                val newSale = priceBinding.etNewSalePrice.text.asCleanLong()
                if (unitCost <= 0 || newSale <= 0) { toast("Harga beli & jual wajib"); return@setOnClickListener }
                val now = FieldValue.serverTimestamp()
                val sku = p.sku.ifBlank { p.id }
                db.collection("products").document(sku)
                    .update(mapOf("lastCost" to unitCost, "salePrice" to newSale, "updatedAt" to now))
                    .addOnSuccessListener { toast("Harga diperbarui") }
                    .addOnFailureListener { e -> toast("Gagal: ${e.message}") }
                dlg.dismiss()
            }
        }
    }

    // ===== Riwayat stok =====
    private fun openHistoryDialog(p: Product) {
        val h = DialogStockHistoryBinding.inflate(layoutInflater)
        val list = mutableListOf<InventoryMovement>()
        val adapter = object : RecyclerView.Adapter<MovVH>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MovVH {
                val row = ItemMovementRowBinding.inflate(layoutInflater, parent, false)
                return MovVH(row)
            }
            override fun getItemCount() = list.size
            override fun onBindViewHolder(holder: MovVH, position: Int) = holder.bind(list[position])
        }
        h.rvHistory.adapter = adapter

        fun render() {
            h.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            adapter.notifyDataSetChanged()
        }

        fun map(doc: DocumentSnapshot) = InventoryMovement(
            id = doc.id,
            sku = doc.getString("sku") ?: p.sku,
            type = doc.getString("type") ?: "",
            qtyDelta = doc.getLong("qtyDelta") ?: 0L,
            unitCost = doc.getLong("unitCost") ?: 0L,
            createdAt = doc.getTimestamp("createdAt"),
            note = doc.getString("note"),
            refId = doc.getString("refId")
        )

        fun load(useOrder: Boolean) {
            h.progress.visibility = View.VISIBLE
            var q: Query = db.collection("inventory_movements")
                .whereEqualTo("sku", p.sku.ifBlank { p.id })
                .limit(100)
            if (useOrder) q = q.orderBy("createdAt", Query.Direction.DESCENDING)
            q.get().addOnSuccessListener { qs ->
                list.clear()
                list.addAll(qs.documents.map(::map))
                if (!useOrder) list.sortByDescending { it.createdAt?.seconds ?: 0 }
                render()
            }.addOnFailureListener { e ->
                if (useOrder && e is FirebaseFirestoreException &&
                    e.code == FirebaseFirestoreException.Code.FAILED_PRECONDITION) {
                    load(false)
                    toast("Index belum dibuat. Data disortir lokal.")
                } else {
                    toast("Gagal memuat riwayat: ${e.message}")
                }
            }.addOnCompleteListener { h.progress.visibility = View.GONE }
        }

        load(true)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Riwayat Stok - ${p.name}")
            .setView(h.root)
            .setPositiveButton("Tutup", null)
            .show()
    }

    private fun openPendingQueueDialog(p: Product) {
        val binding = DialogFifoQueueBinding.inflate(layoutInflater)
        val nf = NumberFormat.getInstance(ID_LOCALE)
        val dfDate = SimpleDateFormat("dd MMM yyyy", ID_LOCALE)
        val dfDateTime = SimpleDateFormat("dd MMM yyyy HH:mm", ID_LOCALE)

        data class PendingRow(
            val invoiceNo: String,
            val supplierName: String,
            val qty: Long,
            val dueDate: com.google.firebase.Timestamp?,
            val scheduledAt: com.google.firebase.Timestamp?,
            val status: String,
            val purchaseId: String?
        )

        val rows = mutableListOf<PendingRow>()

        class PendingVH(private val b: ItemFifoBatchBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(row: PendingRow) {
                b.tvTitle.text = if (row.invoiceNo.isNotBlank()) row.invoiceNo else "Invoice otomatis"
                b.tvSupplier.text = "Supplier: ${row.supplierName.ifBlank { "-" }}"
                b.tvQty.text = "Qty ${nf.format(row.qty)} unit"
                val dueText = row.dueDate?.toDate()?.let { dfDate.format(it) } ?: "-"
                val scheduledText = row.scheduledAt?.toDate()?.let { dfDateTime.format(it) } ?: "-"
                b.tvDue.text = "Jatuh tempo: $dueText (jadwal $scheduledText)"
                val statusPretty = when (row.status.lowercase(Locale.ROOT)) {
                    "processing" -> "Sedang diproses"
                    "posted" -> "Selesai"
                    else -> "Menunggu"
                }
                val purchaseInfo = row.purchaseId?.takeIf { it.isNotBlank() }?.let { " - Purchase ${it.takeLast(6)}" } ?: ""
                b.tvStatus.text = "Status: $statusPretty$purchaseInfo"
            }
        }

        val adapter = object : RecyclerView.Adapter<PendingVH>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PendingVH {
                val row = ItemFifoBatchBinding.inflate(layoutInflater, parent, false)
                return PendingVH(row)
            }

            override fun onBindViewHolder(holder: PendingVH, position: Int) = holder.bind(rows[position])

            override fun getItemCount(): Int = rows.size
        }

        binding.rvQueue.layoutManager = LinearLayoutManager(requireContext())
        binding.rvQueue.adapter = adapter
        binding.progress.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE
        binding.tvSummary.visibility = View.GONE

        fun render() {
            binding.progress.visibility = View.GONE
            if (rows.isEmpty()) {
                binding.tvEmpty.visibility = View.VISIBLE
                binding.tvSummary.visibility = View.GONE
            } else {
                binding.tvEmpty.visibility = View.GONE
                binding.tvSummary.visibility = View.VISIBLE
                val totalQty = rows.sumOf { it.qty }
                binding.tvSummary.text = "Total antrian: ${rows.size} | Qty: ${nf.format(totalQty)}"
            }
            adapter.notifyDataSetChanged()
        }

        val skuKey = p.sku.ifBlank { p.id }
        db.collection("pending_stock_receipts")
            .whereEqualTo("sku", skuKey)
            .get()
            .addOnSuccessListener { snap ->
                rows.clear()
                rows.addAll(
                    snap.documents.mapNotNull { doc ->
                        val qtyVal = doc.getLong("qty") ?: return@mapNotNull null
                        val statusVal = doc.getString("status") ?: "pending"
                        PendingRow(
                            invoiceNo = doc.getString("invoiceNo") ?: "",
                            supplierName = doc.getString("supplierName") ?: "",
                            qty = qtyVal,
                            dueDate = doc.getTimestamp("dueDate"),
                            scheduledAt = doc.getTimestamp("scheduledAt"),
                            status = statusVal,
                            purchaseId = doc.getString("purchaseId")
                        )
                    }.sortedWith(
                        compareBy<PendingRow> { it.dueDate?.toDate()?.time ?: Long.MAX_VALUE }
                            .thenBy { it.scheduledAt?.toDate()?.time ?: Long.MAX_VALUE }
                    )
                )
                render()
            }
            .addOnFailureListener { e ->
                toast("Gagal memuat antrian: ${e.message}")
                rows.clear(); render()
            }
            .addOnCompleteListener { binding.progress.visibility = View.GONE }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Antrian Pending ${p.name}")
            .setView(binding.root)
            .setPositiveButton("Tutup", null)
            .show()
    }

    private class MovVH(private val b: ItemMovementRowBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(m: InventoryMovement) {
            val sign = if (m.qtyDelta >= 0) "+" else ""
            b.tvTitle.text = "${m.type}  (${sign}${m.qtyDelta})"
            val ts = m.createdAt?.toDate()?.toString() ?: "-"
            b.tvSub.text = "${m.note ?: ""}  -  $ts"
        }
    }

    // ===== Penyesuaian stok =====
    private fun openAdjustStockDialog(p: Product) {
        val a = DialogStockAdjustBinding.inflate(layoutInflater)
        a.etAdjUnitCost.attachRupiahFormatter()
        a.rgMode.check(R.id.rbAdd)
        // Tampilkan field harga modal hanya saat mode Tambah
        fun refreshAdjUi() {
            val add = a.rgMode.checkedRadioButtonId == R.id.rbAdd
            a.tilAdjUnitCost.visibility = if (add) View.VISIBLE else View.GONE
            if (add && (a.etAdjUnitCost.text.isNullOrBlank()) && p.lastCost > 0) {
                a.etAdjUnitCost.setText(rupiah(p.lastCost))
            }
        }
        a.rgMode.setOnCheckedChangeListener { _, _ -> refreshAdjUi() }
        refreshAdjUi()

        val dlg = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Penyesuaian Stok - ${p.name}")
            .setView(a.root)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan", null)
            .create()

        currentDialog?.dismiss(); currentDialog = dlg
        dlg.setOnDismissListener { currentDialog = null }

        dlg.setOnShowListener {
            val btn = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
            btn.setOnClickListener {
                a.tilQty.error = null; a.tilReason.error = null
                val qty = a.etQty.text?.toString()?.toLongOrNull() ?: 0L
                val add = a.rgMode.checkedRadioButtonId == R.id.rbAdd
                val reason = a.etReason.text?.toString()?.trim().orEmpty()

                var ok = true
                if (qty <= 0) { a.tilQty.error = "Qty harus > 0"; ok = false }
                if (reason.isEmpty()) { a.tilReason.error = "Harus diisi"; ok = false }
                if (!ok) return@setOnClickListener

                val delta = if (add) qty else -qty
                val sku = p.sku.ifBlank { p.id }

                if (requiresApprovalForAdjustment()) {
                    val now = FieldValue.serverTimestamp()
                    val unitCostAdj = if (add) (a.etAdjUnitCost.text.asCleanLongOrNull() ?: p.lastCost) else 0L
                    val req = mapOf(
                        "sku" to sku,
                        "productName" to p.name,
                        "requestedDelta" to delta,
                        "reason" to reason,
                        "unitCost" to unitCostAdj,
                        "mode" to if (add) "ADD" else "SUB",
                        "status" to "PENDING",
                        "requestedBy" to (FirebaseAuth.getInstance().currentUser?.uid ?: ""),
                        "createdAt" to now
                    )
                    db.collection("stock_adjust_requests").add(req)
                        .addOnSuccessListener {
                            db.collection("notifications").add(
                                mapOf(
                                    "type" to "ADJUSTMENT_REQUEST",
                                    "title" to "Permintaan penyesuaian stok",
                                    "message" to "${p.name}: ${if (delta>=0) "+" else ""}$delta",
                                    "sku" to sku,
                                    "createdAt" to now,
                                    "toRole" to "owner"
                                )
                            )
                            toast("Permintaan dikirim ke Owner")
                            dlg.dismiss()
                        }
                        .addOnFailureListener { e -> toast("Gagal kirim permintaan: ${e.message}") }
                } else {
                    val pRef = db.collection("products").document(sku)
                    val now = FieldValue.serverTimestamp()
                    if (add) {
                        // Tambah stok: butuh harga modal; merge ke batch terakhir jika unitCost >= last.unitCost, else batch baru
                        val unitCostAdj = a.etAdjUnitCost.text.asCleanLongOrNull() ?: p.lastCost
                        if (unitCostAdj <= 0L) { a.tilAdjUnitCost.error = "Harga modal wajib"; return@setOnClickListener }
                        db.collection("stock_batches")
                            .whereEqualTo("sku", sku)
                            .orderBy("receivedAt", Query.Direction.DESCENDING)
                            .limit(1)
                            .get()
                            .addOnSuccessListener { snapLast ->
                                val lastDoc = snapLast.documents.firstOrNull()
                                db.runTransaction { trx ->
                                    val ps = trx.get(pRef)
                                    require(ps.exists()) { "Produk tidak ditemukan" }
                                    require((ps.getBoolean("trackStock") ?: true)) { "Jasa tidak pakai stok" }
                                    val old = ps.getLong("stock") ?: 0L
                                    val newStock = old + qty
                                    val salePriceNow = ps.getLong("salePrice") ?: 0L
                                    trx.update(pRef, mapOf("stock" to newStock, "lastCost" to unitCostAdj, "updatedAt" to now))

                                    var merged = false
                                    if (lastDoc != null) {
                                        val bRef = lastDoc.reference
                                        val bs = trx.get(bRef)
                                        val lastCost = bs.getLong("unitCost") ?: 0L
                                        if (unitCostAdj >= lastCost) {
                                            val remain = bs.getLong("remainingQty") ?: 0L
                                            val newRemain = remain + qty
                                            val weighted = if (newRemain > 0) ((lastCost * remain + unitCostAdj * qty) / newRemain) else unitCostAdj
                                            trx.update(bRef, mapOf("remainingQty" to newRemain, "unitCost" to weighted, "receivedAt" to now))
                                            merged = true
                                        }
                                    }
                                    if (!merged) {
                                        val nb = db.collection("stock_batches").document()
                                        trx.set(nb, mapOf(
                                            "sku" to sku,
                                            "unitCost" to unitCostAdj,
                                            "remainingQty" to qty,
                                            "receivedAt" to now,
                                            "purchaseId" to "",
                                            "invoiceNo" to "",
                                            "dueDate" to com.google.firebase.Timestamp.now(),
                                            "salePrice" to salePriceNow
                                        ))
                                    }
                                    val mv = db.collection("inventory_movements").document()
                                    trx.set(mv, mapOf(
                                        "sku" to sku, "type" to "ADJUSTMENT",
                                        "qtyDelta" to qty, "unitCost" to unitCostAdj,
                                        "createdAt" to now, "refId" to "ADJ", "note" to reason
                                    ))
                                    null
                                }.addOnSuccessListener { toast("Stok ditambah"); dlg.dismiss() }
                                    .addOnFailureListener { e -> toast("Gagal: ${e.message}") }
                            }
                            .addOnFailureListener { e -> toast("Gagal muat batch: ${e.message}") }
                    } else {
                        // Kurangi stok: konsumsi FIFO dari batch tertua
                        val need = qty
                        // prefetch batches ascending until cukup
                        fun fetchEnough(acc: MutableList<DocumentSnapshot> = mutableListOf(), startAfter: DocumentSnapshot? = null) {
                            var q = db.collection("stock_batches")
                                .whereEqualTo("sku", sku)
                                .orderBy("receivedAt", Query.Direction.ASCENDING)
                                .limit(50)
                            if (startAfter != null) q = q.startAfter(startAfter)
                            q.get().addOnSuccessListener { snap ->
                                val docs = snap.documents
                                acc.addAll(docs)
                                val total = acc.sumOf { it.getLong("remainingQty") ?: 0L }
                                if (total < need && docs.isNotEmpty()) fetchEnough(acc, docs.last())
                                else consume(acc)
                            }.addOnFailureListener { e -> toast("Gagal muat batch: ${e.message}") }
                        }
                        fun consume(batches: List<DocumentSnapshot>) {
                            val avail = batches.sumOf { it.getLong("remainingQty") ?: 0L }
                            if (avail < need) { toast("Batch stok tidak cukup"); return }
                            db.runTransaction { trx ->
                                val ps = trx.get(pRef)
                                require(ps.exists()) { "Produk tidak ditemukan" }
                                val track = ps.getBoolean("trackStock") ?: true
                                require(track) { "Jasa tidak pakai stok" }
                                val old = ps.getLong("stock") ?: 0L
                                val newStock = old - need
                                require(newStock >= 0) { "Stok tidak cukup" }
                                trx.update(pRef, mapOf("stock" to newStock, "updatedAt" to now))
                                var remainNeed = need
                                for (d in batches) {
                                    if (remainNeed <= 0) break
                                    val bRef = d.reference
                                    val bs = trx.get(bRef)
                                    val rem = bs.getLong("remainingQty") ?: 0L
                                    if (rem <= 0L) continue
                                    val unit = bs.getLong("unitCost") ?: 0L
                                    val take = kotlin.math.min(remainNeed, rem)
                                    trx.update(bRef, mapOf("remainingQty" to (rem - take)))
                                    val mv = db.collection("inventory_movements").document()
                                    trx.set(mv, mapOf(
                                        "sku" to sku, "type" to "ADJUSTMENT",
                                        "qtyDelta" to -take, "unitCost" to unit,
                                        "createdAt" to now, "refId" to "ADJ", "note" to reason
                                    ))
                                    remainNeed -= take
                                }
                                require(remainNeed == 0L) { "Batch stok tidak cukup" }
                                null
                            }.addOnSuccessListener { toast("Stok dikurangi"); dlg.dismiss() }
                                .addOnFailureListener { e -> toast("Gagal: ${e.message}") }
                        }
                        fetchEnough()
                    }
                }
            }
        }
        dlg.show()
    }

    // ===== Terima Stok + (opsional) ubah harga =====
    private fun openReceiveFlow(p: Product, anchorView: View?) {
        val receiveBinding = DialogStockReceiveBinding.inflate(layoutInflater)
        receiveBinding.tvProductName.text = p.name

        data class SupplierRow(val id: String, val name: String, val term: Long)
        val suppliers = mutableListOf<SupplierRow>()
        db.collection("suppliers").get().addOnSuccessListener { snap ->
            suppliers.clear()
            suppliers.addAll(
                snap.documents.map { doc ->
                    SupplierRow(
                        id = doc.id,
                        name = doc.getString("name") ?: doc.id,
                        term = doc.getLong("paymentTermDays") ?: 0L
                    )
                }.sortedBy { it.name.lowercase() }
            )
            val names = suppliers.map { it.name }.toTypedArray()
            receiveBinding.actSupplier.setSimpleItems(names)
        }

        if (p.lastCost > 0) receiveBinding.etUnitCost.setText(rupiah(p.lastCost))
        receiveBinding.etUnitCost.attachRupiahFormatter()

        val localeId = Locale("in", "ID")
        val dfIso = SimpleDateFormat("yyyy-MM-dd", localeId).apply { isLenient = false }
        var selectedDue: Date? = null

        fun refreshModeUi() {
            val actual = receiveBinding.rbActual.isChecked
            receiveBinding.groupPurchase.visibility = if (actual) View.GONE else View.VISIBLE
            receiveBinding.tvActualInfo.visibility = if (actual) View.VISIBLE else View.GONE
        }

        receiveBinding.rgMode.setOnCheckedChangeListener { _, _ -> refreshModeUi() }
        receiveBinding.rbActual.isChecked = true
        refreshModeUi()

        fun showDatePicker() {
            val cal = Calendar.getInstance()
            (selectedDue ?: Date()).let { cal.time = it }
            DatePickerDialog(requireContext(), { _, year, month, dayOfMonth ->
                val picked = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, dayOfMonth)
                    set(Calendar.HOUR_OF_DAY, 23)
                    set(Calendar.MINUTE, 59)
                    set(Calendar.SECOND, 59)
                    set(Calendar.MILLISECOND, 0)
                }
                selectedDue = picked.time
                receiveBinding.etDueDate.setText(dfIso.format(picked.time))
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }

        receiveBinding.etDueDate.setOnClickListener { showDatePicker() }
        receiveBinding.etDueDate.setOnLongClickListener {
            selectedDue = null
            receiveBinding.etDueDate.text?.clear()
            receiveBinding.tilDueDate.error = null
            true
        }

        val receiveDlg = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Tambah stok: ${p.name}")
            .setView(receiveBinding.root)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan", null)
            .create()

        currentDialog?.dismiss()
        currentDialog = receiveDlg
        receiveDlg.setOnDismissListener { currentDialog = null }

        fun promptPriceDialog(force: Boolean, onOk: (Long, Long) -> Unit) {
            val priceBinding = DialogUpdatePricesBinding.inflate(layoutInflater)
            if (p.lastCost > 0) priceBinding.etUnitCost.setText(rupiah(p.lastCost))
            if (p.salePrice > 0) priceBinding.etNewSalePrice.setText(rupiah(p.salePrice))
            priceBinding.etUnitCost.attachRupiahFormatter()
            priceBinding.etNewSalePrice.attachRupiahFormatter()
            val title = if (force) "Isi harga ${p.name}" else "Ubah harga ${p.name}"
            val priceDlg = MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setView(priceBinding.root)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Simpan", null)
                .create()
            showDialogOnce(priceDlg)
            priceDlg.setOnShowListener {
                val save = priceDlg.getButton(AlertDialog.BUTTON_POSITIVE)
                save.setOnClickListener {
                    priceBinding.tilUnitCost.error = null
                    priceBinding.tilNewSalePrice.error = null
                    val unitCost = priceBinding.etUnitCost.text.asCleanLongOrNull() ?: 0L
                    val salePrice = priceBinding.etNewSalePrice.text.asCleanLongOrNull() ?: 0L
                    var ok = true
                    if (unitCost <= 0) { priceBinding.tilUnitCost.error = "Wajib"; ok = false }
                    if (salePrice <= 0) { priceBinding.tilNewSalePrice.error = "Wajib"; ok = false }
                    if (!ok) return@setOnClickListener
                    priceDlg.dismiss()
                    onOk(unitCost, salePrice)
                }
            }
        }

        receiveDlg.setOnShowListener {
            val save = receiveDlg.getButton(AlertDialog.BUTTON_POSITIVE)
            save.setOnClickListener {
                receiveBinding.tilQty.error = null
                val qty = receiveBinding.etQty.text?.toString()?.toLongOrNull() ?: 0L
                if (qty <= 0) {
                    receiveBinding.tilQty.error = "Qty wajib"
                    return@setOnClickListener
                }

                if (receiveBinding.rbActual.isChecked) {
                    receiveDlg.dismiss()
                    val baseCost = p.lastCost.takeIf { it > 0 } ?: 0L
                    val baseSale = p.salePrice.takeIf { it > 0 } ?: 0L
                    val needPrice = baseCost <= 0 || baseSale <= 0
                    if (needPrice) {
                        promptPriceDialog(force = true) { unitCost, sale ->
                            addActualStock(p, qty, unitCost, sale, anchorView)
                        }
                    } else {
                        MaterialAlertDialogBuilder(requireContext())
                            .setMessage("Update harga beli & harga jual sekalian?")
                            .setNegativeButton("Tidak") { _, _ -> addActualStock(p, qty, baseCost, baseSale, anchorView) }
                            .setPositiveButton("Ya") { _, _ ->
                                promptPriceDialog(force = false) { unitCost, sale ->
                                    addActualStock(p, qty, unitCost, sale, anchorView)
                                }
                            }
                            .show()
                    }
                    return@setOnClickListener
                }

                receiveBinding.tilUnitCost.error = null
                receiveBinding.tilDueDate.error = null

                val unitCost = receiveBinding.etUnitCost.text.asCleanLongOrNull()
                if (unitCost == null || unitCost <= 0) {
                    receiveBinding.tilUnitCost.error = "Wajib"
                    return@setOnClickListener
                }

                val unitCostValue = unitCost

                val supplierInput = receiveBinding.actSupplier.text?.toString()?.trim().orEmpty()
                val chosenSupplier = suppliers.firstOrNull { it.name.equals(supplierInput, ignoreCase = true) }
                val supplierId = chosenSupplier?.id
                val supplierName = chosenSupplier?.name ?: supplierInput

                var dueDate: Date? = selectedDue
                if (dueDate == null) {
                    val dueText = receiveBinding.etDueDate.text?.toString()?.trim().orEmpty()
                    if (dueText.isNotEmpty()) {
                        try {
                            val parsed = dfIso.parse(dueText) ?: throw IllegalArgumentException()
                            dueDate = parsed
                        } catch (_: Exception) {
                            receiveBinding.tilDueDate.error = "Format tanggal salah (yyyy-MM-dd)"
                            return@setOnClickListener
                        }
                    }
                }
                if (dueDate == null && chosenSupplier != null && chosenSupplier.term > 0) {
                    val cal = Calendar.getInstance()
                    cal.time = Date()
                    cal.add(Calendar.DAY_OF_YEAR, chosenSupplier.term.toInt())
                    cal.set(Calendar.HOUR_OF_DAY, 23)
                    cal.set(Calendar.MINUTE, 59)
                    cal.set(Calendar.SECOND, 59)
                    cal.set(Calendar.MILLISECOND, 0)
                    dueDate = cal.time
                }

                val salePrice = p.salePrice.takeIf { it > 0 } ?: unitCostValue

                receiveDlg.dismiss()
                val dueTs = dueDate?.let { com.google.firebase.Timestamp(it) }
                receiveStockAndUpdatePrice(
                    sku = p.sku.ifBlank { p.id },
                    productName = p.name,
                    qty = qty,
                    unitCost = unitCostValue,
                    newSalePrice = salePrice,
                    dueDate = dueTs,
                    supplierName = supplierName.ifBlank { null },
                    supplierId = supplierId,
                    supplierTermDays = chosenSupplier?.term,
                    anchorView = anchorView
                )
            }
        }

        receiveDlg.show()
    }

    private fun addActualStock(
        product: Product,
        qty: Long,
        unitCost: Long,
        salePrice: Long,
        anchorView: View?
    ) {
        val sku = product.sku.ifBlank { product.id }
        val pRef = db.collection("products").document(sku)
        val now = FieldValue.serverTimestamp()

        fun commit(lastDoc: com.google.firebase.firestore.DocumentSnapshot?) {
            db.runTransaction { trx ->
                val ps = trx.get(pRef)
                require(ps.exists()) { "Produk tidak ditemukan" }
                require((ps.getBoolean("trackStock") ?: true)) { "Jasa tidak pakai stok" }
                val oldStock = ps.getLong("stock") ?: 0L
                val newStock = oldStock + qty
                val updates = mutableMapOf<String, Any?>(
                    "stock" to newStock,
                    "updatedAt" to now
                )
                if (unitCost > 0) updates["lastCost"] = unitCost
                val oldSale = ps.getLong("salePrice") ?: 0L
                if (salePrice > 0 && salePrice != oldSale) updates["salePrice"] = salePrice
                trx.update(pRef, updates)

                var merged = false
                if (lastDoc != null) {
                    val bRef = lastDoc.reference
                    val bs = trx.get(bRef)
                    val lastCost = bs.getLong("unitCost") ?: 0L
                    if (unitCost >= lastCost) {
                        val remain = bs.getLong("remainingQty") ?: 0L
                        val newRemain = remain + qty
                        val weighted = if (newRemain > 0) ((lastCost * remain + unitCost * qty) / newRemain) else unitCost
                        trx.update(bRef, mapOf(
                            "remainingQty" to newRemain,
                            "unitCost" to weighted,
                            "receivedAt" to now
                        ))
                        merged = true
                    }
                }
                if (!merged) {
                    val batchRef = db.collection("stock_batches").document()
                    trx.set(batchRef, mapOf(
                        "sku" to sku,
                        "unitCost" to unitCost,
                        "remainingQty" to qty,
                        "receivedAt" to now,
                        "purchaseId" to "",
                        "invoiceNo" to "",
                        "supplierName" to "",
                        "supplierId" to "",
                        "dueDate" to com.google.firebase.Timestamp.now()
                    ))
                }

                val mvRef = db.collection("inventory_movements").document()
                trx.set(mvRef, mapOf(
                    "sku" to sku,
                    "type" to "ACTUAL_STOCK",
                    "qtyDelta" to qty,
                    "unitCost" to unitCost,
                    "createdAt" to now,
                    "refId" to "ACTUAL_STOCK",
                    "note" to "Tambah stok aktual"
                ))
                null
            }.addOnSuccessListener {
                toast("Stok ditambah.")
                currentDialog?.dismiss()
                stockEventVm.emitAdjustmentEvent()
                val anchor = anchorView
                this@SuperAdminProductFragment.view?.post {
                    animateStockReceiveSuccess(anchor, R.id.superAdminInventoryFragment)
                } ?: animateStockReceiveSuccess(anchor, R.id.superAdminInventoryFragment)
            }.addOnFailureListener { e ->
                toast("Gagal menambah stok: ${e.message}")
            }
        }

        db.collection("stock_batches")
            .whereEqualTo("sku", sku)
            .orderBy("receivedAt", Query.Direction.DESCENDING)
            .limit(1)
            .get()
            .addOnSuccessListener { snap -> commit(snap.documents.firstOrNull()) }
            .addOnFailureListener { commit(null) }
    }

    private fun receiveStockAndUpdatePrice(
        sku: String,
        productName: String,
        qty: Long,
        unitCost: Long,
        newSalePrice: Long,
        dueDate: com.google.firebase.Timestamp?,
        supplierName: String?,
        supplierId: String?,
        supplierTermDays: Long?,
        anchorView: View?
    ) {
        val pRef = db.collection("products").document(sku)
        val now = FieldValue.serverTimestamp()
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        val termDays = supplierTermDays ?: 0L
        val shouldDelayStock = shouldDelayStockPosting(dueDate)
        val pendingRef = if (shouldDelayStock) db.collection("pending_stock_receipts").document() else null
        val scheduledTimestamp = dueDate ?: com.google.firebase.Timestamp.now()

        fun commitReceive(
            lastDoc: DocumentSnapshot?,
            delayStock: Boolean,
            pendingDocument: DocumentReference?
        ) {
            data class ReceiveResult(
                val purchaseId: String,
                val invoiceNo: String,
                val stagedSalePrice: Long?
            )

            val invoiceCounterRef = db.collection("counters").document("purchase_invoice")

            db.runTransaction { trx ->
                val pSnap = trx.get(pRef)
                require(pSnap.exists()) { "Produk tidak ditemukan" }
                require((pSnap.getBoolean("trackStock") ?: true)) { "Jasa tidak pakai stok" }

                val counterSnap = trx.get(invoiceCounterRef)
                val nextInvoice = if (counterSnap.exists()) {
                    val current = counterSnap.getLong("last") ?: 0L
                    val next = current + 1L
                    trx.update(invoiceCounterRef, mapOf(
                        "last" to next,
                        "updatedAt" to now
                    ))
                    next
                } else {
                    trx.set(invoiceCounterRef, mapOf(
                        "last" to 1L,
                        "updatedAt" to now
                    ))
                    1L
                }
                val invoiceNoGenerated = "Invoice-%03d".format(nextInvoice)

                val poRef = db.collection("purchases").document()
                val lastBatchSnap = if (!delayStock && lastDoc != null) trx.get(lastDoc.reference) else null

                val items = listOf(
                    mapOf(
                        "sku" to sku,
                        "name" to (pSnap.getString("name") ?: productName),
                        "qty" to qty,
                        "unitCost" to unitCost,
                        "lineCost" to qty * unitCost
                    )
                )

                val stockOld = pSnap.getLong("stock") ?: 0L
                val newStock = stockOld + qty
                val currentSalePrice = pSnap.getLong("salePrice") ?: 0L
                val currentCost = pSnap.getLong("lastCost") ?: unitCost
                val shouldStageSalePrice = !delayStock && newSalePrice > 0 && newSalePrice != currentSalePrice && stockOld > 0 && unitCost < currentCost
                val batchSalePrice = if (newSalePrice > 0) newSalePrice else currentSalePrice

                val purchaseData = mutableMapOf<String, Any?>(
                    "date" to now,
                    "createdBy" to (uid ?: ""),
                    "invoiceNo" to invoiceNoGenerated,
                    "dueDate" to (dueDate ?: com.google.firebase.Timestamp.now()),
                    "supplierName" to (supplierName ?: ""),
                    "supplierId" to (supplierId ?: ""),
                    "items" to items,
                    "totalCost" to (qty * unitCost),
                    "termDays" to termDays,
                    "dueReminderSent" to false,
                    "createdAt" to now,
                    "updatedAt" to now
                )
                if (delayStock) {
                    purchaseData["stockPosted"] = false
                    purchaseData["stockPostedAt"] = null
                    pendingDocument?.let { purchaseData["pendingStockId"] = it.id }
                } else {
                    purchaseData["stockPosted"] = true
                    purchaseData["stockPostedAt"] = now
                }
                trx.set(poRef, purchaseData)

                val productUpdates = mutableMapOf<String, Any?>("updatedAt" to now)
                if (!delayStock) productUpdates["stock"] = newStock
                if (unitCost > 0) productUpdates["lastCost"] = unitCost
                if (!delayStock && !shouldStageSalePrice && newSalePrice > 0 && newSalePrice != currentSalePrice) {
                    productUpdates["salePrice"] = newSalePrice
                }
                trx.update(pRef, productUpdates)

                var stagedSalePriceResult: Long? = null
                if (shouldStageSalePrice) {
                    stagedSalePriceResult = newSalePrice
                }

                if (!delayStock) {
                    var merged = false
                    var batchUpdateData: Map<String, Any>? = null
                    var batchCreateData: Map<String, Any>? = null
                    var targetBatchId: String? = null

                    if (lastBatchSnap != null) {
                        val lastCost = lastBatchSnap.getLong("unitCost") ?: 0L
                        if (unitCost >= lastCost) {
                            val remain = lastBatchSnap.getLong("remainingQty") ?: 0L
                            val newRemain = remain + qty
                            val weighted = if (newRemain > 0) ((lastCost * remain + unitCost * qty) / newRemain) else unitCost
                            val update = mutableMapOf<String, Any>(
                                "remainingQty" to newRemain,
                                "unitCost" to weighted,
                                "receivedAt" to now
                            )
                            if (newSalePrice > 0) update["salePrice"] = newSalePrice
                            batchUpdateData = update
                            merged = true
                            targetBatchId = lastBatchSnap.id
                        }
                    }
                    if (!merged) {
                        val create = mutableMapOf<String, Any>(
                            "sku" to sku,
                            "unitCost" to unitCost,
                            "remainingQty" to qty,
                            "receivedAt" to now,
                            "purchaseId" to poRef.id,
                            "invoiceNo" to invoiceNoGenerated,
                            "supplierName" to (supplierName ?: ""),
                            "supplierId" to (supplierId ?: ""),
                            "dueDate" to (dueDate ?: com.google.firebase.Timestamp.now()),
                            "termDays" to termDays
                        )
                        create["salePrice"] = batchSalePrice
                        batchCreateData = create
                    }

                    if (merged && lastDoc != null && batchUpdateData != null) trx.update(lastDoc.reference, batchUpdateData)
                    else if (batchCreateData != null) {
                        val batchRef = db.collection("stock_batches").document()
                        trx.set(batchRef, batchCreateData)
                        targetBatchId = batchRef.id
                    }

                    val mvRef = db.collection("inventory_movements").document()
                    val movement = mutableMapOf<String, Any>(
                        "sku" to sku,
                        "type" to "PURCHASE",
                        "qtyDelta" to qty,
                        "unitCost" to unitCost,
                        "createdAt" to now,
                        "refId" to poRef.id
                    )
                    targetBatchId?.let { movement["batchId"] = it }
                    trx.set(mvRef, movement)
                } else {
                    val targetDoc = pendingDocument ?: throw IllegalStateException("Pending document ref null")
                    val pendingData = mutableMapOf<String, Any?>(
                        "sku" to sku,
                        "productName" to (pSnap.getString("name") ?: productName),
                        "qty" to qty,
                        "unitCost" to unitCost,
                        "newSalePrice" to newSalePrice,
                        "invoiceNo" to invoiceNoGenerated,
                        "supplierName" to (supplierName ?: ""),
                        "supplierId" to (supplierId ?: ""),
                        "termDays" to termDays,
                        "dueDate" to scheduledTimestamp,
                        "scheduledAt" to scheduledTimestamp,
                        "purchaseId" to poRef.id,
                        "createdAt" to now,
                        "createdBy" to (uid ?: ""),
                        "status" to "pending",
                        "notificationSent" to false
                    )
                    trx.set(targetDoc, pendingData)
                }

                ReceiveResult(
                    purchaseId = poRef.id,
                    invoiceNo = invoiceNoGenerated,
                    stagedSalePrice = stagedSalePriceResult
                )
            }.addOnSuccessListener { result ->
                if (delayStock) {
                    stockEventVm.emitPurchaseEvent()
                    currentDialog?.dismiss()
                    val locale = Locale("in", "ID")
                    val humanDate = try {
                        SimpleDateFormat("dd MMM yyyy", locale).format(scheduledTimestamp.toDate())
                    } catch (_: Throwable) {
                        SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(scheduledTimestamp.toDate())
                    }
                    toast("Stok akan otomatis ditambah pada $humanDate. Invoice ${result.invoiceNo}.")
                    pendingDocument?.let { doc ->
                        val ctx = requireContext().applicationContext
                        ScheduledStockPostingWorker.enqueue(ctx, doc.id, scheduledTimestamp)
                    }
                } else {
                    val staged = result.stagedSalePrice
                    val base = if (staged != null && staged > 0) {
                        val formatted = rupiah(staged)
                        "Stok ditambah. Harga jual baru Rp $formatted aktif setelah stok lama habis."
                    } else "Stok ditambah."
                    toast("$base Invoice ${result.invoiceNo}.")
                    currentDialog?.dismiss()
                    stockEventVm.emitPurchaseEvent()
                    val anchor = anchorView
                    view?.post { animateStockReceiveSuccess(anchor, R.id.superAdminReportFragment) }
                        ?: animateStockReceiveSuccess(anchor, R.id.superAdminReportFragment)
                    val ctx = requireContext().applicationContext
                    StockNotificationHelper.notifyStockPosted(
                        ctx,
                        db,
                        result.purchaseId,
                        productName,
                        qty,
                        dueDate,
                        sku,
                        result.invoiceNo
                    )
                }
            }.addOnFailureListener { e -> toast("Gagal terima stok: ${e.message}") }
        }

        fun onGotLastBatch(lastDoc: DocumentSnapshot?) {
            if (shouldDelayStock) {
                commitReceive(lastDoc, true, pendingRef)
                return
            }
            commitReceive(lastDoc, false, null)
        }
        db.collection("stock_batches")
            .whereEqualTo("sku", sku)
            .orderBy("receivedAt", Query.Direction.DESCENDING)
            .limit(1)
            .get()
            .addOnSuccessListener { snapLast -> onGotLastBatch(snapLast.documents.firstOrNull()) }
            .addOnFailureListener { onGotLastBatch(null) }
    }

    private fun shouldDelayStockPosting(dueTs: com.google.firebase.Timestamp?): Boolean {
        dueTs ?: return false
        val dueDate = dueTs.toDate()
        if (dueDate.time <= System.currentTimeMillis()) return false
        val dueCal = Calendar.getInstance().apply {
            time = dueDate
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return dueCal.after(todayCal)
    }

    private fun animateStockReceiveSuccess(anchorView: View?, targetMenuId: Int = R.id.superAdminReportFragment) {
        val activity = activity ?: return
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val bubbleSize = resources.getDimensionPixelSize(R.dimen.stock_receive_bubble_size)
        val bubble = View(activity).apply {
            val bubbleColor = try {
                MaterialColors.getColor(anchorView ?: root, com.google.android.material.R.attr.colorPrimary)
            } catch (_: Throwable) {
                MaterialColors.getColor(root, com.google.android.material.R.attr.colorPrimary)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(bubbleColor)
            }
            alpha = 0.9f
        }
        val params = FrameLayout.LayoutParams(bubbleSize, bubbleSize)
        root.addView(bubble, params)

        val rootLoc = IntArray(2).apply { root.getLocationOnScreen(this) }
        val start = IntArray(2)
        if (anchorView != null) {
            anchorView.getLocationOnScreen(start)
        } else {
            start[0] = root.width / 2 + rootLoc[0]
            start[1] = root.height / 2 + rootLoc[1]
        }
        val anchorWidth = anchorView?.width ?: bubbleSize
        val anchorHeight = anchorView?.height ?: bubbleSize
        val startX = start[0] - rootLoc[0] + anchorWidth / 2f - bubbleSize / 2f
        val startY = start[1] - rootLoc[1] + anchorHeight / 2f - bubbleSize / 2f
        bubble.translationX = startX
        bubble.translationY = startY

        val bottomNav = (activity as? SuperAdminMainActivity)?.findViewById<BottomNavigationView>(R.id.bottomNav)
        val targetView = bottomNav?.findViewById<View>(targetMenuId)
        val endLoc = IntArray(2)
        if (targetView != null) {
            targetView.getLocationOnScreen(endLoc)
        } else {
            endLoc[0] = rootLoc[0] + (root.width * 0.75f).toInt()
            endLoc[1] = rootLoc[1] + root.height - (bottomNav?.height ?: bubbleSize)
        }
        val targetWidth = targetView?.width ?: bubbleSize
        val targetHeight = targetView?.height ?: bubbleSize
        val endX = endLoc[0] - rootLoc[0] + targetWidth / 2f - bubbleSize / 2f
        val endY = endLoc[1] - rootLoc[1] + targetHeight / 2f - bubbleSize / 2f

        bubble.animate()
            .setDuration(600)
            .setInterpolator(FastOutSlowInInterpolator())
            .translationX(endX)
            .translationY(endY)
            .scaleX(0.2f)
            .scaleY(0.2f)
            .alpha(0f)
            .withEndAction { root.removeView(bubble) }
            .start()
    }

    private fun uploadImageThen(productId: String, uri: Uri, onOk: (String) -> Unit) {
        val storage = FirebaseStorage.getInstance().reference
        val path = "products/$productId/${System.currentTimeMillis()}.jpg"
        val ref = storage.child(path)
        ref.putFile(uri)
            .continueWithTask { task -> if (!task.isSuccessful) throw task.exception ?: RuntimeException("Upload gagal"); ref.downloadUrl }
            .addOnSuccessListener { url -> onOk(url.toString()) }
            .addOnFailureListener { e -> toast("Upload foto gagal: ${e.message}") }
    }

    private fun confirmDelete(p: Product) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Hapus ${p.name}?")
            .setMessage("Data tidak bisa dikembalikan.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ ->
                db.collection("products").document(p.sku.ifBlank { p.id }).delete()
                    .addOnSuccessListener { toast("Dihapus") }
                    .addOnFailureListener { toast("Gagal: ${it.message}") }
            }.show()
    }

    private fun showDialogOnce(builder: MaterialAlertDialogBuilder): AlertDialog {
        if (currentDialog?.isShowing == true) return currentDialog!!
        val dlg = builder.create()
        currentDialog = dlg
        dlg.setOnDismissListener { currentDialog = null }
        dlg.show()
        return dlg
    }
    private fun showDialogOnce(dlg: AlertDialog): AlertDialog {
        if (currentDialog?.isShowing == true) return currentDialog!!
        currentDialog = dlg
        dlg.setOnDismissListener { currentDialog = null }
        dlg.show()
        return dlg
    }

    private inline fun String?.ifNullOrEmpty(block: () -> String): String =
        if (this.isNullOrEmpty()) block() else this
}

internal fun SuperAdminProductFragment.consume(snapshots: MutableList<DocumentSnapshot>) {}

/* ===== Adapter grid ===== */
private class ProductsAdapter(
    val onReceive: (Product, View) -> Unit,
    val onEdit: (Product) -> Unit,
    val onDelete: (Product) -> Unit,
    val onMore: (Product) -> Unit,
    val allowLongPress: Boolean = true
) : ListAdapter<Product, ProductsAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Product>() {
            override fun areItemsTheSame(a: Product, b: Product) = a.id == b.id
            override fun areContentsTheSame(a: Product, b: Product) = a == b
        }
    }

    private var knownCategoryIds: Set<String> = emptySet()
    private var defaultCategoryColor: Int? = null

    fun updateKnownCategoryIds(ids: Set<String>) {
        knownCategoryIds = ids
        notifyDataSetChanged()
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.img)
        val tvBadge: TextView = v.findViewById(R.id.tvBadge)
        val tvName: TextView = v.findViewById(R.id.tvName)
        val tvStock: TextView = v.findViewById(R.id.tvStock)
        val tvCategory: TextView = v.findViewById(R.id.tvCategory)
        val tvPrice: TextView = v.findViewById(R.id.tvPrice)
        val tvCost: TextView = v.findViewById(R.id.tvCost)
        val btnPrimary: com.google.android.material.button.MaterialButton = v.findViewById(R.id.btnAdd)
        val btnDelete: ImageButton = v.findViewById(R.id.btnDelete)
        init {
            v.setOnClickListener { onEdit(getItem(bindingAdapterPosition)) }
            if (allowLongPress) {
                v.setOnLongClickListener { onMore(getItem(bindingAdapterPosition)); true }
            } else {
                v.setOnLongClickListener(null)
            }
        }
    }

    override fun onCreateViewHolder(p: ViewGroup, vt: Int): VH {
        val v = LayoutInflater.from(p.context).inflate(R.layout.item_product_super_admin, p, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val product = getItem(position)
        h.img.load(product.images.firstOrNull() ?: R.drawable.store)
        val inStock = product.isService || product.stock > 0
        h.tvBadge.text = if (product.isService) "Jasa" else if (inStock) "Stock Tersedia" else "Stock Habis"
        h.tvBadge.setBackgroundResource(
            if (product.isService) R.drawable.bg_badge_green
            else if (inStock) R.drawable.bg_badge_green else R.drawable.bg_badge_red
        )
        h.tvName.text = product.name
        h.tvStock.text = if (product.isService) "Jasa (tanpa stok)" else "Stock      : ${product.stock}"
        if (defaultCategoryColor == null) defaultCategoryColor = h.tvCategory.currentTextColor
        val missingCat = product.categoryId.isBlank() || (knownCategoryIds.isNotEmpty() && !knownCategoryIds.contains(product.categoryId))
        if (missingCat) {
            val errColor = MaterialColors.getColor(h.tvCategory, com.google.android.material.R.attr.colorError)
            h.tvCategory.setTextColor(errColor)
            h.tvCategory.text = "Kategori: wajib diisi"
        } else {
            h.tvCategory.setTextColor(defaultCategoryColor!!)
            h.tvCategory.text = if (product.categoryName.isNotBlank()) "Kategori : ${product.categoryName}" else "Kategori: -"
        }
        h.tvPrice.text = if (product.isService) "Harga: input kasir" else "Rp ${rupiah(product.salePrice)}"
        h.tvCost.text = if (product.isService) "Modal/Unit  : -" else "Modal/Unit  : Rp ${rupiah(product.lastCost)}"
        h.btnPrimary.text = if (product.isService) "Non-Stok" else "Terima Stok"
        h.btnPrimary.isEnabled = !product.isService
        h.btnPrimary.setOnClickListener { onReceive(product, h.btnPrimary) }
        h.btnDelete.setOnClickListener { onDelete(product) }
    }
}
