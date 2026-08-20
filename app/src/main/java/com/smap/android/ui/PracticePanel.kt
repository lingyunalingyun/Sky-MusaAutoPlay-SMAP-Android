package com.smap.android.ui

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smap.android.data.LibraryItem
import com.smap.android.i18n.tr
import kotlinx.coroutines.delay

private val practiceBackground = Color(0xFF121214)
private val practicePanel = Color(0xFF1C1C1C)
private val practiceKey = Color(0xFF4A4A4A)
private val practiceBorder = Color(0xFF585858)
private val practiceAccent = Color(0xFF5AA0FF)

@Composable
fun PracticePanel(
    item: LibraryItem,
    pitch: Int,
    gameMode: Boolean,
    onBack: () -> Unit,
    onGameMode: () -> Unit,
    onKeyDown: (Int) -> Unit
) {
    val steps = remember(item.fileName) { buildPracticeSteps(item) }
    var step by remember(item.fileName) { mutableIntStateOf(0) }
    var readMode by remember { mutableStateOf(false) }
    var metronome by remember { mutableStateOf(false) }
    var bpm by remember(item.fileName) { mutableIntStateOf(item.song.bpm.coerceIn(30, 300)) }
    var page by remember { mutableIntStateOf(0) }
    val held = remember { mutableSetOf<Int>() }
    val pageCount = ((steps.size + 31) / 32).coerceAtLeast(1)

    fun press(key: Int) {
        onKeyDown(key)
        held += key
        val expected = steps.getOrNull(step) ?: intArrayOf()
        if (expected.isNotEmpty() && expected.all(held::contains)) {
            held.clear()
            step = if (step + 1 >= steps.size) 0 else step + 1
            page = step / 32
        }
    }

    Box(Modifier.fillMaxSize().background(practiceBackground)) {
        Text(
            text = "‹ ${tr("返回")}",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp).background(practiceKey, RoundedCornerShape(7.dp))
                .clickable(onClick = onBack).padding(horizontal = 22.dp, vertical = 11.dp)
        )

        Column(
            modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.68f).fillMaxHeight().padding(top = 14.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (readMode) {
                SheetWall(
                    steps = steps,
                    currentStep = step,
                    page = page,
                    pageCount = pageCount,
                    onPrevious = { if (page > 0) page-- },
                    onNext = { if (page + 1 < pageCount) page++ },
                    onSelect = { selected -> step = selected; page = selected / 32 }
                )
                Spacer(Modifier.height(8.dp))
            }
            PracticeKeyboard(
                pitch = pitch,
                current = (steps.getOrNull(step) ?: intArrayOf()).toSet(),
                next = (steps.getOrNull(step + 1) ?: intArrayOf()).toSet(),
                compact = readMode,
                onDown = ::press,
                onUp = { held -= it }
            )
        }

        Column(
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 18.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            PracticeSwitch(tr("读谱模式"), readMode) { readMode = it; page = step / 32 }
            PracticeSwitch(tr("打点模式"), metronome) { metronome = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("打点速度"), color = Color.White, fontSize = 14.sp, modifier = Modifier.width(82.dp))
                Text(
                    "$bpm BPM", color = if (metronome) Color.White else Color(0xFF77777C), fontSize = 12.sp,
                    modifier = Modifier.background(Color(0xFF2B2B2E), RoundedCornerShape(14.dp))
                        .clickable(enabled = metronome) { bpm = if (bpm >= 180) 60 else bpm + 15 }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
            PracticeSwitch(tr("游戏浮窗"), gameMode) { onGameMode() }
        }

        Metronome(enabled = metronome, bpm = bpm)
    }
}

@Composable
private fun PracticeSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.width(82.dp))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun Metronome(enabled: Boolean, bpm: Int) {
    val tone = remember { ToneGenerator(AudioManager.STREAM_MUSIC, 55) }
    DisposableEffect(Unit) { onDispose { tone.release() } }
    LaunchedEffect(enabled, bpm) {
        while (enabled) {
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 45)
            delay(60_000L / bpm.coerceAtLeast(1))
        }
    }
}

@Composable
private fun PracticeKeyboard(
    pitch: Int,
    current: Set<Int>,
    next: Set<Int>,
    compact: Boolean,
    onDown: (Int) -> Unit,
    onUp: (Int) -> Unit
) {
    val semitones = intArrayOf(0, 2, 4, 5, 7, 9, 11, 12, 14, 16, 17, 19, 21, 23, 24)
    Column(
        modifier = Modifier.background(practicePanel, RoundedCornerShape(14.dp)).border(1.dp, Color(0xFF38383C), RoundedCornerShape(14.dp))
            .padding(if (compact) 9.dp else 18.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 7.dp)
    ) {
        repeat(3) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
                repeat(5) { column ->
                    val key = row * 5 + column
                    val color = when (key) { in current -> practiceAccent; in next -> Color(0xFF405F88); else -> practiceKey }
                    Box(
                        modifier = Modifier.size(if (compact) 50.dp else 76.dp, if (compact) 42.dp else 70.dp)
                            .background(color, RoundedCornerShape(7.dp)).border(1.dp, practiceBorder, RoundedCornerShape(7.dp))
                            .pointerInput(key) {
                                detectTapGestures(onPress = {
                                    onDown(key)
                                    tryAwaitRelease()
                                    onUp(key)
                                })
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(Modifier.size(if (compact) 22.dp else 31.dp)) {
                            if (key % 2 == 0) drawCircle(Color(0xFFD8D8DF), style = Stroke(1.5.dp.toPx()))
                            else {
                                val c = center
                                val r = size.minDimension * .42f
                                val points = listOf(Offset(c.x, c.y-r), Offset(c.x+r, c.y), Offset(c.x, c.y+r), Offset(c.x-r, c.y))
                                for (i in points.indices) drawLine(Color(0xFFD8D8DF), points[i], points[(i+1)%4], 1.5.dp.toPx())
                            }
                        }
                        Text(noteName(semitones[key] + pitch), color = Color.White, fontSize = if (compact) 11.sp else 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetWall(
    steps: List<IntArray>, currentStep: Int, page: Int, pageCount: Int,
    onPrevious: () -> Unit, onNext: () -> Unit, onSelect: (Int) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PageButton("‹", page + 1, onPrevious)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(4) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(8) { column ->
                        val index = page * 32 + row * 8 + column
                        MiniStep(steps.getOrNull(index), index == currentStep, Modifier.weight(1f)) {
                            if (index < steps.size) onSelect(index)
                        }
                    }
                }
            }
        }
        PageButton("›", pageCount, onNext)
    }
}

@Composable
private fun PageButton(symbol: String, number: Int, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 8.dp)) {
        Text(symbol, color = Color.White, fontSize = 29.sp, modifier = Modifier.size(42.dp).border(1.dp, Color(0xFF606066), CircleShape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp))
        Text(number.toString(), color = Color(0xFF9A9AA1), fontSize = 11.sp)
    }
}

@Composable
private fun MiniStep(keys: IntArray?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Canvas(modifier.height(34.dp).background(if (selected) Color(0xFF303F55) else Color(0xFF242426), RoundedCornerShape(4.dp))
        .border(1.dp, if (selected) practiceAccent else Color(0xFF3A3A3D), RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(5.dp)) {
        val active = (keys ?: intArrayOf()).toSet()
        repeat(15) { key ->
            val x = (key % 5 + .5f) * size.width / 5f
            val y = (key / 5 + .5f) * size.height / 3f
            drawCircle(if (key in active) practiceAccent else Color(0xFF66666B), radius = if (key in active) 2.5.dp.toPx() else 1.4.dp.toPx(), center = Offset(x, y))
        }
    }
}

private fun buildPracticeSteps(item: LibraryItem): List<IntArray> {
    val notes = item.song.songNotes.filter { it.key in 0..14 }.sortedBy { it.time }
    if (notes.isEmpty()) return emptyList()
    val result = mutableListOf<IntArray>()
    var index = 0
    while (index < notes.size) {
        val time = notes[index].time
        val keys = linkedSetOf<Int>()
        while (index < notes.size && notes[index].time - time <= 20) keys += notes[index++].key
        result += keys.toIntArray()
    }
    return result
}

private fun noteName(semitone: Int): String {
    val names = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
    return names[((semitone % 12) + 12) % 12]
}
