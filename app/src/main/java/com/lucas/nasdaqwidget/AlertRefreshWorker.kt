package com.lucas.nasdaqwidget

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class AlertRefreshWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result {
        return runCatching {
            AlertMarketRepository.refresh(applicationContext)
            AlertWidgetProvider.updateAll(applicationContext)
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() }
        )
    }
}
