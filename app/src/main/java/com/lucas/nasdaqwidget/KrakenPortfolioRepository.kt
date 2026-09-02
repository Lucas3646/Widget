package com.lucas.nasdaqwidget

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class KrakenPositionSnapshot(val symbol: String, val valueEur: Double, val dayChangeEur: Double, val dayChangePercent: Double)
data class KrakenPortfolioSnapshot(val totalEur: Double, val dayChangeEur: Double, val dayChangePercent: Double, val balances: Map<String, Double>, val positions: List<KrakenPositionSnapshot>, val updatedAt: Long)
private data class TickerSnapshot(val current: Double, val open: Double)

object KrakenPortfolioRepository {
    private const val PREFS = "kraken_portfolio_cache"
    private const val BASE = "https://api.kraken.com"
    private val cashLikeAssets = setOf("EUR", "USD", "GBP", "CHF", "CAD", "AUD", "JPY", "USDT", "USDC")

    fun refresh(context: Context, timeframe: PortfolioTimeframe = PortfolioTimeframeStore.get(context)): KrakenPortfolioSnapshot {
        val credentials = BrokerConnectionStore.krakenCredentials(context) ?: throw IllegalStateException("Identifiants Kraken absents")
        val balances = fetchBalances(credentials).filterValues { kotlin.math.abs(it) > 0.00000001 }
        var total = 0.0; var totalChange = 0.0
        val positions = mutableListOf<KrakenPositionSnapshot>()
        balances.forEach { (rawAsset, amount) ->
            val symbol = normalizeAsset(rawAsset)
            val currentTicker = eurTicker(rawAsset)
            val current = currentTicker?.current ?: 0.0
            val reference = if (timeframe == PortfolioTimeframe.SESSION) currentTicker?.open ?: current else historicalEurPrice(rawAsset, timeframe) ?: current
            val value = amount * current
            val change = amount * (current - reference)
            val percent = if (reference > 0) (current / reference - 1.0) * 100.0 else 0.0
            total += value; totalChange += change
            if (symbol !in cashLikeAssets && current > 0 && value > 0.01) positions += KrakenPositionSnapshot(if (symbol == "XBT") "BTC" else symbol, value, change, percent)
        }
        val previous = total - totalChange
        val snapshot = KrakenPortfolioSnapshot(total, totalChange, if (previous > 0) totalChange / previous * 100.0 else 0.0, balances, positions, System.currentTimeMillis())
        cache(context, snapshot); BrokerConnectionStore.setKrakenVerified(context, true); return snapshot
    }

    private fun historicalEurPrice(rawAsset: String, timeframe: PortfolioTimeframe): Double? {
        val asset = normalizeAsset(rawAsset)
        if (asset == "EUR") return 1.0
        val daysBack = when (timeframe) {
            PortfolioTimeframe.MONTH -> 30L
            PortfolioTimeframe.THREE_MONTHS -> 90L
            PortfolioTimeframe.YEAR -> 365L
            PortfolioTimeframe.YTD -> (LocalDate.now().toEpochDay() - LocalDate.now().withDayOfYear(1).toEpochDay()).coerceAtLeast(1)
            PortfolioTimeframe.SESSION -> 1L
        }
        val since = LocalDate.now().minusDays(daysBack).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val pair = when (asset) { "XBT" -> "XBTEUR"; else -> "${asset}EUR" }
        return ohlcOpen(pair, since) ?: if (asset == "USD") {
            val eurUsd = ohlcOpen("EURUSD", since); eurUsd?.let { 1.0 / it }
        } else null
    }

    private fun ohlcOpen(pair: String, since: Long): Double? = runCatching {
        val url = "$BASE/0/public/OHLC?pair=${URLEncoder.encode(pair, "UTF-8")}&interval=1440&since=$since"
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout=8000; readTimeout=8000; setRequestProperty("User-Agent","MarketWidgets/1.0") }
        if (c.responseCode !in 200..299) return@runCatching null
        val result = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optJSONObject("result") ?: return@runCatching null
        val key = result.keys().asSequence().firstOrNull { it != "last" } ?: return@runCatching null
        val rows = result.optJSONArray(key) ?: return@runCatching null
        var best: JSONArray? = null
        for (i in 0 until rows.length()) { val row=rows.optJSONArray(i) ?: continue; if (row.optLong(0) >= since) { best=row; break } }
        best?.optString(1)?.toDoubleOrNull()
    }.getOrNull()

    fun cached(context: Context): KrakenPortfolioSnapshot? {
        val prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE); val raw=prefs.getString("balances",null)?:return null
        val obj=runCatching{JSONObject(raw)}.getOrNull()?:return null; val balances=mutableMapOf<String,Double>(); obj.keys().forEach{balances[it]=obj.optDouble(it,0.0)}
        val positions=mutableListOf<KrakenPositionSnapshot>(); prefs.getString("positions",null)?.let { runCatching{JSONArray(it)}.getOrNull()?.let{a->for(i in 0 until a.length()){val p=a.optJSONObject(i)?:continue;positions+=KrakenPositionSnapshot(p.optString("symbol"),p.optDouble("valueEur"),p.optDouble("dayChangeEur"),p.optDouble("dayChangePercent"))}}}
        return KrakenPortfolioSnapshot(java.lang.Double.longBitsToDouble(prefs.getLong("total",0)),java.lang.Double.longBitsToDouble(prefs.getLong("dayChange",0)),java.lang.Double.longBitsToDouble(prefs.getLong("dayPercent",0)),balances,positions,prefs.getLong("updated",0))
    }
    fun clear(context:Context){context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().clear().apply()}
    private fun cache(context:Context,s:KrakenPortfolioSnapshot){val b=JSONObject();s.balances.forEach{(k,v)->b.put(k,v)};val p=JSONArray();s.positions.forEach{p.put(JSONObject().put("symbol",it.symbol).put("valueEur",it.valueEur).put("dayChangeEur",it.dayChangeEur).put("dayChangePercent",it.dayChangePercent))};context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putLong("total",java.lang.Double.doubleToRawLongBits(s.totalEur)).putLong("dayChange",java.lang.Double.doubleToRawLongBits(s.dayChangeEur)).putLong("dayPercent",java.lang.Double.doubleToRawLongBits(s.dayChangePercent)).putString("balances",b.toString()).putString("positions",p.toString()).putLong("updated",s.updatedAt).apply()}
    private fun fetchBalances(c:BrokerConnectionStore.KrakenCredentials):Map<String,Double>{val path="/0/private/Balance";val nonce=System.currentTimeMillis().toString();val data="nonce="+URLEncoder.encode(nonce,"UTF-8");val con=(URL(BASE+path).openConnection() as HttpURLConnection).apply{requestMethod="POST";connectTimeout=12000;readTimeout=12000;doOutput=true;setRequestProperty("API-Key",c.apiKey);setRequestProperty("API-Sign",sign(path,nonce,data,c.apiSecret));setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");setRequestProperty("User-Agent","MarketWidgets/1.0")};con.outputStream.use{it.write(data.toByteArray(StandardCharsets.UTF_8))};val body=(if(con.responseCode in 200..299)con.inputStream else con.errorStream).bufferedReader().use{it.readText()};val j=JSONObject(body);val e=j.optJSONArray("error");if(e!=null&&e.length()>0)throw IllegalStateException(e.optString(0,"Erreur Kraken"));val r=j.optJSONObject("result")?:throw IllegalStateException("Réponse Kraken invalide");val out=linkedMapOf<String,Double>();r.keys().forEach{val v=r.optString(it).toDoubleOrNull()?:0.0;if(v!=0.0)out[it]=v};return out}
    private fun sign(path:String,nonce:String,data:String,secret:String):String{val hash=MessageDigest.getInstance("SHA-256").digest((nonce+data).toByteArray(StandardCharsets.UTF_8));val mac=Mac.getInstance("HmacSHA512");mac.init(SecretKeySpec(Base64.decode(secret,Base64.DEFAULT),"HmacSHA512"));return Base64.encodeToString(mac.doFinal(path.toByteArray(StandardCharsets.UTF_8)+hash),Base64.NO_WRAP)}
    private fun eurTicker(raw:String):TickerSnapshot?{val a=normalizeAsset(raw);if(a=="EUR")return TickerSnapshot(1.0,1.0);val pairs=when(a){"XBT"->listOf("XBTEUR" to false,"XXBTZEUR" to false);"USD"->listOf("EURUSD" to true);"USDT"->listOf("USDTEUR" to false);"USDC"->listOf("USDCEUR" to false);else->listOf("${a}EUR" to false)};for((pair,inv)in pairs){val t=fetchTicker(pair)?:continue;if(t.current>0&&t.open>0)return if(inv)TickerSnapshot(1/t.current,1/t.open)else t};return null}
    private fun fetchTicker(pair:String):TickerSnapshot?=runCatching{val c=(URL("$BASE/0/public/Ticker?pair=${URLEncoder.encode(pair,"UTF-8")}").openConnection() as HttpURLConnection).apply{connectTimeout=8000;readTimeout=8000;setRequestProperty("User-Agent","MarketWidgets/1.0")};if(c.responseCode !in 200..299)return@runCatching null;val r=JSONObject(c.inputStream.bufferedReader().use{it.readText()}).optJSONObject("result")?:return@runCatching null;val k=r.keys().asSequence().firstOrNull()?:return@runCatching null;val t=r.optJSONObject(k)?:return@runCatching null;val current=t.optJSONArray("c")?.optString(0)?.toDoubleOrNull()?:return@runCatching null;TickerSnapshot(current,t.optString("o").toDoubleOrNull()?:current)}.getOrNull()
    private fun normalizeAsset(raw:String):String{val a=raw.substringBefore('.');return when(a){"ZEUR"->"EUR";"ZUSD"->"USD";"XXBT"->"XBT";"XETH"->"ETH";else->a.removePrefix("X").removePrefix("Z")}}
}
