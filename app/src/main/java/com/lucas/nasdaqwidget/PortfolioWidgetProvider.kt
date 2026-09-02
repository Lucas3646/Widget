package com.lucas.nasdaqwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
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
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(android.content.ComponentName(context, PortfolioWidgetProvider::class.java))
            updateAll(context, manager, ids)
        }

        private fun updateAll(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val snapshot = KrakenPortfolioRepository.cached(context)
            val eur = NumberFormat.getCurrencyInstance(Locale.FRANCE)
            ids.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.widget_portfolio)
                views.setTextViewText(R.id.portfolioTotal, snapshot?.let { eur.format(it.totalEur) } ?: "— €")
                // Daily performance and position ranking are ready in the UI and will be populated
                // from the unified IBKR + Kraken position snapshot once both feeds are connected.
                views.setTextViewText(R.id.portfolioDayChange, "— €")
                views.setTextViewText(R.id.portfolioDayPercent, "— % aujourd’hui")
                listOf(R.id.portfolioTop1, R.id.portfolioTop2, R.id.portfolioTop3,
                    R.id.portfolioFlop1, R.id.portfolioFlop2, R.id.portfolioFlop3).forEach {
                    views.setTextViewText(it, "—")
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
    }
}
