package com.lucas.nasdaqwidget

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class MvrvSnapshot(
    val zScore: Double,
    val estimatedHighZonePrice: Double?,
    val sourcePrice: Double?,
    val updatedAtMillis: Long
)

object MvrvRepository {
    private const val PREFS = "btc_mvrv_snapshot"
    private const val HIGH_ZONE_Z = 7.0
    private const val ENDPOINT =
        "https://community-api.coinmetrics.io/v4/timeseries/asset-metrics" +
            "?assets=btc" +
            "&metrics=CapMVRVZ,CapMrktCurUSD,CapRealUSD,SplyCur,PriceUSD" +
            "&frequency=1d&limit_per_asset=1&paging_from=end"

    fun refresh(context: Context): MvrvSnapshot {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MarketWidgets/1.0 Android")
        }

        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Coin Metrics HTTP ${connection.responseCode}")
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            val data = root.getJSONArray("data")
            if (data.length() == 0) throw IllegalStateException("No MVRV data")
            val item = data.getJSONObject(data.length() - 1)

            val zScore = item.optString("CapMVRVZ").toDoubleOrNull()
                ?: throw IllegalStateException("Missing CapMVRVZ")
            val marketCap = item.optString("CapMrktCurUSD").toDoubleOrNull()
            val realizedCap = item.optString("CapRealUSD").toDoubleOrNull()
            val supply = item.optString("SplyCur").toDoubleOrNull()
            val sourcePrice = item.optString("PriceUSD").toDoubleOrNull()

            val estimatedHighZonePrice = if (
                marketCap != null && realizedCap != null && supply != null && supply > 0.0 && kotlin.math.abs(zScore) > 0.000001
            ) {
                val marketCapStdDev = (marketCap - realizedCap) / zScore
                val targetMarketCap = realizedCap + HIGH_ZONE_Z * marketCapStdDev
                if (targetMarketCap > 0.0) targetMarketCap / supply else null
            } else null

            val snapshot = MvrvSnapshot(
                zScore = zScore,
                estimatedHighZonePrice = estimatedHighZonePrice,
                sourcePrice = sourcePrice,
                updatedAtMillis = System.currentTimeMillis()
            )
            save(context, snapshot)
            return snapshot
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
            .apply()
    }
}
