package com.lucas.nasdaqwidget

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle

class PortfolioTimeframeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val options = PortfolioTimeframe.entries.toTypedArray()
        val current = PortfolioTimeframeStore.get(this)
        AlertDialog.Builder(this)
            .setTitle("Période du Portfolio Tracker")
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(), options.indexOf(current)) { dialog, which ->
                PortfolioTimeframeStore.set(this, options[which])
                dialog.dismiss()
                PortfolioWidgetProvider.refreshAndUpdate(this)
                finish()
            }
            .setNegativeButton("Annuler") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
