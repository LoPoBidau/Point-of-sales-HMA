package com.example.pos_hma.ui.role.super_admin

import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.pos_hma.R
import com.example.pos_hma.data.Category
import com.example.pos_hma.data.InventoryMovement
import com.example.pos_hma.data.Product
import com.example.pos_hma.databinding.*
import com.example.pos_hma.utils.AppFlags
import com.example.pos_hma.utils.SnapshotDisposable
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import com.google.firebase.storage.FirebaseStorage
import java.text.NumberFormat
import java.util.Locale

// ===== formatter uang "10.000" =====
private val ID_LOCALE = Locale("in", "ID")
private fun rupiah(v: Long): String = NumberFormat.getInstance(ID_LOCALE).format(v)

class SuperAdminProductFragment : Fragment(), SnapshotDisposable {

    private var _binding: FragmentSuperAdminProductBinding? = null
    private val binding get() = _binding!!

    private val db by lazy { FirebaseFirestore.getInstance() }

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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentSuperAdminProductBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
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
        adapter = ProductsAdapter(
            onReceive = { openReceiveFlow(it) },
            onEdit    = { openForm(it) },
            onDelete  = { confirmDelete(it) },
            onMore    = { showProductActions(it) }  // long press
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
                binding.actCategory.setSimpleItems(names.toTypedArray())
                val idx = if (prevId != null) list.indexOfFirst { it.id == prevId } else -1
                if (idx >= 0) {
                    binding.actCategory.setText(names[idx + 1], false)
                    selectedFilterCategory = list[idx]
                } else {
                    binding.actCategory.setText(names.first(), false)
                    selectedFilterCategory = null
                }
                adapter.updateKnownCategoryIds(activeCategoryIds)
                applyFilter()
            }
            .addOnFailureListener { e -> toast("Kategori gagal dimuat: ${e.message}") }
    }

    private fun listenCategoriesForUi() {
        categoriesReg?.remove()
        categoriesReg = db.collection("categories").addSnapshotListener { snap, e ->
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
            binding.actCategory.setSimpleItems(names.toTypedArray())
            val idx = if (prevId != null) activeList.indexOfFirst { it.id == prevId } else -1
            if (idx >= 0) {
                binding.actCategory.setText(names[idx + 1], false)
                selectedFilterCategory = activeList[idx]
            } else {
                binding.actCategory.setText(names.first(), false)
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
            .addOnCompleteListener { binding.swipeRefresh.isRefreshing = false }
    }

    private fun applyFilter() {
        val q = binding.etSearch.text?.toString()?.trim()?.lowercase().orEmpty()
        val catId = selectedFilterCategory?.id.orEmpty()
        val filtered = allProducts.filter { p ->
            val okName = q.isEmpty() || p.nameLowercase.contains(q)
            val okCat  = selectedFilterCategory == null || p.categoryId == catId
            okName && okCat
        }
        adapter.submitList(filtered.toList())
        binding.tvCount.text = "Total: ${filtered.size}"
    }

    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_LONG).show()
    private fun normalizeSku(s: String) = s.trim().uppercase().replace("\\s+".toRegex(), "-")
    private fun slugify(s: String) = s.trim().lowercase().replace("[^a-z0-9\\s-]".toRegex(), "").replace("\\s+".toRegex(), "-")

    // ====== Normalisasi tipe kategori (ID/EN → key) ======
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
        val fixedIsService = p?.isService ?: false

        // ===== Prefill non-kategori =====
        if (isEditing) {
            form.swService.visibility = View.GONE
            form.tilStock.visibility = View.GONE
            form.tilInitCost.visibility = View.GONE

            form.etName.setText(p!!.name)
            form.etSKU.setText(p.sku); form.etSKU.isEnabled = false
            form.etPrice.setText(p.salePrice.toString())

            if (fixedIsService) { form.tilPrice.hint = "Harga ditentukan kasir"; form.etPrice.isEnabled = false }
            else { form.tilPrice.hint = "Harga jual (Rp)"; form.etPrice.isEnabled = true }

            if (p.images.firstOrNull().isNullOrBlank()) {
                form.imgPreview.setImageResource(R.drawable.store); form.imgPreview.alpha = .25f
            } else { form.imgPreview.alpha = 1f; form.imgPreview.load(p.images.first()) }
        } else {
            form.swService.visibility = View.VISIBLE
            form.swService.isChecked = false
            form.tilStock.visibility = View.VISIBLE
            form.tilInitCost.visibility = View.VISIBLE
            form.imgPreview.setImageResource(R.drawable.store); form.imgPreview.alpha = .25f
        }

        // Helper tipe saat ini (goods/service)
        fun currentTypeKey(): String = if (isEditing) {
            if (fixedIsService) "service" else "goods"
        } else {
            if (form.swService.isChecked) "service" else "goods"
        }

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
                val salePrice = if (isService) 0L else (form.etPrice.text.toString().toLongOrNull() ?: 0L)
                if (!isService && salePrice <= 0) { form.tilPrice.error = "Harus diisi"; ok = false }

                val initStock = if (isService) 0L else (form.etStock.text.toString().toLongOrNull() ?: 0L)
                val initCost  = if (isService) 0L else (form.etInitCost.text?.toString()?.toLongOrNull() ?: 0L)
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
                            val mv = db.collection("inventory_movements").document()
                            trx.set(mv, mapOf(
                                "sku" to sku, "type" to "PURCHASE",
                                "qtyDelta" to initStock, "unitCost" to initCost,
                                "createdAt" to now2, "refId" to "INIT"
                            ))
                            val po = db.collection("purchases").document()
                            trx.set(po, mapOf(
                                "date" to now2, "createdBy" to FirebaseAuth.getInstance().currentUser?.uid,
                                "items" to listOf(mapOf("sku" to sku, "name" to name, "qty" to initStock,
                                    "unitCost" to initCost, "lineCost" to initStock * initCost)),
                                "note" to "Initial stock"))
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
        val items = arrayOf("Riwayat Stok", "Penyesuaian Stok")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(p.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openHistoryDialog(p)
                    1 -> openAdjustStockDialog(p)
                }
            }.show()
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
            .setTitle("Riwayat Stok — ${p.name}")
            .setView(h.root)
            .setPositiveButton("Tutup", null)
            .show()
    }

    private class MovVH(private val b: ItemMovementRowBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(m: InventoryMovement) {
            val sign = if (m.qtyDelta >= 0) "+" else ""
            b.tvTitle.text = "${m.type}  (${sign}${m.qtyDelta})"
            val ts = m.createdAt?.toDate()?.toString() ?: "-"
            b.tvSub.text = "${m.note ?: ""}  •  $ts"
        }
    }

    // ===== Penyesuaian stok =====
    private fun openAdjustStockDialog(p: Product) {
        val a = DialogStockAdjustBinding.inflate(layoutInflater)
        a.rgMode.check(R.id.rbAdd)

        val dlg = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Penyesuaian Stok — ${p.name}")
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
                    val req = mapOf(
                        "sku" to sku,
                        "productName" to p.name,
                        "requestedDelta" to delta,
                        "reason" to reason,
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
                    db.runTransaction { trx ->
                        val snap = trx.get(pRef)
                        require(snap.exists()) { "Produk tidak ditemukan" }
                        require((snap.getBoolean("trackStock") ?: true)) { "Jasa tidak pakai stok" }
                        val old = snap.getLong("stock") ?: 0L
                        val newStock = old + delta
                        require(newStock >= 0) { "Stok tidak boleh negatif" }

                        trx.update(pRef, mapOf("stock" to newStock, "updatedAt" to now))
                        val mv = db.collection("inventory_movements").document()
                        trx.set(mv, mapOf(
                            "sku" to sku, "type" to "ADJUSTMENT",
                            "qtyDelta" to delta, "unitCost" to 0L,
                            "createdAt" to now, "refId" to "ADJ", "note" to reason
                        ))
                        null
                    }.addOnSuccessListener { toast("Penyesuaian tersimpan"); dlg.dismiss() }
                        .addOnFailureListener { e -> toast("Gagal: ${e.message}") }
                }
            }
        }
        dlg.show()
    }

    // ===== Terima Stok + (opsional) ubah harga =====
    private fun openReceiveFlow(p: Product) {
        val qtyBinding = DialogReceiveQtyOnlyBinding.inflate(layoutInflater)

        val qtyDlg = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Terima stok: ${p.name}")
            .setView(qtyBinding.root)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Lanjut", null)
            .create()

        currentDialog?.dismiss()
        currentDialog = qtyDlg
        qtyDlg.setOnDismissListener { currentDialog = null }

        qtyDlg.setOnShowListener {
            val btn = qtyDlg.getButton(AlertDialog.BUTTON_POSITIVE)
            btn.setOnClickListener {
                val qty = qtyBinding.etQty.text?.toString()?.toLongOrNull() ?: 0L
                if (qty <= 0) { toast("Qty wajib"); return@setOnClickListener }
                qtyDlg.dismiss()

                MaterialAlertDialogBuilder(requireContext())
                    .setMessage("Update harga beli & harga jual sekalian?")
                    .setNegativeButton("Tidak") { _, _ ->
                        receiveStockAndUpdatePrice(p.sku.ifBlank { p.id }, qty, p.lastCost, p.salePrice)
                    }
                    .setPositiveButton("Ya") { _, _ ->
                        val priceBinding = DialogUpdatePricesBinding.inflate(layoutInflater)
                        if (p.lastCost > 0) priceBinding.etUnitCost.setText(p.lastCost.toString())
                        if (p.salePrice > 0) priceBinding.etNewSalePrice.setText(p.salePrice.toString())

                        val priceDlg = MaterialAlertDialogBuilder(requireContext())
                            .setTitle("Ubah harga ${p.name}")
                            .setView(priceBinding.root)
                            .setNegativeButton("Batal", null)
                            .setPositiveButton("Simpan", null)
                            .create()

                        showDialogOnce(priceDlg)
                        priceDlg.setOnShowListener {
                            val saveBtn = priceDlg.getButton(AlertDialog.BUTTON_POSITIVE)
                            saveBtn.setOnClickListener {
                                val unitCost = priceBinding.etUnitCost.text?.toString()?.toLongOrNull() ?: 0L
                                val newSale  = priceBinding.etNewSalePrice.text?.toString()?.toLongOrNull() ?: 0L
                                if (unitCost <= 0 || newSale <= 0) { toast("Harga beli & jual wajib"); return@setOnClickListener }
                                receiveStockAndUpdatePrice(p.sku.ifBlank { p.id }, qty, unitCost, newSale)
                                priceDlg.dismiss()
                            }
                        }
                    }
                    .show()
            }
        }
        qtyDlg.show()
    }

    private fun receiveStockAndUpdatePrice(sku: String, qty: Long, unitCost: Long, newSalePrice: Long) {
        val pRef = db.collection("products").document(sku)
        val now = FieldValue.serverTimestamp()
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        db.runTransaction { trx ->
            val p = trx.get(pRef)
            require(p.exists()) { "Produk tidak ditemukan" }
            require((p.getBoolean("trackStock") ?: true)) { "Jasa tidak pakai stok" }
            val stockOld = p.getLong("stock") ?: 0L
            val newStock = stockOld + qty
            trx.update(pRef, mapOf("stock" to newStock, "lastCost" to unitCost, "salePrice" to newSalePrice, "updatedAt" to now))
            val mvRef = db.collection("inventory_movements").document()
            trx.set(mvRef, mapOf("sku" to sku, "type" to "PURCHASE", "qtyDelta" to qty, "unitCost" to unitCost, "createdAt" to now, "refId" to ""))
            val poRef = db.collection("purchases").document()
            trx.set(poRef, mapOf(
                "date" to now, "createdBy" to uid,
                "items" to listOf(mapOf("sku" to sku, "name" to (p.getString("name") ?: sku), "qty" to qty, "unitCost" to unitCost, "lineCost" to qty * unitCost))
            ))
            null
        }.addOnSuccessListener { toast("Stok ditambah."); currentDialog?.dismiss() }
            .addOnFailureListener { e -> toast("Gagal terima stok: ${e.message}") }
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

/* ===== Adapter grid ===== */
private class ProductsAdapter(
    val onReceive: (Product) -> Unit,
    val onEdit: (Product) -> Unit,
    val onDelete: (Product) -> Unit,
    val onMore: (Product) -> Unit
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
        val btnPrimary: com.google.android.material.button.MaterialButton = v.findViewById(R.id.btnAdd)
        val btnDelete: ImageButton = v.findViewById(R.id.btnDelete)
        init {
            v.setOnClickListener { onEdit(getItem(bindingAdapterPosition)) }
            v.setOnLongClickListener { onMore(getItem(bindingAdapterPosition)); true }
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
        h.btnPrimary.text = if (product.isService) "Non-Stok" else "Terima Stok"
        h.btnPrimary.isEnabled = !product.isService
        h.btnPrimary.setOnClickListener { onReceive(product) }
        h.btnDelete.setOnClickListener { onDelete(product) }
    }
}
