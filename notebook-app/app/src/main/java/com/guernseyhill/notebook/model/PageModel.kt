package com.guernseyhill.notebook.model

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

/**
 * Everything the notebook remembers about one day's page.
 *
 * Her layer: ink, stickers, notes, photos, paper, fonts, and where the frames sit.
 * My layer (the day's information) is NOT stored here; it arrives from outside and
 * is poured into the frames. For the pen test it comes from assets/sample_day.json.
 */
@Serializable
data class PageModel(
    val date: String,
    var paper: String = "dotted",
    var tint: String = "sage",
    var dateFont: String = "caveat",
    var contentFont: String = "plex_sans",
    /** Page size in pixels when the ink was recorded; ink is rescaled on a different screen. */
    var pageW: Float = 0f,
    var pageH: Float = 0f,
    var strokes: MutableList<StrokeModel> = mutableListOf(),
    var stickers: MutableList<StickerModel> = mutableListOf(),
    var frames: MutableList<FrameModel> = mutableListOf(),
    /** checklist ticks, keyed "group|index" */
    var checks: MutableMap<String, Boolean> = mutableMapOf(),
)

/** One pen stroke. pts is flat: x, y, pressure, tiltRadians, orientationRadians, tMillis — six per point. */
@Serializable
data class StrokeModel(
    val brush: String,
    val color: Int,
    val size: Float,
    val pts: List<Float>,
)

/** A sticker, sticky note, photo or strip of washi tape. x,y are the center as a fraction of the page. */
@Serializable
data class StickerModel(
    val id: String,
    val kind: String,          // emoji | note | photo | washi
    var x: Float,
    var y: Float,
    var rotation: Float = 0f,
    var scale: Float = 1f,
    var text: String = "",     // the emoji, the note's words, the photo file name
    var tint: Int = 0,         // note or washi color index
)

/** A box the day's information flows into. All values are fractions of the page. */
@Serializable
data class FrameModel(
    val key: String,
    var x: Float,
    var y: Float,
    var w: Float,
    var h: Float,
    var visible: Boolean = true,
)

object Frames {
    val keys = listOf("date", "weather", "greeting", "agenda", "checklist", "dinner")
    fun defaults(): List<FrameModel> = listOf(
        FrameModel("date", 0.05f, 0.025f, 0.55f, 0.115f),
        FrameModel("weather", 0.63f, 0.025f, 0.32f, 0.115f),
        FrameModel("greeting", 0.05f, 0.155f, 0.90f, 0.085f),
        FrameModel("agenda", 0.05f, 0.26f, 0.42f, 0.30f),
        FrameModel("checklist", 0.51f, 0.26f, 0.44f, 0.46f),
        FrameModel("dinner", 0.05f, 0.58f, 0.42f, 0.14f),
    )
}

class PageStore(context: Context) {
    private val dir = File(context.filesDir, "pages").apply { mkdirs() }
    val photoDir: File = File(context.filesDir, "photos").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(date: LocalDate): PageModel {
        val f = file(date)
        if (!f.exists()) return PageModel(date.toString())
        return runCatching { json.decodeFromString<PageModel>(f.readText()) }
            .getOrElse { PageModel(date.toString()) }
    }

    fun save(page: PageModel) {
        val tmp = File(dir, page.date + ".tmp")
        tmp.writeText(json.encodeToString(page))
        tmp.renameTo(File(dir, page.date + ".json"))
    }

    /** Days she has touched, so the week strip can show a dot. */
    fun touched(): Set<String> =
        dir.listFiles()?.filter { it.name.endsWith(".json") }?.map { it.name.removeSuffix(".json") }?.toSet() ?: emptySet()

    private fun file(d: LocalDate) = File(dir, "$d.json")
}
