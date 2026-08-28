package com.smap.android.ui

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable as FloatAnimatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smap.android.data.LibraryItem
import com.smap.android.i18n.tr
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val practiceBackground = Color(0xFF121214)
private val practicePanel = Color(0xFF1C1C1C)
private val practiceKey = Color(0xFF4A4A4A)
private val practiceBorder = Color(0xFF585858)
private val practiceAccent = Color(0xFF5AA0FF)

@Composable
fun PracticePanel(
    item: LibraryItem,
    pitch: Int,
    positionMs: Long,
    playing: Boolean,
    onPositionChange: (Long) -> Unit,
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
    val recentPresses = remember { mutableMapOf<Int, Long>() }
    val pageCount = ((steps.size + 31) / 32).coerceAtLeast(1)

    fun press(key: Int) {
        val now = SystemClock.elapsedRealtime()
        onKeyDown(key)
        held += key
        recentPresses[key] = now
        val expected = steps.getOrNull(step)?.keys ?: intArrayOf()
        if (expected.isNotEmpty() && expected.all { expectedKey ->
                expectedKey in held || recentPresses[expectedKey]?.let { now - it <= 120L } == true
            }) {
            held.clear()
            recentPresses.clear()
            step = nextNoteStep(steps, step + 1).let { if (it >= steps.size) nextNoteStep(steps, 0) else it }
            page = step / 32
            onPositionChange(steps.getOrNull(step)?.timeMs ?: 0L)
        }
    }

    LaunchedEffect(positionMs, playing, steps) {
        if (steps.isEmpty()) return@LaunchedEffect
        var synced = 0
        while (synced + 1 < steps.size && steps[synced + 1].timeMs <= positionMs) synced++
        step = if (playing) synced else nearestStep(steps, positionMs)
        page = step / 32
        held.clear()
        recentPresses.clear()
    }

    val keyboardStep = if (playing) nextNoteStep(steps, step + 1) else step
    val nextKeyboardStep = nextNoteStep(steps, keyboardStep + 1)

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
            modifier = Modifier.align(Alignment.CenterStart).fillMaxWidth(0.78f).fillMaxHeight()
                .padding(start = 88.dp, top = 2.dp, end = 8.dp, bottom = 2.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (readMode) {
                SheetWall(
                    steps = steps.map { it.keys },
                    currentStep = step,
                    page = page,
                    pageCount = pageCount,
                    onPrevious = { if (page > 0) page-- },
                    onNext = { if (page + 1 < pageCount) page++ },
                    onSelect = { selected ->
                        val target = if (steps.getOrNull(selected)?.keys?.isEmpty() == true) nextNoteStep(steps, selected) else selected
                        if (target in steps.indices) {
                            step = target; page = target / 32; held.clear(); recentPresses.clear(); onPositionChange(steps[target].timeMs)
                        }
                    }
                )
                Spacer(Modifier.height(5.dp))
            }
            PracticeKeyboard(
                pitch = pitch,
                current = (steps.getOrNull(keyboardStep)?.keys ?: intArrayOf()).toSet(),
                next = (steps.getOrNull(nextKeyboardStep)?.keys ?: intArrayOf()).toSet(),
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
    val keyWidth = if (compact) 43.dp else 76.dp
    val keyHeight = if (compact) 28.dp else 70.dp
    val horizontalGap = if (compact) 4.dp else 8.dp
    val verticalGap = if (compact) 3.dp else 7.dp
    val keyboardPadding = if (compact) 6.dp else 18.dp
    val touchFlashes = remember { androidx.compose.runtime.mutableStateListOf(*Array(15) { 0 }) }
    Column(
        modifier = Modifier.background(practicePanel, RoundedCornerShape(14.dp)).border(1.dp, Color(0xFF38383C), RoundedCornerShape(14.dp))
            .pointerInput(compact) {
                val keyW = keyWidth.toPx()
                val keyH = keyHeight.toPx()
                val gapX = horizontalGap.toPx()
                val gapY = verticalGap.toPx()
                val inset = keyboardPadding.toPx()
                val activePointers = mutableMapOf<androidx.compose.ui.input.pointer.PointerId, Int>()
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change ->
                            if (change.pressed && !change.previousPressed) {
                                val localX = change.position.x - inset
                                val localY = change.position.y - inset
                                val column = (localX / (keyW + gapX)).toInt()
                                val row = (localY / (keyH + gapY)).toInt()
                                val insideKey = column in 0..4 && row in 0..2 &&
                                    localX >= 0 && localY >= 0 &&
                                    localX - column * (keyW + gapX) <= keyW &&
                                    localY - row * (keyH + gapY) <= keyH
                                if (insideKey) {
                                    val key = row * 5 + column
                                    activePointers[change.id] = key
                                    touchFlashes[key]++
                                    onDown(key)
                                }
                            } else if (!change.pressed && change.previousPressed) {
                                activePointers.remove(change.id)?.let(onUp)
                            }
                        }
                    }
                }
            }
            .padding(keyboardPadding),
        verticalArrangement = Arrangement.spacedBy(verticalGap)
    ) {
        repeat(3) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(horizontalGap)) {
                repeat(5) { column ->
                    val key = row * 5 + column
                    val color = when (key) { in current -> practiceAccent; in next -> Color(0xFF405F88); else -> practiceKey }
                    val flash = touchFlashes[key]
                    val animatedBackground by animateColorAsState(color, tween(120), label = "practiceKeyColor")
                    val rotation = remember(key) { FloatAnimatable(45f) }
                    val corner = remember(key) { FloatAnimatable(3f) }
                    val keyScale = remember(key) { FloatAnimatable(1f) }
                    LaunchedEffect(flash) {
                        if (flash == 0) return@LaunchedEffect
                        launch {
                            rotation.snapTo(45f)
                            rotation.animateTo(405f, tween(360, easing = CubicBezierEasing(.42f, 0f, .58f, 1f)))
                            rotation.snapTo(45f)
                        }
                        launch {
                            corner.snapTo(3f)
                            corner.animateTo(15f, tween(180, easing = CubicBezierEasing(.445f, .05f, .55f, .95f)))
                            corner.animateTo(3f, tween(180, easing = CubicBezierEasing(.445f, .05f, .55f, .95f)))
                        }
                        launch {
                            keyScale.snapTo(1f)
                            keyScale.animateTo(.85f, tween(126, easing = LinearEasing))
                            keyScale.animateTo(1f, tween(234, easing = LinearEasing))
                        }
                    }
                    Box(
                        modifier = Modifier.size(keyWidth, keyHeight)
                            .graphicsLayer { scaleX = keyScale.value; scaleY = keyScale.value }
                            .background(animatedBackground, RoundedCornerShape(7.dp))
                            .border(1.dp, practiceBorder, RoundedCornerShape(7.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(Modifier.size(if (compact) 16.dp else 31.dp)) {
                            rotate(rotation.value) {
                                drawRoundRect(
                                    color = Color(0xFFD8D8DF),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner.value.dp.toPx()),
                                    style = Stroke(1.5.dp.toPx())
                                )
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
    Canvas(modifier.height(26.dp).background(if (selected) Color(0xFF303F55) else Color(0xFF242426), RoundedCornerShape(4.dp))
        .border(1.dp, if (selected) practiceAccent else Color(0xFF3A3A3D), RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(4.dp)) {
        val active = (keys ?: intArrayOf()).toSet()
        val cellW = size.width / 5f
        val cellH = size.height / 3f
        val baseRadius = minOf(cellW, cellH) * 0.18f
        repeat(15) { key ->
            val x = (key % 5 + .5f) * size.width / 5f
            val y = (key / 5 + .5f) * size.height / 3f
            drawCircle(
                if (key in active) practiceAccent else Color(0xFF77777D),
                radius = if (key in active) baseRadius * 1.45f else baseRadius,
                center = Offset(x, y)
            )
        }
    }
}

private data class PracticeStep(val keys: IntArray, val timeMs: Long)

private fun buildPracticeSteps(item: LibraryItem): List<PracticeStep> {
    val notes = item.song.songNotes.filter { it.key in 0..14 }.sortedBy { it.time }
    if (notes.isEmpty()) return emptyList()
    val cells = mutableListOf<Pair<Int, IntArray>>()
    var index = 0
    while (index < notes.size) {
        val time = notes[index].time
        val keys = linkedSetOf<Int>()
        while (index < notes.size && notes[index].time - time <= 20) keys += notes[index++].key
        cells += time to keys.toIntArray()
    }
    val msPerBeat = if (item.song.bpm > 0) 60_000.0 / item.song.bpm else 500.0
    val beatPositions = cells.map { it.first / msPerBeat }
    val subdivision = listOf(1, 2, 3, 4, 6, 8).firstOrNull { sd ->
        beatPositions.all { beat -> kotlin.math.abs(beat * sd - kotlin.math.round(beat * sd)) <= .18 }
    } ?: 4
    val unitMs = msPerBeat / subdivision
    val firstMs = cells.first().first.toDouble()
    val maxSlot = kotlin.math.round((cells.last().first - firstMs) / unitMs).toInt().coerceIn(0, 20_000)
    val slots = arrayOfNulls<IntArray>(maxSlot + 1)
    cells.forEach { (time, keys) ->
        val slot = kotlin.math.round((time - firstMs) / unitMs).toInt().coerceIn(0, maxSlot)
        slots[slot] = ((slots[slot]?.toList() ?: emptyList()) + keys.toList()).distinct().toIntArray()
    }
    val result = List(maxSlot + 1) { slot -> PracticeStep(slots[slot] ?: intArrayOf(), (firstMs + slot * unitMs).toLong()) }
    return result
}

private fun nextNoteStep(steps: List<PracticeStep>, from: Int): Int {
    for (index in from.coerceAtLeast(0) until steps.size) if (steps[index].keys.isNotEmpty()) return index
    return steps.size
}

private fun nearestStep(steps: List<PracticeStep>, timeMs: Long): Int =
    steps.indices.minByOrNull { kotlin.math.abs(steps[it].timeMs - timeMs) } ?: 0

private fun noteName(semitone: Int): String {
    val names = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
    return names[((semitone % 12) + 12) % 12]
}
