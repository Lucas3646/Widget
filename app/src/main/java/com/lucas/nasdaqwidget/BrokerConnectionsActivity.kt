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
            text = "Connecte tes comptes en lecture seule pour alimenter les widgets Portfolio et Dividendes."
            textSize = 14f
            setTextColor(Color.rgb(142, 160, 178))
            setPadding(0, dp(5), 0, dp(22))
        })

        root.addView(TextView(this).apply { text = "KRAKEN PRO"; textSize = 18f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) })
        krakenStatus = statusText()
        root.addView(krakenStatus)
        val apiKey = EditText(this).apply { hint = "API Key"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setSingleLine(true) }
        val apiSecret = EditText(this).apply { hint = "Private Key / Secret"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        root.addView(apiKey)
        root.addView(apiSecret)
        root.addView(TextView(this).apply {
            text = "Crée une clé Kraken dédiée avec uniquement les permissions de consultation nécessaires. N’active pas trading ni retraits. Les identifiants saisis ici sont chiffrés avec Android Keystore et restent sur l’appareil."
            textSize = 12f; setTextColor(Color.rgb(142, 160, 178)); setPadding(0, dp(6), 0, dp(8))
        })
        root.addView(Button(this).apply {
            text = "ENREGISTRER KRAKEN"
            setOnClickListener {
                if (apiKey.text.isBlank() || apiSecret.text.isBlank()) { Toast.makeText(this@BrokerConnectionsActivity, "Renseigne les deux champs Kraken", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                BrokerConnectionStore.saveKraken(this@BrokerConnectionsActivity, apiKey.text.toString(), apiSecret.text.toString())
                apiKey.text.clear(); apiSecret.text.clear(); refreshStatus(); Toast.makeText(this@BrokerConnectionsActivity, "Kraken enregistré sur cet appareil", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(Button(this).apply { text = "SUPPRIMER KRAKEN"; setOnClickListener { BrokerConnectionStore.clearKraken(this@BrokerConnectionsActivity); refreshStatus() } })

        root.addView(TextView(this).apply { text = "INTERACTIVE BROKERS"; textSize = 18f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(26), 0, 0) })
        ibkrStatus = statusText()
        root.addView(ibkrStatus)
        root.addView(TextView(this).apply {
            text = "Pour un compte IBKR individuel, la connexion Web API standard passe par Client Portal Gateway et demande une authentification navigateur quotidienne. Elle ne peut donc pas être connectée automatiquement comme Kraken directement depuis l’app mobile."
            textSize = 13f; setTextColor(Color.rgb(142, 160, 178)); setPadding(0, dp(6), 0, dp(8))
        })
        root.addView(Button(this).apply {
            text = "OUVRIR LA CONFIGURATION IBKR"
            setOnClickListener {
                BrokerConnectionStore.setIbkrSetupAcknowledged(this@BrokerConnectionsActivity, true)
                refreshStatus()
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.interactivebrokers.com/docs/web-api/getting-started")))
            }
        })
        root.addView(TextView(this).apply {
            text = "La synchronisation IBKR sera activée lorsque le Gateway/flux d’authentification est disponible. Aucun mot de passe IBKR n’est demandé ni stocké par Market Widgets."
            textSize = 12f; setTextColor(Color.rgb(142, 160, 178)); gravity = Gravity.START; setPadding(0, dp(8), 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(root) })
        refreshStatus()
    }

    private fun statusText() = TextView(this).apply { textSize = 14f; setPadding(0, 6, 0, 10) }

    private fun refreshStatus() {
        val green = Color.rgb(56, 242, 122)
        val muted = Color.rgb(142, 160, 178)
        val kraken = BrokerConnectionStore.hasKraken(this)
        krakenStatus.text = if (kraken) "● Identifiants enregistrés" else "○ Non connecté"
        krakenStatus.setTextColor(if (kraken) green else muted)
        val ibkr = BrokerConnectionStore.hasIbkrSetup(this)
        ibkrStatus.text = if (ibkr) "◐ Configuration Gateway à terminer" else "○ Non configuré"
        ibkrStatus.setTextColor(if (ibkr) Color.rgb(241, 176, 67) else muted)
    }
}
