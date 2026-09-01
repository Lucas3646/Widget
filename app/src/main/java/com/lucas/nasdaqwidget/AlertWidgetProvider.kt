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
                if (AlertStore.rules(context).any { it.symbol == "BTC-USD" }) {
                    runCatching { MvrvRepository.refresh(context) }
                }
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
            val allRules = AlertStore.rules(context)
            val hasBtc = allRules.any { it.symbol == "BTC-USD" }
            val decorated = allRules.map { rule ->
                val price = AlertMarketRepository.cachedPrice(context, rule.symbol)
                Triple(rule, price, price?.let(rule::isTriggered) == true)
            }
            val shown = decorated.sortedWith(
                compareByDescending<Triple<AlertRule, Double?, Boolean>> { it.third }
                    .thenBy { it.first.id }
            ).take(if (hasBtc) 3 else 4)

            val symbols = DecimalFormatSymbols(Locale.FRANCE).apply { groupingSeparator = ' ' }
            val priceFormat = DecimalFormat("#,##0.##", symbols)
            val compactPriceFormat = DecimalFormat("#,##0", symbols)
            val oneDecimal = DecimalFormat("0.0", symbols)
            val triggeredCount = decorated.count { it.third }

            rowIds.forEach { views.setViewVisibility(it, View.GONE) }
            views.setViewVisibility(R.id.mvrvStrip, if (hasBtc) View.VISIBLE else View.GONE)
            views.setTextViewText(
                R.id.alertSummaryText,
                when {
                    allRules.isEmpty() -> "Aucune règle configurée"
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
                    val price = item.second
                    val triggered = item.third
                    val priceLabel = price?.let(priceFormat::format) ?: "--"
                    val thresholdLabel = priceFormat.format(rule.threshold)

                    views.setTextViewText(symbolIds[index], rule.symbol)
                    views.setTextViewText(conditionIds[index], "${rule.operator} $thresholdLabel")
                    views.setTextViewText(priceIds[index], priceLabel)
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

            if (hasBtc) {
                val snapshot = MvrvRepository.cached(context)
                val liveBtc = AlertMarketRepository.cachedPrice(context, "BTC-USD")
                if (snapshot == null) {
                    views.setTextViewText(R.id.mvrvValueText, "MVRV Z --")
                    views.setTextViewText(R.id.mvrvZoneText, "Toucher pour charger")
                    views.setTextColor(R.id.mvrvValueText, Color.rgb(142, 160, 178))
                    views.setTextColor(R.id.mvrvZoneText, Color.rgb(114, 131, 148))
                } else {
                    val zoneLabel = MvrvRepository.zoneLabel(snapshot.zScore)
                    val zonePrice = snapshot.estimatedHighZonePrice
                    val distancePct = if (zonePrice != null && liveBtc != null && liveBtc > 0.0) {
                        (zonePrice / liveBtc - 1.0) * 100.0
                    } else null
                    val zoneColor = when {
                        snapshot.zScore >= MvrvRepository.highZoneZ() -> Color.rgb(255, 82, 82)
                        snapshot.zScore >= 5.0 -> Color.rgb(255, 188, 66)
                        else -> Color.rgb(80, 191, 255)
                    }
                    views.setTextViewText(R.id.mvrvValueText, "MVRV Z ${oneDecimal.format(snapshot.zScore)} · $zoneLabel")
                    views.setTextColor(R.id.mvrvValueText, zoneColor)

                    val detail = when {
                        zonePrice != null && distancePct != null && distancePct > 0.0 ->
                            "Zone Z7 ≈ $${compactPriceFormat.format(zonePrice)} · +${oneDecimal.format(distancePct)}%"
                        zonePrice != null && distancePct != null ->
                            "Zone Z7 ≈ $${compactPriceFormat.format(zonePrice)} · atteinte"
                        else -> "Zone haute Z7 · estimation indisponible"
                    }
                    views.setTextViewText(R.id.mvrvZoneText, detail)
                    views.setTextColor(R.id.mvrvZoneText, Color.rgb(205, 216, 228))
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
