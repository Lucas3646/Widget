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
    val chartValues: List<Double>,
    val updatedAt: Long
)

private data class FlexPosition(val symbol: String, val quantity: Double, val markPrice: Double, val positionValue: Double, val currency: String)
private data class FlexCash(val amount: Double, val currency: String)
private data class FlexNav(val date: LocalDate, val total: Double, val currency: String, val accountId: String)
private data class ParsedFlex(val positions: List<FlexPosition>, val cash: List<FlexCash>, val nav: List<FlexNav>)
private data class YahooQuote(val current: Double, val reference: Double)

object IbkrFlexRepository {
    private const val PREFS = "ibkr_flex_cache"
    private const val BASE = "https://ndcdyn.interactivebrokers.com/AccountManagement/FlexWebService"

    fun refresh(context: Context, timeframe: PortfolioTimeframe = PortfolioTimeframeStore.get(context)): IbkrPortfolioSnapshot {
        val credentials = BrokerConnectionStore.ibkrFlexCredentials(context) ?: error("Token Flex ou Query ID absent")
        val parsed = parseStatement(fetchStatement(credentials))
        if (parsed.positions.isEmpty()) error("Aucune position dans la Flex Query")

        var positionsTotal = 0.0
        var investedBase = 0.0
        var change = 0.0
        val live = mutableListOf<IbkrPositionSnapshot>()
        parsed.positions.forEach { p ->
            val quote = yahooQuote(p.symbol, p.currency, timeframe)
            val fx = fxToEur(p.currency)
            val current = quote?.current ?: p.markPrice
            val reference = quote?.reference ?: current
            val baseValue = if (p.positionValue != 0.0) p.positionValue * fx else p.quantity * p.markPrice * fx
            val currentValue = if (quote != null && p.markPrice > 0) baseValue * (current / p.markPrice) else baseValue
            positionsTotal += currentValue
            val referenceValue = if (reference != 0.0) currentValue * (reference / current.coerceAtLeast(1e-12)) else currentValue
            val delta = currentValue - referenceValue
            change += delta
            if (referenceValue.isFinite() && referenceValue > 0) investedBase += referenceValue
            val percent = if (reference != 0.0) (current / reference - 1.0) * 100 else 0.0
            if (kotlin.math.abs(currentValue) > 0.01) live += IbkrPositionSnapshot(p.symbol, currentValue, delta, percent)
        }

        val cash = parsed.cash.sumOf { it.amount * fxToEur(it.currency) }
        val total = positionsTotal + cash
        val chart = buildNavChart(parsed.nav, timeframe, total)
        val snapshot = IbkrPortfolioSnapshot(
            totalEur = total,
            periodChangeEur = change,
            periodChangePercent = if (investedBase > 0.0) change / investedBase * 100 else 0.0,
            positions = live,
            chartValues = chart,
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
            runCatching { JSONArray(raw) }.getOrNull()?.let { array ->
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    positions += IbkrPositionSnapshot(o.optString("symbol"), o.optDouble("value"), o.optDouble("change"), o.optDouble("percent"))
                }
            }
        }
        val chart = mutableListOf<Double>()
        prefs.getString("chart", null)?.let { raw ->
            runCatching { JSONArray(raw) }.getOrNull()?.let { array ->
                for (i in 0 until array.length()) if (!array.isNull(i)) chart += array.optDouble(i)
            }
        }
        return IbkrPortfolioSnapshot(
            Double.fromBits(prefs.getLong("total", 0)),
            Double.fromBits(prefs.getLong("change", 0)),
            Double.fromBits(prefs.getLong("percent", 0)),
            positions,
            chart,
            prefs.getLong("updated", 0)
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun cache(context: Context, snapshot: IbkrPortfolioSnapshot) {
        val positions = JSONArray()
        snapshot.positions.forEach {
            positions.put(JSONObject().put("symbol", it.symbol).put("value", it.valueEur).put("change", it.periodChangeEur).put("percent", it.periodChangePercent))
        }
        val chart = JSONArray()
        snapshot.chartValues.forEach(chart::put)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("total", snapshot.totalEur.toBits())
            .putLong("change", snapshot.periodChangeEur.toBits())
            .putLong("percent", snapshot.periodChangePercent.toBits())
            .putString("positions", positions.toString())
            .putString("chart", chart.toString())
            .putLong("updated", snapshot.updatedAt)
            .apply()
    }

    private fun buildNavChart(nav: List<FlexNav>, timeframe: PortfolioTimeframe, currentTotal: Double): List<Double> {
        if (nav.isEmpty()) return listOf((currentTotal - currentTotal * 0.002).coerceAtLeast(0.0), currentTotal)
        val now = LocalDate.now()
        val start = when (timeframe) {
            PortfolioTimeframe.SESSION -> now.minusDays(7)
            PortfolioTimeframe.MONTH -> now.minusMonths(1)
            PortfolioTimeframe.THREE_MONTHS -> now.minusMonths(3)
            PortfolioTimeframe.YTD -> now.withDayOfYear(1)
            PortfolioTimeframe.YEAR -> now.minusYears(1)
        }
        val byDate = nav.filter { !it.date.isBefore(start) }.groupBy { it.date }.toSortedMap()
        val values = byDate.values.map { rows -> rows.sumOf { it.total * fxToEur(it.currency) } }.filter { it.isFinite() && it > 0 }
        val sampled = if (values.size <= 40) values else values.filterIndexed { index, _ -> index % ((values.size / 40).coerceAtLeast(1)) == 0 }.takeLast(40)
        return (sampled + currentTotal).takeLast(41).ifEmpty { listOf(currentTotal) }
    }

    private fun parseStatement(xml: String): ParsedFlex {
        val positions = mutableListOf<FlexPosition>()
        val cash = mutableListOf<FlexCash>()
        val nav = mutableListOf<FlexNav>()
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply { setInput(StringReader(xml)) }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "OpenPosition" -> {
                        val symbol = parser.getAttributeValue(null, "symbol").orEmpty()
                        val quantity = parser.getAttributeValue(null, "position")?.toDoubleOrNull() ?: 0.0
                        val mark = parser.getAttributeValue(null, "markPrice")?.toDoubleOrNull() ?: 0.0
                        val value = parser.getAttributeValue(null, "positionValue")?.toDoubleOrNull() ?: 0.0
                        val currency = parser.getAttributeValue(null, "currency").orEmpty()
                        if (symbol.isNotBlank() && quantity != 0.0) positions += FlexPosition(symbol, quantity, mark, value, currency)
                    }
                    "CashReportCurrency" -> {
                        val currency = parser.getAttributeValue(null, "currency").orEmpty()
                        if (currency != "BASE_SUMMARY") {
                            val value = parser.getAttributeValue(null, "endingCash")?.toDoubleOrNull() ?: 0.0
                            if (value != 0.0) cash += FlexCash(value, currency)
                        }
                    }
                    "EquitySummaryByReportDateInBase" -> {
                        val dateRaw = parser.getAttributeValue(null, "reportDate").orEmpty()
                        val total = parser.getAttributeValue(null, "total")?.toDoubleOrNull() ?: 0.0
                        val currency = parser.getAttributeValue(null, "currency").orEmpty()
                        val account = parser.getAttributeValue(null, "accountId").orEmpty().ifBlank { "default" }
                        val date = runCatching { LocalDate.parse(dateRaw, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
                        if (date != null && total > 0) nav += FlexNav(date, total, currency, account)
                    }
                }
            }
            event = parser.next()
        }
        return ParsedFlex(positions, cash, nav)
    }

    private fun fetchStatement(c: BrokerConnectionStore.IbkrFlexCredentials): String {
        val token = URLEncoder.encode(c.token, "UTF-8")
        val query = URLEncoder.encode(c.queryId, "UTF-8")
        val send = get("$BASE/SendRequest?t=$token&q=$query&v=3")
        val ref = tagText(send, "ReferenceCode") ?: error(tagText(send, "ErrorMessage") ?: "IBKR Flex: ReferenceCode absent")
        var last = "Rapport IBKR en préparation"
        repeat(6) {
            if (it > 0) Thread.sleep(1000)
            val body = get("$BASE/GetStatement?t=$token&q=${URLEncoder.encode(ref, "UTF-8")}&v=3")
            if (body.contains("<FlexQueryResponse")) return body
            last = tagText(body, "ErrorMessage") ?: last
        }
        error(last)
    }

    private fun get(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 20000
            setRequestProperty("User-Agent", "MarketWidgets/1.7 Android")
        }
        return try {
            val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (c.responseCode !in 200..299) error("IBKR HTTP ${c.responseCode}")
            body
        } finally { c.disconnect() }
    }

    private fun yahooQuote(symbol: String, currency: String, timeframe: PortfolioTimeframe): YahooQuote? {
        val candidates = when (currency.uppercase()) {
            "USD" -> listOf(symbol)
            "GBP" -> listOf("$symbol.L", symbol)
            "EUR" -> listOf("$symbol.PA", "$symbol.AS", "$symbol.BR", "$symbol.DE", "$symbol.MI", symbol)
            else -> listOf(symbol)
        }
        val range = when (timeframe) {
            PortfolioTimeframe.SESSION -> "1d"
            PortfolioTimeframe.MONTH -> "1mo"
            PortfolioTimeframe.THREE_MONTHS -> "3mo"
            PortfolioTimeframe.YTD -> "ytd"
            PortfolioTimeframe.YEAR -> "1y"
        }
        val interval = if (timeframe == PortfolioTimeframe.SESSION) "5m" else "1d"
        for (candidate in candidates) {
            val result = runCatching {
                val enc = URLEncoder.encode(candidate, "UTF-8")
                val root = JSONObject(getYahoo("https://query1.finance.yahoo.com/v8/finance/chart/$enc?range=$range&interval=$interval&includePrePost=true"))
                val r = root.getJSONObject("chart").optJSONArray("result")?.optJSONObject(0) ?: return@runCatching null
                val meta = r.getJSONObject("meta")
                val current = meta.optDouble("regularMarketPrice", Double.NaN)
                if (!current.isFinite()) return@runCatching null
                val reference = if (timeframe == PortfolioTimeframe.SESSION) {
                    meta.optDouble("chartPreviousClose", meta.optDouble("previousClose", current))
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

    private fun getYahoo(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", "Mozilla/5.0 MarketWidgets")
        }
        return try {
            if (c.responseCode !in 200..299) error("quote")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun fxToEur(currency: String): Double {
        val c = currency.uppercase()
        if (c.isBlank() || c == "EUR" || c == "BASE_SUMMARY") return 1.0
        return runCatching {
            val root = JSONObject(getYahoo("https://query1.finance.yahoo.com/v8/finance/chart/${URLEncoder.encode("EUR${c}=X", "UTF-8")}?range=1d&interval=5m"))
            val rate = root.getJSONObject("chart").getJSONArray("result").getJSONObject(0).getJSONObject("meta").getDouble("regularMarketPrice")
            if (rate > 0) 1.0 / rate else 1.0
        }.getOrDefault(1.0)
    }

    private fun tagText(xml: String, tag: String) = Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.getOrNull(1)?.trim()
}
