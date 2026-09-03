package com.lucas.nasdaqwidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object MvrvFallbackRepository {
    private const val PREFS = "mvrv_fallback"

    fun refresh(context: Context): Double {
        val urls = listOf(
            "https://bitcoin-data.com/v1/mvrv-zscore/last",
            "https://bitcoin-data.com/api/v1/mvrv-zscore/last"
        )
        var last: Throwable? = null
        urls.forEach { endpoint ->
            runCatching { fetch(endpoint) }.onSuccess { value ->
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong("z", value.toBits())
                    .putLong("updatedAt", System.currentTimeMillis())
                    .apply()
                return value
            }.onFailure { last = it }
        }
        throw last ?: IllegalStateException("MVRV fallback unavailable")
    }

    fun cached(context: Context): Double? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (p.contains("z")) Double.fromBits(p.getLong("z", 0L)) else null
    }

    private fun fetch(endpoint: String): Double {
        val c = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MarketWidgets/2.0 Android")
        }
        return try {
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("MVRV HTTP $code")
            parse(body) ?: error("MVRV field missing")
        } finally { c.disconnect() }
    }

    private fun parse(body: String): Double? {
        val preferred = setOf("mvrv-zscore", "mvrv-z-score", "mvrv_zscore", "mvrv_z_score", "mvrvzscore", "zscore", "z_score", "value")
        fun walk(node: Any?): Double? = when (node) {
            is JSONObject -> {
                val keys = node.keys().asSequence().toList()
                keys.firstOrNull { it.lowercase() in preferred }?.let { key ->
                    val raw = node.opt(key)
                    when (raw) {
                        is Number -> raw.toDouble()
                        is String -> raw.toDoubleOrNull()
                        else -> null
                    }
                } ?: keys.asSequence().mapNotNull { walk(node.opt(it)) }.firstOrNull()
            }
            is JSONArray -> (0 until node.length()).asSequence().mapNotNull { walk(node.opt(it)) }.firstOrNull()
            is Number -> node.toDouble()
            is String -> node.toDoubleOrNull()
            else -> null
        }
        val root: Any = runCatching { JSONObject(body) }.getOrElse { runCatching { JSONArray(body) }.getOrNull() ?: return null }
        return walk(root)
    }
}
