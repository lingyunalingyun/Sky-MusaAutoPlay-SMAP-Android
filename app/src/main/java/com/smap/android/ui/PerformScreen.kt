package com.smap.android.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smap.android.engine.KeyLayout
import com.smap.android.engine.KeyLayoutStore
import com.smap.android.engine.PlayerEngine
import com.smap.android.model.SkySong
import com.smap.android.service.SMAPAccessibilityService
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val AccentColor = Color(0xFF7DD3FC)

/** 演奏界面：15 键大键盘 + 自动弹琴控制 + 琴键校准 */
@Composable
fun PerformScreen(song: SkySong, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val config = LocalConfiguration.current

    val screenW = with(density) { config.screenWidthDp.dp.roundToPx() }
    val screenH = with(density) { config.screenHeightDp.dp.roundToPx() }

    var layout by remember { mutableStateOf(KeyLayoutStore.load(context)) }
    val keys = remember(layout) { layout.computeKeys() }
    var playing by remember { mutableStateOf(false) }
    var activeKey by remember { mutableIntStateOf(-1) }
    var speed by remember { mutableFloatStateOf(1f) }
    var showCalib by remember { mutableStateOf(false) }
    val serviceEnabled = SMAPAccessibilityService.isEnabled()
    val engine = remember { PlayerEngine(scope) }

    fun togglePlay() {
        if (playing) {
            engine.stop()
            playing = false
            activeKey = -1
        } else {
            if (!serviceEnabled) return
            engine.play(
                song = song, keys = keys, screenW = screenW, screenH = screenH,
                onNoteFired = { k -> activeKey = k },
                onFinished = { playing = false; activeKey = -1 }
            )
            playing = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(Color(0xFF0E0A1F), Color(0xFF1E1B4B), Color(0xFF302B63))))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.clickable(onClick = onBack),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0x33110E2A)
                ) {
                    Text("‹ 返回", color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(song.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(
                        "BPM ${song.bpm} · ${song.songNotes.size} 音符",
                        color = Color(0xFF9CA3AF), fontSize = 12.sp
                    )
                }
                Spacer(Modifier.weight(1f))
                // 无障碍状态
                if (!serviceEnabled) {
                    Surface(
                        modifier = Modifier.clickable {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0x66F59E0B)
                    ) {
                        Text("⚠ 需开启无障碍", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                } else {
                    Surface(shape = RoundedCornerShape(12.dp), color = Color(0x3340B000)) {
                        Text("✓ 无障碍已开", color = Color(0xFFA3E635), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
            }

            // 15 键大键盘（绝对定位）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                keys.forEachIndexed { idx, kp ->
                    val x = (kp.xRatio * screenW).roundToInt()
                    val y = (kp.yRatio * screenH).roundToInt()
                    val w = (layout.keyW * screenW).roundToInt()
                    val h = (layout.keyH * screenH).roundToInt()
                    val isActive = idx == activeKey
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(x - w / 2, y - h / 2) }
                            .size(width = w.dp, height = h.dp)
                            .background(
                                color = when {
                                    isActive -> AccentColor
                                    else -> Color(0x4D1E1B4B)
                                },
                                shape = RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            (idx + 1).toString(),
                            color = if (isActive) Color(0xFF0E0A1F) else Color(0xFF7C7FA8),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // 控制栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (!serviceEnabled) {
                    Text("请先开启无障碍服务，然后切到光遇再点播放", color = Color(0xFFF59E0B), fontSize = 13.sp)
                } else {
                    // 播放/停止
                    Surface(
                        modifier = Modifier
                            .size(64.dp)
                            .clickable(onClick = { togglePlay() }),
                        shape = RoundedCornerShape(50),
                        color = if (playing) Color(0x66EF4444) else AccentColor
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(if (playing) "⏹" else "▶", fontSize = 26.sp, color = if (playing) Color.White else Color(0xFF0E0A1F))
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    // 倍速
                    Surface(
                        modifier = Modifier.clickable {
                            speed = when (speed) {
                                0.5f -> 1f
                                1f -> 2f
                                else -> 0.5f
                            }
                            engine.setSpeed(speed)
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0x33110E2A)
                    ) {
                        Text("${speed}x", color = AccentColor, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    // 校准开关
                    Surface(
                        modifier = Modifier.clickable { showCalib = !showCalib },
                        shape = RoundedCornerShape(12.dp),
                        color = if (showCalib) Color(0x407DD3FC) else Color(0x33110E2A)
                    ) {
                        Text("校准", color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                    }
                }
            }

            // 校准面板
            if (showCalib) {
                CalibrationPanel(
                    layout = layout,
                    onChange = { layout = it },
                    onSave = { KeyLayoutStore.save(context, layout) },
                    onReset = { KeyLayoutStore.reset(context); layout = KeyLayout() }
                )
            }

            Spacer(Modifier.height(4.dp))
        }
    }
}

/** 校准面板：整体偏移/键大小微调 */
@Composable
fun CalibrationPanel(
    layout: KeyLayout,
    onChange: (KeyLayout) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xE61A1738)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("面板位置", color = Color.White, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                CalibBtn("←") { onChange(layout.copy(panelX = layout.panelX - 0.02f)) }
                CalibBtn("→") { onChange(layout.copy(panelX = layout.panelX + 0.02f)) }
                CalibBtn("↑") { onChange(layout.copy(panelY = layout.panelY - 0.02f)) }
                CalibBtn("↓") { onChange(layout.copy(panelY = layout.panelY + 0.02f)) }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("键大小", color = Color.White, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                CalibBtn("键-") { onChange(layout.copy(keyW = layout.keyW - 0.01f, keyH = layout.keyH - 0.008f)) }
                CalibBtn("键+") { onChange(layout.copy(keyW = layout.keyW + 0.01f, keyH = layout.keyH + 0.008f)) }
                CalibBtn("行距-") { onChange(layout.copy(rowGap = layout.rowGap - 0.005f)) }
                CalibBtn("行距+") { onChange(layout.copy(rowGap = layout.rowGap + 0.005f)) }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Surface(
                    modifier = Modifier.clickable(onClick = onSave),
                    shape = RoundedCornerShape(10.dp),
                    color = AccentColor
                ) {
                    Text("保存", color = Color(0xFF0E0A1F), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                }
                Spacer(Modifier.width(10.dp))
                Surface(
                    modifier = Modifier.clickable(onClick = onReset),
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0x335F6368)
                ) {
                    Text("重置", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
fun CalibBtn(label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = Color(0x3343437A)
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}
