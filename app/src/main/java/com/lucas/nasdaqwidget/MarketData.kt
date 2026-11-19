package com.lucas.nasdaqwidget

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class MarketData(
    val price: Double,
    val change: Double?,
    val changePercent: Double?,
    val candles: List<Float>,
    val quotedAtMillis: Long?

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
        val endpoint = "https://query1.finance.yahoo.com/v8/finance/chart/$encoded?range=$range&interval=$interval&includePrePost=false"
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
            val times = result.optJSONArray("timestamp")
            val points = (0 until closes.length()).mapNotNull { index ->
                val close = closes.optDouble(index).takeIf { it.isFinite() && it.toFloat().isFinite() }
                close?.let { it to (times?.optLong(index)?.takeIf { seconds -> seconds > 0 }?.times(1000L)) }
            }
            require(points.isNotEmpty()) { "Aucune clôture disponible" }
            val marketPrice = meta.optDouble("regularMarketPrice").takeIf(Double::isFinite)
            val price = marketPrice ?: points.last().first
            val quotedAt = if (marketPrice != null) {
                meta.optLong("regularMarketTime").takeIf { it > 0 }?.times(1000L)
            } else points.last().second
            val baseline = if (timeframe == WidgetAssetConfig.TF_1D) {
                sequenceOf("chartPreviousClose", "previousClose").map(meta::optDouble).firstOrNull(Double::isFinite)
            } else points.first().first.takeIf { points.size >= 2 }
            val change = baseline?.let { price - it }
            val percent = baseline?.takeIf { it != 0.0 }?.let { (price - it) / kotlin.math.abs(it) * 100.0 }
            return MarketData(price, change, percent, points.map { it.first.toFloat() }, quotedAt)
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
            change = Double.fromBits(prefs.getLong("change_$key", Double.NaN.toBits())).takeIf(Double::isFinite),
            changePercent = Double.fromBits(prefs.getLong("changePercent_$key", Double.NaN.toBits())).takeIf(Double::isFinite),
            candles = candles,
            quotedAtMillis = prefs.getLong("quotedAt_$key", 0L).takeIf { it > 0 }
        )
    }

    private fun save(context: Context, symbol: String, timeframe: String, data: MarketData) {
        val key = safeKey("${symbol}_$timeframe")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("price_$key", data.price.toBits())
            .putLong("change_$key", (data.change ?: Double.NaN).toBits())
            .putLong("changePercent_$key", (data.changePercent ?: Double.NaN).toBits())
            .putString("candles_$key", data.candles.joinToString(","))
            .putLong("quotedAt_$key", data.quotedAtMillis ?: 0L)
            .apply()
    }

    private fun safeKey(symbol: String): String = "v2_$symbol"
}
