package com.lucas.nasdaqwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import android.widget.RemoteViews
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

class PortfolioWidgetProvider : AppWidgetProvider() {
    override fun onDisabled(context: Context) = work.cancel(context)

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        updateAll(context)
        work.schedule(context)
        work.refresh(context)
    }

    companion object {
        private const val GREEN = "#38F27A"
        private const val RED = "#FF6B6B"
        private val work = BrokerWidgetWork("portfolio_live_refresh", PortfolioRefreshWorker::class.java)
        private val reportFormat = DateTimeFormatter.ofPattern("dd/MM")
        private val quoteFormat = DateTimeFormatter.ofPattern("dd/MM HH:mm")

        fun refreshAndUpdate(context: Context) {
            updateAll(context)
            work.refresh(context)
        }

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PortfolioWidgetProvider::class.java))
            val hasKraken = BrokerConnectionStore.hasKraken(context)
            val hasIbkr = BrokerConnectionStore.hasIbkrSetup(context)
            val kraken = if (hasKraken) KrakenPortfolioRepository.cached(context) else null
            val ibkr = if (hasIbkr) IbkrFlexRepository.cached(context) else null
            val period = PortfolioTimeframeStore.get(context)
            val eur = NumberFormat.getCurrencyInstance(Locale.FRANCE)
            val compactEur = NumberFormat.getCurrencyInstance(Locale.FRANCE).apply { maximumFractionDigits = 0 }
            val krakenTotal = kraken?.availableTotalEur
            val total = listOfNotNull(krakenTotal, ibkr?.totalEur).takeIf { it.isNotEmpty() }?.sum()
            val totalLabel = when {
                total == null -> ""
                hasKraken && krakenTotal == null -> "IBKR seul"
                hasIbkr && ibkr == null && kraken?.unpricedAssets.isNullOrEmpty() -> "Kraken seul"
                !kraken?.unpricedAssets.isNullOrEmpty() -> "Total partiel"
                else -> ""
            }
            val krakenError = if (hasKraken) KrakenPortfolioRepository.lastError(context) else null
            val chart = ibkr?.takeIf { it.timeframe == period }?.chartValues.orEmpty()
            val krakenTime = kraken?.let { Instant.ofEpochMilli(it.updatedAt) }
            val krakenRecent = krakenTime != null && System.currentTimeMillis() - krakenTime.toEpochMilli() in 0..TimeUnit.MINUTES.toMillis(30)
            val krakenToday = krakenTime?.atZone(ZoneOffset.UTC)?.toLocalDate() == LocalDate.now(ZoneOffset.UTC)
            val showKrakenDay = period == PortfolioTimeframe.SESSION && krakenRecent && krakenToday
            val ranked = if (showKrakenDay) kraken?.positions.orEmpty().filter { it.dayChangePercent.isFinite() }.sortedByDescending { it.dayChangePercent } else emptyList()
            val krakenPercent = kraken?.dayChangePercent?.takeIf { !hasIbkr && showKrakenDay && it.isFinite() }
            val ibkrPercent = ibkr?.takeIf { !hasKraken && it.timeframe == period }?.periodReturnPercent

            ids.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.widget_portfolio)
                views.setTextViewText(R.id.portfolioTotal, total?.let(eur::format) ?: "— €")
                views.setTextViewText(R.id.portfolioTotalLabel, totalLabel)
                views.setViewVisibility(R.id.portfolioTotalLabel, if (totalLabel.isEmpty()) View.GONE else View.VISIBLE)
                val percent = ibkrPercent ?: krakenPercent
                views.setTextViewText(R.id.portfolioDayPercent, when {
                    ibkrPercent != null -> String.format(Locale.FRANCE, "%+.2f %% · IBKR", ibkrPercent)
                    krakenPercent != null -> String.format(Locale.FRANCE, "%+.2f %% · jour UTC", krakenPercent)
                    else -> "— % · ${period.label}"
                })
                views.setTextViewText(R.id.portfolioDayChange, if (krakenPercent != null) {
                    val change = kraken!!.dayChangeEur
                    if (change >= 0) "+${eur.format(change)}" else eur.format(change)
                } else "")
                if (percent != null) {
                    val color = Color.parseColor(if (percent >= 0) GREEN else RED)
                    views.setTextColor(R.id.portfolioDayChange, color)
                    views.setTextColor(R.id.portfolioDayPercent, color)
                }
                views.setViewVisibility(R.id.portfolioChartGroup, if (chart.size >= 2) View.VISIBLE else View.GONE)
                if (chart.size >= 2) {
                    views.setImageViewBitmap(R.id.portfolioChart, sparkline(chart, chart.last() >= chart.first()))
                }
                views.setViewVisibility(R.id.portfolioRankings, if (ranked.isNotEmpty()) View.VISIBLE else View.GONE)
                fill(views, listOf(R.id.portfolioTop1, R.id.portfolioTop2, R.id.portfolioTop3),
                    ranked.filter { it.dayChangePercent > 0 }, compactEur)
                fill(views, listOf(R.id.portfolioFlop1, R.id.portfolioFlop2, R.id.portfolioFlop3),
                    ranked.asReversed().filter { it.dayChangePercent < 0 }, compactEur)
                views.setTextViewText(R.id.portfolioKraken, when {
                    kraken != null && kraken.unpricedAssets.isNotEmpty() -> {
                        val part = krakenTotal?.let(eur::format)?.let { "$it · " }.orEmpty()
                        "Kraken · ${part}${kraken.unpricedAssets.size} actif(s) sans cours"
                    }
                    krakenTotal != null && krakenTime != null -> {
                        val time = krakenTime.atZone(ZoneId.systemDefault()).format(quoteFormat)
                        val stale = if (krakenRecent && krakenError == null) "" else " · cache"
                        "Kraken · ${eur.format(krakenTotal)} · $time$stale"
                    }
                    krakenError != null -> "Kraken · $krakenError"
                    hasKraken -> "Kraken · actualisation requise"
                    else -> "Kraken · toucher pour connecter"
                })
                views.setTextViewText(R.id.portfolioIbkr, when {
                    ibkr != null -> "IBKR · ${eur.format(ibkr.totalEur)} · relevé du ${ibkr.reportDate.format(reportFormat)}"
                    hasIbkr -> "IBKR · relevé indisponible"
                    else -> "IBKR · toucher pour connecter"
                })
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                val timeframeIntent = PendingIntent.getActivity(context, id, Intent(context, PortfolioTimeframeActivity::class.java), flags)
                val brokerIntent = PendingIntent.getActivity(context, id,
                    Intent(context, BrokerConnectionsActivity::class.java).putExtra("refresh_target", "portfolio"), flags)
                listOf(R.id.portfolioTotal, R.id.portfolioDayPercent, R.id.portfolioChart).forEach {
                    views.setOnClickPendingIntent(it, timeframeIntent)
                }
                views.setOnClickPendingIntent(R.id.portfolioKraken, brokerIntent)
                views.setOnClickPendingIntent(R.id.portfolioIbkr, brokerIntent)
                manager.updateAppWidget(id, views)
            }
        }

        private fun sparkline(values: List<Double>, positive: Boolean): Bitmap {
            val width = 560
            val height = 86
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val min = values.minOrNull() ?: return bitmap
            val range = (values.maxOrNull()!! - min).takeIf { it > 0.0001 } ?: 1.0
            val padding = 7f
            val path = Path()
            values.forEachIndexed { index, value ->
                val x = padding + (width - 2 * padding) * index / values.lastIndex
                val y = padding + (height - 2 * padding) * (1f - ((value - min) / range).toFloat())
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val color = Color.parseColor(if (positive) GREEN else RED)
            val area = Path(path).apply {
                lineTo(width - padding, height.toFloat())
                lineTo(padding, height.toFloat())
                close()
            }
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, 0f, 0f, height.toFloat(),
                    Color.argb(105, Color.red(color), Color.green(color), Color.blue(color)),
                    Color.TRANSPARENT, Shader.TileMode.CLAMP)
            }
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.STROKE
                strokeWidth = 4f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            return bitmap.also {
                Canvas(it).apply {
                    drawPath(area, fill)
                    drawPath(path, stroke)
                }
            }
        }

        private fun fill(views: RemoteViews, ids: List<Int>, positions: List<KrakenPositionSnapshot>, format: NumberFormat) {
            ids.forEachIndexed { index, id ->
                val position = positions.getOrNull(index)
                val label = position?.let {
                    val change = String.format(Locale.FRANCE, "%+.1f %%", it.dayChangePercent)
                    "${it.symbol} ${format.format(it.valueEur)} $change"
                }
                views.setTextViewText(id, label ?: "—")
            }
        }
    }
}
