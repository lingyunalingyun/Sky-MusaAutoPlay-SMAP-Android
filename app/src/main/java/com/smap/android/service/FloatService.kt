package com.smap.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.smap.android.MainActivity
import com.smap.android.R
import com.smap.android.data.LibraryItem
import com.smap.android.data.LibraryPreferences
import com.smap.android.data.SongRepository
import com.smap.android.cloud.CloudApi
import com.smap.android.engine.KeyLayout
import com.smap.android.engine.KeyLayoutStore
import com.smap.android.engine.KeyPoint
import com.smap.android.engine.PlayerEngine
import com.smap.android.i18n.AppLocale
import com.smap.android.i18n.tr
import com.smap.android.model.SkySong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 游戏演奏悬浮服务（传统 View 实现，避开 Compose 悬浮窗的 ViewTree 要求）：
 * - 悬浮球（可拖动小圆圈）：点击展开/收起选曲面板
 * - 选曲面板：曲库列表 + 播放/停止
 * - 播放时后台按曲谱无障碍点击琴键
 */
class FloatService : Service() {

    companion object {
        const val CHANNEL_ID = "smap_float"
        const val NOTIF_ID = 1001
        private const val ACTION_STOP = "com.smap.android.action.STOP_GAME_MODE"

        @Volatile
        var instance: FloatService? = null
            private set

        fun isRunning(): Boolean = instance != null

        fun start(context: Context) {
            context.startService(Intent(context, FloatService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatService::class.java))
        }
    }

    private lateinit var wm: WindowManager
    private var ballView: View? = null
    private var panelView: View? = null
    private var calibrationView: View? = null
    private val followViews = mutableListOf<View>()
    private val followHandler = Handler(Looper.getMainLooper())
    private val pendingFollowKeys = linkedSetOf<Int>()
    private var followStep = 0
    private var panelVisible = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val engine = PlayerEngine(scope)

    private var songs: List<LibraryItem> = emptyList()
    private var currentSong: SkySong? = null
    private var overlayPositionMs = 0L
    private var overlayProgressFill: View? = null
    private var overlayElapsedLabel: TextView? = null
    private var lastOverlayUiMs = -100L
    private var layout: KeyLayout = KeyLayout()

    // 拖动状态
    private var downX = 0f
    private var downY = 0f
    private var startPX = 0
    private var startPY = 0
    private var downTime = 0L
    private var moved = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppLocale.set(LibraryPreferences(this).language())
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        layout = KeyLayoutStore.load(this)
        songs = SongRepository(this).loadSongs()
        val preferences = LibraryPreferences(this)
        currentSong = songs.firstOrNull { it.fileName == preferences.lastSong() }?.song
        overlayPositionMs = preferences.lastPosition()
        hydrateMissingCloudCovers()
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (ballView == null) addBall()
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        engine.stop()
        stopFollowMode()
        scope.cancel()
        removePanel()
        calibrationView?.let { runCatching { wm.removeView(it) } }
        removeBall()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- 前台通知 ----------

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, tr("SMAP 演奏"), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this,
            1,
            Intent(this, FloatService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(tr("SMAP 演奏"))
            .setContentText(tr("悬浮球运行中，点击展开选曲面板"))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, tr("关闭"), stopPi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    // ---------- 悬浮球 ----------

    private fun addBall() {
        val tv = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_logo)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val size = (62 * resources.displayMetrics.density).toInt()
        val lp = WindowManager.LayoutParams(
            size, size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 200
        }
        tv.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startPX = lp.x; startPY = lp.y
                    downTime = System.currentTimeMillis()
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (dx * dx + dy * dy > 400) moved = true
                    if (moved) {
                        lp.x = startPX + dx
                        lp.y = startPY + dy
                        wm.updateViewLayout(v, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved && System.currentTimeMillis() - downTime < 400) {
                        togglePanel()
                    }
                    true
                }
                else -> true
            }
        }
        wm.addView(tv, lp)
        ballView = tv
    }

    private fun removeBall() {
        ballView?.let { runCatching { wm.removeView(it) } }
        ballView = null
    }

    // ---------- 悬浮面板（传统 View） ----------

    fun togglePanel() {
        if (followViews.isNotEmpty()) {
            stopFollowMode()
            showPanel()
            return
        }
        if (panelVisible) hidePanel() else showPanel()
    }

    private fun showPanel() {
        if (panelView != null) {
            panelView?.visibility = View.VISIBLE
            panelVisible = true
            return
        }
        val density = resources.displayMetrics.density
        fun d(value: Int) = (value * density).toInt()
        fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = d(radius).toFloat() }
        fun text(value: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
            this.text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER_VERTICAL; maxLines = 1
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xF2141414.toInt(), 8)
            setPadding(d(11), d(11), d(11), d(8))
        }
        val buttonRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun actionButton(label: String, click: () -> Unit) = text(label, 9f, bold = true).apply {
            gravity = Gravity.CENTER; background = rounded(0xFF4A4A4C.toInt(), 4); setOnClickListener { click() }
        }
        buttonRow.addView(actionButton(tr("键位调整")) { startCalibration() }, LinearLayout.LayoutParams(0, d(21), 1f).apply { marginEnd = d(8) })
        buttonRow.addView(actionButton(tr("跟弹模式")) { startFollowMode() }, LinearLayout.LayoutParams(0, d(21), 1f))
        panel.addView(buttonRow)

        val panelSongs = overlayQueue()
        val selected = panelSongs.firstOrNull { it.song == currentSong } ?: panelSongs.firstOrNull()
        if (currentSong == null) currentSong = selected?.song
        val duration = currentSong?.songNotes?.maxOfOrNull { it.time }?.toLong() ?: 0L
        val progressBox = FrameLayout(this).apply { background = rounded(0xFF272729.toInt(), 4) }
        val progressWidth = if (duration <= 0) 0 else (d(132) * (overlayPositionMs.toFloat() / duration).coerceIn(0f, 1f)).toInt()
        val progressFill = View(this).apply { background = rounded(0xFF101F9A.toInt(), 4) }
        overlayProgressFill = progressFill
        progressBox.addView(progressFill, FrameLayout.LayoutParams(progressWidth, d(14)))
        progressBox.addView(text(currentSong?.name ?: tr("未选曲"), 8f, bold = true).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, d(14)))
        panel.addView(progressBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, d(14)).apply { topMargin = d(11) })
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val elapsedLabel = text(formatOverlayTime(overlayPositionMs), 7f, bold = true)
        overlayElapsedLabel = elapsedLabel
        times.addView(elapsedLabel, LinearLayout.LayoutParams(0, d(11), 1f))
        times.addView(text(formatOverlayTime(duration), 7f, bold = true).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, d(11), 1f))
        panel.addView(times)

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun control(icon: Int, description: String, click: () -> Unit) = ImageView(this).apply {
            setImageResource(icon)
            contentDescription = description
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(d(5), d(5), d(5), d(5))
            isClickable = true
            setOnClickListener { click() }
        }
        controls.addView(control(R.drawable.ic_overlay_shuffle, tr("随机播放")) {}, LinearLayout.LayoutParams(0, d(33), 1f))
        controls.addView(control(R.drawable.ic_overlay_previous, tr("上一首")) { stepOverlaySong(-1) }, LinearLayout.LayoutParams(0, d(33), 1f))
        controls.addView(control(if (engine.isRunning() && !engine.isPaused()) R.drawable.ic_overlay_pause else R.drawable.ic_overlay_play, tr("播放")) { toggleOverlayPlayback() }.apply {
            background = GradientDrawable().apply { setColor(0xFF17152F.toInt()); shape = GradientDrawable.OVAL; setStroke(d(1), 0xFF3E3A67.toInt()) }
            setPadding(d(7), d(7), d(7), d(7))
        }, LinearLayout.LayoutParams(d(29), d(29)).apply { marginStart = d(3); marginEnd = d(3) })
        controls.addView(control(R.drawable.ic_overlay_next, tr("下一首")) { stepOverlaySong(1) }, LinearLayout.LayoutParams(0, d(33), 1f))
        controls.addView(actionButton("1.0x") {}, LinearLayout.LayoutParams(d(25), d(14)).apply { gravity = Gravity.CENTER_VERTICAL })
        panel.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, d(39)).apply { topMargin = d(2) })
        panel.addView(View(this).apply { setBackgroundColor(0xFF68686A.toInt()) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, d(1)).apply { topMargin = d(5); bottomMargin = d(7) })

        val listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panelSongs.forEachIndexed { index, item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(d(5), d(6), d(5), d(6)); background = rounded(if (index == 0) 0xFF222223.toInt() else 0xFF19191A.toInt(), 4)
                setOnClickListener { currentSong = item.song; rebuildOverlayPanel() }
            }
            val cover = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(0xFF242426.toInt(), 4)
                val bitmap = item.coverBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                if (bitmap != null) setImageBitmap(bitmap) else {
                    setImageResource(R.drawable.ic_overlay_music)
                    setPadding(d(8), d(8), d(8), d(8))
                }
            }
            row.addView(cover, LinearLayout.LayoutParams(d(29), d(29)).apply { marginEnd = d(5) })
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(text(item.song.name, 7.5f, bold = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, d(11)))
            info.addView(text(item.song.author?.ifBlank { tr("未知作者") } ?: tr("未知作者"), 7f, 0xFFB8B8BE.toInt()), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, d(10)))
            info.addView(text(item.song.transcribedBy?.ifBlank { tr("未知创谱者") } ?: tr("未知创谱者"), 7f, 0xFF9A9AA1.toInt()), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, d(10)))
            row.addView(info, LinearLayout.LayoutParams(0, d(31), 1f))
            row.addView(text(formatOverlayTime(item.song.songNotes.maxOfOrNull { it.time }?.toLong() ?: 0L), 7f, 0xFFB8B8BE.toInt()).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(d(28), d(31)))
            listContainer.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, d(43)).apply { bottomMargin = d(1) })
        }
        panel.addView(android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(listContainer)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val lp = WindowManager.LayoutParams(
            d(154),
            d(236),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = d(72)
            y = 180
        }
        wm.addView(panel, lp)
        panelView = panel
        panelVisible = true
    }

    private fun hidePanel() {
        panelView?.visibility = View.GONE
        panelVisible = false
    }

    private fun removePanel() {
        panelView?.let { runCatching { wm.removeView(it) } }
        panelView = null
        overlayProgressFill = null
        overlayElapsedLabel = null
    }

    private fun startFollowMode() {
        if (!SMAPAccessibilityService.isEnabled()) return
        hidePanel()
        stopFollowMode()
        val keyPoints = layout.computeKeys()
        val size = (resources.displayMetrics.widthPixels * .075f).toInt().coerceIn(
            (42 * resources.displayMetrics.density).toInt(),
            (78 * resources.displayMetrics.density).toInt()
        )
        keyPoints.forEachIndexed { key, point ->
            val view = TextView(this).apply {
                text = (key + 1).toString()
                gravity = Gravity.CENTER
                setTextColor(0xCCFFFFFF.toInt())
                textSize = 11f
                setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) followKeyDown(key)
                    true
                }
            }
            val lp = WindowManager.LayoutParams(
                size, size, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (point.xRatio * resources.displayMetrics.widthPixels - size / 2).toInt()
                y = (point.yRatio * resources.displayMetrics.heightPixels - size / 2).toInt()
            }
            wm.addView(view, lp)
            followViews += view
        }
        followStep = 0
        updateFollowHints()
    }

    private val recentFollowPresses = mutableMapOf<Int, Long>()
    private val followDispatch = Runnable {
        val keys = pendingFollowKeys.toList()
        pendingFollowKeys.clear()
        if (keys.isEmpty()) return@Runnable
        setFollowTouchable(false)
        val points = layout.computeKeys()
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        SMAPAccessibilityService.tapMany(keys.map { points[it].xRatio * w to points[it].yRatio * h })
        followHandler.postDelayed({ if (followViews.isNotEmpty()) setFollowTouchable(true) }, 65L)
    }

    private fun followKeyDown(key: Int) {
        val now = android.os.SystemClock.elapsedRealtime()
        recentFollowPresses[key] = now
        pendingFollowKeys += key
        followHandler.removeCallbacks(followDispatch)
        followHandler.postDelayed(followDispatch, 70L)
        val steps = followSteps()
        val expected = steps.getOrNull(followStep) ?: return
        if (expected.all { recentFollowPresses[it]?.let { time -> now - time <= 120L } == true }) {
            recentFollowPresses.clear()
            followStep = if (followStep + 1 >= steps.size) 0 else followStep + 1
            updateFollowHints()
        }
    }

    private fun followSteps(): List<IntArray> {
        val notes = (currentSong ?: songs.firstOrNull()?.song)?.songNotes?.filter { it.key in 0..14 }?.sortedBy { it.time }.orEmpty()
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

    private fun updateFollowHints() {
        val steps = followSteps()
        val current = steps.getOrNull(followStep)?.toSet().orEmpty()
        val next = steps.getOrNull(followStep + 1)?.toSet().orEmpty()
        followViews.forEachIndexed { key, view ->
            view.background = GradientDrawable().apply {
                cornerRadius = 10 * resources.displayMetrics.density
                setColor(when (key) {
                    in current -> 0xB85AA0FF.toInt()
                    in next -> 0x88405F88.toInt()
                    else -> 0x55333336
                })
                setStroke((1 * resources.displayMetrics.density).toInt(), 0x99D8D8DF.toInt())
            }
        }
    }

    private fun setFollowTouchable(touchable: Boolean) {
        followViews.forEach { view ->
            val lp = view.layoutParams as WindowManager.LayoutParams
            lp.flags = if (touchable) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            runCatching { wm.updateViewLayout(view, lp) }
        }
    }

    private fun stopFollowMode() {
        followHandler.removeCallbacks(followDispatch)
        pendingFollowKeys.clear()
        recentFollowPresses.clear()
        followViews.forEach { runCatching { wm.removeView(it) } }
        followViews.clear()
    }

    private fun startCalibration() {
        hidePanel()
        val view = KeyCalibrationView(this, layout.computeKeys().toMutableList()) { points ->
            layout = KeyLayout(customKeys = points)
            KeyLayoutStore.save(this, layout)
            calibrationView?.let { runCatching { wm.removeView(it) } }
            calibrationView = null
            panelView?.visibility = View.VISIBLE
            panelVisible = true
        }
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(view, lp)
        calibrationView = view
    }

    // ---------- 播放 ----------

    private fun formatOverlayTime(ms: Long): String = "%d:%02d".format(ms / 60_000, ms / 1_000 % 60)

    private fun overlayQueue(): List<LibraryItem> {
        val order = LibraryPreferences(this).playlist()
        return order.mapNotNull { fileName -> songs.firstOrNull { it.fileName == fileName } }
            .ifEmpty { songs }
    }

    private fun hydrateMissingCloudCovers() {
        val missing = songs.filter { it.coverBytes == null && !it.fileName.endsWith(".mid", true) }
        if (missing.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val api = CloudApi(this@FloatService)
            val cloud = api.list("", "newest", 0, 1, 100).getOrNull()?.items.orEmpty()
            val repository = SongRepository(this@FloatService)
            var changed = false
            missing.forEach { local ->
                val remote = cloud.firstOrNull {
                    val remoteTitle = it.title.trim().trim('-').lowercase()
                    val localTitle = local.song.name.trim().trim('-').lowercase()
                    remoteTitle.isNotBlank() && localTitle.contains(remoteTitle) &&
                        (it.artist.isBlank() || local.song.author.equals(it.artist, true))
                } ?: return@forEach
                val cover = api.cover(remote) ?: return@forEach
                repository.saveCover(local.fileName, cover)
                changed = true
            }
            if (changed) followHandler.post {
                songs = repository.loadSongs()
                if (panelVisible) rebuildOverlayPanel()
            }
        }
    }

    private fun rebuildOverlayPanel() {
        removePanel()
        showPanel()
    }

    private fun toggleOverlayPlayback() {
        if (!engine.isRunning()) playCurrent()
        else if (engine.isPaused()) engine.resume() else engine.pause()
        rebuildOverlayPanel()
    }

    private fun stepOverlaySong(delta: Int) {
        val queue = overlayQueue()
        if (queue.isEmpty()) return
        val index = queue.indexOfFirst { it.song == currentSong }.let { if (it < 0) 0 else it }
        currentSong = queue[(index + delta + queue.size) % queue.size].song
        overlayPositionMs = 0L
        engine.stop()
        rebuildOverlayPanel()
    }

    private fun playCurrent() {
        val song = currentSong ?: songs.firstOrNull()?.song ?: return
        if (!SMAPAccessibilityService.isEnabled()) return
        if (engine.isRunning()) { engine.stop(); return }
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        val keys = layout.computeKeys()
        engine.play(
            song = song, keys = keys, screenW = w, screenH = h,
            onNoteFired = {},
            onProgress = { position ->
                overlayPositionMs = position
                if (position - lastOverlayUiMs < 50L) return@play
                lastOverlayUiMs = position
                followHandler.post {
                    val total = song.songNotes.maxOfOrNull { it.time }?.toLong() ?: 0L
                    val width = (132 * resources.displayMetrics.density * if (total > 0) position.toFloat() / total else 0f).toInt()
                    overlayProgressFill?.layoutParams = overlayProgressFill?.layoutParams?.apply { this.width = width }
                    overlayProgressFill?.requestLayout()
                    overlayElapsedLabel?.text = formatOverlayTime(position)
                }
            },
            onFinished = { overlayPositionMs = 0L; rebuildOverlayPanel() }
        )
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
}

private class KeyCalibrationView(
    context: Context,
    private val points: MutableList<KeyPoint>,
    private val onConfirm: (List<KeyPoint>) -> Unit
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private var active = -1

    override fun onDraw(canvas: Canvas) {
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.color = Color.WHITE
        paint.textSize = 27 * density
        canvas.drawText(tr("将方框移动到按键位置"), width / 2f, 45 * density, paint)
        val half = 31 * density
        points.forEachIndexed { index, point ->
            val x = point.xRatio * width
            val y = point.yRatio * height
            paint.color = 0x990D0000.toInt()
            canvas.drawRoundRect(x - half, y - half, x + half, y + half, 5 * density, 5 * density, paint)
            paint.color = Color.WHITE
            paint.textSize = 12 * density
            canvas.drawText((index + 1).toString(), x, y + 4 * density, paint)
        }
        val top = height - 50 * density
        paint.color = 0xEE151515.toInt()
        canvas.drawRoundRect(width / 2f - 55 * density, top, width / 2f + 55 * density, height - 9 * density, 6 * density, 6 * density, paint)
        paint.color = Color.WHITE
        paint.textSize = 18 * density
        canvas.drawText(tr("确认"), width / 2f, height - 23 * density, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (event.y > height - 65 * density && kotlin.math.abs(event.x - width / 2f) < 75 * density) {
                    onConfirm(points.toList())
                    return true
                }
                active = points.indices.minByOrNull { i ->
                    val dx = points[i].xRatio * width - event.x
                    val dy = points[i].yRatio * height - event.y
                    dx * dx + dy * dy
                } ?: -1
            }
            MotionEvent.ACTION_MOVE -> if (active >= 0) {
                points[active] = KeyPoint((event.x / width).coerceIn(.02f, .98f), (event.y / height).coerceIn(.02f, .98f))
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> active = -1
        }
        return true
    }
}
