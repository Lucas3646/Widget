package com.lucas.nasdaqwidget

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class AssetSuggestion(
    val symbol: String,
    val name: String,
    val type: String,
    val exchange: String = ""
) {
    fun displayLabel(): String {
        val meta = listOf(type, exchange).filter { it.isNotBlank() }.joinToString(" · ")
        return if (meta.isBlank()) "$name · $symbol" else "$name · $symbol\n$meta"
    }
}

object AssetSearchRepository {
    private val commonAssets = listOf(
        AssetSuggestion("BTC-USD", "Bitcoin USD", "Crypto"),
        AssetSuggestion("ETH-USD", "Ethereum USD", "Crypto"),
        AssetSuggestion("SOL-USD", "Solana USD", "Crypto"),
        AssetSuggestion("AAPL", "Apple Inc.", "Action", "NASDAQ"),
        AssetSuggestion("NVDA", "NVIDIA Corporation", "Action", "NASDAQ"),
        AssetSuggestion("TSLA", "Tesla, Inc.", "Action", "NASDAQ"),
        AssetSuggestion("MSFT", "Microsoft Corporation", "Action", "NASDAQ"),
        AssetSuggestion("AMZN", "Amazon.com, Inc.", "Action", "NASDAQ"),
        AssetSuggestion("META", "Meta Platforms, Inc.", "Action", "NASDAQ"),
        AssetSuggestion("GOOGL", "Alphabet Inc.", "Action", "NASDAQ"),
        AssetSuggestion("AMD", "Advanced Micro Devices, Inc.", "Action", "NASDAQ"),
        AssetSuggestion("NFLX", "Netflix, Inc.", "Action", "NASDAQ"),
        AssetSuggestion("SPY", "SPDR S&P 500 ETF Trust", "ETF", "NYSE Arca"),
        AssetSuggestion("QQQ", "Invesco QQQ Trust", "ETF", "NASDAQ"),
        AssetSuggestion("GLD", "SPDR Gold Shares", "ETF", "NYSE Arca"),
        AssetSuggestion("GC=F", "Gold Futures", "Future", "COMEX"),
        AssetSuggestion("CL=F", "Crude Oil Futures", "Future", "NYMEX"),
        AssetSuggestion("EURUSD=X", "EUR/USD", "Forex"),
        AssetSuggestion("GBPUSD=X", "GBP/USD", "Forex")
    )

    fun search(query: String): List<AssetSuggestion> {
        val clean = query.trim()
        if (clean.isBlank()) return emptyList()

        val local = localSearch(clean)
        if (clean.length < 2) return local.take(8)

        val remote = runCatching { remoteSearch(clean) }.getOrDefault(emptyList())
        return (remote + local)
            .distinctBy { it.symbol }
            .take(8)
    }

    private fun localSearch(query: String): List<AssetSuggestion> {
        val q = query.lowercase()
        return commonAssets.filter {
            it.symbol.lowercase().contains(q) || it.name.lowercase().contains(q)
        }.sortedWith(
            compareBy<AssetSuggestion> {
                when {
                    it.symbol.equals(query, ignoreCase = true) -> 0
                    it.symbol.startsWith(query, ignoreCase = true) -> 1
                    it.name.startsWith(query, ignoreCase = true) -> 2
                    else -> 3
                }
            }.thenBy { it.symbol }
        )
    }

    private fun remoteSearch(query: String): List<AssetSuggestion> {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        val endpoint = "https://query2.finance.yahoo.com/v1/finance/search?q=$encoded&quotesCount=8&newsCount=0&enableFuzzyQuery=true"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 7_000
            readTimeout = 7_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MarketWidgets/1.1 Android")
        }

        try {
            if (connection.responseCode !in 200..299) return emptyList()
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val quotes = JSONObject(body).optJSONArray("quotes") ?: return emptyList()
            return buildList {
                for (i in 0 until quotes.length()) {
                    val item = quotes.optJSONObject(i) ?: continue
                    val symbol = item.optString("symbol").trim()
                    if (symbol.isBlank()) continue
                    val quoteType = item.optString("quoteType")
                    if (quoteType.equals("OPTION", ignoreCase = true)) continue
                    val name = item.optString("longname")
                        .ifBlank { item.optString("shortname") }
                        .ifBlank { symbol }
                    add(
                        AssetSuggestion(
                            symbol = symbol,
                            name = name,
                            type = friendlyType(quoteType),
                            exchange = item.optString("exchange")
                        )
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun friendlyType(type: String): String = when (type.uppercase()) {
        "EQUITY" -> "Action"
        "ETF" -> "ETF"
        "CRYPTOCURRENCY" -> "Crypto"
        "CURRENCY" -> "Forex"
        "FUTURE" -> "Future"
        "INDEX" -> "Indice"
        "MUTUALFUND" -> "Fonds"
        else -> type.lowercase().replaceFirstChar { it.uppercase() }.ifBlank { "Actif" }
    }
}
