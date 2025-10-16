package com.example.pos_hma.ui.role.super_admin

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.pos_hma.R
import com.example.pos_hma.databinding.FragmentSuperAdminNotificationBinding
import com.example.pos_hma.databinding.ItemNotificationBinding
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.android.material.color.MaterialColors
import java.text.SimpleDateFormat
import java.util.Locale

class SuperAdminNotificationFragment : Fragment() {
    private var _b: FragmentSuperAdminNotificationBinding? = null
    private val b get() = _b!!
    private val db by lazy { FirebaseFirestore.getInstance() }

    private var reg: ListenerRegistration? = null
    private val adapter = NotifAdapter(onClick = { n -> onNotifClick(n) })

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentSuperAdminNotificationBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        b.rv.layoutManager = LinearLayoutManager(requireContext())
        b.rv.adapter = adapter
        startListen()
        b.swipeRefresh.setOnRefreshListener { refreshOnce() }
    }

    override fun onDestroyView() {
        reg?.remove(); reg = null
        _b = null
        super.onDestroyView()
    }

    private fun startListen() {
        reg?.remove(); reg = null
        reg = db.collection("notifications")
            .whereEqualTo("toRole", "super-admin")
            .addSnapshotListener { snap, e ->
                if (e != null) {
                    b.empty.visibility = View.VISIBLE
                    return@addSnapshotListener
                }
                var list = snap?.documents?.map { d ->
                    AppNotif(
                        id = d.id,
                        title = d.getString("title") ?: "",
                        message = d.getString("message") ?: "",
                        createdAt = d.getTimestamp("createdAt"),
                        read = d.getBoolean("read") ?: false,
                        type = d.getString("type")
                    )
                }.orEmpty()
                // Sort: unread first, then newest by createdAt
                list = list.sortedWith(
                    compareBy<AppNotif>({ if (it.read) 1 else 0 })
                        .thenByDescending { it.createdAt?.toDate()?.time ?: 0L }
                )
                adapter.submitList(list)
                if (list.isEmpty()) {
                    b.empty.text = getString(R.string.notification_empty_state)
                    b.empty.visibility = View.VISIBLE
                } else {
                    b.empty.text = ""
                    b.empty.visibility = View.GONE
                }
            }
    }

    private fun refreshOnce() {
        db.collection("notifications")
            .whereEqualTo("toRole", "super-admin")
            .get()
            .addOnSuccessListener { qs ->
                var list = qs.documents.map { d ->
                    AppNotif(
                        id = d.id,
                        title = d.getString("title") ?: "",
                        message = d.getString("message") ?: "",
                        createdAt = d.getTimestamp("createdAt"),
                        read = d.getBoolean("read") ?: false,
                        type = d.getString("type")
                    )
                }
                list = list.sortedWith(
                    compareBy<AppNotif>({ if (it.read) 1 else 0 })
                        .thenByDescending { it.createdAt?.toDate()?.time ?: 0L }
                )
                adapter.submitList(list)
                if (list.isEmpty()) {
                    b.empty.text = getString(R.string.notification_empty_state)
                    b.empty.visibility = View.VISIBLE
                } else {
                    b.empty.text = ""
                    b.empty.visibility = View.GONE
                }
            }
            .addOnCompleteListener { b.swipeRefresh.isRefreshing = false }
    }

    private fun onNotifClick(n: AppNotif) {
        // Tandai sebagai dibaca (update butuh role "superadmin/owner" sesuai rules)
        db.collection("notifications").document(n.id).update("read", true)

        // Arahkan sesuai tipe (opsional)
        when (n.type) {
            "ADJUSTMENT_REQUEST" -> {
                try { findNavController().navigate(R.id.superAdminAdjustRequestFragment) } catch (_: Throwable) {}
            }
            "PURCHASE_DUE" -> {
                // Belum ada layar detail purchase → arahkan ke Report agar Super Admin bisa cek
                try { findNavController().navigate(R.id.superAdminReportFragment) } catch (_: Throwable) {}
            }
        }
    }
}

data class AppNotif(
    val id: String,
    val title: String,
    val message: String,
    val createdAt: Timestamp?,
    val read: Boolean,
    val type: String?
)

private class NotifAdapter(
    val onClick: (AppNotif) -> Unit
) : ListAdapter<AppNotif, NotifVH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<AppNotif>() {
            override fun areItemsTheSame(a: AppNotif, b: AppNotif) = a.id == b.id
            override fun areContentsTheSame(a: AppNotif, b: AppNotif) = a == b
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NotifVH {
        val b = com.example.pos_hma.databinding.ItemNotificationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return NotifVH(b, onClick)
    }

    override fun onBindViewHolder(holder: NotifVH, position: Int) = holder.bind(getItem(position))
}

private class NotifVH(
    private val b: ItemNotificationBinding,
    val onClick: (AppNotif) -> Unit
) : RecyclerView.ViewHolder(b.root) {
    private val df = SimpleDateFormat("dd MMM HH:mm", Locale("in","ID"))
    fun bind(n: AppNotif) {
        b.tvTitle.text = n.title
        b.tvMessage.text = n.message
        b.tvTime.text = n.createdAt?.toDate()?.let { df.format(it) } ?: ""

        // Unread: white bg + visible dot; Read: transparent bg + hide dot
        val bgColor = if (n.read) Color.TRANSPARENT else Color.WHITE
        (b.root as com.google.android.material.card.MaterialCardView).setCardBackgroundColor(bgColor)
        b.vDot.visibility = if (n.read) View.GONE else View.VISIBLE

        // Ensure text contrast in dark theme
        if (n.read) {
            val onSurface = MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorOnSurface)
            b.tvTitle.setTextColor(onSurface)
            b.tvMessage.setTextColor(onSurface)
            b.tvTime.setTextColor(onSurface)
        } else {
            // Unread on white background: use dark text
            val primaryDark = Color.parseColor("#212121")
            val secondaryDark = Color.parseColor("#616161")
            b.tvTitle.setTextColor(primaryDark)
            b.tvMessage.setTextColor(secondaryDark)
            b.tvTime.setTextColor(secondaryDark)
        }

        b.root.setOnClickListener { onClick(n) }
    }
}
