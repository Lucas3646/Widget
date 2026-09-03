package com.lucas.nasdaqwidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var alertsContainer: LinearLayout
    private var selectedAssetSymbol: String? = null
    private var suggestions: List<AssetSuggestion> = emptyList()
    private val searchHandler = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18),dp(22),dp(18),dp(32)) }
        content.addView(TextView(this).apply { text="Market Widgets";textSize=31f;setTextColor(Color.WHITE);setTypeface(typeface,Typeface.BOLD) })
        content.addView(TextView(this).apply { text="Tes marchés. Tes règles. Tes widgets.";textSize=15f;setTextColor(Color.rgb(142,160,178));setPadding(0,dp(3),0,dp(12)) })
        content.addView(Button(this).apply { text="🔗 COMPTES / API";setOnClickListener{startActivity(Intent(this@MainActivity,BrokerConnectionsActivity::class.java))} })
        content.addView(sectionTitle("MES WIDGETS"))
        content.addView(widgetCatalogCard("NASDAQ 100","2×2","Cours, variation et graphique",NasdaqWidgetProvider::class.java))
        content.addView(widgetCatalogCard("Alertes marché","4×2","Prix ou MVRV Z-Score pour BTC, prix pour les autres actifs",AlertWidgetProvider::class.java),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(9)})
        content.addView(sectionTitle("CRÉER UNE ALERTE").apply{setPadding(0,dp(28),0,dp(10))})
        content.addView(TextView(this).apply{text="Recherche un nom ou un ticker. Pour BTC, tu peux choisir une alerte de prix ou de MVRV Z-Score.";textSize=13f;setTextColor(Color.rgb(142,160,178));setPadding(0,0,0,dp(10))})
        val form=card(dp(16))
        val assetInput=AutoCompleteTextView(this).apply{hint="🔎  Rechercher un actif";threshold=1;setSingleLine(true);textSize=17f;setTextColor(Color.WHITE);setHintTextColor(Color.rgb(112,131,150));setPadding(dp(12),dp(10),dp(12),dp(10));background=inputBackground()}
        val suggestionAdapter=ArrayAdapter<String>(this,android.R.layout.simple_dropdown_item_1line,mutableListOf());assetInput.setAdapter(suggestionAdapter);form.addView(assetInput,LinearLayout.LayoutParams(-1,dp(56)))
        val selectedText=TextView(this).apply{text="Tape au moins 1 caractère pour rechercher";textSize=12f;setTextColor(Color.rgb(112,131,150));setPadding(dp(4),dp(7),dp(4),dp(2))};form.addView(selectedText)
        assetInput.onItemClickListener=android.widget.AdapterView.OnItemClickListener{_,_,position,_->val clicked=suggestionAdapter.getItem(position).orEmpty();val sym=clicked.substringBefore(" — ").trim();val asset=suggestions.firstOrNull{it.symbol.equals(sym,true)}?:return@OnItemClickListener;pendingSearch?.let(searchHandler::removeCallbacks);selectedAssetSymbol=asset.symbol;assetInput.setText(asset.symbol,false);val identity="✓ ${asset.name} · ${asset.type}${if(asset.exchange.isNotBlank())" · ${asset.exchange}" else ""}";selectedText.text="$identity\nCours actuel : chargement…";selectedText.setTextColor(Color.rgb(56,242,122));Thread{val fresh=runCatching{MarketRepository.fetchAndCache(this@MainActivity,asset.symbol)}.getOrNull();runOnUiThread{if(selectedAssetSymbol.equals(asset.symbol,true)){selectedText.text=if(fresh!=null)"$identity\nCours actuel : ${formatCurrentPrice(fresh.price)}" else "$identity\nCours actuel indisponible"}}}.start()}
        assetInput.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit;override fun afterTextChanged(s:Editable?)=Unit;override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){val q=s?.toString().orEmpty().trim();pendingSearch?.let(searchHandler::removeCallbacks);if(selectedAssetSymbol.equals(q,true))return;selectedAssetSymbol=null;if(q.isBlank()){suggestions=emptyList();suggestionAdapter.clear();selectedText.text="Tape au moins 1 caractère pour rechercher";return};selectedText.text="Recherche…";val task=Runnable{Thread{val r=AssetSearchRepository.search(q);runOnUiThread{if(assetInput.text.toString().trim()!=q)return@runOnUiThread;suggestions=r;suggestionAdapter.clear();suggestionAdapter.addAll(r.map{"${it.symbol} — ${it.name}"});suggestionAdapter.notifyDataSetChanged();if(r.isNotEmpty())assetInput.showDropDown()}}.start()};pendingSearch=task;searchHandler.postDelayed(task,300)}})
        form.addView(TextView(this).apply{text="TYPE D'ALERTE";textSize=11f;setTextColor(Color.rgb(142,160,178));setTypeface(typeface,Typeface.BOLD);setPadding(dp(4),dp(15),0,dp(5))})
        val alertTypeSpinner=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("Prix de l'actif","MVRV Z-Score · BTC uniquement"))};form.addView(alertTypeSpinner,LinearLayout.LayoutParams(-1,dp(52)))
        form.addView(TextView(this).apply{text="CONDITION";textSize=11f;setTextColor(Color.rgb(142,160,178));setTypeface(typeface,Typeface.BOLD);setPadding(dp(4),dp(15),0,dp(5))})
        val conditionRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};val operatorSpinner=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("<",">","≤","≥"))};conditionRow.addView(operatorSpinner,LinearLayout.LayoutParams(dp(86),dp(56)));val thresholdInput=EditText(this).apply{hint="Prix ou Z-Score cible";inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED;setSingleLine(true);setTextColor(Color.WHITE);setHintTextColor(Color.rgb(112,131,150));background=inputBackground()};conditionRow.addView(thresholdInput,LinearLayout.LayoutParams(0,dp(56),1f).apply{leftMargin=dp(8)});form.addView(conditionRow)
        form.addView(Button(this).apply{text="+ AJOUTER L'ALERTE";setOnClickListener{val raw=selectedAssetSymbol?:assetInput.text.toString().trim();val threshold=thresholdInput.text.toString().replace(',','.').toDoubleOrNull();if(raw.isBlank()||threshold==null){Toast.makeText(this@MainActivity,"Choisis un actif et une valeur cible valide",Toast.LENGTH_SHORT).show();return@setOnClickListener};val norm=raw.uppercase().replace(" ","");val m=alertTypeSpinner.selectedItemPosition==1;if(m&&norm !in setOf("BTC","BTCUSD","BTC/USD","BTC-USD")){Toast.makeText(this@MainActivity,"Le MVRV Z-Score est disponible uniquement pour BTC",Toast.LENGTH_SHORT).show();return@setOnClickListener};val op=when(operatorSpinner.selectedItem.toString()){"≤"->"<=";"≥"->">=";else->operatorSpinner.selectedItem.toString()};AlertStore.add(this@MainActivity,raw,op,threshold,if(m)AlertRule.METRIC_MVRV else AlertRule.METRIC_PRICE);assetInput.setText("",false);thresholdInput.text.clear();selectedAssetSymbol=null;renderAlerts();Thread{AlertMarketRepository.refresh(this@MainActivity);if(m)runCatching{MvrvRepository.refresh(this@MainActivity)};AlertWidgetProvider.updateAll(this@MainActivity);runOnUiThread{renderAlerts()}}.start()}}
        ,LinearLayout.LayoutParams(-1,dp(52)).apply{topMargin=dp(14)});content.addView(form)
        content.addView(sectionTitle("MES ALERTES").apply{setPadding(0,dp(27),0,0)});alertsContainer=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(0,dp(11),0,0)};content.addView(alertsContainer)
        content.addView(Button(this).apply{text="↻ Actualiser toutes les données";setOnClickListener{Thread{AlertMarketRepository.refresh(this@MainActivity);runCatching{MvrvRepository.refresh(this@MainActivity)};AlertWidgetProvider.updateAll(this@MainActivity);MvrvWidgetProvider.updateAll(this@MainActivity);runOnUiThread{renderAlerts()}}.start()}})
        setContentView(ScrollView(this).apply{setBackgroundColor(Color.rgb(7,15,25));addView(content)});renderAlerts();NasdaqWidgetProvider.scheduleRefresh(this);AlertWidgetProvider.scheduleRefresh(this)
    }
    override fun onDestroy(){pendingSearch?.let(searchHandler::removeCallbacks);super.onDestroy()}
    private fun formatCurrentPrice(v:Double)=DecimalFormat("#,##0.##",DecimalFormatSymbols(Locale.FRANCE)).format(v)
    private fun renderAlerts(){alertsContainer.removeAllViews();val rules=AlertStore.rules(this);if(rules.isEmpty()){alertsContainer.addView(TextView(this).apply{text="Aucune alerte pour le moment";setTextColor(Color.rgb(142,160,178));textSize=14f});return};rules.forEach{r->val value=if(r.isMvrv())MvrvRepository.cached(this)?.zScore else AlertMarketRepository.cachedPrice(this,r.symbol);alertsContainer.addView(TextView(this).apply{text="${r.symbol}  ${r.operator} ${r.threshold}   ${value?.let{String.format(Locale.FRANCE,"%.2f",it)}?:"—"}";setTextColor(if(value?.let(r::isTriggered)==true)Color.rgb(56,242,122) else Color.WHITE);textSize=16f;setPadding(12,16,12,16)})}}
    private fun sectionTitle(t:String)=TextView(this).apply{text=t;textSize=12f;setTextColor(Color.rgb(56,242,122));setTypeface(typeface,Typeface.BOLD);setPadding(0,18,0,10)}
    private fun card(p:Int)=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(p,p,p,p);background=GradientDrawable().apply{cornerRadius=18f;setColor(Color.rgb(14,25,39))}}
    private fun inputBackground()=GradientDrawable().apply{cornerRadius=14f;setColor(Color.rgb(10,20,32));setStroke(1,Color.rgb(37,58,77))}
    private fun widgetCatalogCard(title:String,size:String,desc:String,provider:Class<*>)=card(16).apply{addView(TextView(this@MainActivity).apply{text="$title · $size";textSize=17f;setTextColor(Color.WHITE);setTypeface(typeface,Typeface.BOLD)});addView(TextView(this@MainActivity).apply{text=desc;textSize=13f;setTextColor(Color.rgb(142,160,178))})}
}
