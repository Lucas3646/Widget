package com.lucas.nasdaqwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

class IbkrReportTest {
    private val today = LocalDate.of(2026, 11, 19)
    private val reportDate = today.minusDays(1)

    @Test
    fun dividendCorrectionsKeepTheirSignAndCashTaxIsExcludedFromGross() {
        val xml = report(statement("A", body = """
            <CashTransactions>
                <CashTransaction type="Dividends" transactionID="T1" amount="100" currency="USD"
                    fxRateToBase="0.9" dateTime="20261104;161233" symbol="AAPL"/>
                <CashTransaction type="Dividends" transactionID="T1" amount="100" currency="USD"
                    fxRateToBase="0.9" dateTime="20261104;161233" symbol="AAPL"/>
                <CashTransaction type="Dividends" transactionID="T2" amount="-20" currency="USD"
                    fxRateToBase="0.95" dateTime="2026-11-05T12:00:00" symbol="AAPL"/>
                <CashTransaction type="Withholding Tax" description="AAPL dividend tax" amount="-10"
                    currency="USD" fxRateToBase="0.9" dateTime="20261104"/>
            </CashTransactions>
            <OpenDividendAccruals>
                <OpenDividendAccrual symbol="KO" grossAmount="12" netAmount="10" currency="USD"
                    fxRateToBase="0.9" exDate="20261104" payDate="20261117"/>
                <OpenDividendAccrual symbol="SHORT" netAmount="-5" currency="USD"
                    fxRateToBase="0.9" payDate="20261201"/>
                <OpenDividendAccrual symbol="NEXT" grossAmount="12" tax="1.5" fee="0.5" currency="EUR"
                    payDate="20270104"/>
            </OpenDividendAccruals>
        """))
        val result = IbkrDividendRepository.summarize(xml, today)
        assertEquals(71.0, result.receivedYtdEur!!, 0.001)
        assertEquals(14.5, result.accruedEur!!, 0.001)
        assertEquals("KO", result.symbol)
        assertEquals(10.0, result.nextAmount!!, 0.001)
        assertEquals(today.minusDays(2), result.paymentDate)
        assertEquals(reportDate, result.reportDate)
    }

    @Test
    fun shortReportAndMissingFxDoNotBecomeZeroOrEuroAtPar() {
        val xml = report(statement("A", from = "20261101", body = """
            <CashTransactions/>
            <OpenDividendAccruals>
                <OpenDividendAccrual symbol="KO" netAmount="10" currency="USD" payDate="20261120"/>
            </OpenDividendAccruals>
        """))
        val result = IbkrDividendRepository.summarize(xml, today)
        assertNull(result.receivedYtdEur)
        assertNull(result.accruedEur)
        assertEquals("USD", result.nextCurrency)
    }

    @Test
    fun accrualWithoutPaymentDateDoesNotUseExDate() {
        val xml = report(statement("A", body = """
            <CashTransactions/>
            <OpenDividendAccruals>
                <OpenDividendAccrual symbol="KO" netAmount="10" currency="EUR" exDate="20261120"/>
            </OpenDividendAccruals>
        """))
        val result = IbkrDividendRepository.summarize(xml, today)
        assertEquals(0.0, result.receivedYtdEur!!, 0.001)
        assertNull(result.paymentDate)
        assertNull(result.accruedEur)
    }

    @Test
    fun netValueUsesCommonAccountDatesAndIncludesNegativeEquity() {
        val navA = """
            <EquitySummaryByReportDateInBase reportDate="20261116" total="900"/>
            <EquitySummaryByReportDateInBase reportDate="20261117" total="1000"/>
            <EquitySummaryByReportDateInBase reportDate="20261118" total="1100"/>
            <ChangeInNAV twr="20"/>
        """
        val navB = """
            <EquitySummaryByReportDateInBase reportDate="20261116" total="-50"/>
            <EquitySummaryByReportDateInBase reportDate="20261117" total="-100"/>
        """
        val data = IbkrFlexRepository.summarize(report(statement("A", body = navA), statement("B", body = navB)),
            PortfolioTimeframe.YTD, today)
        assertEquals(900.0, data.totalEur, 0.001)
        assertEquals(listOf(850.0, 900.0), data.chartValues)
        assertEquals(today.minusDays(2), data.reportDate)
        assertNull(data.periodReturnPercent)
    }

    @Test
    fun brokerTwrBelongsOnlyToTheReportsExactPeriod() {
        val xml = report(statement("A", body = """
            <EquitySummaryByReportDateInBase reportDate="20261118" total="1000"/>
            <ChangeInNAV twr="2.25"/>
        """))
        assertEquals(2.25, IbkrFlexRepository.summarize(xml, PortfolioTimeframe.YTD, today).periodReturnPercent!!, 0.001)
        assertNull(IbkrFlexRepository.summarize(xml, PortfolioTimeframe.MONTH, today).periodReturnPercent)
        assertNull(IbkrFlexRepository.summarize(xml, PortfolioTimeframe.SESSION, today).periodReturnPercent)
    }

    @Test
    fun missingSectionsAndWrongReportTypeAreRejected() {
        val xml = report(statement("A", body = "<CashReport/>"))
        assertThrows(IllegalArgumentException::class.java) { IbkrDividendRepository.summarize(xml, today) }
        assertThrows(IllegalArgumentException::class.java) { IbkrFlexRepository.summarize(xml, PortfolioTimeframe.YTD, today) }
        assertThrows(IllegalArgumentException::class.java) {
            IbkrDividendRepository.summarize("<FlexStatementResponse><Status>Fail</Status></FlexStatementResponse>", today)
        }
    }

    private fun report(vararg statements: String) = """
        <FlexQueryResponse><FlexStatements>${statements.joinToString("")}</FlexStatements></FlexQueryResponse>
    """.trimIndent()

    private fun statement(id: String, from: String = "20260101", body: String) = """
        <FlexStatement accountId="$id" fromDate="$from" toDate="20261118">
            <AccountInformation currency="EUR"/>
            $body
        </FlexStatement>
    """.trimIndent()
}
