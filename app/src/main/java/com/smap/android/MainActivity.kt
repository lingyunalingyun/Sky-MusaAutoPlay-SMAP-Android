package com.smap.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smap.android.data.LibraryItem
import com.smap.android.data.LibraryPreferences
import com.smap.android.data.SongRepository
import com.smap.android.service.FloatService
import com.smap.android.ui.SMAPPlayButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var performSong by remember { mutableStateOf<LibraryItem?>(null) }
    var selectedItem by remember { mutableStateOf<LibraryItem?>(null) }
    var navTab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var ascending by remember { mutableStateOf(true) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { SongRepository(context) }
    val preferences = remember { LibraryPreferences(context) }
    var favorites by remember { mutableStateOf(preferences.favorites()) }
    val scope = rememberCoroutineScope()

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val results = withContext(Dispatchers.IO) { uris.map(repository::importSong) }
            items = withContext(Dispatchers.IO) { repository.loadSongs() }
            navTab = 0
            val success = results.count { it.isSuccess }
            val failure = results.size - success
            val message = when {
                failure == 0 -> "已导入 $success 首曲谱"
                success == 0 -> results.firstNotNullOfOrNull { it.exceptionOrNull()?.message } ?: "导入失败"
                else -> "已导入 $success 首，失败 $failure 首"
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        items = withContext(Dispatchers.IO) { repository.loadSongs() }
    }

    val visibleItems = items
        .asSequence()
        .filter { navTab != 2 || it.fileName in favorites }
        .filter {
            query.isBlank() || it.song.name.contains(query, true) ||
                it.song.author.orEmpty().contains(query, true) || it.fileName.contains(query, true)
        }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.song.name })
        .let { if (ascending) it else it.toList().asReversed().asSequence() }
        .toList()

    val current = performSong
    if (current != null) {
        com.smap.android.ui.PerformScreen(
            song = current.song,
            onBack = { performSong = null },
            onGameModeChange = { enabled ->
                if (enabled) onGamePerform() else FloatService.stop(context)
            }
        )
        return
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
                        ascending = ascending,
                        onReset = { query = "" },
                        onToggleSort = { ascending = !ascending }
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 6.dp, vertical = 5.dp)
                    ) {
                        items(visibleItems, key = { it.fileName }) { item ->
                            SongCard(
                                item = item,
                                selected = selectedItem?.fileName == item.fileName,
                                favorite = item.fileName in favorites,
                                onFavorite = { favorites = preferences.toggleFavorite(item.fileName) },
                                onClick = { selectedItem = item }
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        if (visibleItems.isEmpty()) {
                            item { EmptyMessage(if (navTab == 2) "还没有收藏曲谱" else "没有匹配的曲谱") }
                        }
                    }
                } else {
                    val message = when (navTab) {
                        1 -> "云端曲库将在下一阶段接入"
                        4 -> "尚未登录缪斯树屋账号"
                        5 -> "SMAP Android 0.1.0\n本地曲库与练习功能已启用"
                        else -> ""
                    }
                    EmptyMessage(message)
                }
            }

            RightPanel(modifier = Modifier.weight(0.58f))
        }
        BottomBar(item = selectedItem, onPlay = { selectedItem?.let { performSong = it } })
    }
}

/** 左侧边栏 */
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
    ascending: Boolean,
    onReset: () -> Unit,
    onToggleSort: () -> Unit
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
            FilterChip("全部", Modifier.weight(1f), onReset)
            FilterChip(if (ascending) "A - Z" else "Z - A", Modifier.weight(1f), onToggleSort)
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
@Composable
fun SongCard(
    item: LibraryItem,
    selected: Boolean,
    favorite: Boolean,
    onFavorite: () -> Unit,
    onClick: () -> Unit
) {
    val song = item.song
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = CardColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) AccentColor else BorderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(PanelColor, RoundedCornerShape(6.dp))
                    .border(1.dp, BorderColor, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("♪", color = AccentColor, fontSize = 22.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(song.name, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "BPM ${song.bpm} · ${song.songNotes.size} 音符" +
                        (song.author?.let { " · $it" } ?: ""),
                    color = SecondaryText,
                    fontSize = 10.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (favorite) "★" else "☆",
                color = Color(0xFFFFD229),
                fontSize = 20.sp,
                modifier = Modifier.clickable(onClick = onFavorite).padding(4.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                formatDuration(song.durationMs),
                color = AccentColor,
                fontSize = 11.sp
            )
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
fun RightPanel(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(PanelColor)
            .border(1.dp, BorderColor)
            .padding(12.dp)
    ) {
        Spacer(Modifier.height(4.dp))
        val labels = listOf("YUIOP", "HJKL;", "NM,./")
        val shapes = listOf(
            listOf(2, 1, 0, 1, 0),
            listOf(0, 1, 2, 1, 0),
            listOf(0, 1, 0, 1, 2)
        )
        labels.forEachIndexed { rowIndex, row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEachIndexed { columnIndex, key ->
                    KeyboardKey(key.toString(), shapes[rowIndex][columnIndex], Modifier.weight(1f))
                }
            }
            if (rowIndex < 2) Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
fun KeyboardKey(label: String, shapeMode: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.height(70.dp).background(Color(0xFF858585), RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize().padding(10.dp)) {
            val stroke = Stroke(width = 1.2f)
            if (shapeMode == 0 || shapeMode == 2) drawCircle(Color(0xFFE2E2E2), style = stroke)
            if (shapeMode == 1 || shapeMode == 2) {
                val p = Path().apply {
                    moveTo(size.width / 2f, 0f)
                    lineTo(size.width, size.height / 2f)
                    lineTo(size.width / 2f, size.height)
                    lineTo(0f, size.height / 2f)
                    close()
                }
                drawPath(p, Color(0xFFE2E2E2), style = stroke)
            }
        }
        Text(label, color = Color(0xFFEAEAEA), fontSize = 11.sp)
    }
}

/** 底部播放条占位 */
@Composable
fun BottomBar(item: LibraryItem?, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().height(62.dp),
        color = PanelColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor)
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(3.dp).background(Color(0xFFABABAF))) {
                Box(Modifier.size(10.dp).background(Color.White, CircleShape).align(Alignment.CenterStart))
            }
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 18.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
            Box(Modifier.size(42.dp).background(CardColor, RoundedCornerShape(6.dp)).border(1.dp, BorderColor, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                Text("♪", color = AccentColor, fontSize = 20.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(item?.song?.name ?: "未选择曲谱", color = Color.White, fontSize = 12.sp, maxLines = 1)
                Text(
                    item?.let { "BPM ${it.song.bpm} · ${it.song.songNotes.size} 音符" } ?: "请从曲库选择一首曲谱",
                    color = SecondaryText,
                    fontSize = 9.sp
                )
            }
            Spacer(Modifier.weight(1f))
            Text("↶", color = SecondaryText, fontSize = 18.sp)
            Spacer(Modifier.width(14.dp))
            SMAPPlayButton(playing = false, size = 52.dp, onClick = onPlay)
            Spacer(Modifier.width(14.dp))
            Text("≡", color = Color.White, fontSize = 22.sp)
            Spacer(Modifier.weight(1f))
            Text("音色  ·  1.0×", color = SecondaryText, fontSize = 11.sp)
            }
        }
    }
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
