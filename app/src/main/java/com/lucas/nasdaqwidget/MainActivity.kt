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
            "Prix ou MVRV Z-Score pour BTC, prix pour les autres actifs",
            AlertWidgetProvider::class.java
        ), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(9)
        })

        content.addView(sectionTitle("CRÉER UNE ALERTE").apply { setPadding(0, dp(28), 0, dp(10)) })
        content.addView(TextView(this).apply {
            text = "Recherche un nom ou un ticker. Pour BTC, tu peux choisir une alerte de prix ou de MVRV Z-Score."
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
            val clicked = suggestionAdapter.getItem(position).orEmpty()
            val clickedSymbol = clicked.substringBefore(" — ").trim()
            val asset = suggestions.firstOrNull { it.symbol.equals(clickedSymbol, ignoreCase = true) }
                ?: return@OnItemClickListener
            pendingSearch?.let(searchHandler::removeCallbacks)
            selectedAssetSymbol = asset.symbol
            assetInput.setText(asset.symbol, false)

            val identity = "✓ ${asset.name} · ${asset.type}${if (asset.exchange.isNotBlank()) " · ${asset.exchange}" else ""}"
            val cached = MarketRepository.cached(this@MainActivity, asset.symbol)
            selectedText.text = if (cached != null) {
                "$identity\nCours actuel : ${formatCurrentPrice(cached.price)}  ·  actualisation…"
            } else {
                "$identity\nCours actuel : chargement…"
            }
            selectedText.setTextColor(Color.rgb(56, 242, 122))

            Thread {
                val fresh = runCatching {
                    MarketRepository.fetchAndCache(this@MainActivity, asset.symbol)
                }.getOrNull()
                runOnUiThread {
                    if (!selectedAssetSymbol.equals(asset.symbol, ignoreCase = true)) return@runOnUiThread
                    if (!assetInput.text.toString().trim().equals(asset.symbol, ignoreCase = true)) return@runOnUiThread
                    selectedText.text = if (fresh != null) {
                        val sign = if (fresh.changePercent >= 0.0) "+" else ""
                        "$identity\nCours actuel : ${formatCurrentPrice(fresh.price)}  ·  $sign${String.format(Locale.FRANCE, "%.2f", fresh.changePercent)} % aujourd'hui"
                    } else if (cached != null) {
                        "$identity\nCours actuel : ${formatCurrentPrice(cached.price)} · donnée en cache"
                    } else {
                        "$identity\nCours actuel indisponible"
                    }
                    selectedText.setTextColor(if (fresh != null || cached != null) Color.rgb(56, 242, 122) else Color.rgb(241, 176, 67))
                }
            }.start()
        }

        assetInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString().orEmpty().trim()
                pendingSearch?.let(searchHandler::removeCallbacks)
                if (selectedAssetSymbol.equals(query, ignoreCase = true)) return
                selectedAssetSymbol = null
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

        val alertTypeLabel = TextView(this).apply {
            text = "TYPE D'ALERTE"
            textSize = 11f
            setTextColor(Color.rgb(142, 160, 178))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(15), 0, dp(5))
        }
        form.addView(alertTypeLabel)

        val alertTypeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Prix de l'actif", "MVRV Z-Score · BTC uniquement")
            )
        }
        form.addView(alertTypeSpinner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))

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
            hint = "Prix ou Z-Score cible"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
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
                if (rawSymbol.isBlank() || threshold == null) {
                    Toast.makeText(this@MainActivity, "Choisis un actif et une valeur cible valide", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val normalizedForCheck = rawSymbol.trim().uppercase().replace(" ", "")
                val isBtc = normalizedForCheck in setOf("BTC", "BTCUSD", "BTC/USD", "BTC-USD")
                val wantsMvrv = alertTypeSpinner.selectedItemPosition == 1
                if (wantsMvrv && !isBtc) {
                    Toast.makeText(this@MainActivity, "Le MVRV Z-Score est disponible uniquement pour BTC", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!wantsMvrv && threshold <= 0.0) {
                    Toast.makeText(this@MainActivity, "Le prix cible doit être supérieur à 0", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val operator = when (operatorSpinner.selectedItem.toString()) {
                    "≤" -> "<="
                    "≥" -> ">="
                    else -> operatorSpinner.selectedItem.toString()
                }
                val metric = if (wantsMvrv) AlertRule.METRIC_MVRV else AlertRule.METRIC_PRICE
                AlertStore.add(this@MainActivity, rawSymbol, operator, threshold, metric)
                assetInput.setText("", false)
                thresholdInput.text.clear()
                alertTypeSpinner.setSelection(0)
                selectedAssetSymbol = null
                selectedText.text = "Tape au moins 1 caractère pour rechercher"
                selectedText.setTextColor(Color.rgb(112, 131, 150))
                renderAlerts()
                Thread {
                    AlertMarketRepository.refresh(this@MainActivity)
                    if (AlertStore.rules(this@MainActivity).any { it.isMvrv() }) {
                        runCatching { MvrvRepository.refresh(this@MainActivity) }
                    }
                    AlertWidgetProvider.updateAll(this@MainActivity)
                    runOnUiThread { renderAlerts() }
                }.start()
                Toast.makeText(this@MainActivity, if (wantsMvrv) "Alerte MVRV ajoutée" else "Alerte prix ajoutée", Toast.LENGTH_SHORT).show()
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
            text = "↻ Actualiser toutes les données"
            setOnClickListener {
                isEnabled = false
                text = "Actualisation…"
                Thread {
                    AlertMarketRepository.refresh(this@MainActivity)
                    if (AlertStore.rules(this@MainActivity).any { it.isMvrv() }) {
                        runCatching { MvrvRepository.refresh(this@MainActivity) }
                    }
                    AlertWidgetProvider.updateAll(this@MainActivity)
                    runOnUiThread {
                        isEnabled = true
                        text = "↻ Actualiser toutes les données"
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

    private fun formatCurrentPrice(value: Double): String {
        val symbols = DecimalFormatSymbols(Locale.FRANCE).apply { groupingSeparator = ' ' }
        val pattern = when {
            value >= 1_000.0 -> "#,##0.00"
            value >= 1.0 -> "#,##0.####"
            else -> "0.########"
        }
        return DecimalFormat(pattern, symbols).format(value)
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
        val zFormat = DecimalFormat("0.00", symbols)
        rules.sortedByDescending { rule ->
            val value = if (rule.isMvrv()) {
                MvrvRepository.cached(this)?.zScore
            } else {
                AlertMarketRepository.cachedPrice(this, rule.symbol)
            }
            value?.let(rule::isTriggered) == true
        }.forEach { rule ->
            val value = if (rule.isMvrv()) {
                MvrvRepository.cached(this)?.zScore
            } else {
                AlertMarketRepository.cachedPrice(this, rule.symbol)
            }
            val triggered = value?.let(rule::isTriggered) == true
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
                    text = if (rule.isMvrv()) "BTC · MVRV Z" else rule.symbol
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(this@MainActivity).apply {
                    text = if (rule.isMvrv()) {
                        "${rule.operator} ${zFormat.format(rule.threshold)}  ·  Z actuel ${value?.let(zFormat::format) ?: "--"}"
                    } else {
                        "${rule.operator} ${format.format(rule.threshold)}  ·  cours ${value?.let(format::format) ?: "--"}"
                    }
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