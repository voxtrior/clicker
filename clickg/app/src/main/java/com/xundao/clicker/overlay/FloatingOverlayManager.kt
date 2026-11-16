package com.xundao.clicker.overlay

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.xundao.clicker.model.ScheduledClickConfig
import com.xundao.clicker.model.SchedulerState
import com.xundao.clicker.model.TriggerMode
import com.xundao.clicker.repository.ConfigRepository
import com.xundao.clicker.service.AutoClickAccessibilityService
import com.xundao.clicker.time.TimeSyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 毫秒级授时悬浮时钟面板 + 可拖拽坐标准星管理器
 *
 * 悬浮面板实时显示：
 * 1. 当前同步服务器名称（如：京东服务器时间 / 阿里淘宝时间 / 国家授时中心）
 * 2. 精确到毫秒的实时服务器时钟 (`HH:mm:ss.SSS`，每 16ms 高刷显示)
 * 3. 目标触发时刻与毫秒级倒计时 (`T - 00:00:12.345`)
 * 4. 实时坐标显示 `(X, Y)`、一键校准时间、一键进入【🎯 定时待命】、一键【⚡ 测试点击】、轻触准星弹窗精细修改毫秒时间与坐标
 */
class FloatingOverlayManager(
    private val service: AutoClickAccessibilityService,
    private val scope: CoroutineScope
) {
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val repository = ConfigRepository(service)
    private val density = service.resources.displayMetrics.density

    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private var targetCrosshairView: TargetCrosshairView? = null
    private var targetParams: WindowManager.LayoutParams? = null

    private var sourceHeaderTv: TextView? = null
    private var liveClockTv: TextView? = null
    private var countdownTv: TextView? = null
    private var coordInfoTv: TextView? = null
    private var armButtonTv: TextView? = null

    private var clockTickerJob: Job? = null
    private var activeConfig: ScheduledClickConfig? = null

    init {
        scope.launch {
            AutoClickAccessibilityService.schedulerState.collectLatest { state ->
                updateArmButtonUi(state)
                targetCrosshairView?.invalidate()
            }
        }
    }

    fun show(config: ScheduledClickConfig) {
        dismissAll()
        activeConfig = config
        createFloatingClockPanel(config)
        createTargetCrosshair(config)
        startHighFreqClockTicker()
    }

    fun dismissAll() {
        clockTickerJob?.cancel()
        clockTickerJob = null

        targetCrosshairView?.let { runCatching { windowManager.removeView(it) } }
        targetCrosshairView = null
        targetParams = null

        panelView?.let { runCatching { windowManager.removeView(it) } }
        panelView = null
        panelParams = null
    }

    /**
     * 定时待命或连点触发期间将准星设为 FLAG_NOT_TOUCHABLE，确保手势 100% 穿透到底层应用按钮
     */
    fun setTouchPassthrough(passthrough: Boolean) {
        val view = targetCrosshairView ?: return
        val lp = targetParams ?: return
        if (passthrough) {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            view.alpha = 0.65f
        } else {
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            view.alpha = 1.0f
        }
        runCatching { windowManager.updateViewLayout(view, lp) }
        view.invalidate()
    }

    private fun startHighFreqClockTicker() {
        clockTickerJob?.cancel()
        clockTickerJob = scope.launch {
            while (isActive) {
                val cfg = activeConfig
                val nowMs = TimeSyncEngine.nowServerMillis()
                liveClockTv?.text = ScheduledClickConfig.formatEpochFullMs(nowMs)

                val syncInfo = TimeSyncEngine.lastSyncResult.value
                val offsetSign = if (syncInfo.clockOffsetMs >= 0) "+${syncInfo.clockOffsetMs}" else "${syncInfo.clockOffsetMs}"
                sourceHeaderTv?.text = "${cfg?.timeSource?.displayName ?: "服务器时间"} (Δ${offsetSign}ms)"

                val state = AutoClickAccessibilityService.schedulerState.value
                when (state) {
                    SchedulerState.ARMED_WAITING -> {
                        val targetMs = AutoClickAccessibilityService.targetTriggerEpochMs.value
                        val rem = (targetMs - nowMs).coerceAtLeast(0L)
                        countdownTv?.text = "倒计时: ${formatRemainingMs(rem)}"
                        countdownTv?.setTextColor(
                            if (rem <= 3000L) Color.parseColor("#FB7185") else Color.parseColor("#38BDF8")
                        )
                    }
                    SchedulerState.FIRING -> {
                        val shots = AutoClickAccessibilityService.executedClicks.value
                        countdownTv?.text = "🔥 正在毫秒连发: 第 $shots / ${cfg?.burstCount ?: 1} 次"
                        countdownTv?.setTextColor(Color.parseColor("#FBBF24"))
                    }
                    SchedulerState.COMPLETED -> {
                        val shots = AutoClickAccessibilityService.executedClicks.value
                        countdownTv?.text = "✅ 已准时完成 ($shots 次点击)"
                        countdownTv?.setTextColor(Color.parseColor("#34D399"))
                    }
                    SchedulerState.IDLE -> {
                        if (cfg != null) {
                            val advStr = if (cfg.advanceOffsetMs >= 0) "+${cfg.advanceOffsetMs}ms" else "${cfg.advanceOffsetMs}ms"
                            countdownTv?.text = "设定时刻: ${cfg.formatTargetTimeString()} ($advStr)"
                            countdownTv?.setTextColor(Color.parseColor("#CBD5E1"))
                        }
                    }
                }
                delay(16L) // ~60fps 毫秒表刷新率
            }
        }
    }

    private fun formatRemainingMs(remMs: Long): String {
        val hours = remMs / 3_600_000L
        val minutes = (remMs % 3_600_000L) / 60_000L
        val seconds = (remMs % 60_000L) / 1000L
        val millis = remMs % 1000L
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, millis)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingClockPanel(config: ScheduledClickConfig) {
        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#E60F172A"))
                setStroke(dp(1), Color.parseColor("#6638BDF8"))
            }
        }

        // 顶部状态与拖拽标题栏
        val headerTv = TextView(service).apply {
            text = "${config.timeSource.displayName} (点击可拖拽)"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 10.5f
        }
        sourceHeaderTv = headerTv
        root.addView(headerTv)

        // 核心大号毫秒级实时时钟 (HH:mm:ss.SSS)
        val clockTv = TextView(service).apply {
            text = "00:00:00.000"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 21f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(2), 0, dp(2))
        }
        liveClockTv = clockTv
        root.addView(clockTv)

        // 目标时刻与毫秒倒计时显示
        val cdTv = TextView(service).apply {
            text = "设定时刻: ${config.formatTargetTimeString()}"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
        }
        countdownTv = cdTv
        root.addView(cdTv)

        // 当前准星坐标与连发参数显示
        val coordTv = TextView(service).apply {
            text = "坐标: (${config.targetX}, ${config.targetY}) · 连发: ${config.burstCount}次/${config.intervalMs}ms"
            setTextColor(Color.parseColor("#A7F3D0"))
            textSize = 11f
            setPadding(0, dp(2), 0, dp(8))
        }
        coordInfoTv = coordTv
        root.addView(coordTv)

        // 按钮第一行：【🎯 定时待命 / 停止】 + 【⚙ 设时间/坐标】
        val row1 = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val armBtn = createActionBtn("🎯 定时待命", "#10B981", dp(104)) {
            service.toggleArmOrRun()
        }
        armButtonTv = armBtn
        row1.addView(armBtn)

        val space1 = View(service).apply { layoutParams = LinearLayout.LayoutParams(dp(6), dp(1)) }
        row1.addView(space1)

        val editBtn = createActionBtn("⚙ 时间/坐标", "#3B82F6", dp(96)) {
            if (AutoClickAccessibilityService.schedulerState.value == SchedulerState.ARMED_WAITING ||
                AutoClickAccessibilityService.schedulerState.value == SchedulerState.FIRING
            ) {
                Toast.makeText(service, "请先取消待命后再修改参数", Toast.LENGTH_SHORT).show()
                return@createActionBtn
            }
            showQuickConfigDialog(config)
        }
        row1.addView(editBtn)
        root.addView(row1)

        // 按钮第二行：【🔄 校准时间】 + 【⚡ 测试点击】 + 【✖ 关闭】
        val row2 = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }

        row2.addView(createActionBtn("🔄 校时", "#6366F1", dp(64)) {
            scope.launch {
                Toast.makeText(service, "正在校准 ${config.timeSource.displayName}...", Toast.LENGTH_SHORT).show()
                val res = TimeSyncEngine.syncWithServer(config.timeSource, sampleCount = 4)
                Toast.makeText(service, res.message, Toast.LENGTH_SHORT).show()
            }
        })

        row2.addView(View(service).apply { layoutParams = LinearLayout.LayoutParams(dp(6), dp(1)) })

        row2.addView(createActionBtn("⚡ 测点", "#0EA5E9", dp(64)) {
            service.performSingleTestClick()
        })

        row2.addView(View(service).apply { layoutParams = LinearLayout.LayoutParams(dp(6), dp(1)) })

        row2.addView(createActionBtn("✖ 关闭", "#EF4444", dp(64)) {
            service.closeOverlay()
        })

        root.addView(row2)

        val lp = createOverlayLayoutParams(
            width = WindowManager.LayoutParams.WRAP_CONTENT,
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            x = dp(16),
            y = dp(110)
        )

        val dragTouchListener = object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var downRawX = 0f
            private var downRawY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = lp.x
                        startY = lp.y
                        downRawX = event.rawX
                        downRawY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = startX + (event.rawX - downRawX).roundToInt()
                        lp.y = startY + (event.rawY - downRawY).roundToInt()
                        runCatching { windowManager.updateViewLayout(root, lp) }
                        return true
                    }
                }
                return false
            }
        }
        root.setOnTouchListener(dragTouchListener)
        headerTv.setOnTouchListener(dragTouchListener)
        clockTv.setOnTouchListener(dragTouchListener)

        windowManager.addView(root, lp)
        panelView = root
        panelParams = lp
    }

    private fun createActionBtn(label: String, hexColor: String, widthPx: Int, onClick: () -> Unit): TextView {
        return TextView(service).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(7), dp(6), dp(7))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor(hexColor))
            }
            layoutParams = LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener { onClick() }
        }
    }

    private fun updateArmButtonUi(state: SchedulerState) {
        val btn = armButtonTv ?: return
        when (state) {
            SchedulerState.ARMED_WAITING -> {
                btn.text = "⏹ 取消待命"
                (btn.background as? GradientDrawable)?.setColor(Color.parseColor("#F43F5E"))
            }
            SchedulerState.FIRING -> {
                btn.text = "⏹ 停止连点"
                (btn.background as? GradientDrawable)?.setColor(Color.parseColor("#EA580C"))
            }
            SchedulerState.IDLE, SchedulerState.COMPLETED -> {
                val mode = activeConfig?.triggerMode ?: TriggerMode.SCHEDULED_AT_TIME
                btn.text = if (mode == TriggerMode.SCHEDULED_AT_TIME) "🎯 定时待命" else "▶ 立即连点"
                (btn.background as? GradientDrawable)?.setColor(Color.parseColor("#10B981"))
            }
        }
    }

    /**
     * 创建可自由拖拽的十字瞄准靶心视图
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun createTargetCrosshair(config: ScheduledClickConfig) {
        val sizePx = dp(56)
        val half = sizePx / 2
        val targetView = TargetCrosshairView(service, config)
        val lp = createOverlayLayoutParams(
            width = sizePx,
            height = sizePx,
            x = config.targetX - half,
            y = config.targetY - half
        )

        targetView.setOnTouchListener(object : View.OnTouchListener {
            private var initLpX = 0
            private var initLpY = 0
            private var downRawX = 0f
            private var downRawY = 0f
            private var dragged = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val state = AutoClickAccessibilityService.schedulerState.value
                if (state == SchedulerState.ARMED_WAITING || state == SchedulerState.FIRING) {
                    return false
                }
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initLpX = lp.x
                        initLpY = lp.y
                        downRawX = event.rawX
                        downRawY = event.rawY
                        dragged = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downRawX).roundToInt()
                        val dy = (event.rawY - downRawY).roundToInt()
                        if (hypot(dx.toDouble(), dy.toDouble()) > dp(4)) {
                            dragged = true
                        }
                        lp.x = initLpX + dx
                        lp.y = initLpY + dy
                        config.targetX = lp.x + half
                        config.targetY = lp.y + half
                        coordInfoTv?.text = "坐标: (${config.targetX}, ${config.targetY}) · 连发: ${config.burstCount}次/${config.intervalMs}ms"
                        runCatching { windowManager.updateViewLayout(targetView, lp) }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (dragged) {
                            repository.saveActiveConfig(config)
                        } else {
                            showQuickConfigDialog(config)
                        }
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(targetView, lp)
        targetCrosshairView = targetView
        targetParams = lp
    }

    /**
     * 在悬浮窗内直接弹出毫秒时间与精确坐标编辑器（无需切回主应用）
     */
    private fun showQuickConfigDialog(config: ScheduledClickConfig) {
        val container = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }

        fun addField(label: String, defaultVal: String, signed: Boolean = false): EditText {
            val tv = TextView(service).apply {
                text = label
                textSize = 12.5f
                setTextColor(Color.DKGRAY)
            }
            val et = EditText(service).apply {
                setText(defaultVal)
                inputType = if (signed) {
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                } else {
                    InputType.TYPE_CLASS_NUMBER
                }
                textSize = 14f
            }
            container.addView(tv)
            container.addView(et)
            return et
        }

        val hourEt = addField("触发小时 (0~23):", config.targetHour.toString())
        val minEt = addField("触发分钟 (0~59):", config.targetMinute.toString())
        val secEt = addField("触发秒数 (0~59):", config.targetSecond.toString())
        val msEt = addField("触发毫秒 (0~999，例如 0 或 950):", config.targetMillisecond.toString())
        val advEt = addField("提前触发补偿量 (毫秒，如 -25 表示提前25ms点击):", config.advanceOffsetMs.toString(), signed = true)
        val xEt = addField("点击 X 坐标 (px):", config.targetX.toString())
        val yEt = addField("点击 Y 坐标 (px):", config.targetY.toString())
        val burstEt = addField("到点连发点击次数 (1=单次，>1=连点抢购):", config.burstCount.toString())
        val intervalEt = addField("连发间隔 (毫秒，建议 25~60):", config.intervalMs.toString())

        val scroll = ScrollView(service).apply { addView(container) }

        val dialog = AlertDialog.Builder(service, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("定时毫秒时间与坐标设置")
            .setView(scroll)
            .setPositiveButton("保存并生效") { _, _ ->
                config.targetHour = hourEt.text.toString().toIntOrNull()?.coerceIn(0, 23) ?: config.targetHour
                config.targetMinute = minEt.text.toString().toIntOrNull()?.coerceIn(0, 59) ?: config.targetMinute
                config.targetSecond = secEt.text.toString().toIntOrNull()?.coerceIn(0, 59) ?: config.targetSecond
                config.targetMillisecond = msEt.text.toString().toIntOrNull()?.coerceIn(0, 999) ?: config.targetMillisecond
                config.advanceOffsetMs = advEt.text.toString().toLongOrNull()?.coerceIn(-5000L, 5000L) ?: config.advanceOffsetMs
                config.targetX = xEt.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: config.targetX
                config.targetY = yEt.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: config.targetY
                config.burstCount = burstEt.text.toString().toIntOrNull()?.coerceIn(1, 5000) ?: config.burstCount
                config.intervalMs = intervalEt.text.toString().toLongOrNull()?.coerceAtLeast(15L) ?: config.intervalMs

                repository.saveActiveConfig(config)
                repository.savePreset(config)

                // 更新准星悬浮窗位置
                val sizePx = dp(56)
                val half = sizePx / 2
                targetParams?.let { lp ->
                    lp.x = config.targetX - half
                    lp.y = config.targetY - half
                    targetCrosshairView?.let { v ->
                        runCatching { windowManager.updateViewLayout(v, lp) }
                        v.invalidate()
                    }
                }
                coordInfoTv?.text = "坐标: (${config.targetX}, ${config.targetY}) · 连发: ${config.burstCount}次/${config.intervalMs}ms"
                Toast.makeText(service, "已更新：${config.formatTargetTimeString()} @ (${config.targetX}, ${config.targetY})", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .create()

        dialog.window?.setType(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        )
        dialog.show()
    }

    private fun createOverlayLayoutParams(width: Int, height: Int, x: Int, y: Int): WindowManager.LayoutParams {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            width,
            height,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    /**
     * 精密十字瞄准靶心视图（待命时外圈变红锁定，编辑时为科技蓝）
     */
    private inner class TargetCrosshairView(
        context: Context,
        private val config: ScheduledClickConfig
    ) : View(context) {

        private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            color = Color.WHITE
        }
        private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1.5f.roundToInt().coerceAtLeast(1)).toFloat()
            color = Color.WHITE
        }
        private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#FDE047")
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = (width.coerceAtMost(height) / 2f) - dp(3)

            val state = AutoClickAccessibilityService.schedulerState.value
            outerPaint.color = when (state) {
                SchedulerState.ARMED_WAITING -> Color.parseColor("#B3E11D48")
                SchedulerState.FIRING -> Color.parseColor("#CCF97316")
                else -> Color.parseColor("#B32563EB")
            }

            canvas.drawCircle(cx, cy, radius, outerPaint)
            canvas.drawCircle(cx, cy, radius, ringPaint)

            // 十字准星线
            canvas.drawLine(cx - radius, cy, cx - dp(5), cy, crossPaint)
            canvas.drawLine(cx + dp(5), cy, cx + radius, cy, crossPaint)
            canvas.drawLine(cx, cy - radius, cx, cy - dp(5), crossPaint)
            canvas.drawLine(cx, cy + dp(5), cx, cy + radius, crossPaint)

            // 中心精确像素点
            canvas.drawCircle(cx, cy, dp(3).toFloat(), centerDotPaint)
        }
    }
}
