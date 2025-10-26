package com.example.pos_hma.ui.role.super_admin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.tasks.Tasks
import com.example.pos_hma.R
import com.example.pos_hma.data.BatchState
import com.example.pos_hma.data.SaleLineItem
import com.example.pos_hma.data.SaleReturnStatus
import com.example.pos_hma.data.SaleStatus
import com.example.pos_hma.data.toSaleLineItems
import com.example.pos_hma.databinding.FragmentSuperAdminReturnBinding
import com.example.pos_hma.databinding.ItemSuperAdminReturnRowBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class SuperAdminReturnRequestFragment : Fragment() {

    private var _binding: FragmentSuperAdminReturnBinding? = null
    private val b get() = _binding!!

    private val db by lazy { FirebaseFirestore.getInstance() }
    private val nf by lazy { NumberFormat.getInstance(Locale("in", "ID")) }
    private val df by lazy { SimpleDateFormat("dd MMM yyyy HH:mm", Locale("in", "ID")) }

    private val rows = mutableListOf<ReturnRequestRow>()
    private val adapter = ReturnRequestAdapter(rows)
    private var registration: ListenerRegistration? = null
    private var currentFilter = StatusFilter.ALL
    private var activeChipId: Int? = null
    private val processing = mutableSetOf<String>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSuperAdminReturnBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        b.rvReturnRequests.adapter = adapter
        setupChips()
    }

    override fun onDestroyView() {
        registration?.remove()
        registration = null
        _binding = null
        super.onDestroyView()
    }

    private fun setupChips() {
        b.chipGroupStatus.isSingleSelection = false

        fun updateChipStates() {
            b.chipPending.isChecked = activeChipId == b.chipPending.id
            b.chipApproved.isChecked = activeChipId == b.chipApproved.id
            b.chipRejected.isChecked = activeChipId == b.chipRejected.id
        }

        fun applyStatus(filter: StatusFilter, chipId: Int?) {
            currentFilter = filter
            activeChipId = chipId
            updateChipStates()
            b.progress.isVisible = true
            subscribe()
        }

        b.chipPending.setOnClickListener {
            if (activeChipId == b.chipPending.id) {
                applyStatus(StatusFilter.ALL, null)
            } else {
                applyStatus(StatusFilter.PENDING, b.chipPending.id)
            }
        }
        b.chipApproved.setOnClickListener {
            if (activeChipId == b.chipApproved.id) {
                applyStatus(StatusFilter.ALL, null)
            } else {
                applyStatus(StatusFilter.APPROVED, b.chipApproved.id)
            }
        }
        b.chipRejected.setOnClickListener {
            if (activeChipId == b.chipRejected.id) {
                applyStatus(StatusFilter.ALL, null)
            } else {
                applyStatus(StatusFilter.REJECTED, b.chipRejected.id)
            }
        }

        applyStatus(StatusFilter.ALL, null)
    }

    private fun subscribe() {
        registration?.remove()
        var query: Query = db.collection("sale_return_requests")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(200)
        if (currentFilter != StatusFilter.ALL) {
            query = query.whereEqualTo("status", currentFilter.name)
        }
        registration = query.addSnapshotListener { snapshot, error ->
            if (!isAdded) return@addSnapshotListener
            b.progress.isVisible = false
            if (error != null) {
                toast(error.localizedMessage ?: "Gagal memuat permintaan retur")
                return@addSnapshotListener
            }
            rows.clear()
            snapshot?.documents?.forEach { doc ->
                ReturnRequestRow.from(doc)?.let { rows += it }
            }
            adapter.notifyDataSetChanged()
            updateStatus()
        }
    }

    private fun updateStatus() {
        val filterLabel = when (currentFilter) {
            StatusFilter.ALL -> "Semua"
            StatusFilter.PENDING -> "Menunggu"
            StatusFilter.APPROVED -> "Disetujui"
            StatusFilter.REJECTED -> "Ditolak"
        }
        b.tvCount.text = "Permintaan ($filterLabel): ${rows.size}"
        val empty = rows.isEmpty()
        b.tvEmpty.isVisible = empty
        b.rvReturnRequests.isVisible = !empty
    }

    private fun confirmApprove(row: ReturnRequestRow) {
        if (processing.contains(row.id)) return
        val message = buildString {
            append("No. Nota: ${row.displayId}\n")
            append("Total: Rp ${nf.format(row.total)}\n")
            append("Kasir: ${row.cashierName ?: row.cashierEmail ?: row.cashierId ?: "-"}\n\n")
            append("Setujui permintaan retur ini? Stok akan dikembalikan.")
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Setujui Retur")
            .setMessage(message)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Setujui") { _, _ -> approveReturn(row) }
            .show()
    }

    private fun confirmReject(row: ReturnRequestRow) {
        if (processing.contains(row.id)) return
        val context = requireContext()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 0)
        }
        val til = layoutInflater.inflate(R.layout.view_dialog_text_input, container, false) as TextInputLayout
        val et = (til.editText as? TextInputEditText) ?: TextInputEditText(context).also { til.addView(it) }
        et.minLines = 2
        et.maxLines = 5
        et.isSingleLine = false
        container.addView(
            til,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Tolak Retur")
            .setMessage("Berikan catatan penolakan untuk kasir.")
            .setView(container)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Tolak", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val note = et.text?.toString()?.trim().orEmpty()
                if (note.length < 5) {
                    til.error = "Minimal 5 karakter"
                    return@setOnClickListener
                }
                til.error = null
                dialog.dismiss()
                rejectReturn(row, note)
            }
        }
        dialog.show()
    }

    private fun approveReturn(row: ReturnRequestRow) {
        if (processing.contains(row.id)) return
        processing += row.id
        showProcessing(true)
        notifyRowChanged(row.id)
        val currentUser = FirebaseAuth.getInstance().currentUser
        val approverId = currentUser?.uid ?: "unknown"
        val reqRef = db.collection("sale_return_requests").document(row.id)
        val serverNow = FieldValue.serverTimestamp()

        fun runWithRefs(productRefs: Map<String, DocumentReference>) {
            db.runTransaction { trx ->
                val reqSnap = trx.get(reqRef)
                if (!reqSnap.exists()) {
                    throw FirebaseFirestoreException("Permintaan tidak ditemukan", FirebaseFirestoreException.Code.NOT_FOUND)
                }
                val status = reqSnap.getString("status") ?: throw FirebaseFirestoreException("Status tidak valid", FirebaseFirestoreException.Code.ABORTED)
                if (!status.equals(SaleReturnStatus.PENDING, true)) {
                    throw FirebaseFirestoreException("Permintaan sudah diproses", FirebaseFirestoreException.Code.ABORTED)
                }
                val saleId = reqSnap.getString("saleId") ?: throw FirebaseFirestoreException("ID transaksi kosong", FirebaseFirestoreException.Code.ABORTED)
                val saleRef = db.collection("sales").document(saleId)
                val saleSnap = trx.get(saleRef)
                if (!saleSnap.exists()) {
                    throw FirebaseFirestoreException("Transaksi tidak ditemukan", FirebaseFirestoreException.Code.NOT_FOUND)
                }
                val itemMaps = (reqSnap.get("items") as? List<*>) ?: emptyList<Any>()
                val saleItems = itemMaps.toSaleLineItems()

                val productSnapshots = mutableMapOf<String, DocumentSnapshot>()
                val productRefsBySku = mutableMapOf<String, DocumentReference>()
                val batchSnapshots = mutableMapOf<String, DocumentSnapshot>()
                val batchRefsById = mutableMapOf<String, DocumentReference>()

                saleItems.forEach { item ->
                    if (!item.isGoods || item.qty <= 0L) return@forEach
                    val productRef = productRefs[item.sku]
                        ?: throw FirebaseFirestoreException("Produk ${item.name} tidak memiliki referensi", FirebaseFirestoreException.Code.ABORTED)
                    if (!productSnapshots.containsKey(item.sku)) {
                        val snap = trx.get(productRef)
                        if (!snap.exists()) {
                            throw FirebaseFirestoreException("Produk ${item.name} tidak ditemukan", FirebaseFirestoreException.Code.NOT_FOUND)
                        }
                        productSnapshots[item.sku] = snap
                        productRefsBySku[item.sku] = productRef
                    }
                    item.consumptions.forEach { cons ->
                        val batchId = cons.batchId
                        if (!batchId.isNullOrBlank() && cons.qty > 0L) {
                            if (!batchRefsById.containsKey(batchId)) {
                                val ref = db.collection("stock_batches").document(batchId)
                                batchRefsById[batchId] = ref
                                batchSnapshots[batchId] = trx.get(ref)
                            }
                        }
                    }
                }

                saleItems.forEach { item ->
                    if (!item.isGoods || item.qty <= 0L) return@forEach
                    val productRef = productRefsBySku[item.sku]
                        ?: throw FirebaseFirestoreException("Produk ${item.name} tidak memiliki referensi", FirebaseFirestoreException.Code.ABORTED)
                    val productSnap = productSnapshots[item.sku]
                        ?: throw FirebaseFirestoreException("Produk ${item.name} tidak ditemukan", FirebaseFirestoreException.Code.NOT_FOUND)
                    val currentStock = productSnap.getLong("stock") ?: 0L
                    val newStock = max(0L, currentStock) + item.qty
                    val productUpdates = mutableMapOf<String, Any>(
                        "stock" to newStock,
                        "updatedAt" to serverNow
                    )
                    trx.update(productRef, productUpdates)

                    var remainingUnassigned = item.qty
                    if (item.consumptions.isNotEmpty()) {
                        item.consumptions.forEach { cons ->
                            val qty = cons.qty
                            if (qty <= 0L) return@forEach
                            val batchId = cons.batchId
                            if (!batchId.isNullOrBlank()) {
                                val batchRef = batchRefsById[batchId]
                                val batchSnap = batchSnapshots[batchId]
                                if (batchRef != null && batchSnap != null && batchSnap.exists()) {
                                    val currentRemaining = batchSnap.getLong("remainingQty") ?: 0L
                                    val newRemaining = currentRemaining + qty
                                    val batchUpdates = mutableMapOf<String, Any>(
                                        "remainingQty" to newRemaining,
                                        "updatedAt" to serverNow
                                    )
                                    val state = batchSnap.getString("state")
                                    if (newRemaining > 0L && state.equals(BatchState.CLEARED.name, ignoreCase = true)) {
                                        batchUpdates["state"] = BatchState.OPEN.name
                                    }
                                    trx.update(batchRef, batchUpdates)

                                    val mvRef = db.collection("inventory_movements").document()
                                    val movement = mutableMapOf<String, Any>(
                                        "sku" to item.sku,
                                        "type" to "RETURN",
                                        "qtyDelta" to qty,
                                        "unitCost" to cons.unitCost,
                                        "createdAt" to serverNow,
                                        "refId" to reqRef.id,
                                        "saleId" to saleRef.id,
                                        "returnRequestId" to reqRef.id,
                                        "batchId" to batchRef.id
                                    )
                                    trx.set(mvRef, movement)
                                    remainingUnassigned -= qty
                                } else {
                                    remainingUnassigned -= qty
                                }
                            }
                        }
                    }

                    if (remainingUnassigned > 0L) {
                        val batchRef = db.collection("stock_batches").document()
                        val batchData = mutableMapOf<String, Any>(
                            "sku" to item.sku,
                            "productName" to item.name,
                            "unitCost" to item.unitCost,
                            "receivedQty" to remainingUnassigned,
                            "remainingQty" to remainingUnassigned,
                            "salePrice" to item.unitPrice,
                            "state" to BatchState.OPEN.name,
                            "receivedAt" to serverNow,
                            "returnRequestId" to reqRef.id,
                            "source" to "RETURN"
                        )
                        trx.set(batchRef, batchData)

                        val mvRef = db.collection("inventory_movements").document()
                        val movement = mutableMapOf<String, Any>(
                            "sku" to item.sku,
                            "type" to "RETURN",
                            "qtyDelta" to remainingUnassigned,
                            "unitCost" to item.unitCost,
                            "createdAt" to serverNow,
                            "refId" to reqRef.id,
                            "saleId" to saleRef.id,
                            "returnRequestId" to reqRef.id,
                            "batchId" to batchRef.id
                        )
                        trx.set(mvRef, movement)
                    }
                }

                trx.update(
                    saleRef,
                    mapOf(
                        "status" to SaleStatus.RETURNED,
                        "returnStatus" to SaleReturnStatus.APPROVED,
                        "returnApprovedAt" to serverNow,
                        "returnApprovedBy" to approverId,
                        "returnRejectedAt" to FieldValue.delete(),
                        "returnRejectionNote" to FieldValue.delete()
                    )
                )

                val requestUpdates = mutableMapOf<String, Any>(
                    "status" to SaleReturnStatus.APPROVED,
                    "decidedAt" to serverNow,
                    "decidedBy" to approverId,
                    "rejectionNote" to FieldValue.delete()
                )
                currentUser?.displayName?.takeIf { it.isNotBlank() }?.let { requestUpdates["decidedByName"] = it }
                trx.update(reqRef, requestUpdates)
            }.addOnSuccessListener {
            if (!isAdded) return@addOnSuccessListener
            toast("Retur disetujui")
        }.addOnFailureListener { e ->
            if (!isAdded) return@addOnFailureListener
            toast(e.localizedMessage ?: "Gagal menyetujui retur")
        }.addOnCompleteListener {
            if (!isAdded) return@addOnCompleteListener
            processing -= row.id
            showProcessing(false)
            notifyRowChanged(row.id)
        }
        }

        val goodsItems = row.items.filter { it.isGoods }
        val productRefs = mutableMapOf<String, DocumentReference>()
        val fetchTasks = mutableListOf<Pair<String, com.google.android.gms.tasks.Task<com.google.firebase.firestore.QuerySnapshot>>>()

        goodsItems.forEach { item ->
            if (!item.productId.isNullOrBlank()) {
                productRefs[item.sku] = db.collection("products").document(item.productId!!)
            } else if (item.sku.isNotBlank()) {
                val task = db.collection("products")
                    .whereEqualTo("sku", item.sku)
                    .limit(1)
                    .get()
                fetchTasks += item.sku to task
            }
        }

        if (fetchTasks.isEmpty()) {
            runWithRefs(productRefs)
        } else {
            Tasks.whenAllSuccess<com.google.firebase.firestore.QuerySnapshot>(fetchTasks.map { it.second })
                .addOnSuccessListener { snapshots ->
                    val missing = mutableListOf<String>()
                    snapshots.forEachIndexed { index, snapshot ->
                        val sku = fetchTasks[index].first
                        val doc = snapshot.documents.firstOrNull()
                        if (doc != null) {
                            productRefs[sku] = doc.reference
                        } else {
                            missing += sku
                        }
                    }
                    if (missing.isNotEmpty()) {
                        toast("Produk ${missing.joinToString()} tidak memiliki referensi")
                        processing -= row.id
                        showProcessing(false)
                        notifyRowChanged(row.id)
                        return@addOnSuccessListener
                    }
                    runWithRefs(productRefs)
                }
                .addOnFailureListener { e ->
                    toast(e.localizedMessage ?: "Gagal memuat referensi produk")
                    processing -= row.id
                    showProcessing(false)
                    notifyRowChanged(row.id)
                }
        }
    }

    private fun rejectReturn(row: ReturnRequestRow, note: String) {
        if (processing.contains(row.id)) return
        processing += row.id
        showProcessing(true)
        notifyRowChanged(row.id)

        val currentUser = FirebaseAuth.getInstance().currentUser
        val approverId = currentUser?.uid ?: "unknown"
        val reqRef = db.collection("sale_return_requests").document(row.id)
        val serverNow = FieldValue.serverTimestamp()

        db.runTransaction { trx ->
            val reqSnap = trx.get(reqRef)
            if (!reqSnap.exists()) {
                throw FirebaseFirestoreException("Permintaan tidak ditemukan", FirebaseFirestoreException.Code.NOT_FOUND)
            }
            val status = reqSnap.getString("status") ?: throw FirebaseFirestoreException("Status tidak valid", FirebaseFirestoreException.Code.ABORTED)
            if (!status.equals(SaleReturnStatus.PENDING, true)) {
                throw FirebaseFirestoreException("Permintaan sudah diproses", FirebaseFirestoreException.Code.ABORTED)
            }
            val saleId = reqSnap.getString("saleId") ?: throw FirebaseFirestoreException("ID transaksi kosong", FirebaseFirestoreException.Code.ABORTED)
            val saleRef = db.collection("sales").document(saleId)
            val saleSnap = trx.get(saleRef)
            if (!saleSnap.exists()) {
                throw FirebaseFirestoreException("Transaksi tidak ditemukan", FirebaseFirestoreException.Code.NOT_FOUND)
            }

            trx.update(
                saleRef,
                mapOf(
                    "status" to SaleStatus.PAID,
                    "returnStatus" to SaleReturnStatus.REJECTED,
                    "returnRejectedAt" to serverNow,
                    "returnRejectionNote" to note,
                    "returnApprovedAt" to FieldValue.delete(),
                    "returnApprovedBy" to FieldValue.delete()
                )
            )

            val updates = mutableMapOf<String, Any>(
                "status" to SaleReturnStatus.REJECTED,
                "decidedAt" to serverNow,
                "decidedBy" to approverId,
                "rejectionNote" to note
            )
            currentUser?.displayName?.takeIf { it.isNotBlank() }?.let { updates["decidedByName"] = it }
            trx.update(reqRef, updates)
        }.addOnSuccessListener {
            if (!isAdded) return@addOnSuccessListener
            toast("Retur ditolak")
        }.addOnFailureListener { e ->
            if (!isAdded) return@addOnFailureListener
            toast(e.localizedMessage ?: "Gagal menolak retur")
        }.addOnCompleteListener {
            if (!isAdded) return@addOnCompleteListener
            processing -= row.id
            showProcessing(false)
            notifyRowChanged(row.id)
        }
    }

    private fun notifyRowChanged(id: String) {
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) adapter.notifyItemChanged(index)
    }

    private fun showProcessing(show: Boolean) {
        b.progress.isVisible = show
    }

    private fun toast(message: String) {
        if (!isAdded) return
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private inner class ReturnRequestAdapter(private val data: List<ReturnRequestRow>) :
        RecyclerView.Adapter<ReturnRequestAdapter.VH>() {

        inner class VH(val vb: ItemSuperAdminReturnRowBinding) : RecyclerView.ViewHolder(vb.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val inflater = LayoutInflater.from(parent.context)
            return VH(ItemSuperAdminReturnRowBinding.inflate(inflater, parent, false))
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = data[position]
            val vb = holder.vb
            vb.tvSaleNo.text = "No. Nota: ${row.displayId}"
            vb.tvCashier.text = "Kasir: ${row.cashierName ?: row.cashierEmail ?: row.cashierId ?: "-"}"
            vb.tvRequestedAt.text = row.requestedAt?.let { "Diajukan: ${df.format(it)}" } ?: "Diajukan: -"
            vb.tvTotal.text = "Total: Rp ${nf.format(row.total)}"
            vb.tvReason.text = row.reason.ifBlank { "-" }
            val (statusLabel, badgeRes) = row.statusLabelAndBadge()
            vb.tvStatus.text = statusLabel
            vb.tvStatus.setBackgroundResource(badgeRes)

            val isPending = row.isPending
            val busy = processing.contains(row.id)
            vb.btnApprove.isVisible = isPending
            vb.btnReject.isVisible = isPending
            vb.btnApprove.isEnabled = isPending && !busy
            vb.btnReject.isEnabled = isPending && !busy

            vb.btnApprove.setOnClickListener { confirmApprove(row) }
            vb.btnReject.setOnClickListener { confirmReject(row) }
            vb.btnDetail.setOnClickListener { showDetail(row) }
        }

        override fun getItemCount(): Int = data.size
    }

    private fun showDetail(row: ReturnRequestRow) {
        val sb = StringBuilder()
        sb.append("No. Nota: ${row.displayId}\n")
        sb.append("Kasir: ${row.cashierName ?: row.cashierEmail ?: row.cashierId ?: "-"}\n")
        sb.append("Total: Rp ${nf.format(row.total)}\n")
        sb.append("Status: ${row.statusLabelAndBadge().first}\n")
        row.requestedAt?.let { sb.append("Diajukan: ${df.format(it)}\n") }
        row.decidedAt?.let { sb.append("Diputuskan: ${df.format(it)}\n") }
        sb.append("\nAlasan:\n${row.reason.ifBlank { "-" }}\n")
        if (row.rejectionNote.isNotBlank()) {
            sb.append("\nCatatan Penolakan:\n${row.rejectionNote}\n")
        }
        if (row.items.isNotEmpty()) {
            sb.append("\nRincian Barang:\n")
            row.items.forEach { item ->
                val name = item.name.ifBlank { item.sku.ifBlank { "-" } }
                sb.append("- $name x${item.qty} @ Rp ${nf.format(item.unitPrice)}\n")
            }
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Detail Retur")
            .setMessage(sb.toString())
            .setPositiveButton("Tutup", null)
            .show()
    }

    private enum class StatusFilter { ALL, PENDING, APPROVED, REJECTED }

    private data class ReturnRequestRow(
        val id: String,
        val saleId: String,
        val saleNo: String?,
        val status: String,
        val reason: String,
        val rejectionNote: String,
        val requestedAt: Date?,
        val decidedAt: Date?,
        val cashierId: String?,
        val cashierName: String?,
        val cashierEmail: String?,
        val total: Long,
        val items: List<SaleLineItem>
    ) {
        val displayId: String get() = saleNo ?: saleId
        val isPending: Boolean get() = status.equals(SaleReturnStatus.PENDING, true)

        fun statusLabelAndBadge(): Pair<String, Int> = when (status.uppercase(Locale.ROOT)) {
            SaleReturnStatus.PENDING -> "Menunggu" to R.drawable.bg_badge_orange
            SaleReturnStatus.APPROVED -> "Disetujui" to R.drawable.bg_badge_green
            SaleReturnStatus.REJECTED -> "Ditolak" to R.drawable.bg_badge_red
            else -> status.ifBlank { "Tidak diketahui" } to R.drawable.bg_badge_gray
        }

        companion object {
            fun from(doc: DocumentSnapshot): ReturnRequestRow? {
                val id = doc.id
                val status = doc.getString("status") ?: return null
                val saleId = doc.getString("saleId") ?: return null
                val items = doc.get("items") as? List<*>
                return ReturnRequestRow(
                    id = id,
                    saleId = saleId,
                    saleNo = doc.getString("saleNo"),
                    status = status,
                    reason = doc.getString("reason").orEmpty(),
                    rejectionNote = doc.getString("rejectionNote").orEmpty(),
                    requestedAt = doc.getTimestamp("createdAt")?.toDate(),
                    decidedAt = doc.getTimestamp("decidedAt")?.toDate(),
                    cashierId = doc.getString("cashierId"),
                    cashierName = doc.getString("cashierName"),
                    cashierEmail = doc.getString("cashierEmail"),
                    total = doc.getLong("total") ?: 0L,
                    items = items.toSaleLineItems()
                )
            }
        }
    }
}
