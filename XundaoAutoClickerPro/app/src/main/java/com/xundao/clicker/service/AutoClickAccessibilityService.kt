package com.xundao.clicker.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import com.xundao.clicker.model.ActionNode
import com.xundao.clicker.model.ActionType
import com.xundao.clicker.model.ScriptConfig
import com.xundao.clicker.model.StopConditionType
import com.xundao.clicker.overlay.FloatingOverlayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 自动点击器高级版 - 核心无障碍服务 (com.xundao.clicker.service.AutoClickAccessibilityService)
 *
 * 特性：
 * 1. 基于 Android 7.0+ GestureDescription 实现免 Root 毫秒级点击、长按与平滑滑动。
 * 2. 内置防检测算法：支持每次点击随机坐标微偏移 (randomOffsetRadiusPx) 与随机时间间隔抖动 (randomIntervalJitterMs)。
 * 3. 运行时自动通知悬浮窗管理器将所有靶心切换为触摸穿透模式 (FLAG_NOT_TOUCHABLE)，确保手势精准落入底层应用。
 */
class AutoClickAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var executionJob: Job? = null
    private var overlayManager: FloatingOverlayManager? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceConnected.value = true
        overlayManager = FloatingOverlayManager(this, serviceScope)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 无需监听界面节点内容，保持最小资源占用
    }

    override fun onInterrupt() {
        stopEngine()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopEngine()
        overlayManager?.dismissAll()
        overlayManager = null
        instance = null
        _isServiceConnected.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * 显示悬浮控制面板与瞄准靶心
     */
    fun showOverlayWithScript(script: ScriptConfig) {
        stopEngine()
        _activeScript.value = script
        overlayManager?.show(script)
    }

    /**
     * 关闭悬浮窗并停止任务
     */
    fun closeOverlay() {
        stopEngine()
        overlayManager?.dismissAll()
    }

    /**
     * 启动或暂停自动点击引擎
     */
    fun toggleEngine() {
        if (_isRunning.value) {
            stopEngine()
        } else {
            startEngine()
        }
    }

    /**
     * 启动连点/滑动协程循环
     */
    fun startEngine() {
        val script = _activeScript.value ?: return
        if (script.nodes.isEmpty()) return

        executionJob?.cancel()
        _isRunning.value = true
        _executedCycles.value = 0L
        overlayManager?.setTouchPassthrough(true)

        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val startTimeMs = System.currentTimeMillis()

        executionJob = serviceScope.launch {
            try {
                var cycleCount = 0L
                while (isActive && _isRunning.value) {
                    // 检查停止条件
                    if (shouldStop(script, startTimeMs, cycleCount)) {
                        break
                    }

                    val snapshotNodes = script.nodes.toList()
                    for (node in snapshotNodes) {
                        if (!isActive || !_isRunning.value) break
                        if (node.delayBeforeMs > 0L) {
                            delay(node.delayBeforeMs)
                        }
                        val repeats = node.repeatCount.coerceAtLeast(1)
                        for (r in 0 until repeats) {
                            if (!isActive || !_isRunning.value) break
                            if (shouldStop(script, startTimeMs, cycleCount)) break

                            dispatchNodeGesture(
                                node = node,
                                antiDetect = script.antiDetectionEnabled,
                                screenW = screenW,
                                screenH = screenH
                            )

                            val waitMs = computeRandomizedInterval(
                                baseIntervalMs = node.intervalMs,
                                jitterMs = if (script.antiDetectionEnabled) node.randomIntervalJitterMs else 0L
                            )
                            delay(waitMs)
                        }
                    }

                    cycleCount++
                    _executedCycles.value = cycleCount
                }
            } finally {
                _isRunning.value = false
                overlayManager?.setTouchPassthrough(false)
            }
        }
    }

    /**
     * 停止自动点击引擎
     */
    fun stopEngine() {
        executionJob?.cancel()
        executionJob = null
        _isRunning.value = false
        overlayManager?.setTouchPassthrough(false)
    }

    private fun shouldStop(script: ScriptConfig, startTimeMs: Long, currentCycle: Long): Boolean {
        return when (script.stopConditionType) {
            StopConditionType.INFINITE -> false
            StopConditionType.DURATION_SECONDS -> {
                val limitMs = script.stopValue.coerceAtLeast(1L) * 1000L
                (System.currentTimeMillis() - startTimeMs) >= limitMs
            }
            StopConditionType.CYCLE_COUNT -> {
                currentCycle >= script.stopValue.coerceAtLeast(1L)
            }
        }
    }

    private fun computeRandomizedInterval(baseIntervalMs: Long, jitterMs: Long): Long {
        val safeBase = baseIntervalMs.coerceAtLeast(10L)
        if (jitterMs <= 0L) return safeBase
        val delta = Random.nextLong(-jitterMs, jitterMs + 1)
        return max(10L, safeBase + delta)
    }

    private fun computeRandomizedPoint(
        x: Int,
        y: Int,
        radiusPx: Int,
        screenW: Int,
        screenH: Int
    ): Pair<Float, Float> {
        if (radiusPx <= 0) {
            return Pair(
                x.coerceIn(1, max(1, screenW - 1)).toFloat(),
                y.coerceIn(1, max(1, screenH - 1)).toFloat()
            )
        }
        val dx = Random.nextInt(-radiusPx, radiusPx + 1)
        val dy = Random.nextInt(-radiusPx, radiusPx + 1)
        val rx = min(max(1, x + dx), max(1, screenW - 1)).toFloat()
        val ry = min(max(1, y + dy), max(1, screenH - 1)).toFloat()
        return Pair(rx, ry)
    }

    /**
     * 执行单次点击、长按或滑动手势，并挂起等待系统完成回调
     */
    private suspend fun dispatchNodeGesture(
        node: ActionNode,
        antiDetect: Boolean,
        screenW: Int,
        screenH: Int
    ): Boolean = suspendCancellableCoroutine { cont ->
        val radius = if (antiDetect) node.randomOffsetRadiusPx else 0
        val (startX, startY) = computeRandomizedPoint(node.x, node.y, radius, screenW, screenH)

        val path = Path().apply {
            moveTo(startX, startY)
            if (node.type == ActionType.SWIPE) {
                val (endX, endY) = computeRandomizedPoint(node.endX, node.endY, radius, screenW, screenH)
                lineTo(endX, endY)
            }
        }

        val duration = when (node.type) {
            ActionType.CLICK -> node.durationMs.coerceIn(10L, 60_000L)
            ActionType.LONG_PRESS -> max(500L, node.durationMs).coerceAtMost(60_000L)
            ActionType.SWIPE -> max(80L, node.durationMs).coerceAtMost(60_000L)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            },
            null
        )

        if (!dispatched && cont.isActive) {
            cont.resume(false)
        }
    }

    companion object {
        @Volatile
        var instance: AutoClickAccessibilityService? = null
            private set

        private val _isServiceConnected = MutableStateFlow(false)
        val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _executedCycles = MutableStateFlow(0L)
        val executedCycles: StateFlow<Long> = _executedCycles.asStateFlow()

        private val _activeScript = MutableStateFlow<ScriptConfig?>(null)
        val activeScript: StateFlow<ScriptConfig?> = _activeScript.asStateFlow()
    }
}
