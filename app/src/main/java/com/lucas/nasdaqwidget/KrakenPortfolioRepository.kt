package com.lucas.nasdaqwidget

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec


data class KrakenPositionSnapshot(
    val symbol: String,
    val valueEur: Double,
    val dayChangeEur: Double,
    val dayChangePercent: Double
)

data class KrakenPortfolioSnapshot(
    val totalEur: Double,
    val dayChangeEur: Double,
    val dayChangePercent: Double,
    val balances: Map<String, Double>,
    val positions: List<KrakenPositionSnapshot>,
    val updatedAt: Long
)

private data class TickerSnapshot(val current: Double, val open: Double)

object KrakenPortfolioRepository {
    private const val PREFS = "kraken_portfolio_cache"
    private const val BASE = "https://api.kraken.com"
    private val cashLikeAssets = setOf("EUR", "USD", "GBP", "CHF", "CAD", "AUD", "JPY", "USDT", "USDC")

    fun refresh(context: Context): KrakenPortfolioSnapshot {
        val credentials = BrokerConnectionStore.krakenCredentials(context)
            ?: throw IllegalStateException("Identifiants Kraken absents")

        val balances = fetchBalances(credentials)
            .filterValues { kotlin.math.abs(it) > 0.00000001 }

        var total = 0.0
        var totalDayChange = 0.0
        val positions = mutableListOf<KrakenPositionSnapshot>()

        balances.forEach { (rawAsset, amount) ->
            val symbol = normalizeAsset(rawAsset)
            val ticker = eurTicker(rawAsset)
            val current = ticker?.current ?: 0.0
            val open = ticker?.open ?: current
            val value = amount * current
            val dayChange = amount * (current - open)
            val dayPercent = if (open > 0) (current / open - 1.0) * 100.0 else 0.0

            total += value
            totalDayChange += dayChange

            if (symbol !in cashLikeAssets && current > 0 && value > 0.01) {
                positions += KrakenPositionSnapshot(
                    symbol = if (symbol == "XBT") "BTC" else symbol,
                    valueEur = value,
                    dayChangeEur = dayChange,
                    dayChangePercent = dayPercent
                )
            }
        }

        val previous = total - totalDayChange
        val totalDayPercent = if (previous > 0) totalDayChange / previous * 100.0 else 0.0
        val snapshot = KrakenPortfolioSnapshot(
            totalEur = total,
            dayChangeEur = totalDayChange,
            dayChangePercent = totalDayPercent,
            balances = balances,
            positions = positions,
            updatedAt = System.currentTimeMillis()
        )
        cache(context, snapshot)
        BrokerConnectionStore.setKrakenVerified(context, true)
        return snapshot
    }

    fun cached(context: Context): KrakenPortfolioSnapshot? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString("balances", null) ?: return null
        val objectJson = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val balances = mutableMapOf<String, Double>()
        objectJson.keys().forEach { key -> balances[key] = objectJson.optDouble(key, 0.0) }

        val positions = mutableListOf<KrakenPositionSnapshot>()
        val positionRaw = prefs.getString("positions", null)
        if (positionRaw != null) {
            runCatching { JSONArray(positionRaw) }.getOrNull()?.let { array ->
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    positions += KrakenPositionSnapshot(
                        symbol = item.optString("symbol"),
                        valueEur = item.optDouble("valueEur", 0.0),
                        dayChangeEur = item.optDouble("dayChangeEur", 0.0),
                        dayChangePercent = item.optDouble("dayChangePercent", 0.0)
                    )
                }
            }
        }

        return KrakenPortfolioSnapshot(
            totalEur = java.lang.Double.longBitsToDouble(prefs.getLong("total", 0L)),
            dayChangeEur = java.lang.Double.longBitsToDouble(prefs.getLong("dayChange", 0L)),
            dayChangePercent = java.lang.Double.longBitsToDouble(prefs.getLong("dayPercent", 0L)),
            balances = balances,
            positions = positions,
            updatedAt = prefs.getLong("updated", 0L)
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun cache(context: Context, snapshot: KrakenPortfolioSnapshot) {
        val balancesJson = JSONObject()
        snapshot.balances.forEach { (asset, amount) -> balancesJson.put(asset, amount) }
        val positionsJson = JSONArray()
        snapshot.positions.forEach { position ->
            positionsJson.put(JSONObject().apply {
                put("symbol", position.symbol)
                put("valueEur", position.valueEur)
                put("dayChangeEur", position.dayChangeEur)
                put("dayChangePercent", position.dayChangePercent)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("total", java.lang.Double.doubleToRawLongBits(snapshot.totalEur))
            .putLong("dayChange", java.lang.Double.doubleToRawLongBits(snapshot.dayChangeEur))
            .putLong("dayPercent", java.lang.Double.doubleToRawLongBits(snapshot.dayChangePercent))
            .putString("balances", balancesJson.toString())
            .putString("positions", positionsJson.toString())
            .putLong("updated", snapshot.updatedAt)
            .apply()
    }

    private fun fetchBalances(credentials: BrokerConnectionStore.KrakenCredentials): Map<String, Double> {
        val path = "/0/private/Balance"
        val nonce = System.currentTimeMillis().toString()
        val postData = "nonce=" + URLEncoder.encode(nonce, "UTF-8")
        val signature = sign(path, nonce, postData, credentials.apiSecret)
        val connection = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 12_000
            doOutput = true
            setRequestProperty("API-Key", credentials.apiKey)
            setRequestProperty("API-Sign", signature)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("User-Agent", "MarketWidgets/1.0")
        }
        connection.outputStream.use { it.write(postData.toByteArray(StandardCharsets.UTF_8)) }
        val body = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
            .bufferedReader().use { it.readText() }
        val json = JSONObject(body)
        val errors = json.optJSONArray("error")
        if (errors != null && errors.length() > 0) {
            throw IllegalStateException(errors.optString(0, "Erreur Kraken"))
        }
        val result = json.optJSONObject("result") ?: throw IllegalStateException("Réponse Kraken invalide")
        val balances = linkedMapOf<String, Double>()
        result.keys().forEach { key ->
            val amount = result.optString(key).toDoubleOrNull() ?: 0.0
            if (amount != 0.0) balances[key] = amount
        }
        return balances
    }

    private fun sign(path: String, nonce: String, postData: String, secretBase64: String): String {
        val sha256 = MessageDigest.getInstance("SHA-256")
        val hash = sha256.digest((nonce + postData).toByteArray(StandardCharsets.UTF_8))
        val message = path.toByteArray(StandardCharsets.UTF_8) + hash
        val secret = Base64.decode(secretBase64, Base64.DEFAULT)
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(secret, "HmacSHA512"))
        return Base64.encodeToString(mac.doFinal(message), Base64.NO_WRAP)
    }

    private fun eurTicker(rawAsset: String): TickerSnapshot? {
        val asset = normalizeAsset(rawAsset)
        if (asset == "EUR") return TickerSnapshot(1.0, 1.0)
        val candidates = when (asset) {
            "XBT" -> listOf("XBTEUR" to false, "XXBTZEUR" to false)
            "USD" -> listOf("EURUSD" to true)
            "USDT" -> listOf("USDTEUR" to false)
            "USDC" -> listOf("USDCEUR" to false)
            else -> listOf("${asset}EUR" to false)
        }
        for ((pair, inverse) in candidates) {
            val ticker = fetchTicker(pair) ?: continue
            if (ticker.current <= 0 || ticker.open <= 0) continue
            return if (inverse) TickerSnapshot(1.0 / ticker.current, 1.0 / ticker.open) else ticker
        }
        return null
    }

    private fun fetchTicker(pair: String): TickerSnapshot? {
        return runCatching {
            val encoded = URLEncoder.encode(pair, "UTF-8")
            val connection = (URL("$BASE/0/public/Ticker?pair=$encoded").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", "MarketWidgets/1.0")
            }
            if (connection.responseCode !in 200..299) return@runCatching null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val result = json.optJSONObject("result") ?: return@runCatching null
            val firstKey = result.keys().asSequence().firstOrNull() ?: return@runCatching null
            val ticker = result.optJSONObject(firstKey) ?: return@runCatching null
            val current = ticker.optJSONArray("c")?.optString(0)?.toDoubleOrNull() ?: return@runCatching null
            val open = ticker.optString("o").toDoubleOrNull() ?: current
            TickerSnapshot(current, open)
        }.getOrNull()
    }

    private fun normalizeAsset(raw: String): String {
        val withoutSuffix = raw.substringBefore('.')
        return when (withoutSuffix) {
            "ZEUR" -> "EUR"
            "ZUSD" -> "USD"
            "XXBT" -> "XBT"
            "XETH" -> "ETH"
            else -> withoutSuffix.removePrefix("X").removePrefix("Z")
        }
    }
}
