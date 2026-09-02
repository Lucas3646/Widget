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
    private const val BASE_ENDPOINT =
        "https://community-api.coinmetrics.io/v4/timeseries/asset-metrics"

    fun refresh(context: Context): MvrvSnapshot {
        clearLastError(context)

        val direct = runCatching { fetchLatest("CapMVRVZ") }.getOrNull()
        val directZ = direct?.optString("CapMVRVZ")?.toDoubleOrNull()

        val historical = if (directZ == null) fetchHistoricalCaps() else null
        val computed = historical?.let { computeFromHistory(it) }

        val zScore = directZ ?: computed?.zScore
            ?: throw IllegalStateException("MVRV Z indisponible")

        val latestMarketCap = computed?.marketCap
            ?: runCatching { fetchLatest("CapMrktCurUSD") }
                .getOrNull()
                ?.optString("CapMrktCurUSD")
                ?.toDoubleOrNull()

        val latestRealizedCap = computed?.realizedCap
            ?: runCatching { fetchLatest("CapRealUSD") }
                .getOrNull()
                ?.optString("CapRealUSD")
                ?.toDoubleOrNull()

        val stdDev = computed?.stdDev ?: if (
            latestMarketCap != null && latestRealizedCap != null && kotlin.math.abs(zScore) > 0.000001
        ) {
            (latestMarketCap - latestRealizedCap) / zScore
        } else null

        val sourcePrice = runCatching { fetchLatest("PriceUSD") }
            .getOrNull()
            ?.optString("PriceUSD")
            ?.toDoubleOrNull()

        val targetPrice = if (
            latestMarketCap != null && latestMarketCap > 0.0 &&
            latestRealizedCap != null && stdDev != null && stdDev > 0.0 &&
            sourcePrice != null && sourcePrice > 0.0
        ) {
            val targetMarketCap = latestRealizedCap + HIGH_ZONE_Z * stdDev
            if (targetMarketCap > 0.0) sourcePrice * (targetMarketCap / latestMarketCap) else null
        } else null

        val snapshot = MvrvSnapshot(
            zScore = zScore,
            estimatedHighZonePrice = targetPrice,
            sourcePrice = sourcePrice,
            updatedAtMillis = System.currentTimeMillis()
        )
        save(context, snapshot)
        return snapshot
    }

    fun recordError(context: Context, throwable: Throwable) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("lastError", throwable.message ?: throwable.javaClass.simpleName)
            .putLong("lastErrorAt", System.currentTimeMillis())
            .apply()
    }

    fun lastError(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("lastError", null)

    private fun clearLastError(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove("lastError")
            .remove("lastErrorAt")
            .apply()
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
            val realizedCap = item.optString("CapRealUSD").toDoubleOrNull()
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
        val endpoint = buildString {
            append(BASE_ENDPOINT)
            append("?assets=btc")
            append("&metrics=CapMrktCurUSD,CapRealUSD")
            append("&frequency=1d")
            append("&page_size=10000")
            append("&paging_from=start")
            append("&ignore_forbidden_errors=true")
            append("&ignore_unsupported_errors=true")
        }
        return fetchData(endpoint)
    }

    private fun fetchLatest(metric: String): JSONObject {
        val endpoint = buildString {
            append(BASE_ENDPOINT)
            append("?assets=btc")
            append("&metrics=")
            append(metric)
            append("&frequency=1d")
            append("&limit_per_asset=1")
            append("&paging_from=end")
        }
        val data = fetchData(endpoint)
        if (data.length() == 0) throw IllegalStateException("Aucune donnée pour $metric")
        return data.getJSONObject(data.length() - 1)
    }

    private fun fetchData(endpoint: String): JSONArray {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 25_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Encoding", "gzip")
            setRequestProperty("User-Agent", "MarketWidgets/1.0 Android")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val error = runCatching {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull().orEmpty()
                throw IllegalStateException(
                    "Coin Metrics HTTP $code${if (error.isNotBlank()) ": ${error.take(120)}" else ""}"
                )
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            return root.optJSONArray("data")
                ?: throw IllegalStateException("Réponse Coin Metrics invalide")
        } finally {
            connection.disconnect()
        }
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
                snapshot.estimatedHighZonePrice?.let { putLong("estimatedHighZonePrice", it.toBits()) }
                    ?: remove("estimatedHighZonePrice")
                snapshot.sourcePrice?.let { putLong("sourcePrice", it.toBits()) }
                    ?: remove("sourcePrice")
            }
            .putLong("updatedAt", snapshot.updatedAtMillis)
            .remove("lastError")
            .remove("lastErrorAt")
            .apply()
    }
}
