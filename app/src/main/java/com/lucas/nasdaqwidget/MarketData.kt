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

    fun fetchAndCache(context: Context, symbol: String): MarketData = fetchAndCache(context, symbol, WidgetAssetConfig.TF_1D)

    fun fetchAndCache(context: Context, symbol: String, timeframe: String): MarketData {
        val encoded = URLEncoder.encode(symbol, StandardCharsets.UTF_8.toString())
        val (range, interval) = when (timeframe) {
            WidgetAssetConfig.TF_5D -> "5d" to "15m"
            WidgetAssetConfig.TF_1M -> "1mo" to "1h"
            WidgetAssetConfig.TF_3M -> "3mo" to "1d"
            WidgetAssetConfig.TF_YTD -> "ytd" to "1d"
            WidgetAssetConfig.TF_1Y -> "1y" to "1d"
            else -> "1d" to "5m"
        }
        val endpoint = "https://query1.finance.yahoo.com/v8/finance/chart/$encoded?range=$range&interval=$interval&includePrePost=true"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MarketWidgets/2.0 Android")
        }

        try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("Market data HTTP ${connection.responseCode}")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            val result = root.getJSONObject("chart").getJSONArray("result").getJSONObject(0)
            val meta = result.getJSONObject("meta")
            val quote = result.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
            val closes = quote.getJSONArray("close")
            val candles = buildList {
                for (i in 0 until closes.length()) if (!closes.isNull(i)) add(closes.getDouble(i).toFloat())
            }
            if (candles.isEmpty()) throw IllegalStateException("No candles returned")
            val price = if (meta.has("regularMarketPrice") && !meta.isNull("regularMarketPrice")) meta.getDouble("regularMarketPrice") else candles.last().toDouble()
            val baseline = if (timeframe == WidgetAssetConfig.TF_1D) {
                when {
                    meta.has("chartPreviousClose") && !meta.isNull("chartPreviousClose") -> meta.getDouble("chartPreviousClose")
                    meta.has("previousClose") && !meta.isNull("previousClose") -> meta.getDouble("previousClose")
                    else -> candles.first().toDouble()
                }
            } else candles.first().toDouble()
            val change = price - baseline
            val changePercent = if (baseline != 0.0) change / baseline * 100.0 else 0.0
            return MarketData(price, change, changePercent, candles, System.currentTimeMillis())
                .also { save(context, symbol, timeframe, it) }
        } finally { connection.disconnect() }
    }

    fun cached(context: Context, symbol: String): MarketData? = cached(context, symbol, WidgetAssetConfig.TF_1D)

    fun cached(context: Context, symbol: String, timeframe: String): MarketData? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = safeKey("${symbol}_$timeframe")
        if (!prefs.contains("price_$key")) return null
        val candles = prefs.getString("candles_$key", null)?.split(',')?.mapNotNull { it.toFloatOrNull() }.orEmpty()
        if (candles.isEmpty()) return null
        return MarketData(
            price = Double.fromBits(prefs.getLong("price_$key", 0L)),
            change = Double.fromBits(prefs.getLong("change_$key", 0L)),
            changePercent = Double.fromBits(prefs.getLong("changePercent_$key", 0L)),
            candles = candles,
            updatedAtMillis = prefs.getLong("updatedAt_$key", 0L)
        )
    }

    private fun save(context: Context, symbol: String, timeframe: String, data: MarketData) {
        val key = safeKey("${symbol}_$timeframe")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("price_$key", data.price.toBits())
            .putLong("change_$key", data.change.toBits())
            .putLong("changePercent_$key", data.changePercent.toBits())
            .putString("candles_$key", data.candles.joinToString(","))
            .putLong("updatedAt_$key", data.updatedAtMillis)
            .apply()
    }

    private fun safeKey(symbol: String): String = symbol.replace(Regex("[^A-Za-z0-9_-]"), "_")
}
