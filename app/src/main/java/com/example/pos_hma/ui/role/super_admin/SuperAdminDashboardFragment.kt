package com.example.pos_hma.ui.role.super_admin

// Noted:
// Dashboard adalah beranda super admin. Halaman ini merangkum KPI penjualan, barang favorit, indikator stok, dan pintasan menuju
// modul penting lainnya sehingga pemilik toko bisa langsung melihat kesehatan bisnis saat membuka aplikasi.

// Class Note:
// - Mengambil data agregat (penjualan mingguan, top product, dsb) dari Firestore, kemudian memplot menggunakan MPAndroidChart (Bar/Line/Pie).
// - Menyediakan kartu aksi seperti “Laporan” atau “Permintaan” dengan status badge sehingga user tahu modul mana yang memerlukan perhatian.
// - Mengatur ulang tampilan ketika pengguna mengganti rentang tanggal atau menarik refresh, menjaga responsif dengan coroutines/snapshot listener.

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.example.pos_hma.R
import com.example.pos_hma.databinding.FragmentSuperAdminDashboardBinding
import com.example.pos_hma.ui.login.LoginActivity
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import com.github.mikephil.charting.formatter.PercentFormatter
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.utils.ColorTemplate
import com.google.android.material.color.MaterialColors
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.example.pos_hma.data.SaleStatus
import com.google.firebase.firestore.Query
import com.example.pos_hma.utils.toUserMessage
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// Class Note (Deklarasi):
// Bertugas mengikat layout dashboard, menjalankan query agregasi, lalu merender berbagai komponen (chart, kartu KPI, shortcut) agar super admin mendapat ringkasan bisnis.
class SuperAdminDashboardFragment : Fragment() {

    private var _binding: FragmentSuperAdminDashboardBinding? = null
    private val binding get() = _binding!!

    private val db by lazy { FirebaseFirestore.getInstance() }
    private val localeId = Locale("in", "ID")
    private val numberFormat by lazy { NumberFormat.getInstance(localeId) }
    private val dateFormatRange by lazy { SimpleDateFormat("dd MMM yyyy", localeId) }
    private val dateFormatUpdated by lazy { SimpleDateFormat("dd MMM yyyy HH:mm", localeId) }
    private val dateFormatDay by lazy { SimpleDateFormat("EEEE, dd MMM yyyy", localeId) }
    private var loadToken: Int = 0
    private var salesListener: ListenerRegistration? = null

    private enum class TimeRange(val days: Int, val chipId: Int, val label: String) {
        TODAY(1, R.id.chipRangeToday, "Hari Ini"),
        DAYS_7(7, R.id.chipRange7, "7 Hari Terakhir"),
        DAYS_30(30, R.id.chipRange30, "30 Hari Terakhir"),
        DAYS_90(90, R.id.chipRange90, "90 Hari Terakhir")
    }

    private var currentRange: TimeRange = TimeRange.TODAY

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentSuperAdminDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        ensureRoleAllowedOrExit(setOf("owner", "super-admin", "superadmin")) {
            _binding?.let { b ->
                val displayName = FirebaseAuth.getInstance().currentUser?.displayName
                b.tvSummary.text = buildString {
                    append("Halo")
                    if (!displayName.isNullOrBlank()) {
                        append(" ")
                        append(displayName.trim())
                    } else {
                        append(" Owner")
                    }
                    append("! Berikut ringkasan penjualan terbaru.")
                }
                setupRangeSelector()
                loadDashboard(currentRange)
            }
        }
    }

    private fun ensureRoleAllowedOrExit(allowed: Set<String>, onOk: () -> Unit) {
        val user = FirebaseAuth.getInstance().currentUser ?: return goLogin()
        user.getIdToken(false).addOnSuccessListener { tok ->
            val r = normalize(tok.claims["role"] as? String)
            if (r != null && allowed.contains(r)) {
                onOk()
            } else {
                FirebaseFirestore.getInstance().collection("users").document(user.uid).get()
                    .addOnSuccessListener { s ->
                        val rr = normalize(s.getString("role"))
                        if (rr != null && allowed.contains(rr)) onOk() else goLogin()
                    }.addOnFailureListener { goLogin() }
            }
        }.addOnFailureListener { goLogin() }
    }

    private fun normalize(r: String?) = r?.trim()?.lowercase()?.replace('_', '-')?.replace(' ', '-')

    private fun goLogin() {
        val ctx = context ?: return
        ctx.startActivity(Intent(ctx, LoginActivity::class.java))
        activity?.finish()
    }

    override fun onDestroyView() {
        salesListener?.remove(); salesListener = null
        _binding = null
        super.onDestroyView()
    }

    private fun setupRangeSelector() {
        val b = binding
        b.chipGroupRange.setOnCheckedStateChangeListener { _, checkedIds ->
            val selectedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val selected = TimeRange.values().firstOrNull { it.chipId == selectedId } ?: return@setOnCheckedStateChangeListener
            if (selected != currentRange) {
                currentRange = selected
                loadDashboard(selected)
            }
        }
        b.chipGroupRange.check(currentRange.chipId)
    }

    private fun loadDashboard(range: TimeRange) {
        val token = ++loadToken
        val b = _binding ?: return

        salesListener?.remove(); salesListener = null
        showSummaryLoading(range)
        showTrendLoading(range)
        showCompositionLoading(range)
        showTopProductsLoading(range)
        if (range == TimeRange.TODAY) {
            showTodaySummaryLoading()
        } else {
            b.cardTodaySummary.isVisible = false
        }

        val (start, endExclusive) = computeRange(range)
        salesListener = db.collection("sales")
            .whereGreaterThanOrEqualTo("createdAt", start)
            .whereLessThan("createdAt", endExclusive)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(500)
            .addSnapshotListener { snap, error ->
                if (token != loadToken) return@addSnapshotListener
                if (error != null) {
                    val message = error.toUserMessage("Gagal memuat data penjualan.")
                    showSummaryStatus(message)
                    showTrendStatus(message)
                    showCompositionStatus(message)
                    showTopProductsStatus(message)
                    if (range == TimeRange.TODAY) {
                        showTodaySummaryStatus(message)
                    } else {
                        b.cardTodaySummary.isVisible = false
                    }
                    return@addSnapshotListener
                }
                val docs = snap?.documents ?: emptyList()
                val analytics = aggregateAnalytics(docs, start, endExclusive)
                if (analytics.transactionCount == 0) {
                    showSummaryStatus("Belum ada transaksi pada periode ini.")
                    showTrendStatus("Belum ada data pendapatan untuk periode ini.")
                    showCompositionStatus("Belum ada data perbandingan pendapatan pada periode ini.")
                    showTopProductsStatus("Belum ada penjualan barang pada periode ini.")
                } else {
                    renderSummary(range, analytics)
                    renderTrend(range, analytics)
                    renderComposition(range, analytics)
                    renderTopProducts(analytics)
                }
                if (range == TimeRange.TODAY) {
                    if (analytics.todayTransactions == 0) {
                        showTodaySummaryStatus("Belum ada transaksi hari ini.")
                    } else {
                        renderTodaySummary(analytics)
                    }
                } else {
                    b.cardTodaySummary.isVisible = false
                }
            }
    }

    private fun computeRange(range: TimeRange): Pair<Date, Date> {
        val now = Calendar.getInstance()
        val endExclusive = (now.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.time
        val start = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -(range.days - 1))
        }.time
        return start to endExclusive
    }

    private fun showSummaryLoading(range: TimeRange) {
        val b = _binding ?: return
        b.tvRevenueSummarySubtitle.text = "Ringkasan periode ${range.label}"
        b.progressRevenueSummary.isVisible = true
        b.tvRevenueSummaryStatus.isVisible = false
        b.containerRevenueSummary.isVisible = false
    }

    private fun showSummaryStatus(message: String) {
        val b = _binding ?: return
        b.progressRevenueSummary.isVisible = false
        b.containerRevenueSummary.isVisible = false
        b.tvRevenueSummaryStatus.isVisible = true
        b.tvRevenueSummaryStatus.text = message
    }

    private fun showTodaySummaryLoading() {
        val b = _binding ?: return
        b.cardTodaySummary.isVisible = true
        b.progressTodaySummary.isVisible = true
        b.tvTodaySummaryStatus.isVisible = false
        b.containerTodaySummary.isVisible = false
        b.tvTodaySummarySubtitle.text = "Memuat ringkasan hari ini..."
    }

    private fun showTodaySummaryStatus(message: String) {
        val b = _binding ?: return
        b.cardTodaySummary.isVisible = true
        b.progressTodaySummary.isVisible = false
        b.containerTodaySummary.isVisible = false
        b.tvTodaySummaryStatus.isVisible = true
        b.tvTodaySummaryStatus.text = message
        val todayLabel = dateFormatDay.format(Date())
        b.tvTodaySummarySubtitle.text = "Periode: $todayLabel"
    }

    private fun renderTodaySummary(analytics: DashboardAnalytics) {
        val b = _binding ?: return
        b.cardTodaySummary.isVisible = true
        b.progressTodaySummary.isVisible = false
        b.tvTodaySummaryStatus.isVisible = false
        b.containerTodaySummary.isVisible = true
        val todayLabel = dateFormatDay.format(Date())
        b.tvTodaySummarySubtitle.text = "Periode: $todayLabel"
        b.tvTodayGross.text = formatCurrency(analytics.todayGoodsRevenue)
        b.tvTodayTransactions.text = numberFormat.format(analytics.todayTransactions)
        b.tvTodayService.text = formatCurrency(analytics.todayServiceRevenue)
        b.tvTodayNet.text = formatCurrency(analytics.todayNetProfit)
        b.tvTodayGoods.text = numberFormat.format(analytics.todayGoodsQuantity)
    }

    private fun renderSummary(range: TimeRange, analytics: DashboardAnalytics) {
        val b = _binding ?: return
        val ctx = requireContext()
        b.tvRevenueSummarySubtitle.text = "Ringkasan periode ${range.label}"
        b.progressRevenueSummary.isVisible = false
        b.tvRevenueSummaryStatus.isVisible = false
        b.containerRevenueSummary.isVisible = true

        b.tvRevenueGrossValue.text = formatCurrency(analytics.goodsRevenue)
        b.tvServiceRevenueValue.text = formatCurrency(analytics.serviceRevenue)
        b.tvRevenueCostValue.text = formatCurrency(analytics.totalCost)
        b.tvRevenueNetValue.text = formatCurrency(analytics.netProfit)

        val positiveColor = ContextCompat.getColor(ctx, R.color.green_success)
        val negativeColor = MaterialColors.getColor(b.cardRevenueSummary, com.google.android.material.R.attr.colorError)
        b.tvRevenueNetValue.setTextColor(if (analytics.netProfit >= 0L) positiveColor else negativeColor)

        b.tvRevenueSummaryOrders.text = "Total transaksi: ${numberFormat.format(analytics.transactionCount)}"
        val periodLabel = formatDateRange(analytics.start, analytics.end)
        val updated = dateFormatUpdated.format(analytics.generatedAt)
        b.tvRevenueSummaryUpdated.text = "Periode: $periodLabel\nTerakhir diperbarui: $updated"
    }

    private fun showTrendLoading(range: TimeRange) {
        val b = _binding ?: return
        b.tvRevenueTrendSubtitle.text = "Pergerakan omzet & jasa (${range.label})"
        b.progressRevenueTrend.isVisible = true
        b.tvRevenueTrendStatus.isVisible = false
        b.chartRevenueTrend.isVisible = false
    }

    private fun showTrendStatus(message: String) {
        val b = _binding ?: return
        b.progressRevenueTrend.isVisible = false
        b.chartRevenueTrend.clear()
        b.chartRevenueTrend.isVisible = false
        b.tvRevenueTrendStatus.isVisible = true
        b.tvRevenueTrendStatus.text = message
    }

    private fun renderTrend(range: TimeRange, analytics: DashboardAnalytics) {
        val b = _binding ?: return
        val daily = analytics.daily
        if (daily.isEmpty()) {
            showTrendStatus("Tidak ada data tanggal transaksi pada periode ini.")
            return
        }

        val ctx = requireContext()
        val chart = b.chartRevenueTrend
        val onSurfaceColor = MaterialColors.getColor(chart, com.google.android.material.R.attr.colorOnSurface)
        val outlineColor = MaterialColors.getColor(chart, com.google.android.material.R.attr.colorOutline)
        val revenueEntries = daily.mapIndexed { index, item -> Entry(index.toFloat(), item.goodsRevenue.toFloat()) }
        val serviceEntries = daily.mapIndexed { index, item -> Entry(index.toFloat(), item.serviceRevenue.toFloat()) }
        val profitEntries = daily.mapIndexed { index, item -> Entry(index.toFloat(), item.netProfit.toFloat()) }

        val revenueColor = MaterialColors.getColor(b.cardRevenueTrend, com.google.android.material.R.attr.colorPrimary)
        val serviceColor = MaterialColors.getColor(b.cardRevenueTrend, com.google.android.material.R.attr.colorSecondary)
        val profitColor = ContextCompat.getColor(ctx, R.color.green_success)

        val revenueSet = LineDataSet(revenueEntries, "Omzet Barang").apply {
            color = revenueColor
            setDrawCircles(false)
            lineWidth = 2.5f
            setDrawValues(false)
        }
        val serviceSet = LineDataSet(serviceEntries, "Pendapatan Jasa").apply {
            color = serviceColor
            setDrawCircles(false)
            enableDashedLine(8f, 6f, 0f)
            lineWidth = 2f
            setDrawValues(false)
        }
        val profitSet = LineDataSet(profitEntries, "Laba Bersih").apply {
            color = profitColor
            setCircleColor(profitColor)
            circleRadius = 3.5f
            lineWidth = 2f
            setDrawValues(false)
        }

        val dataSets = mutableListOf<ILineDataSet>(revenueSet)
        if (daily.any { it.serviceRevenue > 0L }) {
            dataSets += serviceSet
        }
        dataSets += profitSet

        chart.data = LineData(dataSets)
        chart.description.isEnabled = false
        chart.setNoDataText("Data pendapatan belum tersedia.")
        chart.setNoDataTextColor(onSurfaceColor)
        chart.axisRight.isEnabled = false
        chart.legend.isEnabled = true
        chart.legend.textColor = onSurfaceColor
        chart.setTouchEnabled(true)
        chart.isDragEnabled = true
        chart.setScaleEnabled(true)
        chart.setAutoScaleMinMaxEnabled(true)

        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(false)
            gridColor = outlineColor
            axisLineColor = outlineColor
            textColor = onSurfaceColor
            granularity = 1f
            axisMinimum = 0f
            axisMaximum = (daily.size - 1).coerceAtLeast(0).toFloat()
            valueFormatter = DayAxisFormatter(daily.map { it.label })
        }
        chart.axisLeft.apply {
            setDrawGridLines(true)
            gridColor = outlineColor
            axisLineColor = outlineColor
            textColor = onSurfaceColor
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String = numberFormat.format(value.toLong())
            }
        }
        chart.invalidate()

        b.progressRevenueTrend.isVisible = false
        b.tvRevenueTrendStatus.isVisible = false
        chart.isVisible = true
        b.tvRevenueTrendSubtitle.text = "Pergerakan omzet & jasa (${range.label})"
    }

    private fun showCompositionLoading(range: TimeRange) {
        val b = _binding ?: return
        b.tvRevenueCompositionSubtitle.text = "Perbandingan omzet & laba (${range.label})"
        b.progressRevenueComposition.isVisible = true
        b.tvRevenueCompositionStatus.isVisible = false
        b.chartRevenueComposition.isVisible = false
    }

    private fun showCompositionStatus(message: String) {
        val b = _binding ?: return
        b.progressRevenueComposition.isVisible = false
        b.chartRevenueComposition.clear()
        b.chartRevenueComposition.isVisible = false
        b.tvRevenueCompositionStatus.isVisible = true
        b.tvRevenueCompositionStatus.text = message
    }

    private fun renderComposition(range: TimeRange, analytics: DashboardAnalytics) {
        val b = _binding ?: return
        val chart = b.chartRevenueComposition
        val metrics = listOf(
            "Omzet Barang" to analytics.goodsRevenue,
            "Pendapatan Jasa" to analytics.serviceRevenue,
            "Total Modal" to analytics.totalCost,
            "Laba Bersih" to analytics.netProfit
        )
        if (metrics.all { it.second == 0L }) {
            showCompositionStatus("Belum ada data perbandingan pendapatan pada periode ini.")
            return
        }

        val entries = metrics.mapIndexed { index, (label, value) ->
            BarEntry(index.toFloat(), value.toFloat(), label)
        }
        val labels = metrics.map { it.first }
        val onSurfaceColor = MaterialColors.getColor(chart, com.google.android.material.R.attr.colorOnSurface)
        val outlineColor = MaterialColors.getColor(chart, com.google.android.material.R.attr.colorOutline)
        val palette = mutableListOf<Int>().apply {
            addAll(ColorTemplate.MATERIAL_COLORS.toList())
            addAll(ColorTemplate.COLORFUL_COLORS.toList())
            addAll(ColorTemplate.VORDIPLOM_COLORS.toList())
        }

        val dataSet = BarDataSet(entries, "").apply {
            colors = palette.take(entries.size)
            valueTextColor = onSurfaceColor
            valueTextSize = 12f
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String = formatCurrency(value.toLong())
            }
        }

        chart.data = BarData(dataSet).apply { barWidth = 0.6f }
        chart.description.isEnabled = false
        chart.setNoDataText("Data komposisi belum tersedia.")
        chart.setNoDataTextColor(onSurfaceColor)
        chart.legend.isEnabled = false
        chart.axisRight.isEnabled = false
        chart.setFitBars(true)
        chart.setDrawValueAboveBar(true)
        chart.animateY(600)

        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            textColor = onSurfaceColor
            setDrawGridLines(false)
            granularity = 1f
            axisMinimum = -0.5f
            axisMaximum = metrics.size - 0.5f
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    val index = value.roundToInt()
                    return labels.getOrNull(index) ?: ""
                }
            }
        }
        chart.axisLeft.apply {
            setDrawGridLines(true)
            gridColor = outlineColor
            axisLineColor = outlineColor
            textColor = onSurfaceColor
            setDrawZeroLine(true)
            zeroLineColor = outlineColor
            zeroLineWidth = 1f
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String = formatCurrency(value.toLong())
            }
        }

        chart.invalidate()

        b.progressRevenueComposition.isVisible = false
        b.tvRevenueCompositionStatus.isVisible = false
        chart.isVisible = true
        b.tvRevenueCompositionSubtitle.text = "Perbandingan omzet & laba (${range.label})"
    }

    private fun showTopProductsLoading(range: TimeRange) {
        val b = _binding ?: return
        b.tvTopProductsSubtitle.text = "Distribusi berdasarkan jumlah terjual (${range.label})"
        b.progressTopProducts.isVisible = true
        b.tvTopProductsStatus.isVisible = false
        b.chartTopProducts.isVisible = false
        b.containerTopProducts.isVisible = false
        b.tvTopProductsUpdated.isVisible = false
    }

    private fun showTopProductsStatus(message: String) {
        val b = _binding ?: return
        b.progressTopProducts.isVisible = false
        b.chartTopProducts.clear()
        b.chartTopProducts.isVisible = false
        b.containerTopProducts.isVisible = false
        b.tvTopProductsUpdated.isVisible = false
        b.tvTopProductsStatus.isVisible = true
        b.tvTopProductsStatus.text = message
    }

    private fun renderTopProducts(analytics: DashboardAnalytics) {
        val b = _binding ?: return
        val items = analytics.products
        if (items.isEmpty()) {
            val message = if (analytics.goodsQuantity <= 0L) {
                "Transaksi jasa tidak ditampilkan di chart ini. Belum ada penjualan barang fisik pada periode ini."
            } else {
                "Penjualan barang belum memiliki data yang dapat divisualisasikan."
            }
            showTopProductsStatus(message)
            return
        }

        val chart = b.chartTopProducts
        val top = items.take(5)
        val others = items.drop(5)
        val entries = mutableListOf<PieEntry>()
        top.forEach { agg -> entries += PieEntry(agg.quantity.toFloat(), agg.name) }
        val othersQty = others.sumOf { it.quantity }
        if (othersQty > 0L) {
            entries += PieEntry(othersQty.toFloat(), "Lainnya")
        }

        val onSurfaceColor = MaterialColors.getColor(chart, com.google.android.material.R.attr.colorOnSurface)
        val dataSet = PieDataSet(entries, "").apply {
            val colors = mutableListOf<Int>().apply {
                addAll(ColorTemplate.MATERIAL_COLORS.toList())
                addAll(ColorTemplate.COLORFUL_COLORS.toList())
                addAll(ColorTemplate.VORDIPLOM_COLORS.toList())
            }
            this.colors = colors.take(entries.size)
            sliceSpace = 2f
            valueTextSize = 12f
            valueTextColor = onSurfaceColor
        }

        chart.setUsePercentValues(true)
        chart.description.isEnabled = false
        chart.legend.isWordWrapEnabled = true
        chart.legend.isEnabled = true
        chart.legend.textColor = onSurfaceColor
        chart.setDrawEntryLabels(false)
        chart.setNoDataText("Data penjualan belum tersedia.")
        chart.setNoDataTextColor(onSurfaceColor)

        val pieData = PieData(dataSet).apply {
            setValueFormatter(PercentFormatter(chart))
            setValueTextColor(onSurfaceColor)
            setValueTextSize(12f)
        }
        chart.data = pieData
        chart.setEntryLabelColor(onSurfaceColor)
        chart.invalidate()

        b.containerTopProducts.removeAllViews()
        top.forEachIndexed { idx, agg ->
            b.containerTopProducts.addView(buildProductRow(idx, agg))
        }
        if (othersQty > 0L) {
            val othersRevenue = others.sumOf { it.revenue }
            b.containerTopProducts.addView(buildOthersRow(othersQty, othersRevenue))
        }

        b.progressTopProducts.isVisible = false
        b.tvTopProductsStatus.isVisible = false
        chart.isVisible = true
        b.containerTopProducts.isVisible = true
        b.tvTopProductsUpdated.isVisible = true

        val periodLabel = formatDateRange(analytics.start, analytics.end)
        val updated = dateFormatUpdated.format(analytics.generatedAt)
        b.tvTopProductsUpdated.text = "Periode: $periodLabel\nTerakhir diperbarui: $updated"
    }

    private fun buildProductRow(position: Int, agg: AggregatedProduct): View {
        val ctx = requireContext()
        val paddingVertical = if (position == 0) 0 else dpToPx(8)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, paddingVertical, 0, 0)

            addView(TextView(ctx).apply {
                text = "#${position + 1} ${agg.name}"
                setTypeface(null, Typeface.BOLD)
                textSize = 16f
            })

            addView(TextView(ctx).apply {
                val qtyLabel = "${numberFormat.format(agg.quantity)} unit"
                val revenueLabel = formatCurrency(agg.revenue)
                text = "$qtyLabel | $revenueLabel"
                setTextColor(ContextCompat.getColor(ctx, com.google.android.material.R.color.material_on_surface_emphasis_medium))
                textSize = 13f
            })
        }
    }

    private fun buildOthersRow(quantity: Long, revenue: Long): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dpToPx(12), 0, 0)

            addView(TextView(ctx).apply {
                text = "Lainnya"
                setTypeface(null, Typeface.BOLD)
                textSize = 15f
            })

            addView(TextView(ctx).apply {
                val qtyLabel = "${numberFormat.format(quantity)} unit"
                val revenueLabel = formatCurrency(revenue)
                text = "$qtyLabel | $revenueLabel"
                setTextColor(ContextCompat.getColor(ctx, com.google.android.material.R.color.material_on_surface_emphasis_medium))
                textSize = 13f
            })
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).roundToInt()
    }

    private fun formatCurrency(value: Long): String = "Rp ${numberFormat.format(value)}"

    private fun formatDateRange(start: Date, end: Date): String {
        val startLabel = dateFormatRange.format(start)
        val endLabel = dateFormatRange.format(end)
        return if (startLabel == endLabel) startLabel else "$startLabel - $endLabel"
    }

    private fun aggregateAnalytics(docs: List<DocumentSnapshot>, start: Date, endExclusive: Date): DashboardAnalytics {
        val aggregated = mutableMapOf<String, AggregatedProduct>()
        val dailyMap = mutableMapOf<String, DailyAggregateMutable>()
        val dayKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dayLabelFormat = SimpleDateFormat("dd MMM", localeId)

        var totalRevenue = 0L
        var goodsRevenue = 0L
        var serviceRevenue = 0L
        var totalCost = 0L
        var goodsQuantity = 0L
        var transactionCount = 0
        val todayStart = normalizeDay(Date())
        val todayEndExclusive = Date(todayStart.time + 24 * 60 * 60 * 1000L)
        var todayGoodsRevenue = 0L
        var todayServiceRevenue = 0L
        var todayTotalRevenue = 0L
        var todayCost = 0L
        var todayGoodsQuantity = 0L
        var todayTransactions = 0

        for (doc in docs) {
            val status = doc.getString("status") ?: SaleStatus.PAID
            if (!status.equals(SaleStatus.PAID, ignoreCase = true)) continue
            val createdAt = doc.getTimestamp("createdAt")?.toDate() ?: continue
            transactionCount++
            val total = doc.getLong("total") ?: 0L
            val serviceFee = doc.getLong("serviceFee") ?: 0L

            val items = (doc.get("items") as? List<Map<String, Any?>>).orEmpty()
            var saleCost = 0L
            var goodsInSale = 0L
            var saleGoodsRevenue = 0L
            var saleServiceRevenue = serviceFee

            for (item in items) {
                val qty = (item["qty"] as? Number)?.toLong() ?: 0L
                if (qty <= 0L) continue
                val unitPrice = (item["unitPrice"] as? Number)?.toLong() ?: 0L
                val lineRevenue = qty * unitPrice
                val isService = (item["isService"] as? Boolean) ?: false
                if (!isService) {
                    val sku = (item["sku"] as? String).orEmpty()
                    val nameRaw = (item["name"] as? String).orEmpty()
                    val name = if (nameRaw.isBlank()) sku else nameRaw
                    val key = sku.ifBlank { name.lowercase(localeId) }
                    val agg = aggregated.getOrPut(key) { AggregatedProduct(sku = sku, name = name, quantity = 0L, revenue = 0L) }
                    agg.quantity += qty
                    agg.revenue += lineRevenue
                    if (agg.name.isBlank() && name.isNotBlank()) agg.name = name
                    val unitCost = (item["unitCost"] as? Number)?.toLong() ?: 0L
                    saleCost += qty * unitCost
                    goodsQuantity += qty
                    goodsInSale += qty
                    saleGoodsRevenue += lineRevenue
                } else {
                    saleServiceRevenue += lineRevenue
                }
            }

            totalRevenue += total
            goodsRevenue += saleGoodsRevenue
            serviceRevenue += saleServiceRevenue
            totalCost += saleCost

            val dayKey = dayKeyFormat.format(createdAt)
            val bucket = dailyMap.getOrPut(dayKey) {
                DailyAggregateMutable(
                    date = normalizeDay(createdAt),
                    label = dayLabelFormat.format(createdAt),
                    goodsRevenue = 0L,
                    serviceRevenue = 0L,
                    totalRevenue = 0L,
                    netProfit = 0L
                )
            }
            bucket.goodsRevenue += saleGoodsRevenue
            bucket.serviceRevenue += saleServiceRevenue
            bucket.totalRevenue += total
            bucket.netProfit += (total - saleCost)

            if (createdAt >= todayStart && createdAt < todayEndExclusive) {
                todayTransactions += 1
                todayGoodsRevenue += saleGoodsRevenue
                todayServiceRevenue += saleServiceRevenue
                todayTotalRevenue += total
                todayCost += saleCost
                todayGoodsQuantity += goodsInSale
            }
        }

        val sortedProducts = aggregated.values.sortedWith(
            compareByDescending<AggregatedProduct> { it.quantity }.thenByDescending { it.revenue }
        )
        val daily = dailyMap.values.sortedBy { it.date }.map {
            DailyAggregate(
                date = it.date,
                label = it.label,
                goodsRevenue = it.goodsRevenue,
                serviceRevenue = it.serviceRevenue,
                totalRevenue = it.totalRevenue,
                netProfit = it.netProfit
            )
        }
        val netProfit = totalRevenue - totalCost
        val generatedAt = Date()
        val endInclusive = Date(endExclusive.time - 1L)
        val todayNetProfit = todayTotalRevenue - todayCost

        return DashboardAnalytics(
            start = start,
            end = endInclusive,
            generatedAt = generatedAt,
            products = sortedProducts,
            daily = daily,
            goodsRevenue = goodsRevenue,
            serviceRevenue = serviceRevenue,
            totalRevenue = totalRevenue,
            totalCost = totalCost,
            netProfit = netProfit,
            transactionCount = transactionCount,
            goodsQuantity = goodsQuantity,
            todayGoodsRevenue = todayGoodsRevenue,
            todayServiceRevenue = todayServiceRevenue,
            todayTotalRevenue = todayTotalRevenue,
            todayNetProfit = todayNetProfit,
            todayTransactions = todayTransactions,
            todayGoodsQuantity = todayGoodsQuantity
        )
    }

    private fun normalizeDay(date: Date): Date {
        val cal = Calendar.getInstance()
        cal.time = date
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.time
    }

    private class DayAxisFormatter(private val labels: List<String>) : ValueFormatter() {
        override fun getFormattedValue(value: Float): String {
            if (labels.isEmpty()) return ""
            val index = value.roundToInt().coerceIn(0, labels.lastIndex)
            return labels[index]
        }
    }

    private data class AggregatedProduct(
        val sku: String,
        var name: String,
        var quantity: Long,
        var revenue: Long
    )

    private data class DailyAggregate(
        val date: Date,
        val label: String,
        val goodsRevenue: Long,
        val serviceRevenue: Long,
        val totalRevenue: Long,
        val netProfit: Long
    )

    private data class DailyAggregateMutable(
        val date: Date,
        val label: String,
        var goodsRevenue: Long,
        var serviceRevenue: Long,
        var totalRevenue: Long,
        var netProfit: Long
    )

    private data class DashboardAnalytics(
        val start: Date,
        val end: Date,
        val generatedAt: Date,
        val products: List<AggregatedProduct>,
        val daily: List<DailyAggregate>,
        val goodsRevenue: Long,
        val serviceRevenue: Long,
        val totalRevenue: Long,
        val totalCost: Long,
        val netProfit: Long,
        val transactionCount: Int,
        val goodsQuantity: Long,
        val todayGoodsRevenue: Long,
        val todayServiceRevenue: Long,
        val todayTotalRevenue: Long,
        val todayNetProfit: Long,
        val todayTransactions: Int,
        val todayGoodsQuantity: Long
    )
}
