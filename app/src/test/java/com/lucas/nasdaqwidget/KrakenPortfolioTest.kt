package com.lucas.nasdaqwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KrakenPortfolioTest {
    @Test
    fun unknownAssetLeavesTheKnownAmountsAvailableWithoutAPerformance() {
        val snapshot = KrakenPortfolioRepository.valueBalances(
            mapOf("ZEUR" to 100.0, "XXBT" to 1.0, "XBT.F" to 0.5, "UNKNOWN" to 4.0),
            { if (it == "XBT") TickerSnapshot(200.0, 180.0) else null },
            1L
        )
        assertEquals(400.0, snapshot.availableTotalEur!!, 0.001)
        assertEquals(setOf("UNKNOWN"), snapshot.unpricedAssets)
        assertEquals(1, snapshot.positions.size)
        assertTrue(snapshot.dayChangePercent.isNaN())
    }

    @Test
    fun missingOpeningPriceKeepsTheValuation() {
        val snapshot = KrakenPortfolioRepository.valueBalances(
            mapOf("XETH" to 2.0), { TickerSnapshot(500.0, null) }, 1L
        )
        assertEquals(1_000.0, snapshot.availableTotalEur!!, 0.001)
        assertTrue(snapshot.unpricedAssets.isEmpty())
        assertTrue(snapshot.dayChangePercent.isNaN())
    }

    @Test
    fun unvaluedAccountDiffersFromAnEmptyAccount() {
        val unknown = KrakenPortfolioRepository.valueBalances(mapOf("UNKNOWN" to 1.0), { null }, 1L)
        val empty = KrakenPortfolioRepository.valueBalances(emptyMap(), { null }, 1L)
        assertNull(unknown.availableTotalEur)
        assertEquals(0.0, empty.availableTotalEur!!, 0.001)
    }
}
