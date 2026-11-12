package com.guernseyhill.notebook.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.launch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.guernseyhill.notebook.ink.Palette
import com.guernseyhill.notebook.ink.Tool
import com.guernseyhill.notebook.page.Fonts
import com.guernseyhill.notebook.page.NotebookView
import com.guernseyhill.notebook.page.Paper
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Accent = Color(0xFF2E6E5D)
private val PaperBg = Color(0xFFEFF1EC)
private val Ink = Color(0xFF19231F)
private val Muted = Color(0xFF6D7B73)

enum class Tray { NONE, STICKERS, PAPER, FONTS, SYNC }

@Composable
fun NotebookTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Accent,
            onPrimary = Color(0xFFFBFCFA),
            surface = Color(0xFFFBFCFA),
            background = PaperBg,
            onSurface = Ink,
            secondaryContainer = Color(0xFFDBE9E3),
            onSecondaryContainer = Accent,
        ),
        content = content,
    )
}

@Composable
fun NotebookScreen() {
    val tick = remember { mutableIntStateOf(0) }
    var nb by remember { mutableStateOf<NotebookView?>(null) }
    var tray by remember { mutableStateOf(Tray.NONE) }
    var confirmClear by remember { mutableStateOf(false) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) nb?.addPhoto(uri)
    }
    val t = tick.intValue
    val v = nb

    Column(Modifier.fillMaxSize().background(PaperBg).systemBarsPadding()) {
        TopBar(v, t)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    NotebookView(ctx).also { view ->
                        view.listener = NotebookView.Listener { tick.intValue++ }
                        nb = view
                    }
                },
            )
            if (tray != Tray.NONE && v != null) {
                TrayPanel(tray, v, t, Modifier.align(Alignment.BottomCenter)) { tray = Tray.NONE }
            }
        }
        if (v != null) {
            BottomBar(
                v = v, t = t, tray = tray,
                onTray = { tray = if (tray == it) Tray.NONE else it },
                onClear = { confirmClear = true },
                onPickPhoto = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            )
        }
    }

    if (confirmClear && v != null) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the ink on this page?") },
            text = { Text("Stickers, notes and photos stay. Undo brings the ink back.") },
            confirmButton = { TextButton(onClick = { v.clearInk(); confirmClear = false }) { Text("Clear ink") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep it") } },
        )
    }
}

@Composable
private fun TopBar(v: NotebookView?, t: Int) {
    val date = v?.date ?: LocalDate.now()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { v?.goTo(date.minusDays(1)) }) { Text("‹", fontSize = 22.sp) }
        Text(
            date.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)),
            fontSize = 17.sp, color = Ink,
            fontFamily = FontFamily(Font(Fonts.resId("newsreader"))),
        )
        TextButton(onClick = { v?.goTo(date.plusDays(1)) }) { Text("›", fontSize = 22.sp) }
        if (date != LocalDate.now()) TextButton(onClick = { v?.goTo(LocalDate.now()) }) { Text("Today") }
        Spacer(Modifier.weight(1f))
        if (v != null && v.penSeen) PressureMeter(v)
        Spacer(Modifier.width(10.dp))
        Text(if (v != null && v.sync.configured) v.sync.status else "0.4", fontSize = 11.sp, color = Muted, letterSpacing = 1.sp)
        Spacer(Modifier.width(8.dp))
    }
}

/** Shows what the S Pen is reporting, so a flat line means the pen, not the app. */
@Composable
private fun PressureMeter(v: NotebookView) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("pressure", fontSize = 10.sp, color = Muted, letterSpacing = 1.sp)
        Spacer(Modifier.width(6.dp))
        Box(Modifier.width(70.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFD6DCD3))) {
            Box(Modifier.fillMaxWidth(v.lastPressure.coerceIn(0f, 1f)).height(8.dp).background(Accent))
        }
        Spacer(Modifier.width(6.dp))
        val range = if (v.pressureMax >= v.pressureMin) "%.2f–%.2f".format(Locale.US, v.pressureMin, v.pressureMax) else ""
        Text(range, fontSize = 10.sp, color = Muted)
    }
}

@Composable
private fun BottomBar(
    v: NotebookView, t: Int, tray: Tray,
    onTray: (Tray) -> Unit, onClear: () -> Unit, onPickPhoto: () -> Unit,
) {
    Surface(tonalElevation = 2.dp, shadowElevation = 6.dp, color = Color(0xFFFBFCFA)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            // row one: the pen
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolChip("✒️ Pen", v.tool == Tool.PEN) { v.tool = Tool.PEN }
                ToolChip("🖌 Brush", v.tool == Tool.BRUSH) { v.tool = Tool.BRUSH }
                ToolChip("🖍️ Marker", v.tool == Tool.MARKER) { v.tool = Tool.MARKER }
                ToolChip("🖊️ Highlight", v.tool == Tool.HIGHLIGHTER) { v.tool = Tool.HIGHLIGHTER }
                ToolChip("◻ Eraser", v.tool == Tool.ERASER) { v.tool = Tool.ERASER }
                ToolChip("✋ Hand", v.tool == Tool.HAND) { v.tool = Tool.HAND }
                Spacer(Modifier.width(6.dp))
                for (i in 0..2) {
                    val dia = listOf(7.dp, 11.dp, 15.dp)[i]
                    Box(
                        Modifier.size(28.dp).clip(CircleShape)
                            .background(if (v.sizeIdx == i) Color(0xFFDBE9E3) else Color.Transparent)
                            .clickable { v.sizeIdx = i },
                        contentAlignment = Alignment.Center,
                    ) { Box(Modifier.size(dia).clip(CircleShape).background(Ink)) }
                }
                Spacer(Modifier.width(6.dp))
                Palette.inks.forEachIndexed { i, ink ->
                    Box(
                        Modifier.size(26.dp).clip(CircleShape).background(Color(ink.argb))
                            .border(if (v.colorIdx == i) 3.dp else 0.dp, if (v.colorIdx == i) Color(0xFFFBFCFA) else Color.Transparent, CircleShape)
                            .border(if (v.colorIdx == i) 1.5.dp else 0.dp, if (v.colorIdx == i) Ink else Color.Transparent, CircleShape)
                            .clickable { v.colorIdx = i },
                    )
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { v.undo() }, enabled = v.canUndo) { Text("Undo") }
                TextButton(onClick = onClear, enabled = v.strokeCount > 0) { Text("Clear ink") }
            }
            // row two: the notebook
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolChip("😊 Stickers", tray == Tray.STICKERS) { onTray(Tray.STICKERS) }
                ToolChip("🗒 Note", false) { v.addNote() }
                ToolChip("🎀 Washi", false) { v.addWashi() }
                ToolChip("🖼 Photo", false) { onPickPhoto() }
                ToolChip("📄 Paper", tray == Tray.PAPER) { onTray(Tray.PAPER) }
                ToolChip("Aa Fonts", tray == Tray.FONTS) { onTray(Tray.FONTS) }
                ToolChip("☁ Sync", tray == Tray.SYNC) { onTray(Tray.SYNC) }
                Spacer(Modifier.width(6.dp))
                ToolChip("▦ Boxes", v.layoutMode) { v.layoutMode = !v.layoutMode }
                if (v.layoutMode && v.hasCustomBoxes) TextButton(onClick = { v.resetBoxes() }) { Text("Reset boxes") }
                ToolChip("👆 Finger draws", v.fingerDraws) { v.fingerDraws = !v.fingerDraws }
                if (v.hasSelection) {
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = { v.deleteSelectedSticker() }) { Text("Remove sticker", color = Color(0xFFA2431E)) }
                }
            }
        }
    }
}

@Composable
private fun ToolChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label, fontSize = 13.sp) })
}

private val EMOJI = listOf(
    "❤️", "🧡", "💛", "💚", "💙", "💜", "🤍", "✨", "⭐", "🌟", "💫", "🌈", "☀️", "🌤", "🌧", "❄️", "🌙",
    "🌸", "🌼", "🌷", "🌻", "🌿", "🍃", "🍂", "🍁", "🌵", "🪴", "🌲", "🍄", "🐾", "🐶", "🐕", "🦮", "🐱",
    "☕", "🍵", "🥐", "🍕", "🥗", "🍲", "🍰", "🧁", "🍪", "🍎", "🥑", "🧺", "🧹", "🛒", "📦", "✈️", "🚗",
    "🎵", "🎶", "🎬", "🎟", "📚", "✏️", "📌", "📎", "🔖", "✅", "❗", "💤", "🛁", "💅", "💇‍♀️", "🏋️‍♀️", "🧘‍♀️", "🎉",
)

@Composable
private fun TrayPanel(tray: Tray, v: NotebookView, t: Int, modifier: Modifier, onClose: () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(14.dp), tonalElevation = 3.dp, shadowElevation = 10.dp, color = Color(0xFFFBFCFA),
    ) {
        Column(Modifier.padding(10.dp)) {
            when (tray) {
                Tray.STICKERS -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(46.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                ) {
                    items(EMOJI) { e ->
                        Box(
                            Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)).clickable { v.addEmoji(e) },
                            contentAlignment = Alignment.Center,
                        ) { Text(e, fontSize = 26.sp) }
                    }
                }
                Tray.PAPER -> {
                    Text("PAPER", fontSize = 10.sp, color = Muted, letterSpacing = 2.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (s in Paper.styles) ToolChip(s, v.page.paper == s) { v.setPaper(s) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("TINT", fontSize = 10.sp, color = Muted, letterSpacing = 2.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (name in Paper.tints) {
                            Box(
                                Modifier.size(34.dp).clip(CircleShape).background(Color(Paper.tintColor(name)))
                                    .border(if (v.page.tint == name) 2.dp else 1.dp, if (v.page.tint == name) Accent else Color(0xFFD6DCD3), CircleShape)
                                    .clickable { v.setTint(name) },
                            )
                        }
                    }
                }
                Tray.FONTS -> {
                    Text("THE DATE", fontSize = 10.sp, color = Muted, letterSpacing = 2.sp)
                    FontRow(v.page.dateFont) { v.setDateFont(it) }
                    Spacer(Modifier.height(8.dp))
                    Text("THE DAY'S WORDS", fontSize = 10.sp, color = Muted, letterSpacing = 2.sp)
                    FontRow(v.page.contentFont) { v.setContentFont(it) }
                }
                Tray.SYNC -> SyncPanel(v)
                Tray.NONE -> {}
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClose) { Text("Done") }
            }
        }
    }
}

@Composable
private fun SyncPanel(v: NotebookView) {
    val scope = rememberCoroutineScope()
    var owner by remember { mutableStateOf(v.sync.owner) }
    var repo by remember { mutableStateOf(v.sync.repo) }
    var branch by remember { mutableStateOf(v.sync.branch) }
    var token by remember { mutableStateOf(v.sync.token) }
    var status by remember { mutableStateOf(v.sync.status) }
    Text("GITHUB REPOSITORY", fontSize = 10.sp, color = Muted, letterSpacing = 2.sp)
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(owner, { owner = it }, label = { Text("Owner") }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(repo, { repo = it }, label = { Text("Repository") }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(branch, { branch = it }, label = { Text("Branch") }, singleLine = true, modifier = Modifier.width(110.dp))
    }
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        token, { token = it }, label = { Text("Fine-grained token (Contents: read and write)") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = {
            v.sync.owner = owner; v.sync.repo = repo; v.sync.branch = branch; v.sync.token = token
            scope.launch { status = v.sync.test(); v.reconnect() }
        }) { Text("Save and connect") }
        Text(status, fontSize = 12.sp, color = Muted)
    }
}

@Composable
private fun FontRow(current: String, onPick: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((name, label) in Fonts.all) {
            FilterChip(
                selected = current == name,
                onClick = { onPick(name) },
                label = { Text(label, fontSize = 16.sp, fontFamily = FontFamily(Font(Fonts.resId(name)))) },
            )
        }
    }
}
