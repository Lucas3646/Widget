package com.lucas.nasdaqwidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class AlertRule(
    val id: Long,
    val symbol: String,
    val operator: String,
    val threshold: Double,
    val metric: String = METRIC_PRICE
) {
    fun isTriggered(value: Double): Boolean = when (operator) {
        ">" -> value > threshold
        ">=" -> value >= threshold
        "<=" -> value <= threshold
        else -> value < threshold
    }

    fun isMvrv(): Boolean = metric == METRIC_MVRV

    companion object {
        const val METRIC_PRICE = "PRICE"
        const val METRIC_MVRV = "MVRV_Z"
    }
}

object AlertStore {
    private const val PREFS = "market_alerts"
    private const val RULES_KEY = "rules"

    fun rules(context: Context): List<AlertRule> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(RULES_KEY, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(
                        AlertRule(
                            id = item.getLong("id"),
                            symbol = item.getString("symbol"),
                            operator = item.getString("operator"),
                            threshold = item.getDouble("threshold"),
                            metric = item.optString("metric", AlertRule.METRIC_PRICE)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun add(
        context: Context,
        symbol: String,
        operator: String,
        threshold: Double,
        metric: String = AlertRule.METRIC_PRICE
    ) {
        val normalized = normalizeSymbol(symbol)
        if (normalized.isBlank()) return
        val safeMetric = if (metric == AlertRule.METRIC_MVRV && normalized == "BTC-USD") {
            AlertRule.METRIC_MVRV
        } else {
            AlertRule.METRIC_PRICE
        }
        val updated = rules(context) + AlertRule(
            id = System.currentTimeMillis(),
            symbol = normalized,
            operator = operator,
            threshold = threshold,
            metric = safeMetric
        )
        save(context, updated)
    }

    fun remove(context: Context, id: Long) {
        save(context, rules(context).filterNot { it.id == id })
    }

    private fun save(context: Context, rules: List<AlertRule>) {
        val array = JSONArray()
        rules.forEach { rule ->
            array.put(JSONObject().apply {
                put("id", rule.id)
                put("symbol", rule.symbol)
                put("operator", rule.operator)
                put("threshold", rule.threshold)
                put("metric", rule.metric)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(RULES_KEY, array.toString())
            .apply()
    }

    private fun normalizeSymbol(input: String): String {
        val value = input.trim().uppercase().replace(" ", "")
        return when (value) {
            "BTCUSD", "BTC/USD", "BTC" -> "BTC-USD"
            "ETHUSD", "ETH/USD" -> "ETH-USD"
            else -> value
        }
    }
}

object AlertMarketRepository {
    private const val PREFS = "market_alert_prices"

    fun refresh(context: Context): Map<String, Double> {
        val rules = AlertStore.rules(context)
        val symbols = buildSet {
            rules.filterNot { it.isMvrv() }.forEach { add(it.symbol) }
            if (rules.any { it.isMvrv() }) add("BTC-USD")
        }
        val result = mutableMapOf<String, Double>()
        symbols.forEach { symbol ->
            runCatching { fetchPrice(symbol) }
                .onSuccess { price ->
                    result[symbol] = price
                    savePrice(context, symbol, price)
                }
        }
        return result
    }

    fun cachedPrice(context: Context, symbol: String): Double? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "price_$symbol"
        return if (prefs.contains(key)) Double.fromBits(prefs.getLong(key, 0L)) else null
    }

    fun lastUpdated(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("updatedAt", 0L)

    private fun savePrice(context: Context, symbol: String, price: Double) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong("price_$symbol", price.toBits())
            .putLong("updatedAt", System.currentTimeMillis())
            .apply()
    }

    private fun fetchPrice(symbol: String): Double {
        val encoded = URLEncoder.encode(symbol, StandardCharsets.UTF_8.toString())
        val endpoint = "https://query1.finance.yahoo.com/v8/finance/chart/$encoded?range=1d&interval=5m&includePrePost=true"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MarketWidget/1.0 Android")
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            val result = root.getJSONObject("chart").getJSONArray("result").getJSONObject(0)
            val meta = result.getJSONObject("meta")
            if (meta.has("regularMarketPrice") && !meta.isNull("regularMarketPrice")) {
                return meta.getDouble("regularMarketPrice")
            }
            val quote = result.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
            val closes = quote.getJSONArray("close")
            for (i in closes.length() - 1 downTo 0) {
                if (!closes.isNull(i)) return closes.getDouble(i)
            }
            throw IllegalStateException("No market price")
        } finally {
            connection.disconnect()
        }
    }
}
