package com.guernseyhill.notebook.page

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.exifinterface.media.ExifInterface
import com.guernseyhill.notebook.model.StickerModel
import java.io.File
import kotlin.math.atan2
import kotlin.math.hypot

object NoteColors {
    val fills = intArrayOf(0xFFFAEFC2.toInt(), 0xFFD8EEDF.toInt(), 0xFFE9E1F6.toInt(), 0xFFFBE0E6.toInt(), 0xFFDDEBF6.toInt())
    const val ink = 0xFF2A2418.toInt()
}

object WashiColors {
    val fills = intArrayOf(0xFFB8D8C8.toInt(), 0xFFF2C6C2.toInt(), 0xFFF5DFA6.toInt(), 0xFFC7D4EA.toInt(), 0xFFDCC9EA.toInt(), 0xFFB5342B.toInt())
}

/**
 * Her layer of things that are not ink: emoji, sticky notes, photos, washi tape.
 * Each child is a StickerView that drags with one finger and turns or grows with two.
 */
class StickerLayer(context: Context) : FrameLayout(context) {
    var onChanged: (() -> Unit)? = null
    var onDeleteRequested: (() -> Unit)? = null
    var selected: StickerView? = null
        private set

    private val d = resources.displayMetrics.density
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = 0xFF2E6E5D.toInt()
        pathEffect = DashPathEffect(floatArrayOf(5 * d, 4 * d), 0f)
    }
    private val xBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFA2431E.toInt() }
    private val xFg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 2 * d; strokeCap = Paint.Cap.ROUND }
    private val tmp = RectF()
    private var deleting = false

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
    }

    fun bind(models: List<StickerModel>, photoDir: File) {
        removeAllViews()
        selected = null
        for (m in models) addView(StickerView.create(context, m, photoDir, this))
        invalidate()
    }

    fun add(m: StickerModel, photoDir: File): StickerView {
        val v = StickerView.create(context, m, photoDir, this)
        addView(v)
        select(v)
        onChanged?.invoke()
        return v
    }

    fun select(v: StickerView?) {
        if (selected === v) return
        selected = v
        v?.bringToFront()
        invalidate()
    }

    fun deselect() = select(null)

    fun removeSelected(models: MutableList<StickerModel>) {
        val v = selected ?: return
        models.remove(v.model)
        removeView(v)
        selected = null
        onChanged?.invoke()
        invalidate()
    }

    private fun bounds(v: View, out: RectF) {
        out.set(0f, 0f, v.width.toFloat(), v.height.toFloat())
        v.matrix.mapRect(out)
        out.offset(v.left.toFloat(), v.top.toFloat())
    }

    private fun hitDelete(v: StickerView, x: Float, y: Float): Boolean {
        bounds(v, tmp); tmp.inset(-6 * d, -6 * d)
        return hypot(x - tmp.right, y - tmp.top) < 18 * d
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val s = selected
            if (s != null && hitDelete(s, ev.x, ev.y)) { deleting = true; return true }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (deleting) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_UP -> { deleting = false; onDeleteRequested?.invoke() }
                MotionEvent.ACTION_CANCEL -> deleting = false
            }
            return true
        }
        // a touch on bare paper puts the selection away and falls through to the layers beneath
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && selected != null) deselect()
        return false
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val s = selected ?: return
        bounds(s, tmp); tmp.inset(-6 * d, -6 * d)
        canvas.drawRoundRect(tmp, 6 * d, 6 * d, outline)
        canvas.drawCircle(tmp.right, tmp.top, 12 * d, xBg)
        val k = 4.5f * d
        canvas.drawLine(tmp.right - k, tmp.top - k, tmp.right + k, tmp.top + k, xFg)
        canvas.drawLine(tmp.right - k, tmp.top + k, tmp.right + k, tmp.top - k, xFg)
    }
}

class StickerView private constructor(context: Context, val model: StickerModel, private val layer: StickerLayer) : FrameLayout(context) {
    private val d = resources.displayMetrics.density
    private var mode = 0 // 1 drag, 2 two-finger turn and grow
    private var lastX = 0f
    private var lastY = 0f
    private var startDist = 1f
    private var startAngle = 0f
    private var startScale = 1f
    private var startRot = 0f
    /** When set, a one-finger drag only begins on this child (the grip of a note), so the text stays editable. */
    var dragHandle: View? = null

    companion object {
        fun create(context: Context, m: StickerModel, photoDir: File, layer: StickerLayer): StickerView {
            val v = StickerView(context, m, layer)
            val d = v.d
            when (m.kind) {
                "note" -> v.buildNote(d)
                "photo" -> v.buildPhoto(d, File(photoDir, m.text))
                "washi" -> v.addView(WashiView(context, m), LayoutParams((220 * d).toInt(), (34 * d).toInt()))
                else -> v.buildEmoji(d)
            }
            v.layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            return v
        }
    }

    private fun buildEmoji(d: Float) {
        val tv = TextView(context)
        tv.text = model.text
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
        tv.setPadding((6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt())
        addView(tv)
    }

    private fun buildNote(d: Float) {
        val card = LinearLayout(context)
        card.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.cornerRadius = 10 * d
        bg.setColor(NoteColors.fills[model.tint.coerceIn(0, NoteColors.fills.lastIndex)])
        card.background = bg
        card.elevation = 5 * d
        val grip = View(context)
        val gripBg = GradientDrawable()
        gripBg.cornerRadii = floatArrayOf(10 * d, 10 * d, 10 * d, 10 * d, 0f, 0f, 0f, 0f)
        gripBg.setColor(0x22000000)
        grip.background = gripBg
        card.addView(grip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (22 * d).toInt()))
        grip.setOnLongClickListener {
            model.tint = (model.tint + 1) % NoteColors.fills.size
            bg.setColor(NoteColors.fills[model.tint])
            layer.onChanged?.invoke()
            true
        }
        val et = EditText(context)
        et.setText(model.text)
        et.hint = "write a note"
        et.setHintTextColor(0x66000000)
        et.setTextColor(NoteColors.ink)
        et.typeface = Fonts(context).get("caveat")
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        et.background = null
        et.minLines = 3
        et.gravity = Gravity.TOP or Gravity.START
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        et.setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { model.text = s?.toString() ?: ""; layer.onChanged?.invoke() }
        })
        card.addView(et, LinearLayout.LayoutParams((230 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(card)
        dragHandle = grip
    }

    private fun buildPhoto(d: Float, file: File) {
        val iv = ImageView(context)
        iv.adjustViewBounds = true
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.setBackgroundColor(Color.WHITE)
        val p = (5 * d).toInt()
        iv.setPadding(p, p, p, p)
        iv.elevation = 6 * d
        iv.layoutParams = LayoutParams((260 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        decode(file, 1400)?.let { iv.setImageBitmap(it) }
        addView(iv)
    }

    private fun decode(file: File, maxPx: Int): Bitmap? {
        if (!file.exists()) return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, opts)
        var sample = 1
        while (opts.outWidth / sample > maxPx || opts.outHeight / sample > maxPx) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val orientation = runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val rot = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rot == 0f) return bmp
        val m = Matrix().apply { postRotate(rot) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    // ---------- placing ----------

    private fun applyModel() {
        val parent = parent as? View ?: return
        if (width == 0 || parent.width == 0) return
        pivotX = width / 2f
        pivotY = height / 2f
        translationX = model.x * parent.width - width / 2f
        translationY = model.y * parent.height - height / 2f
        rotation = model.rotation
        scaleX = model.scale
        scaleY = model.scale
    }

    private fun writeBack() {
        val parent = parent as? View ?: return
        if (parent.width == 0 || parent.height == 0) return
        model.x = (translationX + width / 2f) / parent.width
        model.y = (translationY + height / 2f) / parent.height
        model.rotation = rotation
        model.scale = scaleX
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        applyModel()
    }

    // ---------- gestures ----------

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.pointerCount >= 2) return true
        val h = dragHandle ?: return true
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            layer.select(this)
            val r = Rect()
            h.getHitRect(r)
            return r.contains(ev.x.toInt(), ev.y.toInt())
        }
        return mode != 0
    }

    private fun rawX(ev: MotionEvent, i: Int) = ev.getRawX(i)
    private fun rawY(ev: MotionEvent, i: Int) = ev.getRawY(i)
    private fun dist(ev: MotionEvent) = hypot(rawX(ev, 1) - rawX(ev, 0), rawY(ev, 1) - rawY(ev, 0))
    private fun angle(ev: MotionEvent) = Math.toDegrees(atan2((rawY(ev, 1) - rawY(ev, 0)).toDouble(), (rawX(ev, 1) - rawX(ev, 0)).toDouble())).toFloat()

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                layer.select(this)
                mode = 1; lastX = ev.rawX; lastY = ev.rawY
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (ev.pointerCount == 2) {
                mode = 2
                startDist = dist(ev).coerceAtLeast(1f)
                startAngle = angle(ev)
                startScale = scaleX
                startRot = rotation
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                1 -> {
                    translationX += ev.rawX - lastX
                    translationY += ev.rawY - lastY
                    lastX = ev.rawX; lastY = ev.rawY
                    layer.invalidate()
                }
                2 -> if (ev.pointerCount >= 2) {
                    val s = (startScale * dist(ev) / startDist).coerceIn(0.3f, 6f)
                    scaleX = s; scaleY = s
                    rotation = startRot + (angle(ev) - startAngle)
                    layer.invalidate()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (ev.pointerCount == 2) {
                // the finger that stays must not yank the sticker: end the gesture here
                mode = 0
                writeBack(); layer.onChanged?.invoke()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mode = 0
                writeBack(); layer.onChanged?.invoke()
            }
        }
        return true
    }
}

/** A strip of washi tape: translucent color with a pattern, ready to be turned and stuck anywhere. */
class WashiView(context: Context, private val model: StickerModel) : View(context) {
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pat = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF; strokeWidth = 3 * d }

    override fun onDraw(canvas: Canvas) {
        val c = WashiColors.fills[model.tint.coerceIn(0, WashiColors.fills.lastIndex)]
        fill.color = (c and 0x00FFFFFF) or (0xC8 shl 24)
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, fill)
        when (model.tint % 3) {
            0 -> { var x = -h; while (x < w) { canvas.drawLine(x, h, x + h, 0f, pat); x += 10 * d } }
            1 -> { var x = 6 * d; while (x < w) { canvas.drawCircle(x, h / 2, 2.5f * d, pat); x += 12 * d } }
            else -> { /* plain */ }
        }
        // torn ends
        fill.color = Color.TRANSPARENT
    }
}
