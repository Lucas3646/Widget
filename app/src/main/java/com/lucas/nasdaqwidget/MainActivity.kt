package com.lucas.nasdaqwidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var alertsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }

        content.addView(TextView(this).apply {
            text = "Market Widgets"
            textSize = 30f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = "Ton catalogue de widgets marché"
            textSize = 15f
            setTextColor(Color.rgb(170, 183, 196))
            setPadding(0, dp(4), 0, dp(20))
        })

        val nasdaqCard = card(dp(18)).apply {
            addView(TextView(this@MainActivity).apply {
                text = "NASDAQ 100 · 2×2"
                textSize = 20f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                val manager = AppWidgetManager.getInstance(this@MainActivity)
                val count = manager.getAppWidgetIds(ComponentName(this@MainActivity, NasdaqWidgetProvider::class.java)).size
                text = "Cours + variation + graphique intraday\n${if (count > 0) "$count widget(s) actif(s)" else "Disponible dans le sélecteur de widgets"}"
                textSize = 14f
                setTextColor(Color.rgb(170, 183, 196))
                setPadding(0, dp(8), 0, dp(12))
            })
            addView(Button(this@MainActivity).apply {
                text = "Actualiser NASDAQ"
                setOnClickListener { NasdaqWidgetProvider.updateAll(this@MainActivity) }
            })
        }
        content.addView(nasdaqCard)

        content.addView(TextView(this).apply {
            text = "Alertes marché · 4×2"
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(26), 0, dp(8))
        })
        content.addView(TextView(this).apply {
            text = "Ajoute des règles comme BTC-USD < 70000 ou AAPL > 310. Les prix sont vérifiés automatiquement et les quatre premières règles sont visibles sur le widget."
            textSize = 14f
            setTextColor(Color.rgb(170, 183, 196))
            setPadding(0, 0, 0, dp(14))
        })

        val form = card(dp(16))
        val symbolInput = EditText(this).apply {
            hint = "Actif : BTC-USD, AAPL, TSLA…"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(120, 135, 150))
        }
        form.addView(symbolInput)

        val conditionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        val operatorSpinner = Spinner(this)
        operatorSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("<", ">", "<=", ">=")
        )
        conditionRow.addView(operatorSpinner, LinearLayout.LayoutParams(dp(90), dp(56)))

        val thresholdInput = EditText(this).apply {
            hint = "Prix cible"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(120, 135, 150))
        }
        conditionRow.addView(thresholdInput, LinearLayout.LayoutParams(0, dp(56), 1f))
        form.addView(conditionRow)

        form.addView(Button(this).apply {
            text = "Ajouter l'alerte"
            setOnClickListener {
                val symbol = symbolInput.text.toString().trim()
                val threshold = thresholdInput.text.toString().replace(',', '.').toDoubleOrNull()
                if (symbol.isBlank() || threshold == null || threshold <= 0.0) {
                    Toast.makeText(this@MainActivity, "Renseigne un actif et un prix cible valide", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                AlertStore.add(this@MainActivity, symbol, operatorSpinner.selectedItem.toString(), threshold)
                symbolInput.text.clear()
                thresholdInput.text.clear()
                renderAlerts()
                AlertWidgetProvider.updateAll(this@MainActivity)
                Toast.makeText(this@MainActivity, "Alerte ajoutée", Toast.LENGTH_SHORT).show()
            }
        })
        content.addView(form)

        alertsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        content.addView(alertsContainer)

        content.addView(Button(this).apply {
            text = "Actualiser les prix maintenant"
            setOnClickListener {
                isEnabled = false
                text = "Actualisation…"
                Thread {
                    AlertMarketRepository.refresh(this@MainActivity)
                    AlertWidgetProvider.updateAll(this@MainActivity)
                    runOnUiThread {
                        isEnabled = true
                        text = "Actualiser les prix maintenant"
                        renderAlerts()
                    }
                }.start()
            }
        })

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(8, 16, 27))
            addView(content)
        }
        setContentView(scroll)

        renderAlerts()
        NasdaqWidgetProvider.scheduleRefresh(this)
        AlertWidgetProvider.scheduleRefresh(this)
    }

    private fun renderAlerts() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        alertsContainer.removeAllViews()

        val rules = AlertStore.rules(this)
        if (rules.isEmpty()) {
            alertsContainer.addView(TextView(this).apply {
                text = "Aucune alerte configurée pour le moment."
                setTextColor(Color.rgb(170, 183, 196))
                textSize = 14f
                setPadding(0, 0, 0, dp(12))
            })
            return
        }

        rules.forEach { rule ->
            val price = AlertMarketRepository.cachedPrice(this, rule.symbol)
            val triggered = price?.let(rule::isTriggered) == true
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(10), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(Color.rgb(16, 28, 43))
                }
            }

            row.addView(TextView(this).apply {
                val current = price?.let { String.format(java.util.Locale.FRANCE, "%.2f", it) } ?: "--"
                text = "${rule.symbol}  ${rule.operator} ${rule.threshold}\nCours : $current${if (triggered) "  ✓ condition atteinte" else ""}"
                textSize = 15f
                setTextColor(if (triggered) Color.rgb(56, 242, 122) else Color.WHITE)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            row.addView(Button(this).apply {
                text = "×"
                setOnClickListener {
                    AlertStore.remove(this@MainActivity, rule.id)
                    renderAlerts()
                    AlertWidgetProvider.updateAll(this@MainActivity)
                }
            }, LinearLayout.LayoutParams(dp(54), dp(48)))

            alertsContainer.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
        }
    }

    private fun card(padding: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            background = GradientDrawable().apply {
                cornerRadius = padding.toFloat()
                setColor(Color.rgb(16, 28, 43))
            }
        }
    }
}
