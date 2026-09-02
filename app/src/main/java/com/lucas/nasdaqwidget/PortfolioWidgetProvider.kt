package com.lucas.nasdaqwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

private data class PortfolioDisplayPosition(val symbol: String, val valueEur: Double, val changePercent: Double)

class PortfolioWidgetProvider : AppWidgetProvider() {
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        val request = PeriodicWorkRequestBuilder<PortfolioRefreshWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("portfolio_live_refresh", ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    override fun onDisabled(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork("portfolio_live_refresh")
        super.onDisabled(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAll(context, appWidgetManager, appWidgetIds)
        refreshAndUpdate(context)
    }

    companion object {
        private const val GREEN = "#38F27A"
        private const val RED = "#FF6B6B"

        fun refreshAndUpdate(context: Context) {
            Thread {
                val timeframe = PortfolioTimeframeStore.get(context)
                if (BrokerConnectionStore.isKrakenVerified(context)) runCatching { KrakenPortfolioRepository.refresh(context, timeframe) }
                if (BrokerConnectionStore.hasIbkrSetup(context)) runCatching { IbkrFlexRepository.refresh(context, timeframe) }
                updateAll(context)
            }.start()
        }

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(android.content.ComponentName(context, PortfolioWidgetProvider::class.java))
            updateAll(context, manager, ids)
        }

        private fun updateAll(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val kraken = KrakenPortfolioRepository.cached(context)
            val ibkr = IbkrFlexRepository.cached(context)
            val timeframe = PortfolioTimeframeStore.get(context)
            val eur = NumberFormat.getCurrencyInstance(Locale.FRANCE)
            val eur0 = NumberFormat.getCurrencyInstance(Locale.FRANCE).apply { maximumFractionDigits = 0 }
            val total = (kraken?.totalEur ?: 0.0) + (ibkr?.totalEur ?: 0.0)
            val change = (kraken?.dayChangeEur ?: 0.0) + (ibkr?.periodChangeEur ?: 0.0)
            val previous = total - change
            val percent = if (previous > 0) change / previous * 100.0 else 0.0
            val positions = buildList {
                kraken?.positions?.forEach { add(PortfolioDisplayPosition(it.symbol, it.valueEur, it.dayChangePercent)) }
                ibkr?.positions?.forEach { add(PortfolioDisplayPosition(it.symbol, it.valueEur, it.periodChangePercent)) }
            }
            val hasSnapshot = kraken != null || ibkr != null

            ids.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.widget_portfolio)
                views.setTextViewText(R.id.portfolioTotal, if (hasSnapshot) eur.format(total) else "— €")
                if (hasSnapshot) {
                    views.setTextViewText(R.id.portfolioDayChange, signedMoney(change, eur))
                    views.setTextViewText(R.id.portfolioDayPercent, String.format(Locale.FRANCE, "%+.2f %% · %s", percent, timeframe.label))
                    val color = Color.parseColor(if (change >= 0) GREEN else RED)
                    views.setTextColor(R.id.portfolioDayChange, color)
                    views.setTextColor(R.id.portfolioDayPercent, color)
                    fillRanking(views, listOf(R.id.portfolioTop1, R.id.portfolioTop2, R.id.portfolioTop3), positions.sortedByDescending { it.changePercent }.take(3), eur0)
                    fillRanking(views, listOf(R.id.portfolioFlop1, R.id.portfolioFlop2, R.id.portfolioFlop3), positions.sortedBy { it.changePercent }.take(3), eur0)
                } else {
                    views.setTextViewText(R.id.portfolioDayChange, "— €")
                    views.setTextViewText(R.id.portfolioDayPercent, "— % · ${timeframe.label}")
                    listOf(R.id.portfolioTop1, R.id.portfolioTop2, R.id.portfolioTop3, R.id.portfolioFlop1, R.id.portfolioFlop2, R.id.portfolioFlop3).forEach { views.setTextViewText(it, "—") }
                }

                views.setTextViewText(R.id.portfolioKraken, when {
                    kraken != null -> "Kraken · ${eur.format(kraken.totalEur)}"
                    BrokerConnectionStore.hasKraken(context) -> "Kraken · connexion à vérifier"
                    else -> "Kraken · toucher pour connecter"
                })
                views.setTextViewText(R.id.portfolioIbkr, when {
                    ibkr != null -> "IBKR · ${eur.format(ibkr.totalEur)}"
                    BrokerConnectionStore.hasIbkrSetup(context) -> "IBKR · connexion à vérifier"
                    else -> "IBKR · toucher pour connecter"
                })

                val timeframeIntent = Intent(context, PortfolioTimeframeActivity::class.java)
                val timeframePending = PendingIntent.getActivity(context, id + 20_000, timeframeIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(R.id.portfolioTotal, timeframePending)
                views.setOnClickPendingIntent(R.id.portfolioDayChange, timeframePending)
                views.setOnClickPendingIntent(R.id.portfolioDayPercent, timeframePending)

                val brokerIntent = Intent(context, BrokerConnectionsActivity::class.java)
                val brokerPending = PendingIntent.getActivity(context, id, brokerIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(R.id.portfolioIbkr, brokerPending)
                views.setOnClickPendingIntent(R.id.portfolioKraken, brokerPending)
                manager.updateAppWidget(id, views)
            }
        }

        private fun fillRanking(views: RemoteViews, ids: List<Int>, positions: List<PortfolioDisplayPosition>, eur0: NumberFormat) {
            ids.forEachIndexed { index, viewId ->
                val position = positions.getOrNull(index)
                if (position == null) views.setTextViewText(viewId, "—") else {
                    views.setTextViewText(viewId, "${position.symbol} ${eur0.format(position.valueEur)} ${String.format(Locale.FRANCE, "%+.1f%%", position.changePercent)}")
                    views.setTextColor(viewId, Color.parseColor(if (position.changePercent >= 0) GREEN else RED))
                }
            }
        }

        private fun signedMoney(value: Double, format: NumberFormat): String {
            val raw = format.format(kotlin.math.abs(value))
            return if (value >= 0) "+$raw" else "−$raw"
        }
    }
}
