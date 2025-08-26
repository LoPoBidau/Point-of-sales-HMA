package com.example.pos_hma.ui.role.super_admin

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.pos_hma.R
import com.example.pos_hma.data.Category
import com.example.pos_hma.data.Product
import com.example.pos_hma.databinding.ActivityDashboardSuperAdminBinding
import com.example.pos_hma.ui.login.LoginActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.storage.FirebaseStorage

class DashboardSuperAdminActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardSuperAdminBinding
    private val db by lazy { FirebaseFirestore.getInstance() }

    private var productsReg: ListenerRegistration? = null

    // ==== Image pick ====
    private var selectedImageUri: Uri? = null
    private var formImgPreview: ImageView? = null
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) { selectedImageUri = uri; formImgPreview?.load(uri) }
    }

    // ==== Categories ====
    private val catList = mutableListOf<Category>()
    private lateinit var catAdapter: ArrayAdapter<String>
    private var selectedCategory: Category? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardSuperAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Guard role: hanya owner/super-admin. Kalau OK → lanjut setup UI
        ensureRoleAllowedOrExit(setOf("owner", "super-admin")) {
            setupUiAfterGuard()
        }
    }

    private fun setupUiAfterGuard() {
        binding.rvProducts.layoutManager = GridLayoutManager(this, 2)
        val adapter = ProductsAdapter(
            onReceive = { openReceiveDialog(it) },
            onEdit = { openForm(it) },
            onDelete = { confirmDelete(it) }
        )
        binding.rvProducts.adapter = adapter

        binding.fabAdd.setOnClickListener { openForm(null) }

        productsReg = db.collection("products")
            .orderBy("nameLowercase")
            .addSnapshotListener { snapshot, error ->
                if (error != null) { toast("Gagal: ${error.message}"); return@addSnapshotListener }
                val items = snapshot!!.documents.map { d -> d.toObject(Product::class.java)!!.copy(id = d.id) }
                adapter.submitList(items)
            }
    }

    override fun onDestroy() {
        productsReg?.remove(); productsReg = null
        super.onDestroy()
    }

    // ===== Guard role (claims → Firestore fallback) =====
    private fun ensureRoleAllowedOrExit(allowed: Set<String>, onOk: () -> Unit) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) { goLogin("Sesi habis. Silakan login lagi."); return }

        user.getIdToken(false)
            .addOnSuccessListener { tok ->
                val claimsRole = normalizeRole(tok.claims["role"] as? String)
                if (claimsRole != null && claimsRole in allowed) { onOk(); return@addOnSuccessListener }
                // Fallback Firestore
                FirebaseFirestore.getInstance().collection("users").document(user.uid).get()
                    .addOnSuccessListener { snap ->
                        val fsRole = normalizeRole(snap.getString("role"))
                        if (fsRole != null && fsRole in allowed) { onOk() } else { goLogin("Hanya owner/super-admin.") }
                    }
                    .addOnFailureListener { goLogin("Gagal baca role. ${it.message}") }
            }
            .addOnFailureListener {
                FirebaseFirestore.getInstance().collection("users").document(user.uid).get()
                    .addOnSuccessListener { snap ->
                        val fsRole = normalizeRole(snap.getString("role"))
                        if (fsRole != null && fsRole in allowed) { onOk() } else { goLogin("Hanya owner/super-admin.") }
                    }
                    .addOnFailureListener { goLogin("Gagal baca role. ${it.message}") }
            }
    }

    private fun normalizeRole(raw: String?): String? =
        raw?.trim()?.lowercase()?.replace('_','-')?.replace(' ','-')

    private fun goLogin(msg: String) {
        toast(msg)
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    // ===== Helpers =====
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun normalizeSku(s: String) = s.trim().uppercase().replace("\\s+".toRegex(), "-")
    private fun slugify(s: String) = s.trim().lowercase()
        .replace("[^a-z0-9\\s-]".toRegex(), "")
        .replace("\\s+".toRegex(), "-")

    // ===== Categories =====
    private fun loadCategories(forType: String, onDone: (() -> Unit)? = null) {
        db.collection("categories").whereEqualTo("isActive", true).get()
            .addOnSuccessListener { qs ->
                val all = qs.documents.map { d -> d.toObject(Category::class.java)!!.copy(id = d.id) }
                val filtered = all.filter { it.forType == "both" || it.forType == forType }
                    .sortedWith(compareBy<Category> { it.sortOrder }.thenBy { it.name.lowercase() })
                catList.clear(); catList.addAll(filtered)
                catAdapter.clear(); catAdapter.addAll(filtered.map { it.name }); catAdapter.notifyDataSetChanged()
                onDone?.invoke()
            }
            .addOnFailureListener { toast("Kategori gagal dimuat: ${it.message}") }
    }

    private fun openAddCategoryDialog(defaultType: String, onAdded: (Category) -> Unit) {
        val vv = LayoutInflater.from(this).inflate(R.layout.dialog_category_form, null, false)
        val etName = vv.findViewById<TextInputEditText>(R.id.etCatName)
        val actType = vv.findViewById<AutoCompleteTextView>(R.id.actCatType)
        val types = listOf("goods","service","both")
        actType.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, types))
        actType.setText(defaultType, false)

        MaterialAlertDialogBuilder(this)
            .setTitle("Kategori baru")
            .setView(vv)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan") { _, _ ->
                val name = etName.text?.toString()?.trim().orEmpty()
                val forType = actType.text?.toString()?.trim()?.ifEmpty { defaultType } ?: defaultType
                if (name.isEmpty()) { toast("Nama kategori wajib"); return@setPositiveButton }
                val slug = slugify(name)
                val ref = db.collection("categories").document(slug)
                val now = FieldValue.serverTimestamp()
                ref.set(mapOf(
                    "name" to name, "slug" to slug, "forType" to forType,
                    "isActive" to true, "sortOrder" to 0L, "createdAt" to now, "updatedAt" to now
                )).addOnSuccessListener {
                    onAdded(Category(id = slug, name = name, slug = slug, forType = forType, isActive = true))
                }.addOnFailureListener { toast("Gagal simpan kategori: ${it.message}") }
            }.show()
    }

    // ===== Form Produk (NEW/EDIT) =====
    private fun openForm(p: Product?) {
        selectedImageUri = null
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_product_form, null, false)

        val img = v.findViewById<ImageView>(R.id.imgPreview).also { formImgPreview = it }
        val btnPick = v.findViewById<MaterialButton>(R.id.btnPickImage)
        val btnRemove = v.findViewById<MaterialButton>(R.id.btnRemoveImage)
        val etName  = v.findViewById<EditText>(R.id.etName)
        val etSKU   = v.findViewById<EditText>(R.id.etSKU)
        val etPrice = v.findViewById<EditText>(R.id.etPrice)
        val etStock = v.findViewById<EditText>(R.id.etStock)
        val tilStock= v.findViewById<TextInputLayout>(R.id.tilStock)
        val tilInitCost = v.findViewById<TextInputLayout>(R.id.tilInitCost)
        val etInitCost  = v.findViewById<TextInputEditText>(R.id.etInitCost)
        val swService = v.findViewById<MaterialSwitch>(R.id.swService)

        val actCategory = v.findViewById<AutoCompleteTextView>(R.id.actCategory)
        val btnAddCat = v.findViewById<MaterialButton>(R.id.btnAddCategory)
        catAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        actCategory.setAdapter(catAdapter)

        if (p != null) {
            etName.setText(p.name)
            etSKU.setText(p.sku); etSKU.isEnabled = false
            etPrice.setText(p.salePrice.toString())
            swService.isChecked = p.isService
            tilStock.visibility = View.GONE
            tilInitCost.visibility = View.GONE
            p.images.firstOrNull()?.let { img.load(it) } ?: img.setImageResource(R.drawable.store)
        } else {
            swService.isChecked = false
            tilStock.visibility = View.VISIBLE
            tilInitCost.visibility = View.VISIBLE
            img.setImageResource(R.drawable.store)
        }

        val initialType = if (p?.isService == true) "service" else "goods"
        loadCategories(initialType) {
            if (p != null && p.categoryId.isNotBlank()) {
                val idx = catList.indexOfFirst { it.id == p.categoryId }
                if (idx >= 0) { actCategory.setText(catList[idx].name, false); selectedCategory = catList[idx] }
            }
        }

        swService.setOnCheckedChangeListener { _, isSvc ->
            tilStock.visibility = if (isSvc) View.GONE else View.VISIBLE
            tilInitCost.visibility = if (isSvc) View.GONE else View.VISIBLE
            loadCategories(if (isSvc) "service" else "goods")
            actCategory.setText("", false); selectedCategory = null
        }

        actCategory.setOnItemClickListener { _, _, position, _ ->
            selectedCategory = catList.getOrNull(position)
        }
        btnAddCat.setOnClickListener {
            val t = if (swService.isChecked) "service" else "goods"
            openAddCategoryDialog(t) { newCat ->
                loadCategories(t) {
                    val idx = catList.indexOfFirst { it.id == newCat.id }
                    if (idx >= 0) { actCategory.setText(catList[idx].name, false); selectedCategory = catList[idx] }
                }
            }
        }

        btnPick.setOnClickListener { pickImage.launch("image/*") }
        btnRemove.setOnClickListener { selectedImageUri = null; img.setImageResource(R.drawable.store) }

        MaterialAlertDialogBuilder(this)
            .setTitle(if (p == null) "Produk Baru" else "Edit Produk")
            .setView(v)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan") { _, _ ->
                val name = etName.text.toString().trim()
                if (name.isEmpty()) { toast("Nama wajib"); return@setPositiveButton }
                val salePrice = etPrice.text.toString().toLongOrNull() ?: 0L
                val cat = selectedCategory
                val catId = cat?.id.orEmpty()
                val catName = cat?.name.orEmpty()

                if (p == null) {
                    val sku = normalizeSku(etSKU.text.toString())
                    if (sku.isEmpty()) { toast("SKU wajib"); return@setPositiveButton }
                    val isService = swService.isChecked
                    val initStock = if (isService) 0L else (etStock.text.toString().toLongOrNull() ?: 0L)
                    val initCost  = if (isService) 0L else (etInitCost.text?.toString()?.toLongOrNull() ?: 0L)
                    if (!isService && initStock > 0 && initCost <= 0L) { toast("Harga modal awal wajib"); return@setPositiveButton }

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
                            val mv = db.collection("inventory_movements").document()
                            trx.set(mv, mapOf("sku" to sku, "type" to "PURCHASE",
                                "qtyDelta" to initStock, "unitCost" to initCost, "createdAt" to now, "refId" to "INIT"))
                            val po = db.collection("purchases").document()
                            trx.set(po, mapOf(
                                "date" to now, "createdBy" to FirebaseAuth.getInstance().currentUser?.uid,
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
                        } else toast("Produk tersimpan")
                    }.addOnFailureListener { e ->
                        if (e is IllegalStateException && e.message == "EXISTS") {
                            toast("SKU sudah ada — gunakan 'Terima Stok'")
                            openReceiveDialog(Product(id = sku, sku = sku, name = name))
                        } else toast("Gagal simpan: ${e.message}")
                    }
                } else {
                    val docId = p.sku.ifBlank { p.id }
                    val updates = mutableMapOf<String, Any>(
                        "name" to name, "nameLowercase" to name.lowercase(),
                        "categoryId" to catId, "categoryName" to catName,
                        "salePrice" to salePrice, "updatedAt" to FieldValue.serverTimestamp()
                    )
                    if (selectedImageUri != null) {
                        uploadImageThen(docId, selectedImageUri!!) { url ->
                            updates["images"] = listOf(url)
                            db.collection("products").document(docId)
                                .update(updates)
                                .addOnSuccessListener { toast("Diupdate + foto baru") }
                                .addOnFailureListener { toast("Update gagal: ${it.message}") }
                        }
                    } else {
                        db.collection("products").document(docId)
                            .update(updates)
                            .addOnSuccessListener { toast("Diupdate") }
                            .addOnFailureListener { toast("Update gagal: ${it.message}") }
                    }
                }
            }.show()
    }

    // ===== Terima Stok =====
    private fun openReceiveDialog(p: Product) {
        val vv = LayoutInflater.from(this).inflate(R.layout.dialog_receive_stock_update_price, null, false)
        val etQty  = vv.findViewById<TextInputEditText>(R.id.etQty)
        val etCost = vv.findViewById<TextInputEditText>(R.id.etUnitCost)
        val etNewSale = vv.findViewById<TextInputEditText>(R.id.etNewSalePrice)

        MaterialAlertDialogBuilder(this)
            .setTitle("Terima stok: ${p.name}")
            .setView(vv)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan") { _, _ ->
                val qty = etQty.text?.toString()?.toLongOrNull() ?: 0L
                val unitCost = etCost.text?.toString()?.toLongOrNull() ?: 0L
                val newSale = etNewSale.text?.toString()?.toLongOrNull() ?: 0L
                if (qty <= 0 || unitCost <= 0 || newSale <= 0) {
                    toast("Qty, harga beli, harga jual wajib diisi"); return@setPositiveButton
                }
                receiveStockAndUpdatePrice(p.sku.ifBlank { p.id }, qty, unitCost, newSale)
            }.show()
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

            trx.update(pRef, mapOf(
                "stock" to newStock,
                "lastCost" to unitCost,
                "salePrice" to newSalePrice,
                "updatedAt" to now
            ))

            val mvRef = db.collection("inventory_movements").document()
            trx.set(mvRef, mapOf(
                "sku" to sku, "type" to "PURCHASE",
                "qtyDelta" to qty, "unitCost" to unitCost,
                "createdAt" to now, "refId" to ""
            ))

            val poRef = db.collection("purchases").document()
            trx.set(poRef, mapOf(
                "date" to now, "createdBy" to uid,
                "items" to listOf(mapOf(
                    "sku" to sku,
                    "name" to (p.getString("name") ?: sku),
                    "qty" to qty, "unitCost" to unitCost, "lineCost" to qty * unitCost
                ))
            ))
            null
        }.addOnSuccessListener { toast("Stok ditambah. Harga modal & jual diperbarui.") }
            .addOnFailureListener { e -> toast("Gagal terima stok: ${e.message}") }
    }

    // ===== Upload gambar =====
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
        MaterialAlertDialogBuilder(this)
            .setTitle("Hapus ${p.name}?")
            .setMessage("Data tidak bisa dikembalikan.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ ->
                db.collection("products").document(p.sku.ifBlank { p.id }).delete()
                    .addOnSuccessListener { toast("Dihapus") }
                    .addOnFailureListener { toast("Gagal: ${it.message}") }
            }.show()
    }
}

/* ===== Adapter grid produk ===== */
private class ProductsAdapter(
    val onReceive: (Product) -> Unit,
    val onEdit: (Product) -> Unit,
    val onDelete: (Product) -> Unit
) : ListAdapter<Product, ProductsAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Product>() {
            override fun areItemsTheSame(a: Product, b: Product) = a.id == b.id
            override fun areContentsTheSame(a: Product, b: Product) = a == b
        }
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.img)
        val tvBadge: TextView = v.findViewById(R.id.tvBadge)
        val tvName: TextView = v.findViewById(R.id.tvName)
        val tvStock: TextView = v.findViewById(R.id.tvStock)
        val tvCategory: TextView = v.findViewById(R.id.tvCategory)
        val tvPrice: TextView = v.findViewById(R.id.tvPrice)
        val btnPrimary: MaterialButton = v.findViewById(R.id.btnAdd)
        init {
            v.setOnClickListener { onEdit(getItem(bindingAdapterPosition)) }
            v.setOnLongClickListener { onDelete(getItem(bindingAdapterPosition)); true }
        }
    }

    override fun onCreateViewHolder(p: android.view.ViewGroup, vt: Int): VH {
        val v = android.view.LayoutInflater.from(p.context).inflate(R.layout.item_product, p, false)
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
        h.tvStock.text = if (product.isService) "Jasa (tanpa stok)" else "Stock : ${product.stock}"
        h.tvCategory.text = if (product.categoryName.isNotBlank()) "Kategori: ${product.categoryName}" else ""
        h.tvPrice.text = if (product.isService) "Harga: input kasir" else "Rp ${product.salePrice}"
        h.btnPrimary.text = if (product.isService) "Non-Stok" else "Terima Stok"
        h.btnPrimary.isEnabled = !product.isService
        h.btnPrimary.setOnClickListener { onReceive(product) }
    }
}
