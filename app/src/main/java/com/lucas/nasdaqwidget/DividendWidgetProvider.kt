package com.lucas.nasdaqwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

class DividendWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        renderAll(context)
        if (BrokerConnectionStore.hasIbkrSetup(context)) {
            Thread {
                runCatching { IbkrDividendRepository.refresh(context) }
                renderAll(context)
            }.start()
        }
    }

    companion object {
        fun refreshAndUpdate(context: Context) {
            Thread {
                runCatching { IbkrDividendRepository.refresh(context) }
                renderAll(context)
            }.start()
        }

        fun updateAll(context: Context) = renderAll(context)

        private fun renderAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, DividendWidgetProvider::class.java))
            val snapshot = IbkrDividendRepository.cached(context)
            ids.forEach { id -> manager.updateAppWidget(id, buildViews(context, snapshot)) }
        }

        private fun buildViews(context: Context, snapshot: IbkrDividendSnapshot?): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_dividend)
            val openConnections = PendingIntent.getActivity(
                context,
                401,
                Intent(context, BrokerConnectionsActivity::class.java).putExtra("refresh_target", "dividend"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            listOf(
                R.id.dividendTitle,
                R.id.dividendSymbol,
                R.id.dividendCountdown,
                R.id.dividendAmount,
                R.id.dividendDate,
                R.id.dividendReceivedYtd,
                R.id.dividendRemaining
            ).forEach { views.setOnClickPendingIntent(it, openConnections) }

            if (snapshot == null) {
                views.setTextViewText(R.id.dividendTitle, "DIVIDENDES · IBKR")
                views.setTextViewText(R.id.dividendSymbol, if (BrokerConnectionStore.hasIbkrSetup(context)) "Aucune donnée" else "IBKR non connecté")
                views.setTextViewText(R.id.dividendCountdown, "J—")
                views.setTextViewText(R.id.dividendAmount, "—")
                views.setTextViewText(R.id.dividendDate, if (BrokerConnectionStore.hasIbkrSetup(context)) "Aucune échéance trouvée" else "Touchez pour configurer")
                views.setTextViewText(R.id.dividendReceivedYtd, "— €")
                views.setTextViewText(R.id.dividendRemaining, "— €")
                views.setTextViewText(R.id.dividendDescription, "Historique IBKR requis pour calculer les prochaines échéances.")
                return views
            }

            val nextFormatter = runCatching {
                NumberFormat.getCurrencyInstance(Locale.FRANCE).apply { currency = Currency.getInstance(snapshot.nextCurrency) }
            }.getOrElse { NumberFormat.getNumberInstance(Locale.FRANCE) }
            val eur = NumberFormat.getCurrencyInstance(Locale.FRANCE)
            views.setTextViewText(R.id.dividendTitle, if (snapshot.hasUpcoming) "PROCHAIN DIVIDENDE · IBKR" else "DIVIDENDES · IBKR")
            views.setTextViewText(R.id.dividendSymbol, snapshot.symbol)
            views.setTextViewText(R.id.dividendCountdown, if (snapshot.hasUpcoming) "J-${snapshot.daysUntil.coerceAtLeast(0)}" else "—")
            views.setTextViewText(R.id.dividendAmount, nextFormatter.format(snapshot.nextAmount))
            views.setTextViewText(
                R.id.dividendDate,
                when {
                    snapshot.hasUpcoming && snapshot.estimatedUpcoming -> "Estimé le ${snapshot.nextDateLabel}"
                    snapshot.hasUpcoming -> "Prévu le ${snapshot.nextDateLabel}"
                    else -> "Dernier reçu · ${snapshot.nextDateLabel}"
                }
            )
            views.setTextViewText(R.id.dividendReceivedYtd, eur.format(snapshot.receivedYtdEur))
            views.setTextViewText(R.id.dividendRemaining, eur.format(snapshot.remainingYearEur))
            views.setTextViewText(
                R.id.dividendDescription,
                when {
                    snapshot.hasUpcoming && snapshot.estimatedUpcoming -> "Échéances estimées depuis l’historique réel des paiements IBKR"
                    snapshot.hasUpcoming -> "Restant = dividendes déclarés par IBKR jusqu’au 31/12"
                    else -> "Pas assez d’historique pour estimer le prochain paiement"
                }
            )
            return views
        }
    }
}
