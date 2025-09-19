package com.example.pos_hma.ui.role.admin

import android.content.Context
import android.os.Bundle
import android.print.PrintManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.pos_hma.R
import androidx.core.app.ActivityCompat
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.example.pos_hma.print.DirectEscPosPrinter
import com.example.pos_hma.ui.role.admin.print.SimpleReceiptPrintAdapter
import com.example.pos_hma.databinding.FragmentAdminCashierReceiptBinding
import com.example.pos_hma.ui.role.admin.print.CenteredReceiptPrintAdapter
import com.example.pos_hma.utils.PrintersPref
import com.google.android.material.dialog.MaterialAlertDialogBuilder


class AdminCashierReceiptFragment : Fragment() {

    private var _b: FragmentAdminCashierReceiptBinding? = null
    private val b get() = _b!!
    private val BT_REQ = 201


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentAdminCashierReceiptBinding.inflate(inflater, container, false)
        return b.root
        b.btnPrint.setOnClickListener {
            val receiptText = b.tvReceipt.text.toString()

            // TODO: simpan MAC di Settings/SharedPreferences
            val printerMac = "00:11:22:33:44:55" // ganti dengan MAC printermu

            // Minta izin BLUETOOTH_CONNECT di Android 12+
            if (android.os.Build.VERSION.SDK_INT >= 31 &&
                ActivityCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.BLUETOOTH_CONNECT
                )
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 101)
                return@setOnClickListener
            }

            DirectEscPosPrinter.print(
                requireContext(),
                printerMac,
                receiptText,
                onSuccess = { toast("Terkirim ke printer") },
                onError = {
                    // fallback opsional ke PrintManager jika gagal
                    val pm =
                        requireContext().getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager
                    pm.print(
                        "Struk-${
                            b.tvSaleId.text
                        }", SimpleReceiptPrintAdapter(requireContext(), receiptText), null
                    )
                }
            )
        }

    }

    override fun onViewCreated(v: View, s: Bundle?) {
        // Nonaktifkan tombol back fisik di halaman Struk
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // Dibiarkan kosong agar tidak kembali ke layar manapun
                }
            }
        )

        val saleId = arguments?.getString("saleId") ?: "-" // this holds No. Nota string now
        val textUI = arguments?.getCharSequence("receiptScreen") ?: ""
        val textPDF = arguments?.getString("receiptPrinter") ?: textUI.toString()

        b.tvTitle.text = "Nota"
        b.tvSaleId.text = "No. Nota: $saleId"
        b.tvReceipt.text = textUI   // sudah rapi & bold untuk judul

        b.btnPrint.setOnClickListener { printReceipt(saleId, textPDF) }
        b.btnSaveAndBackToCatalog.setOnClickListener {
            val nav = findNavController()
            val popped =
                nav.popBackStack(com.example.pos_hma.R.id.adminCashierCatalogFragment, false)
            if (!popped) nav.navigate(com.example.pos_hma.R.id.adminCashierCatalogFragment)
        }
        b.btnPrint.setOnClickListener { startDirectPrint() }
    }
    private fun startDirectPrint() {
        // Minta izin Android 12+
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), BT_REQ)
            return
        }

        val text = b.tvReceipt.text.toString()  // ini sudah dari ReceiptFormatter.buildForScreen/Printer
        val savedMac = PrintersPref.getMac(requireContext())
        if (savedMac.isNullOrBlank()) {
            showBtPicker { mac ->
                PrintersPref.saveMac(requireContext(), mac)
                doPrint(mac, text)
            }
        } else {
            doPrint(savedMac, text)
        }
    }

    private fun doPrint(mac: String, text: String) {
        DirectEscPosPrinter.print(
            requireContext(),
            mac,
            // Penting: untuk printer gunakan versi "printer", bukan yang di-stylize.
            // Jika yang kamu tampilkan di layar pakai buildForScreen(), buat lagi:
            text = if (text.isBlank()) " " else text,
            onSuccess = { toast("Terkirim ke printer") },
            onError = { toast("Gagal cetak: ${it.message}") }
        )
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun showBtPicker(onPicked: (String) -> Unit) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val bonded = adapter?.bondedDevices?.toList().orEmpty()
        if (bonded.isEmpty()) { toast("Tidak ada printer terpasang (pairing dulu)"); return }

        val labels = bonded.map { "${it.name} (${it.address})" }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Pilih Printer")
            .setItems(labels) { _, which -> onPicked(bonded[which].address) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun printReceipt(saleId: String, text: String) {
        val pm =
            requireContext().getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager
        pm.print(
            "Struk-$saleId",
            CenteredReceiptPrintAdapter(
                requireContext(),
                text,
                desiredContentWidthMm = 72f
            ), // 58f utk 58mm
            null
        )
    }

    private fun toast(s: String) =
        android.widget.Toast.makeText(requireContext(), s, android.widget.Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        _b = null; super.onDestroyView()
    }
    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == BT_REQ && res.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startDirectPrint()
        }
    }
}
