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
        val requests = ids.map {
            WidgetAssetConfig.symbol(applicationContext, it) to WidgetAssetConfig.timeframe(applicationContext, it)
        }.distinct()
        var success = false
        requests.forEach { (symbol, timeframe) ->
            runCatching { MarketRepository.fetchAndCache(applicationContext, symbol, timeframe) }
                .onSuccess { success = true }
        }
        NasdaqWidgetProvider.updateAll(applicationContext)
        return if (success || requests.isEmpty()) Result.success() else Result.retry()
    }
}
