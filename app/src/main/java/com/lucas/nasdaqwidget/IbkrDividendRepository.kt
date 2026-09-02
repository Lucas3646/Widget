package com.lucas.nasdaqwidget

import android.content.Context
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter

data class IbkrDividendSnapshot(
    val symbol: String,
    val nextAmount: Double,
    val nextCurrency: String,
    val nextDateLabel: String,
    val daysUntil: Long,
    val receivedYtdEur: Double,
    val remainingYearEur: Double,
    val hasUpcoming: Boolean,
    val updatedAt: Long
)

private data class DividendRow(
    val symbol: String,
    val amount: Double,
    val currency: String,
    val date: LocalDate,
    val upcoming: Boolean
)

object IbkrDividendRepository {
    private const val PREFS = "ibkr_dividend_cache"
    private const val BASE = "https://ndcdyn.interactivebrokers.com/AccountManagement/FlexWebService"

    fun refresh(context: Context): IbkrDividendSnapshot? {
        val credentials = BrokerConnectionStore.ibkrFlexCredentials(context)
            ?: throw IllegalStateException("Token Flex ou Query ID absent")
        val rows = parse(fetchStatement(credentials))
        val today = LocalDate.now()
        val yearStart = today.withDayOfYear(1)
        val yearEnd = today.withMonth(12).withDayOfMonth(31)
        val upcoming = rows.filter { it.upcoming && !it.date.isBefore(today) && !it.date.isAfter(yearEnd) }.sortedBy { it.date }
        val received = rows.filter { !it.upcoming && !it.date.isBefore(yearStart) && !it.date.isAfter(today) }
        val next = upcoming.firstOrNull()
        val receivedYtd = received.sumOf { it.amount * fxToEur(it.currency) }
        val remaining = upcoming.sumOf { it.amount * fxToEur(it.currency) }
        if (next == null && received.isEmpty()) {
            cache(context, null)
            return null
        }
        val source = next ?: received.maxByOrNull { it.date } ?: return null
        val snapshot = IbkrDividendSnapshot(
            symbol = source.symbol.ifBlank { "IBKR" },
            nextAmount = source.amount,
            nextCurrency = source.currency.ifBlank { "EUR" },
            nextDateLabel = source.date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),
            daysUntil = if (next != null) ChronoUnit.DAYS.between(today, source.date) else 0,
            receivedYtdEur = receivedYtd,
            remainingYearEur = remaining,
            hasUpcoming = next != null,
            updatedAt = System.currentTimeMillis()
        )
        cache(context, snapshot)
        return snapshot
    }

    fun cached(context: Context): IbkrDividendSnapshot? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains("updated")) return null
        return IbkrDividendSnapshot(
            symbol = p.getString("symbol", "IBKR") ?: "IBKR",
            nextAmount = Double.fromBits(p.getLong("nextAmount", 0L)),
            nextCurrency = p.getString("nextCurrency", "EUR") ?: "EUR",
            nextDateLabel = p.getString("nextDate", "") ?: "",
            daysUntil = p.getLong("daysUntil", 0L),
            receivedYtdEur = Double.fromBits(p.getLong("receivedYtd", 0L)),
            remainingYearEur = Double.fromBits(p.getLong("remainingYear", 0L)),
            hasUpcoming = p.getBoolean("hasUpcoming", false),
            updatedAt = p.getLong("updated", 0L)
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun cache(context: Context, snapshot: IbkrDividendSnapshot?) {
        val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear()
        if (snapshot != null) {
            e.putString("symbol", snapshot.symbol)
                .putLong("nextAmount", snapshot.nextAmount.toBits())
                .putString("nextCurrency", snapshot.nextCurrency)
                .putString("nextDate", snapshot.nextDateLabel)
                .putLong("daysUntil", snapshot.daysUntil)
                .putLong("receivedYtd", snapshot.receivedYtdEur.toBits())
                .putLong("remainingYear", snapshot.remainingYearEur.toBits())
                .putBoolean("hasUpcoming", snapshot.hasUpcoming)
                .putLong("updated", snapshot.updatedAt)
        }
        e.apply()
    }

    private fun parse(xml: String): List<DividendRow> {
        val rows = mutableListOf<DividendRow>()
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply { setInput(StringReader(xml)) }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val attrs = (0 until parser.attributeCount).associate { parser.getAttributeName(it) to parser.getAttributeValue(it) }
                when (parser.name) {
                    "OpenDividendAccrual" -> rowFrom(attrs, true)?.let(rows::add)
                    "CashTransaction" -> {
                        val combined = listOfNotNull(attrs["type"], attrs["transactionType"], attrs["description"], attrs["activityDescription"]).joinToString(" ").lowercase()
                        if ("dividend" in combined || "dividende" in combined) rowFrom(attrs, false)?.let(rows::add)
                    }
                }
            }
            event = parser.next()
        }
        return rows
    }

    private fun rowFrom(a: Map<String, String>, upcoming: Boolean): DividendRow? {
        val symbol = a["symbol"].orEmpty()
        val amountKeys = if (upcoming) listOf("grossAmount", "accruedAmount", "amount", "netAmount") else listOf("amount", "netAmount", "grossAmount", "proceeds")
        val amount = amountKeys.asSequence().mapNotNull { a[it]?.replace(",", "")?.toDoubleOrNull() }.firstOrNull() ?: 0.0
        val currency = a["currency"] ?: a["fxCurrency"] ?: "EUR"
        val dateKeys = if (upcoming) listOf("payDate", "exDate", "date", "reportDate") else listOf("dateTime", "date", "settleDate", "reportDate")
        val rawDate = dateKeys.asSequence().mapNotNull { a[it] }.firstOrNull() ?: return null
        val date = parseDate(rawDate) ?: return null
        if (symbol.isBlank() && amount == 0.0) return null
        return DividendRow(symbol, amount, currency, date, upcoming)
    }

    private fun parseDate(raw: String): LocalDate? {
        val clean = raw.trim().substringBefore(';').substringBefore(' ')
        val patterns = listOf("yyyyMMdd", "yyyy-MM-dd", "MM/dd/yyyy", "dd/MM/yyyy")
        for (p in patterns) {
            val d = runCatching { LocalDate.parse(clean, DateTimeFormatter.ofPattern(p)) }.getOrNull()
            if (d != null) return d
        }
        return null
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
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "MarketWidgets/1.7 Android")
            setRequestProperty("Accept", "application/xml,text/xml,*/*")
        }
        return try {
            val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (c.responseCode !in 200..299) throw IllegalStateException("IBKR HTTP ${c.responseCode}")
            body
        } finally { c.disconnect() }
    }

    private fun fxToEur(currency: String): Double {
        val c = currency.uppercase()
        if (c.isBlank() || c == "EUR" || c == "BASE_SUMMARY") return 1.0
        return runCatching {
            val symbol = URLEncoder.encode("EUR${c}=X", "UTF-8")
            val conn = (URL("https://query1.finance.yahoo.com/v8/finance/chart/$symbol?range=1d&interval=5m").openConnection() as HttpURLConnection).apply {
                connectTimeout = 7_000
                readTimeout = 7_000
                setRequestProperty("User-Agent", "Mozilla/5.0 MarketWidgets")
            }
            if (conn.responseCode !in 200..299) return@runCatching 1.0
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val rate = org.json.JSONObject(body).getJSONObject("chart").getJSONArray("result").getJSONObject(0).getJSONObject("meta").getDouble("regularMarketPrice")
            if (rate > 0) 1.0 / rate else 1.0
        }.getOrDefault(1.0)
    }

    private fun tagText(xml: String, tag: String): String? = Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.getOrNull(1)?.trim()
}
