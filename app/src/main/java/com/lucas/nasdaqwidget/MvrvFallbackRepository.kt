package com.lucas.nasdaqwidget

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object MvrvFallbackRepository {
    private const val PREFS = "mvrv_fallback"
    private const val ENDPOINT = "https://community-api.coinmetrics.io/v4/timeseries/asset-metrics?assets=btc&metrics=CapMVRVZ&frequency=1d&page_size=1&paging_from=end"

    fun refresh(context: Context): Double {
        return try {
            val value = fetch()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("z", value.toBits())
                .putLong("updatedAt", System.currentTimeMillis())
                .remove("lastError")
                .apply()
            value
        } catch (t: Throwable) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("lastError", t.message ?: t.javaClass.simpleName)
                .apply()
            throw t
        }
    }

    fun cached(context: Context): Double? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (p.contains("z")) Double.fromBits(p.getLong("z", 0L)) else null
    }

    fun lastError(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("lastError", null)

    private fun fetch(): Double {
        val c = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MarketWidgets/2.2 Android")
        }
        return try {
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val apiMessage = runCatching { JSONObject(body).optString("error").takeIf { it.isNotBlank() } }.getOrNull()
                error("CM HTTP $code${apiMessage?.let { ": $it" } ?: ""}")
            }
            val data = JSONObject(body).optJSONArray("data") ?: error("CM: data missing")
            if (data.length() == 0) error("CM: data empty")
            val item = data.getJSONObject(data.length() - 1)
            val raw = item.opt("CapMVRVZ")
            val value = when (raw) {
                is Number -> raw.toDouble()
                is String -> raw.toDoubleOrNull()
                else -> null
            }
            value ?: error("CM: CapMVRVZ missing")
        } finally {
            c.disconnect()
        }
    }
}
