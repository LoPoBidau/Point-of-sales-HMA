package com.example.pos_hma.ui.role.admin

import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

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
            refreshServiceSection()
            updateTotalAndButton()
        }
        cartVm.goodsSubTotal.observe(viewLifecycleOwner) { _ -> updateTotalAndButton() }
        cartVm.serviceFee.observe(viewLifecycleOwner) { _ ->
            refreshServiceSection()
            updateTotalAndButton()
        }
        cartVm.serviceDescription.observe(viewLifecycleOwner) { _ ->
            refreshServiceSection()
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
            val desc = cartVm.serviceDescription.value.orEmpty()
            val lines = buildString {
                appendLine("Subtotal Barang: Rp ${rupiah(goods)}")
                if (fee > 0) {
                    val labelBase = if (hasGoods) "Tambahan Biaya Jasa" else "Biaya Jasa"
                    val label = if (desc.isBlank()) labelBase else "$labelBase ($desc)"
                    appendLine("$label: Rp ${rupiah(fee)}")
                }
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

    private fun refreshServiceSection() {
        val fee = cartVm.serviceFee.value ?: 0L
        val desc = cartVm.serviceDescription.value.orEmpty()
        val hasGoods = (cartVm.lines.value?.isNotEmpty() == true)
        val hasService = fee > 0L

        if (hasService) {
            binding.tvServiceFee.visibility = View.VISIBLE
            val labelBase = if (hasGoods) "Tambahan Biaya Jasa" else "Biaya Jasa"
            val label = if (desc.isBlank()) labelBase else "$labelBase ($desc)"
            binding.tvServiceFee.text = "$label: Rp ${rupiah(fee)}"
            binding.btnServiceFee.text = "Ubah jasa"
        } else {
            binding.tvServiceFee.visibility = View.GONE
            binding.btnServiceFee.text = "Tambah Jasa"
        }

        val linesEmpty = cartVm.lines.value?.isEmpty() == true
        binding.empty.visibility = if (linesEmpty && !hasService) View.VISIBLE else View.GONE
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
            val firstImage = p.images.firstOrNull()
            if (firstImage.isNullOrBlank()) {
                b.img.setImageResource(R.drawable.ic_product_placeholder)
                b.img.alpha = 0.65f
            } else {
                b.img.alpha = 1f
                b.img.load(firstImage)
            }
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
    val tilFee = view.findViewById<TextInputLayout>(R.id.tilServiceFee)
    val etFee = view.findViewById<TextInputEditText>(R.id.etServiceFee)
    val tilDesc = view.findViewById<TextInputLayout>(R.id.tilServiceDescription)
    val etDesc = view.findViewById<TextInputEditText>(R.id.etServiceDescription)
    val currentFee = cartVm.serviceFee.value ?: 0L
    val formatter = java.text.NumberFormat.getInstance(ID_LOCALE)
    if (currentFee > 0) {
        etFee.setText(formatter.format(currentFee))
    }
    val currentDesc = cartVm.serviceDescription.value.orEmpty()
    if (currentDesc.isNotBlank()) {
        etDesc.setText(currentDesc)
        etDesc.setSelection(currentDesc.length)
    }

    // Format ribuan (1.000, 10.000, dst) saat mengetik
    var editing = false
    etFee.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            if (editing) return
            editing = true
            tilFee.error = null
            val raw = s?.toString().orEmpty()
            // Ambil angka saja
            val digits = raw.replace("[^\\d]".toRegex(), "")
            if (digits.isEmpty()) {
                etFee.setText("")
                editing = false
                return
            }
            val v = digits.toLongOrNull() ?: 0L
            val formatted = formatter.format(v)
            if (formatted != raw) {
                etFee.setText(formatted)
                etFee.setSelection(formatted.length)
            }
            editing = false
        }
    })

    etDesc.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            tilDesc.error = null
        }
    })

    val dialog = MaterialAlertDialogBuilder(ctx)
        .setTitle("Biaya Jasa")
        .setView(view)
        .setNegativeButton("Batal", null)
        .setPositiveButton("Simpan", null)
        .create()

    dialog.setOnShowListener {
        val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        positive.setOnClickListener {
            tilFee.error = null
            tilDesc.error = null

            val amountRaw = etFee.text?.toString().orEmpty()
            val amountDigits = amountRaw.replace("[^\\d]".toRegex(), "")
            val amount = amountDigits.toLongOrNull() ?: 0L
            val desc = etDesc.text?.toString()?.trim().orEmpty()

            if (amount > 0L && desc.isBlank()) {
                tilDesc.error = "Deskripsi jasa wajib diisi ketika menambahkan jasa"
                return@setOnClickListener
            }

            if (amount > 0L) {
                cartVm.setServiceFee(amount)
                cartVm.setServiceDescription(desc)
            } else {
                cartVm.clearServiceFee()
            }
            dialog.dismiss()
        }
    }

    dialog.show()
}
