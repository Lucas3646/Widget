package com.lucas.nasdaqwidget

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.NumberFormat
import java.util.Locale

class BrokerConnectionsActivity : AppCompatActivity() {
    private lateinit var krakenStatus: TextView
    private lateinit var ibkrStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
            setBackgroundColor(Color.rgb(7, 15, 25))
        }
        root.addView(TextView(this).apply { text = "Comptes connectés"; textSize = 29f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) })
        root.addView(TextView(this).apply {
            text = "Lecture seule · Kraken + IBKR. Les secrets restent chiffrés sur l’appareil."
            textSize = 14f; setTextColor(Color.rgb(142, 160, 178)); setPadding(0, dp(5), 0, dp(22))
        })

        root.addView(TextView(this).apply { text = "KRAKEN PRO"; textSize = 18f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) })
        krakenStatus = statusText(); root.addView(krakenStatus)
        val apiKey = EditText(this).apply { hint = "API Key"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setSingleLine(true) }
        val apiSecret = EditText(this).apply { hint = "Private Key / Secret"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        root.addView(apiKey); root.addView(apiSecret)
        root.addView(TextView(this).apply { text = "Clé Kraken dédiée en lecture seule. Stockage Android Keystore."; textSize = 12f; setTextColor(Color.rgb(142,160,178)); setPadding(0,dp(6),0,dp(8)) })
        root.addView(Button(this).apply {
            text = "CONNECTER KRAKEN"
            setOnClickListener {
                if (apiKey.text.isBlank() || apiSecret.text.isBlank()) { Toast.makeText(this@BrokerConnectionsActivity,"Renseigne les deux champs Kraken",Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                isEnabled=false; text="VÉRIFICATION…"
                BrokerConnectionStore.saveKraken(this@BrokerConnectionsActivity, apiKey.text.toString(), apiSecret.text.toString())
                Thread { val result=runCatching{KrakenPortfolioRepository.refresh(this@BrokerConnectionsActivity)}; runOnUiThread { isEnabled=true;text="CONNECTER KRAKEN"; if(result.isSuccess){apiKey.text.clear();apiSecret.text.clear();PortfolioWidgetProvider.updateAll(this@BrokerConnectionsActivity);Toast.makeText(this@BrokerConnectionsActivity,"Kraken connecté",Toast.LENGTH_SHORT).show()}else{BrokerConnectionStore.setKrakenVerified(this@BrokerConnectionsActivity,false);Toast.makeText(this@BrokerConnectionsActivity,"Connexion Kraken refusée : ${result.exceptionOrNull()?.message}",Toast.LENGTH_LONG).show()};refreshStatus() } }.start()
            }
        })
        root.addView(Button(this).apply { text="ACTUALISER KRAKEN"; setOnClickListener { isEnabled=false; Thread { val r=runCatching{KrakenPortfolioRepository.refresh(this@BrokerConnectionsActivity)}; runOnUiThread { isEnabled=true;if(r.isSuccess){PortfolioWidgetProvider.updateAll(this@BrokerConnectionsActivity);Toast.makeText(this@BrokerConnectionsActivity,"Kraken actualisé",Toast.LENGTH_SHORT).show()}else Toast.makeText(this@BrokerConnectionsActivity,r.exceptionOrNull()?.message?:"Erreur Kraken",Toast.LENGTH_LONG).show();refreshStatus() } }.start() } })
        root.addView(Button(this).apply { text="SUPPRIMER KRAKEN"; setOnClickListener { BrokerConnectionStore.clearKraken(this@BrokerConnectionsActivity);KrakenPortfolioRepository.clear(this@BrokerConnectionsActivity);PortfolioWidgetProvider.updateAll(this@BrokerConnectionsActivity);refreshStatus() } })

        root.addView(TextView(this).apply { text="INTERACTIVE BROKERS · FLEX"; textSize=18f;setTextColor(Color.WHITE);setTypeface(typeface,Typeface.BOLD);setPadding(0,dp(26),0,0) })
        ibkrStatus=statusText();root.addView(ibkrStatus)
        root.addView(TextView(this).apply {
            text="Simple et fiable : la Flex Query fournit positions + historique, puis Market Widgets actualise les prix de marché pour le Portfolio Tracker. Pas de Gateway ni de reconnexion quotidienne."
            textSize=13f;setTextColor(Color.rgb(142,160,178));setPadding(0,dp(6),0,dp(8))
        })
        val flexToken = EditText(this).apply { hint="Flex Web Service Token";setTextColor(Color.WHITE);setHintTextColor(Color.GRAY);setSingleLine(true);inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val queryId = EditText(this).apply { hint="Activity Flex Query ID";setTextColor(Color.WHITE);setHintTextColor(Color.GRAY);setSingleLine(true);inputType=InputType.TYPE_CLASS_NUMBER }
        root.addView(flexToken);root.addView(queryId)
        root.addView(TextView(this).apply { text="Le token est chiffré avec Android Keystore. Ne le mets jamais dans GitHub ni dans un message.";textSize=12f;setTextColor(Color.rgb(142,160,178));setPadding(0,dp(6),0,dp(8)) })
        root.addView(Button(this).apply {
            text="CONNECTER IBKR"
            setOnClickListener {
                if(flexToken.text.isBlank()||queryId.text.isBlank()){Toast.makeText(this@BrokerConnectionsActivity,"Renseigne Token + Query ID",Toast.LENGTH_SHORT).show();return@setOnClickListener}
                isEnabled=false;text="VÉRIFICATION…"
                BrokerConnectionStore.saveIbkrFlex(this@BrokerConnectionsActivity,flexToken.text.toString(),queryId.text.toString())
                Thread { val r=runCatching{IbkrFlexRepository.refresh(this@BrokerConnectionsActivity)};runOnUiThread { isEnabled=true;text="CONNECTER IBKR";if(r.isSuccess){flexToken.text.clear();queryId.text.clear();PortfolioWidgetProvider.updateAll(this@BrokerConnectionsActivity);val total=NumberFormat.getCurrencyInstance(Locale.FRANCE).format(r.getOrThrow().totalEur);Toast.makeText(this@BrokerConnectionsActivity,"IBKR connecté · $total",Toast.LENGTH_LONG).show()}else{BrokerConnectionStore.setIbkrVerified(this@BrokerConnectionsActivity,false);Toast.makeText(this@BrokerConnectionsActivity,"Connexion IBKR refusée : ${r.exceptionOrNull()?.message}",Toast.LENGTH_LONG).show()};refreshStatus() } }.start()
            }
        })
        root.addView(Button(this).apply { text="ACTUALISER IBKR";setOnClickListener { isEnabled=false;Thread{val r=runCatching{IbkrFlexRepository.refresh(this@BrokerConnectionsActivity)};runOnUiThread{isEnabled=true;if(r.isSuccess){PortfolioWidgetProvider.updateAll(this@BrokerConnectionsActivity);Toast.makeText(this@BrokerConnectionsActivity,"IBKR actualisé",Toast.LENGTH_SHORT).show()}else Toast.makeText(this@BrokerConnectionsActivity,r.exceptionOrNull()?.message?:"Erreur IBKR",Toast.LENGTH_LONG).show();refreshStatus()}}.start()} })
        root.addView(Button(this).apply { text="SUPPRIMER IBKR";setOnClickListener { BrokerConnectionStore.clearIbkr(this@BrokerConnectionsActivity);IbkrFlexRepository.clear(this@BrokerConnectionsActivity);PortfolioWidgetProvider.updateAll(this@BrokerConnectionsActivity);refreshStatus() } })
        root.addView(TextView(this).apply { text="Le widget se rafraîchit automatiquement toutes les ~15 min. Un changement de période ou une actualisation manuelle déclenche aussi un refresh immédiat.";textSize=12f;setTextColor(Color.rgb(142,160,178));gravity=Gravity.START;setPadding(0,dp(8),0,0) })

        setContentView(ScrollView(this).apply { addView(root) });refreshStatus()
    }

    private fun statusText()=TextView(this).apply{textSize=14f;setPadding(0,6,0,10)}
    private fun refreshStatus(){
        val green=Color.rgb(56,242,122);val amber=Color.rgb(241,176,67);val muted=Color.rgb(142,160,178)
        val hasKraken=BrokerConnectionStore.hasKraken(this);val krakenVerified=BrokerConnectionStore.isKrakenVerified(this);val kc=KrakenPortfolioRepository.cached(this)
        krakenStatus.text=when{krakenVerified&&kc!=null->"● Connecté · ${NumberFormat.getCurrencyInstance(Locale.FRANCE).format(kc.totalEur)}";hasKraken->"◐ Identifiants enregistrés · vérification nécessaire";else->"○ Non connecté"};krakenStatus.setTextColor(if(krakenVerified)green else if(hasKraken)amber else muted)
        val hasIbkr=BrokerConnectionStore.hasIbkrSetup(this);val ibkrVerified=BrokerConnectionStore.isIbkrVerified(this);val ic=IbkrFlexRepository.cached(this)
        ibkrStatus.text=when{ibkrVerified&&ic!=null->"● Connecté · ${NumberFormat.getCurrencyInstance(Locale.FRANCE).format(ic.totalEur)}";hasIbkr->"◐ Token enregistré · vérification nécessaire";else->"○ Non connecté"};ibkrStatus.setTextColor(if(ibkrVerified)green else if(hasIbkr)amber else muted)
    }
}
