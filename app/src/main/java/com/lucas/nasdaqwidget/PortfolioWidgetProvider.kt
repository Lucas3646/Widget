package com.lucas.nasdaqwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import java.text.NumberFormat
import java.util.Locale

class PortfolioWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAll(context, appWidgetManager, appWidgetIds)
        if (BrokerConnectionStore.isKrakenVerified(context)) {
            Thread {
                runCatching { KrakenPortfolioRepository.refresh(context) }
                updateAll(context)
            }.start()
        }
    }

    companion object {
        private const val GREEN = "#38F27A"
        private const val RED = "#FF6B6B"

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(android.content.ComponentName(context, PortfolioWidgetProvider::class.java))
            updateAll(context, manager, ids)
        }

        private fun updateAll(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val snapshot = KrakenPortfolioRepository.cached(context)
            val eur = NumberFormat.getCurrencyInstance(Locale.FRANCE)
            val eur0 = NumberFormat.getCurrencyInstance(Locale.FRANCE).apply { maximumFractionDigits = 0 }
            ids.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.widget_portfolio)
                views.setTextViewText(R.id.portfolioTotal, snapshot?.let { eur.format(it.totalEur) } ?: "— €")

                if (snapshot != null) {
                    val positive = snapshot.dayChangeEur >= 0
                    views.setTextViewText(R.id.portfolioDayChange, signedMoney(snapshot.dayChangeEur, eur))
                    views.setTextViewText(R.id.portfolioDayPercent, String.format(Locale.FRANCE, "%+.2f %% aujourd’hui", snapshot.dayChangePercent))
                    val color = Color.parseColor(if (positive) GREEN else RED)
                    views.setTextColor(R.id.portfolioDayChange, color)
                    views.setTextColor(R.id.portfolioDayPercent, color)

                    val top = snapshot.positions.sortedByDescending { it.dayChangePercent }.take(3)
                    val flop = snapshot.positions.sortedBy { it.dayChangePercent }.take(3)
                    fillRanking(views, listOf(R.id.portfolioTop1, R.id.portfolioTop2, R.id.portfolioTop3), top, eur0)
                    fillRanking(views, listOf(R.id.portfolioFlop1, R.id.portfolioFlop2, R.id.portfolioFlop3), flop, eur0)
                } else {
                    views.setTextViewText(R.id.portfolioDayChange, "— €")
                    views.setTextViewText(R.id.portfolioDayPercent, "— % aujourd’hui")
                    listOf(R.id.portfolioTop1, R.id.portfolioTop2, R.id.portfolioTop3,
                        R.id.portfolioFlop1, R.id.portfolioFlop2, R.id.portfolioFlop3).forEach {
                        views.setTextViewText(it, "—")
                    }
                }

                views.setTextViewText(
                    R.id.portfolioKraken,
                    when {
                        snapshot != null -> "Kraken · ${eur.format(snapshot.totalEur)}"
                        BrokerConnectionStore.hasKraken(context) -> "Kraken · connexion à vérifier"
                        else -> "Kraken · toucher pour connecter"
                    }
                )
                views.setTextViewText(
                    R.id.portfolioIbkr,
                    if (BrokerConnectionStore.hasIbkrSetup(context)) "IBKR · configuration en cours" else "IBKR · toucher pour connecter"
                )
                val intent = Intent(context, BrokerConnectionsActivity::class.java)
                val pending = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(R.id.portfolioTotal, pending)
                views.setOnClickPendingIntent(R.id.portfolioIbkr, pending)
                views.setOnClickPendingIntent(R.id.portfolioKraken, pending)
                manager.updateAppWidget(id, views)
            }
        }

        private fun fillRanking(
            views: RemoteViews,
            ids: List<Int>,
            positions: List<KrakenPositionSnapshot>,
            eur0: NumberFormat
        ) {
            ids.forEachIndexed { index, viewId ->
                val position = positions.getOrNull(index)
                if (position == null) {
                    views.setTextViewText(viewId, "—")
                } else {
                    views.setTextViewText(
                        viewId,
                        "${position.symbol} ${eur0.format(position.valueEur)} ${String.format(Locale.FRANCE, "%+.1f%%", position.dayChangePercent)}"
                    )
                    views.setTextColor(viewId, Color.parseColor(if (position.dayChangePercent >= 0) GREEN else RED))
                }
            }
        }

        private fun signedMoney(value: Double, format: NumberFormat): String {
            val raw = format.format(kotlin.math.abs(value))
            return if (value >= 0) "+$raw" else "−$raw"
        }
    }
}
