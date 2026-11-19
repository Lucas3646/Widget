package com.lucas.nasdaqwidget

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

data class IbkrDividendSnapshot(
    val symbol: String?,
    val nextAmount: Double?,
    val nextCurrency: String?,
    val paymentDate: LocalDate?,
    val receivedYtdEur: Double?,
    val accruedEur: Double?,
    val reportDate: LocalDate,
    val hasCashSection: Boolean,
    val hasAccrualSection: Boolean
)

object IbkrDividendRepository {
    private const val PREFS = "ibkr_dividend_cache"

    private data class Dividend(
        val symbol: String, val amount: Double, val currency: String,
        val date: LocalDate, val eur: Double?
    )

    fun refresh(context: Context): IbkrDividendSnapshot {
        val credentials = BrokerConnectionStore.ibkrFlexCredentials(context)
            ?: error("Token Flex ou Query ID absent")
        val snapshot = summarize(IbkrFlexClient.statement(context, credentials), LocalDate.now())
        synchronized(BrokerConnectionStore) {
            require(BrokerConnectionStore.ibkrFlexCredentials(context) == credentials) { "Connexion IBKR modifiée" }
            if (cached(context)?.reportDate?.isAfter(snapshot.reportDate) != true) {
                val json = JSONObject().put("symbol", snapshot.symbol).put("amount", snapshot.nextAmount)
                    .put("currency", snapshot.nextCurrency).put("payment", snapshot.paymentDate?.toString())
                    .put("received", snapshot.receivedYtdEur).put("accrued", snapshot.accruedEur)
                    .put("date", snapshot.reportDate.toString()).put("cash", snapshot.hasCashSection)
                    .put("accrual", snapshot.hasAccrualSection)
                prefs(context).edit().putString("snapshot", json.toString()).apply()
            }
        }
        return snapshot
    }

    internal fun summarize(xml: String, today: LocalDate): IbkrDividendSnapshot {
        val startOfYear = today.withDayOfYear(1)
        val received = mutableListOf<Dividend>()
        val accruals = mutableListOf<Dividend>()
        val seenPayments = mutableSetOf<Pair<String, String>>()
        var base = ""
        var account = ""
        var reportDate: LocalDate? = null
        var startDate: LocalDate? = null
        var statements = 0
        var cashSections = 0
        var accrualSections = 0
        var cashInvalid = false
        var accrualInvalid = false
        var coversYtd = true

        fun row(attrs: Map<String, String>, accrued: Boolean): Dividend? {
            val date = if (accrued) flexDate(attrs["payDate"]) else
                listOf("dateTime", "date", "reportDate", "settleDate").firstNotNullOfOrNull { flexDate(attrs[it]) }
            val amount = if (accrued) attrs.flexNumber("netAmount") ?: attrs.flexNumber("grossAmount")?.let {
                it - (attrs.flexNumber("tax") ?: return null) - (attrs.flexNumber("fee") ?: return null)
            } else attrs.flexNumber("amount")
            val currency = attrs["currency"].orEmpty().trim().uppercase(Locale.ROOT)
            if (date == null || amount == null || currency.isBlank()) return null
            return Dividend(attrs["symbol"].orEmpty(), amount, currency, date, flexAmountInEur(amount, currency, base, attrs))
        }

        readFlex(xml) { tag, attrs ->
            when (tag) {
                "FlexStatement" -> {
                    statements++
                    account = attrs["accountId"].orEmpty()
                    base = ""
                    val to = flexDate(attrs["toDate"]) ?: error("Date du relevé IBKR absente")
                    val from = flexDate(attrs["fromDate"])
                    if (from == null || from.isAfter(startOfYear) || to.isBefore(startOfYear)) coversYtd = false
                    startDate = from
                    reportDate = minOf(reportDate ?: to, to)
                }
                "AccountInformation" -> base = attrs["currency"].orEmpty()
                "CashTransactions" -> cashSections++
                "OpenDividendAccruals" -> accrualSections++
                "OpenDividendAccrual" -> {
                    val dividend = row(attrs, true)
                    if (dividend == null) accrualInvalid = true else accruals += dividend
                }
                "CashTransaction" -> {
                    val type = (attrs["type"] ?: attrs["transactionType"]).orEmpty().trim().lowercase(Locale.ROOT)
                    if (type in setOf("dividends", "dividend", "ordinary dividends", "payment in lieu of dividends",
                            "payment in lieu of dividend", "dividendes", "dividende")) {
                        val transactionId = attrs["transactionID"] ?: attrs["transactionId"]
                        if (!transactionId.isNullOrBlank() && !seenPayments.add((attrs["accountId"] ?: account) to transactionId)) return@readFlex
                        val dividend = row(attrs, false)
                        if (dividend == null) cashInvalid = true
                        else if (dividend.date >= startOfYear && dividend.date <= today &&
                            startDate?.let { dividend.date >= it } != false) received += dividend
                    }
                }
            }
        }
        val date = reportDate ?: error("Aucun relevé IBKR")
        val cashAvailable = statements > 0 && cashSections == statements
        val accrualsAvailable = statements > 0 && accrualSections == statements
        require(cashAvailable || accrualsAvailable) { "Sections Cash Transactions et Open Dividend Accruals absentes ou incomplètes" }
        val pending = if (accrualsAvailable && !accrualInvalid) accruals.filter { it.amount != 0.0 } else emptyList()
        val next = pending.filter { it.amount > 0 }.minByOrNull { it.date }
        val ytd = if (cashAvailable && coversYtd && !cashInvalid && received.all { it.eur != null }) received.sumOf { it.eur!! } else null
        val outstanding = if (accrualsAvailable && !accrualInvalid && pending.all { it.eur != null }) pending.sumOf { it.eur!! } else null
        return IbkrDividendSnapshot(
            next?.symbol, next?.amount, next?.currency, next?.date, ytd, outstanding, date,
            cashAvailable, accrualsAvailable
        )
    }

    fun cached(context: Context): IbkrDividendSnapshot? = runCatching {
        val json = JSONObject(prefs(context).getString("snapshot", null) ?: return null)
        fun string(key: String) = if (json.isNull(key)) null else json.getString(key)
        fun number(key: String) = if (json.isNull(key)) null else json.getDouble(key)
        IbkrDividendSnapshot(
            string("symbol"), number("amount"), string("currency"), flexDate(string("payment")),
            number("received"), number("accrued"), LocalDate.parse(json.getString("date")),
            json.getBoolean("cash"), json.getBoolean("accrual")
        )
    }.getOrNull()

    fun clear(context: Context) { prefs(context).edit().clear().apply() }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

internal fun flexAmountInEur(
    amount: Double, currency: String, base: String, attrs: Map<String, String>
): Double? = when {
    currency.equals("EUR", true) -> amount
    base.equals("EUR", true) -> attrs.flexNumber("fxRateToBase")?.takeIf { it > 0 }?.let { amount * it }
    amount == 0.0 && currency.isNotBlank() -> 0.0
    else -> null
}
