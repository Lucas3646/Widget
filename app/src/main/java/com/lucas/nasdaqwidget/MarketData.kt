package com.lucas.nasdaqwidget

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class MarketData(
    val price: Double,
    val change: Double,
    val changePercent: Double,
    val candles: List<Float>,
    val updatedAtMillis: Long = System.currentTimeMillis()
)

object MarketRepository {
    private const val PREFS = "market_widget_market"

    fun fetchAndCache(context: Context, symbol: String): MarketData {
        val encoded = URLEncoder.encode(symbol, StandardCharsets.UTF_8.toString())
        val endpoint = "https://query1.finance.yahoo.com/v8/finance/chart/$encoded?range=1d&interval=5m&includePrePost=true"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MarketWidgets/1.2 Android")
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
            } else candles.last().toDouble()

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
            ).also { save(context, symbol, it) }
        } finally {
            connection.disconnect()
        }
    }

    fun cached(context: Context, symbol: String): MarketData? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = safeKey(symbol)
        if (!prefs.contains("price_$key")) return null
        val candles = prefs.getString("candles_$key", null)
            ?.split(',')
            ?.mapNotNull { it.toFloatOrNull() }
            .orEmpty()
        if (candles.isEmpty()) return null

        return MarketData(
            price = Double.fromBits(prefs.getLong("price_$key", 0L)),
            change = Double.fromBits(prefs.getLong("change_$key", 0L)),
            changePercent = Double.fromBits(prefs.getLong("changePercent_$key", 0L)),
            candles = candles,
            updatedAtMillis = prefs.getLong("updatedAt_$key", 0L)
        )
    }

    private fun save(context: Context, symbol: String, data: MarketData) {
        val key = safeKey(symbol)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong("price_$key", data.price.toBits())
            .putLong("change_$key", data.change.toBits())
            .putLong("changePercent_$key", data.changePercent.toBits())
            .putString("candles_$key", data.candles.joinToString(","))
            .putLong("updatedAt_$key", data.updatedAtMillis)
            .apply()
    }

    private fun safeKey(symbol: String): String = symbol.replace(Regex("[^A-Za-z0-9_-]"), "_")
}
