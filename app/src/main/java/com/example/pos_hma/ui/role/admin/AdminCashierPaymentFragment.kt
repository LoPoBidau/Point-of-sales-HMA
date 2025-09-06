package com.example.pos_hma.ui.role.admin

import android.content.Context
import android.os.Bundle
import android.print.PrintManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import coil.load
import com.example.pos_hma.R
import com.example.pos_hma.data.Product
import com.example.pos_hma.databinding.FragmentAdminCashierPaymentBinding
import com.example.pos_hma.ui.role.admin.print.SimpleReceiptPrintAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

private val ID_LOCALE = java.util.Locale("in","ID")
private fun rupiah(v: Long) = java.text.NumberFormat.getInstance(ID_LOCALE).format(v)

class AdminCashierPaymentFragment : Fragment() {

    private var _binding: FragmentAdminCashierPaymentBinding? = null
    private val binding get() = _binding!!

    private val cartVm: CartViewModel by activityViewModels()
    private val db by lazy { FirebaseFirestore.getInstance() }

    private var inputDigits: String = ""
    private var totalAmount: Long = 0L

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentAdminCashierPaymentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        // Disable system back; users must use Cancel button
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Toast.makeText(requireContext(), "Gunakan tombol Batal Bayar", Toast.LENGTH_SHORT).show()
            }
        })

        cartVm.totalAmount.observe(viewLifecycleOwner) { amt ->
            totalAmount = amt
            binding.tvTotal.text = "Rp ${rupiah(amt)}"
            updatePaidUI()
        }

        // Keypad listeners
        val map = mapOf(
            R.id.btnK0 to "0", R.id.btnK1 to "1", R.id.btnK2 to "2", R.id.btnK3 to "3",
            R.id.btnK4 to "4", R.id.btnK5 to "5", R.id.btnK6 to "6",
            R.id.btnK7 to "7", R.id.btnK8 to "8", R.id.btnK9 to "9"
        )
        map.forEach { (id, digit) -> binding.root.findViewById<View>(id).setOnClickListener { appendDigit(digit) } }
        binding.btnClear.setOnClickListener { inputDigits = ""; updatePaidUI() }
        binding.btnBackspace.setOnClickListener {
            if (inputDigits.isNotEmpty()) inputDigits = inputDigits.dropLast(1)
            updatePaidUI()
        }

        binding.btnPayNow.setOnClickListener { attemptPay() }
        binding.btnCancel.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Batalkan pembayaran?")
                .setMessage("Anda akan kembali ke keranjang. Item di keranjang tetap tersimpan.")
                .setNegativeButton("Tidak", null)
                .setPositiveButton("Ya, batalkan") { _, _ ->
                    // Kembali ke keranjang tanpa menghapus item
                    val nav = findNavController()
                    val popped = nav.popBackStack(R.id.adminCartFragment, false)
                    if (!popped) nav.navigate(R.id.adminCartFragment)
                }
                .show()
        }
    }

    private fun appendDigit(d: String) {
        if (inputDigits == "" && d == "0") return
        if (inputDigits.length >= 12) return
        inputDigits += d
        updatePaidUI()
    }

    private fun updatePaidUI() {
        val paid = inputDigits.toLongOrNull() ?: 0L
        val change = paid - totalAmount
        binding.tvPaid.text = "Rp ${rupiah(paid)}"
        binding.tvChange.text = "Kembalian: Rp ${rupiah(if (change > 0) change else 0)}"
        val enabled = paid >= totalAmount && totalAmount > 0
        binding.btnPayNow.isEnabled = enabled
        // Tetap tampil, tapi diburamkan saat nonaktif
        binding.btnPayNow.alpha = if (enabled) 1f else 0.5f
    }

    private fun attemptPay() {
        val lines = cartVm.lines.value ?: emptyMap()
        if (lines.isEmpty()) { Toast.makeText(requireContext(), "Keranjang kosong", Toast.LENGTH_SHORT).show(); return }
        val paid = inputDigits.toLongOrNull() ?: 0L
        if (paid < totalAmount) { Toast.makeText(requireContext(), "Nominal kurang dari total", Toast.LENGTH_SHORT).show(); return }

        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: ""
        val now = FieldValue.serverTimestamp()
        val saleRef = db.collection("sales").document()

        db.runTransaction { trx ->
            val items = mutableListOf<Map<String, Any>>()
            lines.values.forEach { l ->
                val p = l.product
                val sku = p.sku.ifBlank { p.id }
                val qty = l.qty.toLong()

                if (!p.isService && (p.trackStock)) {
                    val pRef = db.collection("products").document(sku)
                    val snap = trx.get(pRef)
                    require(snap.exists()) { "Produk tidak ditemukan: ${p.name}" }
                    val track = snap.getBoolean("trackStock") ?: true
                    if (track) {
                        val old = snap.getLong("stock") ?: 0L
                        val newStock = old - qty
                        require(newStock >= 0) { "Stok tidak cukup: ${p.name}" }
                        trx.update(pRef, mapOf("stock" to newStock, "updatedAt" to now))

                        val mv = db.collection("inventory_movements").document()
                        trx.set(mv, mapOf(
                            "sku" to sku,
                            "type" to "SALE",
                            "qtyDelta" to -qty,
                            "unitCost" to 0L,
                            "createdAt" to now,
                            "refId" to saleRef.id
                        ))
                    }
                }

                val unitPrice = if (p.isService) 0L else p.salePrice
                items.add(mapOf(
                    "sku" to sku,
                    "name" to p.name,
                    "qty" to qty,
                    "unitPrice" to unitPrice,
                    "isService" to p.isService
                ))
            }

            trx.set(saleRef, mapOf(
                "createdAt" to now,
                "cashierId" to uid,
                "items" to items,
                "total" to totalAmount,
                "paid" to paid,
                "change" to (paid - totalAmount),
                "status" to "PAID"
            ))
            null
        }.addOnSuccessListener {
            cartVm.clear()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Pembayaran berhasil")
                .setMessage("Ingin cetak struk sekarang?")
                .setNegativeButton("Tutup") { _, _ ->
                    findNavController().navigate(R.id.adminCashierCatalogFragment)
                }
                .setPositiveButton("Cetak") { _, _ ->
                    printReceipt(saleRef.id, lines.values.toList(), totalAmount, paid)
                    findNavController().navigate(R.id.adminCashierCatalogFragment)
                }
                .show()
        }.addOnFailureListener { e ->
            Toast.makeText(requireContext(), e.message ?: "Gagal memproses pembayaran", Toast.LENGTH_LONG).show()
        }
    }

    private fun printReceipt(saleId: String, lines: List<CartLine>, total: Long, paid: Long) {
        val change = paid - total
        val b = StringBuilder()
        b.appendLine("POS HMA")
        b.appendLine("Nota: $saleId")
        b.appendLine("-------------------------------")
        lines.forEach { l ->
            val p = l.product
            if (!p.isService) {
                val sub = p.salePrice * l.qty
                b.appendLine("${p.name}")
                b.appendLine("  ${l.qty} x Rp ${rupiah(p.salePrice)}  = Rp ${rupiah(sub)}")
            } else {
                b.appendLine("${p.name}")
                b.appendLine("  Jasa x ${l.qty}")
            }
        }
        b.appendLine("-------------------------------")
        b.appendLine("Total   : Rp ${rupiah(total)}")
        b.appendLine("Bayar   : Rp ${rupiah(paid)}")
        b.appendLine("Kembali : Rp ${rupiah(change)}")
        b.appendLine("Terima kasih")

        val pm = requireContext().getSystemService(Context.PRINT_SERVICE) as PrintManager
        pm.print("Struk-$saleId", SimpleReceiptPrintAdapter(requireContext(), b.toString()), null)
    }

    override fun onDestroyView() { _binding = null; super.onDestroyView() }
}
