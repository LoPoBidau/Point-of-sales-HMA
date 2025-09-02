package com.example.pos_hma.ui.owner

import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.pos_hma.R
import com.example.pos_hma.data.StockAdjustRequest
import com.example.pos_hma.databinding.FragmentSuperAdminAdjustRequestBinding
import com.example.pos_hma.databinding.FragmentSuperAdminDashboardBinding
import com.example.pos_hma.databinding.ItemAdjustRequestBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

class SuperAdminAdjustRequestFragment : Fragment() {

    private var _binding: FragmentSuperAdminAdjustRequestBinding? = null
    private val binding get() = _binding!!
    private val db by lazy { FirebaseFirestore.getInstance() }

    private var reg: ListenerRegistration? = null
    private val adapter = ReqAdapter(
        onApprove = { approve(it) },
        onReject = { reject(it) }
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
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
        reg?.remove(); reg = null
        _binding = null
        super.onDestroyView()
    }

    private fun listen() {
        reg?.remove()
        reg = db.collection("stock_adjust_requests")
            .whereEqualTo("status", "PENDING")
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .addSnapshotListener { snap, e ->
                if (e != null) { Toast.makeText(requireContext(), e.message, Toast.LENGTH_SHORT).show(); return@addSnapshotListener }
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
        db.collection("stock_adjust_requests").whereEqualTo("status", "PENDING")
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
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
                val pRef = db.collection("products").document(r.sku)
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
                }.addOnSuccessListener { Toast.makeText(requireContext(), "Disetujui", Toast.LENGTH_SHORT).show() }
                    .addOnFailureListener { e -> Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show() }
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
                    .addOnSuccessListener { Toast.makeText(requireContext(), "Ditolak", Toast.LENGTH_SHORT).show() }
                    .addOnFailureListener { e -> Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show() }
            }.show()
    }

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
        override fun getItemCount() = super.getItemCount()
    }

    private class ReqVH(
        private val b: ItemAdjustRequestBinding,
        val onApprove: (StockAdjustRequest) -> Unit,
        val onReject: (StockAdjustRequest) -> Unit
    ) : RecyclerView.ViewHolder(b.root) {
        fun bind(r: StockAdjustRequest) {
            b.tvTitle.text = r.productName
            b.tvSub.text = "SKU ${r.sku} • ${if (r.requestedDelta>=0) "+" else ""}${r.requestedDelta}\nAlasan: ${r.reason}"
            b.btnApprove.setOnClickListener { onApprove(r) }
            b.btnReject.setOnClickListener { onReject(r) }
        }
    }
}
