package com.lucas.nasdaqwidget

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class PortfolioRefreshWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val context = applicationContext
        val kraken = if (BrokerConnectionStore.hasKraken(context)) {
            runCatching { KrakenPortfolioRepository.refresh(context) }.exceptionOrNull()
        } else null
        val ibkr = if (BrokerConnectionStore.hasIbkrSetup(context)) {
            runCatching { IbkrFlexRepository.refresh(context) }.exceptionOrNull()
        } else null
        PortfolioWidgetProvider.updateAll(context)
        val retry = kraken != null || ibkr?.isFlexRetryable() == true
        return if (retry && runAttemptCount < 2 && !isStopped) Result.retry() else Result.success()
    }
}
