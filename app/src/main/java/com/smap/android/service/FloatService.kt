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
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.smap.android.MainActivity
import com.smap.android.data.LibraryItem
import com.smap.android.data.SongRepository
import com.smap.android.engine.KeyLayout
import com.smap.android.engine.KeyLayoutStore
import com.smap.android.engine.PlayerEngine
import com.smap.android.model.SkySong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

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
    private var panelVisible = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val engine = PlayerEngine(scope)

    private var songs: List<LibraryItem> = emptyList()
    private var currentSong: SkySong? = null
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
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        layout = KeyLayoutStore.load(this)
        songs = SongRepository(this).loadSongs()
        startForegroundCompat()
        addBall()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        instance = null
        engine.stop()
        scope.cancel()
        removePanel()
        removeBall()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- 前台通知 ----------

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "SMAP 演奏", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("SMAP 演奏")
            .setContentText("悬浮球运行中，点击展开选曲面板")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
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
        val tv = TextView(this).apply {
            text = "🎹"
            textSize = 22f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xEE1E1B4B.toInt())
            }
        }
        val size = (52 * resources.displayMetrics.density).toInt()
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
        tv.setOnClickListener { togglePanel() }
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
        if (panelVisible) hidePanel() else showPanel()
    }

    private fun showPanel() {
        if (panelView != null) {
            panelView?.visibility = View.VISIBLE
            panelVisible = true
            return
        }
        val density = resources.displayMetrics.density
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF219163A.toInt())
            setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
        }

        // 标题行
        panel.addView(TextView(this).apply {
            text = "🎹 SMAP 选曲"
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
        })

        // 当前曲目
        val currentLabel = TextView(this).apply {
            setTextColor(0xFF9CA3AF.toInt())
            textSize = 13f
            text = "未选曲"
        }
        panel.addView(currentLabel)

        // 曲库列表
        val list = ListView(this).apply {
            divider = null
            adapter = object : BaseAdapter() {
                override fun getCount() = songs.size
                override fun getItem(p: Int) = songs[p]
                override fun getItemId(p: Int) = p.toLong()
                override fun getView(p: Int, convertView: View?, parent: ViewGroup?): View {
                    val item = songs[p]
                    val row = LinearLayout(this@FloatService).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
                    }
                    row.addView(TextView(this@FloatService).apply {
                        text = item.song.name
                        setTextColor(Color.WHITE)
                        textSize = 14f
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        setSingleLine(true)
                    })
                    row.addView(TextView(this@FloatService).apply {
                        text = "BPM ${item.song.bpm} · ${item.song.songNotes.size}"
                        setTextColor(0xFF9CA3AF.toInt())
                        textSize = 11f
                    })
                    if (item.song == currentSong) {
                        row.setBackgroundColor(0x337DD3FC.toInt())
                    }
                    return row
                }
            }
            setOnItemClickListener { _, _, p, _ ->
                currentSong = songs[p].song
                currentLabel.text = songs[p].song.name
            }
        }
        panel.addView(
            list,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (160 * density).toInt()
            )
        )

        // 播放控制
        val playBtn = TextView(this).apply {
            text = "▶ 开始弹奏"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xFF0E0A1F.toInt())
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                cornerRadius = (12 * density).toFloat()
                setColor(0xFF7DD3FC.toInt())
            }
            setOnClickListener {
                playCurrent()
            }
        }
        panel.addView(
            playBtn,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (44 * density).toInt()
            ).apply {
                topMargin = (10 * density).toInt()
            }
        )

        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (300 * density).toInt(),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
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
    }

    // ---------- 播放 ----------

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
            onFinished = { hidePanel() }
        )
        hidePanel()
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
}
