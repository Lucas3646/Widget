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
    val title: String, val country: String, val eventAtMillis: Long,
    val actual: String?, val forecast: String?, val previous: String?,
    val importance: Int, val released: Boolean, val updatedAtMillis: Long
)

private data class MacroEvent(
    val title: String, val country: String, val eventAtMillis: Long,
    val actual: String?, val forecast: String?, val previous: String?, val importance: Int
)

object MacroRepository {
    private const val PREFS = "macro_widget_cache"
    private const val ENDPOINT = "https://economic-calendar.tradingview.com/events"
    private val majorKeywords = listOf("cpi", "inflation", "pce", "nonfarm", "non-farm", "payroll", "unemployment", "fomc", "fed interest", "interest rate decision", "federal funds", "ecb", "bce", "gdp", "retail sales", "ism", "pmi", "core pce", "core cpi")

    fun refresh(context: Context): MacroSnapshot {
        val now = System.currentTimeMillis()
        val events = fetchEvents(Instant.ofEpochMilli(now - 3L * 3600000), Instant.ofEpochMilli(now + 10L * 86400000))
        if (events.isEmpty()) throw IllegalStateException("Calendrier macro indisponible")
        val major = events.filter { e -> e.importance >= 3 && majorKeywords.any { e.title.lowercase().contains(it) } }
        val high = events.filter { it.importance >= 3 }
        val pool = (if (major.isNotEmpty()) major else if (high.isNotEmpty()) high else events).sortedBy { it.eventAtMillis }
        val recent = pool.filter { it.actual != null && it.eventAtMillis <= now && now - it.eventAtMillis <= 2L * 3600000 }.maxByOrNull { it.eventAtMillis }
        val upcoming = pool.filter { it.eventAtMillis >= now }
        val first = recent ?: upcoming.firstOrNull() ?: pool.last()
        val second = upcoming.firstOrNull { it.eventAtMillis > first.eventAtMillis }
        val primary = first.toSnapshot(now)
        save(context, primary, "")
        if (second != null) save(context, second.toSnapshot(now), "second_") else clearSecond(context)
        return primary
    }

    fun cached(context: Context): MacroSnapshot? = load(context, "")
    fun cachedSecond(context: Context): MacroSnapshot? = load(context, "second_")

    private fun MacroEvent.toSnapshot(now: Long) = MacroSnapshot(compactTitle(title), country, eventAtMillis, actual, forecast, previous, importance, actual != null && eventAtMillis <= now, now)

    private fun load(context: Context, prefix: String): MacroSnapshot? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains(prefix + "eventAt")) return null
        return MacroSnapshot(p.getString(prefix + "title", "MACRO") ?: "MACRO", p.getString(prefix + "country", "") ?: "", p.getLong(prefix + "eventAt", 0), p.getString(prefix + "actual", null), p.getString(prefix + "forecast", null), p.getString(prefix + "previous", null), p.getInt(prefix + "importance", 0), p.getBoolean(prefix + "released", false), p.getLong(prefix + "updatedAt", 0))
    }

    private fun fetchEvents(from: Instant, to: Instant): List<MacroEvent> {
        val url = "$ENDPOINT?from=${DateTimeFormatter.ISO_INSTANT.format(from)}&to=${DateTimeFormatter.ISO_INSTANT.format(to)}&countries=US,EU,GB,DE,FR"
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 12000; readTimeout = 18000
            setRequestProperty("Accept", "application/json"); setRequestProperty("Origin", "https://www.tradingview.com"); setRequestProperty("Referer", "https://www.tradingview.com/economic-calendar/"); setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 17) MarketWidgets/1.0")
        }
        return try { val code=c.responseCode; val body=(if(code in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty(); if(code !in 200..299) throw IllegalStateException("Calendrier HTTP $code"); parse(body) } finally { c.disconnect() }
    }

    private fun parse(body: String): List<MacroEvent> {
        val root=JSONObject(body); val data=root.optJSONArray("result")?:root.optJSONArray("data")?:JSONArray(); val out=mutableListOf<MacroEvent>()
        for(i in 0 until data.length()) { val o=data.optJSONObject(i)?:continue; val title=firstText(o,"title","event","indicator","category")?:continue; val eventAt=parseDate(firstText(o,"date","datetime","time")?:continue)?:continue
            val importance=when(val raw=o.opt("importance")){ is Number->raw.toInt(); is String->when{raw.equals("high",true)->3;raw.equals("medium",true)->2;raw.equals("low",true)->1;else->raw.toIntOrNull()?:0};else->0 }
            out += MacroEvent(title, firstText(o,"country","countryCode","currency")?:"", eventAt, cleanValue(o.opt("actual")), cleanValue(o.opt("forecast")), cleanValue(o.opt("previous")), importance)
        }; return out
    }
    private fun parseDate(raw:String):Long? { val v=raw.trim(); return runCatching{Instant.parse(v).toEpochMilli()}.getOrNull()?:runCatching{OffsetDateTime.parse(v).toInstant().toEpochMilli()}.getOrNull()?:runCatching{ZonedDateTime.parse(v).toInstant().toEpochMilli()}.getOrNull()?:runCatching{java.time.LocalDateTime.parse(v).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()}.getOrNull() }
    private fun firstText(o:JSONObject,vararg keys:String):String?=keys.asSequence().map{o.optString(it).trim()}.firstOrNull{it.isNotBlank()&&!it.equals("null",true)}
    private fun cleanValue(v:Any?):String? { if(v==null||v==JSONObject.NULL)return null; val t=v.toString().trim(); return t.takeIf{it.isNotBlank()&&!it.equals("null",true)&&it!="-"} }
    private fun compactTitle(raw:String):String { val l=raw.lowercase(); return when { "nonfarm" in l||"non-farm" in l||"payroll" in l->"NFP"; "core pce" in l->"CORE PCE"; "pce" in l->"PCE"; "core cpi" in l->"CORE CPI"; "cpi" in l||"consumer price" in l->"CPI"; "fomc" in l||"fed interest" in l||"federal funds" in l->"FED"; "ecb" in l||"bce" in l->"BCE"; "gdp" in l->"PIB"; "unemployment" in l->"CHÔMAGE"; "retail sales" in l->"VENTES"; "ism" in l->"ISM"; "pmi" in l->"PMI"; else->raw.trim().take(20).uppercase() } }
    private fun save(context:Context,s:MacroSnapshot,prefix:String) { context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(prefix+"title",s.title).putString(prefix+"country",s.country).putLong(prefix+"eventAt",s.eventAtMillis).apply { s.actual?.let{putString(prefix+"actual",it)}?:remove(prefix+"actual"); s.forecast?.let{putString(prefix+"forecast",it)}?:remove(prefix+"forecast"); s.previous?.let{putString(prefix+"previous",it)}?:remove(prefix+"previous") }.putInt(prefix+"importance",s.importance).putBoolean(prefix+"released",s.released).putLong(prefix+"updatedAt",s.updatedAtMillis).apply() }
    private fun clearSecond(context:Context) { val e=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit(); listOf("title","country","eventAt","actual","forecast","previous","importance","released","updatedAt").forEach{e.remove("second_$it")}; e.apply() }
}
