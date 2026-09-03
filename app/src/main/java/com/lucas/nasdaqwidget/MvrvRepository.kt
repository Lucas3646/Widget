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
    private const val BASE="https://bitcoin-data.com/v1"

    fun refresh(context:Context):MvrvSnapshot {
        clearLastError(context)
        val z = fetchNumber(listOf("$BASE/mvrv-zscore/last","$BASE/mvrv-zscore"), setOf("mvrv-zscore","mvrv_zscore","mvrvzscore","zscore","value"))
            ?: throw IllegalStateException("MVRV Z indisponible")
        val price = fetchNumber(listOf("$BASE/btc-price/last","$BASE/btc-price"), setOf("price","btcprice","btc_price","close","value"))
        return MvrvSnapshot(z,null,price,System.currentTimeMillis()).also{save(context,it)}
    }

    private fun fetchNumber(urls:List<String>,keys:Set<String>):Double? {
        for(endpoint in urls){
            val value=runCatching{findNumber(parse(getJson(endpoint)),keys)}.getOrNull()
            if(value!=null&&value.isFinite())return value
        }
        return null
    }
    private fun parse(body:String):Any?=runCatching{JSONObject(body)}.getOrElse{runCatching{JSONArray(body)}.getOrNull()}
    private fun findNumber(node:Any?,preferred:Set<String>):Double?=when(node){
        is JSONObject->{
            val keys=node.keys().asSequence().toList()
            for(k in keys){if(k.lowercase().replace("-","").replace("_","") in preferred.map{it.replace("-","").replace("_","")}){val r=node.opt(k);val n=when(r){is Number->r.toDouble();is String->r.replace(",","").toDoubleOrNull();else->null};if(n!=null)return n}}
            for(k in keys){val n=findNumber(node.opt(k),preferred);if(n!=null)return n};null
        }
        is JSONArray->{for(i in node.length()-1 downTo 0){val n=findNumber(node.opt(i),preferred);if(n!=null)return n};null}
        else->null
    }
    private fun getJson(endpoint:String):String{
        val c=(URL(endpoint).openConnection() as HttpURLConnection).apply{requestMethod="GET";connectTimeout=15000;readTimeout=25000;setRequestProperty("Accept","application/json");setRequestProperty("User-Agent","Mozilla/5.0 MarketWidgets/2.0")}
        return try{val code=c.responseCode;val body=(if(code in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty();if(code !in 200..299)throw IllegalStateException("HTTP $code");body}finally{c.disconnect()}
    }
    fun recordError(context:Context,t:Throwable){context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString("lastError",t.message?:t.javaClass.simpleName).apply()}
    fun lastError(context:Context)=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString("lastError",null)
    private fun clearLastError(context:Context){context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().remove("lastError").apply()}
    fun cached(context:Context):MvrvSnapshot?{val p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);if(!p.contains("zScore"))return null;val pb=p.getLong("sourcePrice",Long.MIN_VALUE);return MvrvSnapshot(Double.fromBits(p.getLong("zScore",0)),null,if(pb==Long.MIN_VALUE)null else Double.fromBits(pb),p.getLong("updatedAt",0))}
    fun zoneLabel(z:Double)=when{z<0->"Sous-évalué";z<2->"Basse";z<5->"Neutre";z<HIGH_ZONE_Z->"Chaude";else->"Haute"}
    fun highZoneZ()=HIGH_ZONE_Z
    private fun save(context:Context,s:MvrvSnapshot){val e=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putLong("zScore",s.zScore.toBits()).putLong("updatedAt",s.updatedAtMillis).remove("lastError");if(s.sourcePrice!=null)e.putLong("sourcePrice",s.sourcePrice.toBits()) else e.remove("sourcePrice");e.apply()}
}
