package com.lucas.nasdaqwidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

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
    private const val MAX_QUOTE_AGE = 45 * 60 * 1000L

    fun symbols(context: Context): Set<String> =
        AlertStore.rules(context).filterNot(AlertRule::isMvrv).map(AlertRule::symbol).toSet()

    fun refresh(context: Context): Set<String> {
        val failed = mutableSetOf<String>()
        for (symbol in symbols(context)) {
            runCatching { MarketRepository.fetchAndCache(context, symbol) }.onFailure { failed += symbol }
        }
        return failed
    }

    fun cachedPrice(context: Context, symbol: String): Double? {
        val quote = MarketRepository.cached(context, symbol) ?: return null
        val timestamp = quote.quotedAtMillis ?: return null
        val age = System.currentTimeMillis() - timestamp
        return quote.price.takeIf { age in 0..MAX_QUOTE_AGE }
    }
}
