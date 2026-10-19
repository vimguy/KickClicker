package com.antigravity.kickclicker.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.atomic.AtomicLong

class ClickAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ClickAccessibilityService"
        private const val MAX_CLICKABLE_ANCESTOR_DEPTH = 12

        @Volatile
        var instance: ClickAccessibilityService? = null
            private set

        fun isServiceEnabled(context: Context): Boolean {
            if (instance != null) return true
            val expected = "${context.packageName}/${ClickAccessibilityService::class.java.name}"
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            while (splitter.hasNext()) {
                val component = splitter.next()
                if (component.equals(expected, ignoreCase = true) ||
                    component.endsWith("/${ClickAccessibilityService::class.java.name}")
                ) {
                    return true
                }
            }
            return false
        }

        /** 供音量键等急停路径使用：服务实例引用不受影响，由调用方判空。 */
        fun disableSelfIfRunning(): Boolean {
            val service = instance ?: return false
            return try {
                service.disableSelf()
                true
            } catch (t: Throwable) {
                Log.w(TAG, "关闭无障碍服务失败", t)
                false
            } finally {
                if (service === instance) instance = null
            }
        }
    }

    private val acceptedCount = AtomicLong(0)
    private val rejectedCount = AtomicLong(0)
    private val completedCount = AtomicLong(0)
    private val cancelledCount = AtomicLong(0)

    data class DispatchStats(
        val accepted: Long,
        val rejected: Long,
        val completed: Long,
        val cancelled: Long
    )

    fun dispatchStats(): DispatchStats = DispatchStats(
        acceptedCount.get(), rejectedCount.get(), completedCount.get(), cancelledCount.get()
    )

    fun resetDispatchStats() {
        acceptedCount.set(0)
        rejectedCount.set(0)
        completedCount.set(0)
        cancelledCount.set(0)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 连点只依赖主动查询与手势派发，不需要消费事件流
    }

    override fun onInterrupt() {
        Log.i(TAG, "无障碍服务被中断")
    }

    /**
     * 免 Root 派发高精度毫秒手势点击。
     * 返回 false 表示手势未被系统受理（服务已断开或手势队列拥塞），调用方据此统计漏点。
     */
    fun dispatchTap(x: Float, y: Float, durationMs: Long = 10L): Boolean {
        return try {
            val path = Path().apply { moveTo(x.coerceAtLeast(0f), y.coerceAtLeast(0f)) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(1L))
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            // 回调与 handler 的传法保持与 1.0.3 已验证实现一致：每次新建回调、handler 传 null
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    completedCount.incrementAndGet()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    cancelledCount.incrementAndGet()
                }
            }
            val accepted = dispatchGesture(gesture, callback, null)
            if (accepted) acceptedCount.incrementAndGet() else rejectedCount.incrementAndGet()
            accepted
        } catch (t: Throwable) {
            rejectedCount.incrementAndGet()
            Log.w(TAG, "手势派发异常", t)
            false
        }
    }

    /**
     * 智能识别并点击包含指定文本的可点击控件（如"立即购买""提交订单""确定"）。
     * 先查当前活动窗口，再遍历其他可交互窗口，覆盖弹窗式确认按钮。
     */
    fun clickByTextGlobally(text: String): Boolean {
        if (text.isBlank()) return false
        if (clickInWindow(rootInActiveWindow, text)) return true
        return try {
            val windows: List<AccessibilityWindowInfo>? = windows
            windows?.any { window -> clickInWindow(window?.root, text) } == true
        } catch (t: Throwable) {
            false
        }
    }

    private fun clickInWindow(root: AccessibilityNodeInfo?, text: String): Boolean {
        if (root == null) return false
        return try {
            val nodes = root.findAccessibilityNodeInfosByText(text) ?: return false
            nodes.any { node ->
                if (node == null || !node.isVisibleToUser) {
                    false
                } else {
                    val clickable = findClickableAncestor(node)
                    clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                }
            }
        } catch (t: Throwable) {
            false
        }
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < MAX_CLICKABLE_ANCESTOR_DEPTH) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }
}
