package com.example.pos_hma.ui.role.super_admin

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.*
import android.widget.*
import android.graphics.drawable.GradientDrawable
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
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
import com.example.pos_hma.data.BatchState
import com.example.pos_hma.databinding.*
import com.example.pos_hma.utils.StockNotificationHelper
import com.example.pos_hma.utils.AppFlags
import com.example.pos_hma.utils.SnapshotDisposable
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import com.google.firebase.storage.FirebaseStorage
import java.io.File
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

private enum class PendingQueueType { SCHEDULED, STAGED }

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
    private var formRemoveImageButton: ImageButton? = null
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedImageUri = uri
            formImgPreview?.let {
                it.load(uri)
                it.alpha = 1f
            }
            formRemoveImageButton?.isVisible = true
        }
    }
    private var cameraTempUri: Uri? = null
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && cameraTempUri != null) {
            selectedImageUri = cameraTempUri
            formImgPreview?.let {
                it.load(cameraTempUri)
                it.alpha = 1f
            }
            formRemoveImageButton?.isVisible = true
        } else {
            cameraTempUri = null
        }
    }


    // kategori
    private val catFilterList = mutableListOf<Category>()
    private val catFormList = mutableListOf<Category>()
    private var selectedFilterCategory: Category? = null
    private var categoriesReg: ListenerRegistration? = null
    private var activeCategoryIds: MutableSet<String> = mutableSetOf()

    private fun createTempImageUri(): Uri? {
        return try {
            val context = requireContext()
            val file = File.createTempFile("product_${System.currentTimeMillis()}", ".jpg", context.cacheDir)
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file).also {
                cameraTempUri = it
            }
        } catch (e: Exception) {
            toast("Tidak dapat mengakses kamera: ${e.localizedMessage ?: "Unknown error"}")
            null
        }
    }

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
            onViewPending = { openPendingQueueDialog(it) },
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
                        isActive = isActive,
                        sortOrder = d.getLong("sortOrder") ?: 0L,
                        order = d.getLong("order")
                    )
                }.sortedWith(compareBy<Category> { it.effectiveOrder }.thenBy { it.name })

                val prevId = selectedFilterCategory?.id
                catFilterList.clear(); catFilterList.addAll(list)
                activeCategoryIds = list.map { it.id }.toMutableSet()
                val names = mutableListOf("Semua").apply { addAll(list.map { it.name }) }

                // PERBAIKAN: Gunakan ArrayAdapter
                val categoryAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, names)
                b.actCategory.setAdapter(categoryAdapter)

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

            // PERBAIKAN: Gunakan ArrayAdapter
            val categoryAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, names)
            b.actCategory.setAdapter(categoryAdapter)

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

    // ====== MUAT KATEGORI AKTIF untuk FORM ======
    private fun loadCategoriesForForm(onDone: (() -> Unit)? = null) {
        db.collection("categories")
            .get()
            .addOnSuccessListener { qs ->
                val categories = qs.documents.mapNotNull { d ->
                    val isActive = d.getBoolean("isActive") ?: true
                    if (!isActive) return@mapNotNull null
                    val c = d.toObject(Category::class.java)?.copy(id = d.id) ?: Category(
                        id = d.id,
                        name = d.getString("name") ?: d.id,
                        slug = d.getString("slug") ?: d.id,
                        isActive = isActive,
                        sortOrder = d.getLong("sortOrder") ?: 0L,
                        order = d.getLong("order")
                    )
                    c
                }.sortedWith(compareBy<Category> { it.effectiveOrder }.thenBy { it.name })

                catFormList.clear()
                catFormList.addAll(categories)
                onDone?.invoke()
            }
            .addOnFailureListener { toast("Kategori gagal dimuat: ${it.message}") }
    }

    // ====== Dialog pemilih kategori (dipakai di FORM) ======
    private fun openCategoryPickerDialog(
        preselectedId: String?,
        onPicked: (Category?) -> Unit
    ) {
        loadCategoriesForForm {
            val names = catFormList.map { it.name }.toTypedArray()
            if (names.isEmpty()) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Pilih Kategori")
                    .setMessage("Tidak ada kategori aktif.")
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
        cameraTempUri = null
        val form = DialogProductFormBinding.inflate(layoutInflater)
        formImgPreview = form.imgPreview
        formRemoveImageButton = form.btnRemoveImage
        form.btnRemoveImage.isVisible = false

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
            if (fixedIsService) {
                form.tilPrice.visibility = View.VISIBLE
                form.tilPrice.hint = "Harga jasa (Rp)"
                form.etPrice.isEnabled = true
                if (p.salePrice > 0) form.etPrice.setText(rupiah(p.salePrice)) else form.etPrice.setText("")
            } else {
                form.tilPrice.visibility = View.GONE
                form.etPrice.isEnabled = false
                form.etPrice.setText("")
            }

            if (p.images.firstOrNull().isNullOrBlank()) {
                form.imgPreview.setImageResource(R.drawable.ic_product_placeholder); form.imgPreview.alpha = .25f
                form.btnRemoveImage.isVisible = false
            } else {
                form.imgPreview.alpha = 1f
                form.imgPreview.load(p.images.first())
                form.btnRemoveImage.isVisible = true
            }
        } else {
            // Non-editing: lock to current tab type
            form.imgPreview.setImageResource(R.drawable.ic_product_placeholder); form.imgPreview.alpha = .25f
            form.btnRemoveImage.isVisible = false
            if (forTypeTab == "service") {
                // Service tab: create service only
                form.swService.visibility = View.GONE
                form.tilStock.visibility = View.GONE
                form.tilInitCost.visibility = View.GONE
                form.tilPrice.visibility = View.VISIBLE
                form.tilPrice.hint = "Harga jasa (Rp)"
                form.etPrice.isEnabled = true
                form.etPrice.setText("")
            } else {
                // Goods tab: create goods only
                form.swService.visibility = View.GONE
                form.tilStock.visibility = View.VISIBLE
                form.tilInitCost.visibility = View.VISIBLE
                form.tilPrice.visibility = View.GONE
                form.etPrice.isEnabled = false
                form.etPrice.setText("")
            }
        }

        // Muat awal + preselect untuk EDIT
        loadCategoriesForForm {
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
            openCategoryPickerDialog(selectedFormCategory?.id) { picked ->
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
                    form.tilPrice.visibility = View.VISIBLE
                    form.tilPrice.hint = "Harga jasa (Rp)"
                    form.etPrice.isEnabled = true
                } else {
                    form.tilPrice.visibility = View.GONE
                    form.etPrice.isEnabled = false
                    form.etPrice.setText("")
                    form.tilPrice.error = null
                }
                // Muat kategori sesuai tipe baru dan reset pilihan
                loadCategoriesForForm {
                    actCat.setText("", false)
                    selectedFormCategory = null
                }
            }
        }

        // Pasang formatter rupiah
        form.etPrice.attachRupiahFormatter()
        form.etInitCost.attachRupiahFormatter()

        // Foto
        form.btnPickImage.setOnClickListener {
            pickImage.launch("image/*")
        }
        form.btnTakePhoto.setOnClickListener {
            val uri = createTempImageUri() ?: return@setOnClickListener
            takePhoto.launch(uri)
        }
        form.btnRemoveImage.setOnClickListener {
            selectedImageUri = null
            cameraTempUri = null
            form.imgPreview.setImageResource(R.drawable.ic_product_placeholder)
            form.imgPreview.alpha = .25f
            form.btnRemoveImage.isVisible = false
            formRemoveImageButton?.isVisible = false
        }

        // Build dialog & tombol SIMPAN
        val dlg = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (isEditing) "Edit Produk" else "Produk Baru")
            .setView(form.root)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan", null)
            .create()

        currentDialog?.dismiss(); currentDialog = dlg
        dlg.setOnDismissListener {
            currentDialog = null
            formImgPreview = null
            formRemoveImageButton = null
        }

        dlg.setOnShowListener {
            val btn = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
            btn.setOnClickListener {
                // reset error
                val cleared = mutableListOf(form.tilName, form.tilSKU, form.tilCategory, form.tilStock, form.tilInitCost)
                if (form.tilPrice.visibility == View.VISIBLE) cleared += form.tilPrice
                cleared.forEach { it.error = null }
                form.tvPhotoError.visibility = View.GONE; form.cardImage.strokeWidth = 0

                var ok = true
                if (form.etName.text.isNullOrBlank()) { form.tilName.error = "Harus diisi"; ok = false }
                if (!isEditing && form.etSKU.text.isNullOrBlank()) { form.tilSKU.error = "Harus diisi"; ok = false }

                val isService = if (isEditing) fixedIsService else form.swService.isChecked
                val salePrice = if (form.tilPrice.visibility == View.VISIBLE) form.etPrice.text.asCleanLong() else 0L
                if (form.tilPrice.visibility == View.VISIBLE && salePrice <= 0) { form.tilPrice.error = "Harus diisi"; ok = false }

                var initStock = 0L
                var initCost = 0L
                if (!isService) {
                    val stockText = form.etStock.text?.toString()?.trim().orEmpty()
                    val stockVal = stockText.toLongOrNull()
                    when {
                        stockText.isEmpty() -> { form.tilStock.error = "Harus diisi"; ok = false }
                        stockVal == null -> { form.tilStock.error = "Angka tidak valid"; ok = false }
                        stockVal < 0 -> { form.tilStock.error = "Tidak boleh negatif"; ok = false }
                        else -> initStock = stockVal
                    }

                    val costVal = form.etInitCost.text.asCleanLongOrNull()
                    if (costVal == null) {
                        form.tilInitCost.error = "Harus diisi"; ok = false
                    } else {
                        initCost = costVal
                        if (initStock > 0 && initCost <= 0) {
                            form.tilInitCost.error = "Harus lebih besar dari 0"; ok = false
                        }
                    }
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
                        "updatedAt" to FieldValue.serverTimestamp()
                    ).apply {
                        if (form.tilPrice.visibility == View.VISIBLE) this["salePrice"] = salePrice
                    }
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
    private fun openAddCategoryDialog(onAdded: (Category) -> Unit) {
        val cat = DialogCategoryFormBinding.inflate(layoutInflater)

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

                if (name.isEmpty()) { cat.tilCatName.error = "Harus diisi"; return@setOnClickListener }

                val slug = slugify(name)
                val now = FieldValue.serverTimestamp()
                val ref = db.collection("categories").document(slug)

                ref.set(
                    mapOf(
                        "name" to name,
                        "slug" to slug,
                        "isActive" to true,
                        "nameLowercase" to name.lowercase(),
                        "sortOrder" to System.currentTimeMillis(),
                        "createdAt" to now,
                        "updatedAt" to now
                    )
                ).addOnSuccessListener {
                    onAdded(Category(id = slug, name = name, slug = slug, isActive = true))
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

    // =========================================================================================
    // MODIFIED SECTION: Pending Queue Dialog Logic
    // =========================================================================================

    private fun openPendingQueueDialog(p: Product) {
        // Inflate layout baru tanpa view binding karena ini file baru
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_pending_queue_options, null)
        val btnGoodsQueue = dialogView.findViewById<Button>(R.id.btn_goods_queue)
        val btnInvoiceQueue = dialogView.findViewById<Button>(R.id.btn_invoice_queue)

        // Buat dialog untuk menampilkan pilihan
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .create()

        // Pastikan dialog sebelumnya ditutup
        currentDialog?.dismiss()
        currentDialog = dialog
        dialog.setOnDismissListener { if (currentDialog === dialog) currentDialog = null }

        // Atur listener untuk tombol "Antrian Barang"
        btnGoodsQueue.setOnClickListener {
            dialog.dismiss()
            showStagedGoodsQueueDialog(p) // Panggil dialog khusus stok tertahan
        }

        // Atur listener untuk tombol "Antrian Pembelian"
        btnInvoiceQueue.setOnClickListener {
            dialog.dismiss()
            showScheduledInvoiceQueueDialog(p) // Panggil dialog khusus invoice masuk
        }

        dialog.show()
    }

    private fun showScheduledInvoiceQueueDialog(p: Product) {
        val binding = DialogFifoQueueBinding.inflate(layoutInflater)
        val nf = NumberFormat.getInstance(ID_LOCALE)
        val dfDate = SimpleDateFormat("dd MMM yyyy", ID_LOCALE)
        val dfDateTime = SimpleDateFormat("dd MMM yyyy HH:mm", ID_LOCALE)

        data class PendingRow(
            val invoiceNo: String, val supplierName: String, val productName: String,
            val qty: Long, val unitCost: Long?, val unitSalePrice: Long?,
            val dueDate: com.google.firebase.Timestamp?, val scheduledAt: com.google.firebase.Timestamp?,
            val status: String, val purchaseId: String?,
            val type: PendingQueueType, val receivedAt: com.google.firebase.Timestamp?
        )

        val rows = mutableListOf<PendingRow>()
        val registrations = mutableListOf<ListenerRegistration>()
        val hiddenStatuses = setOf("posted", "done", "selesai", "completed", "complete", "finished", "finish")

        class PendingVH(private val b: ItemFifoBatchBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(row: PendingRow) {
                b.tvTitle.text = if (row.invoiceNo.isNotBlank()) row.invoiceNo else "Pengadaan terjadwal"
                b.tvProduct.text = "Barang: ${row.productName.ifBlank { "-" }}"
                val costText = row.unitCost?.takeIf { it > 0 }?.let { "Modal/unit: Rp ${nf.format(it)}" } ?: "Modal/unit: -"
                val saleText = row.unitSalePrice?.takeIf { it > 0 }?.let { "Harga jual/unit: Rp ${nf.format(it)}" } ?: "Harga jual/unit: -"
                b.tvCost.text = costText; b.tvSale.text = saleText
                b.tvSupplier.text = "Supplier: ${row.supplierName.ifBlank { "-" }}"
                b.tvQty.text = "Qty ${nf.format(row.qty)} unit"
                val dueText = row.dueDate?.toDate()?.let { dfDate.format(it) } ?: "-"
                val scheduledText = row.scheduledAt?.toDate()?.let { dfDateTime.format(it) } ?: "-"
                b.tvDue.text = "Jadwal posting: $scheduledText | Jatuh tempo: $dueText"
                val statusPretty = when (row.status.lowercase(Locale.ROOT)) {
                    "processing" -> "Sedang diproses"; "posted" -> "Selesai"; else -> "Menunggu"
                }
                val purchaseInfo = row.purchaseId?.takeIf { it.isNotBlank() }?.let { " - Purchase ${it.takeLast(6)}" } ?: ""
                b.tvStatus.text = "Status: $statusPretty$purchaseInfo"
            }
        }

        val adapter = object : RecyclerView.Adapter<PendingVH>() {
            override fun onCreateViewHolder(parent: ViewGroup, vt: Int) = PendingVH(ItemFifoBatchBinding.inflate(layoutInflater, parent, false))
            override fun onBindViewHolder(holder: PendingVH, position: Int) = holder.bind(rows[position])
            override fun getItemCount(): Int = rows.size
        }

        fun render() {
            binding.progress.visibility = View.GONE
            if (rows.isEmpty()) {
                binding.tvEmpty.visibility = View.VISIBLE
                binding.tvSummary.visibility = View.GONE
            } else {
                binding.tvEmpty.visibility = View.GONE
                binding.tvSummary.visibility = View.VISIBLE
                val totalQty = rows.sumOf { it.qty }
                binding.tvSummary.text = "Total antrian: ${rows.size} | Total Qty: ${nf.format(totalQty)}"
            }
            adapter.notifyDataSetChanged()
        }

        binding.rvQueue.layoutManager = LinearLayoutManager(requireContext())
        binding.rvQueue.adapter = adapter
        binding.progress.visibility = View.VISIBLE

        val skuKey = p.sku.ifBlank { p.id }
        val pendingReg = db.collection("pending_stock_receipts")
            .whereEqualTo("sku", skuKey)
            .addSnapshotListener { snap, e ->
                if (e != null) { if (isAdded) toast("Gagal memuat antrian: ${e.message}"); return@addSnapshotListener }
                rows.clear()
                snap?.documents?.forEach { doc ->
                    val qtyVal = doc.getLong("qty") ?: return@forEach
                    if (qtyVal <= 0L) return@forEach
                    val statusVal = doc.getString("status") ?: "pending"
                    if (statusVal.lowercase(Locale.ROOT) in hiddenStatuses) return@forEach
                    rows += PendingRow(
                        invoiceNo = doc.getString("invoiceNo") ?: "",
                        supplierName = doc.getString("supplierName") ?: "",
                        productName = doc.getString("productName") ?: p.name,
                        qty = qtyVal,
                        unitCost = doc.getLong("unitCost"),
                        unitSalePrice = doc.getLong("newSalePrice") ?: doc.getLong("salePrice"),
                        dueDate = doc.getTimestamp("dueDate"),
                        scheduledAt = doc.getTimestamp("scheduledAt"),
                        status = statusVal,
                        purchaseId = doc.getString("purchaseId"),
                        type = PendingQueueType.SCHEDULED,
                        receivedAt = null
                    )
                }
                rows.sortBy { it.scheduledAt?.toDate()?.time ?: Long.MAX_VALUE }
                render()
            }
        registrations += pendingReg

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Antrian Invoice - ${p.name}")
            .setView(binding.root)
            .setPositiveButton("Tutup", null)
            .create()

        dialog.setOnDismissListener {
            registrations.forEach { it.remove() }
            if (currentDialog === dialog) currentDialog = null
        }
        currentDialog?.dismiss(); currentDialog = dialog
        dialog.show()
    }

    private fun showStagedGoodsQueueDialog(p: Product) {
        val binding = DialogFifoQueueBinding.inflate(layoutInflater)
        val nf = NumberFormat.getInstance(ID_LOCALE)
        val dfDateTime = SimpleDateFormat("dd MMM yyyy HH:mm", ID_LOCALE)

        data class PendingRow(
            val invoiceNo: String, val supplierName: String, val productName: String,
            val qty: Long, val unitCost: Long?, val unitSalePrice: Long?,
            val dueDate: com.google.firebase.Timestamp?, val scheduledAt: com.google.firebase.Timestamp?,
            val status: String, val purchaseId: String?,
            val type: PendingQueueType, val receivedAt: com.google.firebase.Timestamp?
        )

        val rows = mutableListOf<PendingRow>()
        var stageInfo: String? = null
        val registrations = mutableListOf<ListenerRegistration>()

        class PendingVH(private val b: ItemFifoBatchBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(row: PendingRow) {
                b.tvTitle.text = if (row.invoiceNo.isNotBlank()) row.invoiceNo else "Stok baru tertahan"
                b.tvProduct.text = "Barang: ${row.productName.ifBlank { "-" }}"
                val costText = row.unitCost?.takeIf { it > 0 }?.let { "Modal/unit: Rp ${nf.format(it)}" } ?: "Modal/unit: -"
                val saleText = row.unitSalePrice?.takeIf { it > 0 }?.let { "Harga jual/unit: Rp ${nf.format(it)}" } ?: "Harga jual/unit: -"
                b.tvCost.text = costText; b.tvSale.text = saleText
                b.tvSupplier.text = "Supplier: ${row.supplierName.ifBlank { "-" }}"
                b.tvQty.text = "Qty ${nf.format(row.qty)} unit"
                val receivedText = row.receivedAt?.toDate()?.let { dfDateTime.format(it) } ?: "-"
                b.tvDue.text = "Diterima: $receivedText"
                val purchaseInfo = row.purchaseId?.takeIf { it.isNotBlank() }?.let { " - Purchase ${it.takeLast(6)}" } ?: ""
                b.tvStatus.text = "Status: Menunggu stok lama habis$purchaseInfo"
            }
        }

        val adapter = object : RecyclerView.Adapter<PendingVH>() {
            override fun onCreateViewHolder(parent: ViewGroup, vt: Int) = PendingVH(ItemFifoBatchBinding.inflate(layoutInflater, parent, false))
            override fun onBindViewHolder(holder: PendingVH, position: Int) = holder.bind(rows[position])
            override fun getItemCount(): Int = rows.size
        }

        fun render() {
            binding.progress.visibility = View.GONE
            val infoParts = mutableListOf<String>()
            if (rows.isEmpty()) {
                binding.tvEmpty.visibility = if (stageInfo.isNullOrBlank()) View.VISIBLE else View.GONE
            } else {
                binding.tvEmpty.visibility = View.GONE
                val totalQty = rows.sumOf { it.qty }
                infoParts += "Total antrian: ${rows.size} | Qty: ${nf.format(totalQty)}"
            }
            stageInfo?.let { if (it.isNotBlank()) infoParts += it }
            binding.tvSummary.visibility = if (infoParts.isNotEmpty()) View.VISIBLE else View.GONE
            binding.tvSummary.text = infoParts.joinToString(" | ")
            adapter.notifyDataSetChanged()
        }

        binding.rvQueue.layoutManager = LinearLayoutManager(requireContext())
        binding.rvQueue.adapter = adapter
        binding.progress.visibility = View.VISIBLE

        val skuKey = p.sku.ifBlank { p.id }
        val productReg = db.collection("products").document(skuKey)
            .addSnapshotListener { doc, e ->
                if (e != null) { if (isAdded) toast("Gagal memuat info stok: ${e.message}"); return@addSnapshotListener }
                if (doc != null && doc.exists()) {
                    val stagedQty = doc.getLong("stagedOldQty") ?: 0L
                    val parts = mutableListOf<String>()
                    if (stagedQty > 0L) parts += "Sisa stok lama: ${nf.format(stagedQty)}"
                    stageInfo = if (parts.isEmpty()) null else parts.joinToString(" | ")
                } else stageInfo = null
                render()
            }
        registrations += productReg

        val holdReg = db.collection("stock_batches")
            .whereEqualTo("sku", skuKey)
            .whereEqualTo("state", BatchState.HOLD.name)
            .addSnapshotListener { snap, e ->
                if (e != null) { if (isAdded) toast("Gagal memuat stok tertahan: ${e.message}"); return@addSnapshotListener }
                rows.clear()
                snap?.documents?.forEach { doc ->
                    val qtyVal = doc.getLong("remainingQty") ?: doc.getLong("receivedQty") ?: return@forEach
                    if (qtyVal <= 0L) return@forEach
                    rows += PendingRow(
                        invoiceNo = doc.getString("invoiceNo") ?: "",
                        supplierName = doc.getString("supplierName") ?: "",
                        productName = doc.getString("productName") ?: p.name,
                        qty = qtyVal, unitCost = doc.getLong("unitCost"), unitSalePrice = doc.getLong("salePrice"),
                        dueDate = null, scheduledAt = null,
                        status = doc.getString("state") ?: BatchState.HOLD.name,
                        purchaseId = doc.getString("purchaseId"), type = PendingQueueType.STAGED,
                        receivedAt = doc.getTimestamp("receivedAt")
                    )
                }
                rows.sortBy { it.receivedAt?.toDate()?.time ?: Long.MAX_VALUE }
                render()
            }
        registrations += holdReg

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Antrian Barang Tertahan - ${p.name}")
            .setView(binding.root)
            .setPositiveButton("Tutup", null)
            .create()

        dialog.setOnDismissListener {
            registrations.forEach { it.remove() }
            if (currentDialog === dialog) currentDialog = null
        }
        currentDialog?.dismiss(); currentDialog = dialog
        dialog.show()
    }

    // =========================================================================================
    // END OF MODIFIED SECTION
    // =========================================================================================

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
        var selectedSupplier: SupplierRow? = null
        receiveBinding.actSupplier.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                selectedSupplier = null
                receiveBinding.tilSupplier.error = null
            }
        })
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
            // PERBAIKAN: Gunakan ArrayAdapter
            val supplierAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, names)
            receiveBinding.actSupplier.setAdapter(supplierAdapter)
        }

        if (p.lastCost > 0) receiveBinding.etUnitCost.setText(rupiah(p.lastCost))
        if (p.salePrice > 0) receiveBinding.etSalePrice.setText(rupiah(p.salePrice))
        receiveBinding.etUnitCost.attachRupiahFormatter()
        receiveBinding.etSalePrice.attachRupiahFormatter()

        val localeId = Locale("in", "ID")
        val dfDate = SimpleDateFormat("yyyy-MM-dd", localeId).apply { isLenient = false }
        val dfTime = SimpleDateFormat("HH:mm", localeId).apply { isLenient = false }
        val selectedDateTimeCal = Calendar.getInstance().apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MINUTE, 5)
        }
        receiveBinding.etDueDate.setText(dfDate.format(selectedDateTimeCal.time))
        receiveBinding.etDueTime.setText(dfTime.format(selectedDateTimeCal.time))

        fun refreshSalePriceUi() {
            val update = receiveBinding.rbUpdateSalePrice.isChecked
            receiveBinding.tilSalePrice.isEnabled = update
            receiveBinding.etSalePrice.isEnabled = update
            if (update) {
                if (receiveBinding.etSalePrice.text.isNullOrBlank()) {
                    val currentSale = p.salePrice.takeIf { it > 0 } ?: 0L
                    if (currentSale > 0) receiveBinding.etSalePrice.setText(rupiah(currentSale))
                }
            } else {
                receiveBinding.tilSalePrice.error = null
            }
        }

        fun refreshModeUi() {
            val isInvoiceMode = receiveBinding.rbInvoice.isChecked
            receiveBinding.groupPurchase.visibility = if (isInvoiceMode) View.VISIBLE else View.GONE
            receiveBinding.tvActualInfo.visibility = if (isInvoiceMode) View.GONE else View.VISIBLE
            if (!isInvoiceMode) {
                receiveBinding.rgSalePriceMode.check(receiveBinding.rbKeepSalePrice.id)
            }
            refreshSalePriceUi()
        }

        receiveBinding.rgMode.setOnCheckedChangeListener { _, _ -> refreshModeUi() }
        receiveBinding.rgSalePriceMode.setOnCheckedChangeListener { _, _ -> refreshSalePriceUi() }
        receiveBinding.rbActual.isChecked = true
        refreshModeUi()

        // Fungsi untuk menampilkan Date Picker
        fun showDatePicker() {
            DatePickerDialog(requireContext(), { _, year, month, dayOfMonth ->
                selectedDateTimeCal.set(Calendar.YEAR, year)
                selectedDateTimeCal.set(Calendar.MONTH, month)
                selectedDateTimeCal.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                selectedDateTimeCal.set(Calendar.SECOND, 0)
                selectedDateTimeCal.set(Calendar.MILLISECOND, 0)
                receiveBinding.tilDueDate.error = null
                receiveBinding.tilDueTime.error = null
                receiveBinding.etDueDate.setText(dfDate.format(selectedDateTimeCal.time))
            },
                selectedDateTimeCal.get(Calendar.YEAR),
                selectedDateTimeCal.get(Calendar.MONTH),
                selectedDateTimeCal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }
        receiveBinding.etDueDate.setOnClickListener { showDatePicker() }

        // Fungsi untuk menampilkan Time Picker
        fun showTimePicker() {
            TimePickerDialog(requireContext(), { _, hourOfDay, minute ->
                selectedDateTimeCal.set(Calendar.HOUR_OF_DAY, hourOfDay)
                selectedDateTimeCal.set(Calendar.MINUTE, minute)
                selectedDateTimeCal.set(Calendar.SECOND, 0)
                selectedDateTimeCal.set(Calendar.MILLISECOND, 0)
                receiveBinding.tilDueTime.error = null
                receiveBinding.tilDueDate.error = null
                receiveBinding.etDueTime.setText(dfTime.format(selectedDateTimeCal.time))
            },
                selectedDateTimeCal.get(Calendar.HOUR_OF_DAY),
                selectedDateTimeCal.get(Calendar.MINUTE),
                true // 24-hour format
            ).show()
        }
        receiveBinding.etDueTime.setOnClickListener { showTimePicker() }

        receiveBinding.actSupplier.setOnItemClickListener { _, _, position, _ ->
            val chosenSupplier = suppliers.getOrNull(position)
            selectedSupplier = chosenSupplier
            if (chosenSupplier != null) {
                receiveBinding.tilSupplier.error = null
                val cal = Calendar.getInstance().apply {
                    time = Date()
                    add(Calendar.DAY_OF_YEAR, chosenSupplier.term.toInt())
                    set(Calendar.HOUR_OF_DAY, selectedDateTimeCal.get(Calendar.HOUR_OF_DAY))
                    set(Calendar.MINUTE, selectedDateTimeCal.get(Calendar.MINUTE))
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                selectedDateTimeCal.time = cal.time
                receiveBinding.tilDueDate.error = null
                receiveBinding.tilDueTime.error = null
                receiveBinding.etDueDate.setText(dfDate.format(selectedDateTimeCal.time))
                receiveBinding.etDueTime.setText(dfTime.format(selectedDateTimeCal.time))
            }
        }
        val receiveDlg = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Tambah stok: ${p.name}")
            .setView(receiveBinding.root)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan", null)
            .create()

        currentDialog?.dismiss(); currentDialog = receiveDlg
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
                    priceBinding.tilUnitCost.error = null; priceBinding.tilNewSalePrice.error = null
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
                    receiveBinding.tilQty.error = "Qty wajib"; return@setOnClickListener
                }

                if (receiveBinding.rbActual.isChecked) {
                    receiveDlg.dismiss()
                    val baseCost = p.lastCost.takeIf { it > 0 } ?: 0L
                    val baseSale = p.salePrice.takeIf { it > 0 } ?: 0L
                    if (baseCost <= 0 || baseSale <= 0) {
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
                receiveBinding.tilDueTime.error = null
                receiveBinding.tilSupplier.error = null

                val unitCost = receiveBinding.etUnitCost.text.asCleanLongOrNull()
                if (unitCost == null || unitCost <= 0) {
                    receiveBinding.tilUnitCost.error = "Wajib"; return@setOnClickListener
                }

                val supplierInput = receiveBinding.actSupplier.text?.toString()?.trim().orEmpty()
                if (supplierInput.isBlank()) {
                    receiveBinding.tilSupplier.error = "Wajib"; return@setOnClickListener
                }
                val chosenSupplier = selectedSupplier?.takeIf { it.name.equals(supplierInput, ignoreCase = true) }
                    ?: suppliers.firstOrNull { it.name.equals(supplierInput, ignoreCase = true) }
                if (chosenSupplier == null) {
                    receiveBinding.tilSupplier.error = "Pilih supplier dari daftar"; return@setOnClickListener
                }
                selectedSupplier = chosenSupplier
                receiveBinding.tilSupplier.error = null

                val dueDateText = receiveBinding.etDueDate.text?.toString()?.trim().orEmpty()
                if (dueDateText.isBlank()) {
                    receiveBinding.tilDueDate.error = "Wajib"; return@setOnClickListener
                }
                val baseDate = try {
                    dfDate.parse(dueDateText)
                } catch (_: Exception) {
                    receiveBinding.tilDueDate.error = "Format tanggal salah (yyyy-MM-dd)"; return@setOnClickListener
                }

                val dueTimeText = receiveBinding.etDueTime.text?.toString()?.trim().orEmpty()
                if (dueTimeText.isBlank()) {
                    receiveBinding.tilDueTime.error = "Wajib"; return@setOnClickListener
                }
                val timeParts = dueTimeText.split(":")
                val hour = timeParts.getOrNull(0)?.toIntOrNull()
                val minute = timeParts.getOrNull(1)?.toIntOrNull()
                if (timeParts.size != 2 || hour == null || minute == null || hour !in 0..23 || minute !in 0..59) {
                    receiveBinding.tilDueTime.error = "Format jam salah (HH:mm)"; return@setOnClickListener
                }

                val dueCalendar = Calendar.getInstance().apply {
                    time = baseDate
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val dueDate = dueCalendar.time
                if (dueDate.time <= System.currentTimeMillis()) {
                    receiveBinding.tilDueTime.error = "Waktu harus lebih dari sekarang"; return@setOnClickListener
                }
                receiveBinding.tilDueDate.error = null
                receiveBinding.tilDueTime.error = null
                selectedDateTimeCal.time = dueDate


                val salePrice = if (receiveBinding.rbUpdateSalePrice.isChecked) {
                    val entered = receiveBinding.etSalePrice.text.asCleanLongOrNull()
                    if (entered == null || entered <= 0) {
                        receiveBinding.tilSalePrice.error = "Wajib"; return@setOnClickListener
                    }
                    receiveBinding.tilSalePrice.error = null
                    entered
                } else {
                    val current = p.salePrice.takeIf { it > 0 } ?: 0L
                    if (current > 0) current else unitCost
                }

                receiveDlg.dismiss()
                val dueTs = com.google.firebase.Timestamp(dueDate)
                receiveStockAndUpdatePrice(
                    sku = p.sku.ifBlank { p.id },
                    productName = p.name,
                    qty = qty,
                    unitCost = unitCost,
                    newSalePrice = salePrice,
                    dueDate = dueTs,
                    supplierName = chosenSupplier.name,
                    supplierId = chosenSupplier.id,
                    supplierTermDays = chosenSupplier.term,
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
                val stageCost = !delayStock && unitCost > 0 && stockOld > 0 && unitCost < currentCost
                val stagePrice = !delayStock && newSalePrice > 0 && stockOld > 0 && newSalePrice < currentSalePrice
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
                    "updatedAt" to now,
                    "scheduledAt" to scheduledTimestamp
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

                val previousIncoming = pSnap.getLong("stagedIncomingQty") ?: 0L
                val holdNewStock = !delayStock && (stageCost || stagePrice)
                val productUpdates = mutableMapOf<String, Any?>("updatedAt" to now)
                when {
                    delayStock -> { /* stock update deferred until posting */ }
                    holdNewStock -> {
                        productUpdates["stock"] = stockOld
                        productUpdates["stagedIncomingQty"] = previousIncoming + qty
                    }
                    else -> {
                        productUpdates["stock"] = newStock
                    }
                }
                if (stageCost) {
                    productUpdates["stagedLastCost"] = unitCost
                } else if (unitCost > 0) {
                    productUpdates["lastCost"] = unitCost
                }
                if (stagePrice) {
                    productUpdates["stagedSalePrice"] = newSalePrice
                } else if (!delayStock && newSalePrice > 0 && newSalePrice != currentSalePrice) {
                    productUpdates["salePrice"] = newSalePrice
                }
                if (stageCost || stagePrice) {
                    productUpdates["stagedOldQty"] = stockOld
                }
                trx.update(pRef, productUpdates)

                var stagedSalePriceResult: Long? = null
                if (stagePrice) {
                    stagedSalePriceResult = newSalePrice
                }

                if (!delayStock) {
                    val batchRef = db.collection("stock_batches").document()
                    val batchData = mutableMapOf<String, Any>(
                        "sku" to sku,
                        "unitCost" to unitCost,
                        "remainingQty" to qty,
                        "receivedQty" to qty,
                        "receivedAt" to now,
                        "purchaseId" to poRef.id,
                        "invoiceNo" to invoiceNoGenerated,
                        "supplierName" to (supplierName ?: ""),
                        "supplierId" to (supplierId ?: ""),
                        "dueDate" to (dueDate ?: com.google.firebase.Timestamp.now()),
                        "termDays" to termDays,
                        "state" to if (holdNewStock) BatchState.HOLD.name else BatchState.OPEN.name
                    )
                    batchData["salePrice"] = batchSalePrice
                    trx.set(batchRef, batchData)
                    val targetBatchId = batchRef.id

                    val mvRef = db.collection("inventory_movements").document()
                    val movement = mutableMapOf<String, Any>(
                        "sku" to sku,
                        "type" to "PURCHASE",
                        "qtyDelta" to qty,
                        "unitCost" to unitCost,
                        "createdAt" to now,
                        "refId" to poRef.id,
                        "batchId" to targetBatchId
                    )
                    if (holdNewStock) {
                        movement["note"] = "Stok baru menunggu stok lama habis"
                    }
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
                        SimpleDateFormat("dd MMM yyyy HH:mm", locale).format(scheduledTimestamp.toDate())
                    } catch (_: Throwable) {
                        SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(scheduledTimestamp.toDate())
                    }
                    toast("Stok akan otomatis ditambah pada $humanDate. Invoice ${result.invoiceNo}.")
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

        if (shouldDelayStock) {
            commitReceive(null, true, pendingRef)
        } else {
            commitReceive(null, false, null)
        }
    }

    private fun shouldDelayStockPosting(dueTs: com.google.firebase.Timestamp?): Boolean {
        val dueDate = dueTs?.toDate() ?: return false
        val nowMillis = System.currentTimeMillis()
        val dueMillis = dueDate.time
        return dueMillis > nowMillis
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

        val targetView: View? = null
        val endLoc = IntArray(2)
        if (targetView != null) {
            targetView.getLocationOnScreen(endLoc)
        } else {
            endLoc[0] = rootLoc[0] + (root.width * 0.75f).toInt()
            endLoc[1] = rootLoc[1] + root.height - bubbleSize
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
    val onViewPending: (Product) -> Unit,
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
        val btnPending: com.google.android.material.button.MaterialButton = v.findViewById(R.id.btnPending)
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
        val firstImage = product.images.firstOrNull()
        if (firstImage.isNullOrBlank()) {
            h.img.setImageResource(R.drawable.ic_product_placeholder)
            h.img.alpha = 0.65f
        } else {
            h.img.alpha = 1f
            h.img.load(firstImage)
        }
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
            h.tvCategory.text = "Kategori: belum diisi"
        } else {
            h.tvCategory.setTextColor(defaultCategoryColor!!)
            h.tvCategory.text = if (product.categoryName.isNotBlank()) "Kategori : ${product.categoryName}" else "Kategori: belum diisi"
        }
        h.tvPrice.text = if (product.isService) "Harga: input kasir" else "Rp ${rupiah(product.salePrice)}"
        h.tvCost.text = if (product.isService) "Modal/Unit  : -" else "Modal/Unit  : Rp ${rupiah(product.lastCost)}"
        h.btnPrimary.text = if (product.isService) "Non-Stok" else "Terima Stok"
        h.btnPrimary.isEnabled = !product.isService
        h.btnPrimary.setOnClickListener { onReceive(product, h.btnPrimary) }
        h.btnPending.visibility = if (product.isService) View.GONE else View.VISIBLE
        h.btnPending.setOnClickListener { onViewPending(product) }
        h.btnDelete.setOnClickListener { onDelete(product) }
    }
}

