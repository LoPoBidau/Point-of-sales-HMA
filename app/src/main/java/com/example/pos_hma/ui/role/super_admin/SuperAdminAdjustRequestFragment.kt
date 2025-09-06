package com.example.pos_hma.ui.role.super_admin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
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

class SuperAdminAdjustRequestFragment : Fragment(), SnapshotDisposable {

    private var _binding: FragmentSuperAdminAdjustRequestBinding? = null
    private val binding get() = _binding!!
    private val db by lazy { FirebaseFirestore.getInstance() }

    private var reg: ListenerRegistration? = null
    private val adapter = ReqAdapter(
        onApprove = { approve(it) },
        onReject = { reject(it) }
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, s: Bundle?
    ): View {
        _binding = FragmentSuperAdminAdjustRequestBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        binding.rv.layoutManager = LinearLayoutManager(requireContext())
        binding.rv.adapter = adapter
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

    private fun listen() {
        disposeSnapshots()
        reg = db.collection("stock_adjust_requests")
            .whereEqualTo("status", "PENDING")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, e ->
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
                val list = snap!!.documents.map { d ->
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
                adapter.submitList(list)
                binding.empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
    }

    private fun refreshOnce() {
        db.collection("stock_adjust_requests")
            .whereEqualTo("status", "PENDING")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { qs ->
                val list = qs.documents.map { d ->
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
                adapter.submitList(list)
                binding.empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
            .addOnCompleteListener { binding.swipeRefresh.isRefreshing = false }
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
                db.runTransaction { trx ->
                    val p = trx.get(pRef)
                    if (!p.exists()) throw IllegalStateException("Produk tidak ditemukan")
                    if (!(p.getBoolean("trackStock") ?: true)) throw IllegalStateException("Jasa tidak pakai stok")

                    val old = p.getLong("stock") ?: 0L
                    val newStock = old + r.requestedDelta
                    if (newStock < 0) throw IllegalStateException("Stok tidak boleh negatif")

                    trx.update(pRef, mapOf("stock" to newStock, "updatedAt" to now))
                    val mv = db.collection("inventory_movements").document()
                    trx.set(mv, mapOf(
                        "sku" to r.sku, "type" to "ADJUSTMENT",
                        "qtyDelta" to r.requestedDelta, "unitCost" to 0L,
                        "createdAt" to now, "refId" to "REQ", "note" to r.reason
                    ))
                    trx.update(reqRef, mapOf("status" to "APPROVED", "decidedAt" to now))
                    null
                }.addOnSuccessListener { toast("Disetujui") }
                    .addOnFailureListener { e -> toast(e.message ?: "Gagal menyetujui") }
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
        b.tvTitle.text = r.productName
        b.tvSub.text = "SKU ${r.sku} • ${if (r.requestedDelta >= 0) "+" else ""}${r.requestedDelta}\nAlasan: ${r.reason}"
        b.btnApprove.setOnClickListener { onApprove(r) }
        b.btnReject.setOnClickListener { onReject(r) }
    }
}
