package com.guernseyhill.notebook.model

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The day's information, in the same shape the Guernsey Hill Day Book keeps it.
 * The overnight runs copy their `content` document straight into days/<date>/content.json;
 * nothing is reshaped on the way, so the notebook shows exactly what the Day Book knows.
 * Fields the notebook does not draw (nav, wake) are simply ignored.
 */
@Serializable
data class DayContent(
    val date: String = "",
    val greeting: String = "",
    val agenda: List<AgendaItem> = emptyList(),
    val alerts: List<Alert> = emptyList(),
    val sections: List<Section> = emptyList(),
)

@Serializable
data class AgendaItem(val at: String = "", val what: String = "")

@Serializable
data class Alert(val text: String = "", val tone: String = "")

/** kind is prose, list, schedule or freeform. */
@Serializable
data class Section(
    val kind: String = "list",
    val title: String = "",
    val code: String? = null,
    val groups: List<Group> = emptyList(),
    val rows: List<ScheduleRow> = emptyList(),
) {
    val isPlaceholder: Boolean
        get() = title.trim() == "(title me)" && groups.all { it.items.isEmpty() } && rows.isEmpty()
}

@Serializable
data class Group(val label: String? = null, val items: List<Item> = emptyList())

/** k marks an actionable line, which is what gets a tick box. */
@Serializable
data class Item(val code: String? = null, val k: String? = null, val text: String = "")

@Serializable
data class ScheduleRow(val label: String = "", val body: String = "")

object ContentJson {
    val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }
}

object SampleDay {
    fun load(context: Context): DayContent {
        val text = context.assets.open("sample_day.json").bufferedReader().use { it.readText() }
        return ContentJson.json.decodeFromString(text)
    }
}
