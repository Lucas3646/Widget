package com.lucas.nasdaqwidget

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec


data class KrakenPortfolioSnapshot(
    val totalEur: Double,
    val balances: Map<String, Double>,
    val updatedAt: Long
)

object KrakenPortfolioRepository {
    private const val PREFS = "kraken_portfolio_cache"
    private const val BASE = "https://api.kraken.com"

    fun refresh(context: Context): KrakenPortfolioSnapshot {
        val credentials = BrokerConnectionStore.krakenCredentials(context)
            ?: throw IllegalStateException("Identifiants Kraken absents")

        val balances = fetchBalances(credentials)
            .filterValues { kotlin.math.abs(it) > 0.00000001 }
        val total = balances.entries.sumOf { (asset, amount) ->
            amount * eurPrice(asset)
        }
        val snapshot = KrakenPortfolioSnapshot(total, balances, System.currentTimeMillis())
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
        return KrakenPortfolioSnapshot(
            totalEur = java.lang.Double.longBitsToDouble(prefs.getLong("total", 0L)),
            balances = balances,
            updatedAt = prefs.getLong("updated", 0L)
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun cache(context: Context, snapshot: KrakenPortfolioSnapshot) {
        val json = JSONObject()
        snapshot.balances.forEach { (asset, amount) -> json.put(asset, amount) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("total", java.lang.Double.doubleToRawLongBits(snapshot.totalEur))
            .putString("balances", json.toString())
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

    private fun eurPrice(rawAsset: String): Double {
        val asset = normalizeAsset(rawAsset)
        if (asset == "EUR") return 1.0
        val candidates = when (asset) {
            "XBT" -> listOf("XBTEUR", "XXBTZEUR")
            "USD" -> listOf("EURUSD")
            "USDT" -> listOf("USDTEUR")
            "USDC" -> listOf("USDCEUR")
            else -> listOf("${asset}EUR")
        }
        for (pair in candidates) {
            val price = fetchTicker(pair)
            if (price != null && price > 0) {
                return if (asset == "USD" && pair == "EURUSD") 1.0 / price else price
            }
        }
        return 0.0
    }

    private fun fetchTicker(pair: String): Double? {
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
            result.optJSONObject(firstKey)?.optJSONArray("c")?.optString(0)?.toDoubleOrNull()
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
