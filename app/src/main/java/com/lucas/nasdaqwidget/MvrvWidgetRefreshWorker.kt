package com.lucas.nasdaqwidget

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class MvrvWidgetRefreshWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result {
        return runCatching {
            MvrvRepository.refresh(applicationContext)
            MvrvWidgetProvider.updateAll(applicationContext)
        }.fold(
            onSuccess = { Result.success() },
            onFailure = {
                MvrvWidgetProvider.updateAll(applicationContext)
                Result.retry()
            }
        )
    }
}
