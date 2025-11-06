package com.example.pos_hma.ui.role.admin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.example.pos_hma.R
import com.example.pos_hma.data.SaleReturnStatus
import com.example.pos_hma.data.SaleStatus
import com.example.pos_hma.data.toSaleLineItems
import com.example.pos_hma.databinding.FragmentAdminCashierReturnBinding
import com.example.pos_hma.databinding.ItemReturnRequestRowBinding
import com.example.pos_hma.utils.SnapshotDisposable
import com.example.pos_hma.utils.toUserMessage
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
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

class AdminCashierReturnFragment : Fragment(), SnapshotDisposable {

    private var _binding: FragmentAdminCashierReturnBinding? = null
    private val binding get() = _binding!!

    private val db by lazy { FirebaseFirestore.getInstance() }
    private val nf by lazy { NumberFormat.getInstance(Locale("in", "ID")) }
    private val df by lazy { SimpleDateFormat("dd MMM yyyy HH:mm", Locale("in", "ID")) }

    private val requests = mutableListOf<ReturnRequestRow>()
    private val adapter = ReturnRequestAdapter(requests)
    private var registration: ListenerRegistration? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAdminCashierReturnBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvRequests.adapter = adapter
        binding.fabRequestReturn.setOnClickListener { showSalePicker() }
        binding.progress.isVisible = true
        startListening()
    }

    override fun onDestroyView() {
        disposeSnapshots()
        _binding = null
        super.onDestroyView()
    }

    override fun disposeSnapshots() {
        registration?.remove()
        registration = null
    }

    private fun startListening() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: run {
            toast("Harap masuk kembali")
            return
        }
        registration?.remove()
        registration = db.collection("sale_return_requests")
            .whereEqualTo("cashierId", uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (!isAdded) return@addSnapshotListener
                binding.progress.isVisible = false
                if (error != null) {
                    toast(error.toUserMessage("Gagal memuat data retur."))
                    return@addSnapshotListener
                }
                requests.clear()
                snapshot?.documents?.forEach { doc ->
                    ReturnRequestRow.from(doc)?.let { requests += it }
                }
                adapter.notifyDataSetChanged()
                updateEmptyState()
            }
    }

    private fun updateEmptyState() {
        binding.tvEmpty.isVisible = requests.isEmpty()
        binding.rvRequests.isVisible = requests.isNotEmpty()
    }

    private fun showSalePicker() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: run {
            toast("Harap masuk kembali")
            return
        }
        binding.progress.isVisible = true
        binding.fabRequestReturn.isEnabled = false

        db.collection("sales")
            .whereEqualTo("cashierId", uid)
            .whereEqualTo("status", SaleStatus.PAID)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(100)
            .get()
            .addOnSuccessListener { snap ->
                if (!isAdded) return@addOnSuccessListener
                val choices = snap.documents.mapNotNull { SaleChoice.from(it, nf, df) }
                if (choices.isEmpty()) {
                    toast("Tidak ada transaksi yang bisa diretur")
                    return@addOnSuccessListener
                }
                val labels = choices.map { it.label }.toTypedArray()
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Pilih Transaksi")
                    .setItems(labels) { _, which ->
                        val choice = choices.getOrNull(which) ?: return@setItems
                        showReasonDialog(choice)
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            }
            .addOnFailureListener { e ->
                if (!isAdded) return@addOnFailureListener
                toast(e.toUserMessage("Gagal memuat transaksi."))
            }
            .addOnCompleteListener {
                if (!isAdded) return@addOnCompleteListener
                binding.progress.isVisible = false
                binding.fabRequestReturn.isEnabled = true
            }
    }

    private fun showReasonDialog(choice: SaleChoice) {
        val context = requireContext()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 0)
        }
        val summary = android.widget.TextView(context).apply {
            text = "No. Nota: ${choice.displayId}\nTotal: ${choice.totalLabel}"
        }
        val til = layoutInflater.inflate(R.layout.view_dialog_text_input, container, false) as TextInputLayout
        val etReason = til.editText as TextInputEditText
        etReason.minLines = 2
        etReason.maxLines = 4
        etReason.isSingleLine = false
        container.addView(
            summary,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        container.addView(til)

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Ajukan Retur")
            .setView(container)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Kirim", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val text = etReason.text?.toString()?.trim().orEmpty()
                if (text.length < 5) {
                    til.error = "Jelaskan alasan retur (min. 5 karakter)"
                    return@setOnClickListener
                }
                til.error = null
                dialog.dismiss()
                submitReturnRequest(choice.saleId, text)
            }
        }
        dialog.show()
    }

    private fun submitReturnRequest(saleId: String, reason: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: run {
            toast("Harap masuk kembali")
            return
        }
        binding.progress.isVisible = true
        binding.fabRequestReturn.isEnabled = false

        val reqRef = db.collection("sale_return_requests").document()
        val saleRef = db.collection("sales").document(saleId)
        val serverNow = FieldValue.serverTimestamp()
        val currentUser = FirebaseAuth.getInstance().currentUser

        db.runTransaction { trx ->
            val saleSnap = trx.get(saleRef)
            if (!saleSnap.exists()) {
                throw FirebaseFirestoreException("Transaksi tidak ditemukan", FirebaseFirestoreException.Code.NOT_FOUND)
            }
            val status = saleSnap.getString("status").orEmpty()
            if (!status.equals(SaleStatus.PAID, ignoreCase = true)) {
                throw FirebaseFirestoreException("Transaksi sudah tidak bisa diretur", FirebaseFirestoreException.Code.ABORTED)
            }
            val returnStatus = saleSnap.getString("returnStatus").orEmpty()
            if (returnStatus.equals(SaleReturnStatus.PENDING, ignoreCase = true)) {
                throw FirebaseFirestoreException("Permintaan retur sedang diproses", FirebaseFirestoreException.Code.ABORTED)
            }
            val itemsList = (saleSnap.get("items") as? List<*>).orEmpty()
            val copiedItems = itemsList.mapNotNull { raw ->
                val map = raw as? Map<*, *> ?: return@mapNotNull null
                val copy = HashMap<String, Any?>()
                map.forEach { entry ->
                    val key = entry.key as? String ?: return@forEach
                    copy[key] = entry.value
                }
                copy
            }

            val requestData = mutableMapOf<String, Any>(
                "status" to SaleReturnStatus.PENDING,
                "saleId" to saleRef.id,
                "saleNo" to (saleSnap.getString("noNota") ?: saleRef.id),
                "cashierId" to (saleSnap.getString("cashierId") ?: uid),
                "createdAt" to serverNow,
                "reason" to reason,
                "items" to copiedItems,
                "total" to (saleSnap.getLong("total") ?: 0L),
                "paid" to (saleSnap.getLong("paid") ?: (saleSnap.getLong("total") ?: 0L)),
                "change" to (saleSnap.getLong("change") ?: 0L),
                "serviceFee" to (saleSnap.getLong("serviceFee") ?: 0L),
                "saleCreatedAt" to (saleSnap.getTimestamp("createdAt") ?: Timestamp.now())
            )
            currentUser?.email?.takeIf { it.isNotBlank() }?.let { requestData["cashierEmail"] = it }
            currentUser?.displayName?.takeIf { it.isNotBlank() }?.let { requestData["cashierName"] = it }
            trx.set(reqRef, requestData)

            val saleUpdates = mutableMapOf<String, Any>(
                "status" to SaleStatus.RETURN_PENDING,
                "returnStatus" to SaleReturnStatus.PENDING,
                "returnRequestId" to reqRef.id,
                "returnReason" to reason,
                "returnRequestedAt" to serverNow,
                "returnApprovedAt" to FieldValue.delete(),
                "returnApprovedBy" to FieldValue.delete(),
                "returnRejectedAt" to FieldValue.delete(),
                "returnRejectionNote" to FieldValue.delete()
            )
            trx.update(saleRef, saleUpdates)
        }.addOnSuccessListener {
            if (!isAdded) return@addOnSuccessListener
            toast("Permintaan retur dikirim")
        }.addOnFailureListener { e ->
            if (!isAdded) return@addOnFailureListener
            toast(e.toUserMessage("Gagal mengirim permintaan retur."))
        }.addOnCompleteListener {
            if (!isAdded) return@addOnCompleteListener
            binding.progress.isVisible = false
            binding.fabRequestReturn.isEnabled = true
        }
    }

    private fun toast(message: String) {
        if (!isAdded) return
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private inner class ReturnRequestAdapter(private val rows: List<ReturnRequestRow>) :
        RecyclerView.Adapter<ReturnRequestAdapter.VH>() {

        inner class VH(val vb: ItemReturnRequestRowBinding) : RecyclerView.ViewHolder(vb.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val inflater = LayoutInflater.from(parent.context)
            return VH(ItemReturnRequestRowBinding.inflate(inflater, parent, false))
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = rows[position]
            val vb = holder.vb
            vb.tvSaleNo.text = "No. Nota: ${row.displayId}"
            vb.tvRequestedAt.text = row.requestedAt?.let { "Diajukan: ${df.format(it)}" } ?: "Diajukan: -"
            vb.tvTotal.text = "Total: Rp ${nf.format(row.total)}"
            vb.tvReason.text = row.reason.ifBlank { "-" }
            val (statusLabel, badgeRes) = row.statusLabelAndBadge()
            vb.tvStatus.text = statusLabel
            vb.tvStatus.setBackgroundResource(badgeRes)
            val hasNote = row.rejectionNote.isNotBlank()
            vb.tvNoteLabel.isVisible = hasNote
            vb.tvNote.isVisible = hasNote
            if (hasNote) {
                vb.tvNote.text = row.rejectionNote
            }
            val showDecided = row.decidedAt != null
            vb.tvDecidedAt.isVisible = showDecided
            if (showDecided) {
                vb.tvDecidedAt.text = "Diputuskan: ${df.format(row.decidedAt!!)}"
            }

            vb.root.setOnClickListener {
                showRequestDetail(row)
            }
        }

        override fun getItemCount(): Int = rows.size
    }

    private fun showRequestDetail(row: ReturnRequestRow) {
        val context = requireContext()
        val sb = StringBuilder()
        sb.append("No. Nota: ${row.displayId}\n")
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
                val label = item.name.ifBlank { item.sku.ifBlank { "-" } }
                sb.append("- $label x${item.qty} @ Rp ${nf.format(item.unitPrice)}\n")
            }
        }
        MaterialAlertDialogBuilder(context)
            .setTitle("Detail Retur")
            .setMessage(sb.toString())
            .setPositiveButton("Tutup", null)
            .show()
    }

    private data class SaleChoice(
        val saleId: String,
        val displayId: String,
        val totalLabel: String,
        val label: String
    ) {
        companion object {
            fun from(doc: DocumentSnapshot, nf: NumberFormat, df: SimpleDateFormat): SaleChoice? {
                val saleId = doc.id
                val noNota = doc.getString("noNota")
                val createdAt = doc.getTimestamp("createdAt")?.toDate()
                val total = doc.getLong("total") ?: 0L
                val displayId = noNota ?: saleId
                val dateLabel = createdAt?.let { df.format(it) } ?: "-"
                val amountLabel = "Rp ${nf.format(total)}"
                val label = "$displayId • $amountLabel • $dateLabel"
                return SaleChoice(
                    saleId = saleId,
                    displayId = displayId,
                    totalLabel = amountLabel,
                    label = label
                )
            }
        }
    }

    private data class ReturnRequestRow(
        val id: String,
        val saleId: String,
        val saleNo: String?,
        val status: String,
        val reason: String,
        val rejectionNote: String,
        val requestedAt: Date?,
        val decidedAt: Date?,
        val total: Long,
        val items: List<com.example.pos_hma.data.SaleLineItem>
    ) {
        val displayId: String get() = saleNo ?: saleId

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
                    total = doc.getLong("total") ?: 0L,
                    items = items.toSaleLineItems()
                )
            }
        }
    }
}
