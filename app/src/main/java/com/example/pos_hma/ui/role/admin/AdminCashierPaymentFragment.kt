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
import com.example.pos_hma.data.BatchState
import com.example.pos_hma.ui.role.admin.print.ReceiptFormatter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions
import java.text.NumberFormat
import java.util.Calendar
import java.util.Locale

private val ID_LOCALE = Locale("in", "ID")
private fun rupiah(v: Long) = NumberFormat.getInstance(ID_LOCALE).format(v)

class AdminCashierPaymentFragment : Fragment() {

    private var _binding: FragmentAdminCashierPaymentBinding? = null
    private val binding get() = _binding!!

    private val cartVm: CartViewModel by activityViewModels()
    private val db by lazy { FirebaseFirestore.getInstance() }

    private var inputDigits = ""
    private var totalAmount = 0L
    private var goodsSubTotal = 0L
    private var serviceFee = 0L

    private var pendingNav: Runnable? = null
    private val EXTRA_SUCCESS_DELAY_MS = 650L

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentAdminCashierPaymentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) { override fun handleOnBackPressed() {} }
        )

        cartVm.goodsSubTotal.observe(viewLifecycleOwner) { goodsSubTotal = it; updateSummaryUI(); updatePaidUI() }
        cartVm.serviceFee.observe(viewLifecycleOwner) { serviceFee = it ?: 0L; updateSummaryUI(); updatePaidUI() }
        cartVm.lines.observe(viewLifecycleOwner) { updateSummaryUI() }

        listOf(
            R.id.btnK0 to "0", R.id.btnK1 to "1", R.id.btnK2 to "2", R.id.btnK3 to "3",
            R.id.btnK4 to "4", R.id.btnK5 to "5", R.id.btnK6 to "6",
            R.id.btnK7 to "7", R.id.btnK8 to "8", R.id.btnK9 to "9"
        ).forEach { (id, d) -> binding.root.findViewById<View>(id).setOnClickListener { appendDigit(d) } }

        binding.btnClear.setOnClickListener { inputDigits = ""; updatePaidUI() }
        binding.btnBackspace.setOnClickListener { if (inputDigits.isNotEmpty()) { inputDigits = inputDigits.dropLast(1); updatePaidUI() } }

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
                }.show()
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
        val due = totalAmount
        binding.tvGrand.text = "Rp ${rupiah(due)}"
        binding.tvPaid.text = "Rp ${rupiah(paid)}"
        binding.tvChange.text = "Kembalian: Rp ${rupiah((paid - due).coerceAtLeast(0))}"
        val ok = paid >= due && due > 0
        binding.btnPayNow.isEnabled = ok
        binding.btnPayNow.alpha = if (ok) 1f else .5f
    }

    private fun attemptPay() {
        val lines = cartVm.lines.value ?: emptyMap()
        if (lines.isEmpty() && (cartVm.serviceFee.value ?: 0L) <= 0L) {
            Toast.makeText(requireContext(), "Keranjang kosong", Toast.LENGTH_SHORT).show()
            return
        }
        val paid = inputDigits.toLongOrNull() ?: 0L
        val due = totalAmount
        if (paid < due || due <= 0) {
            Toast.makeText(requireContext(), "Nominal kurang dari total", Toast.LENGTH_SHORT).show()
            return
        }

        showProcessingOverlay(true)

        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: ""
        val now = FieldValue.serverTimestamp()

        val cal = Calendar.getInstance()
        val yy = (cal.get(Calendar.YEAR) % 100).toString().padStart(2, '0')
        val dd = cal.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
        val MM = (cal.get(Calendar.MONTH) + 1).toString().padStart(2, '0')
        val prefix = "$yy$dd$MM"
        val counterRef = db.collection("counters").document(prefix)

        val batchFetches = mutableListOf<Pair<String, Task<QuerySnapshot>>>()
        val seenSkus = mutableSetOf<String>()
        lines.values.forEach { line ->
            val product = line.product
            if (!product.isService && product.trackStock) {
                val sku = product.sku.ifBlank { product.id }
                if (seenSkus.add(sku)) {
                    val task = db.collection("stock_batches")
                        .whereEqualTo("sku", sku)
                        .orderBy("receivedAt", Query.Direction.ASCENDING)
                        .get()
                    batchFetches += sku to task
                }
            }
        }

        fun proceed(fifoRefsBySku: Map<String, List<DocumentReference>>) {
            db.runTransaction { trx ->
                val cSnap = trx.get(counterRef)
                val last = cSnap.getLong("last") ?: 0L
                val next = last + 1L
                val noNota = String.format("%s/%04d", prefix, next)
                val docId = String.format("%s-%04d", prefix, next)

                val saleRef = db.collection("sales").document(docId)

                data class BatchConsumption(
                    val ref: DocumentReference?,
                    val consumed: Long,
                    val newRemaining: Long,
                    val unitCost: Long,
                    val salePrice: Long?
                )

                data class Plan(
                    val ref: DocumentReference,
                    val sku: String,
                    val name: String,
                    val qty: Long,
                    val unitPrice: Long,
                    val newStock: Long,
                    val avgUnitCost: Long,
                    val consumptions: List<BatchConsumption>,
                    val salePriceUpdate: Long?,
                    val lastCostUpdate: Long?
                )

                val plans = mutableListOf<Plan>()
                val items = mutableListOf<Map<String, Any>>()

                lines.values.forEach { line ->
                    val product = line.product
                    val sku = product.sku.ifBlank { product.id }
                    val qty = line.qty.toLong()

                    if (!product.isService && product.trackStock) {
                        val pRef = db.collection("products").document(sku)
                        val snap = trx.get(pRef)
                        require(snap.exists()) { "Produk tidak ditemukan: ${product.name}" }
                        val track = snap.getBoolean("trackStock") ?: true
                        if (track) {
                            val oldStock = snap.getLong("stock") ?: 0L
                            val newStock = oldStock - qty
                            require(newStock >= 0) { "Stok tidak cukup: ${product.name}" }

                            val batchRefs = fifoRefsBySku[sku].orEmpty()
                            val consumptions = mutableListOf<BatchConsumption>()
                            var remaining = qty
                            var firstBefore: DocumentSnapshot? = null
                            var firstAfter: DocumentSnapshot? = null

                            if (batchRefs.isEmpty()) {
                                if (qty > 0L) {
                                    val fallbackCost = snap.getLong("lastCost") ?: 0L
                                    consumptions += BatchConsumption(null, qty, 0L, fallbackCost, null)
                                    remaining = 0L
                                }
                            } else {
                                for (ref in batchRefs) {
                                    val batchSnap = trx.get(ref)
                                    val remainingQty = batchSnap.getLong("remainingQty") ?: 0L
                                    if (remainingQty <= 0L) continue
                                    if (firstBefore == null) firstBefore = batchSnap

                                    val unitCostBatch = batchSnap.getLong("unitCost") ?: 0L
                                    val salePriceBatch = batchSnap.getLong("salePrice")
                                    var newRemaining = remainingQty
                                    if (remaining > 0L) {
                                        val take = minOf(remaining, remainingQty)
                                        if (take > 0L) {
                                            newRemaining = remainingQty - take
                                            consumptions += BatchConsumption(ref, take, newRemaining, unitCostBatch, salePriceBatch)
                                            remaining -= take
                                        }
                                    }

                                    if (newRemaining > 0L) {
                                        firstAfter = batchSnap
                                        if (remaining <= 0L) break
                                    } else if (remaining <= 0L) {
                                        continue
                                    }
                                }
                            }

                            require(remaining <= 0L) { "Batch stok tidak cukup: ${product.name}" }

                            val totalCost = consumptions.fold(0L) { acc, c -> acc + c.consumed * c.unitCost }
                            val avgUnitCost = if (qty > 0L) totalCost / qty else 0L
                            val currentSalePrice = snap.getLong("salePrice") ?: 0L
                            val salePriceUpdate = if (firstBefore != null && firstAfter != null && firstBefore!!.id != firstAfter!!.id) {
                                val candidate = firstAfter!!.getLong("salePrice") ?: 0L
                                if (candidate > 0L && candidate != currentSalePrice) candidate else null
                            } else null
                            val lastCostUpdate = when {
                                firstBefore != null && firstAfter != null && firstBefore!!.id != firstAfter!!.id ->
                                    firstAfter!!.getLong("unitCost") ?: 0L
                                firstBefore != null && firstAfter == null && newStock <= 0L -> 0L
                                else -> null
                            }

                            plans += Plan(
                                ref = pRef,
                                sku = sku,
                                name = product.name,
                                qty = qty,
                                unitPrice = product.salePrice,
                                newStock = newStock,
                                avgUnitCost = avgUnitCost,
                                consumptions = consumptions,
                                salePriceUpdate = salePriceUpdate,
                                lastCostUpdate = lastCostUpdate
                            )

                            items += mapOf(
                                "sku" to sku,
                                "name" to product.name,
                                "qty" to qty,
                                "unitPrice" to product.salePrice,
                                "unitCost" to avgUnitCost,
                                "isService" to false
                            )
                        }
                    } else {
                        items += mapOf(
                            "sku" to sku,
                            "name" to product.name,
                            "qty" to qty,
                            "unitPrice" to product.salePrice,
                            "unitCost" to 0L,
                            "isService" to true
                        )
                    }
                }

                plans.forEach { pl ->
                    val productUpdates = mutableMapOf<String, Any>(
                        "stock" to pl.newStock,
                        "updatedAt" to now
                    )
                    pl.lastCostUpdate?.let { productUpdates["lastCost"] = it }
                    pl.salePriceUpdate?.let { productUpdates["salePrice"] = it }
                    trx.update(pl.ref, productUpdates)

                    for (cons in pl.consumptions) {
                        if (cons.consumed <= 0L) continue
                        cons.ref?.let { ref ->
                            val batchUpdates = mutableMapOf<String, Any>(
                                "remainingQty" to cons.newRemaining
                            )
                            if (cons.newRemaining <= 0L) {
                                batchUpdates["state"] = BatchState.CLEARED.name
                            }
                            trx.update(ref, batchUpdates)
                        }
                        val mv = db.collection("inventory_movements").document()
                        val movement = mutableMapOf<String, Any>(
                            "sku" to pl.sku,
                            "type" to "SALE",
                            "qtyDelta" to -cons.consumed,
                            "unitCost" to cons.unitCost,
                            "createdAt" to now,
                            "refId" to saleRef.id
                        )
                        cons.ref?.let { movement["batchId"] = it.id }
                        trx.set(mv, movement)
                    }
                }

                trx.set(saleRef, mapOf(
                    "createdAt" to now, "cashierId" to uid, "items" to items,
                    "total" to due, "paid" to paid, "change" to (paid - due),
                    "serviceFee" to (cartVm.serviceFee.value ?: 0L),
                    "status" to "PAID", "noNota" to noNota
                ))

                trx.set(counterRef, mapOf("last" to next, "updatedAt" to now), SetOptions.merge())

                Pair(noNota, saleRef.id)
            }.addOnSuccessListener { (noNota, docId) ->
                onSaleCommitted(noNota, docId, paid, due)
            }.addOnFailureListener { e ->
                showProcessingOverlay(false)
                Toast.makeText(requireContext(), e.message ?: "Gagal memproses pembayaran", Toast.LENGTH_LONG).show()
            }
        }

        if (batchFetches.isEmpty()) {
            proceed(emptyMap())
        } else {
            Tasks.whenAllSuccess<QuerySnapshot>(batchFetches.map { it.second })
                .addOnSuccessListener { snapshots ->
                    val fifoRefs = mutableMapOf<String, List<DocumentReference>>()
                    snapshots.forEachIndexed { index, snapshot ->
                        val docs = snapshot.documents.map { it.reference }
                        fifoRefs[batchFetches[index].first] = docs
                    }
                    proceed(fifoRefs)
                }
                .addOnFailureListener { e ->
                    showProcessingOverlay(false)
                    Toast.makeText(requireContext(), e.message ?: "Gagal memuat batch stok", Toast.LENGTH_LONG).show()
                }
        }
    }
    private fun onSaleCommitted(noNota: String, docId: String, paid: Long, total: Long) {
        val lines = cartVm.lines.value ?: emptyMap()
        val store = ReceiptFormatter.StoreInfo(
            name = "HAGWY MULYA AGUNG BENGKEL",
            address1 = "JL. APT PRANOTO",
            address2 = "KOTA SAMARINDA SEBERANG",
            phone = "0341-8686715"
        )
        val items = lines.values.map { l ->
            val p = l.product
            ReceiptFormatter.Item(name = p.name, qty = l.qty.toLong(), unitPrice = p.salePrice, isService = false)
        }
        val saleInfo = ReceiptFormatter.SaleInfo(saleId = noNota, date = java.util.Date(), total = total, paid = paid)

        val svc = cartVm.serviceFee.value ?: 0L
        val receiptForScreen  = ReceiptFormatter.buildForScreen(store, saleInfo, items, serviceFee = svc)
        val receiptForPrinter = ReceiptFormatter.buildForPrinter(store, saleInfo, items, serviceFee = svc)

        cartVm.clear()
        showSuccessThenNavigate(Bundle().apply {
            putString("saleId", noNota)
            putString("saleDocId", docId)
            putCharSequence("receiptScreen", receiptForScreen)
            putString("receiptPrinter", receiptForPrinter)
        })
    }

    private fun updateSummaryUI() {
        val hasGoods = (cartVm.lines.value?.isNotEmpty() == true)
        binding.tvTotalLabel.text = "Sub Total"
        binding.tvTotal.text = "Rp ${rupiah(goodsSubTotal)}"

        val hasFee = serviceFee > 0
        binding.tvServiceFeeLabel.visibility = if (hasFee) View.VISIBLE else View.GONE
        binding.tvServiceFeeValue.visibility = if (hasFee) View.VISIBLE else View.GONE
        if (hasFee) {
            binding.tvServiceFeeLabel.text = if (hasGoods) "Tambahan Biaya Service" else "Biaya Service"
            binding.tvServiceFeeValue.text = "Rp ${rupiah(serviceFee)}"
        }

        totalAmount = goodsSubTotal + serviceFee
        binding.tvGrand.text = "Rp ${rupiah(totalAmount)}"
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
        binding.progress.visibility = View.GONE
        val anim = binding.successCheck
        anim.visibility = View.VISIBLE

        val r = Runnable {
            if (!isAdded) return@Runnable
            showProcessingOverlay(false)
            findNavController().navigate(R.id.adminCashierReceiptFragment, args)
        }
        pendingNav = r

        val listener = object : com.lottiefiles.dotlottie.core.util.DotLottieEventListener {
            override fun onComplete() { binding.root.postDelayed(r, EXTRA_SUCCESS_DELAY_MS) }
        }
        anim.addEventListener(listener)

        val config = com.lottiefiles.dotlottie.core.model.Config.Builder()
            .autoplay(true).speed(1f).loop(false)
            .source(com.lottiefiles.dotlottie.core.util.DotLottieSource.Asset("success_check.json"))
            .build()
        anim.load(config)
    }

    override fun onDestroyView() {
        pendingNav?.let { binding.root.removeCallbacks(it) }
        pendingNav = null
        _binding = null
        super.onDestroyView()
    }
}

