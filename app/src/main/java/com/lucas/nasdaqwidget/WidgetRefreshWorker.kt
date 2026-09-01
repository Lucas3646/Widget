package com.lucas.nasdaqwidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class WidgetRefreshWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result {
        val manager = AppWidgetManager.getInstance(applicationContext)
        val ids = manager.getAppWidgetIds(ComponentName(applicationContext, NasdaqWidgetProvider::class.java))
        val symbols = ids.map { WidgetAssetConfig.symbol(applicationContext, it) }.distinct()
        var success = false
        symbols.forEach { symbol ->
            runCatching { MarketRepository.fetchAndCache(applicationContext, symbol) }
                .onSuccess { success = true }
        }
        NasdaqWidgetProvider.updateAll(applicationContext)
        return if (success || symbols.isEmpty()) Result.success() else Result.retry()
    }
}
