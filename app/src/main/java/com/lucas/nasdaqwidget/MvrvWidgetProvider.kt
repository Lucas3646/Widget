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

class MvrvWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateWidget(context, manager, it) }
        requestImmediateRefresh(context)
        scheduleRefresh(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH_MVRV) requestImmediateRefresh(context)
    }

    companion object {
        private const val ACTION_REFRESH_MVRV = "com.lucas.nasdaqwidget.REFRESH_MVRV"

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, MvrvWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { updateWidget(context, manager, it) }
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_mvrv)
            val primary = MvrvRepository.cached(context)
            val fallbackZ = MvrvFallbackRepository.cached(context)
            val snapshot = primary ?: fallbackZ?.let { MvrvSnapshot(it, null, null, System.currentTimeMillis()) }
            val primaryError = MvrvRepository.lastError(context)
            val fallbackError = MvrvFallbackRepository.lastError(context)
            val symbols = DecimalFormatSymbols(Locale.FRANCE).apply { groupingSeparator = ' ' }
            val zFormat = DecimalFormat("0.00", symbols)
            val priceFormat = DecimalFormat("#,##0", symbols)
            val pctFormat = DecimalFormat("0.0", symbols)

            if (snapshot == null) {
                views.setTextViewText(R.id.mvrvWidgetValue, "Z --")
                if (primaryError == null && fallbackError == null) {
                    views.setTextViewText(R.id.mvrvWidgetZone, "Chargement…")
                    views.setTextViewText(R.id.mvrvWidgetDistance, "Toucher pour actualiser")
                } else {
                    views.setTextViewText(R.id.mvrvWidgetZone, "Source indisponible")
                    val diagnostic = fallbackError ?: primaryError ?: "Erreur inconnue"
                    views.setTextViewText(R.id.mvrvWidgetDistance, diagnostic.take(52))
                }
                views.setTextColor(R.id.mvrvWidgetValue, Color.rgb(142, 160, 178))
            } else {
                val zone = MvrvRepository.zoneLabel(snapshot.zScore)
                val zoneColor = when {
                    snapshot.zScore >= MvrvRepository.highZoneZ() -> Color.rgb(255, 82, 82)
                    snapshot.zScore >= 5.0 -> Color.rgb(255, 188, 66)
                    snapshot.zScore < 0.0 -> Color.rgb(56, 242, 122)
                    else -> Color.rgb(80, 191, 255)
                }
                views.setTextViewText(R.id.mvrvWidgetValue, "Z ${zFormat.format(snapshot.zScore)}")
                views.setTextColor(R.id.mvrvWidgetValue, zoneColor)
                views.setTextViewText(R.id.mvrvWidgetZone, if (primary == null) "$zone · secours" else zone)

                val target = snapshot.estimatedHighZonePrice
                val price = snapshot.sourcePrice
                val distance = if (target != null && price != null && price > 0.0) (target / price - 1.0) * 100.0 else null
                val detail = when {
                    target != null && distance != null && distance > 0.0 -> "Z7 ≈ $${priceFormat.format(target)} · +${pctFormat.format(distance)}%"
                    target != null && distance != null -> "Z7 ≈ $${priceFormat.format(target)} · atteinte"
                    primary == null -> "MVRV Z-Score disponible"
                    else -> "Zone Z7 · estimation indisponible"
                }
                views.setTextViewText(R.id.mvrvWidgetDistance, detail)
            }

            val refreshIntent = Intent(context, MvrvWidgetProvider::class.java).apply { action = ACTION_REFRESH_MVRV }
            val pendingIntent = PendingIntent.getBroadcast(context, id, refreshIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.mvrvWidgetRoot, pendingIntent)
            manager.updateAppWidget(id, views)
        }

        fun requestImmediateRefresh(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val request = OneTimeWorkRequestBuilder<MvrvWidgetRefreshWorker>().setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniqueWork("mvrv_widget_refresh_now", ExistingWorkPolicy.REPLACE, request)
        }

        fun scheduleRefresh(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val request = PeriodicWorkRequestBuilder<MvrvWidgetRefreshWorker>(6, TimeUnit.HOURS).setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("mvrv_widget_refresh", ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
