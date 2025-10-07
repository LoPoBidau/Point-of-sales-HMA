package com.example.pos_hma.ui.role.admin

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.print.PrintManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.pos_hma.R
import com.example.pos_hma.databinding.FragmentAdminCashierReceiptBinding
import com.example.pos_hma.print.DirectEscPosPrinter
import com.example.pos_hma.ui.role.admin.print.CenteredReceiptPrintAdapter
import com.example.pos_hma.utils.PrintersPref
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class AdminCashierReceiptFragment : Fragment() {

    private var _b: FragmentAdminCashierReceiptBinding? = null
    private val b get() = _b!!

    private val btRequestCode = 201
    private var currentSaleId: String = "-"
    private var receiptPrinterPayload: String = ""

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

        b.tvReceipt.text = textUi

        b.btnPrint.setOnClickListener { startDirectPrint() }
        b.btnSaveAndBackToCatalog.setOnClickListener {
            val nav = findNavController()
            val popped = nav.popBackStack(R.id.adminCashierCatalogFragment, false)
            if (!popped) nav.navigate(R.id.adminCashierCatalogFragment)
        }
    }

    private fun startDirectPrint() {
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), btRequestCode)
            return
        }

        val textForPrinter = if (receiptPrinterPayload.isNotBlank()) receiptPrinterPayload else b.tvReceipt.text.toString()
        val savedMac = PrintersPref.getMac(requireContext())
        if (savedMac.isNullOrBlank()) {
            showBtPicker { mac ->
                PrintersPref.saveMac(requireContext(), mac)
                doPrint(mac, textForPrinter)
            }
        } else {
            doPrint(savedMac, textForPrinter)
        }
    }

    private fun doPrint(mac: String, text: String) {
        DirectEscPosPrinter.print(
            requireContext(),
            mac,
            if (text.isBlank()) " " else text,
            onSuccess = { toast("Terkirim ke printer") },
            onError = {
                toast("Gagal cetak: ${it.message}")
                printReceipt(currentSaleId, text)
            }
        )
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun showBtPicker(onPicked: (String) -> Unit) {
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
        if (requestCode == btRequestCode && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startDirectPrint()
        }
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }
}
