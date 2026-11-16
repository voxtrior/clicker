package com.xundao.clicker.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.PowerManager
import android.os.Process
import android.view.accessibility.AccessibilityEvent
import com.xundao.clicker.model.ScheduledClickConfig
import com.xundao.clicker.model.SchedulerState
import com.xundao.clicker.model.TriggerMode
import com.xundao.clicker.overlay.FloatingOverlayManager
import com.xundao.clicker.time.TimeSyncEngine
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
import kotlinx.coroutines.withContext
import java.util.concurrent.locks.LockSupport
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 自动点击器高级版 - 毫秒级定时定点核心无障碍服务
 *
 * 核心亮点：
 * 1. 两段式亚毫秒级高精度定时触发器：
 *    - 距离目标时刻 > 250ms 时采用协程分段休眠，降低功耗并实时刷新倒计时；
 *    - 距离目标时刻 ≤ 250ms 时自动提升线程优先级至 URGENT_DISPLAY，并进入 0.5ms 纳秒级微步进 + 末段 15ms 自旋锁冲刺，
 *      确保在设定的毫秒时刻（误差 < 1ms）瞬间发出无障碍点击手势！
 * 2. 待命/触发期间自动开启悬浮准星触摸穿透 (FLAG_NOT_TOUCHABLE)，绝不遮挡底层 App 抢购按钮。
 */
class AutoClickAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var schedulerJob: Job? = null
    private var overlayManager: FloatingOverlayManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceConnected.value = true
        overlayManager = FloatingOverlayManager(this, serviceScope)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 保持空实现以确保零额外性能损耗
    }

    override fun onInterrupt() {
        cancelTask()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cancelTask()
        overlayManager?.dismissAll()
        overlayManager = null
        instance = null
        _isServiceConnected.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    fun showOverlay(config: ScheduledClickConfig) {
        cancelTask()
        _activeConfig.value = config
        overlayManager?.show(config)
    }

    fun closeOverlay() {
        cancelTask()
        overlayManager?.dismissAll()
    }

    /**
     * 切换“定时待命 / 停止”状态
     */
    fun toggleArmOrRun() {
        val currentState = _schedulerState.value
        if (currentState == SchedulerState.ARMED_WAITING || currentState == SchedulerState.FIRING) {
            cancelTask()
        } else {
            val cfg = _activeConfig.value ?: return
            if (cfg.triggerMode == TriggerMode.SCHEDULED_AT_TIME) {
                armScheduledClick(cfg)
            } else {
                startContinuousClick(cfg)
            }
        }
    }

    /**
     * 立即在当前靶心坐标执行一次测试点击，方便用户验证坐标位置
     */
    fun performSingleTestClick() {
        val cfg = _activeConfig.value ?: return
        serviceScope.launch {
            overlayManager?.setTouchPassthrough(true)
            delay(60L)
            val dm = resources.displayMetrics
            dispatchFastTap(
                x = cfg.targetX,
                y = cfg.targetY,
                pressDurationMs = cfg.pressDurationMs,
                randomRadiusPx = 0,
                screenW = dm.widthPixels,
                screenH = dm.heightPixels
            )
            delay(80L)
            if (_schedulerState.value == SchedulerState.IDLE || _schedulerState.value == SchedulerState.COMPLETED) {
                overlayManager?.setTouchPassthrough(false)
            }
        }
    }

    /**
     * 启动毫秒级定时定点待命引擎
     */
    fun armScheduledClick(config: ScheduledClickConfig) {
        schedulerJob?.cancel()
        _activeConfig.value = config
        val nowServer = TimeSyncEngine.nowServerMillis()
        val targetEpoch = config.computeNextTargetEpochMs(nowServer)
        _targetTriggerEpochMs.value = targetEpoch
        _executedClicks.value = 0
        _schedulerState.value = SchedulerState.ARMED_WAITING

        // 锁定靶心并开启点击穿透，确保到点时手势直接作用于下层京东/淘宝等应用按钮
        overlayManager?.setTouchPassthrough(true)
        acquireWakeLock()

        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        schedulerJob = serviceScope.launch {
            try {
                // 启动前若距离目标还有 3 秒以上，自动在后台静默做一次高精度授时刷新
                if (targetEpoch - TimeSyncEngine.nowServerMillis() > 3500L) {
                    launch(Dispatchers.IO) {
                        TimeSyncEngine.syncWithServer(config.timeSource, sampleCount = 3)
                    }
                }

                // 第一阶段：距离目标 > 250ms 时协程分段等待
                while (isActive) {
                    val remaining = targetEpoch - TimeSyncEngine.nowServerMillis()
                    if (remaining <= 250L) break
                    delay(min(40L, remaining - 200L))
                }

                // 第二阶段：切入高优先级线程，纳秒级微步进 + 末段 15ms 忙等自旋锁冲刺，实现 <1ms 触发精度
                withContext(Dispatchers.Default) {
                    runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }
                    while (isActive) {
                        val rem = targetEpoch - TimeSyncEngine.nowServerMillis()
                        if (rem <= 0L) break
                        if (rem > 15L) {
                            LockSupport.parkNanos(500_000L) // 0.5ms 步进
                        } else {
                            // 最后 15ms 纯 CPU 自旋，消除操作系统线程调度唤醒延迟
                            Thread.onSpinWait()
                        }
                    }
                }

                if (!isActive) return@launch

                // 到达目标毫秒时刻！立即开始执行定点点击 / 突发连点
                _schedulerState.value = SchedulerState.FIRING
                val totalShots = config.burstCount.coerceAtLeast(1)
                for (shot in 1..totalShots) {
                    if (!isActive) break
                    _executedClicks.value = shot
                    dispatchFastTap(
                        x = config.targetX,
                        y = config.targetY,
                        pressDurationMs = config.pressDurationMs,
                        randomRadiusPx = config.randomRadiusPx,
                        screenW = screenW,
                        screenH = screenH
                    )
                    if (shot < totalShots) {
                        delay(config.intervalMs.coerceAtLeast(15L))
                    }
                }

                _schedulerState.value = SchedulerState.COMPLETED
            } finally {
                releaseWakeLock()
                if (_schedulerState.value != SchedulerState.ARMED_WAITING &&
                    _schedulerState.value != SchedulerState.FIRING
                ) {
                    overlayManager?.setTouchPassthrough(false)
                }
            }
        }
    }

    /**
     * 连续循环连点模式
     */
    fun startContinuousClick(config: ScheduledClickConfig) {
        schedulerJob?.cancel()
        _activeConfig.value = config
        _executedClicks.value = 0
        _schedulerState.value = SchedulerState.FIRING
        overlayManager?.setTouchPassthrough(true)
        acquireWakeLock()

        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        schedulerJob = serviceScope.launch {
            try {
                var count = 0
                while (isActive && _schedulerState.value == SchedulerState.FIRING) {
                    count++
                    _executedClicks.value = count
                    dispatchFastTap(
                        x = config.targetX,
                        y = config.targetY,
                        pressDurationMs = config.pressDurationMs,
                        randomRadiusPx = config.randomRadiusPx,
                        screenW = screenW,
                        screenH = screenH
                    )
                    delay(config.intervalMs.coerceAtLeast(15L))
                }
            } finally {
                releaseWakeLock()
                overlayManager?.setTouchPassthrough(false)
            }
        }
    }

    fun cancelTask() {
        schedulerJob?.cancel()
        schedulerJob = null
        releaseWakeLock()
        _schedulerState.value = SchedulerState.IDLE
        overlayManager?.setTouchPassthrough(false)
    }

    private suspend fun dispatchFastTap(
        x: Int,
        y: Int,
        pressDurationMs: Long,
        randomRadiusPx: Int,
        screenW: Int,
        screenH: Int
    ): Boolean = suspendCancellableCoroutine { cont ->
        val dx = if (randomRadiusPx > 0) Random.nextInt(-randomRadiusPx, randomRadiusPx + 1) else 0
        val dy = if (randomRadiusPx > 0) Random.nextInt(-randomRadiusPx, randomRadiusPx + 1) else 0
        val finalX = (x + dx).coerceIn(1, max(1, screenW - 1)).toFloat()
        val finalY = (y + dy).coerceIn(1, max(1, screenH - 1)).toFloat()

        val path = Path().apply { moveTo(finalX, finalY) }
        val stroke = GestureDescription.StrokeDescription(
            path,
            0L,
            pressDurationMs.coerceIn(10L, 10_000L)
        )
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val ok = dispatchGesture(
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
        if (!ok && cont.isActive) {
            cont.resume(false)
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "XundaoClicker::PrecisionTimerWakeLock"
        ).apply {
            acquire(30 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null
    }

    companion object {
        @Volatile
        var instance: AutoClickAccessibilityService? = null
            private set

        private val _isServiceConnected = MutableStateFlow(false)
        val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

        private val _schedulerState = MutableStateFlow(SchedulerState.IDLE)
        val schedulerState: StateFlow<SchedulerState> = _schedulerState.asStateFlow()

        private val _targetTriggerEpochMs = MutableStateFlow(0L)
        val targetTriggerEpochMs: StateFlow<Long> = _targetTriggerEpochMs.asStateFlow()

        private val _executedClicks = MutableStateFlow(0)
        val executedClicks: StateFlow<Int> = _executedClicks.asStateFlow()

        private val _activeConfig = MutableStateFlow<ScheduledClickConfig?>(null)
        val activeConfig: StateFlow<ScheduledClickConfig?> = _activeConfig.asStateFlow()
    }
}
