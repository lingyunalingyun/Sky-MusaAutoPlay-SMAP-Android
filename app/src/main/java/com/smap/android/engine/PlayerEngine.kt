package com.smap.android.engine

import android.os.SystemClock
import com.smap.android.model.SkySong
import com.smap.android.service.SMAPAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 琴键坐标（屏幕比例 0~1，播放时乘屏幕像素） */
data class KeyPoint(val xRatio: Float, val yRatio: Float)

/**
 * 自动弹琴播放引擎：后台协程按曲谱时间线调用无障碍服务点击琴键。
 * 支持 停止 / 实时倍速。
 */
class PlayerEngine(private val scope: CoroutineScope) {

    private var job: Job? = null

    @Volatile
    private var running = false

    @Volatile
    private var speed = 1f

    fun isRunning(): Boolean = running

    fun setSpeed(s: Float) {
        speed = s.coerceIn(0.25f, 4f)
    }

    /**
     * 开始播放。
     * @param screenW 屏幕宽像素
     * @param screenH 屏幕高像素
     * @param onNoteFired 每发一个音符回调（UI 高亮）
     * @param onFinished 播放结束或停止回调
     */
    fun play(
        song: SkySong,
        keys: List<KeyPoint>,
        screenW: Int,
        screenH: Int,
        onNoteFired: (Int) -> Unit = {},
        onFinished: () -> Unit = {}
    ) {
        stop()
        if (!SMAPAccessibilityService.isEnabled()) {
            onFinished()
            return
        }
        running = true
        job = scope.launch(Dispatchers.Default) {
            val start = SystemClock.elapsedRealtime()
            for (note in song.songNotes) {
                if (!running) break
                val targetMs = (note.time / speed).toLong()
                val elapsed = SystemClock.elapsedRealtime() - start
                if (targetMs > elapsed) delay(targetMs - elapsed)
                if (!running) break
                if (note.key in keys.indices) {
                    val k = keys[note.key]
                    SMAPAccessibilityService.tap(k.xRatio * screenW, k.yRatio * screenH)
                    onNoteFired(note.key)
                }
            }
            running = false
            onFinished()
        }
    }

    fun stop() {
        running = false
        job?.cancel()
        job = null
    }
}
