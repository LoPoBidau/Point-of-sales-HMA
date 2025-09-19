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
    internal val cartVm: CartViewModel by activityViewModels()
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
            val hasService = (cartVm.serviceFee.value ?: 0L) > 0L
            val showEmpty = map.isEmpty() && !hasService
            binding.empty.visibility = if (showEmpty) View.VISIBLE else View.GONE
            updateTotalAndButton()
        }
        cartVm.goodsSubTotal.observe(viewLifecycleOwner) { _ -> updateTotalAndButton() }

        cartVm.serviceFee.observe(viewLifecycleOwner) { fee ->
            val hasGoods = (cartVm.lines.value?.isNotEmpty() == true)
            if (fee != null && fee > 0) {
                binding.tvServiceFee.visibility = View.VISIBLE
                val label = if (hasGoods) "Tambahan Biaya Service" else "Biaya Service"
                binding.tvServiceFee.text = "$label: Rp ${rupiah(fee)}"
                binding.btnServiceFee.text = "Ubah harga service"
            } else {
                binding.tvServiceFee.visibility = View.GONE
                binding.btnServiceFee.text = "Tambah Service"
            }
            // Hide empty-state if only service is present
            val map = cartVm.lines.value ?: emptyMap()
            val hasService = (fee ?: 0L) > 0L
            val showEmpty = map.isEmpty() && !hasService
            binding.empty.visibility = if (showEmpty) View.VISIBLE else View.GONE
            updateTotalAndButton()
        }

        binding.btnClear.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setMessage("Kosongkan keranjang?")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Kosongkan") { _, _ -> cartVm.clear() }
                .show()
        }
        binding.btnCheckout.setOnClickListener {
            val goods = cartVm.goodsSubTotal.value ?: 0L
            val fee = cartVm.serviceFee.value ?: 0L
            val total = goods + fee
            val hasGoods = (cartVm.lines.value?.isNotEmpty() == true)
            val lines = buildString {
                appendLine("Sub Total: Rp ${rupiah(goods)}")
                if (fee > 0) appendLine("${if (hasGoods) "Tambahan Biaya Service" else "Biaya Service"}: Rp ${rupiah(fee)}")
                append("Total Bayar: Rp ${rupiah(total)}")
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Lanjut ke pembayaran?")
                .setMessage(lines)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Ya") { _, _ ->
                    findNavController().navigate(R.id.adminCashierPaymentFragment)
                }
                .show()
        }

        binding.btnServiceFee.setOnClickListener { showServiceFeeDialog() }
    }

    override fun onDestroyView() { _binding = null; super.onDestroyView() }

    private fun updateTotalAndButton() {
        val goods = cartVm.goodsSubTotal.value ?: 0L
        val fee = cartVm.serviceFee.value ?: 0L
        // Sub Total pada kartu = barang + service
        val totalForCard = goods + fee
        binding.tvTotal.text = "Rp ${rupiah(totalForCard)}"

        val hasLines = (cartVm.lines.value?.isNotEmpty() == true)
        val canPay = hasLines || fee > 0L
        binding.btnCheckout.isEnabled = canPay
        binding.btnCheckout.alpha = if (canPay) 1f else 0.5f
    }
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
            b.tvUnitPrice.text = "Unit: Rp ${rupiah(p.salePrice)}"
            b.tvQty.text = l.qty.toString()
            b.tvLineSub.text = "Total: Rp ${rupiah(p.salePrice * l.qty)}"

            b.btnMinus.isEnabled = l.qty > 0
            b.btnPlus.isEnabled = if (p.trackStock) l.qty < p.stock else true

            b.btnMinus.setOnClickListener { onMinus(p) }
            b.btnPlus.setOnClickListener { if (!p.trackStock || l.qty < p.stock) onPlus(p) }
        }
    }
}

private fun AdminCashierCartFragment.showServiceFeeDialog() {
    val ctx = requireContext()
    val view = layoutInflater.inflate(R.layout.dialog_service_fee, null, false)
    val et = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etServiceFee)
    val current = cartVm.serviceFee.value ?: 0L
    if (current > 0) et.setText(java.text.NumberFormat.getInstance(ID_LOCALE).format(current))

    // Format ribuan (1.000, 10.000, dst) saat mengetik
    var editing = false
    et.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            if (editing) return
            editing = true
            val raw = s?.toString().orEmpty()
            // Ambil angka saja
            val digits = raw.replace("[^\\d]".toRegex(), "")
            if (digits.isEmpty()) {
                et.setText("")
                editing = false
                return
            }
            val v = digits.toLongOrNull() ?: 0L
            val formatted = java.text.NumberFormat.getInstance(ID_LOCALE).format(v)
            if (formatted != raw) {
                et.setText(formatted)
                et.setSelection(formatted.length)
            }
            editing = false
        }
    })

    MaterialAlertDialogBuilder(ctx)
        .setTitle("Biaya Service")
        .setView(view)
        .setNegativeButton("Batal", null)
        .setPositiveButton("Simpan") { _, _ ->
            val s = et.text?.toString()?.trim().orEmpty()
            val digits = s.replace("[^\\d]".toRegex(), "")
            val v = digits.toLongOrNull() ?: 0L
            cartVm.setServiceFee(v)
        }
        .show()
}
