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
                Intent(context, BrokerConnectionsActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.dividendTitle, openConnections)
            views.setOnClickPendingIntent(R.id.dividendSymbol, openConnections)

            if (snapshot == null) {
                if (BrokerConnectionStore.hasIbkrSetup(context)) {
                    views.setTextViewText(R.id.dividendTitle, "DIVIDENDES · IBKR")
                    views.setTextViewText(R.id.dividendSymbol, "Aucun dividende")
                    views.setTextViewText(R.id.dividendAmount, "Aucune échéance trouvée dans la Flex Query")
                    views.setTextViewText(R.id.dividendDate, "Touchez pour actualiser IBKR")
                    views.setTextViewText(R.id.dividendDescription, "La Query doit contenir Open Dividend Accruals ou Cash Transactions.")
                } else {
                    views.setTextViewText(R.id.dividendTitle, "DIVIDENDES · IBKR")
                    views.setTextViewText(R.id.dividendSymbol, "IBKR non connecté")
                    views.setTextViewText(R.id.dividendAmount, "Connecte ton compte une seule fois")
                    views.setTextViewText(R.id.dividendDate, "Touchez pour configurer")
                    views.setTextViewText(R.id.dividendDescription, "")
                }
                return views
            }

            val formatter = runCatching {
                NumberFormat.getCurrencyInstance(Locale.FRANCE).apply { currency = Currency.getInstance(snapshot.currency) }
            }.getOrElse { NumberFormat.getNumberInstance(Locale.FRANCE) }
            val amount = formatter.format(snapshot.amount)
            views.setTextViewText(R.id.dividendTitle, if (snapshot.isUpcoming) "PROCHAIN DIVIDENDE · IBKR" else "DERNIER DIVIDENDE · IBKR")
            views.setTextViewText(R.id.dividendSymbol, snapshot.symbol)
            views.setTextViewText(R.id.dividendAmount, amount)
            views.setTextViewText(R.id.dividendDate, if (snapshot.isUpcoming) "Prévu le ${snapshot.dateLabel}" else "Reçu le ${snapshot.dateLabel}")
            views.setTextViewText(R.id.dividendDescription, snapshot.description)
            return views
        }
    }
}
