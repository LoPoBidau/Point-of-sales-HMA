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
import android.print.PrintManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updatePadding
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.pos_hma.R
import com.example.pos_hma.databinding.FragmentAdminCashierReceiptBinding
import com.example.pos_hma.databinding.DialogReceiptPrintStatusBinding
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
    private var isPrinting = false
    private var pendingPermissionAction: (() -> Unit)? = null
    private var printingDialog: AlertDialog? = null
    private var printingBinding: DialogReceiptPrintStatusBinding? = null
    private var pendingReceiptToPrint: String? = null
    private var hasNavigatedAfterPrint = false
    private var dialogPrinterAnimator: ObjectAnimator? = null

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

        resetPrintingState(clearPending = true)

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
        pendingReceiptToPrint = text
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
        hasNavigatedAfterPrint = false
        val dialogBinding = ensurePrintingDialog()
        isPrinting = true
        pendingPermissionAction = null
        b.btnSaveAndPrint.isEnabled = false
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
            if (text.isBlank()) " " else text,
            onSuccess = { onPrintSuccess() },
            onError = { onPrintError(it, text) }
        )
    }

    private fun onPrintSuccess() {
        val binding = printingBinding ?: ensurePrintingDialog()
        stopDialogPrinterAnimation()
        isPrinting = false
        pendingReceiptToPrint = null
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
            setOnClickListener {
                printingDialog?.dismiss()
                navigateAfterPrintOnce()
            }
        }
        toast("Terkirim ke printer")
        binding.root.postDelayed({
            printingDialog?.dismiss()
            navigateAfterPrintOnce()
        }, 1200)
    }

    private fun onPrintError(error: Throwable, fallbackText: String) {
        val binding = printingBinding ?: ensurePrintingDialog()
        stopDialogPrinterAnimation()
        isPrinting = false
        binding.progress.isVisible = false
        binding.ivStatus.setImageResource(R.drawable.ic_printer)
        val errorColor = MaterialColors.getColor(binding.ivStatus, com.google.android.material.R.attr.colorError)
        binding.ivStatus.imageTintList = ColorStateList.valueOf(errorColor)
        val message = error.message?.takeUnless { it.isBlank() } ?: "Tidak diketahui"
        binding.tvStatus.text = getString(R.string.print_status_failed, message)
        binding.btnRetry.isVisible = true
        binding.btnClose.isVisible = true
        binding.btnRetry.setOnClickListener {
            val retryText = pendingReceiptToPrint
            if (!retryText.isNullOrBlank()) {
                beginPrintWorkflow(retryText)
            } else {
                printingDialog?.dismiss()
            }
        }
        binding.btnClose.setOnClickListener { printingDialog?.dismiss() }
        toast("Gagal cetak: $message")
        b.btnSaveAndPrint.isEnabled = true
        pendingReceiptToPrint = fallbackText
        if (fallbackText.isNotBlank()) {
            printReceipt(currentSaleId, fallbackText)
        }
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

    private fun resetPrintingState(clearPending: Boolean = false) {
        isPrinting = false
        hasNavigatedAfterPrint = false
        stopDialogPrinterAnimation()
        b.btnSaveAndPrint.isEnabled = true
        if (clearPending) {
            pendingReceiptToPrint = null
        }
        printingDialog?.dismiss()
    }

    private fun navigateAfterPrintOnce() {
        if (hasNavigatedAfterPrint) return
        hasNavigatedAfterPrint = true
        finishAndNavigate()
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
            resetPrintingState()
            return
        }

        val labels = bonded.map { "${it.name} (${it.address})" }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Pilih Printer")
            .setItems(labels) { _, which -> onPicked(bonded[which].address) }
            .setNegativeButton("Batal") { _, _ ->
                resetPrintingState()
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
                resetPrintingState()
            }
            pendingPermissionAction = null
        }
    }

    override fun onDestroyView() {
        pendingPermissionAction = null
        resetPrintingState(clearPending = true)
        stopDialogPrinterAnimation()
        printingBinding = null
        printingDialog = null
        _b = null
        super.onDestroyView()
    }

    private fun hasAllBtPermissions(): Boolean {
        if (requiredBtPermissions.isEmpty()) return true
        return requiredBtPermissions.all {
            ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
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

