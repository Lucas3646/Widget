package com.lucas.nasdaqwidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.sqrt

data class MvrvSnapshot(
    val zScore: Double,
    val estimatedHighZonePrice: Double?,
    val sourcePrice: Double?,
    val updatedAtMillis: Long
)

object MvrvRepository {
    private const val PREFS = "btc_mvrv_snapshot"
    private const val HIGH_ZONE_Z = 7.0
    private const val COIN_METRICS = "https://community-api.coinmetrics.io/v4/timeseries/asset-metrics"
    private const val BGEOMETRICS = "https://bitcoin-data.com/v1"

    fun refresh(context: Context): MvrvSnapshot {
        clearLastError(context)

        // Coin Metrics made CapMVRVZ an advanced metric. Prefer the public
        // BGeometrics endpoint, which exposes MVRV Z-Score without a token,
        // then keep Coin Metrics as a fallback for resilience.
        val bgZ = runCatching {
            val body = getJson("$BGEOMETRICS/mvrv-zscore/last")
            extractNumber(body, listOf("mvrv-zscore", "mvrv_zscore", "mvrvZScore", "zScore", "zscore", "value"))
        }.getOrNull()

        val direct = if (bgZ == null) runCatching { fetchLatest("CapMVRVZ") }.getOrNull() else null
        val directZ = direct?.optString("CapMVRVZ")?.toDoubleOrNull()

        val historical = if (bgZ == null && directZ == null) runCatching { fetchHistoricalCaps() }.getOrNull() else null
        val computed = historical?.let { computeFromHistory(it) }

        val zScore = bgZ ?: directZ ?: computed?.zScore
            ?: throw IllegalStateException("MVRV Z indisponible sur les sources publiques")

        val sourcePrice = runCatching {
            val body = getJson("$BGEOMETRICS/btc-price/last")
            extractNumber(body, listOf("price", "btcPrice", "btc_price", "close", "value"))
        }.getOrNull() ?: runCatching {
            fetchLatest("PriceUSD").optString("PriceUSD").toDoubleOrNull()
        }.getOrNull()

        // Keep the Z7 price estimate only when the cap inputs are available.
        // The Z-score itself remains usable even when Coin Metrics restricts
        // those auxiliary fields.
        val capData = computed ?: runCatching { computeFromHistory(fetchHistoricalCaps()) }.getOrNull()
        val targetPrice = if (
            capData != null && sourcePrice != null && sourcePrice > 0.0 && capData.marketCap > 0.0
        ) {
            val targetMarketCap = capData.realizedCap + HIGH_ZONE_Z * capData.stdDev
            if (targetMarketCap > 0.0) sourcePrice * (targetMarketCap / capData.marketCap) else null
        } else null

        return MvrvSnapshot(
            zScore = zScore,
            estimatedHighZonePrice = targetPrice,
            sourcePrice = sourcePrice,
            updatedAtMillis = System.currentTimeMillis()
        ).also { save(context, it) }
    }

    fun recordError(context: Context, throwable: Throwable) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("lastError", throwable.message ?: throwable.javaClass.simpleName)
            .putLong("lastErrorAt", System.currentTimeMillis())
            .apply()
    }

    fun lastError(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("lastError", null)

    private fun clearLastError(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove("lastError").remove("lastErrorAt").apply()
    }

    private data class ComputedMvrv(
        val zScore: Double,
        val marketCap: Double,
        val realizedCap: Double,
        val stdDev: Double
    )

    private fun computeFromHistory(data: JSONArray): ComputedMvrv? {
        val marketCaps = ArrayList<Double>(data.length())
        var latestMarketCap: Double? = null
        var latestRealizedCap: Double? = null

        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val marketCap = item.optString("CapMrktCurUSD").toDoubleOrNull()
            val realizedCap = sequenceOf("CapRealUSD", "CapRealizedUSD")
                .mapNotNull { item.optString(it).toDoubleOrNull() }
                .firstOrNull()
            if (marketCap != null && marketCap.isFinite() && marketCap > 0.0) {
                marketCaps += marketCap
                if (realizedCap != null && realizedCap.isFinite() && realizedCap > 0.0) {
                    latestMarketCap = marketCap
                    latestRealizedCap = realizedCap
                }
            }
        }

        if (marketCaps.size < 30 || latestMarketCap == null || latestRealizedCap == null) return null
        val mean = marketCaps.average()
        var sumSquared = 0.0
        marketCaps.forEach { value ->
            val d = value - mean
            sumSquared += d * d
        }
        val stdDev = sqrt(sumSquared / marketCaps.size)
        if (!stdDev.isFinite() || stdDev <= 0.0) return null

        return ComputedMvrv(
            zScore = (latestMarketCap - latestRealizedCap) / stdDev,
            marketCap = latestMarketCap,
            realizedCap = latestRealizedCap,
            stdDev = stdDev
        )
    }

    private fun fetchHistoricalCaps(): JSONArray {
        // Try both realized-cap field names because community coverage has
        // changed over time.
        val metricSets = listOf(
            "CapMrktCurUSD,CapRealUSD",
            "CapMrktCurUSD,CapRealizedUSD"
        )
        var last: Throwable? = null
        metricSets.forEach { metrics ->
            runCatching {
                fetchData(
                    "$COIN_METRICS?assets=btc&metrics=$metrics&frequency=1d&page_size=10000&paging_from=start&ignore_forbidden_errors=true&ignore_unsupported_errors=true"
                )
            }.onSuccess { data -> if (data.length() > 0) return data }
                .onFailure { last = it }
        }
        throw last ?: IllegalStateException("Historique MVRV indisponible")
    }

    private fun fetchLatest(metric: String): JSONObject {
        val data = fetchData("$COIN_METRICS?assets=btc&metrics=$metric&frequency=1d&limit_per_asset=1&paging_from=end&ignore_forbidden_errors=true&ignore_unsupported_errors=true")
        if (data.length() == 0) throw IllegalStateException("Aucune donnée pour $metric")
        return data.getJSONObject(data.length() - 1)
    }

    private fun fetchData(endpoint: String): JSONArray {
        val body = getJson(endpoint)
        val root = JSONObject(body)
        return root.optJSONArray("data") ?: throw IllegalStateException("Réponse Coin Metrics invalide")
    }

    private fun getJson(endpoint: String): String {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 25_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MarketWidgets/1.9 Android")
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code${if (body.isNotBlank()) ": ${body.take(100)}" else ""}")
            body
        } finally {
            connection.disconnect()
        }
    }

    private fun extractNumber(body: String, preferredKeys: List<String>): Double? {
        val parsed: Any = runCatching { JSONObject(body) }.getOrElse {
            runCatching { JSONArray(body) }.getOrNull() ?: return null
        }
        return findNumber(parsed, preferredKeys.map { it.lowercase() }.toSet())
    }

    private fun findNumber(node: Any?, preferred: Set<String>): Double? {
        when (node) {
            is JSONObject -> {
                val keys = node.keys().asSequence().toList()
                keys.firstOrNull { it.lowercase() in preferred }?.let { key ->
                    val raw = node.opt(key)
                    when (raw) {
                        is Number -> return raw.toDouble()
                        is String -> raw.replace(",", "").toDoubleOrNull()?.let { return it }
                    }
                }
                keys.forEach { key -> findNumber(node.opt(key), preferred)?.let { return it } }
            }
            is JSONArray -> for (i in 0 until node.length()) findNumber(node.opt(i), preferred)?.let { return it }
        }
        return null
    }

    fun cached(context: Context): MvrvSnapshot? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains("zScore")) return null
        val zoneBits = prefs.getLong("estimatedHighZonePrice", Long.MIN_VALUE)
        val sourceBits = prefs.getLong("sourcePrice", Long.MIN_VALUE)
        return MvrvSnapshot(
            zScore = Double.fromBits(prefs.getLong("zScore", 0L)),
            estimatedHighZonePrice = if (zoneBits == Long.MIN_VALUE) null else Double.fromBits(zoneBits),
            sourcePrice = if (sourceBits == Long.MIN_VALUE) null else Double.fromBits(sourceBits),
            updatedAtMillis = prefs.getLong("updatedAt", 0L)
        )
    }

    fun zoneLabel(zScore: Double): String = when {
        zScore < 0.0 -> "Sous-évalué"
        zScore < 2.0 -> "Basse"
        zScore < 5.0 -> "Neutre"
        zScore < HIGH_ZONE_Z -> "Chaude"
        else -> "Haute"
    }

    fun highZoneZ(): Double = HIGH_ZONE_Z

    private fun save(context: Context, snapshot: MvrvSnapshot) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong("zScore", snapshot.zScore.toBits())
            .apply {
                snapshot.estimatedHighZonePrice?.let { putLong("estimatedHighZonePrice", it.toBits()) } ?: remove("estimatedHighZonePrice")
                snapshot.sourcePrice?.let { putLong("sourcePrice", it.toBits()) } ?: remove("sourcePrice")
            }
            .putLong("updatedAt", snapshot.updatedAtMillis)
            .remove("lastError").remove("lastErrorAt")
            .apply()
    }
}
