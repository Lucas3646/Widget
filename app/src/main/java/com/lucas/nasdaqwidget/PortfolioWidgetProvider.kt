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
 override fun onEnabled(c:Context){super.onEnabled(c);WorkManager.getInstance(c).enqueueUniquePeriodicWork("portfolio_live_refresh",ExistingPeriodicWorkPolicy.UPDATE,PeriodicWorkRequestBuilder<PortfolioRefreshWorker>(15,TimeUnit.MINUTES).build())}
 override fun onDisabled(c:Context){WorkManager.getInstance(c).cancelUniqueWork("portfolio_live_refresh");super.onDisabled(c)}
 override fun onUpdate(c:Context,m:AppWidgetManager,ids:IntArray){updateAll(c,m,ids);refreshAndUpdate(c)}
 companion object{
  private const val GREEN="#38F27A";private const val RED="#FF6B6B"
  fun refreshAndUpdate(c:Context){Thread{val tf=PortfolioTimeframeStore.get(c);if(BrokerConnectionStore.isKrakenVerified(c))runCatching{KrakenPortfolioRepository.refresh(c,tf)};if(BrokerConnectionStore.hasIbkrSetup(c)){runCatching{IbkrFlexRepository.refresh(c,tf)};runCatching{IbkrTwrRepository.refresh(c,tf)}};updateAll(c)}.start()}
  fun updateAll(c:Context){val m=AppWidgetManager.getInstance(c);updateAll(c,m,m.getAppWidgetIds(android.content.ComponentName(c,PortfolioWidgetProvider::class.java)))}
  private fun updateAll(c:Context,m:AppWidgetManager,ids:IntArray){
   val k=KrakenPortfolioRepository.cached(c);val i=IbkrFlexRepository.cached(c);val tf=PortfolioTimeframeStore.get(c);val twr=IbkrTwrRepository.cached(c,tf)
   val eur=NumberFormat.getCurrencyInstance(Locale.FRANCE);val eur0=NumberFormat.getCurrencyInstance(Locale.FRANCE).apply{maximumFractionDigits=0};val total=(k?.totalEur?:0.0)+(i?.totalEur?:0.0)
   val pos=buildList{i?.positions?.forEach{add(PortfolioDisplayPosition(it.symbol,it.valueEur,it.periodChangePercent))};k?.positions?.forEach{add(PortfolioDisplayPosition(it.symbol,it.valueEur,it.dayChangePercent))}}
   var base=0.0;var change=0.0
   i?.let{val pct=twr?:it.periodChangePercent;val b=if(1+pct/100>0)it.totalEur/(1+pct/100) else it.totalEur;base+=b;change+=it.totalEur-b}
   k?.let{if(it.positions.isNotEmpty()){val pct=it.dayChangePercent;val b=if(1+pct/100>0)it.totalEur/(1+pct/100) else it.totalEur;base+=b;change+=it.totalEur-b}}
   val pct=if(base>0)change/base*100 else 0.0;val top=pos.filter{it.changePercent>0.0001}.sortedByDescending{it.changePercent}.take(3);val flop=pos.filter{it.changePercent< -0.0001}.sortedBy{it.changePercent}.take(3);val has=i!=null||k!=null
   val chart=when{(i?.chartValues?.size?:0)>1->i!!.chartValues.map{it+(k?.totalEur?:0.0)};has->listOf(total,total);else->emptyList()}
   ids.forEach{id->val v=RemoteViews(c.packageName,R.layout.widget_portfolio);v.setTextViewText(R.id.portfolioTotal,if(has)eur.format(total)else"— €")
    if(has){v.setTextViewText(R.id.portfolioDayChange,signedMoney(change,eur));v.setTextViewText(R.id.portfolioDayPercent,String.format(Locale.FRANCE,"%+.2f %% · %s",pct,tf.label));val col=Color.parseColor(if(pct>=0)GREEN else RED);v.setTextColor(R.id.portfolioDayChange,col);v.setTextColor(R.id.portfolioDayPercent,col);v.setImageViewBitmap(R.id.portfolioChart,sparkline(chart,pct>=0));fill(v,listOf(R.id.portfolioTop1,R.id.portfolioTop2,R.id.portfolioTop3),top,eur0);fill(v,listOf(R.id.portfolioFlop1,R.id.portfolioFlop2,R.id.portfolioFlop3),flop,eur0)}else{v.setTextViewText(R.id.portfolioDayChange,"— €");v.setTextViewText(R.id.portfolioDayPercent,"— % · ${tf.label}");v.setImageViewBitmap(R.id.portfolioChart,sparkline(emptyList(),true))}
    v.setTextViewText(R.id.portfolioKraken,when{k!=null->"Kraken · ${eur.format(k.totalEur)}";BrokerConnectionStore.hasKraken(c)->"Kraken · connexion à vérifier";else->"Kraken · toucher pour connecter"});v.setTextViewText(R.id.portfolioIbkr,when{i!=null->"IBKR · ${eur.format(i.totalEur)}";BrokerConnectionStore.hasIbkrSetup(c)->"IBKR · connexion à vérifier";else->"IBKR · toucher pour connecter"});val ti=PendingIntent.getActivity(c,id+20000,Intent(c,PortfolioTimeframeActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE);listOf(R.id.portfolioTotal,R.id.portfolioDayChange,R.id.portfolioDayPercent,R.id.portfolioChart).forEach{v.setOnClickPendingIntent(it,ti)};val bi=PendingIntent.getActivity(c,id,Intent(c,BrokerConnectionsActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE);v.setOnClickPendingIntent(R.id.portfolioIbkr,bi);v.setOnClickPendingIntent(R.id.portfolioKraken,bi);m.updateAppWidget(id,v)}
  }
  private fun sparkline(x:List<Double>,positive:Boolean):Bitmap{val w=560;val h=86;val b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);if(x.size<2)return b;val mn=x.minOrNull()?:return b;val mx=x.maxOrNull()?:return b;val r=(mx-mn).takeIf{it>0.0001}?:1.0;val p=7f;val pts=x.mapIndexed{n,z->p+(w-p*2)*n/(x.size-1f) to p+(h-p*2)*(1f-((z-mn)/r).toFloat())};val path=Path().apply{moveTo(pts[0].first,pts[0].second);for(n in 1 until pts.lastIndex){val a=pts[n];val q=pts[n+1];quadTo(a.first,a.second,(a.first+q.first)/2,(a.second+q.second)/2)};lineTo(pts.last().first,pts.last().second)};val col=Color.parseColor(if(positive)GREEN else RED);val area=Path(path).apply{lineTo(pts.last().first,h.toFloat());lineTo(pts[0].first,h.toFloat());close()};val fp=Paint(1).apply{style=Paint.Style.FILL;shader=LinearGradient(0f,0f,0f,h.toFloat(),Color.argb(105,Color.red(col),Color.green(col),Color.blue(col)),Color.TRANSPARENT,Shader.TileMode.CLAMP)};val lp=Paint(1).apply{color=col;style=Paint.Style.STROKE;strokeWidth=5f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND};Canvas(b).apply{drawPath(area,fp);drawPath(path,lp)};return b}
  private fun fill(v:RemoteViews,ids:List<Int>,p:List<PortfolioDisplayPosition>,f:NumberFormat){ids.forEachIndexed{n,id->val x=p.getOrNull(n);if(x==null)v.setTextViewText(id,"—")else{v.setTextViewText(id,"${x.symbol} ${f.format(x.valueEur)} ${String.format(Locale.FRANCE,"%+.1f%%",x.changePercent)}");v.setTextColor(id,Color.parseColor(if(x.changePercent>=0)GREEN else RED))}}}
  private fun signedMoney(x:Double,f:NumberFormat)=if(x>=0)"+${f.format(kotlin.math.abs(x))}" else "−${f.format(kotlin.math.abs(x))}"
 }
}
