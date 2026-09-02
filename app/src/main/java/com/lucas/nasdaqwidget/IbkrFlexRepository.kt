package com.lucas.nasdaqwidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.format.DateTimeFormatter


data class IbkrPositionSnapshot(
    val symbol: String,
    val valueEur: Double,
    val periodChangeEur: Double,
    val periodChangePercent: Double
)

data class IbkrPortfolioSnapshot(
    val totalEur: Double,
    val periodChangeEur: Double,
    val periodChangePercent: Double,
    val positions: List<IbkrPositionSnapshot>,
    val updatedAt: Long
)

private data class FlexPosition(val symbol: String, val quantity: Double, val markPrice: Double, val currency: String)
private data class FlexNavPoint(val date: LocalDate, val total: Double, val currency: String, val accountId: String)
private data class YahooQuote(val current: Double, val reference: Double)

object IbkrFlexRepository {
    private const val PREFS = "ibkr_flex_cache"
    private const val BASE = "https://ndcdyn.interactivebrokers.com/AccountManagement/FlexWebService"

    fun refresh(context: Context, timeframe: PortfolioTimeframe = PortfolioTimeframeStore.get(context)): IbkrPortfolioSnapshot {
        val credentials = BrokerConnectionStore.ibkrFlexCredentials(context)
            ?: throw IllegalStateException("Token Flex ou Query ID absent")
        val statement = fetchStatement(credentials)
        val parsed = parseStatement(statement)
        if (parsed.first.isEmpty() && parsed.second.isEmpty()) throw IllegalStateException("Aucune donnée portefeuille dans la Flex Query")

        val positions = parsed.first
        val navPoints = parsed.second
        val latestNavByAccount = navPoints.groupBy { it.accountId }.mapNotNull { (_, rows) -> rows.maxByOrNull { it.date } }
        var totalEur = latestNavByAccount.sumOf { navToEur(context, it.total, it.currency) }
        var periodChangeEur = 0.0
        val livePositions = mutableListOf<IbkrPositionSnapshot>()

        positions.forEach { p ->
            val quote = yahooQuote(p.symbol, p.currency, timeframe)
            val fx = fxToEur(p.currency)
            val current = quote?.current ?: p.markPrice
            val reference = quote?.reference ?: p.markPrice
            val currentValue = p.quantity * current * fx
            val markValue = p.quantity * p.markPrice * fx
            totalEur += currentValue - markValue
            val change = p.quantity * (current - reference) * fx
            periodChangeEur += change
            val percent = if (reference != 0.0) (current / reference - 1.0) * 100.0 else 0.0
            if (kotlin.math.abs(currentValue) > 0.01) livePositions += IbkrPositionSnapshot(p.symbol, currentValue, change, percent)
        }

        if (timeframe != PortfolioTimeframe.SESSION && navPoints.isNotEmpty()) {
            val target = targetDate(timeframe)
            val baselineByAccount = navPoints.groupBy { it.accountId }.mapNotNull { (_, rows) ->
                rows.filter { !it.date.isAfter(target) }.maxByOrNull { it.date } ?: rows.minByOrNull { it.date }
            }
            val baseline = baselineByAccount.sumOf { navToEur(context, it.total, it.currency) }
            if (baseline > 0) periodChangeEur = totalEur - baseline
        }

        val previous = totalEur - periodChangeEur
        val snapshot = IbkrPortfolioSnapshot(
            totalEur = totalEur,
            periodChangeEur = periodChangeEur,
            periodChangePercent = if (previous > 0) periodChangeEur / previous * 100.0 else 0.0,
            positions = livePositions,
            updatedAt = System.currentTimeMillis()
        )
        cache(context, snapshot)
        BrokerConnectionStore.setIbkrVerified(context, true)
        return snapshot
    }

    fun cached(context: Context): IbkrPortfolioSnapshot? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains("total")) return null
        val positions = mutableListOf<IbkrPositionSnapshot>()
        prefs.getString("positions", null)?.let { raw ->
            runCatching { JSONArray(raw) }.getOrNull()?.let { a ->
                for (i in 0 until a.length()) {
                    val p = a.optJSONObject(i) ?: continue
                    positions += IbkrPositionSnapshot(p.optString("symbol"), p.optDouble("value"), p.optDouble("change"), p.optDouble("percent"))
                }
            }
        }
        return IbkrPortfolioSnapshot(
            Double.fromBits(prefs.getLong("total", 0)),
            Double.fromBits(prefs.getLong("change", 0)),
            Double.fromBits(prefs.getLong("percent", 0)),
            positions,
            prefs.getLong("updated", 0)
        )
    }

    fun clear(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply() }

    private fun cache(context: Context, s: IbkrPortfolioSnapshot) {
        val array = JSONArray()
        s.positions.forEach { array.put(JSONObject().put("symbol", it.symbol).put("value", it.valueEur).put("change", it.periodChangeEur).put("percent", it.periodChangePercent)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("total", s.totalEur.toBits()).putLong("change", s.periodChangeEur.toBits()).putLong("percent", s.periodChangePercent.toBits())
            .putString("positions", array.toString()).putLong("updated", s.updatedAt).apply()
    }

    private fun fetchStatement(c: BrokerConnectionStore.IbkrFlexCredentials): String {
        val token = URLEncoder.encode(c.token, StandardCharsets.UTF_8.toString())
        val query = URLEncoder.encode(c.queryId, StandardCharsets.UTF_8.toString())
        val send = get("$BASE/SendRequest?t=$token&q=$query&v=3")
        val ref = tagText(send, "ReferenceCode") ?: throw IllegalStateException(tagText(send, "ErrorMessage") ?: "IBKR Flex: ReferenceCode absent")
        var lastError = "Rapport IBKR en préparation"
        repeat(6) {
            if (it > 0) Thread.sleep(1_000)
            val body = get("$BASE/GetStatement?t=$token&q=${URLEncoder.encode(ref, "UTF-8")}&v=3")
            if (body.contains("<FlexQueryResponse")) return body
            lastError = tagText(body, "ErrorMessage") ?: lastError
        }
        throw IllegalStateException(lastError)
    }

    private fun get(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 12_000; readTimeout = 20_000
            setRequestProperty("User-Agent", "MarketWidgets/1.4 Android")
            setRequestProperty("Accept", "application/xml,text/xml,*/*")
        }
        return try {
            val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (c.responseCode !in 200..299) throw IllegalStateException("IBKR HTTP ${c.responseCode}")
            body
        } finally { c.disconnect() }
    }

    private fun parseStatement(xml: String): Pair<List<FlexPosition>, List<FlexNavPoint>> {
        val positions = mutableListOf<FlexPosition>()
        val nav = mutableListOf<FlexNavPoint>()
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply { setInput(StringReader(xml)) }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "OpenPosition" -> {
                        val symbol = parser.getAttributeValue(null, "symbol").orEmpty()
                        val quantity = parser.getAttributeValue(null, "position")?.toDoubleOrNull() ?: 0.0
                        val mark = parser.getAttributeValue(null, "markPrice")?.toDoubleOrNull() ?: 0.0
                        val currency = parser.getAttributeValue(null, "currency").orEmpty()
                        if (symbol.isNotBlank() && quantity != 0.0 && mark > 0) positions += FlexPosition(symbol, quantity, mark, currency)
                    }
                    "EquitySummaryByReportDateInBase" -> {
                        val total = parser.getAttributeValue(null, "total")?.toDoubleOrNull() ?: 0.0
                        val dateRaw = parser.getAttributeValue(null, "reportDate").orEmpty()
                        val currency = parser.getAttributeValue(null, "currency").orEmpty()
                        val account = parser.getAttributeValue(null, "accountId").orEmpty()
                        runCatching { LocalDate.parse(dateRaw, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()?.let { if (total > 0) nav += FlexNavPoint(it, total, currency, account) }
                    }
                }
            }
            event = parser.next()
        }
        return positions to nav
    }

    private fun yahooQuote(symbol: String, currency: String, timeframe: PortfolioTimeframe): YahooQuote? {
        val candidates = when (currency.uppercase()) {
            "USD" -> listOf(symbol)
            "GBP" -> listOf("$symbol.L", symbol)
            "EUR" -> listOf("$symbol.PA", "$symbol.AS", "$symbol.BR", "$symbol.DE", "$symbol.MI", symbol)
            else -> listOf(symbol)
        }
        val range = when (timeframe) { PortfolioTimeframe.SESSION -> "1d"; PortfolioTimeframe.MONTH -> "1mo"; PortfolioTimeframe.THREE_MONTHS -> "3mo"; PortfolioTimeframe.YTD -> "ytd"; PortfolioTimeframe.YEAR -> "1y" }
        val interval = if (timeframe == PortfolioTimeframe.SESSION) "5m" else "1d"
        candidates.forEach { candidate ->
            val result = runCatching {
                val encoded = URLEncoder.encode(candidate, "UTF-8")
                val c = (URL("https://query1.finance.yahoo.com/v8/finance/chart/$encoded?range=$range&interval=$interval&includePrePost=true").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000; readTimeout = 8_000; setRequestProperty("User-Agent", "MarketWidgets/1.4 Android")
                }
                if (c.responseCode !in 200..299) return@runCatching null
                val root = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val r = root.getJSONObject("chart").optJSONArray("result")?.optJSONObject(0) ?: return@runCatching null
                val meta = r.getJSONObject("meta")
                val current = meta.optDouble("regularMarketPrice", Double.NaN)
                if (!current.isFinite()) return@runCatching null
                val reference = if (timeframe == PortfolioTimeframe.SESSION) {
                    when { meta.has("chartPreviousClose") -> meta.optDouble("chartPreviousClose", current); meta.has("previousClose") -> meta.optDouble("previousClose", current); else -> current }
                } else {
                    val closes = r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0).getJSONArray("close")
                    var first = current
                    for (i in 0 until closes.length()) if (!closes.isNull(i)) { first = closes.getDouble(i); break }
                    first
                }
                YahooQuote(current, reference)
            }.getOrNull()
            if (result != null) return result
        }
        return null
    }

    private fun fxToEur(currency: String): Double {
        val c = currency.uppercase()
        if (c.isBlank() || c == "EUR" || c == "BASE_SUMMARY") return 1.0
        val symbol = "EUR${c}=X"
        return runCatching {
            val encoded = URLEncoder.encode(symbol, "UTF-8")
            val conn = (URL("https://query1.finance.yahoo.com/v8/finance/chart/$encoded?range=1d&interval=5m").openConnection() as HttpURLConnection).apply { connectTimeout=6_000;readTimeout=6_000;setRequestProperty("User-Agent","MarketWidgets/1.4 Android") }
            if (conn.responseCode !in 200..299) return@runCatching 1.0
            val root=JSONObject(conn.inputStream.bufferedReader().use{it.readText()}); val r=root.getJSONObject("chart").getJSONArray("result").getJSONObject(0); val rate=r.getJSONObject("meta").getDouble("regularMarketPrice")
            if (rate > 0) 1.0 / rate else 1.0
        }.getOrDefault(1.0)
    }

    private fun navToEur(context: Context, value: Double, currency: String): Double = value * fxToEur(currency)
    private fun targetDate(timeframe: PortfolioTimeframe): LocalDate { val now=LocalDate.now(); return when(timeframe){PortfolioTimeframe.SESSION->now.minusDays(1);PortfolioTimeframe.MONTH->now.minusMonths(1);PortfolioTimeframe.THREE_MONTHS->now.minusMonths(3);PortfolioTimeframe.YTD->now.withDayOfYear(1);PortfolioTimeframe.YEAR->now.minusYears(1)} }
    private fun tagText(xml: String, tag: String): String? = Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.getOrNull(1)?.trim()
}
