package com.example.pos_hma.ui.role.super_admin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.pos_hma.databinding.FragmentSuperAdminReportBinding
import com.example.pos_hma.databinding.ItemReportTabBinding
import com.example.pos_hma.databinding.ItemSaleRowBinding
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.datepicker.MaterialDatePicker
import com.example.pos_hma.ui.role.admin.print.ReceiptFormatter
import android.widget.ScrollView
import android.widget.TextView
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

class SuperAdminReportFragment : Fragment() {

    private var _binding: FragmentSuperAdminReportBinding? = null
    private val binding get() = _binding!!

    private val tabs = listOf("Penjualan", "Pembelian", "Stok & Valuasi")

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

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemReportTabBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun getItemCount() = titles.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val title = titles[position]
            holder.b.tvTitle.text = title
            when (title) {
                "Penjualan" -> bindSales(holder)
                "Pembelian" -> bindPurchases(holder)
                else -> {
                    holder.b.tvDesc.visibility = View.VISIBLE
                    holder.b.tvSummary.visibility = View.GONE
                    holder.b.rvSales.visibility = View.GONE
                    holder.b.searchRow.visibility = View.GONE
                    holder.b.chipGroupRange.visibility = View.GONE
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
                    val withCost = ReceiptFormatter.buildForScreen(
                        store,
                        saleInfo,
                        items,
                        serviceFee = svc,
                        totalCost = if (goods.isNotEmpty() && totalQty > 1L) totalCost else null,
                        unitCost = unitCostSingle
                    )

                    val content = android.widget.TextView(ctx).apply {
                        text = withCost
                        typeface = android.graphics.Typeface.MONOSPACE
                        textAlignment = android.view.View.TEXT_ALIGNMENT_VIEW_START
                        gravity = android.view.Gravity.START
                        setLineSpacing(4f, 1.0f)
                        setTextIsSelectable(true)
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                    }
                    val container = android.widget.LinearLayout(ctx).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        gravity = android.view.Gravity.CENTER
                        setPadding(24, 24, 24, 24)
                        addView(
                            content,
                            android.widget.LinearLayout.LayoutParams(
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { gravity = android.view.Gravity.CENTER }
                        )
                    }
                    val scroll = android.widget.ScrollView(ctx).apply {
                        addView(container)
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

            holder.b.tvTitle.visibility = View.GONE
            holder.b.tvDesc.visibility = View.GONE
            holder.b.tvSummary.visibility = View.GONE
            holder.b.filterCard.visibility = View.VISIBLE
            holder.b.searchRow.visibility = View.VISIBLE
            holder.b.chipGroupRange.visibility = View.VISIBLE
            holder.b.rvSales.visibility = View.VISIBLE
            holder.b.tilSearchSaleId.error = null
            holder.b.tilSearchSaleId.hint = null
            holder.b.etSearchSaleId.hint = "Cari No. Invoice"
            holder.b.btnSearchSaleId.text = "Cari"
            holder.b.tvKeterangan.visibility = View.GONE

            data class Row(
                val doc: DocumentSnapshot,
                val invoiceNo: String,
                val supplierName: String,
                val productSummary: String,
                val dueDate: Date?,
                val createdAt: Date?,
                val totalCost: Long,
                val items: List<Map<String, Any?>>
            )

            val rows = mutableListOf<Row>()
            holder.b.rvSales.layoutManager = LinearLayoutManager(ctx)

            fun mapDocToRow(doc: DocumentSnapshot): Row {
                val items = (doc.get("items") as? List<Map<String, Any?>>).orEmpty()
                val firstName = (items.firstOrNull()?.get("name") as? String)?.ifBlank { "-" } ?: "-"
                val productSummary = if (items.size > 1) "$firstName (+${items.size - 1} lainnya)" else firstName
                val invoice = doc.getString("invoiceNo")?.takeIf { it.isNotBlank() } ?: doc.id
                val supplier = doc.getString("supplierName")?.takeIf { it.isNotBlank() } ?: "-"
                val due = doc.getTimestamp("dueDate")?.toDate()
                val created = doc.getTimestamp("date")?.toDate()
                val total = doc.getLong("totalCost") ?: items.fold(0L) { acc, item ->
                    val qty = (item["qty"] as? Number)?.toLong() ?: 0L
                    val cost = (item["unitCost"] as? Number)?.toLong() ?: 0L
                    acc + qty * cost
                }
                return Row(doc, invoice, supplier, productSummary, due, created, total, items)
            }

            fun showDetail(row: Row) {
                val padding = (16 * ctx.resources.displayMetrics.density).toInt()
                val detail = buildString {
                    appendLine("Nomor Invoice : ${row.invoiceNo}")
                    appendLine("Supplier      : ${row.supplierName.ifBlank { "-" }}")
                    appendLine("Tanggal       : ${row.createdAt?.let { dfDateTime.format(it) } ?: "-"}")
                    appendLine("Jatuh Tempo   : ${row.dueDate?.let { dfDate.format(it) } ?: "-"}")
                    appendLine("Nilai         : Rp ${nf.format(row.totalCost)}")
                    appendLine()
                    appendLine("Daftar Barang:")
                    if (row.items.isEmpty()) {
                        appendLine("-")
                    } else {
                        row.items.forEach { item ->
                            val name = (item["name"] as? String)?.ifBlank { "-" } ?: "-"
                            val qty = (item["qty"] as? Number)?.toLong() ?: 0L
                            val cost = (item["unitCost"] as? Number)?.toLong() ?: 0L
                            append("- ")
                            append(name)
                            append(" | Qty ")
                            append(nf.format(qty))
                            append(" @ Rp ")
                            append(nf.format(cost))
                            append(" = Rp ")
                            append(nf.format(qty * cost))
                            appendLine()
                        }
                    }
                }
                val detailView = TextView(ctx).apply {
                    text = detail.trimEnd()
                    setTextIsSelectable(true)
                    setPadding(padding, padding, padding, padding)
                }
                val scroll = ScrollView(ctx).apply { addView(detailView) }
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("Detail Pembelian ${row.invoiceNo}")
                    .setView(scroll)
                    .setPositiveButton("Tutup", null)
                    .show()
            }

            class PurchaseVH(private val b: com.example.pos_hma.databinding.ItemPurchaseRowBinding) :
                RecyclerView.ViewHolder(b.root) {
                fun bind(row: Row) {
                    b.tvInvoice.text = row.invoiceNo
                    b.tvSupplier.text = if (row.supplierName.isBlank()) "Supplier: -" else "Supplier: ${row.supplierName}"
                    b.tvProduct.text = "Nama Barang: ${row.productSummary}"
                    val dueText = row.dueDate?.let { dfDate.format(it) } ?: "-"
                    b.tvDue.text = "Jatuh Tempo: $dueText"
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

            fun updateSummary() {
                if (rows.isEmpty()) {
                    holder.b.tvSummary.visibility = View.GONE
                    return
                }
                val totalValue = rows.sumOf { it.totalCost }
                holder.b.tvSummary.visibility = View.VISIBLE
                holder.b.tvSummary.text = "Total invoice: ${rows.size} | Nilai: Rp ${nf.format(totalValue)}"
            }

            fun applyRows(
                newRows: List<Row>,
                emptyMessage: String? = null,
                infoMessage: String? = null,
                contextLabel: String? = null
            ) {
                rows.clear()
                rows.addAll(newRows.sortedByDescending { it.createdAt?.time ?: Long.MIN_VALUE })
                adapter.notifyDataSetChanged()

                if (contextLabel.isNullOrBlank()) {
                    holder.b.tvKeterangan.visibility = View.GONE
                } else {
                    holder.b.tvKeterangan.visibility = View.VISIBLE
                    holder.b.tvKeterangan.text = contextLabel
                }

                if (rows.isEmpty()) {
                    holder.b.tvSummary.visibility = View.GONE
                    holder.b.tvDesc.visibility = View.VISIBLE
                    holder.b.tvDesc.text = emptyMessage ?: "Tidak ada data pembelian pada rentang ini."
                } else {
                    if (infoMessage.isNullOrBlank()) {
                        holder.b.tvDesc.visibility = View.GONE
                    } else {
                        holder.b.tvDesc.visibility = View.VISIBLE
                        holder.b.tvDesc.text = infoMessage
                    }
                    updateSummary()
                }
            }

            fun buildRangeLabel(start: Date, endExclusive: Date): String {
                val inclusiveEnd = Date(endExclusive.time - 1L)
                val isSameDay = dfDate.format(start) == dfDate.format(inclusiveEnd)
                return if (isSameDay) {
                    "Data pembelian tanggal ${dfDate.format(start)}"
                } else {
                    "Data pembelian ${dfDate.format(start)} - ${dfDate.format(inclusiveEnd)}"
                }
            }

            fun rangeStart(date: Date): Date {
                val cal = Calendar.getInstance().apply { time = date }
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                return cal.time
            }

            fun rangeEndExclusive(date: Date): Date {
                val cal = Calendar.getInstance().apply { time = date }
                cal.set(Calendar.HOUR_OF_DAY, 23)
                cal.set(Calendar.MINUTE, 59)
                cal.set(Calendar.SECOND, 59)
                cal.set(Calendar.MILLISECOND, 999)
                cal.add(Calendar.MILLISECOND, 1)
                return cal.time
            }

            fun load(start: Date, endExclusive: Date) {
                holder.b.tilSearchSaleId.error = null
                val contextLabel = buildRangeLabel(start, endExclusive)
                db.collection("purchases")
                    .orderBy("date", Query.Direction.DESCENDING)
                    .whereGreaterThanOrEqualTo("date", com.google.firebase.Timestamp(start))
                    .whereLessThan("date", com.google.firebase.Timestamp(endExclusive))
                    .limit(300)
                    .get()
                    .addOnSuccessListener { snap ->
                        val data = snap.documents.map { mapDocToRow(it) }
                        applyRows(
                            data,
                            emptyMessage = "Tidak ada data pembelian pada rentang ini.",
                            contextLabel = contextLabel
                        )
                    }
                    .addOnFailureListener { e ->
                        applyRows(
                            emptyList(),
                            e.message ?: "Gagal memuat data pembelian",
                            contextLabel = contextLabel
                        )
                    }
            }

            fun searchInvoice(rawInput: String) {
                val queryString = rawInput.trim()
                if (queryString.isEmpty()) {
                    holder.b.tilSearchSaleId.error = "Masukkan No. Invoice"
                    return
                }
                holder.b.tilSearchSaleId.error = null

                holder.b.btnSearchSaleId.isEnabled = false
                holder.b.etSearchSaleId.isEnabled = false

                val candidates = linkedSetOf(
                    queryString,
                    queryString.uppercase(localeId),
                    queryString.lowercase(localeId)
                ).filter { it.isNotBlank() }

                fun resetControls() {
                    holder.b.btnSearchSaleId.isEnabled = true
                    holder.b.etSearchSaleId.isEnabled = true
                }

                fun handleResult(label: String, mapped: List<Row>) {
                    val info = if (mapped.isNotEmpty()) {
                        "Menampilkan ${mapped.size} invoice dengan nomor $label"
                    } else null
                    applyRows(
                        mapped,
                        emptyMessage = "Invoice $label tidak ditemukan",
                        infoMessage = info,
                        contextLabel = "Hasil pencarian invoice $label"
                    )
                    resetControls()
                }

                fun handleError(message: String) {
                    applyRows(
                        emptyList(),
                        message,
                        contextLabel = "Hasil pencarian invoice $queryString"
                    )
                    resetControls()
                }

                fun searchAt(index: Int) {
                    if (index >= candidates.size) {
                        handleResult(queryString, emptyList())
                        return
                    }
                    val target = candidates[index]
                    db.collection("purchases")
                        .whereEqualTo("invoiceNo", target)
                        .limit(5)
                        .get()
                        .addOnSuccessListener { snap ->
                            if (snap.isEmpty) {
                                searchAt(index + 1)
                            } else {
                                handleResult(target, snap.documents.map { mapDocToRow(it) })
                            }
                        }
                        .addOnFailureListener { e -> handleError(e.message ?: "Gagal mencari invoice") }
                }

                searchAt(0)
            }

            var currentStart = rangeStart(Date())
            var currentEndExclusive = rangeEndExclusive(Date())
            load(currentStart, currentEndExclusive)

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

            holder.b.chipToday.setOnClickListener {
                currentStart = rangeStart(Date())
                currentEndExclusive = rangeEndExclusive(Date())
                load(currentStart, currentEndExclusive)
            }
            holder.b.chipWeek.setOnClickListener {
                val cal = Calendar.getInstance()
                cal.time = Date()
                cal.add(Calendar.DAY_OF_YEAR, -6)
                currentStart = rangeStart(cal.time)
                currentEndExclusive = rangeEndExclusive(Date())
                load(currentStart, currentEndExclusive)
            }
            holder.b.chipMonth.setOnClickListener {
                val cal = Calendar.getInstance()
                cal.time = Date()
                cal.set(Calendar.DAY_OF_MONTH, 1)
                currentStart = rangeStart(cal.time)
                currentEndExclusive = rangeEndExclusive(Date())
                load(currentStart, currentEndExclusive)
            }
            holder.b.chipCustom.setOnClickListener {
                val picker = MaterialDatePicker.Builder.dateRangePicker().build()
                picker.addOnPositiveButtonClickListener { range ->
                    val start = Date(range.first ?: System.currentTimeMillis())
                    val end = Date(range.second ?: System.currentTimeMillis())
                    currentStart = rangeStart(start)
                    currentEndExclusive = rangeEndExclusive(end)
                    load(currentStart, currentEndExclusive)
                }
                (holder.itemView.context as? FragmentActivity)?.let {
                    picker.show(it.supportFragmentManager, "pick_purchase_range")
                }
            }
        }





    }
}
