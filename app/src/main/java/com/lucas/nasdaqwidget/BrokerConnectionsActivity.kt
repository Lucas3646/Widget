package com.lucas.nasdaqwidget

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
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
        root.addView(TextView(this).apply {
            text = "Comptes connectés"
            textSize = 29f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Connecte tes comptes en lecture seule pour alimenter Portfolio · IBKR + Kraken et Dividendes · IBKR."
            textSize = 14f
            setTextColor(Color.rgb(142, 160, 178))
            setPadding(0, dp(5), 0, dp(22))
        })

        root.addView(TextView(this).apply { text = "KRAKEN PRO"; textSize = 18f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) })
        krakenStatus = statusText()
        root.addView(krakenStatus)
        val apiKey = EditText(this).apply {
            hint = "API Key"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setSingleLine(true)
        }
        val apiSecret = EditText(this).apply {
            hint = "Private Key / Secret"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(apiKey)
        root.addView(apiSecret)
        root.addView(TextView(this).apply {
            text = "Utilise une clé Kraken dédiée en lecture seule. Les identifiants sont chiffrés avec Android Keystore et restent sur l’appareil."
            textSize = 12f
            setTextColor(Color.rgb(142, 160, 178))
            setPadding(0, dp(6), 0, dp(8))
        })
        root.addView(Button(this).apply {
            text = "CONNECTER KRAKEN"
            setOnClickListener {
                if (apiKey.text.isBlank() || apiSecret.text.isBlank()) {
                    Toast.makeText(this@BrokerConnectionsActivity, "Renseigne les deux champs Kraken", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                isEnabled = false
                text = "VÉRIFICATION…"
                BrokerConnectionStore.saveKraken(this@BrokerConnectionsActivity, apiKey.text.toString(), apiSecret.text.toString())
                Thread {
                    val result = runCatching { KrakenPortfolioRepository.refresh(this@BrokerConnectionsActivity) }
                    runOnUiThread {
                        isEnabled = true
                        text = "CONNECTER KRAKEN"
                        if (result.isSuccess) {
                            apiKey.text.clear()
                            apiSecret.text.clear()
                            PortfolioWidgetProvider.updateAll(this@BrokerConnectionsActivity)
                            val total = NumberFormat.getCurrencyInstance(Locale.FRANCE).format(result.getOrThrow().totalEur)
                            Toast.makeText(this@BrokerConnectionsActivity, "Kraken connecté · $total", Toast.LENGTH_LONG).show()
                        } else {
                            BrokerConnectionStore.setKrakenVerified(this@BrokerConnectionsActivity, false)
                            Toast.makeText(this@BrokerConnectionsActivity, "Connexion Kraken refusée : ${result.exceptionOrNull()?.message ?: "erreur inconnue"}", Toast.LENGTH_LONG).show()
                        }
                        refreshStatus()
                    }
                }.start()
            }
        })
        root.addView(Button(this).apply {
            text = "ACTUALISER KRAKEN"
            setOnClickListener {
                isEnabled = false
                Thread {
                    val result = runCatching { KrakenPortfolioRepository.refresh(this@BrokerConnectionsActivity) }
                    runOnUiThread {
                        isEnabled = true
                        if (result.isSuccess) {
                            PortfolioWidgetProvider.updateAll(this@BrokerConnectionsActivity)
                            Toast.makeText(this@BrokerConnectionsActivity, "Kraken actualisé", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@BrokerConnectionsActivity, result.exceptionOrNull()?.message ?: "Erreur Kraken", Toast.LENGTH_LONG).show()
                        }
                        refreshStatus()
                    }
                }.start()
            }
        })
        root.addView(Button(this).apply {
            text = "SUPPRIMER KRAKEN"
            setOnClickListener {
                BrokerConnectionStore.clearKraken(this@BrokerConnectionsActivity)
                KrakenPortfolioRepository.clear(this@BrokerConnectionsActivity)
                PortfolioWidgetProvider.updateAll(this@BrokerConnectionsActivity)
                refreshStatus()
            }
        })

        root.addView(TextView(this).apply {
            text = "INTERACTIVE BROKERS"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(26), 0, 0)
        })
        ibkrStatus = statusText()
        root.addView(ibkrStatus)
        root.addView(TextView(this).apply {
            text = "Pour un compte IBKR individuel, la Web API standard passe par Client Portal Gateway avec authentification navigateur quotidienne. Market Widgets ne stocke donc aucun mot de passe IBKR."
            textSize = 13f
            setTextColor(Color.rgb(142, 160, 178))
            setPadding(0, dp(6), 0, dp(8))
        })
        root.addView(Button(this).apply {
            text = "CONFIGURER IBKR"
            setOnClickListener {
                BrokerConnectionStore.setIbkrSetupAcknowledged(this@BrokerConnectionsActivity, true)
                refreshStatus()
                PortfolioWidgetProvider.updateAll(this@BrokerConnectionsActivity)
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.interactivebrokers.com/docs/web-api/getting-started")))
            }
        })
        root.addView(TextView(this).apply {
            text = "La récupération automatique IBKR sera branchée séparément dès qu’un flux compatible mobile est disponible."
            textSize = 12f
            setTextColor(Color.rgb(142, 160, 178))
            gravity = Gravity.START
            setPadding(0, dp(8), 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(root) })
        refreshStatus()
    }

    private fun statusText() = TextView(this).apply { textSize = 14f; setPadding(0, 6, 0, 10) }

    private fun refreshStatus() {
        val green = Color.rgb(56, 242, 122)
        val amber = Color.rgb(241, 176, 67)
        val muted = Color.rgb(142, 160, 178)
        val hasKraken = BrokerConnectionStore.hasKraken(this)
        val verified = BrokerConnectionStore.isKrakenVerified(this)
        val cached = KrakenPortfolioRepository.cached(this)
        krakenStatus.text = when {
            verified && cached != null -> "● Connecté · ${NumberFormat.getCurrencyInstance(Locale.FRANCE).format(cached.totalEur)}"
            hasKraken -> "◐ Identifiants enregistrés · vérification nécessaire"
            else -> "○ Non connecté"
        }
        krakenStatus.setTextColor(if (verified) green else if (hasKraken) amber else muted)
        val ibkr = BrokerConnectionStore.hasIbkrSetup(this)
        ibkrStatus.text = if (ibkr) "◐ Configuration Gateway à terminer" else "○ Non configuré"
        ibkrStatus.setTextColor(if (ibkr) amber else muted)
    }
}
