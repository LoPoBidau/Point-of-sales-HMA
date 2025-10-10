package com.example.pos_hma.ui.role.admin

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.print.PrintManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updatePadding
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.pos_hma.R
import com.example.pos_hma.databinding.FragmentAdminCashierReceiptBinding
import com.example.pos_hma.print.DirectEscPosPrinter
import com.example.pos_hma.ui.role.admin.print.CenteredReceiptPrintAdapter
import com.example.pos_hma.ui.role.admin.print.ReceiptFormatter
import com.example.pos_hma.utils.PrintersPref
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class AdminCashierReceiptFragment : Fragment() {



    private var _b: FragmentAdminCashierReceiptBinding? = null
    private val b get() = _b!!

    private val btRequestCode = 201
    private val requiredBtPermissions: Array<String> by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else emptyArray()
    }
    private var currentSaleId: String = "-"
    private var receiptPrinterPayload: String = ""
    private var receiptPayload: ReceiptFormatter.Payload? = null
    private var lastRenderedColumns: Int = -1
    private val printingHandler = Handler(Looper.getMainLooper())
    private var printingRunnable: Runnable? = null
    private var printerAnimator: ObjectAnimator? = null
    private var isPrinting = false
    private var pendingPermissionAction: (() -> Unit)? = null
    private var printingMessages: Array<String> = emptyArray()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentAdminCashierReceiptBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // pad top based on status bar height
        val baseTopPadding = b.root.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = baseTopPadding + systemBars.top)
            insets
        }
        ViewCompat.requestApplyInsets(b.root)

        // Disable back button while on receipt screen
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { /* block back */ }
            }
        )

        currentSaleId = arguments?.getString("saleId") ?: "-"
        val textUi = arguments?.getCharSequence("receiptScreen") ?: ""
        receiptPrinterPayload = arguments?.getString("receiptPrinter") ?: textUi.toString()
        receiptPayload = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arguments?.getSerializable("receiptPayload", ReceiptFormatter.Payload::class.java)
        } else {
            @Suppress("DEPRECATION")
            arguments?.getSerializable("receiptPayload") as? ReceiptFormatter.Payload
        }
        receiptPayload?.let { payload ->
            receiptPrinterPayload = ReceiptFormatter.buildForPrinter(payload)
        }

        if (receiptPayload != null) {
            b.tvReceipt.text = textUi
            b.tvReceipt.doOnLayout { view ->
                val payload = receiptPayload ?: return@doOnLayout
                val tv = view as android.widget.TextView
                val columns = ReceiptFormatter.estimateColumns(tv)
                if (columns > 0 && (columns != lastRenderedColumns || tv.text.isNullOrBlank() || tv.text == textUi)) {
                    lastRenderedColumns = columns
                    tv.text = ReceiptFormatter.buildForScreen(payload, columns)
                }
            }
            b.tvReceipt.addOnLayoutChangeListener { view, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left == oldRight - oldLeft) return@addOnLayoutChangeListener
                val payload = receiptPayload ?: return@addOnLayoutChangeListener
                val tv = view as android.widget.TextView
                val columns = ReceiptFormatter.estimateColumns(tv)
                if (columns > 0 && columns != lastRenderedColumns) {
                    lastRenderedColumns = columns
                    tv.text = ReceiptFormatter.buildForScreen(payload, columns)
                }
            }
        } else {
            b.tvReceipt.text = textUi
        }

        printingMessages = arrayOf(
            getString(R.string.receipt_status_printing),
            getString(R.string.receipt_status_printing) + ".",
            getString(R.string.receipt_status_printing) + "..",
            getString(R.string.receipt_status_printing) + "..."
        )
        setIdleState()

        b.btnSaveAndPrint.setOnClickListener {
            if (isPrinting) return@setOnClickListener
            val textForPrinter = if (receiptPrinterPayload.isNotBlank()) {
                receiptPrinterPayload
            } else {
                b.tvReceipt.text?.toString().orEmpty()
            }
            if (textForPrinter.isBlank()) {
                toast("Tidak ada data nota untuk dicetak")
                return@setOnClickListener
            }
            ensureBtPermissions {
                beginPrintWorkflow(textForPrinter)
            }
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

    @SuppressLint("MissingPermission")
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
        isPrinting = true
        pendingPermissionAction = null
        b.btnSaveAndPrint.isEnabled = false
        b.printingOverlay.visibility = View.VISIBLE
        b.ivPrinting.setImageResource(R.drawable.ic_printer)
        ImageViewCompat.setImageTintList(
            b.ivPrinting,
            ColorStateList.valueOf(ContextCompat.getColor(requireContext(), android.R.color.white))
        )
        b.tvPrintingStatus.text = printingMessages.firstOrNull() ?: getString(R.string.receipt_status_printing)
        startPrinterAnimation()
        startStatusLoop()
        DirectEscPosPrinter.print(
            requireContext(),
            mac,
            if (text.isBlank()) " " else text,
            onSuccess = { onPrintSuccess() },
            onError = { onPrintError(it, text) }
        )
    }

    private fun onPrintSuccess() {
        stopStatusLoop()
        stopPrinterAnimation()
        b.printingOverlay.visibility = View.VISIBLE
        b.ivPrinting.setImageResource(R.drawable.ic_check_success)
        ImageViewCompat.setImageTintList(
            b.ivPrinting,
            ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.green_success))
        )
        b.tvPrintingStatus.text = getString(R.string.receipt_status_success)
        isPrinting = false
        b.root.postDelayed({ finishAndNavigate() }, 1500)
    }

    private fun onPrintError(error: Throwable, fallbackText: String) {
        stopStatusLoop()
        stopPrinterAnimation()
        b.printingOverlay.visibility = View.VISIBLE
        b.ivPrinting.setImageResource(R.drawable.ic_printer)
        ImageViewCompat.setImageTintList(
            b.ivPrinting,
            ColorStateList.valueOf(MaterialColors.getColor(b.ivPrinting, com.google.android.material.R.attr.colorError))
        )
        val message = error.message?.takeUnless { it.isBlank() } ?: "Tidak diketahui"
        b.tvPrintingStatus.text = getString(R.string.receipt_status_error, message)
        toast("Gagal cetak: $message")
        isPrinting = false
        b.btnSaveAndPrint.isEnabled = true
        printReceipt(currentSaleId, fallbackText)
        b.root.postDelayed({ setIdleState() }, 2500)
    }

    private fun setIdleState() {
        stopStatusLoop()
        stopPrinterAnimation()
        b.printingOverlay.visibility = View.GONE
        b.ivPrinting.setImageResource(R.drawable.ic_printer)
        ImageViewCompat.setImageTintList(
            b.ivPrinting,
            ColorStateList.valueOf(ContextCompat.getColor(requireContext(), android.R.color.white))
        )
        b.tvPrintingStatus.text = getString(R.string.receipt_status_printing)
        b.btnSaveAndPrint.isEnabled = true
        isPrinting = false
    }

    private fun startPrinterAnimation() {
        stopPrinterAnimation()
        printerAnimator = ObjectAnimator.ofFloat(b.ivPrinting, View.ROTATION, 0f, -8f, 8f, 0f).apply {
            duration = 700
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopPrinterAnimation() {
        printerAnimator?.cancel()
        printerAnimator = null
        b.ivPrinting.rotation = 0f
    }

    private fun startStatusLoop() {
        stopStatusLoop()
        if (printingMessages.isEmpty()) return
        var index = 0
        b.tvPrintingStatus.text = printingMessages[index]
        val runnable = object : Runnable {
            override fun run() {
                index = (index + 1) % printingMessages.size
                b.tvPrintingStatus.text = printingMessages[index]
                printingHandler.postDelayed(this, 400)
            }
        }
        printingRunnable = runnable
        printingHandler.postDelayed(runnable, 400)
    }

    private fun stopStatusLoop() {
        printingRunnable?.let { printingHandler.removeCallbacks(it) }
        printingRunnable = null
    }

    private fun finishAndNavigate() {
        val nav = findNavController()
        val popped = nav.popBackStack(R.id.adminCashierCatalogFragment, false)
        if (!popped) nav.navigate(R.id.adminCashierCatalogFragment)
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN])
    private fun showBtPicker(onPicked: (String) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasAllBtPermissions()) {
            pendingPermissionAction = { showBtPicker(onPicked) }
            requestPermissions(requiredBtPermissions, btRequestCode)
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val bonded = adapter?.bondedDevices?.toList().orEmpty()
        if (bonded.isEmpty()) {
            toast("Tidak ada printer terpasang (pairing dulu)")
            setIdleState()
            return
        }

        val labels = bonded.map { "${it.name} (${it.address})" }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Pilih Printer")
            .setItems(labels) { _, which -> onPicked(bonded[which].address) }
            .setNegativeButton("Batal") { _, _ ->
                setIdleState()
            }
            .show()
    }

    private fun printReceipt(saleId: String, text: String) {
        val pm = requireContext().getSystemService(Context.PRINT_SERVICE) as PrintManager
        pm.print(
            "Struk-$saleId",
            CenteredReceiptPrintAdapter(requireContext(), text, desiredContentWidthMm = 72f),
            null
        )
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == btRequestCode) {
            if (hasAllBtPermissions()) {
                pendingPermissionAction?.invoke()
            } else {
                toast("Izin Bluetooth diperlukan untuk mencetak")
                setIdleState()
            }
            pendingPermissionAction = null
        }
    }

    override fun onDestroyView() {
        stopStatusLoop()
        stopPrinterAnimation()
        pendingPermissionAction = null
        b.printingOverlay.visibility = View.GONE
        _b = null
        super.onDestroyView()
    }

    private fun hasAllBtPermissions(): Boolean {
        if (requiredBtPermissions.isEmpty()) return true
        return requiredBtPermissions.all {
            ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
        }
    }

}

