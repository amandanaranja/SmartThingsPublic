package com.guernseyhill.notebook.page

import android.content.Context
import android.graphics.Matrix
import android.net.Uri
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.OverScroller
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor
import com.guernseyhill.notebook.ink.Brushes
import com.guernseyhill.notebook.ink.Palette
import com.guernseyhill.notebook.ink.StrokeCodec
import com.guernseyhill.notebook.ink.Tool
import com.guernseyhill.notebook.model.DayContent
import com.guernseyhill.notebook.model.PageModel
import com.guernseyhill.notebook.model.PageStore
import com.guernseyhill.notebook.model.SampleDay
import com.guernseyhill.notebook.model.StickerModel
import com.guernseyhill.notebook.model.StrokeModel
import com.guernseyhill.notebook.sync.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One page of the notebook. The page is taller than the screen and scrolls under a finger.
 *
 *   viewport (this view)
 *     └ pageContainer, as tall as the page: paper → the day's information in its boxes → finished ink → stickers
 *     └ the live stroke, screen-sized, drawn on top and mapped into page coordinates
 *
 * The pen goes to ink. Fingers scroll, tick boxes and move stickers. That split is the whole
 * reason this is a native view: the stylus never fights the hand.
 */
class NotebookView(context: Context) : FrameLayout(context) {
    fun interface Listener { fun onStateChanged() }
    var listener: Listener? = null

    val store = PageStore(context)
    val sync = SyncManager(context)
    private val sample = SampleDay.load(context)
    private var content: DayContent? = sample
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pullJob: Job? = null
    private val pushRunnable = Runnable { pushNow() }

    var date: LocalDate = LocalDate.now()
        private set
    var page: PageModel = PageModel(date.toString())
        private set

    private val pageContainer = FrameLayout(context)
    private val paper = PaperView(context)
    private val contentLayer = ContentLayer(context)
    private val finished = FinishedStrokesView(context)
    private val stickers = StickerLayer(context)
    private val inProgress = InProgressStrokesView(context)
    private val predictor = MotionEventPredictor.newInstance(this)
    private val density = resources.displayMetrics.density

    private var pageHeight = 0
    private var offsetY = 0f
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val maxFling = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private var scrolling = false
    private var scrollPointer = -1
    private var scrollLastY = 0f
    private var scrollDownY = 0f

    var tool: Tool = Tool.PEN
        set(v) { field = v; notifyState() }
    var colorIdx: Int = 0
        set(v) { field = v; notifyState() }
    var sizeIdx: Int = 1
        set(v) { field = v; notifyState() }
    var fingerDraws: Boolean = false
        set(v) { field = v; notifyState() }
    var layoutMode: Boolean = false
        set(v) { field = v; contentLayer.layoutMode = v; stickers.deselect(); notifyState() }

    var penSeen: Boolean = false
        private set
    var lastPressure: Float = 0f
        private set
    var pressureMin: Float = 1f
        private set
    var pressureMax: Float = 0f
        private set
    private var lastMeterAt = 0L

    val canUndo: Boolean get() = undo.isNotEmpty()
    val hasSelection: Boolean get() = stickers.selected != null
    val strokeCount: Int get() = finished.strokes.size
    val hasCustomBoxes: Boolean get() = page.frames.isNotEmpty()
    fun touchedDays(): Set<String> = store.touched()

    private sealed class Undo {
        class Added(val stroke: Stroke, val model: StrokeModel) : Undo()
        class Erased(val items: List<Pair<Stroke, StrokeModel>>) : Undo()
    }

    private val undo = ArrayDeque<Undo>()
    private val pendingMeta = HashMap<InProgressStrokeId, Pair<String, Int>>()
    private var activeId: InProgressStrokeId? = null
    private var activePointer = -1
    private var erasing = false
    private val erasedNow = ArrayList<Pair<Stroke, StrokeModel>>()
    private var dirty = false
    private val saveRunnable = Runnable { persist() }

    init {
        val mp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        pageContainer.addView(paper, mp)
        pageContainer.addView(contentLayer, mp)
        pageContainer.addView(finished, mp)
        pageContainer.addView(stickers, mp)
        pageContainer.clipChildren = false
        addView(pageContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(inProgress, mp)
        inProgress.addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
            override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                for ((id, s) in strokes) {
                    val meta = pendingMeta.remove(id) ?: (Brushes.PEN to Palette.inks[0].argb)
                    val m = StrokeCodec.encode(s, meta.first, meta.second)
                    finished.add(s)
                    page.strokes += m
                    undo.addLast(Undo.Added(s, m))
                }
                inProgress.removeFinishedStrokes(strokes.keys)
                markDirty()
                notifyState()
            }
        })
        contentLayer.onChanged = { markDirty() }
        contentLayer.onHeightChanged = { h -> setPageHeight(h) }
        stickers.onChanged = { markDirty(); notifyState() }
        stickers.onDeleteRequested = { deleteSelectedSticker() }
        goTo(LocalDate.now())
    }

    private fun notifyState() { listener?.onStateChanged() }

    // ---------- the tall page ----------

    private fun setPageHeight(h: Int) {
        val target = max(h, height)
        if (target == pageHeight) return
        pageHeight = target
        val lp = pageContainer.layoutParams
        lp.height = pageHeight
        pageContainer.layoutParams = lp
        clampOffset()
    }

    private fun relayout() {
        if (width > 0) setPageHeight(contentLayer.layoutFor(width))
    }

    private fun maxOffset() = max(0f, (pageHeight - height).toFloat())

    private fun clampOffset() {
        offsetY = offsetY.coerceIn(0f, maxOffset())
        pageContainer.translationY = -offsetY
    }

    private fun scrollBy(dy: Float) {
        offsetY += dy
        clampOffset()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            offsetY = scroller.currY.toFloat()
            clampOffset()
            postInvalidateOnAnimation()
        }
    }

    // ---------- pages ----------

    fun goTo(d: LocalDate) {
        if (dirty) flush()
        scroller.forceFinished(true)
        date = d
        page = store.load(d)
        dirty = false
        undo.clear(); pendingMeta.clear(); erasedNow.clear()
        activeId = null; activePointer = -1; erasing = false
        content = if (sync.configured) sync.cachedContent(d) else sample
        contentLayer.page = page
        contentLayer.date = d
        contentLayer.content = content
        paper.set(page.paper, page.tint)
        offsetY = 0f
        if (width > 0 && height > 0) {
            relayout()
            loadInk()
            stickers.bind(page.stickers, store.photoDir)
        }
        clampOffset()
        notifyState()
        pull()
    }

    fun pull() {
        pullJob?.cancel()
        if (!sync.configured) return
        val d = date
        val localPage = page
        pullJob = scope.launch {
            val got = runCatching { sync.pullDay(d, localPage, dirty, store.photoDir) }.getOrNull()
            if (got != null && date == d) {
                content = got.content
                contentLayer.content = content
                relayout()
                val remote = got.page
                if (remote != null && !dirty) {
                    page = remote
                    store.save(page)
                    contentLayer.page = page
                    paper.set(page.paper, page.tint)
                    undo.clear()
                    if (width > 0 && height > 0) {
                        relayout()
                        loadInk()
                        stickers.bind(page.stickers, store.photoDir)
                    }
                } else if (got.photosFetched && width > 0) {
                    stickers.bind(page.stickers, store.photoDir)
                }
            }
            notifyState()
        }
    }

    private fun pushNow() {
        if (!sync.configured) return
        val p = page
        scope.launch {
            sync.pushDay(p, store.photoDir)
            notifyState()
        }
    }

    fun reconnect() {
        removeCallbacks(pushRunnable)
        goTo(date)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            relayout()
            loadInk()
            stickers.bind(page.stickers, store.photoDir)
            clampOffset()
        }
    }

    private fun loadInk() {
        val scale = if (page.pageW > 0f) width / page.pageW else 1f
        if (abs(scale - 1f) > 0.001f) {
            page.strokes = page.strokes.map { StrokeCodec.rescale(it, scale) }.toMutableList()
            if (page.strokes.isNotEmpty()) markDirty()
        }
        page.pageW = width.toFloat()
        page.pageH = pageHeight.toFloat()
        finished.setAll(page.strokes.map { StrokeCodec.decode(it) })
    }

    private fun markDirty() {
        dirty = true
        removeCallbacks(saveRunnable)
        postDelayed(saveRunnable, 500)
    }

    private fun persist() {
        if (!dirty) return
        if (width > 0) { page.pageW = width.toFloat(); page.pageH = pageHeight.toFloat() }
        store.save(page)
        dirty = false
        if (sync.configured) {
            removeCallbacks(pushRunnable)
            postDelayed(pushRunnable, 2500)
        }
    }

    fun flush() {
        removeCallbacks(saveRunnable)
        persist()
    }

    // ---------- touch: pen to ink, fingers to the page ----------

    private fun penLike(ev: MotionEvent, idx: Int): Boolean {
        val t = ev.getToolType(idx)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
    }

    private fun wantsInk(ev: MotionEvent): Boolean {
        if (layoutMode || tool == Tool.HAND) return false
        if (activeId != null || erasing) return true
        val idx = ev.actionIndex
        return penLike(ev, idx) || (fingerDraws && ev.getToolType(idx) == MotionEvent.TOOL_TYPE_FINGER)
    }

    private fun isEraser(ev: MotionEvent, idx: Int): Boolean =
        tool == Tool.ERASER ||
            ev.getToolType(idx) == MotionEvent.TOOL_TYPE_ERASER ||
            (ev.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (wantsInk(ev)) return true
        // a finger that moves vertically scrolls the page, unless a child asked to keep it
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                scrolling = false
                scrollPointer = ev.getPointerId(0)
                scrollDownY = ev.y
                scrollLastY = ev.y
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(ev)
                if (!scrolling && abs(ev.y - scrollDownY) > touchSlop) {
                    scrolling = true
                    scrollLastY = ev.y
                    return true
                }
                return scrolling
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { scrolling = false; return false }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (wantsInk(ev)) { handleInk(ev); return true }
        // nothing underneath took the finger, so it is ours: scroll
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                scrolling = false
                scrollPointer = ev.getPointerId(0)
                scrollDownY = ev.y; scrollLastY = ev.y
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
                stickers.deselect()
            }
            MotionEvent.ACTION_MOVE -> {
                val idx = ev.findPointerIndex(scrollPointer).takeIf { it >= 0 } ?: 0
                velocity?.addMovement(ev)
                val y = ev.getY(idx)
                if (!scrolling && abs(y - scrollDownY) > touchSlop) { scrolling = true; scrollLastY = y }
                if (scrolling) { scrollBy(scrollLastY - y); scrollLastY = y }
            }
            MotionEvent.ACTION_UP -> {
                val v = velocity
                if (scrolling && v != null) {
                    v.computeCurrentVelocity(1000, maxFling.toFloat())
                    val vy = -v.yVelocity
                    if (abs(vy) > 200f) {
                        scroller.fling(0, offsetY.roundToInt(), 0, vy.roundToInt(), 0, 0, 0, maxOffset().roundToInt())
                        postInvalidateOnAnimation()
                    }
                }
                velocity?.recycle(); velocity = null
                scrolling = false
            }
            MotionEvent.ACTION_CANCEL -> { velocity?.recycle(); velocity = null; scrolling = false }
        }
        return true
    }

    /** Screen (viewport) coordinates to page coordinates: only a vertical offset, since nothing zooms yet. */
    private fun pageY(y: Float) = y + offsetY

    private fun handleInk(ev: MotionEvent) {
        requestUnbufferedDispatch(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = ev.actionIndex
                if (activeId != null || erasing) return
                if (!(penLike(ev, idx) || (fingerDraws && ev.getToolType(idx) == MotionEvent.TOOL_TYPE_FINGER))) return
                activePointer = ev.getPointerId(idx)
                stickers.deselect()
                if (isEraser(ev, idx)) {
                    erasing = true
                    eraseAt(ev.getX(idx), pageY(ev.getY(idx)))
                    return
                }
                val name = Brushes.nameFor(tool)
                val color = Palette.inks[colorIdx.coerceIn(0, Palette.inks.lastIndex)].argb
                val brush = Brushes.make(name, color, Brushes.sizePx(name, sizeIdx, density))
                predictor.record(ev)
                val toWorld = Matrix().apply { postTranslate(0f, offsetY) }
                val id = inProgress.startStroke(ev, activePointer, brush, toWorld, Matrix())
                activeId = id
                pendingMeta[id] = name to color
            }
            MotionEvent.ACTION_MOVE -> {
                val idx = ev.findPointerIndex(activePointer)
                if (idx < 0) return
                if (erasing) {
                    for (h in 0 until ev.historySize) eraseAt(ev.getHistoricalX(idx, h), pageY(ev.getHistoricalY(idx, h)))
                    eraseAt(ev.getX(idx), pageY(ev.getY(idx)))
                    return
                }
                val id = activeId ?: return
                if (penLike(ev, idx)) meter(ev.getPressure(idx))
                predictor.record(ev)
                val predicted = predictor.predict()
                inProgress.addToStroke(ev, activePointer, id, predicted)
                predicted?.recycle()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = ev.actionIndex
                if (ev.getPointerId(idx) != activePointer) return
                if (erasing) finishErase() else activeId?.let { inProgress.finishStroke(ev, activePointer, it) }
                activeId = null
                activePointer = -1
                erasing = false
            }
            MotionEvent.ACTION_CANCEL -> {
                activeId?.let { inProgress.cancelStroke(it, ev) }
                if (erasing) finishErase()
                activeId = null
                activePointer = -1
                erasing = false
            }
        }
    }

    private fun meter(p: Float) {
        penSeen = true
        lastPressure = p
        if (p < pressureMin) pressureMin = p
        if (p > pressureMax) pressureMax = p
        val now = System.currentTimeMillis()
        if (now - lastMeterAt > 80) { lastMeterAt = now; notifyState() }
    }

    // ---------- the eraser (page coordinates) ----------

    private fun eraseAt(x: Float, y: Float) {
        val r = 14 * density
        var changed = false
        for (i in finished.strokes.indices.reversed()) {
            val s = finished.strokes[i]
            val bb = s.shape.computeBoundingBox()
            if (bb != null && (x < bb.xMin - r || x > bb.xMax + r || y < bb.yMin - r || y > bb.yMax + r)) continue
            if (!touches(s, x, y, r)) continue
            finished.strokes.removeAt(i)
            val m = page.strokes.getOrNull(i)
            if (m != null) {
                page.strokes.removeAt(i)
                erasedNow += s to m
            }
            changed = true
        }
        if (changed) {
            finished.invalidate()
            markDirty()
        }
    }

    private fun touches(s: Stroke, x: Float, y: Float, r: Float): Boolean {
        val inputs = s.inputs
        for (i in 0 until inputs.size) {
            val p = inputs.get(i)
            if (hypot(p.x - x, p.y - y) <= r) return true
        }
        return false
    }

    private fun finishErase() {
        if (erasedNow.isEmpty()) return
        undo.addLast(Undo.Erased(erasedNow.toList()))
        erasedNow.clear()
        markDirty()
        notifyState()
    }

    fun undo() {
        val a = undo.removeLastOrNull() ?: return
        when (a) {
            is Undo.Added -> {
                finished.remove(a.stroke)
                page.strokes.remove(a.model)
            }
            is Undo.Erased -> {
                for ((s, m) in a.items) { finished.strokes += s; page.strokes += m }
                finished.invalidate()
            }
        }
        markDirty()
        notifyState()
    }

    fun clearInk() {
        if (finished.strokes.isEmpty()) return
        undo.addLast(Undo.Erased(finished.strokes.zip(page.strokes)))
        finished.clear()
        page.strokes.clear()
        markDirty()
        notifyState()
    }

    // ---------- her decorations (page-width fractions; y of the middle of the screen she is looking at) ----------

    private fun newId() = UUID.randomUUID().toString()
    private fun midY(): Float = if (width > 0) (offsetY + height / 2f) / width else 0.5f

    fun addEmoji(e: String) {
        val m = StickerModel(newId(), "emoji", 0.5f, midY(), rotation = (-12..12).random().toFloat(), text = e)
        page.stickers += m
        stickers.add(m, store.photoDir)
    }

    fun addNote() {
        val n = page.stickers.count { it.kind == "note" }
        val m = StickerModel(newId(), "note", 0.5f, midY(), rotation = (-3..3).random().toFloat(), tint = n % 5)
        page.stickers += m
        stickers.add(m, store.photoDir)
    }

    fun addWashi() {
        val n = page.stickers.count { it.kind == "washi" }
        val m = StickerModel(newId(), "washi", 0.5f, midY(), rotation = (-25..25).random().toFloat(), tint = n % 6)
        page.stickers += m
        stickers.add(m, store.photoDir)
    }

    fun addPhoto(uri: Uri) {
        val name = newId() + ".jpg"
        val dest = File(store.photoDir, name)
        val ok = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } } != null
        }.getOrDefault(false)
        if (!ok) return
        val m = StickerModel(newId(), "photo", 0.5f, midY(), rotation = (-6..6).random().toFloat(), text = name)
        page.stickers += m
        stickers.add(m, store.photoDir)
    }

    fun deleteSelectedSticker() {
        stickers.removeSelected(page.stickers)
        markDirty()
        notifyState()
    }

    fun resetBoxes() { contentLayer.resetBoxes(); notifyState() }
    fun setPaper(style: String) { page.paper = style; paper.set(page.paper, page.tint); markDirty(); notifyState() }
    fun setTint(tint: String) { page.tint = tint; paper.set(page.paper, page.tint); markDirty(); notifyState() }
    fun setDateFont(name: String) { page.dateFont = name; contentLayer.invalidate(); markDirty(); notifyState() }
    fun setContentFont(name: String) { page.contentFont = name; contentLayer.page = page; relayout(); markDirty(); notifyState() }
}
