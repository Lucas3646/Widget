package com.lucas.nasdaqwidget

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class MacroWidgetRefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {
    override fun doWork(): Result {
        return runCatching {
            MacroRepository.refresh(applicationContext)
            MacroWidgetProvider.updateAll(applicationContext)
            Result.success()
        }.getOrElse {
            MacroWidgetProvider.updateAll(applicationContext)
            Result.retry()
        }
    }
}
