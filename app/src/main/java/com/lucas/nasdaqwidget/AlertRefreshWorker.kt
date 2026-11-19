package com.lucas.nasdaqwidget

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class AlertRefreshWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result {
        val failed = AlertMarketRepository.refresh(applicationContext)
        AlertWidgetProvider.updateAll(applicationContext)
        return if (failed.isNotEmpty() && runAttemptCount < 2) Result.retry() else Result.success()
    }
}
