package com.lucas.nasdaqwidget

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
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
    private lateinit var krakenKey: EditText
    private lateinit var krakenSecret: EditText
    private lateinit var krakenConnect: Button
    private lateinit var krakenReconfigure: Button
    private lateinit var flexToken: EditText
    private lateinit var queryId: EditText
    private lateinit var ibkrConnect: Button
    private lateinit var ibkrReconfigure: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val refreshTarget = intent.getStringExtra("refresh_target")
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
            setBackgroundColor(Color.rgb(7, 15, 25))
        }
        root.addView(TextView(this).apply { text = "Comptes connectés"; textSize = 29f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) })
        root.addView(TextView(this).apply {
            text = "Lecture seule · Kraken + IBKR. Les identifiants sont saisis une seule fois puis restent chiffrés sur l’appareil."
            textSize = 14f; setTextColor(Color.rgb(142, 160, 178)); setPadding(0, dp(5), 0, dp(12))
        })
        root.addView(Button(this).apply {
            text = "↻ ACTUALISER LE WIDGET"
            setOnClickListener {
                when (refreshTarget) {
                    "dividend" -> DividendWidgetProvider.refreshAndUpdate(this@BrokerConnectionsActivity)
                    "portfolio" -> PortfolioWidgetProvider.refreshAndUpdate(this@BrokerConnectionsActivity)
                    else -> {
                        PortfolioWidgetProvider.refreshAndUpdate(this@BrokerConnectionsActivity)
                        if (BrokerConnectionStore.hasIbkrSetup(this@BrokerConnectionsActivity)) DividendWidgetProvider.refreshAndUpdate(this@BrokerConnectionsActivity)
                    }
                }
                Toast.makeText(this@BrokerConnectionsActivity, "Actualisation du widget lancée", Toast.LENGTH_SHORT).show()
            }
        })

        root.addView(TextView(this).apply { text = "KRAKEN PRO"; textSize = 18f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(18), 0, 0) })
        krakenStatus = statusText(); root.addView(krakenStatus)
        krakenKey = EditText(this).apply { hint = "API Key"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setSingleLine(true) }
        krakenSecret = EditText(this).apply { hint = "Private Key / Secret"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        root.addView(krakenKey); root.addView(krakenSecret)
        krakenConnect = Button(this).apply {
            text = "CONNECTER KRAKEN"
            setOnClickListener {
                if (krakenKey.text.isBlank() || krakenSecret.text.isBlank()) { Toast.makeText(this@BrokerConnectionsActivity,"Renseigne les deux champs Kraken",Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                isEnabled=false; text="VÉRIFICATION…"
                BrokerConnectionStore.saveKraken(this@BrokerConnectionsActivity, krakenKey.text.toString(), krakenSecret.text.toString())
                Thread {
                    val result=runCatching{KrakenPortfolioRepository.refresh(this@BrokerConnectionsActivity)}
                    runOnUiThread {
                        isEnabled=true; text="CONNECTER KRAKEN"
                        if(result.isSuccess){ krakenKey.text.clear(); krakenSecret.text.clear(); PortfolioWidgetProvider.updateAll(this@BrokerConnectionsActivity); Toast.makeText(this@BrokerConnectionsActivity,"Kraken connecté",Toast.LENGTH_SHORT).show() }
                        else { BrokerConnectionStore.setKrakenVerified(this@BrokerConnectionsActivity,false); Toast.makeText(this@BrokerConnectionsActivity,"Connexion Kraken refusée : ${result.exceptionOrNull()?.message}",Toast.LENGTH_LONG).show() }
                        refreshStatus(); updateCredentialFields()
                    }
                }.start()
            }
        }
        root.addView(krakenConnect)
        krakenReconfigure = Button(this).apply {
            text="MODIFIER LES IDENTIFIANTS KRAKEN"
            setOnClickListener { krakenKey.visibility=View.VISIBLE; krakenSecret.visibility=View.VISIBLE; krakenConnect.visibility=View.VISIBLE; visibility=View.GONE }
        }
        root.addView(krakenReconfigure)
        root.addView(Button(this).apply { text="ACTUALISER KRAKEN"; setOnClickListener { isEnabled=false; Thread { val r=runCatching{KrakenPortfolioRepository.refresh(this@BrokerConnectionsActivity)}; runOnUiThread { isEnabled=true; if(r.isSuccess){PortfolioWidgetProvider.updateAll(this@BrokerConnectionsActivity);Toast.makeText(this@BrokerConnectionsActivity,"Kraken actualisé",Toast.LENGTH_SHORT).show()}else Toast.makeText(this@BrokerConnectionsActivity,r.exceptionOrNull()?.message?:"Erreur Kraken",Toast.LENGTH_LONG).show();refreshStatus() } }.start() } })
        root.addView(Button(this).apply { text="SUPPRIMER KRAKEN"; setOnClickListener { BrokerConnectionStore.clearKraken(this@BrokerConnectionsActivity);KrakenPortfolioRepository.clear(this@BrokerConnectionsActivity);PortfolioWidgetProvider.updateAll(this@BrokerConnectionsActivity);refreshStatus();updateCredentialFields() } })

        root.addView(TextView(this).apply { text="INTERACTIVE BROKERS · FLEX"; textSize=18f;setTextColor(Color.WHITE);setTypeface(typeface,Typeface.BOLD);setPadding(0,dp(26),0,0) })
        ibkrStatus=statusText();root.addView(ibkrStatus)
        root.addView(TextView(this).apply {
            text="La Flex Query fournit positions + dividendes. Market Widgets actualise ensuite les prix de marché. Une fois Token + Query ID enregistrés, tu n’as plus à les saisir."
            textSize=13f;setTextColor(Color.rgb(142,160,178));setPadding(0,dp(6),0,dp(8))
        })
        flexToken = EditText(this).apply { hint="Flex Web Service Token";setTextColor(Color.WHITE);setHintTextColor(Color.GRAY);setSingleLine(true);inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        queryId = EditText(this).apply { hint="Activity Flex Query ID";setTextColor(Color.WHITE);setHintTextColor(Color.GRAY);setSingleLine(true);inputType=InputType.TYPE_CLASS_NUMBER }
        root.addView(flexToken);root.addView(queryId)
        ibkrConnect = Button(this).apply {
            text="CONNECTER IBKR"
            setOnClickListener {
                if(flexToken.text.isBlank()||queryId.text.isBlank()){Toast.makeText(this@BrokerConnectionsActivity,"Renseigne Token + Query ID",Toast.LENGTH_SHORT).show();return@setOnClickListener}
                isEnabled=false;text="VÉRIFICATION…"
                BrokerConnectionStore.saveIbkrFlex(this@BrokerConnectionsActivity,flexToken.text.toString(),queryId.text.toString())
                Thread {
                    val r=runCatching{IbkrFlexRepository.refresh(this@BrokerConnectionsActivity)}
                    val dividends=runCatching{IbkrDividendRepository.refresh(this@BrokerConnectionsActivity)}
                    runOnUiThread {
                        isEnabled=true;text="CONNECTER IBKR"
                        if(r.isSuccess){ flexToken.text.clear(); queryId.text.clear(); PortfolioWidgetProvider.updateAll(this@BrokerConnectionsActivity); DividendWidgetProvider.updateAll(this@BrokerConnectionsActivity); val total=NumberFormat.getCurrencyInstance(Locale.FRANCE).format(r.getOrThrow().totalEur); Toast.makeText(this@BrokerConnectionsActivity,"IBKR connecté · $total",Toast.LENGTH_LONG).show() }
                        else { BrokerConnectionStore.setIbkrVerified(this@BrokerConnectionsActivity,false); Toast.makeText(this@BrokerConnectionsActivity,"Connexion IBKR refusée : ${r.exceptionOrNull()?.message}",Toast.LENGTH_LONG).show() }
                        if (dividends.isFailure) DividendWidgetProvider.updateAll(this@BrokerConnectionsActivity)
                        refreshStatus(); updateCredentialFields()
                    }
                }.start()
            }
        }
        root.addView(ibkrConnect)
        ibkrReconfigure = Button(this).apply {
            text="MODIFIER TOKEN / QUERY ID"
            setOnClickListener { flexToken.visibility=View.VISIBLE; queryId.visibility=View.VISIBLE; ibkrConnect.visibility=View.VISIBLE; visibility=View.GONE }
        }
        root.addView(ibkrReconfigure)
        root.addView(Button(this).apply {
            text="ACTUALISER IBKR"
            setOnClickListener {
                isEnabled=false
                Thread {
                    val r=runCatching{IbkrFlexRepository.refresh(this@BrokerConnectionsActivity)}
                    runCatching{IbkrDividendRepository.refresh(this@BrokerConnectionsActivity)}
                    runOnUiThread {
                        isEnabled=true
                        if(r.isSuccess){PortfolioWidgetProvider.updateAll(this@BrokerConnectionsActivity);DividendWidgetProvider.updateAll(this@BrokerConnectionsActivity);Toast.makeText(this@BrokerConnectionsActivity,"IBKR + dividendes actualisés",Toast.LENGTH_SHORT).show()}
                        else Toast.makeText(this@BrokerConnectionsActivity,r.exceptionOrNull()?.message?:"Erreur IBKR",Toast.LENGTH_LONG).show()
                        refreshStatus()
                    }
                }.start()
            }
        })
        root.addView(Button(this).apply { text="SUPPRIMER IBKR";setOnClickListener { BrokerConnectionStore.clearIbkr(this@BrokerConnectionsActivity);IbkrFlexRepository.clear(this@BrokerConnectionsActivity);IbkrDividendRepository.clear(this@BrokerConnectionsActivity);PortfolioWidgetProvider.updateAll(this@BrokerConnectionsActivity);DividendWidgetProvider.updateAll(this@BrokerConnectionsActivity);refreshStatus();updateCredentialFields() } })
        root.addView(TextView(this).apply { text="Le widget portefeuille se rafraîchit automatiquement. Pour les dividendes, une actualisation IBKR recharge aussi les données du widget dividende.";textSize=12f;setTextColor(Color.rgb(142,160,178));gravity=Gravity.START;setPadding(0,dp(8),0,0) })

        setContentView(ScrollView(this).apply { addView(root) })
        refreshStatus(); updateCredentialFields()
    }

    private fun statusText()=TextView(this).apply{textSize=14f;setPadding(0,6,0,10)}

    private fun updateCredentialFields() {
        val hasKraken = BrokerConnectionStore.hasKraken(this)
        krakenKey.visibility = if (hasKraken) View.GONE else View.VISIBLE
        krakenSecret.visibility = if (hasKraken) View.GONE else View.VISIBLE
        krakenConnect.visibility = if (hasKraken) View.GONE else View.VISIBLE
        krakenReconfigure.visibility = if (hasKraken) View.VISIBLE else View.GONE

        val hasIbkr = BrokerConnectionStore.hasIbkrSetup(this)
        flexToken.visibility = if (hasIbkr) View.GONE else View.VISIBLE
        queryId.visibility = if (hasIbkr) View.GONE else View.VISIBLE
        ibkrConnect.visibility = if (hasIbkr) View.GONE else View.VISIBLE
        ibkrReconfigure.visibility = if (hasIbkr) View.VISIBLE else View.GONE
    }

    private fun refreshStatus(){
        val green=Color.rgb(56,242,122);val amber=Color.rgb(241,176,67);val muted=Color.rgb(142,160,178)
        val hasKraken=BrokerConnectionStore.hasKraken(this);val krakenVerified=BrokerConnectionStore.isKrakenVerified(this);val kc=KrakenPortfolioRepository.cached(this)
        krakenStatus.text=when{krakenVerified&&kc!=null->"● Connecté · ${NumberFormat.getCurrencyInstance(Locale.FRANCE).format(kc.totalEur)}";hasKraken->"◐ Identifiants enregistrés · vérification nécessaire";else->"○ Non connecté"};krakenStatus.setTextColor(if(krakenVerified)green else if(hasKraken)amber else muted)
        val hasIbkr=BrokerConnectionStore.hasIbkrSetup(this);val ibkrVerified=BrokerConnectionStore.isIbkrVerified(this);val ic=IbkrFlexRepository.cached(this)
        ibkrStatus.text=when{ibkrVerified&&ic!=null->"● Connecté · ${NumberFormat.getCurrencyInstance(Locale.FRANCE).format(ic.totalEur)}";hasIbkr->"◐ Token + Query ID enregistrés";else->"○ Non connecté"};ibkrStatus.setTextColor(if(ibkrVerified)green else if(hasIbkr)amber else muted)
    }
}
