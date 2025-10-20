package com.example.pos_hma.ui.role.super_admin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.pos_hma.R
import com.example.pos_hma.data.Supplier
import com.example.pos_hma.databinding.DialogSupplierFormBinding
import com.example.pos_hma.databinding.FragmentSuperAdminSupplierBinding
import com.example.pos_hma.databinding.ItemSupplierBinding
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

class SuperAdminSupplierFragment : Fragment() {

    private var _b: FragmentSuperAdminSupplierBinding? = null
    private val b get() = _b!!
    private val db by lazy { FirebaseFirestore.getInstance() }
    private var reg: ListenerRegistration? = null
    private var requiredAlert: AlertDialog? = null
    private val adapter = SupplierAdapter(
        onEdit = { openForm(it) },
        onDelete = { confirmDelete(it) }
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentSuperAdminSupplierBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.rv.layoutManager = LinearLayoutManager(requireContext())
        b.rv.adapter = adapter
        b.fabAdd.setOnClickListener { openForm(null) }
        listen()
    }

    override fun onDestroyView() {
        reg?.remove(); reg = null
        requiredAlert?.dismiss(); requiredAlert = null
        _b = null
        super.onDestroyView()
    }

    private fun listen() {
        reg?.remove(); reg = null
        reg = db.collection("suppliers").orderBy("nameLowercase").addSnapshotListener { snap, e ->
            if (e != null) { toast(e.message ?: "Gagal memuat"); return@addSnapshotListener }
            val list = snap!!.documents.map { d ->
                Supplier(
                    id = d.id,
                    name = d.getString("name") ?: d.id,
                    nameLowercase = (d.getString("nameLowercase") ?: d.getString("name") ?: d.id).lowercase(),
                    phone = d.getString("phone"),
                    address = d.getString("address"),
                    isActive = d.getBoolean("isActive") ?: true,
                    paymentTermDays = d.getLong("paymentTermDays") ?: 0L
                )
            }
            adapter.submitList(list)
        }
    }

    private fun openForm(s: Supplier?) {
        val f = DialogSupplierFormBinding.inflate(layoutInflater)
        if (s != null) {
            f.etName.setText(s.name)
            f.etPhone.setText(s.phone ?: "")
            f.etAddress.setText(s.address ?: "")
            if ((s.paymentTermDays) > 0) f.etTerm.setText(s.paymentTermDays.toString())
            f.swActive.isChecked = s.isActive
        } else {
            f.swActive.isChecked = true
        }

        val dlg = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (s == null) "Supplier Baru" else "Edit Supplier")
            .setView(f.root)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan", null)
            .create()
        dlg.setOnShowListener {
            val save = dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            save.setOnClickListener {
                listOf(f.tilName, f.tilPhone, f.tilAddress, f.tilTerm).forEach { it.error = null }
                val name = f.etName.text?.toString()?.trim().orEmpty()
                val phone = f.etPhone.text?.toString()?.trim().orEmpty()
                val addr = f.etAddress.text?.toString()?.trim().orEmpty()
                val termStr = f.etTerm.text?.toString()?.trim().orEmpty()
                val active = f.swActive.isChecked
                var ok = true
                if (name.isEmpty()) { f.tilName.error = "Wajib"; ok = false }
                if (phone.isEmpty()) { f.tilPhone.error = "Wajib"; ok = false }
                if (addr.isEmpty()) { f.tilAddress.error = "Wajib"; ok = false }
                val term = termStr.toLongOrNull()
                if (term == null || term < 0L) {
                    f.tilTerm.error = "Masukkan angka >= 0"
                    ok = false
                }
                if (!ok) { showRequiredAlert(); return@setOnClickListener }
                val termValue = term!!
                val now = FieldValue.serverTimestamp()
                if (s == null) {
                    val id = name.trim().lowercase().replace(Regex("""[^a-z0-9 -]"""), "").replace(Regex("""\s+"""), "-")
                    val ref = db.collection("suppliers").document(id)
                    ref.set(mapOf(
                        "name" to name,
                        "nameLowercase" to name.lowercase(),
                        "phone" to phone,
                        "address" to addr,
                        "isActive" to active,
                        "paymentTermDays" to termValue,
                        "createdAt" to now,
                        "updatedAt" to now
                    )).addOnSuccessListener { dlg.dismiss() }
                        .addOnFailureListener { e -> toast(e.message ?: "Gagal simpan") }
                } else {
                    db.collection("suppliers").document(s.id).update(mapOf(
                        "name" to name,
                        "nameLowercase" to name.lowercase(),
                        "phone" to phone,
                        "address" to addr,
                        "isActive" to active,
                        "paymentTermDays" to termValue,
                        "updatedAt" to now
                    )).addOnSuccessListener { dlg.dismiss() }
                        .addOnFailureListener { e -> toast(e.message ?: "Gagal simpan") }
                }

            }
        }
        dlg.show()
    }

    private fun showRequiredAlert() {
        if (requiredAlert?.isShowing == true) return
        requiredAlert = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Data belum lengkap")
            .setMessage("Harap isi semua kolom yang wajib diisi.")
            .setPositiveButton("OK", null)
            .create().apply {
                setOnDismissListener { requiredAlert = null }
                show()
            }
    }

    private fun confirmDelete(s: Supplier) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Hapus supplier?")
            .setMessage("Hapus ${s.name}? Data ini tidak dapat dikembalikan.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ ->
                db.collection("suppliers").document(s.id)
                    .delete()
                    .addOnSuccessListener { toast("Supplier dihapus") }
                    .addOnFailureListener { e -> toast(e.message ?: "Gagal menghapus supplier") }
            }
            .show()
    }
    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_LONG).show()
}

private class SupplierAdapter(
    val onEdit: (Supplier) -> Unit,
    val onDelete: (Supplier) -> Unit
) : ListAdapter<Supplier, SupplierAdapter.VH>(DIFF) {
    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Supplier>() {
            override fun areItemsTheSame(a: Supplier, b: Supplier) = a.id == b.id
            override fun areContentsTheSame(a: Supplier, b: Supplier) = a == b
        }
    }
    inner class VH(val b: ItemSupplierBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(s: Supplier) {
            b.tvTitle.text = s.name
            val info = buildString {
                if (!s.phone.isNullOrBlank()) {
                    append(s.phone)
                    append(" - ")
                }
                append("Term ")
                append(s.paymentTermDays)
                append(" hari")
            }
            b.tvSub.text = info
            val (statusLabel, statusBg, statusColor) = if (s.isActive) {
                Triple(
                    "Aktif",
                    R.drawable.bg_badge_green,
                    ContextCompat.getColor(b.root.context, android.R.color.white)
                )
            } else {
                Triple(
                    "Nonaktif",
                    R.drawable.bg_badge_gray,
                    MaterialColors.getColor(b.tvStatus, com.google.android.material.R.attr.colorOnSurface)
                )
            }
            b.tvStatus.text = statusLabel
            b.tvStatus.setBackgroundResource(statusBg)
            b.tvStatus.setTextColor(statusColor)
            b.root.setOnClickListener { onEdit(s) }
            b.btnDelete.setOnClickListener { onDelete(s) }
        }
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemSupplierBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))
}
