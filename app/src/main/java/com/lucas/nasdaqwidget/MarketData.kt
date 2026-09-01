package com.lucas.nasdaqwidget

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class MarketData(
    val price: Double,
    val change: Double,
    val changePercent: Double,
    val candles: List<Float>,
    val updatedAtMillis: Long = System.currentTimeMillis()
)

object MarketRepository {
    private const val PREFS = "nasdaq_widget_market"
    private const val SYMBOL = "%5ENDX"
    private const val ENDPOINT =
        "https://query1.finance.yahoo.com/v8/finance/chart/$SYMBOL?range=1d&interval=5m&includePrePost=false"

    fun fetchAndCache(context: Context): MarketData {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "NasdaqWidget/1.0 Android")
        }

        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Market data HTTP ${connection.responseCode}")
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            val result = root.getJSONObject("chart").getJSONArray("result").getJSONObject(0)
            val meta = result.getJSONObject("meta")
            val quote = result.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
            val closes = quote.getJSONArray("close")

            val candles = buildList {
                for (i in 0 until closes.length()) {
                    if (!closes.isNull(i)) add(closes.getDouble(i).toFloat())
                }
            }
            if (candles.isEmpty()) throw IllegalStateException("No intraday candles returned")

            val price = if (meta.has("regularMarketPrice") && !meta.isNull("regularMarketPrice")) {
                meta.getDouble("regularMarketPrice")
            } else {
                candles.last().toDouble()
            }

            val previousClose = when {
                meta.has("chartPreviousClose") && !meta.isNull("chartPreviousClose") -> meta.getDouble("chartPreviousClose")
                meta.has("previousClose") && !meta.isNull("previousClose") -> meta.getDouble("previousClose")
                else -> candles.first().toDouble()
            }
            val change = price - previousClose
            val changePercent = if (previousClose != 0.0) change / previousClose * 100.0 else 0.0

            return MarketData(
                price = price,
                change = change,
                changePercent = changePercent,
                candles = candles,
                updatedAtMillis = System.currentTimeMillis()
            ).also { save(context, it) }
        } finally {
            connection.disconnect()
        }
    }

    fun cached(context: Context): MarketData? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains("price")) return null
        val candles = prefs.getString("candles", null)
            ?.split(',')
            ?.mapNotNull { it.toFloatOrNull() }
            .orEmpty()
        if (candles.isEmpty()) return null

        return MarketData(
            price = Double.fromBits(prefs.getLong("price", 0L)),
            change = Double.fromBits(prefs.getLong("change", 0L)),
            changePercent = Double.fromBits(prefs.getLong("changePercent", 0L)),
            candles = candles,
            updatedAtMillis = prefs.getLong("updatedAt", 0L)
        )
    }

    private fun save(context: Context, data: MarketData) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong("price", data.price.toBits())
            .putLong("change", data.change.toBits())
            .putLong("changePercent", data.changePercent.toBits())
            .putString("candles", data.candles.joinToString(","))
            .putLong("updatedAt", data.updatedAtMillis)
            .apply()
    }
}
