package com.lucas.nasdaqwidget

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class WidgetRefreshWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result {
        return try {
            MarketRepository.fetchAndCache(applicationContext)
            NasdaqWidgetProvider.updateAll(applicationContext)
            Result.success()
        } catch (_: Exception) {
            // Keep showing the last cached value and let WorkManager retry later.
            NasdaqWidgetProvider.updateAll(applicationContext)
            Result.retry()
        }
    }
}
