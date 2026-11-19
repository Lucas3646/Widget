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
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class KrakenPositionSnapshot(val symbol:String,val valueEur:Double,val dayChangeEur:Double,val dayChangePercent:Double)
data class KrakenPortfolioSnapshot(val totalEur:Double,val dayChangeEur:Double,val dayChangePercent:Double,val balances:Map<String,Double>,val positions:List<KrakenPositionSnapshot>,val updatedAt:Long)
private data class TickerSnapshot(val current:Double,val open:Double)
object KrakenPortfolioRepository {
    private const val PREFS = "kraken_portfolio_cache"
    private const val BASE = "https://api.kraken.com"
    private var lastNonce = 0L

    @Synchronized
    fun refresh(context: Context): KrakenPortfolioSnapshot {
        val credentials = BrokerConnectionStore.krakenCredentials(context) ?: error("Identifiants Kraken absents")
        val balances = fetchBalances(credentials).filterValues { it != 0.0 }
        val tickers = mutableMapOf<String, TickerSnapshot>()
        var total = 0.0
        var change = 0.0
        val positions = mutableListOf<KrakenPositionSnapshot>()

        balances.forEach { (asset, amount) ->
            val symbol = normalizeAsset(asset)
            val quote = tickers.getOrPut(symbol) { eurTicker(symbol) ?: error("Cours Kraken en EUR indisponible pour $symbol") }
            val current = amount * quote.current
            val delta = amount * (quote.current - quote.open)
            total += current
            change += delta
            if (symbol != "EUR") {
                positions += KrakenPositionSnapshot(if (symbol == "XBT") "BTC" else symbol, current,
                    delta, (quote.current / quote.open - 1.0) * 100.0)
            }
        }
        val previous = total - change
        val snapshot = KrakenPortfolioSnapshot(total, change, if (previous > 0) change / previous * 100 else Double.NaN,
            balances, positions, System.currentTimeMillis())
        synchronized(BrokerConnectionStore) {
            require(BrokerConnectionStore.krakenCredentials(context) == credentials) { "Connexion Kraken modifiée" }
            cache(context, snapshot)
            BrokerConnectionStore.setKrakenVerified(context, true)
        }
        return snapshot
    }

    fun cached(context:Context):KrakenPortfolioSnapshot?{
        val p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        val raw=p.getString("valued_balances",null)?:return null
        val o=runCatching{JSONObject(raw)}.getOrNull()?:return null
        val b=mutableMapOf<String,Double>();o.keys().forEach{b[it]=o.optDouble(it)}
        val pos=mutableListOf<KrakenPositionSnapshot>()
        p.getString("positions",null)?.let{runCatching{JSONArray(it)}.getOrNull()?.let{a->for(i in 0 until a.length()){val x=a.optJSONObject(i)?:continue;pos+=KrakenPositionSnapshot(x.optString("symbol"),x.optDouble("valueEur"),x.optDouble("dayChangeEur"),x.optDouble("dayChangePercent"))}}}
        return KrakenPortfolioSnapshot(Double.fromBits(p.getLong("total",0)),Double.fromBits(p.getLong("dayChange",0)),Double.fromBits(p.getLong("dayPercent",0)),b,pos,p.getLong("updated",0))
    }
    fun clear(context:Context){context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().clear().apply()}
    private fun cache(context:Context,s:KrakenPortfolioSnapshot){
        val b=JSONObject();s.balances.forEach{(k,v)->b.put(k,v)}
        val a=JSONArray();s.positions.forEach{a.put(JSONObject().put("symbol",it.symbol).put("valueEur",it.valueEur).put("dayChangeEur",it.dayChangeEur).put("dayChangePercent",it.dayChangePercent))}
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putLong("total",s.totalEur.toBits()).putLong("dayChange",s.dayChangeEur.toBits()).putLong("dayPercent",s.dayChangePercent.toBits()).putString("valued_balances",b.toString()).putString("positions",a.toString()).putLong("updated",s.updatedAt).apply()
    }
    private fun fetchBalances(c:BrokerConnectionStore.KrakenCredentials):Map<String,Double>{
        val r=privatePost(c,"/0/private/Balance",emptyMap());val x=r.getJSONObject("result");val out=linkedMapOf<String,Double>()
        x.keys().forEach{val v=x.optString(it).toDoubleOrNull()?.takeIf(Double::isFinite) ?: error("Solde Kraken invalide");if(v!=0.0)out[it]=v}
        return out
    }
    private fun privatePost(c:BrokerConnectionStore.KrakenCredentials,path:String,params:Map<String,String>):JSONObject{
        lastNonce = maxOf(System.currentTimeMillis(), lastNonce + 1)
        val nonce = lastNonce.toString()
        val data=(listOf("nonce="+URLEncoder.encode(nonce,"UTF-8"))+params.map{URLEncoder.encode(it.key,"UTF-8")+"="+URLEncoder.encode(it.value,"UTF-8")}).joinToString("&")
        val con=(URL(BASE+path).openConnection() as HttpURLConnection).apply{requestMethod="POST";connectTimeout=12000;readTimeout=12000;doOutput=true;setRequestProperty("API-Key",c.apiKey);setRequestProperty("API-Sign",sign(path,nonce,data,c.apiSecret));setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");setRequestProperty("User-Agent","MarketWidgets/2.1")}
        return try{con.outputStream.use{it.write(data.toByteArray(StandardCharsets.UTF_8))};val body=(if(con.responseCode in 200..299)con.inputStream else con.errorStream).bufferedReader().use{it.readText()};val j=JSONObject(body);val e=j.optJSONArray("error");if(e!=null&&e.length()>0)error(e.optString(0,"Erreur Kraken"));j}finally{con.disconnect()}
    }
    private fun sign(path:String,nonce:String,data:String,secret:String):String{
        val hash=MessageDigest.getInstance("SHA-256").digest((nonce+data).toByteArray());val mac=Mac.getInstance("HmacSHA512");mac.init(SecretKeySpec(Base64.decode(secret,Base64.DEFAULT),"HmacSHA512"));return Base64.encodeToString(mac.doFinal(path.toByteArray()+hash),Base64.NO_WRAP)
    }
    private fun eurTicker(asset: String): TickerSnapshot? = when (asset) {
        "EUR" -> TickerSnapshot(1.0, 1.0)
        "USD" -> fetchTicker("EURUSD")?.let { TickerSnapshot(1 / it.current, 1 / it.open) }
        else -> fetchTicker("${asset}EUR") ?: run {
            val usd = fetchTicker("${asset}USD") ?: return@run null
            val euro = fetchTicker("EURUSD") ?: return@run null
            TickerSnapshot(usd.current / euro.current, usd.open / euro.open)
        }
    }

    private fun fetchTicker(pair: String): TickerSnapshot? = runCatching {
        val connection = URL("$BASE/0/public/Ticker?pair=${URLEncoder.encode(pair, "UTF-8")}").openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        try {
            val result = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getJSONObject("result")
            val ticker = result.getJSONObject(result.keys().next())
            val current = ticker.getJSONArray("c").getString(0).toDouble()
            val open = ticker.getString("o").toDouble()
            require(current.isFinite() && current > 0 && open.isFinite() && open > 0)
            TickerSnapshot(current, open)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun normalizeAsset(raw: String): String {
        val asset = raw.substringBefore('.').uppercase()
        return when (asset) {
            "XXBT", "BTC" -> "XBT"
            "XETH", "XXRP", "XXLM", "XLTC", "XXMR", "XXDG", "XZEC", "XETC", "XMLN", "XREP" -> asset.drop(1)
            "ZEUR", "ZUSD", "ZGBP", "ZCAD", "ZJPY", "ZAUD", "ZCHF" -> asset.drop(1)
            else -> asset
        }
    }
}
