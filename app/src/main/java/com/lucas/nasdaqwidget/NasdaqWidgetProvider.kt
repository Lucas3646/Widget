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
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class NasdaqWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateWidget(context, manager, it) }
        scheduleRefresh(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            updateAll(context)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.lucas.nasdaqwidget.REFRESH"

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, NasdaqWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { updateWidget(context, manager, it) }
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, id: Int) {
            val data = MarketRepository.current()
            val views = RemoteViews(context.packageName, R.layout.widget_nasdaq)

            val symbols = DecimalFormatSymbols(Locale.FRANCE)
            symbols.groupingSeparator = ' '
            val priceFormat = DecimalFormat("#,##0.00", symbols)
            val signed = DecimalFormat("+0.00;-0.00", symbols)

            views.setTextViewText(R.id.priceText, priceFormat.format(data.price))
            views.setTextViewText(R.id.changePercentText, "${signed.format(data.changePercent)}%")
            views.setTextViewText(R.id.changePointsText, signed.format(data.change))
            views.setTextViewText(
                R.id.updatedText,
                "MAJ ${SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(data.updatedAtMillis))}  ●"
            )

            val trendColor = if (data.change >= 0) Color.rgb(56, 242, 122) else Color.rgb(255, 82, 82)
            views.setTextColor(R.id.changePercentText, trendColor)
            views.setTextColor(R.id.changePointsText, trendColor)
            views.setImageViewBitmap(R.id.chartImage, ChartRenderer.render(data.candles))

            val refreshIntent = Intent(context, NasdaqWidgetProvider::class.java).apply { action = ACTION_REFRESH }
            val refreshPendingIntent = PendingIntent.getBroadcast(
                context, id, refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, refreshPendingIntent)
            manager.updateAppWidget(id, views)
        }

        fun scheduleRefresh(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "nasdaq_widget_refresh",
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
