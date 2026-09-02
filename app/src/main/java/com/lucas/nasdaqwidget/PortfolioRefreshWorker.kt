package com.lucas.nasdaqwidget

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class PortfolioRefreshWorker(appContext: Context, workerParams: WorkerParameters) : Worker(appContext, workerParams) {
    override fun doWork(): Result {
        val timeframe = PortfolioTimeframeStore.get(applicationContext)
        var attempted = false
        var success = false
        if (BrokerConnectionStore.isKrakenVerified(applicationContext)) {
            attempted = true
            if (runCatching { KrakenPortfolioRepository.refresh(applicationContext, timeframe) }.isSuccess) success = true
        }
        if (BrokerConnectionStore.hasIbkrSetup(applicationContext)) {
            attempted = true
            if (runCatching { IbkrFlexRepository.refresh(applicationContext, timeframe) }.isSuccess) success = true
        }
        PortfolioWidgetProvider.updateAll(applicationContext)
        return if (!attempted || success) Result.success() else Result.retry()
    }
}
