package com.example.pos_hma.ui.role.admin

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.bluetooth.BluetoothAdapter
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.pos_hma.R
import com.example.pos_hma.databinding.DialogReceiptPrintStatusBinding
import com.example.pos_hma.databinding.FragmentAdminCashierReportBinding
import com.example.pos_hma.databinding.ItemSaleRowBinding
import com.example.pos_hma.print.DirectEscPosPrinter
import com.example.pos_hma.ui.role.admin.print.ReceiptFormatter
import com.example.pos_hma.utils.PrintersPref
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.color.MaterialColors
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.example.pos_hma.data.SaleStatus
import androidx.annotation.RequiresPermission
import androidx.core.view.isVisible
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
    private val filteredSales = mutableListOf<SaleRow>()
    private val adapter = SalesAdapter { position ->
        val row = filteredSales.getOrNull(position) ?: return@SalesAdapter
        onSaleClicked(row.id)
    }
    private var currentKetPrefix: String = "Semua Transaksi"

    // For direct Bluetooth ESC/POS printing from dialog
    private val BT_REQ = 202
    private var pendingReceiptToPrint: String? = null
    private var printingDialog: AlertDialog? = null
    private var printingBinding: DialogReceiptPrintStatusBinding? = null
    private var isPrinting = false
    private var dialogPrinterAnimator: ObjectAnimator? = null
    private var activeRangeChipId: Int? = null
    private val requiredBtPermissions: Array<String> by lazy {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            arrayOf(
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_SCAN
            )
        } else emptyArray()
    }

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

        // Ensure no chip is pre-selected; default shows all data
        b.chipGroupRange.clearCheck()
        b.chipGroupRange.isSingleSelection = false
        activeRangeChipId = null
        setupRangeChips()
        loadAllTransactions()

        // Search No. Nota kini realtime; tombol Cari tidak diperlukan
        b.btnSearchSaleId.visibility = View.GONE
        b.etSearchSaleId.setOnEditorActionListener { _, _, _ -> true }

        val existingWatcher = b.etSearchSaleId.getTag(R.id.tag_search_watcher) as? android.text.TextWatcher
        if (existingWatcher != null) b.etSearchSaleId.removeTextChangedListener(existingWatcher)
        val watcher = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                b.tilSearchSaleId.error = null
                applySalesFilter()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        b.etSearchSaleId.addTextChangedListener(watcher)
        b.etSearchSaleId.setTag(R.id.tag_search_watcher, watcher)
    }

    private fun setupRangeChips() {
        fun updateChipStates() {
            b.chipToday.isChecked = activeRangeChipId == b.chipToday.id
            b.chipWeek.isChecked = activeRangeChipId == b.chipWeek.id
            b.chipMonth.isChecked = activeRangeChipId == b.chipMonth.id
            b.chipCustom.isChecked = activeRangeChipId == b.chipCustom.id
        }

        fun selectToday() {
            activeRangeChipId = b.chipToday.id
            updateChipStates()
            val (start, end) = rangeStartEnd(TodayRange.TODAY)
            loadSalesWithin(start, end, "Transaksi Hari Ini")
        }

        fun selectWeek() {
            activeRangeChipId = b.chipWeek.id
            updateChipStates()
            val (start, end) = rangeStartEnd(TodayRange.THIS_WEEK)
            loadSalesWithin(start, end, "Transaksi Minggu Ini")
        }

        fun selectMonth(start: Date, end: Date, label: String) {
            activeRangeChipId = b.chipMonth.id
            updateChipStates()
            loadSalesWithin(start, end, label)
        }

        fun selectCustom(start: Date, end: Date) {
            activeRangeChipId = b.chipCustom.id
            updateChipStates()
            val endIncl = Date(end.time - 1L)
            val sameDay = dfDay.format(start) == dfDay.format(endIncl)
            val label = if (sameDay) {
                "Transaksi Pada Tanggal (${dfDay.format(start)})"
            } else {
                "Transaksi Pada Tanggal (${dfDay.format(start)} - ${dfDay.format(endIncl)})"
            }
            loadSalesWithin(start, end, label)
        }

        fun clearSelection() {
            activeRangeChipId = null
            updateChipStates()
            loadAllTransactions()
        }

        b.chipToday.setOnClickListener {
            if (activeRangeChipId == b.chipToday.id) clearSelection() else selectToday()
        }

        b.chipWeek.setOnClickListener {
            if (activeRangeChipId == b.chipWeek.id) clearSelection() else selectWeek()
        }

        b.chipMonth.setOnClickListener {
            if (activeRangeChipId == b.chipMonth.id) {
                clearSelection()
            } else {
                pickMonth { start, end, label -> selectMonth(start, end, label) }
                updateChipStates()
            }
        }

        b.chipCustom.setOnClickListener {
            if (activeRangeChipId == b.chipCustom.id) {
                clearSelection()
            } else {
                pickDateRange { start, end -> selectCustom(start, end) }
                updateChipStates()
            }
        }

        updateChipStates()
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

    private fun loadSalesWithin(start: Date?, end: Date?, label: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        var query = db.collection("sales")
            .whereEqualTo("cashierId", uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(200)
        if (start != null && end != null) {
            query = query.whereGreaterThanOrEqualTo("createdAt", start)
                .whereLessThan("createdAt", end)
        }
        query.get().addOnSuccessListener { snap ->
            sales.clear()
            for (doc in snap.documents) {
                val status = doc.getString("status") ?: SaleStatus.PAID
                if (!status.equals(SaleStatus.PAID, ignoreCase = true)) continue
                val ts = doc.getTimestamp("createdAt")?.toDate()
                val saleId = doc.id
                val noNota = doc.getString("noNota")
                val total = doc.getLong("total") ?: 0L
                sales += SaleRow(saleId, ts, total, noNota)
            }
            currentKetPrefix = label
            applySalesFilter()
        }
    }

    private fun loadAllTransactions() {
        activeRangeChipId = null
        b.chipGroupRange.clearCheck()
        b.chipToday.isChecked = false
        b.chipWeek.isChecked = false
        b.chipMonth.isChecked = false
        b.chipCustom.isChecked = false
        loadSalesWithin(null, null, "Semua Transaksi")
    }

    private fun applySalesFilter() {
        val query = b.etSearchSaleId.text?.toString()?.trim().orEmpty().lowercase(Locale("in","ID"))
        filteredSales.clear()
        if (query.isBlank()) {
            filteredSales.addAll(sales)
        } else {
            filteredSales.addAll(
                sales.filter { row ->
                    row.noNota?.lowercase(Locale("in","ID"))?.contains(query) == true ||
                            row.id.lowercase(Locale("in","ID")).contains(query)
                }
            )
        }
        _b?.let { binding ->
            binding.tvKeterangan.visibility = View.VISIBLE
            binding.tvKeterangan.text = "$currentKetPrefix : ${filteredSales.size}"
        }
        adapter.notifyDataSetChanged()
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

    private fun pickMonth(onPicked: (Date, Date, String) -> Unit) {
        // Simple month picker via dialog list of last 12 months
        val cal = Calendar.getInstance()
        val labels = (0 until 12).map { i ->
            val c = (cal.clone() as Calendar).apply { add(Calendar.MONTH, -i); set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY,0); set(Calendar.MINUTE,0); set(Calendar.SECOND,0); set(Calendar.MILLISECOND,0) }
            val label = java.text.SimpleDateFormat("MMMM yyyy", Locale("in","ID")).format(c.time)
            label to c.time
        }
        val items = labels.map { it.first }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
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
                ReceiptFormatter.Item(
                    name = name,
                    qty = qty,
                    unitPrice = unitPrice,
                    isService = isService
                )
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
                serviceDescription = svcDesc.takeIf { it.isNotEmpty() }
            )
            val receiptForPrinter = ReceiptFormatter.buildForPrinter(payload)
            // Tampilkan dialog struk rapi (bukan navigate ke halaman terpisah)
            if (!isAdded) return@addOnSuccessListener
            val ctx = requireContext()
            val tv = android.widget.TextView(ctx).apply {
                typeface = android.graphics.Typeface.MONOSPACE
                textAlignment = android.view.View.TEXT_ALIGNMENT_VIEW_START
                gravity = android.view.Gravity.START
                setLineSpacing(4f, 1.0f)
                setTextIsSelectable(true)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            }
            val screenTextFallback = ReceiptFormatter.buildForScreen(payload)
            tv.text = screenTextFallback
            tv.doOnLayout { view ->
                val textView = view as android.widget.TextView
                val columns = ReceiptFormatter.estimateColumns(textView)
                textView.text = ReceiptFormatter.buildForScreen(payload, columns)
            }
            val container = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.START
                setPadding(24, 24, 24, 24)
                addView(
                    tv,
                    android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { gravity = android.view.Gravity.START }
                )
            }
            val scroll = android.widget.ScrollView(ctx).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = true
                overScrollMode = android.view.View.OVER_SCROLL_ALWAYS
                val params = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                )
                addView(container, params)
            }
            pendingReceiptToPrint = receiptForPrinter
            MaterialAlertDialogBuilder(ctx)
                .setView(scroll)
                .setPositiveButton("Cetak") { _, _ ->
                    startDirectPrintFromDialog(receiptForPrinter)
                }
                .setNegativeButton("Tutup", null)
                .show()
        }
    }

    override fun onDestroyView() {
        stopDialogPrinterAnimation()
        printingDialog?.dismiss()
        printingDialog = null
        printingBinding = null
        pendingReceiptToPrint = null
        isPrinting = false
        _b = null
        super.onDestroyView()
    }

    data class SaleRow(val id: String, val date: Date?, val total: Long, val noNota: String?)

    inner class SalesAdapter(private val onItemClick: (Int) -> Unit) : RecyclerView.Adapter<SalesAdapter.VH>() {
        inner class VH(val vb: ItemSaleRowBinding) : RecyclerView.ViewHolder(vb.root)
        override fun onCreateViewHolder(p: ViewGroup, vt: Int): VH =
            VH(ItemSaleRowBinding.inflate(LayoutInflater.from(p.context), p, false))
        override fun getItemCount() = filteredSales.size
        override fun onBindViewHolder(h: VH, i: Int) {
            val row = filteredSales[i]
            h.vb.tvSaleId.text = "No. Nota: ${row.noNota ?: row.id}"
            h.vb.tvDate.text = row.date?.let { df.format(it) } ?: "-"
            h.vb.tvTotal.text = "Total: Rp ${nf.format(row.total)}"
            h.vb.root.setOnClickListener { onItemClick(i) }
        }
    }

    // ===== Direct Bluetooth ESC/POS printing helpers (dialog) =====
    private fun startDirectPrintFromDialog(rawText: String) {
        val text = rawText.ifBlank { "" }
        if (text.isBlank()) {
            toast("Tidak ada data nota untuk dicetak")
            return
        }
        if (isPrinting) return
        pendingReceiptToPrint = text
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && !hasAllBtPermissions()) {
            requestPermissions(requiredBtPermissions, BT_REQ)
            return
        }

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
        dialogBinding.btnRetry.setOnClickListener(null)
        dialogBinding.btnClose.isVisible = false
        dialogBinding.btnClose.setOnClickListener(null)
        dialogBinding.ivStatus.setImageResource(R.drawable.ic_printer)
        dialogBinding.ivStatus.imageTintList = ColorStateList.valueOf(
            MaterialColors.getColor(dialogBinding.ivStatus, com.google.android.material.R.attr.colorPrimary)
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
        binding.btnClose.apply {
            text = getString(R.string.print_status_close)
            isVisible = true
            setOnClickListener { printingDialog?.dismiss() }
        }
        toast("Terkirim ke printer")
        binding.root.postDelayed({ printingDialog?.dismiss() }, 1200)
    }

    private fun onPrintError(error: Throwable) {
        val binding = printingBinding ?: return
        isPrinting = false
        stopDialogPrinterAnimation()
        binding.progress.isVisible = false
        binding.ivStatus.setImageResource(R.drawable.ic_printer)
        val errorColor = MaterialColors.getColor(binding.ivStatus, com.google.android.material.R.attr.colorError)
        binding.ivStatus.imageTintList = ColorStateList.valueOf(errorColor)
        val message = error.message?.takeUnless { it.isBlank() } ?: "-"
        binding.tvStatus.text = getString(R.string.print_status_failed, message)
        binding.btnRetry.isVisible = true
        binding.btnClose.isVisible = true
        binding.btnRetry.setOnClickListener {
            val retryText = pendingReceiptToPrint
            if (retryText.isNullOrBlank()) {
                printingDialog?.dismiss()
            } else {
                printingDialog?.dismiss()
                startDirectPrintFromDialog(retryText)
            }
        }
        binding.btnClose.setOnClickListener { printingDialog?.dismiss() }
        toast("Gagal cetak: $message")
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun showBtPicker(onPicked: (String) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasAllBtPermissions()) {
            requestPermissions(requiredBtPermissions, BT_REQ)
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

    private fun ensurePrintingDialog(): DialogReceiptPrintStatusBinding {
        val current = printingBinding
        if (current != null) {
            if (printingDialog?.isShowing != true) printingDialog?.show()
            return current
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
            if (!isPrinting) {
                pendingReceiptToPrint = null
            }
        }
        dialog.show()
        printingDialog = dialog
        binding.btnClose.setOnClickListener { printingDialog?.dismiss() }
        return binding
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

    private fun toast(s: String) =
        android.widget.Toast.makeText(requireContext(), s, android.widget.Toast.LENGTH_SHORT).show()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == BT_REQ) {
            if (hasAllBtPermissions()) {
                pendingReceiptToPrint?.let { startDirectPrintFromDialog(it) }
            } else {
                toast("Izin Bluetooth diperlukan untuk mencetak")
                pendingReceiptToPrint = null
                printingDialog?.dismiss()
            }
        }
    }

    private fun hasAllBtPermissions(): Boolean {
        if (requiredBtPermissions.isEmpty()) return true
        return requiredBtPermissions.all {
            androidx.core.content.ContextCompat.checkSelfPermission(requireContext(), it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }
}
