package com.example.pos_hma.ui.role.admin

import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.pos_hma.R
import com.example.pos_hma.data.Product
import com.example.pos_hma.databinding.FragmentAdminCashierCartBinding
import com.example.pos_hma.databinding.ItemCartLineCashierBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

private val ID_LOCALE = java.util.Locale("in","ID")
private fun rupiah(v: Long) = java.text.NumberFormat.getInstance(ID_LOCALE).format(v)

class AdminCashierCartFragment : Fragment() {

    private var _binding: FragmentAdminCashierCartBinding? = null
    private val binding get() = _binding!!
    private val cartVm: CartViewModel by activityViewModels()
    private val adapter = CartLinesAdapter(
        onPlus = { cartVm.plus(it) },
        onMinus = { cartVm.minus(it) }
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentAdminCashierCartBinding.inflate(inflater, container, false)
        return binding.root
    }


    override fun onViewCreated(v: View, s: Bundle?) {
        binding.rv.layoutManager = LinearLayoutManager(requireContext())
        binding.rv.adapter = adapter

        cartVm.lines.observe(viewLifecycleOwner) { map ->
            adapter.submit(map.values.toList())
            binding.empty.visibility = if (map.isEmpty()) View.VISIBLE else View.GONE
        }
        cartVm.totalAmount.observe(viewLifecycleOwner) { amt ->
            binding.tvTotal.text = "Rp ${rupiah(amt)}"
        }

        binding.btnClear.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setMessage("Kosongkan keranjang?")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Kosongkan") { _, _ -> cartVm.clear() }
                .show()
        }
        binding.btnCheckout.setOnClickListener {
            val total = cartVm.totalAmount.value ?: 0L
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Lanjut ke pembayaran?")
                .setMessage("Total: Rp ${rupiah(total)}")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Ya") { _, _ ->
                    findNavController().navigate(R.id.adminCashierPaymentFragment)
                }
                .show()
        }
    }

    override fun onDestroyView() { _binding = null; super.onDestroyView() }
}

private class CartLinesAdapter(
    val onPlus: (Product) -> Unit,
    val onMinus: (Product) -> Unit
) : RecyclerView.Adapter<CartLinesAdapter.VH>() {

    private val lines = mutableListOf<CartLine>()
    fun submit(list: List<CartLine>) { lines.clear(); lines.addAll(list); notifyDataSetChanged() }

    override fun getItemCount() = lines.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemCartLineCashierBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b, onPlus, onMinus)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(lines[position])
    }

    class VH(
        private val b: ItemCartLineCashierBinding,
        val onPlus: (Product) -> Unit,
        val onMinus: (Product) -> Unit
    ) : RecyclerView.ViewHolder(b.root) {
        fun bind(l: CartLine) {
            val p = l.product
            b.img.load(p.images.firstOrNull() ?: R.drawable.store)
            b.tvName.text = p.name
            b.tvUnitPrice.text = if (p.isService) "-" else "Rp ${rupiah(p.salePrice)}"
            b.tvQty.text = l.qty.toString()
            b.tvLineSub.text = if (p.isService) "-" else "Rp ${rupiah(p.salePrice * l.qty)}"

            b.btnMinus.isEnabled = l.qty > 0
            b.btnPlus.isEnabled = if (p.isService) l.qty < 1 else l.qty < p.stock

            b.btnMinus.setOnClickListener { onMinus(p) }
            b.btnPlus.setOnClickListener { onPlus(p) }
        }
    }
}
