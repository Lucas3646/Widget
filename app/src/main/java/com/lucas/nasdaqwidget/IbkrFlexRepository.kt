package com.lucas.nasdaqwidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class IbkrPositionSnapshot(val symbol:String,val valueEur:Double,val periodChangeEur:Double,val periodChangePercent:Double)
data class IbkrPortfolioSnapshot(val totalEur:Double,val periodChangeEur:Double,val periodChangePercent:Double,val positions:List<IbkrPositionSnapshot>,val updatedAt:Long)
private data class FlexPosition(val symbol:String,val quantity:Double,val markPrice:Double,val positionValue:Double,val currency:String)
private data class FlexCash(val amount:Double,val currency:String)
private data class YahooQuote(val current:Double,val reference:Double)

object IbkrFlexRepository {
 private const val PREFS="ibkr_flex_cache"; private const val BASE="https://ndcdyn.interactivebrokers.com/AccountManagement/FlexWebService"
 fun refresh(context:Context,timeframe:PortfolioTimeframe=PortfolioTimeframeStore.get(context)):IbkrPortfolioSnapshot{
  val c=BrokerConnectionStore.ibkrFlexCredentials(context)?:error("Token Flex ou Query ID absent")
  val parsed=parseStatement(fetchStatement(c)); if(parsed.first.isEmpty()) error("Aucune position dans la Flex Query")
  var positionsTotal=0.0; var change=0.0; val live=mutableListOf<IbkrPositionSnapshot>()
  parsed.first.forEach { p ->
   val q=yahooQuote(p.symbol,p.currency,timeframe); val fx=fxToEur(p.currency); val current=q?.current?:p.markPrice; val reference=q?.reference?:current
   // OpenPosition.positionValue is the authoritative Flex value. Revalue it only when a usable live quote exists.
   val baseValue=if(p.positionValue!=0.0) p.positionValue*fx else p.quantity*p.markPrice*fx
   val currentValue=if(q!=null && p.markPrice>0) baseValue*(current/p.markPrice) else baseValue
   positionsTotal+=currentValue
   val d=if(reference!=0.0) currentValue*(current/reference-1.0) else 0.0; change+=d
   val pct=if(reference!=0.0)(current/reference-1.0)*100 else 0.0
   if(kotlin.math.abs(currentValue)>0.01) live+=IbkrPositionSnapshot(p.symbol,currentValue,d,pct)
  }
  // CashReport endingCash is cash only, not NAV. This was previously mistaken for total portfolio value.
  val cash=parsed.second.sumOf{it.amount*fxToEur(it.currency)}
  val total=positionsTotal+cash
  val previous=total-change
  val s=IbkrPortfolioSnapshot(total,change,if(previous!=0.0)change/previous*100 else 0.0,live,System.currentTimeMillis());cache(context,s);BrokerConnectionStore.setIbkrVerified(context,true);return s
 }
 fun cached(context:Context):IbkrPortfolioSnapshot?{val p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);if(!p.contains("total"))return null;val a=mutableListOf<IbkrPositionSnapshot>();p.getString("positions",null)?.let{runCatching{JSONArray(it)}.getOrNull()?.let{x->for(i in 0 until x.length()){val o=x.optJSONObject(i)?:continue;a+=IbkrPositionSnapshot(o.optString("symbol"),o.optDouble("value"),o.optDouble("change"),o.optDouble("percent"))}}};return IbkrPortfolioSnapshot(Double.fromBits(p.getLong("total",0)),Double.fromBits(p.getLong("change",0)),Double.fromBits(p.getLong("percent",0)),a,p.getLong("updated",0))}
 fun clear(context:Context){context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().clear().apply()}
 private fun cache(context:Context,s:IbkrPortfolioSnapshot){val a=JSONArray();s.positions.forEach{a.put(JSONObject().put("symbol",it.symbol).put("value",it.valueEur).put("change",it.periodChangeEur).put("percent",it.periodChangePercent))};context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putLong("total",s.totalEur.toBits()).putLong("change",s.periodChangeEur.toBits()).putLong("percent",s.periodChangePercent.toBits()).putString("positions",a.toString()).putLong("updated",s.updatedAt).apply()}
 private fun parseStatement(xml:String):Pair<List<FlexPosition>,List<FlexCash>>{val pos=mutableListOf<FlexPosition>();val cash=mutableListOf<FlexCash>();val x=XmlPullParserFactory.newInstance().newPullParser().apply{setInput(StringReader(xml))};var e=x.eventType;while(e!=XmlPullParser.END_DOCUMENT){if(e==XmlPullParser.START_TAG)when(x.name){"OpenPosition"->{val s=x.getAttributeValue(null,"symbol").orEmpty();val q=x.getAttributeValue(null,"position")?.toDoubleOrNull()?:0.0;val m=x.getAttributeValue(null,"markPrice")?.toDoubleOrNull()?:0.0;val v=x.getAttributeValue(null,"positionValue")?.toDoubleOrNull()?:0.0;val c=x.getAttributeValue(null,"currency").orEmpty();if(s.isNotBlank()&&q!=0.0)pos+=FlexPosition(s,q,m,v,c)};"CashReportCurrency"->{val c=x.getAttributeValue(null,"currency").orEmpty();if(c!="BASE_SUMMARY"){val v=x.getAttributeValue(null,"endingCash")?.toDoubleOrNull()?:0.0;if(v!=0.0)cash+=FlexCash(v,c)}}};e=x.next()};return pos to cash}
 private fun fetchStatement(c:BrokerConnectionStore.IbkrFlexCredentials):String{val t=URLEncoder.encode(c.token,"UTF-8");val q=URLEncoder.encode(c.queryId,"UTF-8");val send=get("$BASE/SendRequest?t=$t&q=$q&v=3");val ref=tagText(send,"ReferenceCode")?:error(tagText(send,"ErrorMessage")?:"IBKR Flex: ReferenceCode absent");var last="Rapport IBKR en préparation";repeat(6){if(it>0)Thread.sleep(1000);val b=get("$BASE/GetStatement?t=$t&q=${URLEncoder.encode(ref,"UTF-8")}&v=3");if(b.contains("<FlexQueryResponse"))return b;last=tagText(b,"ErrorMessage")?:last};error(last)}
 private fun get(url:String):String{val c=(URL(url).openConnection() as HttpURLConnection).apply{connectTimeout=12000;readTimeout=20000;setRequestProperty("User-Agent","MarketWidgets/1.6 Android")};return try{val s=if(c.responseCode in 200..299)c.inputStream else c.errorStream;val b=s.bufferedReader().use{it.readText()};if(c.responseCode !in 200..299)error("IBKR HTTP ${c.responseCode}");b}finally{c.disconnect()}}
 private fun yahooQuote(symbol:String,currency:String,timeframe:PortfolioTimeframe):YahooQuote?{val candidates=when(currency.uppercase()){ "USD"->listOf(symbol);"GBP"->listOf("$symbol.L",symbol);"EUR"->listOf("$symbol.PA","$symbol.AS","$symbol.BR","$symbol.DE","$symbol.MI",symbol);else->listOf(symbol)};val range=when(timeframe){PortfolioTimeframe.SESSION->"1d";PortfolioTimeframe.MONTH->"1mo";PortfolioTimeframe.THREE_MONTHS->"3mo";PortfolioTimeframe.YTD->"ytd";PortfolioTimeframe.YEAR->"1y"};val interval=if(timeframe==PortfolioTimeframe.SESSION)"5m" else "1d";for(candidate in candidates){val r=runCatching{val enc=URLEncoder.encode(candidate,"UTF-8");val root=JSONObject(getYahoo("https://query1.finance.yahoo.com/v8/finance/chart/$enc?range=$range&interval=$interval&includePrePost=true"));val z=root.getJSONObject("chart").optJSONArray("result")?.optJSONObject(0)?:return@runCatching null;val meta=z.getJSONObject("meta");val cur=meta.optDouble("regularMarketPrice",Double.NaN);if(!cur.isFinite())return@runCatching null;val ref=if(timeframe==PortfolioTimeframe.SESSION)meta.optDouble("chartPreviousClose",meta.optDouble("previousClose",cur))else{val closes=z.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0).getJSONArray("close");var f=cur;for(i in 0 until closes.length())if(!closes.isNull(i)){f=closes.getDouble(i);break};f};YahooQuote(cur,ref)}.getOrNull();if(r!=null)return r};return null}
 private fun getYahoo(url:String):String{val c=(URL(url).openConnection() as HttpURLConnection).apply{connectTimeout=8000;readTimeout=8000;setRequestProperty("User-Agent","Mozilla/5.0 MarketWidgets")};return try{if(c.responseCode !in 200..299)error("quote");c.inputStream.bufferedReader().use{it.readText()}}finally{c.disconnect()}}
 private fun fxToEur(currency:String):Double{val c=currency.uppercase();if(c.isBlank()||c=="EUR"||c=="BASE_SUMMARY")return 1.0;return runCatching{val root=JSONObject(getYahoo("https://query1.finance.yahoo.com/v8/finance/chart/${URLEncoder.encode("EUR${c}=X","UTF-8")}?range=1d&interval=5m"));val rate=root.getJSONObject("chart").getJSONArray("result").getJSONObject(0).getJSONObject("meta").getDouble("regularMarketPrice");if(rate>0)1.0/rate else 1.0}.getOrDefault(1.0)}
 private fun tagText(xml:String,tag:String)=Regex("<$tag>(.*?)</$tag>",RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.getOrNull(1)?.trim()
}
