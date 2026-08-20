package com.smap.android

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Animatable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.Animatable as FloatAnimatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.smap.android.data.LibraryItem
import com.smap.android.data.LibraryPreferences
import com.smap.android.cloud.CloudApi
import com.smap.android.cloud.CloudSheet
import com.smap.android.cloud.CloudUser
import com.smap.android.data.SongRepository
import com.smap.android.engine.AudioEngine
import com.smap.android.engine.KeyPoint
import com.smap.android.engine.PlayerEngine
import com.smap.android.midi.MidiImporter
import com.smap.android.service.FloatService
import com.smap.android.ui.SMAPPlayButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random
import java.text.Collator

// 与桌面版 SMAP 深色主题共用的视觉语言
private val WindowColor = Color(0xFF121214)
private val PanelColor = Color(0xFF1C1C1C)
private val CardColor = Color(0xFF18181A)
private val AccentColor = Color(0xFF5AA0FF)
private val BorderColor = Color(0xFF38383C)
private val SecondaryText = Color(0xFF9A9AA1)
private val LocalBlue = Color(0xFF2F6FD0)
private val CloudGreen = Color(0xFF12795A)
private val FavoriteGold = Color(0xFFE0B700)

private data class PendingMidi(
    val fileName: String,
    val bytes: ByteArray,
    val analysis: MidiImporter.Analysis
)

class MainActivity : ComponentActivity() {
    private var startFloatAfterPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            SMAPTheme {
                MainScreen(onGamePerform = ::startGamePerform)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (startFloatAfterPermission && Settings.canDrawOverlays(this)) {
            startFloatAfterPermission = false
            FloatService.start(this)
        }
    }

    private fun startGamePerform() {
        if (Settings.canDrawOverlays(this)) {
            FloatService.start(this)
            return
        }

        startFloatAfterPermission = true
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }
}

@Composable
fun SMAPTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = AccentColor,
            background = WindowColor,
            surface = PanelColor
        ),
        content = content
    )
}

@Composable
fun MainScreen(onGamePerform: () -> Unit = {}) {
    var items by remember { mutableStateOf<List<LibraryItem>>(emptyList()) }
    var selectedItem by remember { mutableStateOf<LibraryItem?>(null) }
    var nowPlaying by remember { mutableStateOf<LibraryItem?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0L) }
    val keyFlashes = remember { mutableStateListOf(*Array(15) { 0 }) }
    var moreItem by remember { mutableStateOf<LibraryItem?>(null) }
    var deleteItem by remember { mutableStateOf<LibraryItem?>(null) }
    var showPlaylist by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showInstrument by remember { mutableStateOf(false) }
    var showPitch by remember { mutableStateOf(false) }
    var pendingMidis by remember { mutableStateOf<List<PendingMidi>>(emptyList()) }
    var navTab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var sortMode by remember { mutableIntStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { SongRepository(context) }
    val preferences = remember { LibraryPreferences(context) }
    val cloudApi = remember { CloudApi(context) }
    var cloudUser by remember { mutableStateOf(cloudApi.user) }
    var showLogin by remember { mutableStateOf(false) }
    var loginBusy by remember { mutableStateOf(false) }
    var loginError by remember { mutableStateOf<String?>(null) }
    var favorites by remember { mutableStateOf(preferences.favorites()) }
    var playlistFiles by remember { mutableStateOf(preferences.playlist()) }
    var playMode by remember { mutableIntStateOf(preferences.playMode()) }
    var speed by remember { mutableStateOf(preferences.speed()) }
    var randomSpeed by remember { mutableStateOf(preferences.randomSpeed()) }
    var cave by remember { mutableStateOf(preferences.cave()) }
    var playToken by remember { mutableIntStateOf(0) }
    var lastPersistedBucket by remember { mutableStateOf(-1L) }
    val scope = rememberCoroutineScope()
    val audioEngine = remember { AudioEngine(context) }
    val playerEngine = remember { PlayerEngine(scope) }
    var instrument by remember { mutableStateOf(preferences.instrument().takeIf { it in audioEngine.instruments } ?: "Piano") }
    var pitch by remember(instrument) { mutableIntStateOf(preferences.pitch(instrument)) }

    LaunchedEffect(instrument, pitch) {
        withContext(Dispatchers.IO) { audioEngine.setInstrument(instrument) }
        audioEngine.setPitch(pitch)
    }

    LaunchedEffect(cave) { withContext(Dispatchers.IO) { audioEngine.setCave(cave) } }

    DisposableEffect(Unit) {
        onDispose {
            playerEngine.stop()
            audioEngine.release()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val midiFiles = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
                    if (!name.endsWith(".mid", true) && !name.endsWith(".midi", true)) return@mapNotNull null
                    runCatching {
                        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                        PendingMidi(name, bytes, MidiImporter.analyze(bytes))
                    }.getOrNull()
                }
            }
            val midiUris = uris.filter { uri ->
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }.orEmpty()
                name.endsWith(".mid", true) || name.endsWith(".midi", true)
            }.toSet()
            val results = withContext(Dispatchers.IO) { uris.filterNot { it in midiUris }.map(repository::importSong) }
            items = withContext(Dispatchers.IO) { repository.loadSongs() }
            navTab = 0
            val success = results.count { it.isSuccess }
            val failure = results.size - success
            pendingMidis = pendingMidis + midiFiles
            val message = when {
                results.isEmpty() && midiFiles.isNotEmpty() -> null
                failure == 0 -> "已导入 $success 首曲谱"
                success == 0 -> results.firstNotNullOfOrNull { it.exceptionOrNull()?.message } ?: "导入失败"
                else -> "已导入 $success 首，失败 $failure 首"
            }
            message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
        }
    }

    LaunchedEffect(Unit) {
        items = withContext(Dispatchers.IO) { repository.loadSongs() }
        preferences.lastSong()?.let { fileName ->
            items.find { it.fileName == fileName }?.let { restored ->
                selectedItem = restored
                nowPlaying = restored
                positionMs = preferences.lastPosition().coerceAtMost(restored.song.durationMs)
            }
        }
    }

    val nameComparator = remember { Comparator<LibraryItem> { a, b -> Collator.getInstance().compare(a.song.name, b.song.name) } }
    val visibleItems = items
        .asSequence()
        .filter { navTab != 2 || it.fileName in favorites }
        .filter {
            query.isBlank() || it.song.name.contains(query, true) ||
                it.song.author.orEmpty().contains(query, true) || it.song.transcribedBy.orEmpty().contains(query, true) || it.fileName.contains(query, true)
        }
        .let { source ->
            when (sortMode) {
                1 -> source.sortedWith(nameComparator.reversed())
                2 -> source.sortedWith(Comparator { a, b ->
                    val favoriteOrder = (b.fileName in favorites).compareTo(a.fileName in favorites)
                    if (favoriteOrder != 0) favoriteOrder else nameComparator.compare(a, b)
                })
                else -> source.sortedWith(nameComparator)
            }
        }
        .toList()

    val playlist = playlistFiles.mapNotNull { fileName -> items.find { it.fileName == fileName } }

    fun startPlayback(
        item: LibraryItem,
        fromPlaylist: Boolean,
        queueSnapshot: List<LibraryItem> = playlist,
        startPositionMs: Long = 0
    ) {
        val token = ++playToken
        playerEngine.stop()
        audioEngine.stopAll()
        playerEngine.setSpeed(speed)
        playerEngine.setRandomSpeed(randomSpeed)
        selectedItem = item
        nowPlaying = item
        isPlaying = true
        isPaused = false
        positionMs = startPositionMs
        playerEngine.play(
            song = item.song,
            keys = List(15) { KeyPoint(0f, 0f) },
            screenW = 0,
            screenH = 0,
            startPositionMs = startPositionMs,
            sendScreenTaps = false,
            onNoteFired = { key ->
                audioEngine.play(key)
                scope.launch {
                    keyFlashes[key]++
                }
            },
            onProgress = { progress -> scope.launch {
                positionMs = progress
                val bucket = progress / 2_000
                if (bucket != lastPersistedBucket) {
                    lastPersistedBucket = bucket
                    preferences.savePlayback(item.fileName, progress)
                }
            } },
            onFinished = {
                scope.launch {
                    if (fromPlaylist) {
                        val next = when (playMode) {
                            1 -> item
                            2 -> if (queueSnapshot.size <= 1) queueSnapshot.firstOrNull() else queueSnapshot.filterNot { it.fileName == item.fileName }.random(Random)
                            else -> queueSnapshot.getOrNull((queueSnapshot.indexOfFirst { it.fileName == item.fileName } + 1).coerceAtLeast(0) % queueSnapshot.size.coerceAtLeast(1))
                        }
                        isPlaying = false
                        delay(2_000)
                        if (next != null && token == playToken) startPlayback(next, true, queueSnapshot)
                    } else {
                        isPlaying = false
                    }
                }
            }
        )
    }

    fun addToPlaylistAndPlay(item: LibraryItem) {
        val updatedFiles = if (item.fileName in playlistFiles) playlistFiles else playlistFiles + item.fileName
        if (updatedFiles !== playlistFiles) {
            playlistFiles = updatedFiles
            preferences.savePlaylist(updatedFiles)
        }
        val updatedQueue = updatedFiles.mapNotNull { fileName -> items.find { it.fileName == fileName } }
        startPlayback(item, true, updatedQueue)
    }

    fun stopPlayback() {
        playToken++
        playerEngine.stop()
        audioEngine.stopAll()
        isPlaying = false
        isPaused = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WindowColor)
    ) {
        Row(modifier = Modifier.weight(1f).fillMaxWidth().padding(6.dp)) {
            Sidebar(selected = navTab, onSelect = { tab ->
                if (tab == 3) {
                    importLauncher.launch(arrayOf("application/json", "text/plain", "audio/midi", "audio/x-midi", "application/octet-stream"))
                } else {
                    navTab = tab
                }
            })

            Column(
                modifier = Modifier
                    .weight(0.42f)
                    .fillMaxHeight()
                    .border(1.dp, BorderColor)
            ) {
                if (navTab == 0 || navTab == 2) {
                    LibraryFilters(
                        query = query,
                        onQueryChange = { query = it },
                        firstLabel = "全部",
                        secondLabel = when (sortMode) { 1 -> "名称 Z-A"; 2 -> "收藏优先"; else -> "名称 A-Z" },
                        onFirst = { query = "" },
                        onSecond = { sortMode = (sortMode + 1) % 3 }
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 6.dp, vertical = 5.dp)
                    ) {
                        items(visibleItems, key = { it.fileName }) { item ->
                            SongCard(
                                item = item,
                                selected = nowPlaying?.fileName == item.fileName,
                                favorite = item.fileName in favorites,
                                onFavorite = { favorites = preferences.toggleFavorite(item.fileName) },
                                onAdd = {
                                    if (item.fileName !in playlistFiles) {
                                        playlistFiles = playlistFiles + item.fileName
                                        preferences.savePlaylist(playlistFiles)
                                        Toast.makeText(context, "已加入播放列表「${item.song.name}」", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "「${item.song.name}」已在播放列表", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onClick = { addToPlaylistAndPlay(item) },
                                onLongClick = { moreItem = item }
                            )
                            Spacer(Modifier.height(2.dp))
                        }
                        if (visibleItems.isEmpty()) {
                            item { EmptyMessage(if (navTab == 2) "还没有收藏曲谱" else "没有匹配的曲谱") }
                        }
                    }
                } else if (navTab == 1) {
                    CloudLibrary(
                        api = cloudApi,
                        onDownloaded = { sheet ->
                            withContext(Dispatchers.IO) {
                                cloudApi.download(sheet).getOrThrow().let { repository.importDownloaded(sheet.title, it).getOrThrow() }
                            }
                            items = withContext(Dispatchers.IO) { repository.loadSongs() }
                            Toast.makeText(context, "已下载到本地曲库「${sheet.title}」", Toast.LENGTH_SHORT).show()
                        }
                    )
                } else if (navTab == 4) {
                    ProfilePanel(
                        user = cloudUser,
                        onLogin = { loginError = null; showLogin = true },
                        onLogout = { cloudApi.logout(); cloudUser = null },
                        onHome = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CloudApi.BASE))) }
                    )
                } else {
                    val message = when (navTab) {
                        5 -> "SMAP Android 0.1.0\n本地曲库与练习功能已启用"
                        else -> ""
                    }
                    EmptyMessage(message)
                }
            }

            RightPanel(
                keyFlashes = keyFlashes,
                pitch = pitch,
                onKeyPress = { key ->
                    audioEngine.play(key)
                    keyFlashes[key]++
                },
                modifier = Modifier.weight(0.58f)
            )
        }
        BottomBar(
            item = nowPlaying ?: selectedItem,
            playing = isPlaying,
            paused = isPaused,
            positionMs = positionMs,
            playMode = playMode,
            speedLabel = if (randomSpeed) "随机速度" else "${speed}×",
            instrumentLabel = instrument,
            pitchLabel = pitch,
            cave = cave,
            favorite = (nowPlaying ?: selectedItem)?.fileName in favorites,
            onPlay = {
                if (isPlaying && !isPaused) {
                    playerEngine.pause()
                    isPaused = true
                    (nowPlaying ?: selectedItem)?.let { preferences.savePlayback(it.fileName, positionMs) }
                } else if (isPlaying) {
                    playerEngine.resume()
                    isPaused = false
                }
                else if (playlist.isNotEmpty()) {
                    val target = nowPlaying?.takeIf { current -> playlist.any { it.fileName == current.fileName } } ?: playlist.first()
                    startPlayback(target, true, playlist, if (target.fileName == nowPlaying?.fileName) positionMs else 0L)
                }
                else Toast.makeText(context, "播放列表为空，请长按歌曲添加", Toast.LENGTH_SHORT).show()
            },
            onPrevious = {
                if (playlist.isNotEmpty()) {
                    val index = playlist.indexOfFirst { it.fileName == nowPlaying?.fileName }
                    startPlayback(playlist[if (index <= 0) playlist.lastIndex else index - 1], true)
                }
            },
            onNext = {
                if (playlist.isNotEmpty()) {
                    val index = playlist.indexOfFirst { it.fileName == nowPlaying?.fileName }
                    startPlayback(playlist[(index + 1).coerceAtLeast(0) % playlist.size], true)
                }
            },
            onPlayMode = {
                playMode = (playMode + 1) % 3
                preferences.savePlayMode(playMode)
            },
            onSpeed = { showSpeed = true },
            onInstrument = { showInstrument = true },
            onPitch = { showPitch = true },
            onCave = {
                cave = !cave
                preferences.saveCave(cave)
            },
            onPlaylist = { showPlaylist = true },
            onFavorite = {
                (nowPlaying ?: selectedItem)?.let { favorites = preferences.toggleFavorite(it.fileName) }
            },
            onSeek = { fraction ->
                val current = nowPlaying ?: return@BottomBar
                val target = (current.song.durationMs * fraction).toLong()
                val wasPaused = isPaused
                startPlayback(current, true, playlist, target)
                if (wasPaused) {
                    playerEngine.pause()
                    isPaused = true
                }
            }
        )
    }

    if (showLogin) {
        CloudLoginDialog(
            busy = loginBusy,
            error = loginError,
            onDismiss = { if (!loginBusy) showLogin = false },
            onSubmit = { username, password ->
                loginBusy = true
                loginError = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) { cloudApi.login(username, password) }
                    loginBusy = false
                    result.onSuccess { cloudUser = it; showLogin = false }
                        .onFailure { loginError = it.message ?: "登录失败" }
                }
            }
        )
    }

    moreItem?.let { item ->
        SongOptionsDialog(
            item = item,
            favorite = item.fileName in favorites,
            inPlaylist = item.fileName in playlistFiles,
            onDismiss = { moreItem = null },
            onPlay = { moreItem = null; addToPlaylistAndPlay(item) },
            onFavorite = { favorites = preferences.toggleFavorite(item.fileName); moreItem = null },
            onAddPlaylist = {
                if (item.fileName !in playlistFiles) {
                    playlistFiles = playlistFiles + item.fileName
                    preferences.savePlaylist(playlistFiles)
                }
                moreItem = null
            },
            onDelete = { moreItem = null; deleteItem = item }
        )
    }

    deleteItem?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteItem = null },
            containerColor = PanelColor,
            title = { Text("从曲库中移除", color = Color.White) },
            text = { Text("确定从曲库中移除「${item.song.name}」吗？", color = Color.White) },
            confirmButton = {
                TextButton(onClick = {
                    deleteItem = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { repository.deleteSong(item.fileName) }
                        if (result.isSuccess) {
                            if (nowPlaying?.fileName == item.fileName) {
                                stopPlayback()
                                nowPlaying = null
                                selectedItem = null
                            }
                            favorites = preferences.removeSong(item.fileName)
                            playlistFiles = playlistFiles - item.fileName
                            items = withContext(Dispatchers.IO) { repository.loadSongs() }
                            Toast.makeText(context, "已从曲库移除「${item.song.name}」", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "删除失败：${result.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("移除", color = Color(0xFFE74C3C)) }
            },
            dismissButton = { TextButton(onClick = { deleteItem = null }) { Text("取消") } }
        )
    }

    if (showPlaylist) {
        PlaylistDialog(
            items = playlist,
            nowPlaying = nowPlaying,
            playing = isPlaying,
            paused = isPaused,
            favorites = favorites,
            onDismiss = { showPlaylist = false },
            onPlay = { startPlayback(it, true) },
            onFavorite = { favorites = preferences.toggleFavorite(it.fileName) },
            onRemove = { item ->
                playlistFiles = playlistFiles - item.fileName
                preferences.savePlaylist(playlistFiles)
                if (nowPlaying?.fileName == item.fileName) stopPlayback()
            },
            onClear = {
                if (nowPlaying?.let { current -> playlist.any { it.fileName == current.fileName } } == true) {
                    stopPlayback()
                    nowPlaying = null
                }
                playlistFiles = emptyList()
                preferences.savePlaylist(emptyList())
            }
        )
    }

    if (showSpeed) {
        SpeedDialog(
            speed = speed,
            random = randomSpeed,
            onDismiss = { showSpeed = false },
            onSelect = { selectedSpeed, random ->
                speed = selectedSpeed
                randomSpeed = random
                playerEngine.setSpeed(selectedSpeed)
                playerEngine.setRandomSpeed(random)
                preferences.saveSpeed(selectedSpeed, random)
                showSpeed = false
            }
        )
    }

    if (showInstrument) {
        val options = audioEngine.instruments.map { "$it|${localizedInstrument(it)}" }
        ChoiceDialog("选择音色", options, "$instrument|${localizedInstrument(instrument)}", { showInstrument = false }) { selected ->
            val key = selected.substringBefore('|')
            instrument = key
            pitch = preferences.pitch(key)
            preferences.saveInstrument(key)
            showInstrument = false
        }
    }

    if (showPitch) {
        val pitches = (-24..24).map { value -> "$value|${if (value > 0) "+" else ""}$value ${noteName(value)}" }
        ChoiceDialog("音高", pitches, "$pitch|${if (pitch > 0) "+" else ""}$pitch ${noteName(pitch)}", { showPitch = false }) { selected ->
            pitch = selected.substringBefore('|').toInt()
            preferences.savePitch(instrument, pitch)
            showPitch = false
        }
    }

    pendingMidis.firstOrNull()?.let { pending ->
        MidiImportDialog(
            pending = pending,
            onCancel = { pendingMidis = pendingMidis.drop(1) },
            onImport = { name, selectedTracks, autoAlign, octave ->
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        repository.importMidi(pending.bytes, name, selectedTracks, autoAlign, octave)
                    }
                    pendingMidis = pendingMidis.drop(1)
                    if (result.isSuccess) {
                        items = withContext(Dispatchers.IO) { repository.loadSongs() }
                        Toast.makeText(context, "已导入 1 首曲谱", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, result.exceptionOrNull()?.message ?: "导入失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }
}

/** 左侧边栏 */
@Composable
private fun CloudLibrary(api: CloudApi, onDownloaded: suspend (CloudSheet) -> Unit) {
    var query by remember { mutableStateOf("") }
    var appliedQuery by remember { mutableStateOf("") }
    var sortMode by remember { mutableIntStateOf(1) }
    var difficulty by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var downloadingId by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var sheets by remember { mutableStateOf<List<CloudSheet>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val sortKeys = listOf("newest", "newest", "hot", "downloads")
    val sortLabels = listOf("A-Z", "上传时间", "点赞", "下载量")

    LaunchedEffect(query) {
        delay(350)
        appliedQuery = query
    }

    LaunchedEffect(appliedQuery, sortMode, difficulty) {
        loading = true
        error = null
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val first = api.list(appliedQuery, sortKeys[sortMode], difficulty, 1, 100).getOrThrow()
                if (first.pages <= 1) first else {
                    val all = first.items.toMutableList()
                    for (nextPage in 2..first.pages) {
                        all += api.list(appliedQuery, sortKeys[sortMode], difficulty, nextPage, 100).getOrThrow().items
                    }
                    first.copy(items = all)
                }
            }
        }
        result.onSuccess { response ->
            total = response.total
            sheets = if (sortMode == 0) response.items.sortedWith(compareBy(Collator.getInstance()) { it.title }) else response.items
        }.onFailure { error = it.message ?: "加载失败"; sheets = emptyList() }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        LibraryFilters(
            query = query,
            onQueryChange = { query = it },
            firstLabel = if (difficulty == 0) "全部难度" else "★".repeat(difficulty),
            secondLabel = sortLabels[sortMode],
            onFirst = { difficulty = (difficulty + 1) % 6 },
            onSecond = { sortMode = (sortMode + 1) % sortLabels.size }
        )
        when {
            loading -> EmptyMessage("正在加载云端曲库…")
            error != null -> EmptyMessage(error!!)
            sheets.isEmpty() -> EmptyMessage("没有匹配的云端曲谱")
            else -> LazyColumn(Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 5.dp)) {
                items(sheets, key = { it.id }) { sheet ->
                    Surface(Modifier.fillMaxWidth().height(72.dp), color = Color.Transparent) {
                        Row(Modifier.padding(horizontal = 3.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            CloudCover(sheet, api)
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f)) {
                                Text(sheet.title, color = Color.White, fontSize = 13.sp, lineHeight = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(sheet.artist.ifBlank { "未知作者" }, color = SecondaryText, fontSize = 9.sp, lineHeight = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(sheet.transcribedBy.ifBlank { "未知做谱者" }, color = SecondaryText, fontSize = 9.sp, lineHeight = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    val stars = "★".repeat(sheet.difficulty.coerceIn(0, 5))
                                    if (stars.isNotEmpty()) {
                                        Spacer(Modifier.width(5.dp))
                                        Text(stars, color = FavoriteGold, fontSize = 9.sp, lineHeight = 11.sp, maxLines = 1)
                                    }
                                }
                            }
                            Text("↓${sheet.downloads}", color = SecondaryText, fontSize = 9.sp)
                            Spacer(Modifier.width(6.dp))
                            CloudDownloadIcon(
                                downloading = downloadingId == sheet.id,
                                enabled = downloadingId == null,
                                modifier = Modifier.size(30.dp).clickable(enabled = downloadingId == null) {
                                downloadingId = sheet.id
                                scope.launch {
                                    runCatching { onDownloaded(sheet) }
                                        .onFailure { error = it.message ?: "下载失败" }
                                    downloadingId = null
                                }
                            })
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                }
            }
        }
        Text("共 $total 首", color = SecondaryText, fontSize = 9.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 3.dp))
    }
}

@Composable
private fun CloudCover(sheet: CloudSheet, api: CloudApi) {
    var bytes by remember(sheet.coverUrl) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(sheet.coverUrl) {
        bytes = withContext(Dispatchers.IO) { api.cover(sheet) }
    }
    val cover = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    Box(Modifier.size(46.dp).background(CardColor, RoundedCornerShape(6.dp)).border(1.dp, BorderColor, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
        if (cover != null) Image(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text("♪", color = SecondaryText, fontSize = 18.sp)
    }
}

@Composable
private fun CloudDownloadIcon(downloading: Boolean, enabled: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.padding(5.dp)) {
        val color = if (enabled) Color(0xFFE1E1E6) else SecondaryText
        val stroke = 2.dp.toPx()
        if (downloading) {
            drawCircle(color, radius = size.minDimension * 0.32f, style = Stroke(stroke))
        } else {
            drawLine(color, androidx.compose.ui.geometry.Offset(size.width * .5f, size.height * .12f), androidx.compose.ui.geometry.Offset(size.width * .5f, size.height * .65f), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(color, androidx.compose.ui.geometry.Offset(size.width * .28f, size.height * .45f), androidx.compose.ui.geometry.Offset(size.width * .5f, size.height * .67f), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(color, androidx.compose.ui.geometry.Offset(size.width * .72f, size.height * .45f), androidx.compose.ui.geometry.Offset(size.width * .5f, size.height * .67f), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(color, androidx.compose.ui.geometry.Offset(size.width * .2f, size.height * .86f), androidx.compose.ui.geometry.Offset(size.width * .8f, size.height * .86f), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

@Composable
private fun ProfilePanel(user: CloudUser?, onLogin: () -> Unit, onLogout: () -> Unit, onHome: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(76.dp).background(CardColor, CircleShape).border(1.dp, BorderColor, CircleShape), contentAlignment = Alignment.Center) {
                Text(user?.username?.firstOrNull()?.uppercase() ?: "?", color = Color.White, fontSize = 30.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            Text(user?.username ?: "尚未登录", color = Color.White, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Text(if (user == null) "登录缪斯树屋账号" else "已登录 · UID ${user.id}", color = SecondaryText, fontSize = 11.sp)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (user == null) FilterChip("登录", Modifier.width(120.dp), onLogin)
                else {
                    FilterChip("个人主页", Modifier.width(120.dp), onHome)
                    FilterChip("退出账号", Modifier.width(120.dp), onLogout)
                }
            }
        }
    }
}

@Composable
private fun CloudLoginDialog(
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (String, String) -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.width(380.dp), shape = RoundedCornerShape(12.dp), color = PanelColor, border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor)) {
            Column(Modifier.padding(18.dp)) {
                Text("登录 — 缪斯树屋", color = Color.White, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                CloudInput(username, { username = it }, "用户名或邮箱")
                Spacer(Modifier.height(8.dp))
                CloudInput(password, { password = it }, "密码", true)
                error?.let { Text(it, color = Color(0xFFFF6B6B), fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp)) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") }
                    TextButton(enabled = !busy && username.isNotBlank() && password.isNotBlank(), onClick = { onSubmit(username.trim(), password) }) {
                        Text(if (busy) "登录中…" else "登录")
                    }
                }
            }
        }
    }
}

@Composable
private fun CloudInput(value: String, onChange: (String) -> Unit, hint: String, password: Boolean = false) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
        cursorBrush = SolidColor(AccentColor),
        modifier = Modifier.fillMaxWidth().height(42.dp).background(CardColor, RoundedCornerShape(7.dp)).border(1.dp, BorderColor, RoundedCornerShape(7.dp)),
        decorationBox = { input -> Box(Modifier.fillMaxSize().padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(hint, color = SecondaryText, fontSize = 12.sp)
            input()
        } }
    )
}

@Composable
fun Sidebar(selected: Int, onSelect: (Int) -> Unit) {
    Column(
        modifier = Modifier
            .width(128.dp)
            .fillMaxHeight()
            .background(PanelColor)
            .border(1.dp, BorderColor)
    ) {
        NavButton("本地曲库", selected == 0, LocalBlue, Modifier.weight(1f)) { onSelect(0) }
        NavButton("云端曲库", selected == 1, CloudGreen, Modifier.weight(1f)) { onSelect(1) }
        NavButton("我的收藏", selected == 2, FavoriteGold, Modifier.weight(1f)) { onSelect(2) }
        NavButton("导入歌曲", selected == 3, Color(0xFF7B7B80), Modifier.weight(1f)) { onSelect(3) }
        NavButton("个人信息", selected == 4, Color(0xFF7B7B80), Modifier.weight(1f)) { onSelect(4) }
        NavButton("设置", selected == 5, Color(0xFF7B7B80), Modifier.weight(1f)) { onSelect(5) }
    }
}

@Composable
fun NavButton(label: String, active: Boolean, activeColor: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(if (active) Color(0xFF29292C) else PanelColor)
            .border(0.5.dp, BorderColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 11.sp, color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        if (active || label == "本地曲库" || label == "云端曲库" || label == "我的收藏") {
            Box(Modifier.fillMaxHeight().width(4.dp).background(activeColor).align(Alignment.CenterEnd))
        }
    }
}

@Composable
fun LibraryFilters(
    query: String,
    onQueryChange: (String) -> Unit,
    firstLabel: String,
    secondLabel: String,
    onFirst: () -> Unit,
    onSecond: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 11.sp),
            cursorBrush = SolidColor(AccentColor),
            modifier = Modifier.fillMaxWidth().height(30.dp).background(CardColor).border(1.dp, BorderColor),
            decorationBox = { input ->
                Box(Modifier.fillMaxSize().padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text("搜索", color = Color(0xFF77777D), fontSize = 11.sp)
                    input()
                }
            }
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            FilterChip(firstLabel, Modifier.weight(1f), onFirst)
            FilterChip(secondLabel, Modifier.weight(1f), onSecond)
        }
    }
}

@Composable
fun FilterChip(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.height(30.dp).background(PanelColor).border(1.dp, BorderColor).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    }
}

/** 曲库卡片 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongCard(
    item: LibraryItem,
    selected: Boolean,
    favorite: Boolean,
    onFavorite: () -> Unit,
    onAdd: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val song = item.song
    val cover = remember(item.coverBytes) {
        item.coverBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(5.dp),
        color = if (selected) Color(0xFF454552) else Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .background(PanelColor, RoundedCornerShape(6.dp))
                    .border(1.dp, BorderColor, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (cover != null) {
                    Image(cover, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Text("♪", color = SecondaryText, fontSize = 20.sp)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(song.name, color = Color.White, fontSize = 13.sp, lineHeight = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.author ?: "未知作者", color = SecondaryText, fontSize = 9.sp, lineHeight = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.transcribedBy ?: "未知做谱者", color = SecondaryText, fontSize = 9.sp, lineHeight = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(6.dp))
            if (selected) {
                Text("＋", color = Color.White, fontSize = 20.sp, modifier = Modifier.clickable(onClick = onAdd).padding(4.dp))
                FavoriteStarIcon(filled = favorite, modifier = Modifier.size(28.dp).clickable(onClick = onFavorite).padding(4.dp))
                Text("•••", color = SecondaryText, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onLongClick).padding(6.dp))
            } else {
                if (favorite) {
                    FavoriteStarIcon(filled = true, modifier = Modifier.size(23.dp).padding(4.dp))
                    Spacer(Modifier.width(2.dp))
                }
                Text(formatDuration(song.durationMs), color = SecondaryText, fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun EmptyMessage(message: String) {
    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Text(message, color = SecondaryText, fontSize = 12.sp)
    }
}

@Composable
fun RightPanel(
    keyFlashes: List<Int> = List(15) { 0 },
    pitch: Int = 0,
    onKeyPress: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(PanelColor)
            .border(1.dp, BorderColor)
            .padding(12.dp)
    ) {
        Spacer(Modifier.height(4.dp))
        val scaleSemitones = intArrayOf(0, 2, 4, 5, 7, 9, 11, 12, 14, 16, 17, 19, 21, 23, 24)
        repeat(3) { rowIndex ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(5) { columnIndex ->
                    val keyIndex = rowIndex * 5 + columnIndex
                    KeyboardKey(noteName(scaleSemitones[keyIndex] + pitch), keyFlashes[keyIndex], { onKeyPress(keyIndex) }, Modifier.weight(1f))
                }
            }
            if (rowIndex < 2) Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
fun KeyboardKey(label: String, flash: Int = 0, onClick: () -> Unit = {}, modifier: Modifier = Modifier) {
    val background = remember { Animatable(Color(0xFF4A4A4A)) }
    val rotation = remember { FloatAnimatable(45f) }
    val round = remember { FloatAnimatable(3f) }
    val scale = remember { FloatAnimatable(1f) }

    LaunchedEffect(flash) {
        if (flash == 0) return@LaunchedEffect
        launch {
            background.snapTo(Color(0xFF222222))
            background.animateTo(Color(0xFF4A4A4A), tween(240))
        }
        launch {
            rotation.snapTo(45f)
            rotation.animateTo(405f, tween(360, easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)))
            rotation.snapTo(45f)
        }
        launch {
            round.snapTo(3f)
            round.animateTo(15f, tween(180, easing = CubicBezierEasing(0.445f, 0.05f, 0.55f, 0.95f)))
            round.animateTo(3f, tween(180, easing = CubicBezierEasing(0.445f, 0.05f, 0.55f, 0.95f)))
        }
        launch {
            scale.snapTo(1f)
            scale.animateTo(0.85f, tween(126, easing = LinearEasing))
            scale.animateTo(1f, tween(234, easing = LinearEasing))
        }
    }

    Box(
        modifier = modifier
            .height(70.dp)
            .padding(4.dp)
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .background(background.value, RoundedCornerShape(7.dp))
            .border(1.dp, Color(0xFF585858), RoundedCornerShape(7.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(30.dp)) {
            rotate(rotation.value) {
                drawRoundRect(
                    color = Color(0xFFC4C4D6),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(round.value.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }
        Text(label, color = Color(0xFFF2F2F2), fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
    }
}

@Composable
fun BottomBar(
    item: LibraryItem?,
    playing: Boolean,
    paused: Boolean,
    positionMs: Long,
    playMode: Int,
    speedLabel: String,
    instrumentLabel: String,
    pitchLabel: Int,
    cave: Boolean,
    favorite: Boolean,
    onPlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPlayMode: () -> Unit,
    onSpeed: () -> Unit,
    onInstrument: () -> Unit,
    onPitch: () -> Unit,
    onCave: () -> Unit,
    onPlaylist: () -> Unit,
    onFavorite: () -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth().height(82.dp),
        color = PanelColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor)
    ) {
        Column {
            val durationMs = item?.song?.durationMs ?: 0L
            val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
            Row(
                Modifier.fillMaxWidth().height(18.dp).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (item == null) "0:00" else formatPlaybackTime(positionMs), color = SecondaryText, fontSize = 9.sp, modifier = Modifier.width(32.dp))
                Canvas(
                    Modifier.weight(1f).fillMaxHeight().pointerInput(item?.fileName, durationMs) {
                        detectTapGestures { offset -> if (durationMs > 0) onSeek((offset.x / size.width).coerceIn(0f, 1f)) }
                    }
                ) {
                    val centerY = size.height / 2f
                    val radius = 4.dp.toPx()
                    val thumbX = (size.width * progress).coerceIn(radius, size.width - radius)
                    drawLine(Color(0xFF545458), androidx.compose.ui.geometry.Offset(0f, centerY), androidx.compose.ui.geometry.Offset(size.width, centerY), 3.dp.toPx())
                    drawLine(AccentColor, androidx.compose.ui.geometry.Offset(0f, centerY), androidx.compose.ui.geometry.Offset(thumbX, centerY), 3.dp.toPx())
                    if (item != null) {
                        drawCircle(Color.White, radius, androidx.compose.ui.geometry.Offset(thumbX, centerY))
                        drawCircle(Color(0x55000000), radius, androidx.compose.ui.geometry.Offset(thumbX, centerY), style = Stroke(1.dp.toPx()))
                    }
                }
                Text(if (item == null) "0:00" else formatPlaybackTime(durationMs), color = SecondaryText, fontSize = 9.sp, modifier = Modifier.width(32.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 14.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    val cover = remember(item?.coverBytes) {
                        item?.coverBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                    }
                    Box(Modifier.size(46.dp).background(CardColor, RoundedCornerShape(7.dp)).border(1.dp, BorderColor, RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                        if (cover != null) Image(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Text("♪", color = AccentColor, fontSize = 19.sp)
                    }
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.width(155.dp)) {
                        Text(item?.song?.name ?: "未有正在播放的歌曲", color = Color.White, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (item != null) {
                            Text("${item.song.author ?: "未知"} · ${item.song.transcribedBy ?: "未知"}", color = SecondaryText, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (item != null) FavoriteStarIcon(favorite, Modifier.size(30.dp).clickable(onClick = onFavorite).padding(4.dp))
                    Spacer(Modifier.weight(1f))
                    TransportVector(if (playMode == 1) "repeat_one" else if (playMode == 2) "shuffle" else "repeat", Modifier.size(34.dp).clickable(onClick = onPlayMode).padding(4.dp))
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TransportVector("previous", Modifier.size(34.dp).clickable(onClick = onPrevious).padding(5.dp))
                    Spacer(Modifier.width(8.dp))
                    SMAPPlayButton(playing = playing && !paused, size = 52.dp, onClick = onPlay)
                    Spacer(Modifier.width(8.dp))
                    TransportVector("next", Modifier.size(34.dp).clickable(onClick = onNext).padding(5.dp))
                }

                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    TransportVector("list", Modifier.size(36.dp).clickable(onClick = onPlaylist).padding(7.dp))
                    Spacer(Modifier.weight(1f))
                    TransportVector("cave", Modifier.size(32.dp).clickable(onClick = onCave).padding(5.dp), cave)
                    Spacer(Modifier.width(4.dp))
                    PlayerPill("音色:${localizedInstrument(instrumentLabel)}", onInstrument)
                    Spacer(Modifier.width(6.dp))
                    PlayerPill("音高:${if (pitchLabel > 0) "+" else ""}$pitchLabel ${noteName(pitchLabel)}", onPitch)
                    Spacer(Modifier.width(6.dp))
                    PlayerPill(speedLabel, onSpeed)
                }
            }
        }
    }
}

@Composable
private fun PlayerPill(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = SecondaryText,
        fontSize = 9.sp,
        modifier = Modifier.border(1.dp, BorderColor, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun TransportVector(type: String, modifier: Modifier = Modifier, active: Boolean = false) {
    val materialPath = remember(type) {
        val data = when (type) {
            "repeat" -> "M7 7h10v1.79c0 .45.54.67.85.35l2.79-2.79c.2-.2.2-.51 0-.71l-2.79-2.79c-.31-.31-.85-.09-.85.36V5H6c-.55 0-1 .45-1 1v4c0 .55.45 1 1 1s1-.45 1-1V7zm10 10H7v-1.79c0-.45-.54-.67-.85-.35l-2.79 2.79c-.2.2-.2.51 0 .71l2.79 2.79c.31.31.85.09.85-.36V19h11c.55 0 1-.45 1-1v-4c0-.55-.45-1-1-1s-1 .45-1 1v3z"
            "repeat_one" -> "M7 7h10v1.79c0 .45.54.67.85.35l2.79-2.79c.2-.2.2-.51 0-.71l-2.79-2.79c-.31-.31-.85-.09-.85.36V5H6c-.55 0-1 .45-1 1v4c0 .55.45 1 1 1s1-.45 1-1V7zm10 10H7v-1.79c0-.45-.54-.67-.85-.35l-2.79 2.79c-.2.2-.2.51 0 .71l2.79 2.79c.31.31.85.09.85-.36V19h11c.55 0 1-.45 1-1v-4c0-.55-.45-1-1-1s-1 .45-1 1v3zm-4-2.75V9.81c0-.45-.36-.81-.81-.81-.13 0-.25.03-.36.09l-1.49.74c-.21.1-.34.32-.34.55 0 .34.28.62.62.62h.88v3.25c0 .41.34.75.75.75s.75-.34.75-.75z"
            "shuffle" -> "M10.59 9.17L6.12 4.7c-.39-.39-1.02-.39-1.41 0-.39.39-.39 1.02 0 1.41l4.46 4.46 1.42-1.4zm4.76-4.32l1.19 1.19L4.7 17.88c-.39.39-.39 1.02 0 1.41.39.39 1.02.39 1.41 0L17.96 7.46l1.19 1.19c.31.31.85.09.85-.36V4.5c0-.28-.22-.5-.5-.5h-3.79c-.45 0-.67.54-.36.85zm-.52 8.56l-1.41 1.41 3.13 3.13-1.2 1.2c-.31.31-.09.85.36.85h3.79c.28 0 .5-.22.5-.5v-3.79c0-.45-.54-.67-.85-.35l-1.19 1.19-3.13-3.14z"
            "cave" -> "M2 20L8 9L12 15L15 10L22 20Z"
            else -> null
        }
        data?.let { PathParser().parsePathString(it).toPath() }
    }
    Canvas(modifier) {
        val stroke = Stroke(width = 2.2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        val color = if (active || type == "repeat_one" || type == "shuffle") AccentColor else Color(0xFFE1E1E6)
        when (type) {
            "previous", "next" -> {
                val next = type == "next"
                val barX = size.width * if (next) .78f else .22f
                val pointX = size.width * if (next) .70f else .30f
                val backX = size.width * if (next) .28f else .72f
                drawLine(color, androidx.compose.ui.geometry.Offset(barX, size.height * .2f), androidx.compose.ui.geometry.Offset(barX, size.height * .8f), stroke.width)
                val p = Path().apply { moveTo(pointX, size.height * .5f); lineTo(backX, size.height * .2f); lineTo(backX, size.height * .8f); close() }
                drawPath(p, color)
            }
            "list" -> listOf(.25f, .5f, .75f).forEach { y -> drawLine(color, androidx.compose.ui.geometry.Offset(size.width * .18f, size.height * y), androidx.compose.ui.geometry.Offset(size.width * .82f, size.height * y), stroke.width) }
            else -> materialPath?.let { path ->
                scale(size.width / 24f, size.height / 24f, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                    drawPath(path, color)
                }
            }
        }
    }
}

@Composable
fun SongOptionsDialog(
    item: LibraryItem,
    favorite: Boolean,
    inPlaylist: Boolean,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onAddPlaylist: () -> Unit,
    onDelete: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelColor,
        title = { Text(item.song.name, color = Color.White) },
        text = {
            Column {
                TextButton(onClick = onPlay) { Text("播放") }
                TextButton(onClick = onAddPlaylist, enabled = !inPlaylist) {
                    Text(if (inPlaylist) "已在播放列表" else "添加到播放列表")
                }
                TextButton(onClick = onFavorite) { Text(if (favorite) "取消收藏" else "收藏") }
                TextButton(onClick = onDelete) { Text("从曲库中移除", color = Color(0xFFE74C3C)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
fun PlaylistDialog(
    items: List<LibraryItem>,
    nowPlaying: LibraryItem?,
    playing: Boolean,
    paused: Boolean,
    favorites: Set<String>,
    onDismiss: () -> Unit,
    onPlay: (LibraryItem) -> Unit,
    onFavorite: (LibraryItem) -> Unit,
    onRemove: (LibraryItem) -> Unit,
    onClear: () -> Unit
) {
    var confirmClear by remember { mutableStateOf(false) }
    var moreItem by remember { mutableStateOf<LibraryItem?>(null) }
    var infoItem by remember { mutableStateOf<LibraryItem?>(null) }
    var drawerVisible by remember { mutableStateOf(false) }
    val drawerScope = rememberCoroutineScope()
    val openEase = remember { Easing { t -> 1f - (1f - t) * (1f - t) * (1f - t) } }
    val closeEase = remember { Easing { t -> t * t * t } }
    fun closeDrawer() {
        if (!drawerVisible) return
        drawerVisible = false
        drawerScope.launch {
            delay(220)
            onDismiss()
        }
    }
    LaunchedEffect(Unit) { drawerVisible = true }
    Dialog(
        onDismissRequest = { closeDrawer() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val outsideInteraction = remember { MutableInteractionSource() }
        Box(
            Modifier.fillMaxSize().clickable(
                interactionSource = outsideInteraction,
                indication = null,
                onClick = { closeDrawer() }
            )
        ) {
            AnimatedVisibility(
                visible = drawerVisible,
                modifier = Modifier.align(Alignment.CenterEnd),
                enter = slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(260, easing = openEase)
                ),
                exit = slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(220, easing = closeEase)
                )
            ) {
            Column(
                Modifier
                    .fillMaxWidth(0.36f)
                    .fillMaxHeight()
                    .background(PanelColor, RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                    .border(1.dp, BorderColor, RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
            ) {
                Row(
                    Modifier.fillMaxWidth().height(48.dp).padding(start = 12.dp, end = 40.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("播放列表", color = Color.White, fontSize = 19.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Text("${items.size} 首", color = SecondaryText, fontSize = 11.sp)
                    Spacer(Modifier.weight(1f))
                    if (items.isNotEmpty()) {
                        Text("清空", color = SecondaryText, fontSize = 11.sp, modifier = Modifier.clickable { confirmClear = true }.padding(6.dp))
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(BorderColor))
            if (items.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("播放列表为空\n长按曲库歌曲即可添加", color = SecondaryText, fontSize = 12.sp)
                    }
            } else {
                    LazyColumn(Modifier.fillMaxSize().padding(start = 8.dp, end = 40.dp, top = 6.dp, bottom = 6.dp)) {
                    items(items, key = { it.fileName }) { item ->
                        val isCurrent = nowPlaying?.fileName == item.fileName
                        val cover = remember(item.coverBytes) {
                            item.coverBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                        }
                        Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(62.dp)
                                    .background(if (isCurrent) Color(0xFF303033) else Color(0xFF292929), RoundedCornerShape(5.dp))
                                    .clickable { onPlay(item) }
                                    .padding(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                                Box(
                                    Modifier.size(46.dp).background(Color(0xFF353538), RoundedCornerShape(6.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (cover != null) {
                                        Image(cover, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    } else {
                                        Text("♪", color = SecondaryText, fontSize = 18.sp)
                                    }
                                    if (isCurrent) {
                                        Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
                                            PlayPauseVector(playing = playing && !paused, modifier = Modifier.size(22.dp))
                                        }
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            item.song.name,
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (isCurrent) {
                                            Spacer(Modifier.width(5.dp))
                                            Canvas(Modifier.width(16.dp).height(13.dp)) {
                                                val barWidth = size.width / 5f
                                                listOf(0.35f, 0.65f, 1f).forEachIndexed { index, ratio ->
                                                    drawRect(
                                                        color = AccentColor,
                                                        topLeft = androidx.compose.ui.geometry.Offset(index * barWidth * 2f, size.height * (1f - ratio)),
                                                        size = androidx.compose.ui.geometry.Size(barWidth, size.height * ratio)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    Text(item.song.author ?: "未知", color = SecondaryText, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(item.song.transcribedBy ?: "未知", color = SecondaryText, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                FavoriteStarIcon(
                                    filled = item.fileName in favorites,
                                    modifier = Modifier.size(36.dp).clickable { onFavorite(item) }.padding(7.dp)
                                )
                                Box(
                                    Modifier.size(28.dp).background(Color(0xFF505054), CircleShape).clickable { moreItem = item },
                                    contentAlignment = Alignment.Center
                                ) {
                                    MoreVector(Modifier.size(16.dp))
                                }
                        }
                            Spacer(Modifier.height(2.dp))
                }
            }
            }
        }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = PanelColor,
            title = { Text("清空播放列表", color = Color.White) },
            text = { Text("确定清空播放列表吗？", color = Color.White) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClear() }) {
                    Text("清空", color = Color(0xFFE74C3C))
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } }
        )
    }

    moreItem?.let { item ->
        AlertDialog(
            onDismissRequest = { moreItem = null },
            containerColor = PanelColor,
            title = { Text(item.song.name, color = Color.White) },
            text = {
                Column {
                    TextButton(onClick = { moreItem = null; onPlay(item) }) { Text("播放") }
                    TextButton(onClick = { onFavorite(item) }) {
                        Text(if (item.fileName in favorites) "取消收藏" else "收藏")
                    }
                    TextButton(onClick = { moreItem = null; onRemove(item) }) {
                        Text("从播放列表移除", color = Color(0xFFE74C3C))
                    }
                    TextButton(onClick = { moreItem = null; infoItem = item }) { Text("歌曲信息") }
                }
            },
            confirmButton = { TextButton(onClick = { moreItem = null }) { Text("关闭") } }
        )
    }

    infoItem?.let { item -> SongDetailDialog(item) { infoItem = null } }
}

@Composable
private fun PlayPauseVector(playing: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (playing) {
            val width = size.width * 0.22f
            drawRoundRect(Color.White, androidx.compose.ui.geometry.Offset(size.width * 0.22f, size.height * 0.16f), androidx.compose.ui.geometry.Size(width, size.height * 0.68f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(width * 0.22f))
            drawRoundRect(Color.White, androidx.compose.ui.geometry.Offset(size.width * 0.56f, size.height * 0.16f), androidx.compose.ui.geometry.Size(width, size.height * 0.68f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(width * 0.22f))
        } else {
            val triangle = Path().apply {
                moveTo(size.width * 0.25f, size.height * 0.14f)
                lineTo(size.width * 0.82f, size.height * 0.5f)
                lineTo(size.width * 0.25f, size.height * 0.86f)
                close()
            }
            drawPath(triangle, Color.White)
        }
    }
}

@Composable
private fun MoreVector(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val radius = size.minDimension * 0.105f
        listOf(0.22f, 0.5f, 0.78f).forEach { x ->
            drawCircle(Color(0xFF202024), radius, androidx.compose.ui.geometry.Offset(size.width * x, size.height * 0.5f))
        }
    }
}

@Composable
private fun FavoriteStarIcon(filled: Boolean, modifier: Modifier = Modifier) {
    val path = remember(filled) {
        PathParser().parsePathString(
            if (filled) {
                "M12,17.27 L16.15,19.78 C16.91,20.24 17.84,19.56 17.64,18.70 L16.54,13.98 L20.21,10.80 C20.88,10.22 20.52,9.12 19.64,9.05 L14.81,8.64 L12.92,4.18 C12.58,3.37 11.42,3.37 11.08,4.18 L9.19,8.63 L4.36,9.04 C3.48,9.11 3.12,10.21 3.79,10.79 L7.46,13.97 L6.36,18.69 C6.16,19.55 7.09,20.23 7.85,19.77 L12,17.27 Z"
            } else {
                "M19.65,9.04 L14.81,8.62 L12.92,4.17 C12.58,3.36 11.42,3.36 11.08,4.17 L9.19,8.63 L4.36,9.04 C3.48,9.11 3.12,10.21 3.79,10.79 L7.46,13.97 L6.36,18.69 C6.16,19.55 7.09,20.23 7.85,19.77 L12,17.27 L16.15,19.78 C16.91,20.24 17.84,19.56 17.64,18.70 L16.54,13.97 L20.21,10.79 C20.88,10.21 20.53,9.11 19.65,9.04 Z M12,15.4 L8.24,17.67 L9.24,13.39 L5.92,10.51 L10.3,10.13 L12,6.1 L13.71,10.14 L18.09,10.52 L14.77,13.4 L15.77,17.68 Z"
            }
        ).toPath()
    }
    Canvas(modifier) {
        scale(size.width / 24f, size.height / 24f, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            drawPath(path, Color(0xFFFFD600))
        }
    }
}

@Composable
private fun MidiImportDialog(
    pending: PendingMidi,
    onCancel: () -> Unit,
    onImport: (String, Set<Int>, Boolean, Int) -> Unit
) {
    var selectedTracks by remember(pending.fileName) {
        mutableStateOf(pending.analysis.tracks.map { it.index }.toSet())
    }
    var autoAlign by remember(pending.fileName) { mutableStateOf(true) }
    var octave by remember(pending.fileName) { mutableStateOf("0") }
    var songName by remember(pending.fileName) { mutableStateOf(pending.fileName.substringBeforeLast('.')) }
    val shift = if (autoAlign && selectedTracks.isNotEmpty()) MidiImporter.suggestShift(pending.bytes, selectedTracks) else 0
    val whiteRatio = if (autoAlign && selectedTracks.isNotEmpty()) MidiImporter.whiteRatio(pending.bytes, selectedTracks, shift) else 0.0

    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = PanelColor,
        title = { Text("导入 MIDI", color = Color.White) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 430.dp)) {
                item {
                    Text(pending.fileName, color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text("选择音轨", color = SecondaryText, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                }
                items(pending.analysis.tracks, key = { it.index }) { track ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            selectedTracks = selectedTracks.toMutableSet().apply {
                                if (!add(track.index)) remove(track.index)
                            }
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = track.index in selectedTracks, onCheckedChange = null)
                        Text("${track.name}  (${track.noteCount} 音符)", color = Color.White, fontSize = 12.sp)
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { autoAlign = !autoAlign },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = autoAlign, onCheckedChange = null)
                        Text("自动移调对齐 C 大调", color = Color.White, fontSize = 12.sp)
                    }
                    if (autoAlign) {
                        Text(
                            if (selectedTracks.isEmpty()) "请至少选择一条音轨" else "已移调 ${"%+d".format(shift)} 半音 · 白键率 ${(whiteRatio * 100).toInt()}%",
                            color = Color(0xFF2AAA77), fontSize = 11.sp, modifier = Modifier.padding(start = 12.dp, bottom = 6.dp)
                        )
                    } else {
                        Text("手动八度", color = SecondaryText, fontSize = 11.sp)
                        ImportTextField(octave, { octave = it.filter { c -> c == '-' || c.isDigit() } }, "0")
                    }
                    Text("曲名", color = SecondaryText, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                    ImportTextField(songName, { songName = it }, "MIDI 导入")
                    Text("检测 BPM：${"%.1f".format(pending.analysis.initialBpm)}", color = SecondaryText, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedTracks.isNotEmpty(),
                onClick = {
                    onImport(songName.trim().ifBlank { "MIDI 导入" }, selectedTracks, autoAlign, octave.toIntOrNull() ?: 0)
                }
            ) { Text("导入") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } }
    )
}

@Composable
private fun ImportTextField(value: String, onValueChange: (String) -> Unit, hint: String) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
        cursorBrush = SolidColor(AccentColor),
        modifier = Modifier.fillMaxWidth().height(34.dp).background(CardColor).border(1.dp, BorderColor),
        decorationBox = { input ->
            Box(Modifier.fillMaxSize().padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(hint, color = SecondaryText, fontSize = 12.sp)
                input()
            }
        }
    )
}

@Composable
fun SpeedDialog(
    speed: Float,
    random: Boolean,
    onDismiss: () -> Unit,
    onSelect: (Float, Boolean) -> Unit
) {
    val speeds = listOf(2f, 1.75f, 1.5f, 1.25f, 1f, 0.75f, 0.5f)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.52f),
            shape = RoundedCornerShape(12.dp),
            color = PanelColor,
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor)
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("播放速度", color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                val options = listOf<Pair<String, () -> Unit>>(
                    (if (random) "✓ 随机速度" else "随机速度") to { onSelect(speed, true) }
                ) + speeds.map { value ->
                    (if (!random && speed == value) "✓ ${value}×" else "${value}×") to { onSelect(value, false) }
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxWidth().height(84.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(options.size) { index ->
                        val active = options[index].first.startsWith("✓")
                        TextButton(
                            onClick = options[index].second,
                            modifier = Modifier.fillMaxWidth().height(40.dp)
                                .background(if (active) AccentColor.copy(alpha = 0.22f) else CardColor, RoundedCornerShape(7.dp))
                        ) { Text(options[index].first, maxLines = 1) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceDialog(
    title: String,
    options: List<String>,
    selected: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.62f).heightIn(max = 350.dp),
            shape = RoundedCornerShape(12.dp),
            color = PanelColor,
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor)
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 118.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(options.size) { index ->
                        val option = options[index]
                        val display = option.substringAfter('|', option)
                        val active = option == selected
                        TextButton(
                            onClick = { onSelect(option) },
                            modifier = Modifier.fillMaxWidth().height(40.dp)
                                .background(if (active) AccentColor.copy(alpha = 0.22f) else CardColor, RoundedCornerShape(7.dp))
                        ) {
                            Text(if (active) "✓ $display" else display, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

private fun noteName(semitones: Int): String =
    listOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")[(semitones % 12 + 12) % 12]

private fun localizedInstrument(key: String): String {
    val names = mapOf(
        "Piano" to arrayOf("钢琴", "鋼琴", "Piano", "ピアノ"), "Harp" to arrayOf("竖琴", "豎琴", "Harp", "ハープ"),
        "Guitar" to arrayOf("吉他", "吉他", "Guitar", "ギター"), "Flute" to arrayOf("长笛", "長笛", "Flute", "フルート"),
        "Ukulele" to arrayOf("尤克里里", "烏克麗麗", "Ukulele", "ウクレレ"), "Winter Piano" to arrayOf("冬季钢琴", "冬季鋼琴", "Winter Piano", "ウィンターピアノ"),
        "Xylophone" to arrayOf("木琴", "木琴", "Xylophone", "シロフォン"), "Electric Guitar" to arrayOf("电吉他", "電吉他", "Electric Guitar", "エレキギター"),
        "Bassoon" to arrayOf("巴松管", "巴松管", "Bassoon", "ファゴット"), "Orff" to arrayOf("奥尔夫", "奧爾夫", "Orff", "オルフ"),
        "Kalimba" to arrayOf("卡林巴", "卡林巴", "Kalimba", "カリンバ"), "Ocarina" to arrayOf("陶笛", "陶笛", "Ocarina", "オカリナ"),
        "Cello" to arrayOf("大提琴", "大提琴", "Cello", "チェロ"), "Violin" to arrayOf("小提琴", "小提琴", "Violin", "ヴァイオリン"),
        "Saxophone" to arrayOf("萨克斯", "薩克斯", "Saxophone", "サックス"), "Pipa" to arrayOf("琵琶", "琵琶", "Pipa", "ピパ"),
        "Quena" to arrayOf("盖那笛", "蓋那笛", "Quena", "ケーナ"), "Bugle" to arrayOf("军号", "軍號", "Bugle", "ビューグル"),
        "Glock" to arrayOf("钟琴", "鐘琴", "Glockenspiel", "グロッケン"), "LightGuitar" to arrayOf("轻吉他", "輕吉他", "Light Guitar", "ライトギター"),
        "GoldPiano" to arrayOf("金钢琴", "金鋼琴", "Gold Piano", "ゴールドピアノ"), "Horn" to arrayOf("圆号", "圓號", "Horn", "ホルン"),
        "Handpan" to arrayOf("手碟", "手碟", "Handpan", "ハンドパン"), "GoldHandpan" to arrayOf("金手碟", "金手碟", "Gold Handpan", "ゴールドハンドパン"),
        "Dundun" to arrayOf("邓杜鼓", "鄧杜鼓", "Dundun", "ドゥンドゥン"), "APBell1" to arrayOf("铃1", "鈴1", "AP Bell 1", "ベル1"),
        "APBell2" to arrayOf("铃2", "鈴2", "AP Bell 2", "ベル2"), "Harmonica" to arrayOf("口琴", "口琴", "Harmonica", "ハーモニカ"),
        "AP18Ocarina" to arrayOf("陶笛Ⅱ", "陶笛Ⅱ", "Ocarina II", "オカリナⅡ"), "AP29Piccolo" to arrayOf("短笛", "短笛", "Piccolo", "ピッコロ"),
        "GoldBugle" to arrayOf("金军号", "金軍號", "Gold Bugle", "ゴールドビューグル"), "APPiano" to arrayOf("AP钢琴", "AP鋼琴", "AP Piano", "APピアノ"),
        "4thAnnivArp" to arrayOf("四周年·琶音", "四週年·琶音", "4th Anniv Arp", "4周年アルペジオ"), "4thAnnivLead" to arrayOf("四周年·主音", "四週年·主音", "4th Anniv Lead", "4周年リード"),
        "Contrabass" to arrayOf("低音提琴", "低音提琴", "Contrabass", "コントラバス"), "4thAnnivBass" to arrayOf("四周年·贝斯", "四週年·貝斯", "4th Anniv Bass", "4周年ベース"),
        "GoldDundun" to arrayOf("金邓杜鼓", "金鄧杜鼓", "Gold Dundun", "ゴールドドゥンドゥン")
    )
    val locale = java.util.Locale.getDefault()
    val index = when {
        locale.language == "ja" -> 3
        locale.language == "en" -> 2
        locale.language == "zh" && (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")) -> 1
        else -> 0
    }
    return names[key]?.get(index) ?: key
}

/** 曲谱详情 */
@Composable
fun SongDetailDialog(item: LibraryItem, onDismiss: () -> Unit) {
    val song = item.song
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF19163A),
        titleContentColor = Color.White,
        textContentColor = Color(0xFFE5E7EB),
        title = { Text(song.name) },
        text = {
            Column {
                InfoRow("文件名", item.fileName)
                InfoRow("BPM", song.bpm.toString())
                InfoRow("音符数", song.songNotes.size.toString())
                InfoRow("时长", formatDuration(song.durationMs))
                InfoRow("作者", song.author ?: "—")
                InfoRow("做谱者", song.transcribedBy ?: "—")
                InfoRow("音高", song.pitchLevel.toString())
                InfoRow("键数", song.keyCount.toString())
                InfoRow("原创", if (song.isComposed) "是" else "否")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭", color = AccentColor) }
        }
    )
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, fontSize = 13.sp, color = Color(0xFF9CA3AF), modifier = Modifier.width(72.dp))
        Text(value, fontSize = 13.sp, color = Color(0xFFE5E7EB), modifier = Modifier.weight(1f))
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "—"
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

private fun formatPlaybackTime(ms: Long): String {
    val totalSec = ms.coerceAtLeast(0) / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
