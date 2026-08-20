package com.smap.android.engine

import android.os.SystemClock
import com.smap.android.model.SkySong
import com.smap.android.service.SMAPAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/** 琴键坐标（屏幕比例 0~1，播放时乘屏幕像素） */
data class KeyPoint(val xRatio: Float, val yRatio: Float)

/**
 * 曲谱播放引擎：后台协程推进时间线，可选择是否通过无障碍点击琴键。
 * 支持 停止 / 实时倍速。
 */
class PlayerEngine(private val scope: CoroutineScope) {

    private var job: Job? = null

    @Volatile
    private var running = false

    @Volatile
    private var speed = 1f

    @Volatile
    private var paused = false

    @Volatile
    private var randomSpeed = false

    fun isRunning(): Boolean = running

    fun setSpeed(s: Float) {
        speed = s.coerceIn(0.5f, 2f)
    }

    fun setRandomSpeed(enabled: Boolean) {
        randomSpeed = enabled
    }

    fun pause() { paused = true }

    fun resume() { paused = false }

    fun isPaused(): Boolean = paused

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
        sendScreenTaps: Boolean = true,
        onNoteFired: (Int) -> Unit = {},
        onProgress: (Long) -> Unit = {},
        onFinished: () -> Unit = {}
    ) {
        stop()
        if (sendScreenTaps && !SMAPAccessibilityService.isEnabled()) {
            onFinished()
            return
        }
        running = true
        paused = false
        job = scope.launch(Dispatchers.Default) {
            val notes = song.songNotes.sortedBy { it.time }
            var index = 0
            var songMs = 0.0
            var lastRealMs = SystemClock.elapsedRealtime()
            var randomCountdown = 0
            var currentSpeed = speed
            while (running && index < notes.size) {
                val now = SystemClock.elapsedRealtime()
                val realDelta = now - lastRealMs
                lastRealMs = now
                if (paused) {
                    delay(10)
                    continue
                }
                if (!randomSpeed) currentSpeed = speed
                songMs += realDelta * currentSpeed
                while (index < notes.size && notes[index].time <= songMs) {
                    val note = notes[index++]
                    if (note.key !in keys.indices) continue
                    val k = keys[note.key]
                    if (sendScreenTaps) {
                        SMAPAccessibilityService.tap(k.xRatio * screenW, k.yRatio * screenH)
                    }
                    onNoteFired(note.key)
                    if (randomSpeed && --randomCountdown <= 0) {
                        currentSpeed = 0.5f + Random.nextFloat() * 0.8f
                        randomCountdown = Random.nextInt(2, 6)
                    }
                }
                onProgress(songMs.toLong())
                delay(1)
            }
            running = false
            if (index >= notes.size) onFinished()
        }
    }

    fun stop() {
        running = false
        paused = false
        job?.cancel()
        job = null
    }
}
