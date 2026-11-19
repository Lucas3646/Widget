package com.lucas.nasdaqwidget

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class DividendRefreshWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val context = applicationContext
        val error = if (BrokerConnectionStore.hasIbkrSetup(context)) {
            runCatching { IbkrDividendRepository.refresh(context) }.exceptionOrNull()
        } else null
        DividendWidgetProvider.updateAll(context)
        return if (error?.isFlexRetryable() == true && runAttemptCount < 2 && !isStopped) Result.retry() else Result.success()
    }
}
