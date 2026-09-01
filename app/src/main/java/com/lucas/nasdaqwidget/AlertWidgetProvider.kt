package com.lucas.nasdaqwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class AlertWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateWidget(context, manager, it) }
        scheduleRefresh(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH_ALERTS) {
            Thread {
                AlertMarketRepository.refresh(context)
                updateAll(context)
            }.start()
        }
    }

    companion object {
        private const val ACTION_REFRESH_ALERTS = "com.lucas.nasdaqwidget.REFRESH_ALERTS"
        private val rowIds = intArrayOf(R.id.alertRow1, R.id.alertRow2, R.id.alertRow3, R.id.alertRow4)
        private val symbolIds = intArrayOf(R.id.alertSymbol1, R.id.alertSymbol2, R.id.alertSymbol3, R.id.alertSymbol4)
        private val conditionIds = intArrayOf(R.id.alertCondition1, R.id.alertCondition2, R.id.alertCondition3, R.id.alertCondition4)
        private val priceIds = intArrayOf(R.id.alertPrice1, R.id.alertPrice2, R.id.alertPrice3, R.id.alertPrice4)
        private val stateIds = intArrayOf(R.id.alertState1, R.id.alertState2, R.id.alertState3, R.id.alertState4)

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, AlertWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { updateWidget(context, manager, it) }
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_alerts)
            val allRules = AlertStore.rules(context).filterNot { it.isMvrv() }
            val decorated = allRules.map { rule ->
                val price = AlertMarketRepository.cachedPrice(context, rule.symbol)
                Triple(rule, price, price?.let(rule::isTriggered) == true)
            }
            val shown = decorated.sortedWith(
                compareByDescending<Triple<AlertRule, Double?, Boolean>> { it.third }
                    .thenBy { it.first.id }
            ).take(4)

            val symbols = DecimalFormatSymbols(Locale.FRANCE).apply { groupingSeparator = ' ' }
            val valueFormat = DecimalFormat("#,##0.##", symbols)
            val triggeredCount = decorated.count { it.third }

            rowIds.forEach { views.setViewVisibility(it, View.GONE) }
            views.setViewVisibility(R.id.mvrvStrip, View.GONE)
            views.setTextViewText(
                R.id.alertSummaryText,
                when {
                    allRules.isEmpty() -> "Aucune alerte prix configurée"
                    triggeredCount == 0 -> "${allRules.size} règle${if (allRules.size > 1) "s" else ""} surveillée${if (allRules.size > 1) "s" else ""}"
                    triggeredCount == 1 -> "1 alerte déclenchée"
                    else -> "$triggeredCount alertes déclenchées"
                }
            )
            views.setTextColor(
                R.id.alertSummaryText,
                if (triggeredCount > 0) Color.rgb(56, 242, 122) else Color.rgb(142, 160, 178)
            )

            if (shown.isEmpty()) {
                views.setViewVisibility(R.id.emptyText, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.emptyText, View.GONE)
                shown.forEachIndexed { index, item ->
                    val rule = item.first
                    val value = item.second
                    val triggered = item.third
                    views.setTextViewText(symbolIds[index], rule.symbol)
                    views.setTextViewText(conditionIds[index], "${rule.operator} ${valueFormat.format(rule.threshold)}")
                    views.setTextViewText(priceIds[index], value?.let(valueFormat::format) ?: "--")
                    views.setTextViewText(stateIds[index], if (triggered) "✓" else "○")
                    views.setTextColor(conditionIds[index], if (triggered) Color.rgb(56, 242, 122) else Color.rgb(142, 160, 178))
                    views.setTextColor(priceIds[index], if (triggered) Color.rgb(56, 242, 122) else Color.WHITE)
                    views.setTextColor(stateIds[index], if (triggered) Color.rgb(56, 242, 122) else Color.rgb(102, 120, 138))
                    views.setInt(
                        rowIds[index],
                        "setBackgroundResource",
                        if (triggered) R.drawable.alert_row_triggered_background else R.drawable.alert_row_background
                    )
                    views.setViewVisibility(rowIds[index], View.VISIBLE)
                }
            }

            val updatedAt = AlertMarketRepository.lastUpdated(context)
            val updatedLabel = if (updatedAt > 0) {
                "MAJ ${SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(updatedAt))} · toucher pour actualiser"
            } else {
                "Touchez pour charger les prix"
            }
            views.setTextViewText(R.id.alertUpdatedText, updatedLabel)

            val refreshIntent = Intent(context, AlertWidgetProvider::class.java).apply {
                action = ACTION_REFRESH_ALERTS
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                id,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.alertWidgetRoot, pendingIntent)
            manager.updateAppWidget(id, views)
        }

        fun scheduleRefresh(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<AlertRefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "market_alert_widget_refresh",
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
