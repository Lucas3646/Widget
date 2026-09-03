package com.lucas.nasdaqwidget

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Reads IBKR's own Time Weighted Return from ChangeInNAV. TWR excludes deposits/withdrawals. */
object IbkrTwrRepository {
    private const val PREFS="ibkr_twr_cache"
    private const val BASE="https://ndcdyn.interactivebrokers.com/AccountManagement/FlexWebService"

    fun refresh(context:Context,timeframe:PortfolioTimeframe):Double?{
        val c=BrokerConnectionStore.ibkrFlexCredentials(context)?:return null
        val token=URLEncoder.encode(c.token,"UTF-8")
        val query=URLEncoder.encode(c.queryId,"UTF-8")
        val today=LocalDate.now()
        val start=when(timeframe){
            PortfolioTimeframe.SESSION->today.minusDays(7)
            PortfolioTimeframe.MONTH->today.minusMonths(1)
            PortfolioTimeframe.THREE_MONTHS->today.minusMonths(3)
            PortfolioTimeframe.YTD->today.withDayOfYear(1)
            PortfolioTimeframe.YEAR->today.minusYears(1)
        }
        val fmt=DateTimeFormatter.BASIC_ISO_DATE
        val send=get("$BASE/SendRequest?t=$token&q=$query&fd=${start.format(fmt)}&td=${today.format(fmt)}&v=3")
        val ref=tag(send,"ReferenceCode")?:return null
        var body:String?=null
        for(attempt in 0 until 6){
            if(attempt>0)Thread.sleep(1000)
            val candidate=get("$BASE/GetStatement?t=$token&q=${URLEncoder.encode(ref,"UTF-8")}&v=3")
            if(candidate.contains("<FlexQueryResponse")){body=candidate;break}
        }
        val xml=body?:return null
        val element=Regex("<ChangeInNAV\\b[^>]*?/?>").find(xml)?.value?:return null
        val raw=Regex("\\btwr=\"([^\"]+)\"").find(element)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?:return null
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putLong("value",raw.toBits()).putString("period",timeframe.name).apply()
        return raw
    }

    fun cached(context:Context,timeframe:PortfolioTimeframe):Double?{
        val p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        if(!p.contains("value")||p.getString("period",null)!=timeframe.name)return null
        return Double.fromBits(p.getLong("value",0))
    }

    private fun get(url:String):String{
        val c=(URL(url).openConnection() as HttpURLConnection).apply{connectTimeout=12000;readTimeout=20000;setRequestProperty("User-Agent","MarketWidgets/1.9 Android")}
        return try{val s=if(c.responseCode in 200..299)c.inputStream else c.errorStream;val b=s.bufferedReader().use{it.readText()};if(c.responseCode !in 200..299)error("IBKR HTTP ${c.responseCode}");b}finally{c.disconnect()}
    }
    private fun tag(xml:String,name:String)=Regex("<$name>(.*?)</$name>",RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.getOrNull(1)?.trim()
}
