package com.lucas.nasdaqwidget

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class DividendRefreshWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result {
        if (!BrokerConnectionStore.hasIbkrSetup(applicationContext)) {
            DividendWidgetProvider.updateAll(applicationContext)
            return Result.success()
        }
        val refreshed = runCatching {
            IbkrDividendRepository.refresh(applicationContext)
        }.isSuccess
        DividendWidgetProvider.updateAll(applicationContext)
        return if (refreshed) Result.success() else Result.retry()
    }
}
