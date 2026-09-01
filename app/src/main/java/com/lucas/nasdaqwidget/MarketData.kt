package com.lucas.nasdaqwidget

data class MarketData(
    val price: Double,
    val change: Double,
    val changePercent: Double,
    val candles: List<Float>,
    val updatedAtMillis: Long = System.currentTimeMillis()
)

object MarketRepository {
    // Demo feed for the first visual/build validation.
    // The live market provider is intentionally isolated here so we can plug in
    // the selected free API without touching the widget rendering code.
    fun current(): MarketData = MarketData(
        price = 19842.35,
        change = 145.92,
        changePercent = 0.74,
        candles = listOf(
            19696f, 19722f, 19708f, 19742f, 19731f, 19758f, 19745f,
            19766f, 19789f, 19802f, 19794f, 19818f, 19830f, 19816f,
            19791f, 19760f, 19735f, 19772f, 19788f, 19780f, 19801f,
            19815f, 19803f, 19824f, 19839f, 19830f, 19848f, 19842.35f
        )
    )
}
