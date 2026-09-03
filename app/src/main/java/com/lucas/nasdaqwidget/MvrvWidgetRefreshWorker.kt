package com.lucas.nasdaqwidget

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class MvrvWidgetRefreshWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result {
        val primary = runCatching { MvrvRepository.refresh(applicationContext) }
        if (primary.isFailure) {
            runCatching { MvrvFallbackRepository.refresh(applicationContext) }
                .onFailure { MvrvRepository.recordError(applicationContext, primary.exceptionOrNull() ?: it) }
        }
        MvrvWidgetProvider.updateAll(applicationContext)
        return if (primary.isSuccess || MvrvFallbackRepository.cached(applicationContext) != null) Result.success() else Result.retry()
    }
}
