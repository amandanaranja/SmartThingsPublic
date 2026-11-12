package com.guernseyhill.notebook.page

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.view.View
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke

/** Ink that has already been laid down. Drawn with the Ink library's renderer so it matches the live stroke exactly. */
class FinishedStrokesView(context: Context) : View(context) {
    val strokes = ArrayList<Stroke>()
    private val renderer = CanvasStrokeRenderer.create()
    private val identity = Matrix()

    override fun onDraw(canvas: Canvas) {
        for (s in strokes) renderer.draw(canvas, s, identity)
    }

    fun add(s: Stroke) { strokes += s; invalidate() }
    fun addAll(list: Collection<Stroke>) { strokes.addAll(list); invalidate() }
    fun remove(s: Stroke) { strokes.remove(s); invalidate() }
    fun setAll(list: Collection<Stroke>) { strokes.clear(); strokes.addAll(list); invalidate() }
    fun clear() { strokes.clear(); invalidate() }
}
