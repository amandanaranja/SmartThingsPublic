package com.guernseyhill.notebook.ink

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushTip
import androidx.ink.brush.EasingFunction
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.guernseyhill.notebook.model.StrokeModel

enum class Tool { PEN, BRUSH, MARKER, HIGHLIGHTER, ERASER, HAND }

object Palette {
    data class Ink(val name: String, val argb: Int)
    val inks = listOf(
        Ink("Ink black", 0xFF19231F.toInt()),
        Ink("Day Book green", 0xFF2E6E5D.toInt()),
        Ink("Moss", 0xFF4F6B45.toInt()),
        Ink("Brick", 0xFFB5342B.toInt()),
        Ink("Rust", 0xFFB35F19.toInt()),
        Ink("Mustard", 0xFF8A6A0E.toInt()),
        Ink("Teal", 0xFF1F777E.toInt()),
        Ink("Slate", 0xFF5C7081.toInt()),
        Ink("Violet", 0xFF7B5EA7.toInt()),
        Ink("Rose", 0xFFC23C74.toInt()),
    )
}

@OptIn(ExperimentalInkCustomBrushApi::class)
object Brushes {
    const val PEN = "pen"
    const val BRUSH = "brush"
    const val MARKER = "marker"
    const val HIGHLIGHTER = "highlighter"

    private val sizesDp = mapOf(
        PEN to floatArrayOf(1.8f, 2.8f, 4.4f),
        BRUSH to floatArrayOf(2.6f, 4.2f, 6.5f),
        MARKER to floatArrayOf(4f, 7f, 11f),
        HIGHLIGHTER to floatArrayOf(12f, 18f, 26f),
    )

    /** Width follows pressure: light touch gives lo × size, full press gives hi × size. */
    private fun pressure(lo: Float, hi: Float) = BrushBehavior(
        source = BrushBehavior.Source.NORMALIZED_PRESSURE,
        target = BrushBehavior.Target.SIZE_MULTIPLIER,
        sourceValueRangeStart = 0f,
        sourceValueRangeEnd = 1f,
        targetModifierRangeStart = lo,
        targetModifierRangeEnd = hi,
        sourceOutOfRangeBehavior = BrushBehavior.OutOfRange.CLAMP,
        responseCurve = EasingFunction.Predefined.EASE_IN_OUT,
        responseTimeMillis = 25L,
    )

    /** A fountain-pen feel: a clear difference between light and firm, without going wild. */
    private val penFamily: BrushFamily = BrushFamily(
        BrushTip(behaviors = listOf(pressure(0.45f, 1.45f), StockBrushes.predictionFadeOutBehavior)),
    )

    /** A brush pen: hairline when you barely touch, fat when you lean in. */
    private val brushFamily: BrushFamily = BrushFamily(
        BrushTip(behaviors = listOf(pressure(0.12f, 2.1f), StockBrushes.predictionFadeOutBehavior)),
    )

    fun nameFor(tool: Tool): String = when (tool) {
        Tool.BRUSH -> BRUSH
        Tool.MARKER -> MARKER
        Tool.HIGHLIGHTER -> HIGHLIGHTER
        else -> PEN
    }

    fun family(name: String): BrushFamily = when (name) {
        BRUSH -> brushFamily
        MARKER -> StockBrushes.markerLatest
        HIGHLIGHTER -> StockBrushes.highlighterLatest
        else -> penFamily
    }

    fun sizePx(name: String, idx: Int, density: Float): Float =
        (sizesDp[name] ?: sizesDp.getValue(PEN))[idx.coerceIn(0, 2)] * density

    /** Highlighter ink is translucent so the words stay readable under it. */
    fun colorFor(name: String, color: Int): Int =
        if (name == HIGHLIGHTER) (color and 0x00FFFFFF) or (0x5A shl 24) else color

    fun make(name: String, color: Int, sizePx: Float): Brush =
        Brush.createWithColorIntArgb(
            family = family(name),
            colorIntArgb = colorFor(name, color),
            size = sizePx,
            epsilon = 0.1f,
        )
}

/** Turns library strokes into something we can save, and back. */
object StrokeCodec {
    fun encode(stroke: Stroke, brushName: String, color: Int): StrokeModel {
        val inputs = stroke.inputs
        val pts = ArrayList<Float>(inputs.size * 6)
        for (i in 0 until inputs.size) {
            val p = inputs.get(i)
            pts += p.x
            pts += p.y
            pts += p.pressure
            pts += p.tiltRadians
            pts += p.orientationRadians
            pts += p.elapsedTimeMillis.toFloat()
        }
        return StrokeModel(brushName, color, stroke.brush.size, pts)
    }

    fun decode(m: StrokeModel): Stroke {
        val batch = MutableStrokeInputBatch()
        var i = 0
        while (i + 5 < m.pts.size) {
            runCatching {
                batch.add(
                    type = InputToolType.STYLUS,
                    x = m.pts[i],
                    y = m.pts[i + 1],
                    elapsedTimeMillis = m.pts[i + 5].toLong(),
                    pressure = m.pts[i + 2],
                    tiltRadians = m.pts[i + 3],
                    orientationRadians = m.pts[i + 4],
                )
            }
            i += 6
        }
        return Stroke(Brushes.make(m.brush, m.color, m.size), batch)
    }

    /** Same stroke on a page of a different width. */
    fun rescale(m: StrokeModel, s: Float): StrokeModel {
        if (s == 1f) return m
        val out = ArrayList<Float>(m.pts.size)
        var i = 0
        while (i + 5 < m.pts.size) {
            out += m.pts[i] * s
            out += m.pts[i + 1] * s
            out += m.pts[i + 2]
            out += m.pts[i + 3]
            out += m.pts[i + 4]
            out += m.pts[i + 5]
            i += 6
        }
        return StrokeModel(m.brush, m.color, m.size * s, out)
    }
}
