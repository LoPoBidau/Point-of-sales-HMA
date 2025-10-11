package com.example.pos_hma.ui.role.super_admin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.pos_hma.data.BatchState
import com.example.pos_hma.data.StockAdjustRequest
import com.example.pos_hma.databinding.FragmentSuperAdminAdjustRequestBinding
import com.example.pos_hma.databinding.ItemAdjustRequestBinding
import com.example.pos_hma.utils.SnapshotDisposable
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.DocumentSnapshot

class SuperAdminAdjustRequestFragment : Fragment(), SnapshotDisposable {

    private var _binding: FragmentSuperAdminAdjustRequestBinding? = null
    private val binding get() = _binding!!
    private val db by lazy { FirebaseFirestore.getInstance() }

    private val stockEventVm by activityViewModels<StockEventViewModel>()

    private var reg: ListenerRegistration? = null
    private val adapter = ReqAdapter(
        onApprove = { approve(it) },
        onReject = { reject(it) }
    )

    private enum class StatusFilter { PENDING, APPROVED, REJECTED, ALL }
    private var currentStatus: StatusFilter = StatusFilter.ALL

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, s: Bundle?
    ): View {
        _binding = FragmentSuperAdminAdjustRequestBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        binding.rv.layoutManager = LinearLayoutManager(requireContext())
        binding.rv.adapter = adapter

        // Setup chips for status filter (default: Semua)
        binding.chipPending.isChecked = false
        binding.chipApproved.isChecked = false
        binding.chipRejected.isChecked = false
        binding.chipAll.isChecked = true

        binding.chipPending.setOnClickListener { setStatus(StatusFilter.PENDING) }
        binding.chipApproved.setOnClickListener { setStatus(StatusFilter.APPROVED) }
        binding.chipRejected.setOnClickListener { setStatus(StatusFilter.REJECTED) }
        binding.chipAll.setOnClickListener { setStatus(StatusFilter.ALL) }

        listen()
        binding.swipeRefresh.setOnRefreshListener { refreshOnce() }
    }

    override fun onDestroyView() {
        disposeSnapshots()
        _binding = null
        super.onDestroyView()
    }

    // === SnapshotDisposable (dipanggil juga oleh Activity saat logout) ===
    override fun disposeSnapshots() {
        reg?.remove()
        reg = null
    }

    private fun setStatus(s: StatusFilter) {
        if (currentStatus == s) return
        currentStatus = s
        listen()
    }

    private fun listen() {
        disposeSnapshots()
        var q = db.collection("stock_adjust_requests") as com.google.firebase.firestore.Query
        if (currentStatus != StatusFilter.ALL) q = q.whereEqualTo("status", currentStatus.name)
        q = q.orderBy("createdAt", Query.Direction.DESCENDING)
        reg = q.addSnapshotListener { snap, e ->
            if (e != null) {
                // kalau rules/index bermasalah, tampilkan reason
                val msg = when {
                    e is FirebaseFirestoreException &&
                            e.code == FirebaseFirestoreException.Code.FAILED_PRECONDITION ->
                        "Index Firestore belum dibuat untuk permintaan. Buka link 'Create index' di logcat."
                    e is FirebaseFirestoreException &&
                            e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                        "Permission Firestore ditolak. Cek security rules untuk koleksi stock_adjust_requests."
                    else -> e.message ?: "Gagal memuat."
                }
                toast(msg); return@addSnapshotListener
            }
            var list = snap!!.documents.map { d ->
                StockAdjustRequest(
                    id = d.id,
                    sku = d.getString("sku") ?: "",
                    productName = d.getString("productName") ?: "",
                    requestedDelta = d.getLong("requestedDelta") ?: 0L,
                    reason = d.getString("reason") ?: "",
                    status = d.getString("status") ?: "PENDING",
                    requestedBy = d.getString("requestedBy") ?: "",
                    createdAt = d.getTimestamp("createdAt")
                )
            }
            if (currentStatus == StatusFilter.ALL) {
                list = list.sortedWith(compareBy(
                    { statusOrderWeight(it.status) },
                    { -(it.createdAt?.toDate()?.time ?: 0L) }
                ))
            }
            adapter.submitList(list)
            binding.empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun refreshOnce() {
        var q = db.collection("stock_adjust_requests") as com.google.firebase.firestore.Query
        if (currentStatus != StatusFilter.ALL) q = q.whereEqualTo("status", currentStatus.name)
        q = q.orderBy("createdAt", Query.Direction.DESCENDING)
        q.get()
            .addOnSuccessListener { qs ->
                var list = qs.documents.map { d ->
                    StockAdjustRequest(
                        id = d.id,
                        sku = d.getString("sku") ?: "",
                        productName = d.getString("productName") ?: "",
                        requestedDelta = d.getLong("requestedDelta") ?: 0L,
                        reason = d.getString("reason") ?: "",
                        status = d.getString("status") ?: "PENDING",
                        requestedBy = d.getString("requestedBy") ?: "",
                        createdAt = d.getTimestamp("createdAt")
                    )
                }
                if (currentStatus == StatusFilter.ALL) {
                    list = list.sortedWith(compareBy(
                        { statusOrderWeight(it.status) },
                        { -(it.createdAt?.toDate()?.time ?: 0L) }
                    ))
                }
                adapter.submitList(list)
                binding.empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
            .addOnCompleteListener { binding.swipeRefresh.isRefreshing = false }
    }

    private fun statusOrderWeight(status: String): Int = when (status.uppercase()) {
        "PENDING" -> 0
        "APPROVED" -> 1
        "REJECTED" -> 2
        else -> 3
    }

    private fun approve(r: StockAdjustRequest) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Setujui penyesuaian?")
            .setMessage("${r.productName}\nDelta: ${r.requestedDelta}\nAlasan: ${r.reason}")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Setujui") { _, _ ->
                val pRef  = db.collection("products").document(r.sku)
                val reqRef = db.collection("stock_adjust_requests").document(r.id)
                val now = FieldValue.serverTimestamp()

                // Jika ADD: perlu last batch (DESC 1). Jika SUB: perlu batch ascending cukup qty
                if (r.requestedDelta >= 0) {
                    db.runTransaction { trx ->
                        val p = trx.get(pRef)
                        if (!p.exists()) throw IllegalStateException("Produk tidak ditemukan")
                        if (!(p.getBoolean("trackStock") ?: true)) throw IllegalStateException("Jasa tidak pakai stok")

                        val reqSnap = trx.get(reqRef)
                        val unitCostAdj = (reqSnap.getLong("unitCost") ?: p.getLong("lastCost") ?: 0L)
                        if (unitCostAdj <= 0L) throw IllegalStateException("Harga modal tidak valid")

                        val qty = r.requestedDelta
                        val old = p.getLong("stock") ?: 0L
                        val newStock = old + qty
                        trx.update(pRef, mapOf("stock" to newStock, "lastCost" to unitCostAdj, "updatedAt" to now))

                        val batchRef = db.collection("stock_batches").document()
                        trx.set(
                            batchRef,
                            mapOf(
                                "sku" to r.sku,
                                "productName" to (p.getString("name") ?: r.productName),
                                "unitCost" to unitCostAdj,
                                "receivedQty" to qty,
                                "remainingQty" to qty,
                                "receivedAt" to now,
                                "purchaseId" to "ADJUSTMENT:${'$'}{r.id}",
                                "invoiceNo" to "",
                                "supplierName" to "",
                                "supplierId" to "",
                                "state" to BatchState.OPEN.name,
                                "termDays" to 0L
                            )
                        )

                        val mv = db.collection("inventory_movements").document()
                        trx.set(
                            mv,
                            mapOf(
                                "sku" to r.sku,
                                "type" to "ADJUSTMENT",
                                "qtyDelta" to qty,
                                "unitCost" to unitCostAdj,
                                "createdAt" to now,
                                "refId" to r.id,
                                "note" to r.reason
                            )
                        )
                        trx.update(reqRef, mapOf("status" to "APPROVED", "decidedAt" to now))
                        null
                    }.addOnSuccessListener {
                        toast("Disetujui")
                        stockEventVm.emitAdjustmentEvent()
                    }
                        .addOnFailureListener { e -> toast(e.message ?: "Gagal menyetujui") }
                } else {
                    // SUB: prefetch ascending until cukup
                    val need = -r.requestedDelta
                    fun fetchEnough(acc: MutableList<DocumentSnapshot> = mutableListOf(), startAfter: DocumentSnapshot? = null) {
                        var q = db.collection("stock_batches")
                            .whereEqualTo("sku", r.sku)
                            .orderBy("receivedAt", Query.Direction.ASCENDING)
                            .limit(50)
                        if (startAfter != null) q = q.startAfter(startAfter)
                        q.get().addOnSuccessListener { snap ->
                            val docs = snap.documents
                            acc.addAll(docs)
                            val total = acc.sumOf { it.getLong("remainingQty") ?: 0L }
                            if (total < need && docs.isNotEmpty()) fetchEnough(acc, docs.last()) else consume(acc)
                        }.addOnFailureListener { e -> toast(e.message ?: "Gagal memuat batch") }
                    }
                    fun consume(batches: List<DocumentSnapshot>) {
                        val avail = batches.sumOf { it.getLong("remainingQty") ?: 0L }
                        if (avail < need) { toast("Batch stok tidak cukup"); return }
                        db.runTransaction { trx ->
                            val p = trx.get(pRef)
                            if (!p.exists()) throw IllegalStateException("Produk tidak ditemukan")
                            if (!(p.getBoolean("trackStock") ?: true)) throw IllegalStateException("Jasa tidak pakai stok")
                            val old = p.getLong("stock") ?: 0L
                            val newStock = old - need
                            if (newStock < 0) throw IllegalStateException("Stok tidak cukup")
                            trx.update(pRef, mapOf("stock" to newStock, "updatedAt" to now))
                            var remainNeed = need
                            for (d in batches) {
                                if (remainNeed <= 0) break
                                val bRef = d.reference
                                val bs = trx.get(bRef)
                                val rem = bs.getLong("remainingQty") ?: 0L
                                if (rem <= 0L) continue
                                val unit = bs.getLong("unitCost") ?: 0L
                                val take = kotlin.math.min(remainNeed, rem)
                                val remainingAfter = rem - take
                                val batchUpdates = mutableMapOf<String, Any>("remainingQty" to remainingAfter)
                                if (remainingAfter <= 0L) {
                                    batchUpdates["state"] = BatchState.CLEARED.name
                                }
                                trx.update(bRef, batchUpdates)
                                val mv = db.collection("inventory_movements").document()
                                trx.set(mv, mapOf(
                                    "sku" to r.sku, "type" to "ADJUSTMENT",
                                    "qtyDelta" to -take, "unitCost" to unit,
                                    "createdAt" to now, "refId" to r.id, "note" to r.reason
                                ))
                                remainNeed -= take
                            }
                            if (remainNeed != 0L) throw IllegalStateException("Batch stok tidak cukup")
                            trx.update(reqRef, mapOf("status" to "APPROVED", "decidedAt" to now))
                            null
                        }.addOnSuccessListener {
                            toast("Disetujui")
                            stockEventVm.emitAdjustmentEvent()
                        }
                            .addOnFailureListener { e -> toast(e.message ?: "Gagal menyetujui") }
                    }
                    fetchEnough()
                }
            }.show()
    }

    private fun reject(r: StockAdjustRequest) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Tolak permintaan?")
            .setMessage("${r.productName}\nDelta: ${r.requestedDelta}")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Tolak") { _, _ ->
                db.collection("stock_adjust_requests").document(r.id)
                    .update(mapOf("status" to "REJECTED", "decidedAt" to FieldValue.serverTimestamp()))
                    .addOnSuccessListener { toast("Ditolak") }
                    .addOnFailureListener { e -> toast(e.message ?: "Gagal menolak") }
            }.show()
    }

    private fun toast(s: String) =
        Toast.makeText(requireContext(), s, Toast.LENGTH_LONG).show()
}

private fun SuperAdminAdjustRequestFragment.consume(snapshots: kotlin.collections.MutableList<com.google.firebase.firestore.DocumentSnapshot>) {}

/* ===== Adapter ===== */
private class ReqAdapter(
    val onApprove: (StockAdjustRequest) -> Unit,
    val onReject: (StockAdjustRequest) -> Unit
) : ListAdapter<StockAdjustRequest, ReqVH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<StockAdjustRequest>() {
            override fun areItemsTheSame(a: StockAdjustRequest, b: StockAdjustRequest) = a.id == b.id
            override fun areContentsTheSame(a: StockAdjustRequest, b: StockAdjustRequest) = a == b
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReqVH {
        val b = ItemAdjustRequestBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ReqVH(b, onApprove, onReject)
    }

    override fun onBindViewHolder(holder: ReqVH, position: Int) = holder.bind(getItem(position))
}

private class ReqVH(
    private val b: ItemAdjustRequestBinding,
    val onApprove: (StockAdjustRequest) -> Unit,
    val onReject: (StockAdjustRequest) -> Unit
) : RecyclerView.ViewHolder(b.root) {

    fun bind(r: StockAdjustRequest) {
        val locale = java.util.Locale.getDefault()
        val (statusLabel, badgeRes) = when (r.status.uppercase(locale)) {
            "PENDING" -> "Pending" to com.example.pos_hma.R.drawable.bg_badge_blue
            "APPROVED" -> "Disetujui" to com.example.pos_hma.R.drawable.bg_badge_green
            "REJECTED" -> "Ditolak" to com.example.pos_hma.R.drawable.bg_badge_red
            else -> r.status.ifBlank { "Tidak diketahui" } to com.example.pos_hma.R.drawable.bg_badge_gray
        }

        val deltaLabel = buildString {
            if (r.requestedDelta >= 0) append('+')
            append(r.requestedDelta)
        }
        val reasonText = r.reason.ifBlank { "-" }

        b.tvTitle.text = r.productName.ifBlank { r.sku }
        b.tvStatusBadge.text = statusLabel
        b.tvStatusBadge.setBackgroundResource(badgeRes)
        b.tvSub.text = buildString {
            append("SKU: ")
            append(r.sku.ifBlank { "-" })
            append("\nPerubahan Stok: ")
            append(deltaLabel)
            append(" unit")
            append("\nStatus: ")
            append(statusLabel)
            append("\nAlasan: ")
            append(reasonText)
        }

        val isPending = r.status.equals("PENDING", ignoreCase = true)
        b.btnApprove.visibility = if (isPending) View.VISIBLE else View.GONE
        b.btnReject.visibility = if (isPending) View.VISIBLE else View.GONE

        b.btnApprove.setOnClickListener { onApprove(r) }
        b.btnReject.setOnClickListener { onReject(r) }
    }
}
