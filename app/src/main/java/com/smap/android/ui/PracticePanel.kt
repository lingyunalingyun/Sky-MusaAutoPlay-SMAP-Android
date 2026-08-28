package com.smap.android.ui

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smap.android.data.LibraryItem
import com.smap.android.FavoriteStarIcon
import com.smap.android.TransportVector
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
    paused: Boolean,
    playMode: Int,
    favorite: Boolean,
    instrumentLabel: String,
    speedLabel: String,
    onPositionChange: (Long) -> Unit,
    gameMode: Boolean,
    onBack: () -> Unit,
    onGameMode: () -> Unit,
    onPlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPlayMode: () -> Unit,
    onPlaylist: () -> Unit,
    onFavorite: () -> Unit,
    onInstrument: () -> Unit,
    onSpeed: () -> Unit,
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
            text = tr("返回"),
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp).background(practiceKey, RoundedCornerShape(7.dp))
                .clickable(onClick = onBack).padding(horizontal = 14.dp, vertical = 5.dp)
        )

        if (readMode) {
            Column(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(0.94f).padding(top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SheetWall(
                    steps = steps.map { it.keys },
                    currentStep = step,
                    page = page,
                    pageCount = pageCount,
                    onPrevious = { if (page > 0) page-- },
                    onNext = { if (page + 1 < pageCount) page++ },
                    onSelect = { selected ->
                        if (selected in steps.indices) {
                            val target = if (steps[selected].keys.isEmpty()) nextNoteStep(steps, selected) else selected
                            if (target < steps.size) {
                                step = target; page = target / 32; held.clear(); recentPresses.clear(); onPositionChange(steps[target].timeMs)
                            }
                        }
                    }
                )
                Spacer(Modifier.height(7.dp))
                PracticeKeyboard(
                    pitch = pitch,
                    current = (steps.getOrNull(keyboardStep)?.keys ?: intArrayOf()).toSet(),
                    next = (steps.getOrNull(nextKeyboardStep)?.keys ?: intArrayOf()).toSet(),
                    compact = true,
                    onDown = ::press,
                    onUp = { held -= it }
                )
            }
        } else {
            Box(Modifier.align(Alignment.Center).offset(x = 10.dp)) {
                PracticeKeyboard(
                    pitch = pitch,
                    current = (steps.getOrNull(keyboardStep)?.keys ?: intArrayOf()).toSet(),
                    next = (steps.getOrNull(nextKeyboardStep)?.keys ?: intArrayOf()).toSet(),
                    compact = false,
                    onDown = ::press,
                    onUp = { held -= it }
                )
            }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            PracticeSwitch(tr("读谱模式"), readMode) { readMode = it; page = step / 32 }
            PracticeSwitch(tr("打点模式"), metronome) { metronome = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("打点速度"), color = Color.White, fontSize = 11.sp, modifier = Modifier.width(72.dp))
                Text(
                    "$bpm BPM", color = if (metronome) Color.White else Color(0xFF77777C), fontSize = 10.sp,
                    modifier = Modifier.background(Color(0xFF2B2B2E), RoundedCornerShape(14.dp))
                        .clickable(enabled = metronome) { bpm = if (bpm >= 180) 60 else bpm + 15 }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
            PracticeSwitch(tr("游戏浮窗"), gameMode) { onGameMode() }
        }

        PracticeSongControls(
            item = item,
            playing = playing,
            paused = paused,
            playMode = playMode,
            favorite = favorite,
            instrumentLabel = instrumentLabel,
            speedLabel = speedLabel,
            onPlay = onPlay,
            onPrevious = onPrevious,
            onNext = onNext,
            onPlayMode = onPlayMode,
            onPlaylist = onPlaylist,
            onFavorite = onFavorite,
            onInstrument = onInstrument,
            onSpeed = onSpeed,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = if (readMode) 76.dp else 16.dp, bottom = 10.dp)
        )

        if (readMode) {
            Column(Modifier.align(Alignment.BottomEnd).padding(end = 175.dp, bottom = 12.dp)) {
                Text("BPM: ${item.song.bpm}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("时长: ${formatPracticeDuration(item.song.durationMs)}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("音符数: ${item.song.songNotes.size}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        Metronome(enabled = metronome, bpm = bpm)
    }
}

@Composable
private fun PracticeSongControls(
    item: LibraryItem,
    playing: Boolean,
    paused: Boolean,
    playMode: Int,
    favorite: Boolean,
    instrumentLabel: String,
    speedLabel: String,
    onPlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPlayMode: () -> Unit,
    onPlaylist: () -> Unit,
    onFavorite: () -> Unit,
    onInstrument: () -> Unit,
    onSpeed: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cover = remember(item.coverBytes) {
        item.coverBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    Column(modifier.width(160.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).background(Color(0xFF909090), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                if (cover != null) Image(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Text(tr("封面"), color = Color.Black, fontSize = 10.sp)
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(item.song.name, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.song.author ?: tr("未知"), color = Color.White, fontSize = 8.sp, maxLines = 1)
                Text(item.song.transcribedBy ?: tr("未知"), color = Color.White, fontSize = 8.sp, maxLines = 1)
            }
            FavoriteStarIcon(favorite, Modifier.size(24.dp).clickable(onClick = onFavorite).padding(3.dp))
        }
        Spacer(Modifier.height(7.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TransportVector(if (playMode == 1) "repeat_one" else if (playMode == 2) "shuffle" else "repeat", Modifier.size(24.dp).clickable(onClick = onPlayMode).padding(2.dp))
            TransportVector("previous", Modifier.size(26.dp).clickable(onClick = onPrevious).padding(3.dp))
            SMAPPlayButton(playing = playing && !paused, size = 42.dp, onClick = onPlay)
            TransportVector("next", Modifier.size(26.dp).clickable(onClick = onNext).padding(3.dp))
            TransportVector("list", Modifier.size(26.dp).clickable(onClick = onPlaylist).padding(4.dp))
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(instrumentLabel, color = Color.White, fontSize = 9.sp, modifier = Modifier.border(1.dp, Color(0xFF66666D), RoundedCornerShape(12.dp)).clickable(onClick = onInstrument).padding(horizontal = 8.dp, vertical = 3.dp))
            Text(speedLabel, color = Color.White, fontSize = 9.sp, modifier = Modifier.border(1.dp, Color(0xFF66666D), RoundedCornerShape(12.dp)).clickable(onClick = onSpeed).padding(horizontal = 8.dp, vertical = 3.dp))
        }
    }
}

@Composable
private fun PracticeSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 11.sp, modifier = Modifier.width(72.dp))
        Box(
            Modifier.width(40.dp).height(22.dp)
                .background(if (checked) Color(0xFF00AF32) else Color(0xFF303034), RoundedCornerShape(11.dp))
                .border(1.dp, Color(0xFF66666D), RoundedCornerShape(11.dp))
                .clickable { onChecked(!checked) }
                .padding(2.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(Modifier.size(18.dp).background(Color(0xFFD0D0D3), CircleShape))
        }
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
    val keyWidth = if (compact) 48.dp else 68.dp
    val keyHeight = keyWidth
    val horizontalGap = if (compact) 7.dp else 8.dp
    val verticalGap = if (compact) 7.dp else 7.dp
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
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        PageButton(direction = -1, number = page + 1, onClick = onPrevious)
        Spacer(Modifier.weight(1f))
        Column(Modifier.width(448.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(4) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    repeat(8) { column ->
                        val index = page * 32 + row * 8 + column
                        MiniStep(steps.getOrNull(index), index == currentStep, Modifier.weight(1f)) {
                            if (index < steps.size) onSelect(index)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        PageButton(direction = 1, number = pageCount, onClick = onNext)
    }
}

@Composable
private fun PageButton(direction: Int, number: Int, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 8.dp)) {
        Box(
            Modifier.size(42.dp).border(1.dp, Color(0xFF606066), CircleShape).clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(18.dp)) {
                val arrow = Path().apply {
                    if (direction < 0) {
                        moveTo(size.width * .72f, size.height * .12f)
                        lineTo(size.width * .25f, size.height * .5f)
                        lineTo(size.width * .72f, size.height * .88f)
                    } else {
                        moveTo(size.width * .28f, size.height * .12f)
                        lineTo(size.width * .75f, size.height * .5f)
                        lineTo(size.width * .28f, size.height * .88f)
                    }
                    close()
                }
                drawPath(arrow, Color(0xFFE7E7EA))
            }
        }
        Text(number.toString(), color = Color(0xFF9A9AA1), fontSize = 11.sp)
    }
}

@Composable
private fun MiniStep(keys: IntArray?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val opacity = when {
        keys == null -> .35f
        keys.isEmpty() && !selected -> .5f
        else -> 1f
    }
    Canvas(modifier.height(32.dp).graphicsLayer { alpha = opacity }
        .background(if (selected) Color(0xFF303F55) else Color(0xFF242426), RoundedCornerShape(4.dp))
        .border(if (selected) 2.dp else 1.dp, if (selected) practiceAccent else Color(0xFF3A3A3D), RoundedCornerShape(4.dp))
        .clickable(enabled = keys != null, onClick = onClick).padding(4.dp)) {
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
    val msPerBeat = refineMsPerBeat(cells.map { it.first.toDouble() }, item.song.bpm.takeIf { it > 0 } ?: 120)
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

/** Match the desktop loader: stored BPM is integral, so refine it before building the beat grid. */
private fun refineMsPerBeat(times: List<Double>, nominalBpm: Int): Double {
    val positiveTimes = times.filter { it > 0 }
    if (positiveTimes.isEmpty()) return 60_000.0 / nominalBpm
    var bestBpm = nominalBpm.toDouble()
    var bestError = Double.MAX_VALUE
    var candidate = nominalBpm - 1.0
    while (candidate <= nominalBpm + 1.0 + 1e-9) {
        val cellMs = 15_000.0 / candidate
        var error = 0.0
        positiveTimes.forEach { time ->
            val remainder = time % cellMs
            error += minOf(remainder, cellMs - remainder)
        }
        if (error < bestError) {
            bestError = error
            bestBpm = candidate
        }
        candidate += .01
    }
    return 60_000.0 / bestBpm
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

private fun formatPracticeDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0) / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
