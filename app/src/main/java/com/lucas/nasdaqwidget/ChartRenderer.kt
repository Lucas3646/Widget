package com.lucas.nasdaqwidget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader

object ChartRenderer {
    fun render(values: List<Float>, width: Int = 900, height: Int = 220): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        if (values.size < 2) return bitmap

        val canvas = Canvas(bitmap)
        val min = values.minOrNull() ?: 0f
        val max = values.maxOrNull() ?: 1f
        val range = (max - min).takeIf { it > 0f } ?: 1f
        val padding = 8f
        val usableHeight = height - padding * 2

        fun x(i: Int) = i.toFloat() / (values.size - 1) * width
        fun y(v: Float) = padding + (max - v) / range * usableHeight

        val linePath = Path().apply {
            moveTo(x(0), y(values[0]))
            for (i in 1 until values.size) lineTo(x(i), y(values[i]))
        }

        val fillPath = Path(linePath).apply {
            lineTo(width.toFloat(), height.toFloat())
            lineTo(0f, height.toFloat())
            close()
        }

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                Color.argb(130, 56, 242, 122),
                Color.argb(0, 56, 242, 122),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawPath(fillPath, fill)

        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(56, 242, 122)
            strokeWidth = 5f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(linePath, line)
        return bitmap
    }
}
