package com.lucas.nasdaqwidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

data class IbkrPortfolioSnapshot(
    val totalEur: Double,
    val chartValues: List<Double>,
    val reportDate: LocalDate,
    val timeframe: PortfolioTimeframe,
    val periodReturnPercent: Double?
)

object IbkrFlexRepository {
    private const val PREFS = "ibkr_flex_cache"

    private data class Account(
        val from: LocalDate?,
        val to: LocalDate,
        var base: String = "",
        var twr: Double? = null,
        var twrRows: Int = 0,
        val nav: MutableMap<LocalDate, Double> = mutableMapOf()
    )

    fun refresh(context: Context, timeframe: PortfolioTimeframe = PortfolioTimeframeStore.get(context)): IbkrPortfolioSnapshot {
        val credentials = BrokerConnectionStore.ibkrFlexCredentials(context) ?: error("Token Flex ou Query ID absent")
        val snapshot = summarize(IbkrFlexClient.statement(context, credentials), timeframe, LocalDate.now())
        synchronized(BrokerConnectionStore) {
            require(BrokerConnectionStore.ibkrFlexCredentials(context) == credentials) { "Connexion IBKR modifiée" }
            if (cached(context)?.reportDate?.isAfter(snapshot.reportDate) != true) {
                val json = JSONObject().put("total", snapshot.totalEur).put("date", snapshot.reportDate.toString())
                    .put("timeframe", snapshot.timeframe.name).put("twr", snapshot.periodReturnPercent)
                    .put("chart", JSONArray(snapshot.chartValues))
                prefs(context).edit().putString("snapshot", json.toString()).apply()
            }
            BrokerConnectionStore.setIbkrVerified(context, true)
        }
        return snapshot
    }

    internal fun summarize(xml: String, timeframe: PortfolioTimeframe, today: LocalDate): IbkrPortfolioSnapshot {
        val accounts = linkedMapOf<String, Account>()
        var account: Account? = null
        readFlex(xml) { tag, attrs ->
            when (tag) {
                "FlexStatement" -> {
                    val to = flexDate(attrs["toDate"]) ?: error("Date du relevé IBKR absente")
                    val id = attrs["accountId"].orEmpty()
                    val current = Account(flexDate(attrs["fromDate"]), to)
                    require(accounts.put(id, current) == null) { "Compte IBKR dupliqué dans le relevé" }
                    account = current
                }
                "AccountInformation" -> account?.base = attrs["currency"].orEmpty()
                "ChangeInNAV" -> account?.let {
                    it.twrRows++
                    it.twr = attrs.flexNumber("twr")
                }
                "EquitySummaryByReportDateInBase", "EquitySummaryByReportDateInBaseCurrency" -> account?.let {
                    val date = flexDate(attrs["reportDate"]) ?: return@let
                    val amount = attrs.flexNumber("total") ?: return@let
                    val currency = attrs["currency"].orEmpty().ifBlank { it.base }
                    val eur = flexAmountInEur(amount, currency, it.base, attrs)
                        ?: error("Valeur nette IBKR en $currency · conversion EUR indisponible")
                    val previous = it.nav.put(date, eur)
                    require(previous == null || previous == eur) { "NAV IBKR ambiguë pour le $date" }
                }
            }
        }
        require(accounts.isNotEmpty() && accounts.values.all { it.nav.isNotEmpty() }) {
            "Ajoutez NAV in Base Currency, Account Information et leurs champs à la Flex Query"
        }
        val commonDates = accounts.values.map { it.nav.keys.toSet() }.reduce { a, b -> a intersect b }
            .filter { it <= today && accounts.values.all { account -> it <= account.to } }.sorted()
        val date = commonDates.lastOrNull() ?: error("Aucune date de valorisation commune aux comptes IBKR")
        val total = accounts.values.sumOf { it.nav.getValue(date) }
        val start = when (timeframe) {
            PortfolioTimeframe.SESSION -> date.minusDays(7)
            PortfolioTimeframe.MONTH -> today.minusMonths(1)
            PortfolioTimeframe.THREE_MONTHS -> today.minusMonths(3)
            PortfolioTimeframe.YTD -> today.withDayOfYear(1)
            PortfolioTimeframe.YEAR -> today.minusYears(1)
        }
        val chart = commonDates.filter { it >= start }.map { day -> accounts.values.sumOf { it.nav.getValue(day) } }
        val single = accounts.values.singleOrNull()
        val expectedFrom = if (timeframe == PortfolioTimeframe.SESSION) date else start
        val twr = single?.takeIf { it.twrRows == 1 && it.from == expectedFrom && it.to == date }?.twr
        return IbkrPortfolioSnapshot(total, chart, date, timeframe, twr)
    }

    fun cached(context: Context): IbkrPortfolioSnapshot? = runCatching {
        val json = JSONObject(prefs(context).getString("snapshot", null) ?: return null)
        val chart = json.getJSONArray("chart")
        IbkrPortfolioSnapshot(
            json.getDouble("total"), List(chart.length()) { chart.getDouble(it) },
            LocalDate.parse(json.getString("date")), PortfolioTimeframe.valueOf(json.getString("timeframe")),
            if (json.isNull("twr")) null else json.getDouble("twr")
        )
    }.getOrNull()

    fun clear(context: Context) { prefs(context).edit().clear().apply() }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
