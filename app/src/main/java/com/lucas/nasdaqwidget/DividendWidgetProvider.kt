package com.lucas.nasdaqwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Currency
import java.util.Locale

class DividendWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        updateAll(context)
        work.schedule(context)
        work.refresh(context)
    }

    override fun onDisabled(context: Context) = work.cancel(context)

    companion object {
        private val work = BrokerWidgetWork("dividend_widget_refresh", DividendRefreshWorker::class.java)
        private val dateFormat = DateTimeFormatter.ofPattern("dd/MM")

        fun refreshAndUpdate(context: Context) = work.refresh(context)

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, DividendWidgetProvider::class.java))
            val connected = BrokerConnectionStore.hasIbkrSetup(context)
            val snapshot = if (connected) IbkrDividendRepository.cached(context) else null
            ids.forEach { id -> manager.updateAppWidget(id, buildViews(context, connected, snapshot)) }
        }

        private fun buildViews(context: Context, connected: Boolean, snapshot: IbkrDividendSnapshot?): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_dividend)
            val openConnections = PendingIntent.getActivity(
                context, 401,
                Intent(context, BrokerConnectionsActivity::class.java).putExtra("refresh_target", "dividend"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.dividendRoot, openConnections)

            val today = LocalDate.now()
            val payment = snapshot?.paymentDate
            val days = payment?.let { ChronoUnit.DAYS.between(today, it) }
            val eur = NumberFormat.getCurrencyInstance(Locale.FRANCE)
            val amount = snapshot?.nextAmount?.let { next ->
                runCatching {
                    NumberFormat.getCurrencyInstance(Locale.FRANCE).apply {
                        currency = Currency.getInstance(snapshot.nextCurrency!!)
                        minimumFractionDigits = currency.defaultFractionDigits.coerceAtLeast(0)
                        maximumFractionDigits = minimumFractionDigits
                    }.format(next)
                }.getOrElse { "${NumberFormat.getNumberInstance(Locale.FRANCE).format(next)} ${snapshot.nextCurrency.orEmpty()}" }
            }
            views.setTextViewText(R.id.dividendSymbol, when {
                !connected -> "IBKR non connecté"
                snapshot == null -> "Relevé indisponible"
                payment != null -> snapshot.symbol?.ifBlank { "IBKR" } ?: "IBKR"
                snapshot.accruedEur == 0.0 -> "Aucun dividende"
                else -> "Aucune date"
            })
            views.setTextViewText(R.id.dividendCountdown, if (days != null && days >= 0) "J-$days" else "—")
            views.setTextViewText(R.id.dividendAmount, amount ?: "—")
            views.setTextViewText(R.id.dividendDate, when {
                payment == null -> if (connected) "Échéance IBKR · —" else "Touchez pour configurer"
                days != null && days < 0 -> "En attente depuis le ${payment.format(dateFormat)}"
                else -> "Prévu le ${payment.format(dateFormat)}"
            })
            val received = snapshot?.receivedYtdEur?.takeIf { snapshot.reportDate.year == today.year }
            views.setTextViewText(R.id.dividendReceivedYtd, received?.let(eur::format) ?: "—")
            views.setTextViewText(R.id.dividendRemaining, snapshot?.accruedEur?.let(eur::format) ?: "—")
            views.setTextViewText(R.id.dividendDescription, when {
                snapshot == null && connected -> "Voir Connexions · IBKR"
                snapshot == null -> ""
                else -> "Relevé du ${snapshot.reportDate.format(dateFormat)}"
            })
            return views
        }
    }
}
