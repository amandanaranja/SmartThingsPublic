package com.guernseyhill.notebook.page

import android.content.Context
import android.graphics.Typeface
import com.guernseyhill.notebook.R

/** The fonts she can pick for the date and for the day's words. */
class Fonts(private val context: Context) {
    companion object {
        val all: List<Pair<String, String>> = listOf(
            "caveat" to "Caveat",
            "patrick_hand" to "Patrick Hand",
            "kalam" to "Kalam",
            "homemade_apple" to "Homemade Apple",
            "dancing_script" to "Dancing Script",
            "shadows_into_light" to "Shadows Into Light",
            "newsreader" to "Newsreader",
            "plex_sans" to "Plex Sans",
        )

        fun resId(name: String): Int = when (name) {
            "caveat" -> R.font.caveat
            "patrick_hand" -> R.font.patrick_hand
            "kalam" -> R.font.kalam
            "homemade_apple" -> R.font.homemade_apple
            "dancing_script" -> R.font.dancing_script
            "shadows_into_light" -> R.font.shadows_into_light
            "newsreader" -> R.font.newsreader
            else -> R.font.plex_sans
        }

        fun label(name: String): String = all.firstOrNull { it.first == name }?.second ?: name
    }

    private val cache = HashMap<String, Typeface>()

    fun get(name: String): Typeface = cache.getOrPut(name) {
        runCatching { context.resources.getFont(resId(name)) }.getOrDefault(Typeface.DEFAULT)
    }
}
