package com.lucas.nasdaqwidget

import android.content.Context
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class IbkrDividendSnapshot(val symbol:String,val description:String,val amount:Double,val currency:String,val dateLabel:String,val isUpcoming:Boolean,val updatedAt:Long)
private data class DividendRow(val symbol:String,val description:String,val amount:Double,val currency:String,val date:LocalDate,val upcoming:Boolean)
object IbkrDividendRepository{
 private const val PREFS="ibkr_dividend_cache";private const val BASE="https://ndcdyn.interactivebrokers.com/AccountManagement/FlexWebService"
 fun refresh(context:Context):IbkrDividendSnapshot?{val c=BrokerConnectionStore.ibkrFlexCredentials(context)?:error("Token Flex ou Query ID absent");val rows=parse(fetchStatement(c));val today=LocalDate.now();val next=rows.filter{it.upcoming&&!it.date.isBefore(today)}.minByOrNull{it.date}?:rows.filter{!it.upcoming}.maxByOrNull{it.date}?:rows.maxByOrNull{it.date};val s=next?.let{IbkrDividendSnapshot(it.symbol.ifBlank{"IBKR"},it.description.ifBlank{if(it.upcoming)"Dividende attendu" else "Dernier dividende reçu"},it.amount,it.currency.ifBlank{"USD"},it.date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),it.upcoming,System.currentTimeMillis())};cache(context,s);return s}
 fun cached(context:Context):IbkrDividendSnapshot?{val p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);if(!p.contains("updated"))return null;return IbkrDividendSnapshot(p.getString("symbol","IBKR")?:"IBKR",p.getString("description","")?:"",Double.fromBits(p.getLong("amount",0)),p.getString("currency","USD")?:"USD",p.getString("date","")?:"",p.getBoolean("upcoming",false),p.getLong("updated",0))}
 fun clear(context:Context){context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().clear().apply()}
 private fun cache(context:Context,s:IbkrDividendSnapshot?){val e=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().clear();if(s!=null)e.putString("symbol",s.symbol).putString("description",s.description).putLong("amount",s.amount.toBits()).putString("currency",s.currency).putString("date",s.dateLabel).putBoolean("upcoming",s.isUpcoming).putLong("updated",s.updatedAt);e.apply()}
 private fun parse(xml:String):List<DividendRow>{val out=mutableListOf<DividendRow>();val x=XmlPullParserFactory.newInstance().newPullParser().apply{setInput(StringReader(xml))};var e=x.eventType;while(e!=XmlPullParser.END_DOCUMENT){if(e==XmlPullParser.START_TAG){val a=(0 until x.attributeCount).associate{x.getAttributeName(it) to x.getAttributeValue(it)};when(x.name){"OpenDividendAccrual"->row(a,true)?.let(out::add);"CashTransaction"->{val hay=(a["type"].orEmpty()+" "+a["description"].orEmpty()).lowercase();if("dividend" in hay||"dividende" in hay)row(a,false)?.let(out::add)}}};e=x.next()};return out}
 private fun row(a:Map<String,String>,up:Boolean):DividendRow?{val symbol=a["symbol"].orEmpty();val desc=a["description"]?:a["companyName"]?:"";val amount=listOf("grossAmount","amount","accruedAmount","netAmount").firstNotNullOfOrNull{a[it]?.replace(",","")?.toDoubleOrNull()}?:0.0;val cur=a["currency"]?:"USD";val raw=listOf("payDate","exDate","dateTime","reportDate","settleDate","date").firstNotNullOfOrNull{a[it]}?:return null;val d=parseDate(raw)?:return null;if(symbol.isBlank()&&desc.isBlank())return null;return DividendRow(symbol,desc,amount,cur,d,up)}
 private fun parseDate(raw:String):LocalDate?{val c=raw.trim().substringBefore(';').substringBefore(' ');for(p in listOf("yyyyMMdd","yyyy-MM-dd","MM/dd/yyyy","dd/MM/yyyy")){val d=runCatching{LocalDate.parse(c,DateTimeFormatter.ofPattern(p))}.getOrNull();if(d!=null)return d};return null}
 private fun fetchStatement(c:BrokerConnectionStore.IbkrFlexCredentials):String{val t=URLEncoder.encode(c.token,"UTF-8");val q=URLEncoder.encode(c.queryId,"UTF-8");val send=get("$BASE/SendRequest?t=$t&q=$q&v=3");val ref=tag(send,"ReferenceCode")?:error(tag(send,"ErrorMessage")?:"IBKR Flex: ReferenceCode absent");var last="Rapport IBKR en préparation";repeat(6){if(it>0)Thread.sleep(1000);val b=get("$BASE/GetStatement?t=$t&q=${URLEncoder.encode(ref,"UTF-8")}&v=3");if(b.contains("<FlexQueryResponse"))return b;last=tag(b,"ErrorMessage")?:last};error(last)}
 private fun get(url:String):String{val c=(URL(url).openConnection() as HttpURLConnection).apply{connectTimeout=12000;readTimeout=20000;setRequestProperty("User-Agent","MarketWidgets/1.6 Android")};return try{val s=if(c.responseCode in 200..299)c.inputStream else c.errorStream;val b=s.bufferedReader().use{it.readText()};if(c.responseCode !in 200..299)error("IBKR HTTP ${c.responseCode}");b}finally{c.disconnect()}}
 private fun tag(xml:String,t:String)=Regex("<$t>(.*?)</$t>",RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.getOrNull(1)?.trim()
}
