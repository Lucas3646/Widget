package com.lucas.nasdaqwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.work.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MacroWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { ids.forEach { manager.updateAppWidget(it, buildViews(context)) }; requestImmediateRefresh(context); scheduleRefresh(context) }
    override fun onReceive(context: Context, intent: Intent) { super.onReceive(context, intent); if (intent.action == ACTION_REFRESH_MACRO) requestImmediateRefresh(context) }
    companion object {
        private const val ACTION_REFRESH_MACRO = "com.lucas.nasdaqwidget.REFRESH_MACRO"
        fun updateAll(context: Context) { val m=AppWidgetManager.getInstance(context); m.getAppWidgetIds(ComponentName(context,MacroWidgetProvider::class.java)).forEach{m.updateAppWidget(it,buildViews(context))} }
        private fun timing(s: MacroSnapshot): String { val d=s.eventAtMillis-System.currentTimeMillis(); val c=when { s.released->"PUBLIÉ"; d<=0->"MAINT."; d<3600000->"${(d/60000).coerceAtLeast(1)} min"; d<86400000->"H-${(d/3600000).coerceAtLeast(1)}"; else->"J-${(d/86400000).coerceAtLeast(1)}" }; return "$c · ${SimpleDateFormat("HH:mm",Locale.FRANCE).format(Date(s.eventAtMillis))}" }
        private fun buildViews(context: Context): RemoteViews {
            val v=RemoteViews(context.packageName,R.layout.widget_macro); val s=MacroRepository.cached(context); val s2=MacroRepository.cachedSecond(context)
            if(s==null){ v.setTextViewText(R.id.macroTitle,"MACRO");v.setTextViewText(R.id.macroEvent,"Chargement…");v.setTextViewText(R.id.macroCountdown,"—");v.setTextViewText(R.id.macroForecast,"Att. —");v.setTextViewText(R.id.macroActual,"Rés. —");v.setTextViewText(R.id.macroPrevious,"Préc. —") }
            else { v.setTextViewText(R.id.macroTitle,"MACRO · ${s.country.uppercase().take(3)}");v.setTextViewText(R.id.macroEvent,s.title);v.setTextViewText(R.id.macroCountdown,timing(s));v.setTextViewText(R.id.macroForecast,"Att. ${s.forecast?:"—"}");v.setTextViewText(R.id.macroActual,"Rés. ${s.actual?:"—"}");v.setTextViewText(R.id.macroPrevious,"Préc. ${s.previous?:"—"}") }
            if(s2==null){v.setTextViewText(R.id.macroEvent2,"PROCHAIN · —");v.setTextViewText(R.id.macroCountdown2,"—");v.setTextViewText(R.id.macroForecast2,"Att. — · Préc. —")}
            else {v.setTextViewText(R.id.macroEvent2,"${s2.country.uppercase().take(3)} · ${s2.title}");v.setTextViewText(R.id.macroCountdown2,timing(s2));v.setTextViewText(R.id.macroForecast2,"Att. ${s2.forecast?:"—"} · Préc. ${s2.previous?:"—"}")}
            val refresh=PendingIntent.getBroadcast(context,9901,Intent(context,MacroWidgetProvider::class.java).apply{action=ACTION_REFRESH_MACRO},PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE);v.setOnClickPendingIntent(R.id.macroRoot,refresh);return v
        }
        fun requestImmediateRefresh(context:Context){val r=OneTimeWorkRequestBuilder<MacroWidgetRefreshWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build();WorkManager.getInstance(context).enqueueUniqueWork("macro_widget_refresh_now",ExistingWorkPolicy.REPLACE,r)}
        fun scheduleRefresh(context:Context){val r=PeriodicWorkRequestBuilder<MacroWidgetRefreshWorker>(15,TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build();WorkManager.getInstance(context).enqueueUniquePeriodicWork("macro_widget_refresh",ExistingPeriodicWorkPolicy.UPDATE,r)}
    }
}
