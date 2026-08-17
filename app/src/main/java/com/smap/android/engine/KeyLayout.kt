package com.smap.android.engine

import android.content.Context

/**
 * 琴键布局：3 行 5 列的面板参数 + 整体位置微调。
 * 存储为屏幕比例（0~1），适配不同分辨率；可整体偏移/缩放校准。
 */
data class KeyLayout(
    val panelX: Float = 0f,     // 面板中心 X 偏移（比例，0 = 居中）
    val panelY: Float = 0.12f,  // 面板中心 Y 偏移（比例，正 = 偏下）
    val keyW: Float = 0.15f,    // 键宽（比例）
    val keyH: Float = 0.10f,    // 键高（比例）
    val rowGap: Float = 0.03f   // 行间距（比例）
) {
    /** 计算 15 个键的中心坐标（比例 0~1），顺序 = key 0~14 */
    fun computeKeys(): List<KeyPoint> {
        val gapX = keyW * 0.15f
        val totalW = 5 * keyW + 4 * gapX
        val totalH = 3 * keyH + 2 * rowGap
        val left = (0.5f + panelX) - totalW / 2
        val top = (0.5f + panelY) - totalH / 2
        val keys = mutableListOf<KeyPoint>()
        for (row in 0 until 3) {
            for (col in 0 until 5) {
                val x = left + col * (keyW + gapX) + keyW / 2
                val y = top + row * (keyH + rowGap) + keyH / 2
                keys.add(KeyPoint(x.coerceIn(0.02f, 0.98f), y.coerceIn(0.02f, 0.98f)))
            }
        }
        return keys
    }
}

object KeyLayoutStore {
    private const val PREFS = "key_layout"
    private const val K_X = "panelX"
    private const val K_Y = "panelY"
    private const val K_W = "keyW"
    private const val K_H = "keyH"
    private const val K_GAP = "rowGap"

    fun load(context: Context): KeyLayout {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains(K_W)) return KeyLayout()
        return KeyLayout(
            panelX = p.getFloat(K_X, 0f),
            panelY = p.getFloat(K_Y, 0.12f),
            keyW = p.getFloat(K_W, 0.15f),
            keyH = p.getFloat(K_H, 0.10f),
            rowGap = p.getFloat(K_GAP, 0.03f)
        )
    }

    fun save(context: Context, l: KeyLayout) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(K_X, l.panelX)
            .putFloat(K_Y, l.panelY)
            .putFloat(K_W, l.keyW)
            .putFloat(K_H, l.keyH)
            .putFloat(K_GAP, l.rowGap)
            .apply()
    }

    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
