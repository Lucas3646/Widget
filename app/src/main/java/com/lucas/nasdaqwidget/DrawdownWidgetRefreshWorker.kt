package com.lucas.nasdaqwidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class DrawdownWidgetRefreshWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result {
        val manager = AppWidgetManager.getInstance(applicationContext)
        val ids = manager.getAppWidgetIds(
            ComponentName(applicationContext, DrawdownWidgetProvider::class.java)
        )
        var success = false

        ids.map { WidgetAssetConfig.symbol(applicationContext, it) }
            .distinct()
            .forEach { symbol ->
                if (runCatching { DrawdownRepository.refresh(applicationContext, symbol) }.isSuccess) {
                    success = true
                }
            }

        DrawdownWidgetProvider.updateAll(applicationContext)
        return if (ids.isEmpty() || success) Result.success() else Result.retry()
    }
}
