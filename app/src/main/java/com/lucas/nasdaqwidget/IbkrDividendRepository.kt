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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** A compact read-only snapshot for the dividend widget. */
data class IbkrDividendSnapshot(
    val symbol: String,
    val description: String,
    val amount: Double,
    val currency: String,
    val dateLabel: String,
    val isUpcoming: Boolean,
    val updatedAt: Long
)

private data class DividendRow(
    val symbol: String,
    val description: String,
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
        val xml = fetchStatement(credentials)
        val rows = parse(xml)
        val today = LocalDate.now()

        val next = rows.filter { it.upcoming && !it.date.isBefore(today) }.minByOrNull { it.date }
            ?: rows.filter { it.upcoming }.maxByOrNull { it.date }
            ?: rows.filter { !it.upcoming }.maxByOrNull { it.date }

        val snapshot = next?.let {
            IbkrDividendSnapshot(
                symbol = it.symbol.ifBlank { "IBKR" },
                description = it.description.ifBlank { if (it.upcoming) "Dividende attendu" else "Dernier dividende reçu" },
                amount = it.amount,
                currency = it.currency.ifBlank { "USD" },
                dateLabel = it.date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                isUpcoming = it.upcoming,
                updatedAt = System.currentTimeMillis()
            )
        }
        cache(context, snapshot)
        return snapshot
    }

    fun cached(context: Context): IbkrDividendSnapshot? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains("updated")) return null
        return IbkrDividendSnapshot(
            symbol = p.getString("symbol", "IBKR") ?: "IBKR",
            description = p.getString("description", "") ?: "",
            amount = Double.fromBits(p.getLong("amount", 0L)),
            currency = p.getString("currency", "USD") ?: "USD",
            dateLabel = p.getString("date", "") ?: "",
            isUpcoming = p.getBoolean("upcoming", false),
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
                .putString("description", snapshot.description)
                .putLong("amount", snapshot.amount.toBits())
                .putString("currency", snapshot.currency)
                .putString("date", snapshot.dateLabel)
                .putBoolean("upcoming", snapshot.isUpcoming)
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
                        val type = (attrs["type"] ?: attrs["transactionType"] ?: attrs["description"] ?: "").lowercase()
                        if ("dividend" in type || "dividende" in type) rowFrom(attrs, false)?.let(rows::add)
                    }
                }
            }
            event = parser.next()
        }
        return rows
    }

    private fun rowFrom(a: Map<String, String>, upcoming: Boolean): DividendRow? {
        val symbol = a["symbol"].orEmpty()
        val description = a["description"] ?: a["companyName"] ?: a["issuer"] ?: ""
        val amount = sequenceOf("grossAmount", "amount", "accruedAmount", "netAmount")
            .mapNotNull { a[it]?.replace(",", "")?.toDoubleOrNull() }.firstOrNull() ?: 0.0
        val currency = a["currency"] ?: a["fxCurrency"] ?: "USD"
        val rawDate = sequenceOf("payDate", "exDate", "dateTime", "reportDate", "settleDate", "date")
            .mapNotNull { a[it] }.firstOrNull() ?: return null
        val date = parseDate(rawDate) ?: return null
        if (symbol.isBlank() && description.isBlank()) return null
        return DividendRow(symbol, description, amount, currency, date, upcoming)
    }

    private fun parseDate(raw: String): LocalDate? {
        val clean = raw.trim().substringBefore(';').substringBefore(' ')
        val patterns = listOf("yyyyMMdd", "yyyy-MM-dd", "MM/dd/yyyy", "dd/MM/yyyy")
        for (p in patterns) runCatching { return LocalDate.parse(clean, DateTimeFormatter.ofPattern(p)) }
        return runCatching { LocalDateTime.parse(raw).toLocalDate() }.getOrNull()
    }

    private fun fetchStatement(c: BrokerConnectionStore.IbkrFlexCredentials): String {
        val token = URLEncoder.encode(c.token, StandardCharsets.UTF_8.toString())
        val query = URLEncoder.encode(c.queryId, StandardCharsets.UTF_8.toString())
        val send = get("$BASE/SendRequest?t=$token&q=$query&v=3")
        val ref = tagText(send, "ReferenceCode")
            ?: throw IllegalStateException(tagText(send, "ErrorMessage") ?: "IBKR Flex: ReferenceCode absent")
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
            setRequestProperty("User-Agent", "MarketWidgets/1.5 Android")
            setRequestProperty("Accept", "application/xml,text/xml,*/*")
        }
        return try {
            val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (c.responseCode !in 200..299) throw IllegalStateException("IBKR HTTP ${c.responseCode}")
            body
        } finally { c.disconnect() }
    }

    private fun tagText(xml: String, tag: String): String? =
        Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.getOrNull(1)?.trim()
}
