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

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, AlertWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { updateWidget(context, manager, it) }
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_alerts)
            val rules = AlertStore.rules(context).take(4)
            val symbols = DecimalFormatSymbols(Locale.FRANCE).apply { groupingSeparator = ' ' }
            val priceFormat = DecimalFormat("#,##0.##", symbols)

            rowIds.forEach { views.setViewVisibility(it, View.GONE) }

            if (rules.isEmpty()) {
                views.setViewVisibility(R.id.emptyText, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.emptyText, View.GONE)
                rules.forEachIndexed { index, rule ->
                    val rowId = rowIds[index]
                    val price = AlertMarketRepository.cachedPrice(context, rule.symbol)
                    val triggered = price?.let(rule::isTriggered) == true
                    val priceLabel = price?.let(priceFormat::format) ?: "--"
                    val thresholdLabel = priceFormat.format(rule.threshold)
                    val state = if (triggered) "✓" else "•"
                    views.setTextViewText(rowId, "${rule.symbol}  ${rule.operator} $thresholdLabel   $priceLabel  $state")
                    views.setTextColor(
                        rowId,
                        if (triggered) Color.rgb(56, 242, 122) else Color.rgb(225, 231, 239)
                    )
                    views.setViewVisibility(rowId, View.VISIBLE)
                }
            }

            val updatedAt = AlertMarketRepository.lastUpdated(context)
            val updatedLabel = if (updatedAt > 0) {
                "MAJ ${SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(updatedAt))} • toucher pour actualiser"
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
