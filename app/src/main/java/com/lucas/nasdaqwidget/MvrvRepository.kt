package com.lucas.nasdaqwidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class MvrvSnapshot(val zScore:Double,val estimatedHighZonePrice:Double?,val sourcePrice:Double?,val updatedAtMillis:Long)

object MvrvRepository {
    private const val PREFS="btc_mvrv_snapshot"
    private const val HIGH_ZONE_Z=7.0
    private val BG_BASES=listOf(
        "https://bitcoin-data.com/v1",
        "https://bitcoin-data.com/api/v1",
        "https://api.bgeometrics.com/v1"
    )
    private const val CM="https://community-api.coinmetrics.io/v4/timeseries/asset-metrics"

    fun refresh(context:Context):MvrvSnapshot {
        clearLastError(context)
        val errors=mutableListOf<String>()
        var z:Double?=null
        var price:Double?=null

        for(base in BG_BASES){
            if(z!=null) break
            runCatching{
                val body=get("$base/mvrv-zscore/last")
                extractMetric(body,setOf("mvrv-zscore","mvrv-z-score","mvrv_zscore","mvrv_z_score","mvrvzscore","zscore","z_score","value"))
                    ?: error("valeur absente")
            }.onSuccess{z=it}.onFailure{errors += "${host(base)}: ${shortError(it)}"}
        }

        if(z==null){
            runCatching{coinMetricsLatest("CapMVRVZ")}
                .onSuccess{z=it}
                .onFailure{errors += "CoinMetrics: ${shortError(it)}"}
        }

        if(z==null) throw IllegalStateException(errors.joinToString(" | ").take(240).ifBlank{"MVRV indisponible"})

        for(base in BG_BASES){
            if(price!=null) break
            price=runCatching{extractMetric(get("$base/btc-price/last"),setOf("price","btcprice","btc_price","close","value"))}.getOrNull()
        }
        if(price==null) price=runCatching{coinMetricsLatest("PriceUSD")}.getOrNull()

        return MvrvSnapshot(z!!,null,price,System.currentTimeMillis()).also{save(context,it)}
    }

    fun recordError(context:Context,t:Throwable){context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString("lastError",t.message?:t.javaClass.simpleName).putLong("lastErrorAt",System.currentTimeMillis()).apply()}
    fun lastError(context:Context):String?=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString("lastError",null)
    private fun clearLastError(context:Context){context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().remove("lastError").remove("lastErrorAt").apply()}

    private fun coinMetricsLatest(metric:String):Double{
        val body=get("$CM?assets=btc&metrics=$metric&frequency=1d&limit_per_asset=1&paging_from=end&ignore_forbidden_errors=true&ignore_unsupported_errors=true")
        val data=JSONObject(body).optJSONArray("data")?:error("réponse invalide")
        if(data.length()==0) error("aucune donnée $metric")
        val item=data.optJSONObject(data.length()-1)?:error("donnée invalide")
        return item.optString(metric).toDoubleOrNull()?:error("$metric absent")
    }

    private fun get(endpoint:String):String {
        val c=(URL(endpoint).openConnection() as HttpURLConnection).apply{requestMethod="GET";connectTimeout=12000;readTimeout=20000;setRequestProperty("Accept","application/json");setRequestProperty("User-Agent","MarketWidgets/2.2 Android")}
        return try{val code=c.responseCode;val body=(if(code in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty();if(code !in 200..299)throw IllegalStateException("HTTP $code${if(body.isNotBlank())": ${body.take(80)}" else ""}");body}finally{c.disconnect()}
    }

    private fun host(base:String)=runCatching{URL(base).host}.getOrDefault(base)
    private fun shortError(t:Throwable):String=(t.message?:t.javaClass.simpleName).replace("Unable to resolve host ","DNS ").take(70)

    private fun extractMetric(body:String,preferred:Set<String>):Double? {
        val trimmed=body.trim()
        trimmed.toDoubleOrNull()?.let{return it}
        val root:Any=runCatching{JSONObject(trimmed)}.getOrElse{runCatching{JSONArray(trimmed)}.getOrNull()?:return null}
        return findMetric(root,preferred.map{normalize(it)}.toSet())
    }

    private fun normalize(s:String)=s.lowercase().filter{it.isLetterOrDigit()}
    private fun asDouble(v:Any?):Double?=when(v){is Number->v.toDouble();is String->v.replace(",","").trim().toDoubleOrNull();else->null}
    private fun findMetric(node:Any?,preferred:Set<String>):Double?=when(node){
        is JSONObject->{val keys=node.keys().asSequence().toList();keys.firstNotNullOfOrNull{k->if(normalize(k) in preferred)asDouble(node.opt(k))else null}?:keys.firstNotNullOfOrNull{k->findMetric(node.opt(k),preferred)}}
        is JSONArray->{var found:Double?=null;for(i in 0 until node.length()){found=findMetric(node.opt(i),preferred);if(found!=null)break};found}
        else->asDouble(node)
    }

    fun cached(context:Context):MvrvSnapshot?{val p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);if(!p.contains("zScore"))return null;val zb=p.getLong("estimatedHighZonePrice",Long.MIN_VALUE);val pb=p.getLong("sourcePrice",Long.MIN_VALUE);return MvrvSnapshot(Double.fromBits(p.getLong("zScore",0)),if(zb==Long.MIN_VALUE)null else Double.fromBits(zb),if(pb==Long.MIN_VALUE)null else Double.fromBits(pb),p.getLong("updatedAt",0))}
    fun zoneLabel(z:Double)=when{z<0->"Sous-évalué";z<2->"Basse";z<5->"Neutre";z<HIGH_ZONE_Z->"Chaude";else->"Haute"}
    fun highZoneZ()=HIGH_ZONE_Z
    private fun save(context:Context,s:MvrvSnapshot){context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putLong("zScore",s.zScore.toBits()).apply{s.estimatedHighZonePrice?.let{putLong("estimatedHighZonePrice",it.toBits())}?:remove("estimatedHighZonePrice");s.sourcePrice?.let{putLong("sourcePrice",it.toBits())}?:remove("sourcePrice")}.putLong("updatedAt",s.updatedAtMillis).remove("lastError").remove("lastErrorAt").apply()}
}
