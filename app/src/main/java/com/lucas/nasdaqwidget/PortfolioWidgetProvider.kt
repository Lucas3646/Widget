package com.lucas.nasdaqwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.widget.RemoteViews
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

private data class PortfolioDisplayPosition(val symbol:String,val valueEur:Double,val changePercent:Double)

class PortfolioWidgetProvider:AppWidgetProvider(){
 override fun onEnabled(context:Context){super.onEnabled(context);WorkManager.getInstance(context).enqueueUniquePeriodicWork("portfolio_live_refresh",ExistingPeriodicWorkPolicy.UPDATE,PeriodicWorkRequestBuilder<PortfolioRefreshWorker>(15,TimeUnit.MINUTES).build())}
 override fun onDisabled(context:Context){WorkManager.getInstance(context).cancelUniqueWork("portfolio_live_refresh");super.onDisabled(context)}
 override fun onUpdate(context:Context,m:AppWidgetManager,ids:IntArray){updateAll(context,m,ids);refreshAndUpdate(context)}
 companion object{
  private const val GREEN="#38F27A";private const val RED="#FF6B6B"
  fun refreshAndUpdate(context:Context){Thread{val tf=PortfolioTimeframeStore.get(context);if(BrokerConnectionStore.isKrakenVerified(context))runCatching{KrakenPortfolioRepository.refresh(context,tf)};if(BrokerConnectionStore.hasIbkrSetup(context))runCatching{IbkrFlexRepository.refresh(context,tf)};updateAll(context)}.start()}
  fun updateAll(context:Context){val m=AppWidgetManager.getInstance(context);updateAll(context,m,m.getAppWidgetIds(android.content.ComponentName(context,PortfolioWidgetProvider::class.java)))}
  private fun updateAll(context:Context,manager:AppWidgetManager,ids:IntArray){
   val kraken=KrakenPortfolioRepository.cached(context);val ibkr=IbkrFlexRepository.cached(context);val tf=PortfolioTimeframeStore.get(context)
   val eur=NumberFormat.getCurrencyInstance(Locale.FRANCE);val eur0=NumberFormat.getCurrencyInstance(Locale.FRANCE).apply{maximumFractionDigits=0}
   val total=(kraken?.totalEur?:0.0)+(ibkr?.totalEur?:0.0)
   val positions=buildList{ibkr?.positions?.forEach{add(PortfolioDisplayPosition(it.symbol,it.valueEur,it.periodChangePercent))};kraken?.positions?.forEach{add(PortfolioDisplayPosition(it.symbol,it.valueEur,it.dayChangePercent))}}
   // Broker-level returns are cash-flow adjusted when supplied by IBKR (TWR). Kraken uses cost-basis P/L.
   val components=mutableListOf<Triple<Double,Double,Double>>() // current, change, percent
   ibkr?.let{components+=Triple(it.totalEur,it.periodChangeEur,it.periodChangePercent)}
   kraken?.let{if(it.positions.isNotEmpty())components+=Triple(it.totalEur,it.dayChangeEur,it.dayChangePercent)}
   var change=0.0;var base=0.0
   components.forEach{(current,ch,pct)->val b=if(kotlin.math.abs(pct)>0.000001)current/(1.0+pct/100.0) else current;change+=if(ch.isFinite())ch else current-b;base+=b}
   val percent=if(base>0)change/base*100 else 0.0
   val top=positions.filter{it.changePercent>0.0001}.sortedByDescending{it.changePercent}.take(3);val flop=positions.filter{it.changePercent< -0.0001}.sortedBy{it.changePercent}.take(3)
   val has=kraken!=null||ibkr!=null
   // Restore the historical IBKR series. The repository requests the selected Flex period; performance itself uses IBKR TWR so deposits are excluded from the headline return.
   val chart=when{ibkr?.chartValues?.size?:0>1->ibkr!!.chartValues.map{it+(kraken?.totalEur?:0.0)};has->listOf(total,total);else->emptyList()}
   ids.forEach{id->val v=RemoteViews(context.packageName,R.layout.widget_portfolio);v.setTextViewText(R.id.portfolioTotal,if(has)eur.format(total)else"— €")
    if(has){v.setTextViewText(R.id.portfolioDayChange,signedMoney(change,eur));v.setTextViewText(R.id.portfolioDayPercent,String.format(Locale.FRANCE,"%+.2f %% · %s",percent,tf.label));val c=Color.parseColor(if(percent>=0)GREEN else RED);v.setTextColor(R.id.portfolioDayChange,c);v.setTextColor(R.id.portfolioDayPercent,c);v.setImageViewBitmap(R.id.portfolioChart,sparkline(chart,percent>=0));fillRanking(v,listOf(R.id.portfolioTop1,R.id.portfolioTop2,R.id.portfolioTop3),top,eur0);fillRanking(v,listOf(R.id.portfolioFlop1,R.id.portfolioFlop2,R.id.portfolioFlop3),flop,eur0)}else{v.setTextViewText(R.id.portfolioDayChange,"— €");v.setTextViewText(R.id.portfolioDayPercent,"— % · ${tf.label}");v.setImageViewBitmap(R.id.portfolioChart,sparkline(emptyList(),true))}
    v.setTextViewText(R.id.portfolioKraken,when{kraken!=null->"Kraken · ${eur.format(kraken.totalEur)}";BrokerConnectionStore.hasKraken(context)->"Kraken · connexion à vérifier";else->"Kraken · toucher pour connecter"});v.setTextViewText(R.id.portfolioIbkr,when{ibkr!=null->"IBKR · ${eur.format(ibkr.totalEur)}";BrokerConnectionStore.hasIbkrSetup(context)->"IBKR · connexion à vérifier";else->"IBKR · toucher pour connecter"})
    val ti=PendingIntent.getActivity(context,id+20000,Intent(context,PortfolioTimeframeActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE);listOf(R.id.portfolioTotal,R.id.portfolioDayChange,R.id.portfolioDayPercent,R.id.portfolioChart).forEach{v.setOnClickPendingIntent(it,ti)};val bi=PendingIntent.getActivity(context,id,Intent(context,BrokerConnectionsActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE);v.setOnClickPendingIntent(R.id.portfolioIbkr,bi);v.setOnClickPendingIntent(R.id.portfolioKraken,bi);manager.updateAppWidget(id,v)}
  }
  private fun sparkline(values:List<Double>,positive:Boolean):Bitmap{val w=560;val h=86;val b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);if(values.size<2)return b;val mn=values.minOrNull()?:return b;val mx=values.maxOrNull()?:return b;val range=(mx-mn).takeIf{it>0.0001}?:1.0;val pad=7f;val pts=values.mapIndexed{i,x->pad+(w-pad*2)*i.toFloat()/(values.size-1) to pad+(h-pad*2)*(1f-((x-mn)/range).toFloat())};val line=Path().apply{moveTo(pts[0].first,pts[0].second);for(i in 1 until pts.lastIndex){val c=pts[i];val n=pts[i+1];quadTo(c.first,c.second,(c.first+n.first)/2,(c.second+n.second)/2)};lineTo(pts.last().first,pts.last().second)};val a=Color.parseColor(if(positive)GREEN else RED);val fill=Path(line).apply{lineTo(pts.last().first,h.toFloat());lineTo(pts[0].first,h.toFloat());close()};val fp=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.FILL;shader=LinearGradient(0f,0f,0f,h.toFloat(),Color.argb(105,Color.red(a),Color.green(a),Color.blue(a)),Color.TRANSPARENT,Shader.TileMode.CLAMP)};val sp=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=a;style=Paint.Style.STROKE;strokeWidth=5f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND};Canvas(b).apply{drawPath(fill,fp);drawPath(line,sp)};return b}
  private fun fillRanking(v:RemoteViews,ids:List<Int>,p:List<PortfolioDisplayPosition>,f:NumberFormat){ids.forEachIndexed{i,id->val x=p.getOrNull(i);if(x==null)v.setTextViewText(id,"—")else{v.setTextViewText(id,"${x.symbol} ${f.format(x.valueEur)} ${String.format(Locale.FRANCE,"%+.1f%%",x.changePercent)}");v.setTextColor(id,Color.parseColor(if(x.changePercent>=0)GREEN else RED))}}}
  private fun signedMoney(x:Double,f:NumberFormat):String{val s=f.format(kotlin.math.abs(x));return if(x>=0)"+$s" else "−$s"}
 }
}
