package com.lucas.nasdaqwidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class MacroSnapshot(
    val title: String,
    val country: String,
    val eventAtMillis: Long,
    val actual: String?,
    val forecast: String?,
    val previous: String?,
    val importance: Int,
    val released: Boolean,
    val updatedAtMillis: Long
)

private data class MacroEvent(
    val title: String,
    val country: String,
    val eventAtMillis: Long,
    val actual: String?,
    val forecast: String?,
    val previous: String?,
    val importance: Int
)

object MacroRepository {
    private const val PREFS = "macro_widget_cache"
    private const val ENDPOINT = "https://economic-calendar.tradingview.com/events"

    private val majorKeywords = listOf(
        "cpi", "inflation", "pce", "nonfarm", "non-farm", "payroll", "unemployment",
        "fomc", "fed interest", "interest rate decision", "federal funds", "ecb", "bce",
        "gdp", "retail sales", "ism", "pmi", "core pce", "core cpi"
    )

    fun refresh(context: Context): MacroSnapshot {
        val now = System.currentTimeMillis()
        val from = Instant.ofEpochMilli(now - 3L * 60 * 60 * 1000)
        val to = Instant.ofEpochMilli(now + 10L * 24 * 60 * 60 * 1000)
        val events = fetchEvents(from, to)
        if (events.isEmpty()) throw IllegalStateException("Calendrier macro indisponible")

        val major = events.filter { event ->
            event.importance >= 3 && majorKeywords.any { key -> event.title.lowercase().contains(key) }
        }
        val highImpact = events.filter { it.importance >= 3 }
        val pool = when {
            major.isNotEmpty() -> major
            highImpact.isNotEmpty() -> highImpact
            else -> events
        }

        // Keep a just-published event visible long enough to actually see the result.
        val recentReleased = pool
            .filter { it.actual != null && it.eventAtMillis <= now && now - it.eventAtMillis <= 2L * 60 * 60 * 1000 }
            .maxByOrNull { it.eventAtMillis }
        val next = pool.filter { it.eventAtMillis >= now }.minByOrNull { it.eventAtMillis }
        val selected = recentReleased ?: next ?: pool.maxByOrNull { it.eventAtMillis }
            ?: throw IllegalStateException("Aucun événement macro")

        val snapshot = MacroSnapshot(
            title = compactTitle(selected.title),
            country = selected.country,
            eventAtMillis = selected.eventAtMillis,
            actual = selected.actual,
            forecast = selected.forecast,
            previous = selected.previous,
            importance = selected.importance,
            released = selected.actual != null && selected.eventAtMillis <= now,
            updatedAtMillis = now
        )
        save(context, snapshot)
        return snapshot
    }

    fun cached(context: Context): MacroSnapshot? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains("eventAt")) return null
        return MacroSnapshot(
            title = p.getString("title", "MACRO") ?: "MACRO",
            country = p.getString("country", "") ?: "",
            eventAtMillis = p.getLong("eventAt", 0L),
            actual = p.getString("actual", null),
            forecast = p.getString("forecast", null),
            previous = p.getString("previous", null),
            importance = p.getInt("importance", 0),
            released = p.getBoolean("released", false),
            updatedAtMillis = p.getLong("updatedAt", 0L)
        )
    }

    private fun fetchEvents(from: Instant, to: Instant): List<MacroEvent> {
        val url = "$ENDPOINT?from=${DateTimeFormatter.ISO_INSTANT.format(from)}&to=${DateTimeFormatter.ISO_INSTANT.format(to)}&countries=US,EU,GB,DE,FR"
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 18_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Origin", "https://www.tradingview.com")
            setRequestProperty("Referer", "https://www.tradingview.com/economic-calendar/")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 17) MarketWidgets/1.0")
        }
        return try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("Calendrier HTTP $code")
            parse(body)
        } finally {
            c.disconnect()
        }
    }

    private fun parse(body: String): List<MacroEvent> {
        val root = JSONObject(body)
        val data = root.optJSONArray("result") ?: root.optJSONArray("data") ?: JSONArray()
        val out = mutableListOf<MacroEvent>()
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            val title = firstText(o, "title", "event", "indicator", "category") ?: continue
            val dateText = firstText(o, "date", "datetime", "time") ?: continue
            val eventAt = parseDate(dateText) ?: continue
            val importance = when (val raw = o.opt("importance")) {
                is Number -> raw.toInt()
                is String -> when {
                    raw.equals("high", true) -> 3
                    raw.equals("medium", true) -> 2
                    raw.equals("low", true) -> 1
                    else -> raw.toIntOrNull() ?: 0
                }
                else -> 0
            }
            out += MacroEvent(
                title = title,
                country = firstText(o, "country", "countryCode", "currency") ?: "",
                eventAtMillis = eventAt,
                actual = cleanValue(o.opt("actual")),
                forecast = cleanValue(o.opt("forecast")),
                previous = cleanValue(o.opt("previous")),
                importance = importance
            )
        }
        return out
    }

    private fun parseDate(raw: String): Long? {
        val value = raw.trim()
        return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching {
                java.time.LocalDateTime.parse(value).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
            }.getOrNull()
    }

    private fun firstText(o: JSONObject, vararg keys: String): String? = keys.asSequence()
        .map { o.optString(it).trim() }
        .firstOrNull { it.isNotBlank() && !it.equals("null", true) }

    private fun cleanValue(value: Any?): String? {
        if (value == null || value == JSONObject.NULL) return null
        val text = value.toString().trim()
        return text.takeIf { it.isNotBlank() && !it.equals("null", true) && it != "-" }
    }

    private fun compactTitle(raw: String): String {
        val t = raw.trim()
        val lower = t.lowercase()
        return when {
            "nonfarm" in lower || "non-farm" in lower || "payroll" in lower -> "NFP"
            "core pce" in lower -> "CORE PCE"
            "pce" in lower -> "PCE"
            "core cpi" in lower -> "CORE CPI"
            "cpi" in lower || "consumer price" in lower -> "CPI"
            "fomc" in lower || "fed interest" in lower || "federal funds" in lower -> "FED"
            "ecb" in lower || "bce" in lower -> "BCE"
            "gdp" in lower -> "PIB"
            "unemployment" in lower -> "CHÔMAGE"
            "retail sales" in lower -> "VENTES"
            "ism" in lower -> "ISM"
            "pmi" in lower -> "PMI"
            else -> t.take(20).uppercase()
        }
    }

    private fun save(context: Context, s: MacroSnapshot) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("title", s.title)
            .putString("country", s.country)
            .putLong("eventAt", s.eventAtMillis)
            .apply {
                s.actual?.let { putString("actual", it) } ?: remove("actual")
                s.forecast?.let { putString("forecast", it) } ?: remove("forecast")
                s.previous?.let { putString("previous", it) } ?: remove("previous")
            }
            .putInt("importance", s.importance)
            .putBoolean("released", s.released)
            .putLong("updatedAt", s.updatedAtMillis)
            .apply()
    }
}
