package com.example.pos_hma.ui.role.super_admin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.content.res.ColorStateList
import android.view.ViewGroup
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.util.Log
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.annotation.AttrRes
import com.example.pos_hma.databinding.FragmentSuperAdminReportBinding
import com.example.pos_hma.databinding.DialogPurchaseDetailBinding
import com.example.pos_hma.databinding.ItemPurchaseDetailItemBinding
import com.example.pos_hma.databinding.ItemReportTabBinding
import com.example.pos_hma.databinding.ItemSaleRowBinding
import com.google.android.material.color.MaterialColors
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.datepicker.MaterialDatePicker
import com.example.pos_hma.ui.role.admin.print.ReceiptFormatter
import androidx.core.view.doOnLayout
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.Timestamp
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

class SuperAdminReportFragment : Fragment() {

    private var _binding: FragmentSuperAdminReportBinding? = null
    private val binding get() = _binding!!

    private val tabs = listOf("Penjualan", "Pembelian", "Stok & Valuasi")

    private enum class PurchaseStatusFilter { ALL, UPCOMING, OVERDUE }


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentSuperAdminReportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        binding.pager.adapter = ReportPagerAdapter(tabs)
        TabLayoutMediator(binding.tabs, binding.pager) { tab, pos ->
            tab.text = tabs[pos]
        }.attach()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private class ReportPagerAdapter(private val titles: List<String>) :
        RecyclerView.Adapter<ReportPagerAdapter.VH>() {

        inner class VH(val b: ItemReportTabBinding) : RecyclerView.ViewHolder(b.root)


        private var purchaseListener: ListenerRegistration? = null
        private var purchaseHolder: VH? = null

        private fun clearPurchaseListener() {
            purchaseListener?.remove()
            purchaseListener = null
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
            super.onViewRecycled(holder)
        }

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
                    holder.b.tvDesc.visibility = View.VISIBLE
                    holder.b.tvSummary.visibility = View.GONE
                    holder.b.rvSales.visibility = View.GONE
                    holder.b.searchRow.visibility = View.GONE
                    holder.b.chipGroupRange.visibility = View.GONE
                    holder.b.chipGroupPurchaseStatus.visibility = View.GONE
                    holder.b.filterCard.visibility = View.GONE
                    holder.b.tvDesc.text = "Nilai persediaan, pergerakan stok, valuasi FIFO. (coming soon)"
                }
            }
        }

        private fun bindSales(holder: VH) {
            val ctx = holder.itemView.context
            val db = FirebaseFirestore.getInstance()
            val nf = NumberFormat.getInstance(Locale("in","ID"))
            val df = SimpleDateFormat("dd MMM yy HH:mm", Locale("in","ID"))
            val dfMonth = SimpleDateFormat("MMMM yyyy", Locale("in","ID"))
            val dfDay = SimpleDateFormat("dd MMM yy", Locale("in","ID"))

            holder.b.tvDesc.visibility = View.GONE
            holder.b.tvTitle.visibility = View.GONE
            holder.b.tvSummary.visibility = View.GONE
            holder.b.rvSales.visibility = View.VISIBLE
            holder.b.chipGroupRange.visibility = View.VISIBLE
            holder.b.searchRow.visibility = View.VISIBLE
            holder.b.filterCard.visibility = View.VISIBLE
            holder.b.chipGroupPurchaseStatus.visibility = View.GONE

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
                    val payload = ReceiptFormatter.Payload(
                        store = store,
                        sale = saleInfo,
                        items = items,
                        serviceFee = svc,
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
                        .setPositiveButton("Tutup", null)
                        .show()
                }
            }

            // Search No. Nota
            holder.b.btnSearchSaleId.setOnClickListener {
                val id = holder.b.etSearchSaleId.text?.toString()?.trim().orEmpty()
                if (id.isEmpty()) {
                    holder.b.tilSearchSaleId.error = "Masukkan No. Nota"
                } else {
                    holder.b.tilSearchSaleId.error = null
                    db.collection("sales").whereEqualTo("noNota", id).limit(1).get().addOnSuccessListener { snap ->
                        val d = snap.documents.firstOrNull()
                        if (d != null) fetchAndShow(d.id) else android.widget.Toast.makeText(ctx, "No. Nota tidak ditemukan", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            holder.b.etSearchSaleId.setOnEditorActionListener { _, actionId, event ->
                val isSearch = actionId == EditorInfo.IME_ACTION_SEARCH || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                if (isSearch) {
                    val id = holder.b.etSearchSaleId.text?.toString()?.trim().orEmpty()
                    if (id.isEmpty()) {
                        holder.b.tilSearchSaleId.error = "Masukkan No. Nota"
                    } else {
                        holder.b.tilSearchSaleId.error = null
                        db.collection("sales").whereEqualTo("noNota", id).limit(1).get().addOnSuccessListener { snap ->
                            val d = snap.documents.firstOrNull()
                            if (d != null) fetchAndShow(d.id) else android.widget.Toast.makeText(ctx, "No. Nota tidak ditemukan", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    true
                } else false
            }

            holder.b.rvSales.layoutManager = LinearLayoutManager(ctx)
            val data = mutableListOf<SaleRow>()

            val adapter = object : RecyclerView.Adapter<SaleVH>() {
                override fun onCreateViewHolder(p: ViewGroup, vt: Int): SaleVH =
                    SaleVH(ItemSaleRowBinding.inflate(LayoutInflater.from(ctx), p, false))
                override fun getItemCount() = data.size
                override fun onBindViewHolder(h: SaleVH, i: Int) {
                    val row = data[i]
                    h.v.tvSaleId.text = "No. Nota: ${row.noNota ?: row.id}"
                    h.v.tvDate.text = row.date?.let { df.format(it) } ?: "-"
                    val info = if (row.hasGoods) {
                        "Total: Rp ${nf.format(row.total)}"
                    } else {
                        "Transaksi Jasa - Total: Rp ${nf.format(row.total)}"
                    }
                    h.v.tvTotal.text = info
                    h.v.root.setOnClickListener { fetchAndShow(row.id) }
                }
            }
            holder.b.rvSales.adapter = adapter

            fun computeRange(which: Int): Pair<Date, Date> {
                val cal = Calendar.getInstance()
                val end = Date(System.currentTimeMillis() + 1) // exclusive end
                when (which) {
                    holder.b.chipWeek.id -> {
                        cal.firstDayOfWeek = Calendar.MONDAY
                        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                    }
                    holder.b.chipMonth.id -> cal.set(Calendar.DAY_OF_MONTH, 1)
                    else -> { /* today */ }
                }
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                return cal.time to end
            }

            fun load(rangeChipId: Int) {
                val (start, end) = computeRange(rangeChipId)
                db.collection("sales")
                    .whereGreaterThanOrEqualTo("createdAt", start)
                    .whereLessThan("createdAt", end)
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .limit(200)
                    .get()
                    .addOnSuccessListener { snap ->
                        data.clear()
                        var sumOmzet = 0L
                        var sumCost = 0L
                        for (doc in snap.documents) {
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
                        data += SaleRow(doc.id, ts, total, cost, unitCostAvg, hasGoods, noNota)
                        sumOmzet += total
                        sumCost += cost
                    }
                        holder.b.tvKeterangan.visibility = View.VISIBLE
                        holder.b.tvKeterangan.text = when (rangeChipId) {
                            holder.b.chipWeek.id -> "Transaksi Minggu Ini : ${data.size}"
                            holder.b.chipMonth.id -> "Transaksi Pada Bulan (${dfMonth.format(start)}) : ${data.size}"
                            else -> "Transaksi Hari Ini : ${data.size}"
                        }
                        adapter.notifyDataSetChanged()
                    }
            }

            fun manualLoad(start: Date, end: Date) {
                db.collection("sales")
                    .whereGreaterThanOrEqualTo("createdAt", start)
                    .whereLessThan("createdAt", end)
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .limit(200)
                    .get()
                    .addOnSuccessListener { snap ->
                        data.clear()
                        var sumOmzet = 0L
                        var sumCost = 0L
                        for (doc in snap.documents) {
                            val ts = doc.getTimestamp("createdAt")?.toDate()
                            val total = doc.getLong("total") ?: 0L
                            val items = (doc.get("items") as? List<Map<String, Any?>>).orEmpty()
                        val noNota = doc.getString("noNota")
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
                        data += SaleRow(doc.id, ts, total, cost, unitCostAvg, hasGoods, noNota)
                        sumOmzet += total
                        sumCost += cost
                    }
                        holder.b.tvKeterangan.visibility = View.VISIBLE
                        val endIncl = Date(end.time - 1L)
                        val sameDay = dfDay.format(start) == dfDay.format(endIncl)
                        val rangeLabel = if (sameDay) dfDay.format(start) else "${dfDay.format(start)} - ${dfDay.format(endIncl)}"
                        holder.b.tvKeterangan.text = "Transaksi Pada Tanggal (${rangeLabel}) : ${data.size}"
                        adapter.notifyDataSetChanged()
                    }
            }

            holder.b.chipToday.isChecked = true
            holder.b.chipGroupRange.setOnCheckedStateChangeListener { _, checkedIds ->
                val id = checkedIds.firstOrNull() ?: holder.b.chipToday.id
                when (id) {
                    holder.b.chipToday.id, holder.b.chipWeek.id -> load(id)
                    holder.b.chipMonth.id -> pickMonth(holder) { start, end -> manualLoad(start, end) }
                    holder.b.chipCustom.id -> pickDateRange(holder) { start, end -> manualLoad(start, end) }
                }
            }
            load(holder.b.chipToday.id)
        }

        data class SaleRow(
            val id: String,
            val date: Date?,
            val total: Long,
            val cost: Long,           // total biaya modal (untuk laba)
            val unitCost: Long,       // biaya modal per unit (rata-rata)
            val hasGoods: Boolean,
            val noNota: String?
        )
        inner class SaleVH(val v: ItemSaleRowBinding) : RecyclerView.ViewHolder(v.root)

        private fun pickDateRange(holder: VH, onPicked: (Date, Date) -> Unit) {
            val picker = MaterialDatePicker.Builder.dateRangePicker().build()
            picker.addOnPositiveButtonClickListener { range ->
                val start = Date(range.first ?: return@addOnPositiveButtonClickListener)
                val endExclusive = Date((range.second ?: return@addOnPositiveButtonClickListener) + 24L*60*60*1000)
                onPicked(start, endExclusive)
            }
            val fm = (holder.itemView.context as FragmentActivity).supportFragmentManager
            picker.show(fm, "dateRangeSuper")
        }

        private fun pickMonth(holder: VH, onPicked: (Date, Date) -> Unit) {
            val cal = Calendar.getInstance()
            val labels = (0 until 12).map { i ->
                val c = (cal.clone() as Calendar).apply {
                    add(Calendar.MONTH, -i)
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                val label = SimpleDateFormat("MMMM yyyy", Locale("in","ID")).format(c.time)
                label to c.time
            }
            val items = labels.map { it.first }.toTypedArray()
            MaterialAlertDialogBuilder(holder.itemView.context)
                .setTitle("Pilih Bulan")
                .setItems(items) { _, which ->
                    val start = labels[which].second
                    val c2 = Calendar.getInstance().apply { time = start; add(Calendar.MONTH, 1) }
                    val endExclusive = c2.time
                    onPicked(start, endExclusive)
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
            holder.b.tvSummary.visibility = View.GONE
            holder.b.filterCard.visibility = View.VISIBLE
            holder.b.searchRow.visibility = View.VISIBLE
            holder.b.chipGroupRange.visibility = View.GONE
            holder.b.chipGroupPurchaseStatus.visibility = View.VISIBLE
            holder.b.rvSales.visibility = View.VISIBLE
            holder.b.tilSearchSaleId.error = null
            holder.b.tilSearchSaleId.hint = null
            holder.b.etSearchSaleId.hint = "Cari No. Invoice"
            holder.b.btnSearchSaleId.text = "Cari"
            holder.b.tvKeterangan.visibility = View.GONE

            data class StatusInfo(
                val title: String,
                val detail: String,
                @AttrRes val badgeColorAttr: Int,
                @AttrRes val badgeTextColorAttr: Int,
                @AttrRes val strokeColorAttr: Int,
                val priority: Int
            )

            data class Row(
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

            val rows = mutableListOf<Row>()
            var masterRows: List<Row> = emptyList()
            var lastEmptyMessage: String? = null
            var lastInfoMessage: String? = null
            var lastContextLabel: String? = null
            var statusFilter = PurchaseStatusFilter.ALL
            val ensuredPendingIds = mutableSetOf<String>()
            holder.b.rvSales.layoutManager = LinearLayoutManager(ctx)


            fun setSearchControlsEnabled(enabled: Boolean) {
                holder.b.btnSearchSaleId.isEnabled = enabled
                holder.b.etSearchSaleId.isEnabled = enabled
            }

            fun computeStatus(due: Date?): StatusInfo {
                val today = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                if (due == null) {
                    return StatusInfo(
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
                val diffDays = ((dueCal.timeInMillis - today.timeInMillis) / (24L * 60 * 60 * 1000L)).toInt()
                return when {
                    diffDays < 0 -> StatusInfo(
                        title = "Sudah jatuh tempo",
                        detail = "Terlambat ${-diffDays} hari",
                        badgeColorAttr = com.google.android.material.R.attr.colorError,
                        badgeTextColorAttr = com.google.android.material.R.attr.colorOnError,
                        strokeColorAttr = com.google.android.material.R.attr.colorError,
                        priority = 0
                    )
                    diffDays == 0 -> StatusInfo(
                        title = "Sudah jatuh tempo",
                        detail = "Jatuh tempo hari ini",
                        badgeColorAttr = com.google.android.material.R.attr.colorError,
                        badgeTextColorAttr = com.google.android.material.R.attr.colorOnError,
                        strokeColorAttr = com.google.android.material.R.attr.colorError,
                        priority = 0
                    )
                    else -> StatusInfo(
                        title = "Menunggu jatuh tempo",
                        detail = "Sisa $diffDays hari",
                        badgeColorAttr = com.google.android.material.R.attr.colorSecondary,
                        badgeTextColorAttr = com.google.android.material.R.attr.colorOnSecondary,
                        strokeColorAttr = com.google.android.material.R.attr.colorSecondary,
                        priority = 1
                    )
                }
            }

            fun mapDocToRow(doc: DocumentSnapshot): Row {
                val items = (doc.get("items") as? List<Map<String, Any?>>).orEmpty()
                val firstName = (items.firstOrNull()?.get("name") as? String)?.ifBlank { "-" } ?: "-"
                val productSummary = if (items.size > 1) "$firstName (+${items.size - 1} lainnya)" else firstName
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
                return Row(
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

            fun showDetail(row: Row) {
                val detailBinding = DialogPurchaseDetailBinding.inflate(LayoutInflater.from(ctx))
                detailBinding.tvDialogInvoice.text = row.invoiceNo
                detailBinding.tvDialogSupplier.text = "Supplier: ${row.supplierName.ifBlank { "-" }}"
                detailBinding.tvDialogDate.text = "Pembelian pada: ${row.createdAt?.let { dfDateTime.format(it) } ?: "-"}"
                detailBinding.tvDialogDue.text = "Jatuh Tempo: ${row.dueDate?.let { dfDate.format(it) } ?: "-"}"
                detailBinding.tvDialogDueTime.text = "Jam Jatuh Tempo: ${row.scheduledAt?.let { dfTime.format(it) } ?: "-"}"
                detailBinding.tvDialogTotal.text = "Total: Rp ${nf.format(row.totalCost)}"
                detailBinding.tvDialogStatusTitle.text = row.statusTitle
                detailBinding.tvDialogStatusDetail.text = row.statusDetail

                val badgeColor = MaterialColors.getColor(detailBinding.tvDialogStatusTitle, row.badgeColorAttr)
                val badgeTextColor = MaterialColors.getColor(detailBinding.tvDialogStatusTitle, row.badgeTextColorAttr)
                detailBinding.tvDialogStatusTitle.backgroundTintList = ColorStateList.valueOf(badgeColor)
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
                        val itemBinding = ItemPurchaseDetailItemBinding.inflate(LayoutInflater.from(ctx), container, false)
                        val name = (item["name"] as? String)?.ifBlank { "-" } ?: "-"
                        val qty = (item["qty"] as? Number)?.toLong() ?: 0L
                        val cost = (item["unitCost"] as? Number)?.toLong() ?: 0L
                        itemBinding.tvItemName.text = "${index + 1}. $name"
                        itemBinding.tvItemMeta.text = "Qty ${nf.format(qty)} x Rp ${nf.format(cost)} = Rp ${nf.format(qty * cost)}"
                        itemBinding.divider.visibility = if (index == row.items.lastIndex) View.GONE else View.VISIBLE
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
                fun bind(row: Row) {
                    b.tvInvoice.text = row.invoiceNo
                    b.tvStatusTitle.text = row.statusTitle
                    b.tvStatusDetail.text = row.statusDetail

                    val badgeColor = MaterialColors.getColor(b.tvStatusTitle, row.badgeColorAttr)
                    val badgeTextColor = MaterialColors.getColor(b.tvStatusTitle, row.badgeTextColorAttr)
                    b.tvStatusTitle.backgroundTintList = ColorStateList.valueOf(badgeColor)
                    b.tvStatusTitle.setTextColor(badgeTextColor)
                    b.tvStatusDetail.setTextColor(badgeColor)

                    val strokeColor = MaterialColors.getColor(b.root, row.strokeColorAttr)
                    val strokeWidth = (b.root.resources.displayMetrics.density * 2f).toInt().coerceAtLeast(2)
                    b.root.strokeWidth = strokeWidth
                    b.root.setStrokeColor(strokeColor)

                    b.tvSupplier.text = if (row.supplierName.isBlank()) "Supplier: -" else "Supplier: ${row.supplierName}"
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

                override fun onBindViewHolder(holder: PurchaseVH, position: Int) = holder.bind(rows[position])

                override fun getItemCount(): Int = rows.size
            }
            holder.b.rvSales.adapter = adapter

            fun updateSummary(current: List<Row>) {
                if (current.isEmpty()) {
                    holder.b.tvSummary.visibility = View.GONE
                    return
                }
                val totalValue = current.sumOf { it.totalCost }
                val overdueCount = current.count { it.statusPriority == 0 }
                val upcomingCount = current.count { it.statusPriority == 1 }
                val noDueCount = current.size - overdueCount - upcomingCount
                val summaryParts = mutableListOf(
                    "Total invoice: ${current.size}",
                    "Nilai: Rp ${nf.format(totalValue)}"
                )
                summaryParts += "Sudah JT: $overdueCount"
                summaryParts += "Menunggu: $upcomingCount"
                if (noDueCount > 0) summaryParts += "Tanpa tempo: $noDueCount"
                holder.b.tvSummary.visibility = View.VISIBLE
                holder.b.tvSummary.text = summaryParts.joinToString(" | ")
            }

            fun ensureDueAutoPosting(source: List<Row>) {
                if (source.isEmpty()) return
                val pendingAuto = source.count { !it.stockPosted && !it.pendingStockId.isNullOrBlank() }
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
                    holder.b.tvSummary.visibility = View.GONE
                    holder.b.tvDesc.visibility = View.VISIBLE
                    val emptyMsg = when {
                        masterRows.isEmpty() -> lastEmptyMessage ?: "Tidak ada data pembelian pada rentang ini."
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
                newRows: List<Row>,
                emptyMessage: String? = null,
                infoMessage: String? = null,
                contextLabel: String? = null
            ) {
                masterRows = newRows.sortedWith(compareBy<Row> { statusSortKey(it.statusPriority) }
                    .thenBy { it.dueDate ?: Date(Long.MAX_VALUE) }
                    .thenByDescending { it.createdAt?.time ?: Long.MIN_VALUE })
                lastEmptyMessage = emptyMessage
                lastInfoMessage = infoMessage
                lastContextLabel = contextLabel
                ensureDueAutoPosting(masterRows)
                filterAndDisplay()
            }

            holder.b.chipGroupPurchaseStatus.setOnCheckedStateChangeListener { _, checkedIds ->
                val selected = checkedIds.firstOrNull()
                statusFilter = when (selected) {
                    holder.b.chipStatusUpcoming.id -> PurchaseStatusFilter.UPCOMING
                    holder.b.chipStatusOverdue.id -> PurchaseStatusFilter.OVERDUE
                    else -> PurchaseStatusFilter.ALL
                }
                filterAndDisplay()
            }
            holder.b.chipGroupPurchaseStatus.check(holder.b.chipStatusAll.id)

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
                setSearchControlsEnabled(false)
                listenInvoiceByNumber(queryString, candidates)
            }


            listenRecent()

            holder.b.btnSearchSaleId.setOnClickListener {
                searchInvoice(holder.b.etSearchSaleId.text?.toString().orEmpty())
            }
            holder.b.etSearchSaleId.setOnEditorActionListener { _, actionId, event ->
                val isSearch = actionId == EditorInfo.IME_ACTION_SEARCH ||
                        (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                if (isSearch) {
                    searchInvoice(holder.b.etSearchSaleId.text?.toString().orEmpty())
                    true
                } else {
                    false
                }
            }
        }





    }
}

