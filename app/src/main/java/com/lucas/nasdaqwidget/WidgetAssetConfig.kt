package com.lucas.nasdaqwidget

import android.content.Context

object WidgetAssetConfig {
    private const val PREFS = "market_widget_assets"

    fun symbol(context: Context, appWidgetId: Int): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("symbol_$appWidgetId", "^NDX") ?: "^NDX"

    fun name(context: Context, appWidgetId: Int): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("name_$appWidgetId", "NASDAQ 100") ?: "NASDAQ 100"

    fun save(context: Context, appWidgetId: Int, symbol: String, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("symbol_$appWidgetId", symbol)
            .putString("name_$appWidgetId", name.ifBlank { symbol })
            .apply()
    }

    fun remove(context: Context, appWidgetId: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove("symbol_$appWidgetId")
            .remove("name_$appWidgetId")
            .apply()
    }
}
