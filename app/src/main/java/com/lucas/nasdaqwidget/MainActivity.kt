package com.lucas.nasdaqwidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val padding = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        root.addView(TextView(this).apply {
            text = "NASDAQ Widget"
            textSize = 30f
        })
        root.addView(TextView(this).apply {
            text = "Ajoute le widget 4×2 depuis l'écran d'accueil. Appuie ensuite sur le widget pour forcer une mise à jour."
            textSize = 17f
            setPadding(0, padding, 0, padding)
        })
        root.addView(Button(this).apply {
            text = "Actualiser le widget"
            setOnClickListener { NasdaqWidgetProvider.updateAll(this@MainActivity) }
        })
        root.addView(TextView(this).apply {
            val manager = AppWidgetManager.getInstance(this@MainActivity)
            val count = manager.getAppWidgetIds(ComponentName(this@MainActivity, NasdaqWidgetProvider::class.java)).size
            text = if (count > 0) "$count widget(s) actif(s)" else "Aucun widget ajouté pour le moment"
            setPadding(0, padding, 0, 0)
        })

        setContentView(root)
        NasdaqWidgetProvider.scheduleRefresh(this)
    }
}
