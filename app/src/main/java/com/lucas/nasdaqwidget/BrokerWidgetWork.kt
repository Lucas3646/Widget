package com.lucas.nasdaqwidget

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import java.util.concurrent.TimeUnit

internal class BrokerWidgetWork(private val name: String, private val worker: Class<out Worker>) {
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(context: Context) {
        val request = PeriodicWorkRequest.Builder(worker, 15, TimeUnit.MINUTES).setConstraints(network).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun refresh(context: Context) {
        val request = OneTimeWorkRequest.Builder(worker).setConstraints(network).build()
        WorkManager.getInstance(context).enqueueUniqueWork("$name-now", ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(name)
            cancelUniqueWork("$name-now")
        }
    }
}

internal fun Throwable.isFlexRetryable() = this is IbkrFlexClient.FlexError && retryable
