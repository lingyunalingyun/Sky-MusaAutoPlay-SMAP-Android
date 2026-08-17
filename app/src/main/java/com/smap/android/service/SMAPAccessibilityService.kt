package com.smap.android.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务：为自动弹琴提供屏幕坐标点击能力（dispatchGesture）。
 * 需要在系统设置中开启"SMAP 无障碍服务"权限。
 */
class SMAPAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: SMAPAccessibilityService? = null
            private set

        /** 服务是否已启用 */
        fun isEnabled(): Boolean = instance != null

        /** 在 (x, y) 处执行一次点击（按住 durationMs 毫秒） */
        fun tap(x: Float, y: Float, durationMs: Long = 18) {
            instance?.dispatchTap(x, y, durationMs)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不需要监听事件
    }

    override fun onInterrupt() {
        // 忽略
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    private fun dispatchTap(x: Float, y: Float, durationMs: Long) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, null, null)
    }
}
