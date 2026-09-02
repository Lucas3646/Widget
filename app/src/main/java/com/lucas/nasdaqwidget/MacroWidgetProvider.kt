package com.lucas.nasdaqwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MacroWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, buildViews(context)) }
        requestImmediateRefresh(context)
        scheduleRefresh(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH_MACRO) requestImmediateRefresh(context)
    }

    companion object {
        private const val ACTION_REFRESH_MACRO = "com.lucas.nasdaqwidget.REFRESH_MACRO"

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, MacroWidgetProvider::class.java))
            ids.forEach { manager.updateAppWidget(it, buildViews(context)) }
        }

        private fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_macro)
            val snapshot = MacroRepository.cached(context)
            if (snapshot == null) {
                views.setTextViewText(R.id.macroTitle, "MACRO")
                views.setTextViewText(R.id.macroEvent, "Chargement…")
                views.setTextViewText(R.id.macroCountdown, "—")
                views.setTextViewText(R.id.macroForecast, "Att. —")
                views.setTextViewText(R.id.macroActual, "Rés. —")
            } else {
                val now = System.currentTimeMillis()
                val delta = snapshot.eventAtMillis - now
                val countdown = when {
                    snapshot.released -> "PUBLIÉ"
                    delta <= 0 -> "MAINT."
                    delta < 60L * 60 * 1000 -> "${(delta / 60000).coerceAtLeast(1)} min"
                    delta < 24L * 60 * 60 * 1000 -> "H-${(delta / 3600000).coerceAtLeast(1)}"
                    else -> "J-${(delta / 86400000).coerceAtLeast(1)}"
                }
                val time = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(snapshot.eventAtMillis))
                val country = snapshot.country.uppercase().take(3)
                views.setTextViewText(R.id.macroTitle, "MACRO · $country")
                views.setTextViewText(R.id.macroEvent, snapshot.title)
                views.setTextViewText(R.id.macroCountdown, "$countdown · $time")
                views.setTextViewText(R.id.macroForecast, "Att. ${snapshot.forecast ?: "—"}")
                views.setTextViewText(R.id.macroActual, "Rés. ${snapshot.actual ?: "—"}")
                views.setTextViewText(R.id.macroPrevious, "Préc. ${snapshot.previous ?: "—"}")
            }

            val refresh = PendingIntent.getBroadcast(
                context,
                9901,
                Intent(context, MacroWidgetProvider::class.java).apply { action = ACTION_REFRESH_MACRO },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.macroRoot, refresh)
            return views
        }

        fun requestImmediateRefresh(context: Context) {
            val request = OneTimeWorkRequestBuilder<MacroWidgetRefreshWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "macro_widget_refresh_now",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun scheduleRefresh(context: Context) {
            val request = PeriodicWorkRequestBuilder<MacroWidgetRefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "macro_widget_refresh",
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
