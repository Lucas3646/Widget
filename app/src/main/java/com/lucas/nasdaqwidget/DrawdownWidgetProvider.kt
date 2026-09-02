package com.lucas.nasdaqwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.concurrent.TimeUnit

class DrawdownWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateOne(context, manager, it) }
        requestImmediateRefresh(context)
        scheduleRefresh(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { WidgetAssetConfig.remove(context, it) }
        super.onDeleted(context, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH_DRAWDOWN) requestImmediateRefresh(context)
    }

    companion object {
        private const val ACTION_REFRESH_DRAWDOWN = "com.lucas.nasdaqwidget.REFRESH_DRAWDOWN"

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, DrawdownWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { updateOne(context, manager, it) }
        }

        fun updateOne(context: Context, manager: AppWidgetManager, id: Int) {
            val symbol = WidgetAssetConfig.symbol(context, id)
            val name = WidgetAssetConfig.name(context, id)
            val snapshot = DrawdownRepository.cached(context, symbol)
            val views = RemoteViews(context.packageName, R.layout.widget_drawdown)
            val symbols = DecimalFormatSymbols(Locale.FRANCE).apply { groupingSeparator = ' ' }
            val priceFormat = DecimalFormat("#,##0.##", symbols)
            val pctFormat = DecimalFormat("0.0", symbols)

            val titleName = if (name.length > 12) symbol else name
            views.setTextViewText(R.id.drawdownTitle, "$titleName · ATH")

            if (snapshot == null) {
                views.setTextViewText(R.id.drawdownValue, "--.-%")
                views.setTextViewText(R.id.drawdownAth, "ATH --")
                views.setTextViewText(R.id.drawdownCurrent, "Toucher pour actualiser")
                views.setTextColor(R.id.drawdownValue, Color.rgb(142, 160, 178))
            } else {
                val dd = snapshot.drawdownPercent.coerceAtMost(0.0)
                val valueColor = when {
                    dd >= -5.0 -> Color.rgb(56, 242, 122)
                    dd >= -20.0 -> Color.rgb(255, 188, 66)
                    else -> Color.rgb(255, 82, 82)
                }
                views.setTextViewText(R.id.drawdownValue, "${pctFormat.format(dd)}%")
                views.setTextColor(R.id.drawdownValue, valueColor)
                views.setTextViewText(R.id.drawdownAth, "ATH ${priceFormat.format(snapshot.athPrice)}")
                views.setTextViewText(R.id.drawdownCurrent, "Actuel ${priceFormat.format(snapshot.currentPrice)}")
            }

            val configureIntent = Intent(context, DrawdownWidgetConfigureActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val configurePendingIntent = PendingIntent.getActivity(
                context,
                id,
                configureIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.drawdownWidgetRoot, configurePendingIntent)
            manager.updateAppWidget(id, views)
        }

        fun requestImmediateRefresh(context: Context) {
            val request = OneTimeWorkRequestBuilder<DrawdownWidgetRefreshWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "ath_drawdown_refresh_now",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        private fun scheduleRefresh(context: Context) {
            val request = PeriodicWorkRequestBuilder<DrawdownWidgetRefreshWorker>(30, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "ath_drawdown_refresh",
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
