package com.lucas.nasdaqwidget

import android.content.Context

object WidgetAssetConfig {
    private const val PREFS = "market_widget_assets"
    const val TF_1D = "1D"
    const val TF_5D = "5D"
    const val TF_1M = "1M"
    const val TF_3M = "3M"
    const val TF_YTD = "YTD"
    const val TF_1Y = "1Y"

    val timeframes = listOf(TF_1D, TF_5D, TF_1M, TF_3M, TF_YTD, TF_1Y)

    fun symbol(context: Context, appWidgetId: Int): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("symbol_$appWidgetId", "^NDX") ?: "^NDX"

    fun name(context: Context, appWidgetId: Int): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("name_$appWidgetId", "NASDAQ 100") ?: "NASDAQ 100"

    fun timeframe(context: Context, appWidgetId: Int): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("timeframe_$appWidgetId", TF_1D) ?: TF_1D

    fun save(context: Context, appWidgetId: Int, symbol: String, name: String, timeframe: String = TF_1D) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("symbol_$appWidgetId", symbol)
            .putString("name_$appWidgetId", name.ifBlank { symbol })
            .putString("timeframe_$appWidgetId", timeframe.takeIf { it in timeframes } ?: TF_1D)
            .apply()
    }

    fun remove(context: Context, appWidgetId: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove("symbol_$appWidgetId")
            .remove("name_$appWidgetId")
            .remove("timeframe_$appWidgetId")
            .apply()
    }
}
