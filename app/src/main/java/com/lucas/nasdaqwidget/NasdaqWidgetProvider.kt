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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class NasdaqWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateWidget(context, manager, it) }
        requestImmediateRefresh(context)
        scheduleRefresh(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { WidgetAssetConfig.remove(context, it) }
        super.onDeleted(context, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) requestImmediateRefresh(context)
    }

    companion object {
        const val ACTION_REFRESH = "com.lucas.nasdaqwidget.REFRESH"

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, NasdaqWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { updateWidget(context, manager, it) }
        }

        fun updateOne(context: Context, manager: AppWidgetManager, id: Int) = updateWidget(context, manager, id)

        private fun updateWidget(context: Context, manager: AppWidgetManager, id: Int) {
            val symbol = WidgetAssetConfig.symbol(context, id)
            val name = WidgetAssetConfig.name(context, id)
            val timeframe = WidgetAssetConfig.timeframe(context, id)
            val data = MarketRepository.cached(context, symbol, timeframe)
            val views = RemoteViews(context.packageName, R.layout.widget_nasdaq)
            views.setTextViewText(R.id.assetTitleText, name)
            views.setTextViewText(R.id.assetSymbolText, "$symbol · $timeframe")

            if (data == null) {
                views.setTextViewText(R.id.priceText, "--")
                views.setTextViewText(R.id.changePercentText, "Connexion…")
                views.setTextViewText(R.id.changePointsText, "")
                views.setTextViewText(R.id.updatedText, "$timeframe · TOUCHER POUR CHANGER")
            } else {
                val symbols = DecimalFormatSymbols(Locale.FRANCE).apply { groupingSeparator = ' ' }
                val priceFormat = DecimalFormat("#,##0.00", symbols)
                val signed = DecimalFormat("+0.00;-0.00", symbols)
                views.setTextViewText(R.id.priceText, priceFormat.format(data.price))
                views.setTextViewText(R.id.changePercentText, "${signed.format(data.changePercent)}%")
                views.setTextViewText(R.id.changePointsText, signed.format(data.change))
                views.setTextViewText(R.id.updatedText, "$timeframe · MAJ ${SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(data.updatedAtMillis))} · toucher")
                val trendColor = if (data.change >= 0) Color.rgb(56, 242, 122) else Color.rgb(255, 82, 82)
                views.setTextColor(R.id.changePercentText, trendColor)
                views.setTextColor(R.id.changePointsText, trendColor)
                views.setImageViewBitmap(R.id.chartImage, ChartRenderer.render(data.candles))
            }

            val configureIntent = Intent(context, AssetWidgetConfigureActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val configurePendingIntent = PendingIntent.getActivity(context, id, configureIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widgetRoot, configurePendingIntent)
            manager.updateAppWidget(id, views)
        }

        fun requestImmediateRefresh(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>().setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniqueWork("market_widget_refresh_now", ExistingWorkPolicy.REPLACE, request)
        }

        fun scheduleRefresh(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("market_widget_refresh", ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
