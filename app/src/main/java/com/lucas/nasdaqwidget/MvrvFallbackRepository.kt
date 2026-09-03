package com.lucas.nasdaqwidget

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object MvrvFallbackRepository {
    private const val PREFS = "mvrv_fallback"
    private const val ENDPOINT = "https://community-api.coinmetrics.io/v4/timeseries/asset-metrics?assets=btc&metrics=CapMVRVZ&frequency=1d&page_size=1&paging_from=end"

    fun refresh(context: Context): Double {
        val value = fetch()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("z", value.toBits())
            .putLong("updatedAt", System.currentTimeMillis())
            .apply()
        return value
    }

    fun cached(context: Context): Double? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (p.contains("z")) Double.fromBits(p.getLong("z", 0L)) else null
    }

    private fun fetch(): Double {
        val c = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MarketWidgets/2.1 Android")
        }
        return try {
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("Coin Metrics HTTP $code")
            val data = JSONObject(body).optJSONArray("data") ?: error("Coin Metrics data missing")
            if (data.length() == 0) error("Coin Metrics data empty")
            val raw = data.getJSONObject(data.length() - 1).optString("CapMVRVZ")
            raw.toDoubleOrNull() ?: error("CapMVRVZ missing")
        } finally {
            c.disconnect()
        }
    }
}
