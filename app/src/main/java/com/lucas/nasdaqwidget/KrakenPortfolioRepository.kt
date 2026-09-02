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
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class KrakenPositionSnapshot(val symbol:String,val valueEur:Double,val dayChangeEur:Double,val dayChangePercent:Double)
data class KrakenPortfolioSnapshot(val totalEur:Double,val dayChangeEur:Double,val dayChangePercent:Double,val balances:Map<String,Double>,val positions:List<KrakenPositionSnapshot>,val updatedAt:Long)
private data class TickerSnapshot(val current:Double,val open:Double)
private data class CostState(var qty:Double=0.0,var costUsd:Double=0.0)

object KrakenPortfolioRepository {
    private const val PREFS="kraken_portfolio_cache"
    private const val BASE="https://api.kraken.com"
    private val cashLike=setOf("EUR","USD","GBP","CHF","CAD","AUD","JPY","USDT","USDC")

    fun refresh(context:Context,timeframe:PortfolioTimeframe=PortfolioTimeframeStore.get(context)):KrakenPortfolioSnapshot{
        val c=BrokerConnectionStore.krakenCredentials(context)?:error("Identifiants Kraken absents")
        val balances=fetchBalances(c).filterValues{kotlin.math.abs(it)>1e-8}
        val costs=fetchCostBasis(c)
        val usdToEur=eurTicker("ZUSD")?.current?:1.0
        var total=0.0
        var pnlEur=0.0
        var investedEur=0.0
        val positions=mutableListOf<KrakenPositionSnapshot>()

        balances.forEach{(raw,amount)->
            val symbol=normalizeAsset(raw)
            val eur=eurTicker(raw)?.current?:0.0
            val valueEur=amount*eur
            total+=valueEur
            if(symbol !in cashLike&&eur>0&&valueEur>0.01){
                val state=costs[symbol]
                val currentUsd=usdTicker(raw)?.current
                if(state!=null&&state.qty>1e-10&&state.costUsd>0&&currentUsd!=null&&currentUsd>0){
                    val costUsd=state.costUsd*(amount/state.qty).coerceAtLeast(0.0)
                    if(costUsd>0){
                        val valueUsd=amount*currentUsd
                        val changeUsd=valueUsd-costUsd
                        val pct=changeUsd/costUsd*100.0
                        val changeEur=changeUsd*usdToEur
                        pnlEur+=changeEur
                        investedEur+=costUsd*usdToEur
                        positions+=KrakenPositionSnapshot(displayAsset(symbol),valueEur,changeEur,pct)
                    }
                }
            }
        }
        val snapshot=KrakenPortfolioSnapshot(total,pnlEur,if(investedEur>0)pnlEur/investedEur*100 else 0.0,balances,positions,System.currentTimeMillis())
        cache(context,snapshot)
        BrokerConnectionStore.setKrakenVerified(context,true)
        return snapshot
    }

    private fun fetchCostBasis(c:BrokerConnectionStore.KrakenCredentials):Map<String,CostState>{
        val trades=mutableListOf<JSONObject>()
        var ofs=0
        var count=Int.MAX_VALUE
        while(ofs<count&&ofs<10000){
            val r=privatePost(c,"/0/private/TradesHistory",mapOf("ofs" to ofs.toString()))
            val result=r.getJSONObject("result")
            count=result.optInt("count",0)
            val obj=result.optJSONObject("trades")?:break
            obj.keys().forEach{trades+=obj.getJSONObject(it)}
            if(obj.length()==0)break
            ofs+=obj.length()
        }
        val earliest=trades.minOfOrNull{it.optDouble("time",Double.MAX_VALUE)}?.takeIf{it.isFinite()&&it>0}
        val eurUsdHistory=earliest?.let{dailyFxHistory("EURUSD",it.toLong())}.orEmpty()
        val states=mutableMapOf<String,CostState>()
        trades.sortedBy{it.optDouble("time")}.forEach{t->
            val type=t.optString("type")
            val pair=t.optString("pair")
            val asset=baseAsset(pair)
            val vol=t.optString("vol").toDoubleOrNull()?:return@forEach
            val cost=t.optString("cost").toDoubleOrNull()?:return@forEach
            val fee=t.optString("fee").toDoubleOrNull()?:0.0
            val quote=quoteAsset(pair)
            val timestamp=t.optDouble("time").toLong()
            val quoteUsd=quoteToUsdAt(quote,timestamp,eurUsdHistory)
            val s=states.getOrPut(asset){CostState()}
            if(type=="buy"){
                s.qty+=vol
                // Kraken Pro's displayed average price/cost basis is based on trade cost;
                // fees are reported separately and are therefore not folded into entry price.
                s.costUsd+=cost*quoteUsd
            }else if(type=="sell"&&s.qty>0){
                val sold=vol.coerceAtMost(s.qty)
                val avg=s.costUsd/s.qty
                s.costUsd=(s.costUsd-avg*sold).coerceAtLeast(0.0)
                s.qty=(s.qty-sold).coerceAtLeast(0.0)
                if(s.qty<1e-10){s.qty=0.0;s.costUsd=0.0}
            }
        }
        return states
    }

    private fun quoteToUsdAt(quote:String,time:Long,eurUsd:Map<Long,Double>):Double=when(quote){
        "USD","USDT","USDC"->1.0
        "EUR"->{
            val day=Instant.ofEpochSecond(time).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
            eurUsd[day]?:eurUsd.entries.minByOrNull{kotlin.math.abs(it.key-day)}?.value?:fetchTicker("EURUSD")?.current?:1.0
        }
        else->1.0
    }

    private fun dailyFxHistory(pair:String,since:Long):Map<Long,Double>=runCatching{
        val u="$BASE/0/public/OHLC?pair=${URLEncoder.encode(pair,"UTF-8")}&interval=1440&since=$since"
        val con=URL(u).openConnection() as HttpURLConnection
        con.connectTimeout=10000;con.readTimeout=10000
        val root=JSONObject(con.inputStream.bufferedReader().use{it.readText()}).getJSONObject("result")
        val key=root.keys().asSequence().firstOrNull{it!="last"}?:return@runCatching emptyMap()
        val rows=root.getJSONArray(key)
        buildMap<Long,Double>{
            for(i in 0 until rows.length()){
                val row=rows.getJSONArray(i)
                val ts=row.optLong(0)
                val close=row.optString(4).toDoubleOrNull()?:continue
                val day=Instant.ofEpochSecond(ts).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                put(day,close)
            }
        }
    }.getOrDefault(emptyMap())

    private fun baseAsset(pair:String):String{
        var p=pair.uppercase().replace("/","").replace("-","")
        val quotes=listOf("ZEUR","ZUSD","USDT","USDC","EUR","USD","GBP","BTC","XBT")
        val q=quotes.firstOrNull{p.endsWith(it)}
        if(q!=null)p=p.dropLast(q.length)
        return canonicalAsset(p)
    }
    private fun canonicalAsset(raw:String):String{
        val a=raw.substringBefore('.').uppercase()
        return when(a){
            "XXBT","XBT","BTC"->"XBT"
            "XETH","ETH"->"ETH"
            "ZEUR","EUR"->"EUR"
            "ZUSD","USD"->"USD"
            else->if((a.startsWith("X")||a.startsWith("Z"))&&a.length>=4)a.drop(1) else a
        }
    }
    private fun displayAsset(symbol:String)=if(symbol=="XBT")"BTC" else symbol
    private fun quoteAsset(pair:String):String{
        val p=pair.uppercase().replace("/","").replace("-","")
        return when{
            p.endsWith("USDT")->"USDT"
            p.endsWith("USDC")->"USDC"
            p.endsWith("ZEUR")||p.endsWith("EUR")->"EUR"
            p.endsWith("ZUSD")||p.endsWith("USD")->"USD"
            p.endsWith("GBP")->"GBP"
            else->"EUR"
        }
    }

    fun cached(context:Context):KrakenPortfolioSnapshot?{
        val p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        val raw=p.getString("balances",null)?:return null
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
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putLong("total",s.totalEur.toBits()).putLong("dayChange",s.dayChangeEur.toBits()).putLong("dayPercent",s.dayChangePercent.toBits()).putString("balances",b.toString()).putString("positions",a.toString()).putLong("updated",s.updatedAt).apply()
    }
    private fun fetchBalances(c:BrokerConnectionStore.KrakenCredentials):Map<String,Double>{
        val r=privatePost(c,"/0/private/Balance",emptyMap());val x=r.getJSONObject("result");val out=linkedMapOf<String,Double>()
        x.keys().forEach{val v=x.optString(it).toDoubleOrNull()?:0.0;if(v!=0.0)out[it]=v}
        return out
    }
    private fun privatePost(c:BrokerConnectionStore.KrakenCredentials,path:String,params:Map<String,String>):JSONObject{
        val nonce=System.currentTimeMillis().toString()
        val data=(listOf("nonce="+URLEncoder.encode(nonce,"UTF-8"))+params.map{URLEncoder.encode(it.key,"UTF-8")+"="+URLEncoder.encode(it.value,"UTF-8")}).joinToString("&")
        val con=(URL(BASE+path).openConnection() as HttpURLConnection).apply{requestMethod="POST";connectTimeout=12000;readTimeout=12000;doOutput=true;setRequestProperty("API-Key",c.apiKey);setRequestProperty("API-Sign",sign(path,nonce,data,c.apiSecret));setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");setRequestProperty("User-Agent","MarketWidgets/2.1")}
        return try{con.outputStream.use{it.write(data.toByteArray(StandardCharsets.UTF_8))};val body=(if(con.responseCode in 200..299)con.inputStream else con.errorStream).bufferedReader().use{it.readText()};val j=JSONObject(body);val e=j.optJSONArray("error");if(e!=null&&e.length()>0)error(e.optString(0,"Erreur Kraken"));j}finally{con.disconnect()}
    }
    private fun sign(path:String,nonce:String,data:String,secret:String):String{
        val hash=MessageDigest.getInstance("SHA-256").digest((nonce+data).toByteArray());val mac=Mac.getInstance("HmacSHA512");mac.init(SecretKeySpec(Base64.decode(secret,Base64.DEFAULT),"HmacSHA512"));return Base64.encodeToString(mac.doFinal(path.toByteArray()+hash),Base64.NO_WRAP)
    }
    private fun eurTicker(raw:String):TickerSnapshot?{
        val a=normalizeAsset(raw);if(a=="EUR")return TickerSnapshot(1.0,1.0)
        val pairs=when(a){"XBT"->listOf("XBTEUR" to false,"XXBTZEUR" to false);"USD"->listOf("EURUSD" to true);"USDT"->listOf("USDTEUR" to false);"USDC"->listOf("USDCEUR" to false);else->listOf("${a}EUR" to false)}
        for((pair,inv)in pairs){val t=fetchTicker(pair)?:continue;if(t.current>0)return if(inv)TickerSnapshot(1/t.current,1/t.open)else t}
        return null
    }
    private fun usdTicker(raw:String):TickerSnapshot?{
        val a=normalizeAsset(raw);if(a=="USD"||a=="USDT"||a=="USDC")return TickerSnapshot(1.0,1.0)
        val pairs=when(a){"XBT"->listOf("XBTUSD","XXBTZUSD");else->listOf("${a}USD")}
        for(pair in pairs){val t=fetchTicker(pair);if(t!=null&&t.current>0)return t}
        return null
    }
    private fun fetchTicker(pair:String):TickerSnapshot?=runCatching{
        val c=URL("$BASE/0/public/Ticker?pair=${URLEncoder.encode(pair,"UTF-8")}").openConnection() as HttpURLConnection;c.connectTimeout=8000;c.readTimeout=8000
        val r=JSONObject(c.inputStream.bufferedReader().use{it.readText()}).getJSONObject("result");val t=r.getJSONObject(r.keys().next());val cur=t.getJSONArray("c").getString(0).toDouble();TickerSnapshot(cur,t.optString("o").toDoubleOrNull()?:cur)
    }.getOrNull()
    private fun normalizeAsset(raw:String):String=canonicalAsset(raw)
}
