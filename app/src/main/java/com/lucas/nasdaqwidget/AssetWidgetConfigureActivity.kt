package com.lucas.nasdaqwidget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class AssetWidgetConfigureActivity : AppCompatActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var selected: AssetSuggestion? = null
    private var suggestions: List<AssetSuggestion> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(30), dp(20), dp(24))
            setBackgroundColor(Color.rgb(7, 15, 25))
        }
        root.addView(TextView(this).apply {
            text = "Choisir l'actif du widget"
            textSize = 25f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Action, ETF, crypto, forex, indice…"
            textSize = 14f
            setTextColor(Color.rgb(142, 160, 178))
            setPadding(0, dp(5), 0, dp(20))
        })

        val input = AutoCompleteTextView(this).apply {
            hint = "Rechercher Apple, BTC, QQQ…"
            threshold = 1
            textSize = 17f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(112, 131, 150))
            setSingleLine(true)
            setPadding(dp(14), 0, dp(14), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(13).toFloat()
                setColor(Color.rgb(14, 25, 39))
                setStroke(dp(1), Color.rgb(37, 58, 77))
            }
        }
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_dropdown_item_1line, mutableListOf())
        input.setAdapter(adapter)
        root.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)))

        val helper = TextView(this).apply {
            text = "Tape le nom ou le ticker de l'actif"
            textSize = 12f
            setTextColor(Color.rgb(112, 131, 150))
            setPadding(dp(4), dp(8), dp(4), dp(12))
        }
        root.addView(helper)

        input.onItemClickListener = android.widget.AdapterView.OnItemClickListener { _, _, position, _ ->
            selected = suggestions.getOrNull(position)
            selected?.let {
                input.setText(it.symbol, false)
                helper.text = "✓ ${it.name} · ${it.type}${if (it.exchange.isNotBlank()) " · ${it.exchange}" else ""}"
                helper.setTextColor(Color.rgb(56, 242, 122))
            }
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun afterTextChanged(s: Editable?) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString().orEmpty().trim()
                if (!selected?.symbol.equals(query, ignoreCase = true)) selected = null
                pendingSearch?.let(handler::removeCallbacks)
                if (query.isBlank()) {
                    suggestions = emptyList()
                    adapter.clear()
                    helper.text = "Tape le nom ou le ticker de l'actif"
                    return
                }
                val task = Runnable {
                    Thread {
                        val result = AssetSearchRepository.search(query)
                        runOnUiThread {
                            if (input.text.toString().trim() != query) return@runOnUiThread
                            suggestions = result
                            adapter.clear()
                            adapter.addAll(result.map { "${it.symbol} — ${it.name}" })
                            adapter.notifyDataSetChanged()
                            if (result.isNotEmpty()) input.showDropDown()
                        }
                    }.start()
                }
                pendingSearch = task
                handler.postDelayed(task, 250)
            }
        })

        root.addView(Button(this).apply {
            text = "UTILISER CET ACTIF"
            setTextColor(Color.rgb(7, 17, 28))
            background = GradientDrawable().apply {
                cornerRadius = dp(13).toFloat()
                setColor(Color.rgb(56, 242, 122))
            }
            setOnClickListener {
                val suggestion = selected
                val raw = suggestion?.symbol ?: input.text.toString().trim().uppercase()
                if (raw.isBlank()) return@setOnClickListener
                val name = suggestion?.name ?: raw
                WidgetAssetConfig.save(this@AssetWidgetConfigureActivity, appWidgetId, raw, name)
                val manager = AppWidgetManager.getInstance(this@AssetWidgetConfigureActivity)
                NasdaqWidgetProvider.updateOne(this@AssetWidgetConfigureActivity, manager, appWidgetId)
                NasdaqWidgetProvider.requestImmediateRefresh(this@AssetWidgetConfigureActivity)
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                finish()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(10) })

        setContentView(root)
    }

    override fun onDestroy() {
        pendingSearch?.let(handler::removeCallbacks)
        super.onDestroy()
    }
}
