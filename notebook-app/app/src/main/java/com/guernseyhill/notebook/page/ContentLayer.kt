package com.guernseyhill.notebook.page

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import com.guernseyhill.notebook.model.DayContent
import com.guernseyhill.notebook.model.FrameModel
import com.guernseyhill.notebook.model.Frames
import com.guernseyhill.notebook.model.PageModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * My layer: the day's information poured into her frames.
 *
 * Normal mode: it only reacts to taps on checklist boxes. Everything else passes through.
 * Layout mode: frames can be dragged, resized from the corner, and hidden by tapping their label.
 */
class ContentLayer(context: Context) : View(context) {
    var content: DayContent? = null
    var page: PageModel? = null
    var date: LocalDate = LocalDate.now()
    var onChanged: (() -> Unit)? = null
    var layoutMode: Boolean = false
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val fonts = Fonts(context)

    private val accent = 0xFF2E6E5D.toInt()
    private val ink = 0xFF19231F.toInt()
    private val body = 0xFF333F39.toInt()
    private val muted = 0xFF6D7B73.toInt()
    private val faint = 0xFF96A29A.toInt()

    private val frameStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = d }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val mono = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE; textSize = 9.5f * d; letterSpacing = 0.12f; color = faint
    }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private val boxFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * d; color = 0xFFFBFCFA.toInt(); strokeCap = Paint.Cap.ROUND
    }
    private val chipBg = Paint(Paint.ANTI_ALIAS_FLAG)

    private class CheckHit(val rect: RectF, val key: String)
    private val checkHits = ArrayList<CheckHit>()
    private var pendingCheck: String? = null

    // layout-mode drag
    private var dragFrame: FrameModel? = null
    private var dragMode = 0 // 1 move, 2 resize
    private var lastX = 0f
    private var lastY = 0f

    fun frames(): MutableList<FrameModel> {
        val p = page ?: return mutableListOf()
        if (p.frames.isEmpty()) p.frames.addAll(Frames.defaults())
        return p.frames
    }

    private fun rect(f: FrameModel) = RectF(f.x * width, f.y * height, (f.x + f.w) * width, (f.y + f.h) * height)

    private val empty = DayContent(greeting = "Nothing written for this day yet.")

    override fun onDraw(canvas: Canvas) {
        val p = page ?: return
        val c = content ?: empty
        checkHits.clear()
        for (f in frames()) {
            if (!f.visible && !layoutMode) continue
            val r = rect(f)
            if (layoutMode) {
                frameStroke.color = accent
                frameStroke.pathEffect = DashPathEffect(floatArrayOf(6 * d, 5 * d), 0f)
                canvas.drawRoundRect(r, 10 * d, 10 * d, frameStroke)
                mono.color = accent
                canvas.drawText((if (f.visible) "" else "(hidden) ") + f.key.uppercase(Locale.US), r.left + 10 * d, r.top + 13 * d, mono)
                canvas.drawCircle(r.right, r.bottom, 9 * d, handle)
                mono.color = faint
            } else {
                frameStroke.color = 0x12000000
                frameStroke.pathEffect = null
                canvas.drawRoundRect(r, 10 * d, 10 * d, frameStroke)
            }
            if (!f.visible) continue
            canvas.save()
            canvas.clipRect(r)
            when (f.key) {
                "date" -> drawDate(canvas, r, p)
                "weather" -> drawWeather(canvas, r, p, c)
                "greeting" -> drawGreeting(canvas, r, p, c)
                "agenda" -> drawAgenda(canvas, r, p, c)
                "checklist" -> drawChecklist(canvas, r, p, c)
                "dinner" -> drawDinner(canvas, r, p, c)
            }
            canvas.restore()
        }
    }

    // ---------- pieces ----------

    private fun pad() = 12 * d

    private fun drawDate(canvas: Canvas, r: RectF, p: PageModel) {
        val tf = fonts.get(p.dateFont)
        val weekday = date.format(DateTimeFormatter.ofPattern("EEEE", Locale.US))
        val monthDay = date.format(DateTimeFormatter.ofPattern("MMMM d", Locale.US))
        text.typeface = tf
        text.color = ink
        text.letterSpacing = 0f
        // fit the big line to the box
        var size = r.height() * 0.58f
        text.textSize = size
        while (text.measureText(monthDay) > r.width() - 2 * pad() && size > 12 * d) {
            size *= 0.92f; text.textSize = size
        }
        val baseline = r.bottom - pad() - text.descent() * 0.6f
        canvas.drawText(monthDay, r.left + pad(), baseline, text)
        text.textSize = max(size * 0.34f, 11 * d)
        text.color = muted
        canvas.drawText(weekday, r.left + pad(), r.top + pad() + text.textSize * 0.9f, text)
    }

    private fun drawWeather(canvas: Canvas, r: RectF, p: PageModel, c: DayContent) {
        val w = c.weather ?: return
        text.typeface = Typeface.DEFAULT
        text.textSize = r.height() * 0.42f
        text.color = ink
        text.letterSpacing = 0f
        canvas.drawText(w.icon, r.left + pad(), r.top + pad() + text.textSize * 0.85f, text)
        val iconW = text.measureText(w.icon) + 8 * d
        text.typeface = fonts.get(p.contentFont)
        text.textSize = 20 * d
        text.color = ink
        canvas.drawText(w.high, r.left + pad() + iconW, r.top + pad() + 18 * d, text)
        text.textSize = 13 * d
        text.color = muted
        canvas.drawText(w.low, r.left + pad() + iconW + 4 * d + text.measureText(w.high) * 1.25f, r.top + pad() + 18 * d, text)
        text.textSize = 12.5f * d
        text.color = body
        para(canvas, w.summary, r.left + pad(), r.top + pad() + 28 * d, r.width() - 2 * pad(), 2)
    }

    private fun drawGreeting(canvas: Canvas, r: RectF, p: PageModel, c: DayContent) {
        text.typeface = fonts.get(if (p.contentFont == "plex_sans") "newsreader" else p.contentFont)
        text.textSize = 15.5f * d
        text.color = muted
        text.letterSpacing = 0f
        para(canvas, c.greeting, r.left + pad(), r.top + pad() * 0.8f, r.width() - 2 * pad(), 3)
    }

    private fun drawAgenda(canvas: Canvas, r: RectF, p: PageModel, c: DayContent) {
        var y = heading(canvas, r, "YOUR DAY")
        text.typeface = fonts.get(p.contentFont)
        text.letterSpacing = 0f
        for (a in c.agenda) {
            if (y > r.bottom - 10 * d) break
            text.textSize = 13 * d
            text.color = accent
            canvas.drawText(a.time, r.left + pad(), y + 13 * d, text)
            val timeW = 46 * d
            text.textSize = 15 * d
            text.color = ink
            var h = para(canvas, a.text, r.left + pad() + timeW, y, r.width() - 2 * pad() - timeW, 2)
            if (a.sub.isNotBlank()) {
                text.textSize = 12 * d
                text.color = muted
                h += para(canvas, a.sub, r.left + pad() + timeW, y + h, r.width() - 2 * pad() - timeW, 1)
            }
            y += h + 9 * d
            hairline(canvas, r, y - 4 * d)
        }
    }

    private fun drawChecklist(canvas: Canvas, r: RectF, p: PageModel, c: DayContent) {
        var y = heading(canvas, r, "CHECKLIST")
        text.typeface = fonts.get(p.contentFont)
        text.letterSpacing = 0f
        val boxSize = 17 * d
        for ((group, items) in c.checklist) {
            if (y > r.bottom - 20 * d) break
            mono.color = if (group == "Today") muted else ink
            canvas.drawText(group.uppercase(Locale.US), r.left + pad(), y + 10 * d, mono)
            y += 18 * d
            items.forEachIndexed { i, item ->
                if (y > r.bottom - 10 * d) return@forEachIndexed
                val key = "$group|$i"
                val done = p.checks[key] == true
                val br = RectF(r.left + pad(), y + 2 * d, r.left + pad() + boxSize, y + 2 * d + boxSize)
                box.color = if (done) accent else 0xFFC9D1C8.toInt()
                if (done) {
                    canvas.drawRoundRect(br, 4 * d, 4 * d, boxFill)
                    canvas.drawLine(br.left + 4 * d, br.centerY(), br.left + 7.5f * d, br.bottom - 4.5f * d, tick)
                    canvas.drawLine(br.left + 7.5f * d, br.bottom - 4.5f * d, br.right - 3.5f * d, br.top + 4.5f * d, tick)
                } else {
                    canvas.drawRoundRect(br, 4 * d, 4 * d, box)
                }
                val hit = RectF(br); hit.inset(-10 * d, -8 * d)
                checkHits += CheckHit(hit, key)
                text.textSize = 15 * d
                text.color = if (done) faint else body
                text.isStrikeThruText = done
                val h = para(canvas, item, br.right + 10 * d, y, r.right - pad() - br.right - 10 * d, 3)
                text.isStrikeThruText = false
                y += max(h, boxSize) + 8 * d
            }
            y += 6 * d
        }
    }

    private fun drawDinner(canvas: Canvas, r: RectF, p: PageModel, c: DayContent) {
        val dn = c.dinner ?: return
        var y = heading(canvas, r, "DINNER")
        text.typeface = fonts.get(p.contentFont)
        text.letterSpacing = 0f
        text.textSize = 15 * d
        text.color = ink
        y += para(canvas, dn.line, r.left + pad(), y, r.width() - 2 * pad(), 3) + 6 * d
        if (dn.tomorrow.isNotBlank()) {
            text.textSize = 12.5f * d
            text.color = muted
            para(canvas, "Tomorrow: " + dn.tomorrow, r.left + pad(), y, r.width() - 2 * pad(), 2)
        }
    }

    /** Section label in the Day Book's small caps, with its rule. Returns the y where content starts. */
    private fun heading(canvas: Canvas, r: RectF, label: String): Float {
        mono.color = ink
        mono.textSize = 10.5f * d
        canvas.drawText(label, r.left + pad(), r.top + pad() + 9 * d, mono)
        mono.textSize = 9.5f * d
        hairline(canvas, r, r.top + pad() + 16 * d)
        return r.top + pad() + 24 * d
    }

    private fun hairline(canvas: Canvas, r: RectF, y: Float) {
        frameStroke.color = 0x16000000
        frameStroke.pathEffect = null
        canvas.drawLine(r.left + pad(), y, r.right - pad(), y, frameStroke)
    }

    /** Wrapped text. Returns the height used. */
    private fun para(canvas: Canvas, s: String, x: Float, y: Float, w: Float, maxLines: Int): Float {
        if (w <= 0 || s.isEmpty()) return 0f
        val layout = StaticLayout.Builder.obtain(s, 0, s.length, text, w.toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setIncludePad(false)
            .build()
        canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()
        return layout.height.toFloat()
    }

    // ---------- touch ----------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (layoutMode) return layoutTouch(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val k = hitCheck(e.x, e.y) ?: return false
                pendingCheck = k
                return true
            }
            MotionEvent.ACTION_UP -> {
                val k = pendingCheck
                pendingCheck = null
                if (k != null && hitCheck(e.x, e.y) == k) {
                    val p = page ?: return true
                    p.checks[k] = !(p.checks[k] ?: false)
                    invalidate()
                    onChanged?.invoke()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> pendingCheck = null
        }
        return pendingCheck != null
    }

    private fun hitCheck(x: Float, y: Float): String? = checkHits.firstOrNull { it.rect.contains(x, y) }?.key

    private fun layoutTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val fs = frames()
                // corner handles first, topmost frame last in the list wins
                for (f in fs.asReversed()) {
                    val r = rect(f)
                    if (abs(e.x - r.right) < 22 * d && abs(e.y - r.bottom) < 22 * d) {
                        dragFrame = f; dragMode = 2; lastX = e.x; lastY = e.y; return true
                    }
                }
                for (f in fs.asReversed()) {
                    val r = rect(f)
                    if (r.contains(e.x, e.y)) {
                        // tapping the label strip toggles visibility; decided on UP if the finger did not move
                        dragFrame = f; dragMode = 1; lastX = e.x; lastY = e.y
                        downX = e.x; downY = e.y; moved = false
                        return true
                    }
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val f = dragFrame ?: return false
                val dx = (e.x - lastX) / width
                val dy = (e.y - lastY) / height
                if (abs(e.x - downX) > 6 * d || abs(e.y - downY) > 6 * d) moved = true
                if (dragMode == 1) {
                    f.x = (f.x + dx).coerceIn(0f, 1f - f.w)
                    f.y = (f.y + dy).coerceIn(0f, 1f - f.h)
                } else {
                    f.w = (f.w + dx).coerceIn(0.12f, 1f - f.x)
                    f.h = (f.h + dy).coerceIn(0.05f, 1f - f.y)
                }
                lastX = e.x; lastY = e.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val f = dragFrame
                if (f != null && dragMode == 1 && !moved && e.actionMasked == MotionEvent.ACTION_UP) {
                    val r = rect(f)
                    if (e.y - r.top < 24 * d) { f.visible = !f.visible; invalidate() }
                }
                dragFrame = null; dragMode = 0
                onChanged?.invoke()
                return true
            }
        }
        return dragFrame != null
    }

    private var downX = 0f
    private var downY = 0f
    private var moved = false
}
