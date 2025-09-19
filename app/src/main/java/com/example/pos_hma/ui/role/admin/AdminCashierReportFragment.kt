package com.example.pos_hma.ui.role.admin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.navigation.fragment.findNavController
import com.example.pos_hma.databinding.FragmentAdminCashierReportBinding
import com.example.pos_hma.databinding.ItemSaleRowBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.android.material.datepicker.MaterialDatePicker
import android.view.inputmethod.EditorInfo
import android.view.KeyEvent
import com.google.android.material.textfield.TextInputLayout
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

class AdminCashierReportFragment : Fragment() {
    private var _b: FragmentAdminCashierReportBinding? = null
    private val b get() = _b!!

    private val db by lazy { FirebaseFirestore.getInstance() }
    private val nf by lazy { NumberFormat.getInstance(Locale("in","ID")) }
    private val df by lazy { SimpleDateFormat("dd MMM yy HH:mm", Locale("in","ID")) }
    private val dfMonth by lazy { SimpleDateFormat("MMMM yyyy", Locale("in","ID")) }
    private val dfDay by lazy { SimpleDateFormat("dd MMM yy", Locale("in","ID")) }

    private val sales = mutableListOf<SaleRow>()
    private val adapter = SalesAdapter(sales) { saleId -> onSaleClicked(saleId) }
    private var currentKetPrefix: String = "Transaksi Hari Ini"

    // For direct Bluetooth ESC/POS printing from dialog
    private val BT_REQ = 202
    private var lastReceiptToPrint: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentAdminCashierReportBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        b.rvSales.layoutManager = LinearLayoutManager(requireContext())
        b.rvSales.adapter = adapter
        // Reset filter state to default (Hari ini) without restoring previous selection
        try {
            b.chipGroupRange.isSaveEnabled = false
            b.chipToday.isSaveEnabled = false
            b.chipWeek.isSaveEnabled = false
            b.chipMonth.isSaveEnabled = false
            b.chipCustom.isSaveEnabled = false
        } catch (_: Throwable) {}

        // Ensure no chip is checked before setting listener to avoid auto-invoking pickers
        b.chipGroupRange.clearCheck()
        b.chipToday.isChecked = true
        currentKetPrefix = "Transaksi Hari Ini"
        setFilterListeners()
        loadData(rangeStartEnd(TodayRange.TODAY))

        // Search No. Nota
        b.btnSearchSaleId.setOnClickListener {
            val id = b.etSearchSaleId.text?.toString()?.trim().orEmpty()
            if (id.isEmpty()) {
                b.tilSearchSaleId.error = "Masukkan No. Nota"
            } else {
                b.tilSearchSaleId.error = null
                searchAndOpenReceipt(id)
            }
        }
        b.etSearchSaleId.setOnEditorActionListener { _, actionId, event ->
            val isSearch = actionId == EditorInfo.IME_ACTION_SEARCH || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (isSearch) {
                val id = b.etSearchSaleId.text?.toString()?.trim().orEmpty()
                if (id.isEmpty()) {
                    b.tilSearchSaleId.error = "Masukkan No. Nota"
                } else {
                    b.tilSearchSaleId.error = null
                    searchAndOpenReceipt(id)
                }
                true
            } else false
        }
    }

    private fun setFilterListeners() {
        b.chipGroupRange.setOnCheckedStateChangeListener { _, _ ->
            when {
                b.chipToday.isChecked -> { currentKetPrefix = "Transaksi Hari Ini"; loadData(rangeStartEnd(TodayRange.TODAY)) }
                b.chipWeek.isChecked  -> { currentKetPrefix = "Transaksi Minggu Ini"; loadData(rangeStartEnd(TodayRange.THIS_WEEK)) }
                b.chipMonth.isChecked -> pickMonth { start, end ->
                    currentKetPrefix = "Transaksi Pada Bulan (${dfMonth.format(start)})"
                    loadData(start to end)
                }
                b.chipCustom.isChecked -> pickDateRange { start, endExclusive ->
                    val endIncl = Date(endExclusive.time - 1L)
                    val sameDay = dfDay.format(start) == dfDay.format(endIncl)
                    val rangeLabel = if (sameDay) dfDay.format(start) else "${dfDay.format(start)} - ${dfDay.format(endIncl)}"
                    currentKetPrefix = "Transaksi Pada Tanggal (${rangeLabel})"
                    loadData(start to endExclusive)
                }
            }
        }
    }

    private enum class TodayRange { TODAY, THIS_WEEK, THIS_MONTH }

    private fun rangeStartEnd(r: TodayRange): Pair<Date, Date> {
        val cal = Calendar.getInstance()
        val end = Date(System.currentTimeMillis() + 1) // exclusive end (+1ms)
        when (r) {
            TodayRange.TODAY -> {
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            }
            TodayRange.THIS_WEEK -> {
                cal.firstDayOfWeek = Calendar.MONDAY
                cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            }
            TodayRange.THIS_MONTH -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            }
        }
        val start = cal.time
        return start to end
    }

    private fun loadData(se: Pair<Date, Date>) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val (start, end) = se

        db.collection("sales")
            .whereEqualTo("cashierId", uid)
            .whereGreaterThanOrEqualTo("createdAt", start)
            .whereLessThan("createdAt", end)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(200)
            .get()
            .addOnSuccessListener { snap ->
                sales.clear()
                var totalOmzet = 0L
                for (doc in snap.documents) {
                    val ts = doc.getTimestamp("createdAt")?.toDate()
                    val saleId = doc.id
                    val noNota = doc.getString("noNota")
                    val total = doc.getLong("total") ?: 0L
                    totalOmzet += total
                    sales += SaleRow(saleId, ts, total, noNota)
                }
                // View may be gone if user switches tabs quickly
                _b?.let { binding ->
                    binding.tvKeterangan.text = "$currentKetPrefix : ${sales.size}"
                }
                adapter.notifyDataSetChanged()
            }
    }

    private fun pickDateRange(onPicked: (Date, Date) -> Unit) {
        val picker = MaterialDatePicker.Builder.dateRangePicker().build()
        picker.addOnPositiveButtonClickListener { range ->
            val start = Date(range.first ?: return@addOnPositiveButtonClickListener)
            val endExclusive = Date((range.second ?: return@addOnPositiveButtonClickListener) + 24L*60*60*1000) // include end day
            onPicked(start, endExclusive)
        }
        picker.show(parentFragmentManager, "dateRange")
    }

    private fun pickMonth(onPicked: (Date, Date) -> Unit) {
        // Simple month picker via dialog list of last 12 months
        val cal = Calendar.getInstance()
        val labels = (0 until 12).map { i ->
            val c = (cal.clone() as Calendar).apply { add(Calendar.MONTH, -i); set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY,0); set(Calendar.MINUTE,0); set(Calendar.SECOND,0); set(Calendar.MILLISECOND,0) }
            val label = java.text.SimpleDateFormat("MMMM yyyy", Locale("in","ID")).format(c.time)
            label to c.time
        }
        val items = labels.map { it.first }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
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

    private fun onSaleClicked(saleId: String) {
        // Ambil dokumen sale lalu buka ReceiptFragment untuk cetak ulang
        db.collection("sales").document(saleId).get().addOnSuccessListener { doc ->
            if (!isAdded || !doc.exists()) return@addOnSuccessListener
            val uid = FirebaseAuth.getInstance().currentUser?.uid
            val cashierId = doc.getString("cashierId")
            if (uid != null && cashierId != null && uid != cashierId) {
                android.widget.Toast.makeText(requireContext(), "Nota tidak ditemukan", android.widget.Toast.LENGTH_SHORT).show()
                return@addOnSuccessListener
            }
            val itemsAny = (doc.get("items") as? List<Map<String, Any?>>).orEmpty()
            val items = itemsAny.map { m ->
                val name = (m["name"] as? String).orEmpty()
                val qty = (m["qty"] as? Number)?.toLong() ?: 0L
                val unitPrice = (m["unitPrice"] as? Number)?.toLong() ?: 0L
                val isService = (m["isService"] as? Boolean) ?: false
                com.example.pos_hma.ui.role.admin.print.ReceiptFormatter.Item(
                    name = name,
                    qty = qty,
                    unitPrice = unitPrice,
                    isService = isService
                )
            }

            val store = com.example.pos_hma.ui.role.admin.print.ReceiptFormatter.StoreInfo(
                name = "HAGWY MULYA AGUNG BENGKEL",
                address1 = "JL. APT PRANOTO",
                address2 = "KOTA SAMARINDA SEBERANG",
                phone = "0341-8686715"
            )
            val noNota = doc.getString("noNota") ?: saleId
            val saleInfo = com.example.pos_hma.ui.role.admin.print.ReceiptFormatter.SaleInfo(
                saleId = noNota,
                date = doc.getTimestamp("createdAt")?.toDate() ?: Date(),
                total = doc.getLong("total") ?: 0L,
                paid = doc.getLong("paid") ?: (doc.getLong("total") ?: 0L)
            )
            val svc = doc.getLong("serviceFee") ?: 0L
            val receiptForScreen = com.example.pos_hma.ui.role.admin.print.ReceiptFormatter.buildForScreen(store, saleInfo, items, serviceFee = svc)
            val receiptForPrinter = com.example.pos_hma.ui.role.admin.print.ReceiptFormatter.buildForPrinter(store, saleInfo, items, serviceFee = svc)
            // Tampilkan dialog struk rapi (bukan navigate ke halaman terpisah)
            if (!isAdded) return@addOnSuccessListener
            val ctx = requireContext()
            val tv = android.widget.TextView(ctx).apply {
                text = receiptForScreen
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
                    tv,
                    android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { gravity = android.view.Gravity.CENTER }
                )
            }
            val scroll = android.widget.ScrollView(ctx).apply {
                addView(container)
            }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                .setView(scroll)
                .setPositiveButton("Cetak") { _, _ ->
                    startDirectPrintFromDialog(receiptForPrinter)
                }
                .setNegativeButton("Tutup", null)
                .show()
        }
    }

    private fun searchAndOpenReceipt(noNota: String) {
        // Cari dokumen berdasarkan field noNota
        db.collection("sales").whereEqualTo("noNota", noNota).limit(1).get().addOnSuccessListener { snap ->
            val doc = snap.documents.firstOrNull()
            if (doc != null) onSaleClicked(doc.id)
            else android.widget.Toast.makeText(requireContext(), "No. Nota tidak ditemukan", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }

    data class SaleRow(val id: String, val date: Date?, val total: Long, val noNota: String?)

    inner class SalesAdapter(private val data: List<SaleRow>, private val onClick: (String) -> Unit) : RecyclerView.Adapter<SalesAdapter.VH>() {
        inner class VH(val vb: ItemSaleRowBinding) : RecyclerView.ViewHolder(vb.root)
        override fun onCreateViewHolder(p: ViewGroup, vt: Int): VH = VH(ItemSaleRowBinding.inflate(layoutInflater, p, false))
        override fun getItemCount() = data.size
        override fun onBindViewHolder(h: VH, i: Int) {
            val row = data[i]
            h.vb.tvSaleId.text = "No. Nota: ${row.noNota ?: row.id}"
            h.vb.tvDate.text = row.date?.let { df.format(it) } ?: "-"
            h.vb.tvTotal.text = "Total: Rp ${nf.format(row.total)}"
            h.vb.root.setOnClickListener { onClick(row.id) }
        }
    }

    // ===== Direct Bluetooth ESC/POS printing helpers (dialog) =====
    private fun startDirectPrintFromDialog(text: String) {
        lastReceiptToPrint = text
        if (android.os.Build.VERSION.SDK_INT >= 31 &&
            androidx.core.content.ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.BLUETOOTH_CONNECT)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), BT_REQ)
            return
        }

        val savedMac = com.example.pos_hma.utils.PrintersPref.getMac(requireContext())
        if (savedMac.isNullOrBlank()) {
            showBtPicker { mac ->
                com.example.pos_hma.utils.PrintersPref.saveMac(requireContext(), mac)
                doBtPrint(mac, text)
            }
        } else {
            doBtPrint(savedMac, text)
        }
    }

    private fun doBtPrint(mac: String, text: String) {
        com.example.pos_hma.print.DirectEscPosPrinter.print(
            requireContext(),
            mac,
            if (text.isBlank()) " " else text,
            onSuccess = { toast("Terkirim ke printer") },
            onError = { toast("Gagal cetak: ${it.message}") }
        )
    }

    private fun showBtPicker(onPicked: (String) -> Unit) {
        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        val bonded = adapter?.bondedDevices?.toList().orEmpty()
        if (bonded.isEmpty()) { toast("Tidak ada printer terpasang (pairing dulu)"); return }

        val labels = bonded.map { "${it.name} (${it.address})" }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Pilih Printer")
            .setItems(labels) { _, which -> onPicked(bonded[which].address) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun toast(s: String) =
        android.widget.Toast.makeText(requireContext(), s, android.widget.Toast.LENGTH_SHORT).show()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == BT_REQ && grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            lastReceiptToPrint?.let { startDirectPrintFromDialog(it) }
        }
    }
}
