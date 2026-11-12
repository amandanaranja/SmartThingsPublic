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
import com.guernseyhill.notebook.model.PageModel
import com.guernseyhill.notebook.model.Section
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * My layer: the day's information poured into boxes on a tall page.
 *
 * Boxes she has not touched are laid out automatically, sized to what is in them, in one or two
 * columns. Boxes she has dragged or resized are pinned (stored on the page) and the rest flow
 * around them. In Boxes mode she can move, resize, or hide any box; hidden boxes free the paper
 * for her own lettering. Outside Boxes mode the only thing this layer reacts to is a tap on a tick box.
 */
class ContentLayer(context: Context) : View(context) {
    var content: DayContent? = null
        set(v) { field = v; stale = true; invalidate() }
    var page: PageModel? = null
        set(v) { field = v; stale = true; invalidate() }
    var date: LocalDate = LocalDate.now()
        set(v) { field = v; invalidate() }
    var layoutMode: Boolean = false
        set(v) { field = v; invalidate() }
    var onChanged: (() -> Unit)? = null
    /** Fires when the laid-out page needs a different height than before. */
    var onHeightChanged: ((Int) -> Unit)? = null

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
    private val chipFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = faint }

    // ---------- blocks and their boxes ----------

    private class Block(val key: String, val title: String, val kind: String, val section: Section?)

    private class Placed(val block: Block, val rect: RectF, val pinned: Boolean, val visible: Boolean)

    private var blocks: List<Block> = emptyList()
    private var placed: List<Placed> = emptyList()
    private var stale = true
    private var laidOutWidth = 0
    private var pageHeight = 0

    private class CheckHit(val rect: RectF, val key: String)
    private val checkHits = ArrayList<CheckHit>()
    private var pendingCheck: String? = null

    private fun pad() = 12 * d
    private fun margin() = 14 * d
    private fun gutter() = 12 * d

    private fun slug(title: String): String {
        val s = title.replace(Regex("[^A-Za-z0-9 ]"), "").trim().lowercase(Locale.US).replace(Regex("\\s+"), "-")
        return if (s.isEmpty()) "box" else s
    }

    private fun buildBlocks(c: DayContent): List<Block> {
        val out = ArrayList<Block>()
        out += Block("date", "Date", "date", null)
        if (c.greeting.isNotBlank()) out += Block("greeting", "Greeting", "greeting", null)
        if (c.alerts.isNotEmpty()) out += Block("alerts", "Alerts", "alerts", null)
        if (c.agenda.isNotEmpty()) out += Block("agenda", "Agenda", "agenda", null)
        val seen = HashMap<String, Int>()
        for (s in c.sections) {
            if (s.isPlaceholder) continue
            var key = "s-" + slug(s.title)
            val n = (seen[key] ?: 0) + 1
            seen[key] = n
            if (n > 1) key = "$key-$n"
            out += Block(key, s.title, s.kind, s)
        }
        return out
    }

    /** Lays the page out for this width and returns the height it needs. Cheap when nothing changed. */
    fun layoutFor(width: Int): Int {
        if (width <= 0) return 0
        val p = page
        val c = content ?: empty
        if (!stale && width == laidOutWidth) return pageHeight
        laidOutWidth = width
        stale = false
        blocks = buildBlocks(c)
        val W = width.toFloat()
        val cols = if (W / d >= 700f) 2 else 1
        val colW = (W - 2 * margin() - (cols - 1) * gutter()) / cols
        val custom = p?.frames?.associateBy { it.key } ?: emptyMap()
        val pinnedRects = ArrayList<RectF>()
        val result = ArrayList<Placed>()

        // pinned boxes first: they are obstacles for everything else
        for (b in blocks) {
            val f = custom[b.key] ?: continue
            if (!f.pinned) continue
            val r = RectF(f.x * W, f.y * W, (f.x + f.w) * W, (f.y + f.h) * W)
            if (f.visible) pinnedRects += r
            result += Placed(b, r, pinned = true, visible = f.visible)
        }

        // the rest flow: full-width rows for the top of the page, then columns
        val colBottom = FloatArray(cols) { margin() }
        var y = margin()
        for (b in blocks) {
            val f = custom[b.key]
            if (f != null && f.pinned) continue
            val visible = f?.visible ?: true
            val fullWidth = b.kind == "date" || b.kind == "greeting" || b.kind == "alerts"
            val w = if (fullWidth) W - 2 * margin() else colW
            val h = if (visible) measure(b, w, p, c) else 22 * d
            if (fullWidth) {
                // everything so far must be above this row
                y = max(y, colBottom.max())
                val r = RectF(margin(), y, margin() + w, y + h)
                avoid(r, pinnedRects)
                result += Placed(b, r, pinned = false, visible = visible)
                y = r.bottom + gutter()
                for (i in colBottom.indices) colBottom[i] = y
            } else {
                var col = 0
                for (i in colBottom.indices) if (colBottom[i] < colBottom[col]) col = i
                val x = margin() + col * (colW + gutter())
                val r = RectF(x, colBottom[col], x + w, colBottom[col] + h)
                avoid(r, pinnedRects)
                result += Placed(b, r, pinned = false, visible = visible)
                colBottom[col] = r.bottom + gutter()
            }
        }
        var bottom = colBottom.max()
        for (pl in result) if (pl.pinned) bottom = max(bottom, pl.rect.bottom + gutter())
        placed = result
        val h = (bottom + W * 0.55f).toInt()   // room at the foot of every page for her own things
        if (h != pageHeight) { pageHeight = h; onHeightChanged?.invoke(h) }
        return pageHeight
    }

    /** Push a box down until it no longer overlaps any pinned box. */
    private fun avoid(r: RectF, obstacles: List<RectF>) {
        var moved = true
        var guard = 0
        while (moved && guard++ < 40) {
            moved = false
            for (o in obstacles) {
                if (RectF.intersects(r, o)) {
                    val dy = o.bottom + gutter() - r.top
                    r.offset(0f, dy)
                    moved = true
                }
            }
        }
    }

    private val empty = DayContent(greeting = "Nothing written for this day yet.")

    private fun measure(b: Block, w: Float, p: PageModel?, c: DayContent): Float {
        val r = RectF(0f, 0f, w, 100000f)
        return drawBlock(null, b, r, p, c) + pad()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != laidOutWidth) stale = true
    }

    // ---------- drawing ----------

    override fun onDraw(canvas: Canvas) {
        val p = page ?: return
        val c = content ?: empty
        layoutFor(width)
        checkHits.clear()
        for (pl in placed) {
            val r = pl.rect
            if (!pl.visible && !layoutMode) continue
            if (layoutMode) {
                frameStroke.color = if (pl.pinned) accent else 0x55000000
                frameStroke.pathEffect = DashPathEffect(floatArrayOf(6 * d, 5 * d), 0f)
                canvas.drawRoundRect(r, 10 * d, 10 * d, frameStroke)
                mono.color = if (pl.pinned) accent else muted
                val tag = (if (!pl.visible) "(hidden) " else "") + pl.block.title.replace(Regex("[^A-Za-z0-9 /]"), "").trim().uppercase(Locale.US)
                canvas.drawText(tag, r.left + 10 * d, r.top + 13 * d, mono)
                mono.color = faint
                canvas.drawCircle(r.right, r.bottom, 9 * d, handle)
                if (!pl.visible) continue
            } else {
                frameStroke.color = 0x12000000
                frameStroke.pathEffect = null
                canvas.drawRoundRect(r, 10 * d, 10 * d, frameStroke)
            }
            canvas.save()
            canvas.clipRect(r)
            drawBlock(canvas, pl.block, r, p, c)
            canvas.restore()
        }
    }

    /** Draws a block into r (or only measures it when canvas is null). Returns the height used. */
    private fun drawBlock(canvas: Canvas?, b: Block, r: RectF, p: PageModel?, c: DayContent): Float {
        val cf = p?.contentFont ?: "plex_sans"
        return when (b.kind) {
            "date" -> drawDate(canvas, r, p)
            "greeting" -> drawGreeting(canvas, r, cf, c)
            "alerts" -> drawAlerts(canvas, r, cf, c)
            "agenda" -> drawAgenda(canvas, r, cf, c)
            "schedule" -> drawSchedule(canvas, r, cf, b)
            "prose" -> drawProse(canvas, r, cf, b)
            "freeform" -> drawFreeform(canvas, r, b)
            else -> drawList(canvas, r, cf, b, p)
        }
    }

    private fun drawDate(canvas: Canvas?, r: RectF, p: PageModel?): Float {
        val h = r.width() * 0.11f
        if (canvas == null) return h
        val tf = fonts.get(p?.dateFont ?: "caveat")
        val weekday = date.format(DateTimeFormatter.ofPattern("EEEE", Locale.US))
        val monthDay = date.format(DateTimeFormatter.ofPattern("MMMM d", Locale.US))
        text.typeface = tf
        text.color = ink
        text.letterSpacing = 0f
        var size = h * 0.62f
        text.textSize = size
        while (text.measureText(monthDay) > r.width() * 0.6f && size > 12 * d) { size *= 0.92f; text.textSize = size }
        canvas.drawText(monthDay, r.left + pad(), r.top + h * 0.78f, text)
        text.textSize = max(size * 0.34f, 11 * d)
        text.color = muted
        canvas.drawText(weekday, r.left + pad() + text.measureText(monthDay) * 0f, r.top + pad() + text.textSize * 0.95f, text)
        // weekday sits right of the big date
        return h
    }

    private fun drawGreeting(canvas: Canvas?, r: RectF, cf: String, c: DayContent): Float {
        text.typeface = fonts.get(if (cf == "plex_sans") "newsreader" else cf)
        text.textSize = 16f * d
        text.color = muted
        text.letterSpacing = 0f
        text.isStrikeThruText = false
        return pad() * 0.6f + para(canvas, c.greeting, r.left + pad(), r.top + pad() * 0.8f, r.width() - 2 * pad(), 6)
    }

    private fun drawAlerts(canvas: Canvas?, r: RectF, cf: String, c: DayContent): Float {
        text.typeface = fonts.get(cf)
        text.textSize = 13f * d
        text.letterSpacing = 0f
        text.isStrikeThruText = false
        var x = r.left + pad() * 0.7f
        var y = r.top + pad() * 0.7f
        val chipH = 28 * d
        val maxX = r.right - pad() * 0.7f
        for (a in c.alerts) {
            val w = text.measureText(a.text) + 26 * d
            if (x + w > maxX && x > r.left + pad()) { x = r.left + pad() * 0.7f; y += chipH + 6 * d }
            if (canvas != null) {
                val (fill, fg) = when (a.tone) {
                    "alert" -> 0xFFF7E5DB.toInt() to 0xFFA2431E.toInt()
                    "warn" -> 0xFFF3EAD5.toInt() to 0xFF8A6510.toInt()
                    "good" -> 0xFFDBE9E3.toInt() to accent
                    else -> 0xFFE9ECE5.toInt() to body
                }
                chipFill.color = fill
                val rr = RectF(x, y, x + w, y + chipH)
                canvas.drawRoundRect(rr, chipH / 2, chipH / 2, chipFill)
                dot.color = fg
                canvas.drawCircle(x + 11 * d, y + chipH / 2, 3.5f * d, dot)
                text.color = fg
                canvas.drawText(a.text, x + 19 * d, y + chipH / 2 + text.textSize * 0.35f, text)
            }
            x += w + 6 * d
        }
        return (y + chipH + pad() * 0.7f) - r.top
    }

    private fun drawAgenda(canvas: Canvas?, r: RectF, cf: String, c: DayContent): Float {
        var y = heading(canvas, r, "TODAY, TIMED")
        text.typeface = fonts.get(cf)
        text.letterSpacing = 0f
        text.isStrikeThruText = false
        val timeW = 52 * d
        for (a in c.agenda) {
            text.textSize = 13 * d
            text.color = accent
            canvas?.drawText(a.at, r.left + pad(), y + 13 * d, text)
            text.textSize = 15 * d
            text.color = ink
            val h = para(canvas, a.what, r.left + pad() + timeW, y, r.width() - 2 * pad() - timeW, 4)
            y += max(h, 16 * d) + 8 * d
            hairline(canvas, r, y - 4 * d)
        }
        return y - r.top
    }

    private fun drawProse(canvas: Canvas?, r: RectF, cf: String, b: Block): Float {
        var y = heading(canvas, r, b.title)
        text.typeface = fonts.get(cf)
        text.textSize = 14.5f * d
        text.color = body
        text.letterSpacing = 0f
        text.isStrikeThruText = false
        val items = b.section?.groups?.flatMap { it.items } ?: emptyList()
        if (items.isEmpty()) y += blankRow(canvas, r, y)
        for (it in items) {
            y += para(canvas, it.text, r.left + pad(), y, r.width() - 2 * pad(), 40) + 6 * d
        }
        return y - r.top
    }

    private fun drawList(canvas: Canvas?, r: RectF, cf: String, b: Block, p: PageModel?): Float {
        var y = heading(canvas, r, b.title)
        text.typeface = fonts.get(cf)
        text.letterSpacing = 0f
        val boxSize = 17 * d
        val groups = b.section?.groups ?: emptyList()
        var any = false
        for (g in groups) {
            if (!g.label.isNullOrBlank()) {
                mono.color = if (g.label == "Today") muted else ink
                mono.textSize = 10.5f * d
                canvas?.drawText(g.label.uppercase(Locale.US), r.left + pad(), y + 10 * d, mono)
                mono.textSize = 9.5f * d
                y += 18 * d
                if (g.items.isEmpty()) { y += blankRow(canvas, r, y) }
            }
            for (it in g.items) {
                any = true
                val key = it.code ?: it.text.hashCode().toString()
                val done = p?.checks?.get(key) == true
                val tickable = !it.k.isNullOrBlank()
                val left = r.left + pad()
                if (tickable) {
                    val br = RectF(left, y + 2 * d, left + boxSize, y + 2 * d + boxSize)
                    if (canvas != null) {
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
                    }
                } else if (canvas != null) {
                    dot.color = faint
                    canvas.drawCircle(left + boxSize / 2, y + 2 * d + boxSize / 2, 2.5f * d, dot)
                }
                text.textSize = 15 * d
                text.color = if (done) faint else body
                text.isStrikeThruText = done
                val h = para(canvas, it.text, left + boxSize + 10 * d, y, r.right - pad() - left - boxSize - 10 * d, 8)
                text.isStrikeThruText = false
                y += max(h, boxSize) + 8 * d
            }
            if (!g.label.isNullOrBlank()) y += 4 * d
        }
        if (!any && groups.all { it.label.isNullOrBlank() }) y += blankRow(canvas, r, y)
        return y - r.top
    }

    private fun drawSchedule(canvas: Canvas?, r: RectF, cf: String, b: Block): Float {
        var y = heading(canvas, r, b.title)
        text.typeface = fonts.get(cf)
        text.letterSpacing = 0f
        text.isStrikeThruText = false
        val labelW = 54 * d
        val emptyH = r.width() * 0.085f
        for (row in b.section?.rows ?: emptyList()) {
            val filled = row.body.isNotBlank()
            mono.color = if (filled) accent else faint
            mono.textSize = 11 * d
            canvas?.drawText(row.label, r.left + pad(), y + 13 * d, mono)
            mono.textSize = 9.5f * d
            var h = emptyH
            if (filled) {
                text.textSize = 14.5f * d
                text.color = body
                h = max(h, para(canvas, row.body, r.left + pad() + labelW, y + 1 * d, r.width() - 2 * pad() - labelW, 12) + 8 * d)
            }
            y += h
            hairline(canvas, r, y - 3 * d)
        }
        return y - r.top
    }

    private fun drawFreeform(canvas: Canvas?, r: RectF, b: Block): Float {
        val y = heading(canvas, r, b.title)
        return (y - r.top) + r.width() * 0.26f
    }

    /** One empty, ruled line to write on. */
    private fun blankRow(canvas: Canvas?, r: RectF, y: Float): Float {
        val h = r.width() * 0.09f
        hairline(canvas, r, y + h - 3 * d)
        return h
    }

    /** Section label in the Day Book's small caps, with its rule. Returns the y where content starts. */
    private fun heading(canvas: Canvas?, r: RectF, label: String): Float {
        if (canvas != null) {
            mono.color = ink
            mono.textSize = 10.5f * d
            mono.typeface = Typeface.MONOSPACE
            canvas.drawText(label.uppercase(Locale.US), r.left + pad(), r.top + pad() + 9 * d, mono)
            mono.textSize = 9.5f * d
            hairline(canvas, r, r.top + pad() + 16 * d)
        }
        return r.top + pad() + 24 * d
    }

    private fun hairline(canvas: Canvas?, r: RectF, y: Float) {
        if (canvas == null) return
        frameStroke.color = 0x16000000
        frameStroke.pathEffect = null
        canvas.drawLine(r.left + pad(), y, r.right - pad(), y, frameStroke)
    }

    /** Wrapped text. Returns the height used. Draws nothing when canvas is null. */
    private fun para(canvas: Canvas?, s: String, x: Float, y: Float, w: Float, maxLines: Int): Float {
        if (w <= 0 || s.isEmpty()) return 0f
        val layout = StaticLayout.Builder.obtain(s, 0, s.length, text, w.toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setIncludePad(false)
            .build()
        if (canvas != null) {
            canvas.save()
            canvas.translate(x, y)
            layout.draw(canvas)
            canvas.restore()
        }
        return layout.height.toFloat()
    }

    // ---------- touch ----------

    private var dragPlaced: Placed? = null
    private var dragFrame: FrameModel? = null
    private var dragMode = 0 // 1 move, 2 resize
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false

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

    /** The stored box for a placed block, created (pinned, at its current spot) if she is about to change it. */
    private fun frameFor(pl: Placed): FrameModel {
        val p = page!!
        val W = width.toFloat()
        p.frames.firstOrNull { it.key == pl.block.key }?.let { return it }
        val f = FrameModel(pl.block.key, pl.rect.left / W, pl.rect.top / W, pl.rect.width() / W, pl.rect.height() / W, visible = pl.visible, pinned = true)
        p.frames += f
        return f
    }

    private fun layoutTouch(e: MotionEvent): Boolean {
        val W = width.toFloat()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (page == null) return false
                for (pl in placed.asReversed()) {
                    val r = pl.rect
                    if (abs(e.x - r.right) < 22 * d && abs(e.y - r.bottom) < 22 * d) {
                        dragPlaced = pl; dragMode = 2; lastX = e.x; lastY = e.y; moved = false
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
                for (pl in placed.asReversed()) {
                    if (pl.rect.contains(e.x, e.y)) {
                        dragPlaced = pl; dragMode = 1; lastX = e.x; lastY = e.y
                        downX = e.x; downY = e.y; moved = false
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val pl = dragPlaced ?: return false
                if (!moved && (abs(e.x - downX) > 6 * d || abs(e.y - downY) > 6 * d || dragMode == 2)) {
                    moved = true
                    dragFrame = frameFor(pl)
                }
                val f = dragFrame ?: return true
                val dx = (e.x - lastX) / W
                val dy = (e.y - lastY) / W
                if (dragMode == 1) {
                    f.x = (f.x + dx).coerceIn(0f, 1f - f.w)
                    f.y = (f.y + dy).coerceAtLeast(0f)
                } else {
                    f.w = (f.w + dx).coerceIn(0.14f, 1f - f.x)
                    f.h = (f.h + dy).coerceAtLeast(0.06f)
                }
                f.pinned = true
                lastX = e.x; lastY = e.y
                stale = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val pl = dragPlaced
                if (pl != null && dragMode == 1 && !moved && e.actionMasked == MotionEvent.ACTION_UP) {
                    if (e.y - pl.rect.top < 26 * d) {
                        // a tap on the label hides or shows the box; hiding keeps it where it was
                        val f = frameFor(pl)
                        f.visible = !f.visible
                        stale = true
                        invalidate()
                    }
                }
                dragPlaced = null; dragFrame = null; dragMode = 0
                if (moved || e.actionMasked == MotionEvent.ACTION_UP) onChanged?.invoke()
                return true
            }
        }
        return dragPlaced != null
    }

    /** Forget every box she moved, hid or resized on this page. */
    fun resetBoxes() {
        page?.frames?.clear()
        stale = true
        invalidate()
        onChanged?.invoke()
    }
}
