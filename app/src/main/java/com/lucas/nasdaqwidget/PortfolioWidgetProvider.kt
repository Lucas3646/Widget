package com.lucas.nasdaqwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.widget.RemoteViews
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

private data class PortfolioDisplayPosition(val symbol: String, val valueEur: Double, val changePercent: Double)

class PortfolioWidgetProvider : AppWidgetProvider() {
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("portfolio_live_refresh", ExistingPeriodicWorkPolicy.UPDATE, PeriodicWorkRequestBuilder<PortfolioRefreshWorker>(15, TimeUnit.MINUTES).build())
    }
    override fun onDisabled(context: Context) { WorkManager.getInstance(context).cancelUniqueWork("portfolio_live_refresh"); super.onDisabled(context) }
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) { updateAll(context, appWidgetManager, appWidgetIds); refreshAndUpdate(context) }

    companion object {
        private const val GREEN = "#38F27A"; private const val RED = "#FF6B6B"
        fun refreshAndUpdate(context: Context) { Thread { val timeframe=PortfolioTimeframeStore.get(context); if(BrokerConnectionStore.isKrakenVerified(context)) runCatching{KrakenPortfolioRepository.refresh(context,timeframe)}; if(BrokerConnectionStore.hasIbkrSetup(context)) runCatching{IbkrFlexRepository.refresh(context,timeframe)}; updateAll(context) }.start() }
        fun updateAll(context: Context) { val m=AppWidgetManager.getInstance(context); updateAll(context,m,m.getAppWidgetIds(android.content.ComponentName(context,PortfolioWidgetProvider::class.java))) }
        private fun updateAll(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val kraken=KrakenPortfolioRepository.cached(context); val ibkr=IbkrFlexRepository.cached(context); val timeframe=PortfolioTimeframeStore.get(context)
            val eur=NumberFormat.getCurrencyInstance(Locale.FRANCE); val eur0=NumberFormat.getCurrencyInstance(Locale.FRANCE).apply{maximumFractionDigits=0}
            val total=(kraken?.totalEur?:0.0)+(ibkr?.totalEur?:0.0)
            val ibkrChart=ibkr?.chartValues.orEmpty(); val ibkrChange=if(ibkrChart.size>=2) ibkrChart.last()-ibkrChart.first() else ibkr?.periodChangeEur?:0.0; val ibkrBase=if(ibkrChart.size>=2) ibkrChart.first() else (ibkr?.totalEur?:0.0)-ibkrChange
            val krakenChange=kraken?.dayChangeEur?:0.0; val krakenBase=(kraken?.totalEur?:0.0)-krakenChange
            val change=ibkrChange+krakenChange; val base=ibkrBase+krakenBase; val percent=if(base>0) change/base*100 else 0.0
            val positions=buildList { ibkr?.positions?.forEach{add(PortfolioDisplayPosition(it.symbol,it.valueEur,it.periodChangePercent))}; kraken?.positions?.forEach{add(PortfolioDisplayPosition(it.symbol,it.valueEur,it.dayChangePercent))} }
            val hasSnapshot=kraken!=null||ibkr!=null; val hasPerformance=ibkr!=null || (kraken?.positions?.isNotEmpty()==true)
            val chartValues=when{ibkrChart.isNotEmpty()->ibkrChart.map{it+(kraken?.totalEur?:0.0)};hasSnapshot->listOf(total,total);else->emptyList()}
            ids.forEach { id ->
                val views=RemoteViews(context.packageName,R.layout.widget_portfolio); views.setTextViewText(R.id.portfolioTotal,if(hasSnapshot)eur.format(total)else"— €")
                if(hasSnapshot){
                    if(hasPerformance){views.setTextViewText(R.id.portfolioDayChange,signedMoney(change,eur));views.setTextViewText(R.id.portfolioDayPercent,String.format(Locale.FRANCE,"%+.2f %% · %s",percent,timeframe.label));val c=Color.parseColor(if(change>=0)GREEN else RED);views.setTextColor(R.id.portfolioDayChange,c);views.setTextColor(R.id.portfolioDayPercent,c)}else{views.setTextViewText(R.id.portfolioDayChange,"— €");views.setTextViewText(R.id.portfolioDayPercent,"perf. indispo · ${timeframe.label}")}
                    views.setImageViewBitmap(R.id.portfolioChart,sparkline(chartValues,change>=0));fillRanking(views,listOf(R.id.portfolioTop1,R.id.portfolioTop2,R.id.portfolioTop3),positions.sortedByDescending{it.changePercent}.take(3),eur0);fillRanking(views,listOf(R.id.portfolioFlop1,R.id.portfolioFlop2,R.id.portfolioFlop3),positions.sortedBy{it.changePercent}.take(3),eur0)
                }else{views.setTextViewText(R.id.portfolioDayChange,"— €");views.setTextViewText(R.id.portfolioDayPercent,"— % · ${timeframe.label}");views.setImageViewBitmap(R.id.portfolioChart,sparkline(emptyList(),true));listOf(R.id.portfolioTop1,R.id.portfolioTop2,R.id.portfolioTop3,R.id.portfolioFlop1,R.id.portfolioFlop2,R.id.portfolioFlop3).forEach{views.setTextViewText(it,"—")}}
                views.setTextViewText(R.id.portfolioKraken,when{kraken!=null->"Kraken · ${eur.format(kraken.totalEur)}";BrokerConnectionStore.hasKraken(context)->"Kraken · connexion à vérifier";else->"Kraken · toucher pour connecter"});views.setTextViewText(R.id.portfolioIbkr,when{ibkr!=null->"IBKR · ${eur.format(ibkr.totalEur)}";BrokerConnectionStore.hasIbkrSetup(context)->"IBKR · connexion à vérifier";else->"IBKR · toucher pour connecter"})
                val ti=PendingIntent.getActivity(context,id+20000,Intent(context,PortfolioTimeframeActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE);views.setOnClickPendingIntent(R.id.portfolioTotal,ti);views.setOnClickPendingIntent(R.id.portfolioDayChange,ti);views.setOnClickPendingIntent(R.id.portfolioDayPercent,ti);views.setOnClickPendingIntent(R.id.portfolioChart,ti)
                val bi=PendingIntent.getActivity(context,id,Intent(context,BrokerConnectionsActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE);views.setOnClickPendingIntent(R.id.portfolioIbkr,bi);views.setOnClickPendingIntent(R.id.portfolioKraken,bi);manager.updateAppWidget(id,views)
            }
        }
        private fun sparkline(values:List<Double>,positive:Boolean):Bitmap{val width=560;val height=86;val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);if(values.size<2)return bitmap;val min=values.minOrNull()?:return bitmap;val max=values.maxOrNull()?:return bitmap;val range=(max-min).takeIf{it>0.0001}?:1.0;val pad=7f;val points=values.mapIndexed{i,v->(pad+(width-pad*2)*i.toFloat()/(values.size-1).coerceAtLeast(1)) to (pad+(height-pad*2)*(1f-((v-min)/range).toFloat()))};val line=Path().apply{moveTo(points.first().first,points.first().second);for(i in 1 until points.lastIndex){val c=points[i];val n=points[i+1];quadTo(c.first,c.second,(c.first+n.first)/2f,(c.second+n.second)/2f)};val l=points.last();quadTo(l.first,l.second,l.first,l.second)};val accent=Color.parseColor(if(positive)GREEN else RED);val fill=Path(line).apply{lineTo(points.last().first,height.toFloat());lineTo(points.first().first,height.toFloat());close()};val fp=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.FILL;shader=LinearGradient(0f,0f,0f,height.toFloat(),Color.argb(105,Color.red(accent),Color.green(accent),Color.blue(accent)),Color.argb(0,Color.red(accent),Color.green(accent),Color.blue(accent)),Shader.TileMode.CLAMP)};val sp=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=accent;style=Paint.Style.STROKE;strokeWidth=5f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND};Canvas(bitmap).apply{drawPath(fill,fp);drawPath(line,sp)};return bitmap}
        private fun fillRanking(v:RemoteViews,ids:List<Int>,p:List<PortfolioDisplayPosition>,eur0:NumberFormat){ids.forEachIndexed{i,id->val x=p.getOrNull(i);if(x==null)v.setTextViewText(id,"—")else{v.setTextViewText(id,"${x.symbol} ${eur0.format(x.valueEur)} ${String.format(Locale.FRANCE,"%+.1f%%",x.changePercent)}");v.setTextColor(id,Color.parseColor(if(x.changePercent>=0)GREEN else RED))}}}
        private fun signedMoney(value:Double,format:NumberFormat):String{val raw=format.format(kotlin.math.abs(value));return if(value>=0)"+$raw" else "−$raw"}
    }
}
