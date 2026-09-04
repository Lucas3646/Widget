package com.lucas.nasdaqwidget

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.Toast

class PortfolioTimeframeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val options = PortfolioTimeframe.entries.toTypedArray()
        val current = PortfolioTimeframeStore.get(this)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Période du Portfolio Tracker")
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(), options.indexOf(current)) { d, which ->
                PortfolioTimeframeStore.set(this, options[which])
                d.dismiss()
                PortfolioWidgetProvider.refreshAndUpdate(this)
                finish()
            }
            .setPositiveButton("↻ Actualiser", null)
            .setNegativeButton("Fermer") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                PortfolioWidgetProvider.refreshAndUpdate(this)
                Toast.makeText(this, "Actualisation du widget lancée", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }
}
