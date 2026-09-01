package com.lucas.nasdaqwidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
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

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(32))
        }

        content.addView(TextView(this).apply {
            text = "Market Widgets"
            textSize = 31f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = "Tes marchés. Tes règles. Tes widgets."
            textSize = 15f
            setTextColor(Color.rgb(142, 160, 178))
            setPadding(0, dp(3), 0, dp(22))
        })

        content.addView(sectionTitle("MES WIDGETS"))
        content.addView(widgetCatalogCard(
            "NASDAQ 100",
            "2×2",
            "Cours, variation et graphique intraday",
            NasdaqWidgetProvider::class.java
        ))
        content.addView(widgetCatalogCard(
            "Alertes marché",
            "4×2",
            "Conditions personnalisées sur actions, crypto, ETF, forex…",
            AlertWidgetProvider::class.java
        ), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(9)
        })

        content.addView(sectionTitle("CRÉER UNE ALERTE").apply { setPadding(0, dp(28), 0, dp(10)) })
        content.addView(TextView(this).apply {
            text = "Recherche un nom ou un ticker. Exemples : Apple, AAPL, Bitcoin, BTC, Tesla…"
            textSize = 13f
            setTextColor(Color.rgb(142, 160, 178))
            setPadding(0, 0, 0, dp(10))
        })

        val form = card(dp(16))
        val assetInput = AutoCompleteTextView(this).apply {
            hint = "🔎  Rechercher un actif"
            threshold = 1
            setSingleLine(true)
            textSize = 17f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(112, 131, 150))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = inputBackground()
        }
        val suggestionAdapter = ArrayAdapter<String>(this, android.R.layout.simple_dropdown_item_1line, mutableListOf())
        assetInput.setAdapter(suggestionAdapter)
        form.addView(assetInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))

        val selectedText = TextView(this).apply {
            text = "Tape au moins 1 caractère pour rechercher"
            textSize = 12f
            setTextColor(Color.rgb(112, 131, 150))
            setPadding(dp(4), dp(7), dp(4), dp(2))
        }
        form.addView(selectedText)

        assetInput.onItemClickListener = android.widget.AdapterView.OnItemClickListener { _, _, position, _ ->
            val asset = suggestions.getOrNull(position) ?: return@OnItemClickListener
            selectedAssetSymbol = asset.symbol
            assetInput.setText(asset.symbol, false)
            selectedText.text = "✓ ${asset.name} · ${asset.type}${if (asset.exchange.isNotBlank()) " · ${asset.exchange}" else ""}"
            selectedText.setTextColor(Color.rgb(56, 242, 122))
        }

        assetInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString().orEmpty().trim()
                if (!selectedAssetSymbol.equals(query, ignoreCase = true)) selectedAssetSymbol = null
                pendingSearch?.let(searchHandler::removeCallbacks)
                if (query.isBlank()) {
                    suggestions = emptyList()
                    suggestionAdapter.clear()
                    selectedText.text = "Tape au moins 1 caractère pour rechercher"
                    selectedText.setTextColor(Color.rgb(112, 131, 150))
                    return
                }
                selectedText.text = "Recherche…"
                selectedText.setTextColor(Color.rgb(142, 160, 178))
                val task = Runnable {
                    Thread {
                        val result = AssetSearchRepository.search(query)
                        runOnUiThread {
                            if (assetInput.text.toString().trim() != query) return@runOnUiThread
                            suggestions = result
                            suggestionAdapter.clear()
                            suggestionAdapter.addAll(result.map { asset ->
                                "${asset.symbol} — ${asset.name}${if (asset.type.isNotBlank()) " · ${asset.type}" else ""}"
                            })
                            suggestionAdapter.notifyDataSetChanged()
                            if (result.isNotEmpty()) {
                                selectedText.text = "${result.size} proposition${if (result.size > 1) "s" else ""} trouvée${if (result.size > 1) "s" else ""}"
                                assetInput.showDropDown()
                            } else {
                                selectedText.text = "Aucun résultat. Tu peux quand même utiliser un ticker exact."
                            }
                        }
                    }.start()
                }
                pendingSearch = task
                searchHandler.postDelayed(task, 300)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        val conditionLabel = TextView(this).apply {
            text = "CONDITION"
            textSize = 11f
            setTextColor(Color.rgb(142, 160, 178))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(15), 0, dp(5))
        }
        form.addView(conditionLabel)

        val conditionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val operatorSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("<", ">", "≤", "≥")
            )
        }
        conditionRow.addView(operatorSpinner, LinearLayout.LayoutParams(dp(86), dp(56)))

        val thresholdInput = EditText(this).apply {
            hint = "Prix cible"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            textSize = 17f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(112, 131, 150))
            setPadding(dp(12), 0, dp(12), 0)
            background = inputBackground()
        }
        conditionRow.addView(thresholdInput, LinearLayout.LayoutParams(0, dp(56), 1f).apply { leftMargin = dp(8) })
        form.addView(conditionRow)

        form.addView(Button(this).apply {
            text = "+ AJOUTER L'ALERTE"
            setTextColor(Color.rgb(7, 17, 28))
            background = GradientDrawable().apply {
                cornerRadius = dp(13).toFloat()
                setColor(Color.rgb(56, 242, 122))
            }
            setOnClickListener {
                val rawSymbol = selectedAssetSymbol ?: assetInput.text.toString().trim()
                val threshold = thresholdInput.text.toString().replace(',', '.').toDoubleOrNull()
                if (rawSymbol.isBlank() || threshold == null || threshold <= 0.0) {
                    Toast.makeText(this@MainActivity, "Choisis un actif et un prix cible valide", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val operator = when (operatorSpinner.selectedItem.toString()) {
                    "≤" -> "<="
                    "≥" -> ">="
                    else -> operatorSpinner.selectedItem.toString()
                }
                AlertStore.add(this@MainActivity, rawSymbol, operator, threshold)
                assetInput.setText("", false)
                thresholdInput.text.clear()
                selectedAssetSymbol = null
                selectedText.text = "Tape au moins 1 caractère pour rechercher"
                selectedText.setTextColor(Color.rgb(112, 131, 150))
                renderAlerts()
                Thread {
                    AlertMarketRepository.refresh(this@MainActivity)
                    AlertWidgetProvider.updateAll(this@MainActivity)
                    runOnUiThread { renderAlerts() }
                }.start()
                Toast.makeText(this@MainActivity, "Alerte ajoutée", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(14) })
        content.addView(form)

        content.addView(sectionTitle("MES ALERTES").apply { setPadding(0, dp(27), 0, 0) })
        alertsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(11), 0, 0)
        }
        content.addView(alertsContainer)

        content.addView(Button(this).apply {
            text = "↻ Actualiser tous les prix"
            setOnClickListener {
                isEnabled = false
                text = "Actualisation…"
                Thread {
                    AlertMarketRepository.refresh(this@MainActivity)
                    AlertWidgetProvider.updateAll(this@MainActivity)
                    runOnUiThread {
                        isEnabled = true
                        text = "↻ Actualiser tous les prix"
                        renderAlerts()
                    }
                }.start()
            }
        })

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(7, 15, 25))
            addView(content)
        }
        setContentView(scroll)
        renderAlerts()
        NasdaqWidgetProvider.scheduleRefresh(this)
        AlertWidgetProvider.scheduleRefresh(this)
    }

    override fun onDestroy() {
        pendingSearch?.let(searchHandler::removeCallbacks)
        super.onDestroy()
    }

    private fun renderAlerts() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        alertsContainer.removeAllViews()
        val rules = AlertStore.rules(this)
        if (rules.isEmpty()) {
            alertsContainer.addView(card(dp(16)).apply {
                addView(TextView(this@MainActivity).apply {
                    text = "Aucune alerte pour le moment\nCrée ta première condition ci-dessus."
                    setTextColor(Color.rgb(142, 160, 178))
                    textSize = 14f
                })
            })
            return
        }

        val symbols = DecimalFormatSymbols(Locale.FRANCE).apply { groupingSeparator = ' ' }
        val format = DecimalFormat("#,##0.##", symbols)
        rules.sortedByDescending { rule ->
            AlertMarketRepository.cachedPrice(this, rule.symbol)?.let(rule::isTriggered) == true
        }.forEach { rule ->
            val price = AlertMarketRepository.cachedPrice(this, rule.symbol)
            val triggered = price?.let(rule::isTriggered) == true
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(8), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(if (triggered) Color.rgb(16, 42, 32) else Color.rgb(16, 28, 43))
                    setStroke(dp(1), if (triggered) Color.rgb(42, 143, 83) else Color.rgb(28, 47, 65))
                }
            }
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = rule.symbol
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(this@MainActivity).apply {
                    text = "${rule.operator} ${format.format(rule.threshold)}  ·  cours ${price?.let(format::format) ?: "--"}"
                    textSize = 13f
                    setTextColor(if (triggered) Color.rgb(56, 242, 122) else Color.rgb(142, 160, 178))
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = if (triggered) "✓" else "○"
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(if (triggered) Color.rgb(56, 242, 122) else Color.rgb(102, 120, 138))
            }, LinearLayout.LayoutParams(dp(42), dp(42)))
            row.addView(Button(this).apply {
                text = "×"
                textSize = 18f
                setOnClickListener {
                    AlertStore.remove(this@MainActivity, rule.id)
                    renderAlerts()
                    AlertWidgetProvider.updateAll(this@MainActivity)
                }
            }, LinearLayout.LayoutParams(dp(48), dp(46)))
            alertsContainer.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        }
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.rgb(56, 242, 122))
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
    }

    private fun widgetCatalogCard(title: String, size: String, subtitle: String, provider: Class<*>): LinearLayout {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        return card(dp(15)).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = if (provider == NasdaqWidgetProvider::class.java) "↗" else "◉"
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(56, 242, 122))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(Color.rgb(14, 47, 36))
                }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(TextView(this@MainActivity).apply {
                    text = "$title · $size"
                    textSize = 17f
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(this@MainActivity).apply {
                    val manager = AppWidgetManager.getInstance(this@MainActivity)
                    val count = manager.getAppWidgetIds(ComponentName(this@MainActivity, provider)).size
                    text = "$subtitle\n${if (count > 0) "$count actif${if (count > 1) "s" else ""}" else "Disponible sur l'écran d'accueil"}"
                    textSize = 12f
                    setTextColor(Color.rgb(142, 160, 178))
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun card(padding: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padding, padding, padding, padding)
        background = GradientDrawable().apply {
            cornerRadius = padding.toFloat()
            setColor(Color.rgb(14, 25, 39))
            setStroke((resources.displayMetrics.density).toInt().coerceAtLeast(1), Color.rgb(28, 47, 65))
        }
    }

    private fun inputBackground() = GradientDrawable().apply {
        cornerRadius = (12 * resources.displayMetrics.density)
        setColor(Color.rgb(9, 20, 32))
        setStroke(resources.displayMetrics.density.toInt().coerceAtLeast(1), Color.rgb(37, 58, 77))
    }
}
