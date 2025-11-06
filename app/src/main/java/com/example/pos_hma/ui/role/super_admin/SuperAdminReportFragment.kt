package com.example.pos_hma.ui.role.super_admin

import android.os.Bundle
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.content.res.ColorStateList
import android.view.ViewGroup
import android.util.Log
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.pm.PackageManager
import android.widget.Toast
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.annotation.AttrRes
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import com.example.pos_hma.R
import com.example.pos_hma.databinding.FragmentSuperAdminReportBinding
import com.example.pos_hma.databinding.DialogPurchaseDetailBinding
import com.example.pos_hma.databinding.DialogReceiptPrintStatusBinding
import com.example.pos_hma.databinding.ItemPurchaseDetailItemBinding
import com.example.pos_hma.databinding.ItemReportTabBinding
import com.example.pos_hma.databinding.ItemSaleRowBinding
import com.example.pos_hma.databinding.ItemStockMovementBinding
import com.example.pos_hma.databinding.ItemFifoBatchBinding
import com.example.pos_hma.data.BatchState
import com.example.pos_hma.data.SaleStatus
import com.google.android.material.color.MaterialColors
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.datepicker.MaterialDatePicker
import com.example.pos_hma.ui.role.admin.print.ReceiptFormatter
import com.example.pos_hma.print.DirectEscPosPrinter
import com.example.pos_hma.utils.PrintersPref
import com.example.pos_hma.utils.SnapshotDisposable
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.Timestamp
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

class SuperAdminReportFragment : Fragment(), SnapshotDisposable {

    companion object {
        const val ARG_INITIAL_SALE_DOC_ID = "initialSaleDocId"
        private const val STATE_INITIAL_SALE_HANDLED = "state_initial_sale_handled"
    }

    private var _binding: FragmentSuperAdminReportBinding? = null
    private val binding get() = _binding!!

    private val tabs = listOf("Penjualan", "Pembelian", "Stok & Valuasi")
    private var pagerAdapter: ReportPagerAdapter? = null

    private val btRequestCode = 402
    private var pendingReceiptToPrint: String? = null
    private var printingDialog: AlertDialog? = null
    private var printingBinding: DialogReceiptPrintStatusBinding? = null
    private var pendingPermissionAction: (() -> Unit)? = null
    private var isPrinting = false
    private var dialogPrinterAnimator: ObjectAnimator? = null
    private val requiredBtPermissions: Array<String> by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else emptyArray()
    }

    private var initialSaleDocId: String? = null
    private var initialSaleHandled: Boolean = false

    private enum class PurchaseStatusFilter { ALL, UPCOMING, OVERDUE }

    private data class ProductSummary(
        val sku: String,
        val name: String,
        val stock: Long,
        val lastCost: Long
    )

    private data class MovementRow(
        val productName: String,
        val sku: String,
        val qty: Long,
        val unitCost: Long,
        val totalCost: Long,
        val note: String?,
        val type: String,
        val isInbound: Boolean,
        val timestamp: Date?
    )

    private enum class FifoType { INVOICE, HOLD }
    private enum class StockView { SUMMARY, FIFO }

    private data class PendingRow(
        val sku: String,
        val productName: String,
        val invoiceNo: String,
        val supplierName: String?,
        val qty: Long,
        val unitCost: Long?,
        val salePrice: Long?,
        val dueDate: Date?,
        val scheduledAt: Date?,
        val receivedAt: Date?,
        val status: String?,
        val type: FifoType,
        val sortTime: Long
    )

    private data class SaleRow(
        val id: String,
        val date: Date?,
        val total: Long,
        val cost: Long,
        val unitCost: Long,
        val hasGoods: Boolean,
        val noNota: String?
    )

    private data class PurchaseStatusInfo(
        val title: String,
        val detail: String,
        @AttrRes val badgeColorAttr: Int,
        @AttrRes val badgeTextColorAttr: Int,
        @AttrRes val strokeColorAttr: Int,
        val priority: Int
    )

    private data class PurchaseRow(
        val doc: DocumentSnapshot,
        val invoiceNo: String,
        val supplierName: String,
        val productSummary: String,
        val dueDate: Date?,
        val scheduledAt: Date?,
        val createdAt: Date?,
        val totalCost: Long,
        val items: List<Map<String, Any?>>,
        val statusTitle: String,
        val statusDetail: String,
        @AttrRes val badgeColorAttr: Int,
        @AttrRes val badgeTextColorAttr: Int,
        @AttrRes val strokeColorAttr: Int,
        val statusPriority: Int,
        val stockPosted: Boolean,
        val pendingStockId: String?
    )


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            initialSaleDocId = savedInstanceState.getString(ARG_INITIAL_SALE_DOC_ID)
            initialSaleHandled = savedInstanceState.getBoolean(
                STATE_INITIAL_SALE_HANDLED,
                initialSaleDocId.isNullOrBlank()
            )
        } else {
            initialSaleDocId = arguments?.getString(ARG_INITIAL_SALE_DOC_ID)
            initialSaleHandled = initialSaleDocId.isNullOrBlank()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentSuperAdminReportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        val adapter = ReportPagerAdapter(tabs)
        binding.pager.adapter = adapter
        pagerAdapter = adapter
        TabLayoutMediator(binding.tabs, binding.pager) { tab, pos ->
            tab.text = tabs[pos]
        }.attach()

        if (!initialSaleHandled && !initialSaleDocId.isNullOrBlank()) {
            binding.pager.post { binding.pager.currentItem = 0 }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(ARG_INITIAL_SALE_DOC_ID, initialSaleDocId)
        outState.putBoolean(STATE_INITIAL_SALE_HANDLED, initialSaleHandled)
    }

    override fun onDestroyView() {
        pagerAdapter?.dispose()
        _binding?.pager?.adapter = null
        pagerAdapter = null
        stopDialogPrinterAnimation()
        printingDialog?.dismiss()
        printingDialog = null
        printingBinding = null
        pendingPermissionAction = null
        isPrinting = false
        pendingReceiptToPrint = null
        _binding = null
        super.onDestroyView()
    }

    private fun startDirectPrintFromDialog(rawText: String) {
        val text = rawText.ifBlank { "" }
        if (text.isBlank()) {
            toast("Tidak ada data nota untuk dicetak")
            return
        }
        if (isPrinting) return
        pendingReceiptToPrint = text
        ensureBtPermissions {
            beginPrintWorkflow(text)
        }
    }

    private fun ensureBtPermissions(onGranted: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasAllBtPermissions()) {
            pendingPermissionAction = onGranted
            requestPermissions(requiredBtPermissions, btRequestCode)
        } else {
            onGranted()
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    private fun beginPrintWorkflow(text: String) {
        val savedMac = PrintersPref.getMac(requireContext())
        if (savedMac.isNullOrBlank()) {
            showBtPicker { mac ->
                PrintersPref.saveMac(requireContext(), mac)
                beginDirectPrint(mac, text)
            }
        } else {
            beginDirectPrint(savedMac, text)
        }
    }

    private fun beginDirectPrint(mac: String, text: String) {
        pendingReceiptToPrint = text
        val dialogBinding = ensurePrintingDialog()
        isPrinting = true
        dialogBinding.progress.isVisible = true
        dialogBinding.btnRetry.isVisible = false
        dialogBinding.btnSaveWithoutPrint.isVisible = false
        dialogBinding.btnClose.isVisible = false
        dialogBinding.btnClose.setOnClickListener(null)
        dialogBinding.ivStatus.setImageResource(R.drawable.ic_printer)
        dialogBinding.ivStatus.imageTintList = ColorStateList.valueOf(
            MaterialColors.getColor(
                dialogBinding.ivStatus,
                com.google.android.material.R.attr.colorPrimary
            )
        )
        dialogBinding.tvStatus.text = getString(R.string.print_status_sending)
        startDialogPrinterAnimation(dialogBinding)

        DirectEscPosPrinter.print(
            requireContext(),
            mac,
            text.ifBlank { " " },
            onSuccess = { onPrintSuccess() },
            onError = { error -> onPrintError(error) }
        )
    }

    private fun ensurePrintingDialog(): DialogReceiptPrintStatusBinding {
        val currentBinding = printingBinding
        if (currentBinding != null) {
            if (printingDialog?.isShowing != true) printingDialog?.show()
            return currentBinding
        }
        val binding = DialogReceiptPrintStatusBinding.inflate(layoutInflater)
        printingBinding = binding
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(binding.root)
            .setCancelable(false)
            .create()
        dialog.setOnDismissListener {
            stopDialogPrinterAnimation()
            printingBinding = null
            printingDialog = null
            isPrinting = false
            pendingReceiptToPrint = null
        }
        dialog.show()
        printingDialog = dialog
        binding.btnClose.isVisible = false
        binding.btnClose.setOnClickListener(null)
        binding.btnSaveWithoutPrint.isVisible = false
        return binding
    }

    private fun onPrintSuccess() {
        val binding = printingBinding ?: return
        isPrinting = false
        pendingReceiptToPrint = null
        stopDialogPrinterAnimation()
        binding.progress.isVisible = false
        binding.ivStatus.setImageResource(R.drawable.ic_check_success)
        binding.ivStatus.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(requireContext(), R.color.green_success)
        )
        binding.tvStatus.text = getString(R.string.print_status_success)
        binding.btnRetry.isVisible = false
        binding.btnSaveWithoutPrint.isVisible = false
        binding.btnClose.isVisible = false
        binding.root.postDelayed({ printingDialog?.dismiss() }, 1200)
        toast("Terkirim ke printer")
    }

    private fun onPrintError(error: Throwable) {
        val binding = printingBinding ?: return
        isPrinting = false
        stopDialogPrinterAnimation()
        binding.progress.isVisible = false
        binding.ivStatus.setImageResource(R.drawable.ic_printer)
        val errorColor =
            MaterialColors.getColor(binding.ivStatus, com.google.android.material.R.attr.colorError)
        binding.ivStatus.imageTintList = ColorStateList.valueOf(errorColor)
        val message = error.message?.takeUnless { it.isBlank() } ?: "-"
        binding.tvStatus.text = getString(R.string.print_status_failed, message)
        binding.btnRetry.isVisible = true
        binding.btnRetry.setOnClickListener {
            val retryText = pendingReceiptToPrint
            if (retryText.isNullOrBlank()) {
                printingDialog?.dismiss()
                return@setOnClickListener
            }
            binding.progress.isVisible = true
            binding.btnRetry.isVisible = false
            binding.btnClose.isVisible = false
            binding.ivStatus.setImageResource(R.drawable.ic_printer)
            binding.ivStatus.imageTintList = ColorStateList.valueOf(
                MaterialColors.getColor(
                    binding.ivStatus,
                    com.google.android.material.R.attr.colorPrimary
                )
            )
            binding.tvStatus.text = getString(R.string.print_status_sending)
            ensureBtPermissions { beginPrintWorkflow(retryText) }
        }
        binding.btnClose.apply {
            isVisible = true
            text = getString(R.string.print_status_close)
            setOnClickListener { printingDialog?.dismiss() }
        }
        toast("Gagal cetak: $message")
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN])
    private fun showBtPicker(onPicked: (String) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasAllBtPermissions()) {
            ensureBtPermissions { showBtPicker(onPicked) }
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val bonded = adapter?.bondedDevices?.toList().orEmpty()
        if (bonded.isEmpty()) {
            toast("Tidak ada printer terpasang (pairing dulu)")
            return
        }
        val labels = bonded.map { "${it.name} (${it.address})" }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Pilih Printer")
            .setItems(labels) { _, which -> onPicked(bonded[which].address) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun hasAllBtPermissions(): Boolean {
        if (requiredBtPermissions.isEmpty()) return true
        return requiredBtPermissions.all {
            ContextCompat.checkSelfPermission(
                requireContext(),
                it
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == btRequestCode) {
            if (hasAllBtPermissions()) {
                pendingPermissionAction?.invoke()
            } else {
                toast("Izin Bluetooth diperlukan untuk mencetak")
            }
            pendingPermissionAction = null
        }
    }

    override fun disposeSnapshots() {
        pagerAdapter?.dispose()
    }

    private inner class ReportPagerAdapter(private val titles: List<String>) :
        RecyclerView.Adapter<ReportPagerAdapter.VH>() {

        inner class VH(val b: ItemReportTabBinding) : RecyclerView.ViewHolder(b.root)


        private var purchaseListener: ListenerRegistration? = null
        private var purchaseHolder: VH? = null

        private var stockHolder: VH? = null
        private var stockToken: Int = 0

        private fun clearPurchaseListener() {
            purchaseListener?.remove()
            purchaseListener = null
        }

        fun dispose() {
            clearPurchaseListener()
            purchaseHolder = null
            stockHolder = null
        }


        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemReportTabBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun getItemCount() = titles.size


        override fun onViewRecycled(holder: VH) {
            if (holder == purchaseHolder) {
                clearPurchaseListener()
                purchaseHolder = null
            }
            if (holder == stockHolder) {
                stockHolder = null
            }
            super.onViewRecycled(holder)
        }

        private fun isStockRequestValid(holder: VH, token: Int): Boolean =
            holder == stockHolder && token == stockToken

        override fun onBindViewHolder(holder: VH, position: Int) {
            val title = titles[position]
            holder.b.tvTitle.text = title
            when (title) {
                "Penjualan" -> bindSales(holder)
                "Pembelian" -> {
                    purchaseHolder = holder
                    bindPurchases(holder)
                }

                else -> {
                    stockHolder = holder
                    bindStockValuation(holder)
                }
            }
        }

        private fun bindSales(holder: VH) {
            val ctx = holder.itemView.context
            holder.b.toggleStockView.visibility = View.GONE
            val db = FirebaseFirestore.getInstance()
            val nf = NumberFormat.getInstance(Locale("in", "ID"))
            val df = SimpleDateFormat("dd MMM yy HH:mm", Locale("in", "ID"))
            val dfMonth = SimpleDateFormat("MMMM yyyy", Locale("in", "ID"))
            val dfDay = SimpleDateFormat("dd MMM yy", Locale("in", "ID"))

            holder.b.toggleStockView.visibility = View.GONE
            holder.b.tvDesc.visibility = View.GONE
            holder.b.tvTitle.visibility = View.GONE
            holder.b.hideSummaryButton()
            holder.b.rvSales.visibility = View.VISIBLE
            holder.b.scrollRange.visibility = View.VISIBLE
            holder.b.chipGroupRange.visibility = View.VISIBLE
            holder.b.searchRow.visibility = View.VISIBLE
            holder.b.filterCard.visibility = View.VISIBLE
            holder.b.scrollPurchaseStatus.visibility = View.GONE
            holder.b.chipGroupPurchaseStatus.visibility = View.GONE
            holder.b.chipGroupRange.isSingleSelection = false

            fun fetchAndShow(saleId: String) {
                db.collection("sales").document(saleId).get().addOnSuccessListener { doc ->
                    val itemsAny = (doc.get("items") as? List<Map<String, Any?>>).orEmpty()
                    val items = itemsAny.map { m ->
                        ReceiptFormatter.Item(
                            name = (m["name"] as? String).orEmpty(),
                            qty = (m["qty"] as? Number)?.toLong() ?: 0L,
                            unitPrice = (m["unitPrice"] as? Number)?.toLong() ?: 0L,
                            isService = (m["isService"] as? Boolean) ?: false
                        )
                    }
                    // Hitung total harga modal & unit cost (jika hanya 1 jenis barang)
                    var totalCost = 0L
                    var totalQty = 0L
                    var unitCostSingle: Long? = null
                    val goods = itemsAny.filter { !(it["isService"] as? Boolean ?: false) }
                    goods.forEach { m ->
                        val qty = (m["qty"] as? Number)?.toLong() ?: 0L
                        val unitCost = (m["unitCost"] as? Number)?.toLong() ?: 0L
                        totalCost += qty * unitCost
                        totalQty += qty
                    }
                    if (goods.size == 1) {
                        val g = goods.first()
                        unitCostSingle = (g["unitCost"] as? Number)?.toLong()
                    }

                    val store = ReceiptFormatter.StoreInfo(
                        name = "HAGWY MULYA AGUNG BENGKEL",
                        address1 = "JL. APT PRANOTO",
                        address2 = "KOTA SAMARINDA SEBERANG",
                        phone = "0341-8686715"
                    )
                    val noNota = doc.getString("noNota") ?: saleId
                    val saleInfo = ReceiptFormatter.SaleInfo(
                        saleId = noNota,
                        date = doc.getTimestamp("createdAt")?.toDate() ?: Date(),
                        total = doc.getLong("total") ?: 0L,
                        paid = doc.getLong("paid") ?: (doc.getLong("total") ?: 0L)
                    )
                    val svc = doc.getLong("serviceFee") ?: 0L
                    val svcDesc = doc.getString("serviceDescription")?.trim().orEmpty()
                    val payload = ReceiptFormatter.Payload(
                        store = store,
                        sale = saleInfo,
                        items = items,
                        serviceFee = svc,
                        serviceDescription = svcDesc.takeIf { it.isNotEmpty() },
                        totalCost = if (goods.isNotEmpty() && totalQty > 1L) totalCost else null,
                        unitCost = unitCostSingle
                    )

                    val content = android.widget.TextView(ctx).apply {
                        typeface = android.graphics.Typeface.MONOSPACE
                        textAlignment = android.view.View.TEXT_ALIGNMENT_VIEW_START
                        gravity = android.view.Gravity.START
                        setLineSpacing(4f, 1.0f)
                        setTextIsSelectable(true)
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                    }
                    val fallbackScreen = ReceiptFormatter.buildForScreen(payload)
                    val receiptForPrinter = ReceiptFormatter.buildForPrinter(payload)
                    content.text = fallbackScreen
                    content.doOnLayout { view ->
                        val textView = view as android.widget.TextView
                        val columns = ReceiptFormatter.estimateColumns(textView)
                        textView.text = ReceiptFormatter.buildForScreen(payload, columns)
                    }
                    val container = android.widget.LinearLayout(ctx).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        gravity = android.view.Gravity.START
                        setPadding(24, 24, 24, 24)
                        addView(
                            content,
                            android.widget.LinearLayout.LayoutParams(
                                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { gravity = android.view.Gravity.START }
                        )
                    }
                    val scroll = android.widget.ScrollView(ctx).apply {
                        isFillViewport = true
                        val params = android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                        )
                        addView(container, params)
                    }
                    MaterialAlertDialogBuilder(ctx)
                        .setView(scroll)
                        .setPositiveButton("Cetak") { _, _ ->
                            startDirectPrintFromDialog(receiptForPrinter)
                        }
                        .setNegativeButton("Tutup", null)
                        .show()
                }
            }

            holder.b.btnSearchSaleId.visibility = View.GONE
            holder.b.etSearchSaleId.setOnEditorActionListener { _, _, _ -> true }

            holder.b.rvSales.layoutManager = LinearLayoutManager(ctx)
            val allSales = mutableListOf<SaleRow>()
            val filteredSales = mutableListOf<SaleRow>()
            val adapter = object : RecyclerView.Adapter<SaleVH>() {
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SaleVH =
                    SaleVH(ItemSaleRowBinding.inflate(LayoutInflater.from(ctx), parent, false))

                override fun getItemCount(): Int = filteredSales.size

                override fun onBindViewHolder(holderSale: SaleVH, position: Int) {
                    val row = filteredSales[position]
                    holderSale.v.tvSaleId.text = "No. Nota: ${row.noNota ?: row.id}"
                    holderSale.v.tvDate.text = row.date?.let { df.format(it) } ?: "-"
                    val info = if (row.hasGoods) {
                        "Total: Rp ${nf.format(row.total)}"
                    } else {
                        "Transaksi Jasa - Total: Rp ${nf.format(row.total)}"
                    }
                    holderSale.v.tvTotal.text = info
                    holderSale.v.root.setOnClickListener { fetchAndShow(row.id) }
                }
            }
            holder.b.rvSales.adapter = adapter

            fun maybeShowInitialSale() {
                if (this@SuperAdminReportFragment.initialSaleHandled) return
                val target = this@SuperAdminReportFragment.initialSaleDocId
                if (target.isNullOrBlank()) return
                this@SuperAdminReportFragment.initialSaleHandled = true
                this@SuperAdminReportFragment.initialSaleDocId = null
                fetchAndShow(target)
            }

            fun CharSequence?.normalizeSaleId(): String =
                this?.filter { it.isLetterOrDigit() }
                    ?.toString()
                    ?.toLowerCase(Locale("in", "ID"))
                    .orEmpty()

            fun applySalesSearch() {
                val query = holder.b.etSearchSaleId.text.normalizeSaleId()
                filteredSales.clear()
                if (query.isBlank()) {
                    filteredSales.addAll(allSales)
                } else {
                    filteredSales.addAll(
                        allSales.filter { row ->
                            val notaNorm = row.noNota?.normalizeSaleId().orEmpty()
                            val idNorm = row.id.normalizeSaleId()
                            notaNorm.contains(query) || idNorm.contains(query)
                        }
                    )
                }
                adapter.notifyDataSetChanged()
            }

            val saleWatcherExisting = holder.b.etSearchSaleId.getTag(R.id.tag_search_watcher) as? android.text.TextWatcher
            saleWatcherExisting?.let { holder.b.etSearchSaleId.removeTextChangedListener(it) }
            val saleWatcher = object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    holder.b.tilSearchSaleId.error = null
                    applySalesSearch()
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            }
            holder.b.etSearchSaleId.addTextChangedListener(saleWatcher)
            holder.b.etSearchSaleId.setTag(R.id.tag_search_watcher, saleWatcher)

            var activeRangeChipId: Int? = null

            fun updateRangeChipStates() {
                holder.b.chipToday.isChecked = activeRangeChipId == holder.b.chipToday.id
                holder.b.chipWeek.isChecked = activeRangeChipId == holder.b.chipWeek.id
                holder.b.chipMonth.isChecked = activeRangeChipId == holder.b.chipMonth.id
                holder.b.chipCustom.isChecked = activeRangeChipId == holder.b.chipCustom.id
            }

            fun loadSalesWithin(start: Date?, end: Date?, label: String) {
                var query = db.collection("sales")
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .limit(200)
                if (start != null && end != null) {
                    query = query
                        .whereGreaterThanOrEqualTo("createdAt", start)
                        .whereLessThan("createdAt", end)
                }
                query.get().addOnSuccessListener { snap ->
                    allSales.clear()
                    for (doc in snap.documents) {
                        val status = doc.getString("status") ?: SaleStatus.PAID
                        if (!status.equals(SaleStatus.PAID, ignoreCase = true)) continue
                        val ts = doc.getTimestamp("createdAt")?.toDate()
                        val total = doc.getLong("total") ?: 0L
                        val noNota = doc.getString("noNota")
                        val items = (doc.get("items") as? List<Map<String, Any?>>).orEmpty()
                        var cost = 0L
                        var qtySum = 0L
                        var hasGoods = false
                        items.forEach { m ->
                            val qty = (m["qty"] as? Number)?.toLong() ?: 0L
                            val unitCost = (m["unitCost"] as? Number)?.toLong() ?: 0L
                            val isService = (m["isService"] as? Boolean) ?: false
                            if (!isService) hasGoods = true
                            if (!isService) {
                                cost += qty * unitCost
                                qtySum += qty
                            }
                        }
                        val unitCostAvg = if (qtySum > 0L) cost / qtySum else 0L
                        allSales += SaleRow(doc.id, ts, total, cost, unitCostAvg, hasGoods, noNota)
                    }
                    holder.b.tvKeterangan.visibility = View.VISIBLE
                    holder.b.tvKeterangan.text = "$label : ${allSales.size}"
                    applySalesSearch()
                    maybeShowInitialSale()
                }
            }

            fun loadAllSales() {
                activeRangeChipId = null
                updateRangeChipStates()
                holder.b.chipGroupRange.clearCheck()
                loadSalesWithin(null, null, "Semua Transaksi")
            }

            fun selectToday() {
                activeRangeChipId = holder.b.chipToday.id
                updateRangeChipStates()
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                val start = cal.time
                val end = Date(System.currentTimeMillis() + 1)
                loadSalesWithin(start, end, "Transaksi Hari Ini")
            }

            fun selectWeek() {
                activeRangeChipId = holder.b.chipWeek.id
                updateRangeChipStates()
                val cal = Calendar.getInstance().apply {
                    firstDayOfWeek = Calendar.MONDAY
                    set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                val start = cal.time
                val end = Date(System.currentTimeMillis() + 1)
                loadSalesWithin(start, end, "Transaksi Minggu Ini")
            }

            fun selectMonth(start: Date, end: Date, label: String) {
                activeRangeChipId = holder.b.chipMonth.id
                updateRangeChipStates()
                loadSalesWithin(start, end, label)
            }

            fun selectCustom(start: Date, end: Date) {
                activeRangeChipId = holder.b.chipCustom.id
                updateRangeChipStates()
                val endInclusive = Date(end.time - 1L)
                val sameDay = dfDay.format(start) == dfDay.format(endInclusive)
                val label = if (sameDay) {
                    "Transaksi Pada Tanggal (${dfDay.format(start)})"
                } else {
                    "Transaksi Pada Tanggal (${dfDay.format(start)} - ${dfDay.format(endInclusive)})"
                }
                loadSalesWithin(start, end, label)
            }

            holder.b.chipToday.setOnClickListener {
                if (activeRangeChipId == holder.b.chipToday.id) {
                    loadAllSales()
                } else {
                    selectToday()
                }
            }

            holder.b.chipWeek.setOnClickListener {
                if (activeRangeChipId == holder.b.chipWeek.id) {
                    loadAllSales()
                } else {
                    selectWeek()
                }
            }

            holder.b.chipMonth.setOnClickListener {
                if (activeRangeChipId == holder.b.chipMonth.id) {
                    loadAllSales()
                } else {
                    pickMonth(holder) { start, end, label ->
                        selectMonth(start, end, label)
                    }
                    updateRangeChipStates()
                }
            }

            holder.b.chipCustom.setOnClickListener {
                if (activeRangeChipId == holder.b.chipCustom.id) {
                    loadAllSales()
                } else {
                    pickDateRange(holder) { start, end -> selectCustom(start, end) }
                    updateRangeChipStates()
                }
            }

            loadAllSales()
        }

        inner class SaleVH(val v: ItemSaleRowBinding) : RecyclerView.ViewHolder(v.root)

        private fun pickDateRange(holder: VH, onPicked: (Date, Date) -> Unit) {
            val picker = MaterialDatePicker.Builder.dateRangePicker().build()
            picker.addOnPositiveButtonClickListener { range ->
                val start = Date(range.first ?: return@addOnPositiveButtonClickListener)
                val endExclusive = Date(
                    (range.second ?: return@addOnPositiveButtonClickListener) + 24L * 60 * 60 * 1000
                )
                onPicked(start, endExclusive)
            }
            val fm = (holder.itemView.context as FragmentActivity).supportFragmentManager
            picker.show(fm, "dateRangeSuper")
        }

        private fun pickMonth(holder: VH, onPicked: (Date, Date, String) -> Unit) {
            val cal = Calendar.getInstance()
            val labels = (0 until 12).map { i ->
                val c = (cal.clone() as Calendar).apply {
                    add(Calendar.MONTH, -i)
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(
                    Calendar.SECOND,
                    0
                ); set(Calendar.MILLISECOND, 0)
                }
                val label = SimpleDateFormat("MMMM yyyy", Locale("in", "ID")).format(c.time)
                label to c.time
            }
            val items = labels.map { it.first }.toTypedArray()
            MaterialAlertDialogBuilder(holder.itemView.context)
                .setTitle("Pilih Bulan")
                .setItems(items) { _, which ->
                    val start = labels[which].second
                    val c2 = Calendar.getInstance().apply { time = start; add(Calendar.MONTH, 1) }
                    val endExclusive = c2.time
                    onPicked(start, endExclusive, "Transaksi Pada Bulan (${labels[which].first})")
                }
                .setNegativeButton("Batal", null)
                .show()
        }

        private fun bindPurchases(holder: VH) {
            val ctx = holder.itemView.context
            val db = FirebaseFirestore.getInstance()
            val localeId = Locale("in", "ID")
            val nf = NumberFormat.getInstance(localeId)
            val dfDate = SimpleDateFormat("dd MMM yyyy", localeId)
            val dfDateTime = SimpleDateFormat("dd MMM yyyy HH:mm", localeId)
            val dfTime = SimpleDateFormat("HH:mm", localeId)


            clearPurchaseListener()

            holder.b.tvTitle.visibility = View.GONE
            holder.b.tvDesc.visibility = View.GONE
            holder.b.hideSummaryButton()
            holder.b.filterCard.visibility = View.VISIBLE
            holder.b.searchRow.visibility = View.VISIBLE
            holder.b.scrollRange.visibility = View.GONE
            holder.b.chipGroupRange.visibility = View.GONE
            holder.b.scrollPurchaseStatus.visibility = View.VISIBLE
            holder.b.chipGroupPurchaseStatus.visibility = View.VISIBLE
            holder.b.rvSales.visibility = View.VISIBLE
            holder.b.tilSearchSaleId.error = null
            holder.b.tilSearchSaleId.hint = null
            holder.b.etSearchSaleId.hint = "Cari No. Invoice"
            holder.b.btnSearchSaleId.visibility = View.GONE
            holder.b.tvKeterangan.visibility = View.GONE

            val rows = mutableListOf<PurchaseRow>()
            var masterRows: List<PurchaseRow> = emptyList()
            var lastEmptyMessage: String? = null
            var lastInfoMessage: String? = null
            var lastContextLabel: String? = null
            var statusFilter = PurchaseStatusFilter.ALL
            var activeStatusChipId: Int? = null
            val ensuredPendingIds = mutableSetOf<String>()
            holder.b.rvSales.layoutManager = LinearLayoutManager(ctx)


            fun setSearchControlsEnabled(enabled: Boolean) {
                holder.b.etSearchSaleId.isEnabled = true
            }

            fun computeStatus(due: Date?): PurchaseStatusInfo {
                val today = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                if (due == null) {
                    return PurchaseStatusInfo(
                        title = "Tanpa jatuh tempo",
                        detail = "Transaksi tunai / lunas",
                        badgeColorAttr = com.google.android.material.R.attr.colorSurfaceVariant,
                        badgeTextColorAttr = com.google.android.material.R.attr.colorOnSurface,
                        strokeColorAttr = com.google.android.material.R.attr.colorOutline,
                        priority = 2
                    )
                }
                val dueCal = Calendar.getInstance().apply {
                    time = due
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val diffDays =
                    ((dueCal.timeInMillis - today.timeInMillis) / (24L * 60 * 60 * 1000L)).toInt()
                return when {
                    diffDays < 0 -> PurchaseStatusInfo(
                        title = "Sudah jatuh tempo",
                        detail = "Terlambat ${-diffDays} hari",
                        badgeColorAttr = com.google.android.material.R.attr.colorError,
                        badgeTextColorAttr = com.google.android.material.R.attr.colorOnError,
                        strokeColorAttr = com.google.android.material.R.attr.colorError,
                        priority = 0
                    )

                    diffDays == 0 -> PurchaseStatusInfo(
                        title = "Sudah jatuh tempo",
                        detail = "Jatuh tempo hari ini",
                        badgeColorAttr = com.google.android.material.R.attr.colorError,
                        badgeTextColorAttr = com.google.android.material.R.attr.colorOnError,
                        strokeColorAttr = com.google.android.material.R.attr.colorError,
                        priority = 0
                    )

                    else -> PurchaseStatusInfo(
                        title = "Menunggu jatuh tempo",
                        detail = "Sisa $diffDays hari",
                        badgeColorAttr = com.google.android.material.R.attr.colorSecondary,
                        badgeTextColorAttr = com.google.android.material.R.attr.colorOnSecondary,
                        strokeColorAttr = com.google.android.material.R.attr.colorSecondary,
                        priority = 1
                    )
                }
            }

            fun mapDocToRow(doc: DocumentSnapshot): PurchaseRow {
                val items = (doc.get("items") as? List<Map<String, Any?>>).orEmpty()
                val firstName =
                    (items.firstOrNull()?.get("name") as? String)?.ifBlank { "-" } ?: "-"
                val productSummary =
                    if (items.size > 1) "$firstName (+${items.size - 1} lainnya)" else firstName
                val invoice = doc.getString("invoiceNo")?.takeIf { it.isNotBlank() } ?: "-"
                val supplier = doc.getString("supplierName")?.takeIf { it.isNotBlank() } ?: "-"
                val due = doc.getTimestamp("dueDate")?.toDate()
                val scheduled = doc.getTimestamp("scheduledAt")?.toDate() ?: due
                val created = doc.getTimestamp("date")?.toDate()
                val total = doc.getLong("totalCost") ?: items.fold(0L) { acc, item ->
                    val qty = (item["qty"] as? Number)?.toLong() ?: 0L
                    val cost = (item["unitCost"] as? Number)?.toLong() ?: 0L
                    acc + qty * cost
                }
                val status = computeStatus(due)
                val stockPosted = doc.getBoolean("stockPosted") ?: false
                val pendingStockId = doc.getString("pendingStockId")
                return PurchaseRow(
                    doc = doc,
                    invoiceNo = invoice,
                    supplierName = supplier,
                    productSummary = productSummary,
                    dueDate = due,
                    scheduledAt = scheduled,
                    createdAt = created,
                    totalCost = total,
                    items = items,
                    statusTitle = status.title,
                    statusDetail = status.detail,
                    badgeColorAttr = status.badgeColorAttr,
                    badgeTextColorAttr = status.badgeTextColorAttr,
                    strokeColorAttr = status.strokeColorAttr,
                    statusPriority = status.priority,
                    stockPosted = stockPosted,
                    pendingStockId = pendingStockId
                )
            }

            fun showDetail(row: PurchaseRow) {
                val detailBinding = DialogPurchaseDetailBinding.inflate(LayoutInflater.from(ctx))
                detailBinding.tvDialogInvoice.text = row.invoiceNo
                detailBinding.tvDialogSupplier.text =
                    "Supplier: ${row.supplierName.ifBlank { "-" }}"
                detailBinding.tvDialogDate.text =
                    "Pembelian pada: ${row.createdAt?.let { dfDateTime.format(it) } ?: "-"}"
                detailBinding.tvDialogDue.text =
                    "Jatuh Tempo: ${row.dueDate?.let { dfDate.format(it) } ?: "-"}"
                detailBinding.tvDialogDueTime.text =
                    "Jam Jatuh Tempo: ${row.scheduledAt?.let { dfTime.format(it) } ?: "-"}"
                detailBinding.tvDialogTotal.text = "Total: Rp ${nf.format(row.totalCost)}"
                detailBinding.tvDialogStatusTitle.text = row.statusTitle
                detailBinding.tvDialogStatusDetail.text = row.statusDetail

                val badgeColor =
                    MaterialColors.getColor(detailBinding.tvDialogStatusTitle, row.badgeColorAttr)
                val badgeTextColor = MaterialColors.getColor(
                    detailBinding.tvDialogStatusTitle,
                    row.badgeTextColorAttr
                )
                detailBinding.tvDialogStatusTitle.backgroundTintList =
                    ColorStateList.valueOf(badgeColor)
                detailBinding.tvDialogStatusTitle.setTextColor(badgeTextColor)
                detailBinding.tvDialogStatusDetail.setTextColor(badgeColor)

                val container = detailBinding.containerItems
                container.removeAllViews()
                if (row.items.isEmpty()) {
                    val empty = android.widget.TextView(ctx).apply {
                        text = "Tidak ada item"
                        setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Body2)
                    }
                    container.addView(empty)
                } else {
                    row.items.forEachIndexed { index, item ->
                        val itemBinding = ItemPurchaseDetailItemBinding.inflate(
                            LayoutInflater.from(ctx),
                            container,
                            false
                        )
                        val name = (item["name"] as? String)?.ifBlank { "-" } ?: "-"
                        val qty = (item["qty"] as? Number)?.toLong() ?: 0L
                        val cost = (item["unitCost"] as? Number)?.toLong() ?: 0L
                        itemBinding.tvItemName.text = "${index + 1}. $name"
                        itemBinding.tvItemMeta.text =
                            "Qty ${nf.format(qty)} x Rp ${nf.format(cost)} = Rp ${nf.format(qty * cost)}"
                        itemBinding.divider.visibility =
                            if (index == row.items.lastIndex) View.GONE else View.VISIBLE
                        container.addView(itemBinding.root)
                    }
                }

                MaterialAlertDialogBuilder(ctx)
                    .setTitle("Detail Pembelian ${row.invoiceNo}")
                    .setView(detailBinding.root)
                    .setPositiveButton("Tutup", null)
                    .show()
            }

            class PurchaseVH(private val b: com.example.pos_hma.databinding.ItemPurchaseRowBinding) :
                RecyclerView.ViewHolder(b.root) {
                fun bind(row: PurchaseRow) {
                    b.tvInvoice.text = row.invoiceNo
                    b.tvStatusTitle.text = row.statusTitle
                    b.tvStatusDetail.text = row.statusDetail

                    val badgeColor = MaterialColors.getColor(b.tvStatusTitle, row.badgeColorAttr)
                    val badgeTextColor =
                        MaterialColors.getColor(b.tvStatusTitle, row.badgeTextColorAttr)
                    b.tvStatusTitle.backgroundTintList = ColorStateList.valueOf(badgeColor)
                    b.tvStatusTitle.setTextColor(badgeTextColor)
                    b.tvStatusDetail.setTextColor(badgeColor)

                    val strokeColor = MaterialColors.getColor(b.root, row.strokeColorAttr)
                    val strokeWidth =
                        (b.root.resources.displayMetrics.density * 2f).toInt().coerceAtLeast(2)
                    b.root.strokeWidth = strokeWidth
                    b.root.setStrokeColor(strokeColor)

                    b.tvSupplier.text =
                        if (row.supplierName.isBlank()) "Supplier: -" else "Supplier: ${row.supplierName}"
                    b.tvProduct.text = "Barang: ${row.productSummary}"
                    val dueDateText = row.dueDate?.let { dfDate.format(it) } ?: "-"
                    val dueTimeText = row.scheduledAt?.let { dfTime.format(it) } ?: "-"
                    val purchaseAtText = row.createdAt?.let { dfDateTime.format(it) } ?: "-"
                    b.tvDueDate.text = "Jatuh Tempo: $dueDateText"
                    b.tvDueTime.text = "Jam Jatuh Tempo: $dueTimeText"
                    b.tvPurchaseAt.text = "Pembelian pada: $purchaseAtText"
                    b.tvTotal.text = "Total: Rp ${nf.format(row.totalCost)}"

                    b.root.setOnClickListener { showDetail(row) }
                }
            }

            val adapter = object : RecyclerView.Adapter<PurchaseVH>() {
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PurchaseVH {
                    val row = com.example.pos_hma.databinding.ItemPurchaseRowBinding.inflate(
                        LayoutInflater.from(parent.context),
                        parent,
                        false
                    )
                    return PurchaseVH(row)
                }

                override fun onBindViewHolder(holder: PurchaseVH, position: Int) =
                    holder.bind(rows[position])

                override fun getItemCount(): Int = rows.size
            }
            holder.b.rvSales.adapter = adapter

            holder.b.hideSummaryButton()

            fun updateSummary(current: List<PurchaseRow>) {
                if (current.isEmpty()) {
                    holder.b.hideSummaryButton()
                    return
                }
                val totalValue = current.sumOf { it.totalCost }
                val overdueCount = current.count { it.statusPriority == 0 }
                val upcomingCount = current.count { it.statusPriority == 1 }
                val noDueCount = current.size - overdueCount - upcomingCount
                val rows = buildList {
                    add("Total invoice" to nf.format(current.size.toLong()))
                    add("Nilai pembelian" to "Rp ${nf.format(totalValue)}")
                    add("Sudah jatuh tempo" to nf.format(overdueCount.toLong()))
                    add("Menunggu jatuh tempo" to nf.format(upcomingCount.toLong()))
                    if (noDueCount > 0) add("Tanpa tempo" to nf.format(noDueCount.toLong()))
                }
                holder.b.showSummaryButton("Ringkasan Pembelian", rows) { summaryRows ->
                    this@SuperAdminReportFragment.showSummaryDialog("Ringkasan Pembelian", summaryRows)
                }
            }

            fun ensureDueAutoPosting(source: List<PurchaseRow>) {
                if (source.isEmpty()) return
                val pendingAuto =
                    source.count { !it.stockPosted && !it.pendingStockId.isNullOrBlank() }
                if (pendingAuto > 0) {
                    Log.d(
                        "SuperAdminReport",
                        "Menunggu scheduler server mem-posting $pendingAuto pembelian"
                    )
                }
            }

            fun filterAndDisplay() {
                val filtered = when (statusFilter) {
                    PurchaseStatusFilter.ALL -> masterRows
                    PurchaseStatusFilter.UPCOMING -> masterRows.filter { it.statusPriority == 1 }
                    PurchaseStatusFilter.OVERDUE -> masterRows.filter { it.statusPriority == 0 }
                }
                rows.clear()
                rows.addAll(filtered)
                adapter.notifyDataSetChanged()

                if (lastContextLabel.isNullOrBlank()) {
                    holder.b.tvKeterangan.visibility = View.GONE
                } else {
                    holder.b.tvKeterangan.visibility = View.VISIBLE
                    holder.b.tvKeterangan.text = lastContextLabel
                }

                if (filtered.isEmpty()) {
                    holder.b.hideSummaryButton()
                    holder.b.tvDesc.visibility = View.VISIBLE
                    val emptyMsg = when {
                        masterRows.isEmpty() -> lastEmptyMessage
                            ?: "Tidak ada data pembelian pada rentang ini."

                        statusFilter == PurchaseStatusFilter.UPCOMING -> "Tidak ada pembelian yang menunggu jatuh tempo."
                        statusFilter == PurchaseStatusFilter.OVERDUE -> "Tidak ada pembelian yang sudah jatuh tempo."
                        else -> lastEmptyMessage ?: "Tidak ada data pembelian pada rentang ini."
                    }
                    holder.b.tvDesc.text = emptyMsg
                } else {
                    if (lastInfoMessage.isNullOrBlank()) {
                        holder.b.tvDesc.visibility = View.GONE
                    } else {
                        holder.b.tvDesc.visibility = View.VISIBLE
                        holder.b.tvDesc.text = lastInfoMessage
                    }
                    updateSummary(filtered)
                }
            }


            fun statusSortKey(priority: Int): Int = when (priority) {
                1 -> 0
                0 -> 1
                else -> 2
            }

            fun applyRows(
                newRows: List<PurchaseRow>,
                emptyMessage: String? = null,
                infoMessage: String? = null,
                contextLabel: String? = null
            ) {
                masterRows =
                    newRows.sortedWith(compareBy<PurchaseRow> { statusSortKey(it.statusPriority) }
                        .thenBy { it.dueDate ?: Date(Long.MAX_VALUE) }
                        .thenByDescending { it.createdAt?.time ?: Long.MIN_VALUE })
                lastEmptyMessage = emptyMessage
                lastInfoMessage = infoMessage
                lastContextLabel = contextLabel
                ensureDueAutoPosting(masterRows)
                filterAndDisplay()
            }

            holder.b.chipGroupPurchaseStatus.isSingleSelection = false
            holder.b.chipGroupPurchaseStatus.clearCheck()
            holder.b.chipStatusAll.visibility = View.GONE

            fun updateStatusChips() {
                holder.b.chipStatusUpcoming.isChecked = activeStatusChipId == holder.b.chipStatusUpcoming.id
                holder.b.chipStatusOverdue.isChecked = activeStatusChipId == holder.b.chipStatusOverdue.id
            }

            fun applyStatusFilter(filter: PurchaseStatusFilter, chipId: Int?) {
                statusFilter = filter
                activeStatusChipId = chipId
                updateStatusChips()
                filterAndDisplay()
            }

            holder.b.chipStatusUpcoming.setOnClickListener {
                if (activeStatusChipId == holder.b.chipStatusUpcoming.id) {
                    applyStatusFilter(PurchaseStatusFilter.ALL, null)
                } else {
                    applyStatusFilter(PurchaseStatusFilter.UPCOMING, holder.b.chipStatusUpcoming.id)
                }
            }

            holder.b.chipStatusOverdue.setOnClickListener {
                if (activeStatusChipId == holder.b.chipStatusOverdue.id) {
                    applyStatusFilter(PurchaseStatusFilter.ALL, null)
                } else {
                    applyStatusFilter(PurchaseStatusFilter.OVERDUE, holder.b.chipStatusOverdue.id)
                }
            }

            updateStatusChips()

            fun listenRecent() {
                holder.b.tilSearchSaleId.error = null
                val contextLabel = "Menampilkan 300 pembelian terbaru"
                clearPurchaseListener()
                purchaseListener = db.collection("purchases")
                    .orderBy("date", Query.Direction.DESCENDING)
                    .limit(300)
                    .addSnapshotListener { snap, e ->
                        if (e != null) {
                            applyRows(
                                emptyList(),
                                e.message ?: "Gagal memuat data pembelian",
                                contextLabel = contextLabel
                            )
                            setSearchControlsEnabled(true)
                            return@addSnapshotListener
                        }
                        val docs = snap?.documents.orEmpty()
                        val mapped = docs.map { mapDocToRow(it) }
                        applyRows(
                            mapped,
                            emptyMessage = "Belum ada data pembelian.",
                            contextLabel = contextLabel
                        )
                        setSearchControlsEnabled(true)
                    }
                if (purchaseListener == null) {
                    setSearchControlsEnabled(true)
                }
            }

            fun listenInvoiceByNumber(label: String, keys: List<String>) {
                clearPurchaseListener()
                if (keys.isEmpty()) {
                    applyRows(
                        emptyList(),
                        emptyMessage = "Invoice $label tidak ditemukan",
                        contextLabel = "Hasil pencarian invoice $label"
                    )
                    setSearchControlsEnabled(true)
                    return
                }
                purchaseListener = try {
                    db.collection("purchases")
                        .whereIn("invoiceNo", keys)
                        .limit(20)
                        .addSnapshotListener { snap, e ->
                            if (e != null) {
                                applyRows(
                                    emptyList(),
                                    e.message ?: "Gagal mencari invoice",
                                    contextLabel = "Hasil pencarian invoice $label"
                                )
                                setSearchControlsEnabled(true)
                                return@addSnapshotListener
                            }
                            val docs = snap?.documents.orEmpty()
                            val mapped = docs.map { mapDocToRow(it) }
                            val info = if (mapped.isNotEmpty()) {
                                "Menampilkan ${mapped.size} invoice dengan nomor $label"
                            } else null
                            applyRows(
                                mapped,
                                emptyMessage = "Invoice $label tidak ditemukan",
                                infoMessage = info,
                                contextLabel = "Hasil pencarian invoice $label"
                            )
                            setSearchControlsEnabled(true)
                        }
                } catch (ex: Exception) {
                    applyRows(
                        emptyList(),
                        ex.message ?: "Gagal mencari invoice",
                        contextLabel = "Hasil pencarian invoice $label"
                    )
                    null
                }
                if (purchaseListener == null) {
                    setSearchControlsEnabled(true)
                }
            }

            fun searchInvoice(rawInput: String) {
                val queryString = rawInput.trim()
                if (queryString.isEmpty()) {
                    listenRecent()
                    return
                }
                holder.b.tilSearchSaleId.error = null
                val candidates = linkedSetOf(
                    queryString,
                    queryString.uppercase(localeId),
                    queryString.lowercase(localeId)
                ).filter { it.isNotBlank() }
                if (candidates.isEmpty()) {
                    listenRecent()
                    return
                }
                listenInvoiceByNumber(queryString, candidates)
            }


            listenRecent()

            // search runs realtime as user types

            holder.b.etSearchSaleId.setOnEditorActionListener { _, _, _ -> true }

            val existingWatcher = holder.b.etSearchSaleId.getTag(R.id.tag_search_watcher) as? android.text.TextWatcher
            if (existingWatcher != null) holder.b.etSearchSaleId.removeTextChangedListener(existingWatcher)
            val watcher = object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    searchInvoice(s?.toString().orEmpty())
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            }
            holder.b.etSearchSaleId.addTextChangedListener(watcher)
            holder.b.etSearchSaleId.setTag(R.id.tag_search_watcher, watcher)
        }

        private fun bindStockValuation(holder: VH) {
            val ctx = holder.itemView.context
            val localeId = Locale("in", "ID")
            val db = FirebaseFirestore.getInstance()
            val nf = NumberFormat.getInstance(localeId)
            val dfDateTime = SimpleDateFormat("dd MMM yyyy HH:mm", localeId)
            val dfDate = SimpleDateFormat("dd MMM yyyy", localeId)
            val token = ++stockToken

            holder.b.filterCard.visibility = View.VISIBLE
            holder.b.searchRow.visibility = View.GONE
            holder.b.scrollRange.visibility = View.GONE
            holder.b.chipGroupRange.visibility = View.GONE
            holder.b.scrollPurchaseStatus.visibility = View.GONE
            holder.b.chipGroupPurchaseStatus.visibility = View.GONE
            holder.b.toggleStockView.visibility = View.VISIBLE
            holder.b.tvTitle.visibility = View.GONE

            holder.b.rvSales.visibility = View.GONE
            holder.b.tvDesc.visibility = View.VISIBLE
            holder.b.tvDesc.text = "Memuat ringkasan stok..."
            holder.b.hideSummaryButton()
            holder.b.tvPendingTitle.visibility = View.GONE
            holder.b.rvPendingQueue.visibility = View.GONE
            holder.b.tvPendingStatus.visibility = View.GONE
            holder.b.tvPendingStatus.text = "Memuat antrean FIFO..."

            class MovementVH(private val binding: ItemStockMovementBinding) :
                RecyclerView.ViewHolder(binding.root) {
                fun bind(row: MovementRow) {
                    val productLabel = if (row.productName.equals(row.sku, ignoreCase = true)) {
                        row.productName
                    } else {
                        "${row.productName} (${row.sku})"
                    }
                    binding.tvProduct.text = productLabel
                    val direction = if (row.isInbound) "Masuk" else "Keluar"
                    val typeLabel = when (row.type.uppercase(localeId)) {
                        "PURCHASE" -> "Pembelian"
                        "SALE" -> "Penjualan"
                        "ADJUSTMENT" -> "Penyesuaian"
                        "TRANSFER" -> "Transfer"
                        else -> row.type.ifBlank { "-" }.replaceFirstChar {
                            if (it.isLowerCase()) it.titlecase(localeId) else it.toString()
                        }
                    }
                    binding.tvType.text = "$direction - $typeLabel"
                    val inboundColor = MaterialColors.getColor(
                        binding.tvType,
                        com.google.android.material.R.attr.colorPrimary
                    )
                    val outboundColor = MaterialColors.getColor(
                        binding.tvType,
                        com.google.android.material.R.attr.colorError
                    )
                    binding.tvType.setTextColor(if (row.isInbound) inboundColor else outboundColor)
                    binding.tvQty.text = "Qty: ${nf.format(row.qty)} unit"
                    val unitCostText = if (row.unitCost > 0) {
                        "Modal/unit: Rp ${nf.format(row.unitCost)}"
                    } else {
                        "Modal/unit: -"
                    }
                    val totalCostText = if (row.totalCost > 0) {
                        "Nilai total: Rp ${nf.format(row.totalCost)}"
                    } else ""
                    binding.tvCost.text = if (totalCostText.isNotBlank()) {
                        "$unitCostText | $totalCostText"
                    } else unitCostText
                    val note = row.note?.takeIf { it.isNotBlank() }
                    binding.tvNote.isVisible = note != null
                    if (note != null) binding.tvNote.text = "Catatan: $note"
                    val tsLabel = row.timestamp?.let { dfDateTime.format(it) } ?: "-"
                    binding.tvTimestamp.text = "Dicatat: $tsLabel"
                }
            }

            val movementRows = mutableListOf<MovementRow>()
            val movementAdapter = object : RecyclerView.Adapter<MovementVH>() {
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MovementVH {
                    val binding = ItemStockMovementBinding.inflate(
                        LayoutInflater.from(parent.context),
                        parent,
                        false
                    )
                    return MovementVH(binding)
                }

                override fun onBindViewHolder(holder: MovementVH, position: Int) {
                    holder.bind(movementRows[position])
                }

                override fun getItemCount(): Int = movementRows.size
            }
            if (holder.b.rvSales.layoutManager !is LinearLayoutManager) {
                holder.b.rvSales.layoutManager = LinearLayoutManager(ctx)
            }
            holder.b.rvSales.adapter = movementAdapter

            holder.b.tvPendingTitle.visibility = View.VISIBLE
            holder.b.tvPendingStatus.visibility = View.VISIBLE
            holder.b.tvPendingStatus.text = "Memuat antrean FIFO..."
            holder.b.rvPendingQueue.visibility = View.GONE

            class PendingVH(private val binding: ItemFifoBatchBinding) :
                RecyclerView.ViewHolder(binding.root) {
                fun bind(row: PendingRow) {
                    val productLabel = if (row.productName.equals(row.sku, ignoreCase = true)) {
                        row.productName
                    } else {
                        "${row.productName} (${row.sku})"
                    }
                    binding.tvTitle.text = when (row.type) {
                        FifoType.INVOICE -> if (row.invoiceNo.isNotBlank()) row.invoiceNo else "Invoice tertunda"
                        FifoType.HOLD -> if (row.invoiceNo.isNotBlank()) row.invoiceNo else "Stok tertahan"
                    }
                    binding.tvProduct.text = "Barang: $productLabel"
                    binding.tvSupplier.text =
                        "Supplier: ${row.supplierName?.takeIf { it.isNotBlank() } ?: "-"}"
                    binding.tvQty.text = "Qty ${nf.format(row.qty)} unit"
                    val costText = row.unitCost?.takeIf { it > 0 }
                        ?.let { "Modal/unit: Rp ${nf.format(it)}" } ?: "Modal/unit: -"
                    val saleText = row.salePrice?.takeIf { it > 0 }
                        ?.let { "Harga jual/unit: Rp ${nf.format(it)}" } ?: "Harga jual/unit: -"
                    binding.tvCost.text = costText
                    binding.tvSale.text = saleText
                    when (row.type) {
                        FifoType.INVOICE -> {
                            val schedule = row.scheduledAt?.let { dfDateTime.format(it) } ?: "-"
                            val due = row.dueDate?.let { dfDate.format(it) } ?: "-"
                            binding.tvDue.text = "Jadwal posting: $schedule | Jatuh tempo: $due"
                            val statusPretty = when (row.status?.lowercase(localeId)) {
                                "processing" -> "Sedang diproses"
                                else -> "Menunggu"
                            }
                            binding.tvStatus.text = "Status: $statusPretty"
                        }

                        FifoType.HOLD -> {
                            val received = row.receivedAt?.let { dfDateTime.format(it) } ?: "-"
                            binding.tvDue.text = "Diterima: $received"
                            binding.tvStatus.text = "Status: Menunggu stok lama habis"
                        }
                    }
                }
            }

            val pendingRows = mutableListOf<PendingRow>()
            val pendingAdapter = object : RecyclerView.Adapter<PendingVH>() {
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PendingVH {
                    val binding = ItemFifoBatchBinding.inflate(
                        LayoutInflater.from(parent.context),
                        parent,
                        false
                    )
                    return PendingVH(binding)
                }

                override fun onBindViewHolder(holder: PendingVH, position: Int) {
                    holder.bind(pendingRows[position])
                }

                override fun getItemCount(): Int = pendingRows.size
            }
            if (holder.b.rvPendingQueue.layoutManager !is LinearLayoutManager) {
                holder.b.rvPendingQueue.layoutManager = LinearLayoutManager(ctx)
            }
            holder.b.rvPendingQueue.adapter = pendingAdapter

            var currentView = StockView.SUMMARY

            fun applySections() {
                val showSummary = currentView == StockView.SUMMARY
                holder.b.rvSales.isVisible = showSummary && movementRows.isNotEmpty()
                holder.b.tvDesc.isVisible = showSummary
                holder.b.tvPendingTitle.isVisible = !showSummary
                holder.b.rvPendingQueue.isVisible = !showSummary && pendingRows.isNotEmpty()
                holder.b.tvPendingStatus.isVisible = !showSummary && pendingRows.isEmpty()
            }

            holder.b.toggleStockView.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked) return@addOnButtonCheckedListener
                currentView =
                    if (checkedId == holder.b.btnStockFifo.id) StockView.FIFO else StockView.SUMMARY
                applySections()
            }
            holder.b.toggleStockView.check(holder.b.btnStockSummary.id)

            val productMap = mutableMapOf<String, ProductSummary>()
            var detailStarted = false
            var baseSummaryRows: List<Pair<String, String>> = emptyList()

            fun loadMovements(productLookup: Map<String, ProductSummary>) {
                db.collection("inventory_movements")
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .limit(150)
                    .get()
                    .addOnSuccessListener { snap ->
                        if (!isStockRequestValid(holder, token)) return@addOnSuccessListener
                        movementRows.clear()
                        var totalIn = 0L
                        var totalOut = 0L
                        snap.documents.forEach { doc ->
                            val sku = doc.getString("sku") ?: return@forEach
                            val qtyRaw = doc.getLong("qtyDelta") ?: 0L
                            if (qtyRaw == 0L) return@forEach
                            val inbound = qtyRaw >= 0
                            val qty = abs(qtyRaw)
                            if (inbound) totalIn += qty else totalOut += qty
                            val summary = productLookup[sku]
                            val name = summary?.name ?: sku
                            val unitCost = doc.getLong("unitCost") ?: summary?.lastCost ?: 0L
                            val note = doc.getString("note") ?: doc.getString("refId")
                            val type = doc.getString("type") ?: "-"
                            val ts = doc.getTimestamp("createdAt")?.toDate()
                            val totalCost = unitCost * qty
                            movementRows += MovementRow(
                                productName = name,
                                sku = sku,
                                qty = qty,
                                unitCost = unitCost,
                                totalCost = totalCost,
                                note = note,
                                type = type,
                                isInbound = inbound,
                                timestamp = ts
                            )
                        }
                        movementAdapter.notifyDataSetChanged()
                        val baseRows = if (baseSummaryRows.size == 1 && baseSummaryRows.first().first.equals("Status", true)) {
                            emptyList()
                        } else {
                            baseSummaryRows
                        }
                        val combinedSummary = mutableListOf<Pair<String, String>>().apply {
                            addAll(baseRows)
                            if (movementRows.isNotEmpty()) {
                                add("Stok masuk (150 entri)" to "${nf.format(totalIn)} unit")
                                add("Stok keluar (150 entri)" to "${nf.format(totalOut)} unit")
                            }
                        }
                        holder.b.showSummaryButton("Ringkasan Persediaan", combinedSummary) { rows ->
                            this@SuperAdminReportFragment.showSummaryDialog("Ringkasan Persediaan", rows)
                        }
                        holder.b.tvDesc.text = if (movementRows.isEmpty()) {
                            "Belum ada pergerakan stok."
                        } else {
                            "Riwayat pergerakan stok terbaru (maks 150 entri)."
                        }
                        applySections()
                    }
                    .addOnFailureListener { e ->
                        if (!isStockRequestValid(holder, token)) return@addOnFailureListener
                        val msg = e.localizedMessage ?: "Gagal memuat pergerakan stok."
                        holder.b.tvDesc.text = msg
                        holder.b.showSummaryButton("Ringkasan Persediaan", baseSummaryRows) { rows ->
                            this@SuperAdminReportFragment.showSummaryDialog("Ringkasan Persediaan", rows)
                        }
                        applySections()
                    }
            }

            fun loadFifoQueue(productLookup: Map<String, ProductSummary>) {
                pendingRows.clear()
                pendingAdapter.notifyDataSetChanged()
                var pendingRemaining = 2
                var pendingError: String? = null

                fun complete() {
                    pendingRemaining--
                    if (pendingRemaining > 0) return
                    if (!isStockRequestValid(holder, token)) return
                    if (pendingRows.isNotEmpty()) {
                        pendingRows.sortBy { it.sortTime }
                    }
                    pendingAdapter.notifyDataSetChanged()
                    holder.b.tvPendingStatus.text = pendingError ?: "Tidak ada antrean stok"
                    applySections()
                }

                db.collection("pending_stock_receipts")
                    .whereIn("status", listOf("pending", "processing"))
                    .limit(100)
                    .get()
                    .addOnSuccessListener { snap ->
                        if (!isStockRequestValid(holder, token)) return@addOnSuccessListener
                        snap.documents.forEach { doc ->
                            val sku = doc.getString("sku") ?: return@forEach
                            val qty = doc.getLong("qty") ?: 0L
                            if (qty <= 0L) return@forEach
                            val name =
                                doc.getString("productName") ?: productLookup[sku]?.name ?: sku
                            val invoiceNo = doc.getString("invoiceNo") ?: doc.id.takeLast(6)
                            val supplier = doc.getString("supplierName")
                            val unitCost = doc.getLong("unitCost")
                            val salePrice = doc.getLong("newSalePrice")
                            val dueDate = doc.getTimestamp("dueDate")?.toDate()
                            val scheduledAt = doc.getTimestamp("scheduledAt")?.toDate()
                                ?: doc.getTimestamp("createdAt")?.toDate()
                            val status = doc.getString("status")
                            val sortTime = (scheduledAt ?: dueDate ?: Date()).time
                            pendingRows += PendingRow(
                                sku = sku,
                                productName = name,
                                invoiceNo = invoiceNo,
                                supplierName = supplier,
                                qty = qty,
                                unitCost = unitCost,
                                salePrice = salePrice,
                                dueDate = dueDate,
                                scheduledAt = scheduledAt,
                                receivedAt = null,
                                status = status,
                                type = FifoType.INVOICE,
                                sortTime = sortTime
                            )
                        }
                        complete()
                    }
                    .addOnFailureListener { e ->
                        if (!isStockRequestValid(holder, token)) return@addOnFailureListener
                        pendingError = (pendingError?.plus("\n") ?: "") +
                                (e.localizedMessage ?: "Gagal memuat antrian invoice.")
                        complete()
                    }

                db.collection("stock_batches")
                    .whereEqualTo("state", BatchState.HOLD.name)
                    .limit(100)
                    .get()
                    .addOnSuccessListener { snap ->
                        if (!isStockRequestValid(holder, token)) return@addOnSuccessListener
                        snap.documents.forEach { doc ->
                            val sku = doc.getString("sku") ?: return@forEach
                            val remaining = doc.getLong("remainingQty")
                                ?: doc.getLong("receivedQty") ?: 0L
                            if (remaining <= 0L) return@forEach
                            val name =
                                doc.getString("productName") ?: productLookup[sku]?.name ?: sku
                            val invoiceNo = doc.getString("invoiceNo")
                                ?: doc.getString("purchaseId") ?: doc.id.takeLast(6)
                            val supplier = doc.getString("supplierName")
                            val unitCost = doc.getLong("unitCost")
                            val salePrice = doc.getLong("salePrice")
                            val receivedAt = doc.getTimestamp("receivedAt")?.toDate()
                            val sortTime = (receivedAt ?: Date()).time
                            pendingRows += PendingRow(
                                sku = sku,
                                productName = name,
                                invoiceNo = invoiceNo,
                                supplierName = supplier,
                                qty = remaining,
                                unitCost = unitCost,
                                salePrice = salePrice,
                                dueDate = null,
                                scheduledAt = null,
                                receivedAt = receivedAt,
                                status = doc.getString("state"),
                                type = FifoType.HOLD,
                                sortTime = sortTime
                            )
                        }
                        complete()
                    }
                    .addOnFailureListener { e ->
                        if (!isStockRequestValid(holder, token)) return@addOnFailureListener
                        pendingError = (pendingError?.plus("\n") ?: "") +
                                (e.localizedMessage ?: "Gagal memuat stok tertahan.")
                        complete()
                    }
            }

            fun startDetailLoads() {
                if (detailStarted) return
                detailStarted = true
                loadMovements(productMap)
                loadFifoQueue(productMap)
            }

            db.collection("products")
                .whereEqualTo("trackStock", true)
                .limit(500)
                .get()
                .addOnSuccessListener { snap ->
                    if (!isStockRequestValid(holder, token)) return@addOnSuccessListener
                    var totalQty = 0L
                    var totalValue = 0L
                    snap.documents.forEach { doc ->
                        val sku = doc.getString("sku") ?: doc.id
                        val name = doc.getString("name") ?: sku
                        val stock = doc.getLong("stock") ?: 0L
                        val lastCost = doc.getLong("lastCost") ?: 0L
                        productMap[sku] = ProductSummary(sku, name, stock, lastCost)
                        totalQty += stock
                        totalValue += stock * lastCost
                    }
                    baseSummaryRows = if (productMap.isNotEmpty()) {
                        val skuCount = productMap.size
                        listOf(
                            "Total stok gudang" to "${nf.format(totalQty)} unit",
                            "Nilai persediaan" to "Rp ${nf.format(totalValue)}",
                            "SKU aktif" to nf.format(skuCount.toLong())
                        )
                    } else {
                        listOf("Status" to "Belum ada produk dengan stok aktif.")
                    }
                    holder.b.showSummaryButton("Ringkasan Persediaan", baseSummaryRows) { rows ->
                        this@SuperAdminReportFragment.showSummaryDialog("Ringkasan Persediaan", rows)
                    }
                    holder.b.tvDesc.text = "Riwayat pergerakan stok terbaru (maks 150 entri)."
                    startDetailLoads()
                    applySections()
                }
                .addOnFailureListener { e ->
                    if (!isStockRequestValid(holder, token)) return@addOnFailureListener
                    holder.b.tvDesc.text = e.localizedMessage ?: "Gagal memuat ringkasan stok."
                    holder.b.hideSummaryButton()
                    startDetailLoads()
                    applySections()
                }
        }
    }

    private fun startDialogPrinterAnimation(binding: DialogReceiptPrintStatusBinding) {
        stopDialogPrinterAnimation()
        dialogPrinterAnimator = ObjectAnimator.ofFloat(binding.ivStatus, View.ROTATION, 0f, -10f, 10f, 0f).apply {
            duration = 750
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopDialogPrinterAnimation() {
        dialogPrinterAnimator?.cancel()
        dialogPrinterAnimator = null
        printingBinding?.ivStatus?.rotation = 0f
    }
}

private fun ItemReportTabBinding.hideSummaryButton() {
    summaryRow.visibility = View.GONE
    btnSummary.visibility = View.GONE
    btnSummary.isEnabled = false
    btnSummary.setOnClickListener(null)
}

private fun ItemReportTabBinding.showSummaryButton(
    label: String,
    rows: List<Pair<String, String>>,
    onClick: (List<Pair<String, String>>) -> Unit
) {
    if (rows.isEmpty()) {
        hideSummaryButton()
        return
    }
    summaryRow.visibility = View.VISIBLE
    btnSummary.visibility = View.VISIBLE
    btnSummary.text = label
    btnSummary.setOnClickListener { onClick(rows) }
    btnSummary.isEnabled = true
}

private fun Fragment.showSummaryDialog(title: String, rows: List<Pair<String, String>>) {
    if (rows.isEmpty()) return
    val locale = Locale("in", "ID")
    val message = rows.joinToString(separator = "\n\n") { (label, value) ->
        "${label.uppercase(locale)}\n$value"
    }
    MaterialAlertDialogBuilder(requireContext())
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton("Tutup", null)
        .show()
}
