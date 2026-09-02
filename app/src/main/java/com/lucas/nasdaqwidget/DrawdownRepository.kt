package com.lucas.nasdaqwidget

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.max

data class DrawdownSnapshot(
    val currentPrice: Double,
    val athPrice: Double,
    val drawdownPercent: Double,
    val updatedAtMillis: Long,
    val fullRefreshAtMillis: Long
)

object DrawdownRepository {
    private const val PREFS = "ath_drawdown_cache"
    private const val FULL_REFRESH_MS = 24L * 60L * 60L * 1000L

    fun refresh(context: Context, symbol: String): DrawdownSnapshot {
        val cached = cached(context, symbol)
        val now = System.currentTimeMillis()

        return if (cached == null || now - cached.fullRefreshAtMillis >= FULL_REFRESH_MS) {
            fetchFullHistory(context, symbol)
        } else {
            val current = MarketRepository.fetchAndCache(context, symbol).price
            val ath = max(cached.athPrice, current)
            save(
                context,
                symbol,
                DrawdownSnapshot(
                    currentPrice = current,
                    athPrice = ath,
                    drawdownPercent = drawdown(current, ath),
                    updatedAtMillis = now,
                    fullRefreshAtMillis = cached.fullRefreshAtMillis
                )
            )
        }
    }

    private fun fetchFullHistory(context: Context, symbol: String): DrawdownSnapshot {
        val encoded = URLEncoder.encode(symbol, StandardCharsets.UTF_8.toString())
        val endpoint = "https://query1.finance.yahoo.com/v8/finance/chart/$encoded" +
            "?range=max&interval=1wk&includePrePost=false&includeAdjustedClose=true"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MarketWidgets/1.3 Android")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("Yahoo ATH HTTP $code")

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val result = JSONObject(body)
                .getJSONObject("chart")
                .getJSONArray("result")
                .getJSONObject(0)
            val meta = result.getJSONObject("meta")
            val indicators = result.getJSONObject("indicators")
            val quote = indicators.getJSONArray("quote").getJSONObject(0)
            val highs = quote.getJSONArray("high")
            val closes = quote.optJSONArray("close")
            val adjClose = indicators.optJSONArray("adjclose")?.optJSONObject(0)?.optJSONArray("adjclose")

            var ath = Double.NEGATIVE_INFINITY
            for (i in 0 until highs.length()) {
                if (highs.isNull(i)) continue
                val high = highs.optDouble(i, Double.NaN)
                if (!high.isFinite() || high <= 0.0) continue

                // Adjust historical highs for stock splits when Yahoo provides adjusted close.
                val adjustedHigh = if (
                    closes != null && adjClose != null &&
                    i < closes.length() && i < adjClose.length() &&
                    !closes.isNull(i) && !adjClose.isNull(i)
                ) {
                    val close = closes.optDouble(i, Double.NaN)
                    val adjusted = adjClose.optDouble(i, Double.NaN)
                    if (close.isFinite() && adjusted.isFinite() && close > 0.0) high * (adjusted / close) else high
                } else high

                if (adjustedHigh.isFinite()) ath = max(ath, adjustedHigh)
            }
            if (!ath.isFinite() || ath <= 0.0) throw IllegalStateException("ATH unavailable")

            val current = when {
                meta.has("regularMarketPrice") && !meta.isNull("regularMarketPrice") -> meta.getDouble("regularMarketPrice")
                closes != null -> {
                    var last: Double? = null
                    for (i in closes.length() - 1 downTo 0) {
                        if (!closes.isNull(i)) {
                            last = closes.optDouble(i, Double.NaN)
                            if (last.isFinite() && last > 0.0) break
                        }
                    }
                    last ?: throw IllegalStateException("Current price unavailable")
                }
                else -> throw IllegalStateException("Current price unavailable")
            }

            ath = max(ath, current)
            val now = System.currentTimeMillis()
            val snapshot = DrawdownSnapshot(
                currentPrice = current,
                athPrice = ath,
                drawdownPercent = drawdown(current, ath),
                updatedAtMillis = now,
                fullRefreshAtMillis = now
            )
            save(context, symbol, snapshot)
            return snapshot
        } finally {
            connection.disconnect()
        }
    }

    fun cached(context: Context, symbol: String): DrawdownSnapshot? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = safeKey(symbol)
        if (!prefs.contains("ath_$key")) return null
        val current = Double.fromBits(prefs.getLong("current_$key", 0L))
        val ath = Double.fromBits(prefs.getLong("ath_$key", 0L))
        if (!current.isFinite() || !ath.isFinite() || current <= 0.0 || ath <= 0.0) return null
        return DrawdownSnapshot(
            currentPrice = current,
            athPrice = ath,
            drawdownPercent = drawdown(current, ath),
            updatedAtMillis = prefs.getLong("updated_$key", 0L),
            fullRefreshAtMillis = prefs.getLong("full_$key", 0L)
        )
    }

    private fun save(context: Context, symbol: String, snapshot: DrawdownSnapshot): DrawdownSnapshot {
        val key = safeKey(symbol)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong("current_$key", snapshot.currentPrice.toBits())
            .putLong("ath_$key", snapshot.athPrice.toBits())
            .putLong("updated_$key", snapshot.updatedAtMillis)
            .putLong("full_$key", snapshot.fullRefreshAtMillis)
            .apply()
        return snapshot
    }

    private fun drawdown(current: Double, ath: Double): Double =
        if (ath > 0.0) (current / ath - 1.0) * 100.0 else 0.0

    private fun safeKey(symbol: String): String = symbol.replace(Regex("[^A-Za-z0-9_-]"), "_")
}
