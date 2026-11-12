package com.guernseyhill.notebook.page

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

object Paper {
    val styles = listOf("plain", "dotted", "grid", "lined")
    val tints = listOf("sage", "cream", "white", "blush", "sky")
    fun tintColor(name: String): Int = when (name) {
        "cream" -> 0xFFF7F2E6
        "white" -> 0xFFFDFDFC
        "blush" -> 0xFFF6ECEA
        "sky" -> 0xFFEAF0F4
        else -> 0xFFEFF1EC
    }.toInt()
}

/** The paper itself: a tint and a faint dot, grid or line pattern. */
class PaperView(context: Context) : View(context) {
    private var style = "dotted"
    private var tint = "sage"
    private val d = resources.displayMetrics.density
    private val spacing = 26f * d
    private val bg = Paint()
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = d }

    fun set(style: String, tint: String) {
        this.style = style
        this.tint = tint
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        bg.color = Paper.tintColor(tint)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        mark.color = 0x22000000
        val w = width.toFloat()
        val h = height.toFloat()
        val top = spacing * 1.5f
        when (style) {
            "dotted" -> {
                var y = top
                while (y < h) {
                    var x = spacing
                    while (x < w) { canvas.drawCircle(x, y, 1.4f * d, mark); x += spacing }
                    y += spacing
                }
            }
            "grid" -> {
                var y = top
                while (y < h) { canvas.drawLine(0f, y, w, y, mark); y += spacing }
                var x = spacing
                while (x < w) { canvas.drawLine(x, 0f, x, h, mark); x += spacing }
            }
            "lined" -> {
                var y = top
                while (y < h) { canvas.drawLine(spacing, y, w - spacing, y, mark); y += spacing }
            }
        }
    }
}
