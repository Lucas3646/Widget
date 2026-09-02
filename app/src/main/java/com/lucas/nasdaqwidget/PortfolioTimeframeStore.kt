package com.lucas.nasdaqwidget

import android.content.Context

enum class PortfolioTimeframe(val label: String, val days: Int?) {
    SESSION("1S", 1),
    MONTH("1M", 30),
    THREE_MONTHS("3M", 90),
    YTD("YTD", null),
    YEAR("1A", 365)
}

object PortfolioTimeframeStore {
    private const val PREFS = "portfolio_tracker_preferences"
    private const val KEY = "timeframe"

    fun get(context: Context): PortfolioTimeframe {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, PortfolioTimeframe.SESSION.name)
        return runCatching { PortfolioTimeframe.valueOf(raw ?: PortfolioTimeframe.SESSION.name) }
            .getOrDefault(PortfolioTimeframe.SESSION)
    }

    fun set(context: Context, timeframe: PortfolioTimeframe) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, timeframe.name).apply()
    }
}
