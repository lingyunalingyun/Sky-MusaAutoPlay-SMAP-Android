package com.smap.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smap.android.data.LibraryItem
import com.smap.android.i18n.tr
import com.smap.android.model.SkySong
import com.smap.android.model.SongNote
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

private val ComposerWindow = Color(0xFF171717)
private val ComposerPanel = Color(0xFF242424)
private val ComposerToolbar = Color(0xFF2D2D2D)
private val ComposerGrid = Color(0xFF1E1E1E)
private val ComposerBorder = Color(0xFF3B3B48)
private val ComposerBlue = Color(0xFF2196F3)
private val ComposerGreen = Color(0xFF4CAF50)
private val ComposerOrange = Color(0xFFD08A18)

private data class ComposerNote(val key: Int, val beat: Float)

@Composable
fun ComposerScreen(
    source: LibraryItem?,
    instrumentLabel: String,
    onInstrument: () -> Unit,
    onPreview: (Int) -> Unit,
    onSave: (SkySong, Boolean) -> Unit,
    onBack: () -> Unit
) {
    val sourceBpm = source?.song?.bpm?.coerceIn(30, 300) ?: 120
    val sourceMsPerBeat = 60_000f / sourceBpm
    val initialNotes = remember(source?.fileName) {
        source?.song?.songNotes.orEmpty().map { ComposerNote(it.key, it.time / sourceMsPerBeat) }
    }
    val notes = remember(source?.fileName) { mutableStateListOf<ComposerNote>().apply { addAll(initialNotes) } }
    val undo = remember(source?.fileName) { ArrayDeque<List<ComposerNote>>() }
    val redo = remember(source?.fileName) { ArrayDeque<List<ComposerNote>>() }
    var historyVersion by remember { mutableIntStateOf(0) }
    var bpmText by remember(source?.fileName) { mutableStateOf(sourceBpm.toString()) }
    var subdiv by remember { mutableIntStateOf(4) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var playhead by remember { mutableFloatStateOf(0f) }
    var playing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<ComposerNote?>(null) }
    var showInfo by remember { mutableStateOf(false) }
    var confirmBack by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var name by remember(source?.fileName) { mutableStateOf(source?.song?.name ?: tr("未命名")) }
    var author by remember(source?.fileName) { mutableStateOf(source?.song?.author.orEmpty()) }
    var transcriber by remember(source?.fileName) { mutableStateOf(source?.song?.transcribedBy.orEmpty()) }

    val bpm = bpmText.toIntOrNull()?.coerceIn(30, 300) ?: 120
    val maxNoteBeat = notes.maxOfOrNull { it.beat } ?: 0f
    val totalBeats = max(16, ceil(maxNoteBeat + 4f).toInt())
    val activeKeys = notes.filter { kotlin.math.abs(it.beat - playhead) < (0.55f / subdiv) }.map { it.key }.toSet()

    fun snapshot() {
        undo.addLast(notes.toList())
        if (undo.size > 80) undo.removeFirst()
        redo.clear()
        historyVersion++
    }

    fun restore(value: List<ComposerNote>) {
        notes.clear()
        notes.addAll(value)
        selected = null
        dirty = true
        historyVersion++
    }

    fun toggleNote(key: Int, beat: Float) {
        val snapped = (beat * subdiv).roundToInt().coerceAtLeast(0) / subdiv.toFloat()
        val existing = notes.firstOrNull { it.key == key && kotlin.math.abs(it.beat - snapped) < 0.0001f }
        snapshot()
        if (existing != null) {
            notes.remove(existing)
            if (selected == existing) selected = null
        } else {
            ComposerNote(key, snapped).also { notes += it; selected = it }
        }
        dirty = true
    }

    fun buildSong(): SkySong {
        val msPerBeat = 60_000f / bpm
        return SkySong(
            name = name.trim().ifBlank { tr("未命名") },
            author = author.trim().ifBlank { null },
            transcribedBy = transcriber.trim().ifBlank { null },
            bpm = bpm,
            bitsPerPage = source?.song?.bitsPerPage ?: 16,
            pitchLevel = source?.song?.pitchLevel ?: 0,
            isComposed = true,
            isEncrypted = false,
            keyCount = 15,
            songNotes = notes.sortedWith(compareBy<ComposerNote> { it.beat }.thenBy { it.key })
                .map { SongNote((it.beat * msPerBeat).roundToInt(), it.key) }
        )
    }

    LaunchedEffect(playing, bpm, totalBeats) {
        if (!playing) return@LaunchedEffect
        var lastBeat = playhead
        var lastNanos = 0L
        while (playing) {
            val now = withFrameNanos { it }
            if (lastNanos == 0L) lastNanos = now
            val next = playhead + ((now - lastNanos) / 1_000_000_000f) * (bpm / 60f)
            lastNanos = now
            notes.filter { it.beat > lastBeat + 0.0001f && it.beat <= next + 0.0001f }.forEach { onPreview(it.key) }
            playhead = next
            lastBeat = next
            if (playhead >= totalBeats) {
                playhead = 0f
                playing = false
            }
            delay(1)
        }
    }

    Column(Modifier.fillMaxSize().background(ComposerWindow)) {
        ComposerTopBar(
            title = name,
            playing = playing,
            bpmText = bpmText,
            noteCount = notes.size,
            subdiv = subdiv,
            zoom = zoom,
            instrumentLabel = instrumentLabel,
            canUndo = historyVersion >= 0 && undo.isNotEmpty(),
            canRedo = redo.isNotEmpty(),
            onBack = { if (dirty) confirmBack = true else onBack() },
            onPlay = { if (notes.isNotEmpty()) { if (playhead >= totalBeats) playhead = 0f; playing = !playing } },
            onHome = { playing = false; playhead = 0f },
            onStep = { direction -> if (!playing) playhead = (playhead + direction / subdiv.toFloat()).coerceIn(0f, totalBeats.toFloat()) },
            onBpm = { bpmText = it.filter(Char::isDigit).take(3); dirty = true },
            onSubdiv = { subdiv = when (subdiv) { 4 -> 8; 8 -> 3; 3 -> 6; 6 -> 12; else -> 4 } },
            onZoom = { zoom = when (zoom) { .75f -> 1f; 1f -> 1.25f; 1.25f -> 1.5f; else -> .75f } },
            onInstrument = onInstrument,
            onUndo = {
                if (undo.isNotEmpty()) {
                    redo.addLast(notes.toList())
                    restore(undo.removeLast())
                }
            },
            onRedo = {
                if (redo.isNotEmpty()) {
                    undo.addLast(notes.toList())
                    restore(redo.removeLast())
                }
            },
            onDelete = {
                selected?.let { note -> snapshot(); notes.remove(note); selected = null; dirty = true }
            },
            onInfo = { showInfo = true },
            onSave = { if (notes.isNotEmpty()) { onSave(buildSong(), false); dirty = false } },
            onSaveAs = { if (notes.isNotEmpty()) { onSave(buildSong(), true); dirty = false } }
        )

        PianoRollEditor(
            notes = notes,
            selected = selected,
            playhead = playhead,
            totalBeats = totalBeats,
            subdiv = subdiv,
            zoom = zoom,
            onToggle = ::toggleNote,
            onSelect = { selected = it },
            modifier = Modifier.weight(1f)
        )

        ComposerKeyboard(
            activeKeys = activeKeys,
            onPress = { key -> onPreview(key); if (!playing) toggleNote(key, playhead) }
        )
    }

    if (showInfo) {
        ComposerInfoDialog(
            name = name,
            author = author,
            transcriber = transcriber,
            onDismiss = { showInfo = false },
            onConfirm = { newName, newAuthor, newTranscriber ->
                name = newName
                author = newAuthor
                transcriber = newTranscriber
                dirty = true
                showInfo = false
            }
        )
    }

    if (confirmBack) {
        AlertDialog(
            onDismissRequest = { confirmBack = false },
            containerColor = ComposerPanel,
            title = { Text(tr("放弃未保存的修改？"), color = Color.White) },
            text = { Text(tr("当前编曲尚未保存。"), color = Color(0xFFB8B8BE)) },
            dismissButton = { TextButton(onClick = { confirmBack = false }) { Text(tr("继续编辑")) } },
            confirmButton = { TextButton(onClick = onBack) { Text(tr("放弃修改"), color = Color(0xFFE65B55)) } }
        )
    }
}

@Composable
private fun ComposerTopBar(
    title: String,
    playing: Boolean,
    bpmText: String,
    noteCount: Int,
    subdiv: Int,
    zoom: Float,
    instrumentLabel: String,
    canUndo: Boolean,
    canRedo: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onHome: () -> Unit,
    onStep: (Int) -> Unit,
    onBpm: (String) -> Unit,
    onSubdiv: () -> Unit,
    onZoom: () -> Unit,
    onInstrument: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDelete: () -> Unit,
    onInfo: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit
) {
    val toolbarScroll = rememberScrollState()
    Row(
        Modifier.fillMaxWidth().height(58.dp).background(ComposerToolbar)
            .horizontalScroll(toolbarScroll).padding(horizontal = 7.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        ComposerButton("‹", onBack)
        Column(Modifier.width(150.dp)) {
            Text(tr("钢琴卷帘编辑器"), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(title, color = Color(0xFFA8A8B0), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ComposerButton("◁", onClick = { onStep(-1) })
        ComposerButton(if (playing) "Ⅱ" else "▶", onPlay, if (playing) ComposerOrange else ComposerGreen)
        ComposerButton("▷", onClick = { onStep(1) })
        ComposerButton("⏮", onHome)
        ComposerPill("${tr("音色")}:$instrumentLabel", onInstrument, 92.dp)
        OutlinedTextField(
            value = bpmText,
            onValueChange = onBpm,
            prefix = { Text("BPM ", fontSize = 10.sp) },
            singleLine = true,
            modifier = Modifier.width(88.dp).height(44.dp),
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp)
        )
        ComposerPill("1/$subdiv", onSubdiv)
        ComposerPill("${zoom}x", onZoom)
        Text("${tr("音符")} $noteCount", color = Color(0xFFCCCCD2), fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        ComposerButton("↶", onUndo, enabled = canUndo)
        ComposerButton("↷", onRedo, enabled = canRedo)
        ComposerButton("⌫", onDelete)
        ComposerButton("ℹ", onInfo)
        ComposerButton(tr("保存"), onSave, ComposerBlue, 58.dp)
        ComposerButton(tr("另存为"), onSaveAs, width = 66.dp)
    }
}

@Composable
private fun PianoRollEditor(
    notes: List<ComposerNote>,
    selected: ComposerNote?,
    playhead: Float,
    totalBeats: Int,
    subdiv: Int,
    zoom: Float,
    onToggle: (Int, Float) -> Unit,
    onSelect: (ComposerNote?) -> Unit,
    modifier: Modifier = Modifier
) {
    val scroll = rememberScrollState()
    val beatWidth = 52.dp * zoom
    val rulerHeight = 22.dp
    BoxWithConstraints(modifier.fillMaxWidth().background(ComposerGrid)) {
        val rowHeight = (maxHeight - rulerHeight) / 15
        Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(62.dp).fillMaxHeight().background(ComposerWindow)) {
            Box(Modifier.fillMaxWidth().height(rulerHeight).background(Color(0xFF202020)))
            repeat(15) { row ->
                val key = 14 - row
                Box(
                    Modifier.fillMaxWidth().height(rowHeight)
                        .background(if (key % 5 == 0) Color(0xFF393939) else Color(0xFF2B2B2B))
                        .border(0.5.dp, Color(0xFF181818)),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text("K$key  ${noteName(key)}", color = Color(0xFFD8D8DE), fontSize = 9.sp, modifier = Modifier.padding(start = 7.dp))
                }
            }
        }
        Box(Modifier.fillMaxSize().horizontalScroll(scroll)) {
            Canvas(
                Modifier.width(beatWidth * totalBeats).fillMaxHeight()
                    .pointerInput(notes, subdiv, zoom, totalBeats) {
                        detectTapGestures(
                            onTap = { p ->
                                if (p.y < rulerHeight.toPx()) return@detectTapGestures
                                val beat = p.x / beatWidth.toPx()
                                val row = floor((p.y - rulerHeight.toPx()) / rowHeight.toPx()).toInt().coerceIn(0, 14)
                                val key = 14 - row
                                val snapped = (beat * subdiv).roundToInt() / subdiv.toFloat()
                                val hit = notes.firstOrNull { it.key == key && kotlin.math.abs(it.beat - snapped) < .0001f }
                                if (hit != null) onSelect(hit) else onToggle(key, beat)
                            },
                            onDoubleTap = { p ->
                                if (p.y >= rulerHeight.toPx()) {
                                    val beat = p.x / beatWidth.toPx()
                                    val row = floor((p.y - rulerHeight.toPx()) / rowHeight.toPx()).toInt().coerceIn(0, 14)
                                    onToggle(14 - row, beat)
                                }
                            }
                        )
                    }
            ) {
                drawRect(ComposerGrid)
                val rulerPx = rulerHeight.toPx()
                val rowPx = rowHeight.toPx()
                val beatPx = beatWidth.toPx()
                for (row in 0..15) {
                    val y = rulerPx + row * rowPx
                    drawLine(Color(0xFF343438), Offset(0f, y), Offset(size.width, y), 1f)
                }
                for (cell in 0..(totalBeats * subdiv)) {
                    val x = cell * beatPx / subdiv
                    val major = cell % subdiv == 0
                    drawLine(if (major) Color(0xFF555560) else Color(0xFF2E2E34), Offset(x, if (major) 0f else rulerPx), Offset(x, size.height), if (major) 1.5f else 1f)
                }
                notes.forEach { note ->
                    val x = note.beat * beatPx + 2.dp.toPx()
                    val row = 14 - note.key
                    val y = rulerPx + row * rowPx + 2.dp.toPx()
                    val isSelected = note == selected
                    drawRoundRect(
                        color = if (isSelected) ComposerOrange else ComposerBlue,
                        topLeft = Offset(x, y),
                        size = androidx.compose.ui.geometry.Size(max(7.dp.toPx(), beatPx / subdiv - 4.dp.toPx()), rowPx - 4.dp.toPx()),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
                    )
                    if (isSelected) drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(x, y),
                        size = androidx.compose.ui.geometry.Size(max(7.dp.toPx(), beatPx / subdiv - 4.dp.toPx()), rowPx - 4.dp.toPx()),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
                        style = Stroke(1.dp.toPx())
                    )
                }
                val cursorX = playhead * beatPx
                drawLine(Color(0xFFE34D52), Offset(cursorX, 0f), Offset(cursorX, size.height), 2.dp.toPx())
            }
        }
        }
    }
}

@Composable
private fun ComposerKeyboard(activeKeys: Set<Int>, onPress: (Int) -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Color(0xFF1F1F1F)).border(1.dp, Color(0xFF333333)).padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(tr("点击琴键试听，并在游标位置写谱"), color = Color(0xFF99999F), fontSize = 9.sp)
        repeat(3) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(5) { col ->
                    val key = row * 5 + col
                    Box(
                        Modifier.size(48.dp, 27.dp)
                            .background(if (key in activeKeys) ComposerGreen else Color(0xFF333333), RoundedCornerShape(4.dp))
                            .clickable { onPress(key) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("${noteName(key)} · K$key", color = Color.White, fontSize = 9.sp)
                    }
                }
            }
            if (row < 2) Spacer(Modifier.height(3.dp))
        }
    }
}

@Composable
private fun ComposerInfoDialog(
    name: String,
    author: String,
    transcriber: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit
) {
    var draftName by remember(name) { mutableStateOf(name) }
    var draftAuthor by remember(author) { mutableStateOf(author) }
    var draftTranscriber by remember(transcriber) { mutableStateOf(transcriber) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ComposerPanel,
        title = { Text(tr("编辑曲目信息"), color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedTextField(draftName, { draftName = it }, label = { Text(tr("曲名")) }, singleLine = true)
                OutlinedTextField(draftAuthor, { draftAuthor = it }, label = { Text(tr("作者")) }, singleLine = true)
                OutlinedTextField(draftTranscriber, { draftTranscriber = it }, label = { Text(tr("做谱者")) }, singleLine = true)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消")) } },
        confirmButton = { TextButton(onClick = { onConfirm(draftName, draftAuthor, draftTranscriber) }) { Text(tr("确定")) } }
    )
}

@Composable
private fun ComposerButton(
    text: String,
    onClick: () -> Unit,
    color: Color = Color(0xFF3A3A3A),
    width: androidx.compose.ui.unit.Dp = 38.dp,
    enabled: Boolean = true
) {
    Box(
        Modifier.width(width).height(34.dp).background(if (enabled) color else Color(0xFF292929), RoundedCornerShape(5.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (enabled) Color.White else Color(0xFF66666B), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun ComposerPill(text: String, onClick: () -> Unit, width: androidx.compose.ui.unit.Dp = 54.dp) {
    Box(
        Modifier.width(width).height(34.dp).background(Color(0xFF353535), RoundedCornerShape(5.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Color(0xFFDADAE0), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

private fun noteName(key: Int): String = arrayOf("C", "D", "E", "F", "G", "A", "B", "C", "D", "E", "F", "G", "A", "B", "C")
    .getOrElse(key) { "?" }
