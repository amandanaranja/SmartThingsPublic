package com.guernseyhill.notebook.model

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The day's information: what the overnight runs would pour into the page. */
@Serializable
data class DayContent(
    val greeting: String = "",
    val agenda: List<AgendaItem> = emptyList(),
    val weather: Weather? = null,
    val checklist: Map<String, List<String>> = emptyMap(),
    val dinner: Dinner? = null,
    val chips: List<String> = emptyList(),
)

@Serializable
data class AgendaItem(val time: String, val text: String, val sub: String = "")

@Serializable
data class Weather(val icon: String = "", val high: String = "", val low: String = "", val summary: String = "")

@Serializable
data class Dinner(val line: String = "", val tomorrow: String = "")

object SampleDay {
    fun load(context: Context): DayContent {
        val text = context.assets.open("sample_day.json").bufferedReader().use { it.readText() }
        return Json { ignoreUnknownKeys = true }.decodeFromString(text)
    }
}
