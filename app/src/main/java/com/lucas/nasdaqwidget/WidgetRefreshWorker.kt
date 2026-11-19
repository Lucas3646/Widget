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
        var failed = false
        requests.forEach { (symbol, timeframe) ->
            runCatching { MarketRepository.fetchAndCache(applicationContext, symbol, timeframe) }
                .onFailure { failed = true }
        }
        NasdaqWidgetProvider.updateAll(applicationContext)
        return if (failed && runAttemptCount < 2) Result.retry() else Result.success()
    }
}
