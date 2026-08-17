package com.smap.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smap.android.data.LibraryItem
import com.smap.android.data.SongRepository
import com.smap.android.model.SkySong
import com.smap.android.service.FloatService

// 桌面版 SMAP 风格配色：深蓝紫渐变 + 青色强调
private val BgColors = listOf(Color(0xFF0E0A1F), Color(0xFF1E1B4B), Color(0xFF302B63))
private val CardColor = Color(0xCC19163A)
private val AccentColor = Color(0xFF7DD3FC)
private val DividerColor = Color(0x3343437A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SMAPTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun SMAPTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = AccentColor,
            background = Color(0xFF0E0A1F),
            surface = Color(0xFF19163A)
        ),
        content = content
    )
}

@Composable
fun MainScreen() {
    var items by remember { mutableStateOf<List<LibraryItem>>(emptyList()) }
    var performSong by remember { mutableStateOf<LibraryItem?>(null) }
    var navTab by remember { mutableIntStateOf(0) } // 0 本地 1 云端 2 设置
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { SongRepository(context) }

    LaunchedEffect(Unit) {
        items = repository.loadSongs()
    }

    val current = performSong
    if (current != null) {
        com.smap.android.ui.PerformScreen(song = current.song, onBack = { performSong = null })
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(BgColors))
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // 左侧边栏（音乐软件式）
            Sidebar(selected = navTab, onSelect = { navTab = it })

            // 中栏：标题 + 曲库列表
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                Header(songCount = items.size, onGamePerform = {
                    if (Settings.canDrawOverlays(context)) {
                        FloatService.start(context)
                    } else {
                        // 引导开启悬浮窗权限
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                })
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                ) {
                    items(items, key = { it.fileName }) { item ->
                        SongCard(item = item, onClick = { performSong = item })
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }

        // 底部播放条（占位，M4 接入播放器）
        BottomBar(modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/** 左侧边栏 */
@Composable
fun Sidebar(selected: Int, onSelect: (Int) -> Unit) {
    Column(
        modifier = Modifier
            .width(64.dp)
            .fillMaxHeight()
            .background(Color(0x33110E2A))
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("🎹", fontSize = 26.sp)
        Spacer(Modifier.height(8.dp))
        NavButton("🎵", "本地", selected == 0, onClick = { onSelect(0) })
        NavButton("☁️", "云端", selected == 1, onClick = { onSelect(1) })
        NavButton("⚙️", "设置", selected == 2, onClick = { onSelect(2) })
    }
}

@Composable
fun NavButton(icon: String, label: String, active: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .size(52.dp)
            .background(
                color = if (active) Color(0x407DD3FC) else Color.Transparent,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(icon, fontSize = 20.sp)
        Text(label, fontSize = 9.sp, color = if (active) AccentColor else Color(0xFF9CA3AF))
    }
}

/** 顶部标题栏 */
@Composable
fun Header(songCount: Int, onGamePerform: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("SMAP", color = AccentColor, fontSize = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Text(" 曲库", color = Color.White, fontSize = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(
            "本地曲谱 $songCount 首",
            color = Color(0xFF9CA3AF),
            fontSize = 13.sp,
            modifier = Modifier.padding(end = 12.dp)
        )
        // 游戏演奏按钮（悬浮球模式入口）
        Surface(
            modifier = Modifier.clickable(onClick = onGamePerform),
            shape = RoundedCornerShape(14.dp),
            color = AccentColor
        ) {
            Text(
                "🎮 游戏演奏",
                color = Color(0xFF0E0A1F),
                fontSize = 14.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
    }
}

/** 曲库卡片 */
@Composable
fun SongCard(item: LibraryItem, onClick: () -> Unit) {
    val song = item.song
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = CardColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 封面占位
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Color(0x337DD3FC), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("🎵", fontSize = 20.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(song.name, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "BPM ${song.bpm} · ${song.songNotes.size} 音符" +
                        (song.author?.let { " · $it" } ?: ""),
                    color = Color(0xFF9CA3AF),
                    fontSize = 12.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                formatDuration(song.durationMs),
                color = AccentColor,
                fontSize = 13.sp
            )
        }
    }
}

/** 底部播放条占位 */
@Composable
fun BottomBar(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xCC141033)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("未在播放", color = Color(0xFF9CA3AF), fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text("▶", color = AccentColor, fontSize = 20.sp)
            Spacer(Modifier.width(16.dp))
            Text("⏸", color = Color(0xFF9CA3AF), fontSize = 20.sp)
            Spacer(Modifier.width(16.dp))
            Text("⏹", color = Color(0xFF9CA3AF), fontSize = 20.sp)
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
