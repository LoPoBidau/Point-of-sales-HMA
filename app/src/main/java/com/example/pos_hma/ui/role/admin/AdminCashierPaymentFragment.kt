package com.example.pos_hma.ui.role.admin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.example.pos_hma.R
import com.example.pos_hma.databinding.FragmentAdminCashierPaymentBinding
import com.example.pos_hma.ui.role.admin.print.ReceiptFormatter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.DocumentReference
import com.example.pos_hma.data.BatchState
import java.util.Locale
import java.text.NumberFormat
import java.util.Date
import com.lottiefiles.dotlottie.core.model.Config
import com.lottiefiles.dotlottie.core.util.DotLottieEventListener
import com.lottiefiles.dotlottie.core.util.DotLottieSource

private val ID_LOCALE = Locale("in","ID")
private fun rupiah(v: Long) = NumberFormat.getInstance(ID_LOCALE).format(v)

class AdminCashierPaymentFragment : Fragment() {

    private var _binding: FragmentAdminCashierPaymentBinding? = null
    private val binding get() = _binding!!

    private val cartVm: CartViewModel by activityViewModels()
    private val db by lazy { FirebaseFirestore.getInstance() }

    private var inputDigits: String = ""
    private var totalAmount: Long = 0L
    private var goodsSubTotal: Long = 0L
    private var serviceFee: Long = 0L

    private var pendingNav: Runnable? = null
    private val EXTRA_SUCCESS_DELAY_MS = 650L

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentAdminCashierPaymentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        // Nonaktifkan tombol back sistem; pakai tombol "Batal Bayar"
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object: OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // Consume back press silently on payment screen
                }
            }
        )

        cartVm.goodsSubTotal.observe(viewLifecycleOwner) { v ->
            goodsSubTotal = v
            updateSummaryUI()
            updatePaidUI()
        }
        cartVm.serviceFee.observe(viewLifecycleOwner) { fee ->
            serviceFee = fee ?: 0L
            updateSummaryUI()
            updatePaidUI()
        }
        cartVm.lines.observe(viewLifecycleOwner) { _ ->
            updateSummaryUI()
        }

        // Keypad
        val keys = mapOf(
            R.id.btnK0 to "0", R.id.btnK1 to "1", R.id.btnK2 to "2", R.id.btnK3 to "3",
            R.id.btnK4 to "4", R.id.btnK5 to "5", R.id.btnK6 to "6",
            R.id.btnK7 to "7", R.id.btnK8 to "8", R.id.btnK9 to "9"
        )
        keys.forEach { (id, digit) ->
            binding.root.findViewById<View>(id).setOnClickListener { appendDigit(digit) }
        }
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
                    val nav = findNavController()
                    val popped = nav.popBackStack(R.id.adminCartFragment, false)
                    if (!popped) nav.navigate(R.id.adminCartFragment)
                }
                .show()
        }
    }

    private fun appendDigit(d: String) {
        if (inputDigits.isEmpty() && d == "0") return
        if (inputDigits.length >= 12) return
        inputDigits += d
        updatePaidUI()
    }

    private fun updatePaidUI() {
        val paid = inputDigits.toLongOrNull() ?: 0L
        val effectiveTotal = totalAmount
        val change = paid - effectiveTotal
        // Total due already shown in tvGrand by updateSummaryUI; keep it in sync just in case
        binding.tvGrand.text = "Rp ${rupiah(effectiveTotal)}"
        binding.tvPaid.text = "Rp ${rupiah(paid)}"
        binding.tvChange.text = "Kembalian: Rp ${rupiah(if (change > 0) change else 0)}"
        val enabled = paid >= effectiveTotal && effectiveTotal > 0
        binding.btnPayNow.isEnabled = enabled
        binding.btnPayNow.alpha = if (enabled) 1f else 0.5f
    }

    private fun attemptPay() {
        val lines = cartVm.lines.value ?: emptyMap()
        if (lines.isEmpty() && (cartVm.serviceFee.value ?: 0L) <= 0L) {
            Toast.makeText(requireContext(), "Keranjang kosong", Toast.LENGTH_SHORT).show()
            return
        }
        val paid = inputDigits.toLongOrNull() ?: 0L
        val effectiveTotal = totalAmount

        if (paid < effectiveTotal || effectiveTotal <= 0) {
            Toast.makeText(requireContext(), "Nominal kurang dari total", Toast.LENGTH_SHORT).show()
            return
        }

        // Tampilkan overlay loading
        showProcessingOverlay(true)

        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: ""
        val now = FieldValue.serverTimestamp()
        // Generate No. Nota per hari: yyddMM/#### (reset harian)
        val cal = java.util.Calendar.getInstance()
        val yy = (cal.get(java.util.Calendar.YEAR) % 100).toString().padStart(2, '0')
        val dd = cal.get(java.util.Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
        val MM = (cal.get(java.util.Calendar.MONTH) + 1).toString().padStart(2, '0')
        val prefix = "$yy$dd$MM" // tahun(2)-tanggal-bulan -> contoh 250909
        val counterRef = db.collection("counters").document(prefix)

        // Kumpulkan kebutuhan FIFO batch per SKU (prefetch batch dokumen)
        val skuNeeds = lines.values
            .filter { !it.product.isService && it.product.trackStock }
            .associate { it.product.sku.ifBlank { it.product.id } to it.qty.toLong() }

        // Query sequentially per SKU, then run transaction
        fun onBatchesCollected(batchesBySku: Map<String, List<com.google.firebase.firestore.DocumentSnapshot>>) {
            db.runTransaction { trx ->
                // 1) READS FIRST: counter + reserve sale ID
                val cSnap = trx.get(counterRef)
                val last = cSnap.getLong("last") ?: 0L
                val next = last + 1L

                val noNota = String.format("%s/%04d", prefix, next)
                val docId = String.format("%s-%04d", prefix, next)
                val saleRef = db.collection("sales").document(docId)

                val items = mutableListOf<Map<String, Any>>()

                // Validate and consume per line (FIFO)
                lines.values.forEach { l ->
                    val p = l.product
                    val sku = p.sku.ifBlank { p.id }
                    val qty = l.qty.toLong()

                    if (!p.isService && p.trackStock) {
                        // Check product stock
                        val pRef = db.collection("products").document(sku)
                        val snap = trx.get(pRef)
                        require(snap.exists()) { "Produk tidak ditemukan: ${p.name}" }
                        val track = snap.getBoolean("trackStock") ?: true
                        if (track) {
                            val old = snap.getLong("stock") ?: 0L
                            val newStock = old - qty
                            require(newStock >= 0) { "Stok tidak cukup: ${p.name}" }

                            // Consume FIFO from pre-fetched batches
                            val batchDocs = (batchesBySku[sku] ?: emptyList())
                            var need = qty
                            var costSum = 0L
                            for (doc in batchDocs) {
                                if (need <= 0) break
                                val bRef = doc.reference
                                val bSnap = trx.get(bRef)
                                val remain = bSnap.getLong("remainingQty") ?: 0L
                                if (remain <= 0L) continue
                                val unitCost = bSnap.getLong("unitCost") ?: 0L
                                val take = kotlin.math.min(need, remain)
                                val newRemain = remain - take
                                val batchUpdates = mutableMapOf<String, Any>("remainingQty" to newRemain)
                                if (newRemain <= 0L) {
                                    batchUpdates["state"] = BatchState.CLEARED.name
                                }
                                trx.update(bRef, batchUpdates)
                                costSum += take * unitCost
                                // movement per-batch
                                val mv = db.collection("inventory_movements").document()
                                trx.set(mv, mapOf(
                                    "sku" to sku,
                                    "type" to "SALE",
                                    "qtyDelta" to -take,
                                    "unitCost" to unitCost,
                                    "createdAt" to now,
                                    "refId" to saleRef.id
                                ))
                                need -= take
                            }
                            if (need > 0) {
                                // Auto-fill missing FIFO batches using current product stock snapshot
                                val fallbackQty = need
                                val fallbackCost = snap.getLong("lastCost") ?: p.lastCost
                                val mvFallback = db.collection("inventory_movements").document()
                                trx.set(mvFallback, mapOf(
                                    "sku" to sku,
                                    "type" to "SALE",
                                    "qtyDelta" to -fallbackQty,
                                    "unitCost" to fallbackCost,
                                    "createdAt" to now,
                                    "refId" to saleRef.id
                                ))
                                costSum += fallbackQty * fallbackCost
                                val remainingAfterSale = newStock
                                if (remainingAfterSale > 0) {
                                    val autoBatch = db.collection("stock_batches").document()
                                    trx.set(
                                        autoBatch,
                                        mapOf(
                                            "sku" to sku,
                                            "productName" to p.name,
                                            "unitCost" to fallbackCost,
                                            "receivedQty" to remainingAfterSale,
                                            "remainingQty" to remainingAfterSale,
                                            "receivedAt" to now,
                                            "purchaseId" to "AUTO_FILL",
                                            "invoiceNo" to "",
                                            "supplierName" to "",
                                            "supplierId" to "",
                                            "state" to BatchState.OPEN.name,
                                            "termDays" to 0L
                                        )
                                    )
                                }
                                need = 0L
                            }
                            require(need == 0L) { "Batch stok tidak cukup (FIFO): ${p.name}" }
                            trx.update(pRef, mapOf("stock" to newStock, "updatedAt" to now))

                            val unitPrice = p.salePrice
                            val avgUnitCost = if (qty > 0) (costSum / qty) else 0L
                            items.add(
                                mapOf(
                                    "sku" to sku,
                                    "name" to p.name,
                                    "qty" to qty,
                                    "unitPrice" to unitPrice,
                                    "unitCost" to avgUnitCost,
                                    "isService" to false
                                )
                            )
                        }
                    } else {
                        // Service line (no stock tracking)
                        items.add(
                            mapOf(
                                "sku" to sku,
                                "name" to p.name,
                                "qty" to qty,
                                "unitPrice" to p.salePrice,
                                "unitCost" to 0L,
                                "isService" to true
                            )
                        )
                    }
                }

                // 2) Update counter and write sale
                trx.set(counterRef, mapOf("last" to next, "updatedAt" to now), SetOptions.merge())
                trx.set(saleRef, mapOf(
                    "createdAt" to now,
                    "cashierId" to uid,
                    "items" to items,
                    "total" to effectiveTotal,
                    "paid" to paid,
                    "change" to (paid - effectiveTotal),
                    "serviceFee" to (cartVm.serviceFee.value ?: 0L),
                    "status" to "PAID",
                    "noNota" to noNota
                ))

                Pair(noNota, saleRef.id)
            }.addOnSuccessListener { (noNota, docId) ->
                // after success continue below
                // handled in same place as before
                onSaleCommitted(noNota, docId, lines, effectiveTotal, paid)
            }.addOnFailureListener { e ->
                showProcessingOverlay(false)
                Toast.makeText(requireContext(), e.message ?: "Gagal memproses pembayaran (FIFO)", Toast.LENGTH_LONG).show()
            }
        }

        // Fetch batches then call transaction
        if (skuNeeds.isEmpty()) {
            // No goods, only service fee
            onBatchesCollected(emptyMap())
            return
        }

        val batchesBySku = mutableMapOf<String, MutableList<com.google.firebase.firestore.DocumentSnapshot>>()
        val skus = skuNeeds.keys.toList()
        // Map SKU -> product name for clearer error message
        val nameBySku = lines.values
            .filter { !it.product.isService && it.product.trackStock }
            .associate { (it.product.sku.ifBlank { it.product.id }) to it.product.name }

        fun fetchSkuPaged(idx: Int) {
            if (idx >= skus.size) {
                // Prefetch complete; proceed even if some SKU needs auto-fill batches
                onBatchesCollected(batchesBySku.mapValues { it.value.toList() })
                return
            }

            val sku = skus[idx]
            val need = skuNeeds[sku] ?: 0L
            batchesBySku[sku] = mutableListOf()

            fun page(startAfter: com.google.firebase.firestore.DocumentSnapshot? = null) {
                var q = db.collection("stock_batches")
                    .whereEqualTo("sku", sku)
                    .orderBy("receivedAt", Query.Direction.ASCENDING)
                    .limit(50)
                if (startAfter != null) q = q.startAfter(startAfter)
                q.get()
                    .addOnSuccessListener { snap ->
                        val docs = snap.documents.filter { (it.getLong("remainingQty") ?: 0L) > 0L }
                        batchesBySku[sku]!!.addAll(docs)
                        val sum = batchesBySku[sku]!!.sumOf { d -> d.getLong("remainingQty") ?: 0L }
                        val hasMore = docs.isNotEmpty()
                        if (sum < need && hasMore) page(docs.last()) else fetchSkuPaged(idx + 1)
                    }
                    .addOnFailureListener {
                        // treat as no more; proceed
                        fetchSkuPaged(idx + 1)
                    }
            }
            page()
        }
        fetchSkuPaged(0)
    }

    private fun onSaleCommitted(
        noNota: String,
        docId: String,
        lines: Map<String, com.example.pos_hma.ui.role.admin.CartLine>,
        effectiveTotal: Long,
        paid: Long
    ) {
        // setelah runTransaction sukses:
        val store = ReceiptFormatter.StoreInfo(
                name = "HAGWY MULYA AGUNG BENGKEL",
                address1 = "JL. APT PRANOTO",
                address2 = "KOTA SAMARINDA SEBERANG",
                phone = "0341-8686715"
            )
        val items = lines.values.map { l ->
            val p = l.product
            ReceiptFormatter.Item(
                name = p.name,
                qty = l.qty.toLong(),
                unitPrice = p.salePrice,
                isService = false
            )
        }
        val saleInfo = ReceiptFormatter.SaleInfo(
            saleId = noNota,
            date   = java.util.Date(),
            total  = effectiveTotal,
            paid   = paid
        )

        val svc = cartVm.serviceFee.value ?: 0L
        val receiptForScreen  = ReceiptFormatter.buildForScreen(store, saleInfo, items, serviceFee = svc)
        val receiptForPrinter = ReceiptFormatter.buildForPrinter(store, saleInfo, items, serviceFee = svc)

        cartVm.clear()

        // Show success, then navigate
        showSuccessThenNavigate(
            Bundle().apply {
                putString("saleId", noNota)
                putString("saleDocId", docId)
                putCharSequence("receiptScreen", receiptForScreen)
                putString("receiptPrinter", receiptForPrinter)
            }
        )
    }

    private fun updateSummaryUI() {
        val hasGoods = (cartVm.lines.value?.isNotEmpty() == true)
        // Sub Total (barang)
        binding.tvTotalLabel.text = "Sub Total"
        binding.tvTotal.text = "Rp ${rupiah(goodsSubTotal)}"

        // Service fee row
        val hasFee = serviceFee > 0
        binding.tvServiceFeeLabel.visibility = if (hasFee) View.VISIBLE else View.GONE
        binding.tvServiceFeeValue.visibility = if (hasFee) View.VISIBLE else View.GONE
        if (hasFee) {
            binding.tvServiceFeeLabel.text = if (hasGoods) "Tambahan Biaya Service" else "Biaya Service"
            binding.tvServiceFeeValue.text = "Rp ${rupiah(serviceFee)}"
        }

        // Grand total (yang harus dibayar)
        totalAmount = goodsSubTotal + serviceFee
        binding.tvGrand.text = "Rp ${rupiah(totalAmount)}"
        // Besarkan khusus jika hanya service
        if (!hasGoods && hasFee) {
            binding.tvTotal.visibility = View.GONE
            binding.tvTotalLabel.visibility = View.GONE
            binding.tvGrand.textSize = 26f
        } else {
            binding.tvTotal.visibility = View.VISIBLE
            binding.tvTotalLabel.visibility = View.VISIBLE
            binding.tvGrand.textSize = 22f
        }
    }

    private fun showProcessingOverlay(show: Boolean) {
        if (show) {
            binding.paymentOverlay.visibility = View.VISIBLE
            binding.progress.visibility = View.VISIBLE
            binding.successCheck.visibility = View.GONE
        } else {
            binding.paymentOverlay.visibility = View.GONE
            binding.progress.visibility = View.GONE
            binding.successCheck.visibility = View.GONE
        }
    }

    private fun showSuccessThenNavigate(args: Bundle) {
        // Tampilkan animasi DotLottie (JSON dari assets), lalu navigasi setelah selesai + jeda
        binding.progress.visibility = View.GONE
        val anim = binding.successCheck
        anim.visibility = View.VISIBLE

        val r = Runnable {
            if (!isAdded) return@Runnable
            showProcessingOverlay(false)
            findNavController().navigate(R.id.adminCashierReceiptFragment, args)
        }
        pendingNav = r

        // Dengarkan event selesai animasi
        val listener = object : DotLottieEventListener {
            override fun onComplete() {
                // Sedikit delay agar pengguna melihat hasilnya
                binding.root.postDelayed(r, EXTRA_SUCCESS_DELAY_MS)
            }
        }
        anim.addEventListener(listener)

        // Muat dari assets/success_check.json
        val config = Config.Builder()
            .autoplay(true)
            .speed(1f)
            .loop(false)
            .source(DotLottieSource.Asset("success_check.json"))
            .build()
        anim.load(config)
    }

    override fun onDestroyView() {
        pendingNav?.let { binding.root.removeCallbacks(it) }
        // Tidak perlu memaksa stop animasi; view akan dilepas
        pendingNav = null
        _binding = null
        super.onDestroyView()
    }
}




