package com.xundao.clicker.overlay

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
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
import com.xundao.clicker.model.ActionNode
import com.xundao.clicker.model.ActionType
import com.xundao.clicker.model.ScriptConfig
import com.xundao.clicker.repository.ConfigRepository
import com.xundao.clicker.service.AutoClickAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 悬浮控制面板与瞄准靶心管理器
 *
 * 包含：
 * 1. 可自由拖拽的悬浮控制条（启动/暂停、添加点击靶心、添加滑动轨迹、删除节点、保存配置、关闭面板）
 * 2. 点击/长按圆形准星视图 (ClickTargetView)：支持拖拽定位、轻触弹出单节点精细参数设置
 * 3. 滑动双锚点视图 (SwipeTargetView)：起点(S)与终点(E)独立悬浮锚点 + 实时方向连线图层
 */
class FloatingOverlayManager(
    private val service: AutoClickAccessibilityService,
    private val scope: CoroutineScope
) {
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val repository = ConfigRepository(service)
    private val density = service.resources.displayMetrics.density

    private var controlBarView: View? = null
    private var controlBarParams: WindowManager.LayoutParams? = null
    private var playPauseButton: TextView? = null
    private var cycleCounterText: TextView? = null

    private val nodeViews = mutableListOf<NodeOverlayHolder>()
    private var currentScript: ScriptConfig? = null

    private data class NodeOverlayHolder(
        val node: ActionNode,
        val views: List<View>,
        val paramsList: List<WindowManager.LayoutParams>
    )

    init {
        scope.launch {
            AutoClickAccessibilityService.isRunning.collectLatest { running ->
                updatePlayButtonUi(running)
            }
        }
        scope.launch {
            AutoClickAccessibilityService.executedCycles.collectLatest { cycles ->
                cycleCounterText?.text = "循环: $cycles"
            }
        }
    }

    fun show(script: ScriptConfig) {
        dismissAll()
        currentScript = script
        createControlBar(script)
        script.nodes.forEach { node ->
            addNodeOverlay(node)
        }
    }

    fun dismissAll() {
        clearAllNodeOverlays()
        controlBarView?.let {
            runCatching { windowManager.removeView(it) }
        }
        controlBarView = null
        controlBarParams = null
        playPauseButton = null
        cycleCounterText = null
    }

    /**
     * 当自动点击引擎启动时，将所有靶心窗口设为 FLAG_NOT_TOUCHABLE，
     * 防止无障碍手势点击在悬浮靶心自身视图上而无法穿透到底层游戏/应用。
     */
    fun setTouchPassthrough(passthrough: Boolean) {
        nodeViews.forEach { holder ->
            holder.views.zip(holder.paramsList).forEach { (view, lp) ->
                if (passthrough) {
                    lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    view.alpha = 0.55f
                } else {
                    lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    view.alpha = 1.0f
                }
                runCatching { windowManager.updateViewLayout(view, lp) }
            }
        }
    }

    private fun clearAllNodeOverlays() {
        nodeViews.forEach { holder ->
            holder.views.forEach { v ->
                runCatching { windowManager.removeView(v) }
            }
        }
        nodeViews.clear()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createControlBar(script: ScriptConfig) {
        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#DD1E293B"))
                setStroke(dp(1), Color.parseColor("#6694A3B8"))
            }
        }

        // 顶部拖拽手柄与循环计数
        val dragHeader = TextView(service).apply {
            text = "⠿ 拖动"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
        }
        root.addView(dragHeader)

        val cycleTv = TextView(service).apply {
            text = "循环: 0"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6))
        }
        cycleCounterText = cycleTv
        root.addView(cycleTv)

        // 启动 / 暂停按钮
        val playBtn = createBarButton("▶ 启动", "#10B981") {
            service.toggleEngine()
        }
        playPauseButton = playBtn
        root.addView(playBtn)

        // 多点模式下提供动态增删点击点与滑动轨迹按钮
        if (script.isMultiMode) {
            root.addView(createBarButton("＋ 点击", "#3B82F6") {
                if (AutoClickAccessibilityService.isRunning.value) {
                    Toast.makeText(service, "请先暂停后再添加靶心", Toast.LENGTH_SHORT).show()
                    return@createBarButton
                }
                val nextId = (script.nodes.maxOfOrNull { it.id } ?: 0) + 1
                val metrics = service.resources.displayMetrics
                val settings = repository.loadGlobalSettings()
                val offsetStep = ((nextId - 1) % 6) * dp(28)
                val newNode = ActionNode(
                    id = nextId,
                    type = ActionType.CLICK,
                    x = (metrics.widthPixels / 2 + offsetStep).coerceIn(dp(40), metrics.widthPixels - dp(40)),
                    y = (metrics.heightPixels / 2 + offsetStep).coerceIn(dp(60), metrics.heightPixels - dp(60)),
                    intervalMs = settings.defaultIntervalMs,
                    durationMs = settings.defaultDurationMs,
                    randomOffsetRadiusPx = settings.defaultRandomRadiusPx,
                    randomIntervalJitterMs = settings.defaultRandomJitterMs
                )
                script.nodes.add(newNode)
                addNodeOverlay(newNode)
            })

            root.addView(createBarButton("↗ 滑动", "#8B5CF6") {
                if (AutoClickAccessibilityService.isRunning.value) {
                    Toast.makeText(service, "请先暂停后再添加滑动轨迹", Toast.LENGTH_SHORT).show()
                    return@createBarButton
                }
                val nextId = (script.nodes.maxOfOrNull { it.id } ?: 0) + 1
                val metrics = service.resources.displayMetrics
                val settings = repository.loadGlobalSettings()
                val newNode = ActionNode(
                    id = nextId,
                    type = ActionType.SWIPE,
                    x = metrics.widthPixels / 2,
                    y = (metrics.heightPixels * 0.65f).roundToInt(),
                    endX = metrics.widthPixels / 2,
                    endY = (metrics.heightPixels * 0.30f).roundToInt(),
                    intervalMs = settings.defaultIntervalMs,
                    durationMs = settings.defaultSwipeDurationMs,
                    randomOffsetRadiusPx = settings.defaultRandomRadiusPx,
                    randomIntervalJitterMs = settings.defaultRandomJitterMs
                )
                script.nodes.add(newNode)
                addNodeOverlay(newNode)
            })

            root.addView(createBarButton("－ 移除", "#F59E0B") {
                if (AutoClickAccessibilityService.isRunning.value) {
                    Toast.makeText(service, "请先暂停后再移除节点", Toast.LENGTH_SHORT).show()
                    return@createBarButton
                }
                if (script.nodes.size > 1) {
                    script.nodes.removeLastOrNull()
                    val lastHolder = nodeViews.removeLastOrNull()
                    lastHolder?.views?.forEach { v -> runCatching { windowManager.removeView(v) } }
                } else {
                    Toast.makeText(service, "至少保留 1 个操作节点", Toast.LENGTH_SHORT).show()
                }
            })
        }

        // 保存当前方案按钮
        root.addView(createBarButton("💾 保存", "#0EA5E9") {
            repository.saveScript(script)
            Toast.makeText(service, "已保存方案：${script.name}", Toast.LENGTH_SHORT).show()
        })

        // 关闭悬浮窗按钮
        root.addView(createBarButton("✖ 关闭", "#EF4444") {
            service.closeOverlay()
        })

        val lp = createOverlayLayoutParams(
            width = WindowManager.LayoutParams.WRAP_CONTENT,
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            x = dp(12),
            y = dp(180)
        )

        // 支持按住控制栏任意空白或手柄拖拽移动
        val dragListener = object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var downRawX = 0f
            private var downRawY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = lp.x
                        initialY = lp.y
                        downRawX = event.rawX
                        downRawY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = initialX + (event.rawX - downRawX).roundToInt()
                        lp.y = initialY + (event.rawY - downRawY).roundToInt()
                        runCatching { windowManager.updateViewLayout(root, lp) }
                        return true
                    }
                }
                return false
            }
        }
        dragHeader.setOnTouchListener(dragListener)
        root.setOnTouchListener(dragListener)

        windowManager.addView(root, lp)
        controlBarView = root
        controlBarParams = lp
    }

    private fun createBarButton(label: String, hexColor: String, onClick: () -> Unit): TextView {
        return TextView(service).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor(hexColor))
            }
            val lp = LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun updatePlayButtonUi(running: Boolean) {
        val btn = playPauseButton ?: return
        if (running) {
            btn.text = "⏸ 暂停"
            (btn.background as? GradientDrawable)?.setColor(Color.parseColor("#F43F5E"))
        } else {
            btn.text = "▶ 启动"
            (btn.background as? GradientDrawable)?.setColor(Color.parseColor("#10B981"))
        }
    }

    private fun addNodeOverlay(node: ActionNode) {
        if (node.type == ActionType.SWIPE) {
            addSwipeNodeOverlay(node)
        } else {
            addClickNodeOverlay(node)
        }
    }

    /**
     * 添加单击/长按准星靶心悬浮窗
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun addClickNodeOverlay(node: ActionNode) {
        val sizePx = dp(48)
        val half = sizePx / 2
        val targetView = ClickTargetView(service, node)
        val lp = createOverlayLayoutParams(
            width = sizePx,
            height = sizePx,
            x = node.x - half,
            y = node.y - half
        )

        targetView.setOnTouchListener(object : View.OnTouchListener {
            private var startLpX = 0
            private var startLpY = 0
            private var downRawX = 0f
            private var downRawY = 0f
            private var moved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startLpX = lp.x
                        startLpY = lp.y
                        downRawX = event.rawX
                        downRawY = event.rawY
                        moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downRawX).roundToInt()
                        val dy = (event.rawY - downRawY).roundToInt()
                        if (hypot(dx.toDouble(), dy.toDouble()) > dp(4)) {
                            moved = true
                        }
                        lp.x = startLpX + dx
                        lp.y = startLpY + dy
                        node.x = lp.x + half
                        node.y = lp.y + half
                        runCatching { windowManager.updateViewLayout(targetView, lp) }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved && !AutoClickAccessibilityService.isRunning.value) {
                            showNodeEditDialog(node) {
                                targetView.invalidate()
                            }
                        }
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(targetView, lp)
        nodeViews.add(NodeOverlayHolder(node, listOf(targetView), listOf(lp)))
    }

    /**
     * 添加滑动双锚点与轨迹连线悬浮窗
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun addSwipeNodeOverlay(node: ActionNode) {
        val anchorSize = dp(44)
        val half = anchorSize / 2

        // 全屏透明轨迹画布（始终设置 FLAG_NOT_TOUCHABLE，仅用于绘制起点->终点箭头连线）
        val lineCanvasView = SwipeLineCanvasView(service, node)
        val lineParams = createOverlayLayoutParams(
            width = WindowManager.LayoutParams.MATCH_PARENT,
            height = WindowManager.LayoutParams.MATCH_PARENT,
            x = 0,
            y = 0
        ).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

        val startAnchor = SwipeAnchorView(service, node, isStart = true)
        val startLp = createOverlayLayoutParams(
            width = anchorSize,
            height = anchorSize,
            x = node.x - half,
            y = node.y - half
        )

        val endAnchor = SwipeAnchorView(service, node, isStart = false)
        val endLp = createOverlayLayoutParams(
            width = anchorSize,
            height = anchorSize,
            x = node.endX - half,
            y = node.endY - half
        )

        fun bindAnchorTouch(anchorView: View, lp: WindowManager.LayoutParams, isStart: Boolean) {
            anchorView.setOnTouchListener(object : View.OnTouchListener {
                private var initX = 0
                private var initY = 0
                private var downX = 0f
                private var downY = 0f
                private var moved = false

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            initX = lp.x
                            initY = lp.y
                            downX = event.rawX
                            downY = event.rawY
                            moved = false
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (event.rawX - downX).roundToInt()
                            val dy = (event.rawY - downY).roundToInt()
                            if (hypot(dx.toDouble(), dy.toDouble()) > dp(4)) moved = true
                            lp.x = initX + dx
                            lp.y = initY + dy
                            if (isStart) {
                                node.x = lp.x + half
                                node.y = lp.y + half
                            } else {
                                node.endX = lp.x + half
                                node.endY = lp.y + half
                            }
                            runCatching { windowManager.updateViewLayout(anchorView, lp) }
                            lineCanvasView.invalidate()
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!moved && !AutoClickAccessibilityService.isRunning.value) {
                                showNodeEditDialog(node) {
                                    startAnchor.invalidate()
                                    endAnchor.invalidate()
                                    lineCanvasView.invalidate()
                                }
                            }
                            return true
                        }
                    }
                    return false
                }
            })
        }

        bindAnchorTouch(startAnchor, startLp, isStart = true)
        bindAnchorTouch(endAnchor, endLp, isStart = false)

        windowManager.addView(lineCanvasView, lineParams)
        windowManager.addView(startAnchor, startLp)
        windowManager.addView(endAnchor, endLp)

        nodeViews.add(
            NodeOverlayHolder(
                node = node,
                views = listOf(lineCanvasView, startAnchor, endAnchor),
                paramsList = listOf(lineParams, startLp, endLp)
            )
        )
    }

    /**
     * 轻触靶心弹出单节点参数配置框（可设置间隔、按压/滑动时长、单点重复次数、防检测随机偏移半径）
     */
    private fun showNodeEditDialog(node: ActionNode, onUpdated: () -> Unit) {
        val container = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }

        fun addNumberField(label: String, initialValue: String): EditText {
            val tv = TextView(service).apply {
                text = label
                textSize = 13f
                setTextColor(Color.DKGRAY)
            }
            val et = EditText(service).apply {
                setText(initialValue)
                inputType = InputType.TYPE_CLASS_NUMBER
                textSize = 14f
            }
            container.addView(tv)
            container.addView(et)
            return et
        }

        val intervalEt = addNumberField("执行后等待间隔 (毫秒，建议 ≥20):", node.intervalMs.toString())
        val durationLabel = if (node.type == ActionType.SWIPE) "滑动持续时长 (毫秒，建议 150~1000):" else "按压持续时长 (毫秒，单击建议 40，长按 ≥500):"
        val durationEt = addNumberField(durationLabel, node.durationMs.toString())
        val repeatEt = addNumberField("单轮循环内连续执行次数:", node.repeatCount.toString())
        val radiusEt = addNumberField("防检测随机坐标偏移半径 (px):", node.randomOffsetRadiusPx.toString())
        val jitterEt = addNumberField("防检测随机间隔抖动 (毫秒):", node.randomIntervalJitterMs.toString())

        val scrollView = ScrollView(service).apply { addView(container) }

        val dialog = AlertDialog.Builder(service, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("节点 #${node.id} (${node.type.name}) 参数设置")
            .setView(scrollView)
            .setPositiveButton("保存") { _, _ ->
                node.intervalMs = intervalEt.text.toString().toLongOrNull()?.coerceAtLeast(10L) ?: node.intervalMs
                node.durationMs = durationEt.text.toString().toLongOrNull()?.coerceAtLeast(10L) ?: node.durationMs
                node.repeatCount = repeatEt.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: node.repeatCount
                node.randomOffsetRadiusPx = radiusEt.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: node.randomOffsetRadiusPx
                node.randomIntervalJitterMs = jitterEt.text.toString().toLongOrNull()?.coerceAtLeast(0L) ?: node.randomIntervalJitterMs
                onUpdated()
            }
            .setNeutralButton(
                if (node.type == ActionType.CLICK) "切换为长按" else "切换为单击"
            ) { _, _ ->
                if (node.type != ActionType.SWIPE) {
                    if (node.type == ActionType.CLICK) {
                        node.type = ActionType.LONG_PRESS
                        node.durationMs = 650L
                    } else {
                        node.type = ActionType.CLICK
                        node.durationMs = 45L
                    }
                    onUpdated()
                }
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
     * 自定义点击/长按圆形准星 View
     */
    private inner class ClickTargetView(
        context: Context,
        private val node: ActionNode
    ) : View(context) {

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            color = Color.WHITE
        }
        private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
            color = Color.parseColor("#AAFFFFFF")
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = dp(14).toFloat()
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = (width.coerceAtMost(height) / 2f) - dp(3)

            fillPaint.color = if (node.type == ActionType.LONG_PRESS) {
                Color.parseColor("#CCEA580C")
            } else {
                Color.parseColor("#CC2563EB")
            }

            canvas.drawCircle(cx, cy, radius, fillPaint)
            canvas.drawCircle(cx, cy, radius, strokePaint)

            // 绘制准星十字刻度线
            canvas.drawLine(cx - radius, cy, cx - radius * 0.45f, cy, crossPaint)
            canvas.drawLine(cx + radius * 0.45f, cy, cx + radius, cy, crossPaint)
            canvas.drawLine(cx, cy - radius, cx, cy - radius * 0.45f, crossPaint)
            canvas.drawLine(cx, cy + radius * 0.45f, cx, cy + radius, crossPaint)

            val label = if (node.type == ActionType.LONG_PRESS) "${node.id}L" else "${node.id}"
            val textOffset = (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(label, cx, cy - textOffset, textPaint)
        }
    }

    /**
     * 自定义滑动起点(S)与终点(E)锚点 View
     */
    private inner class SwipeAnchorView(
        context: Context,
        private val node: ActionNode,
        private val isStart: Boolean
    ) : View(context) {

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = if (isStart) Color.parseColor("#CC7C3AED") else Color.parseColor("#CC0D9488")
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            color = Color.WHITE
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = dp(12).toFloat()
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val r = (width.coerceAtMost(height) / 2f) - dp(3)
            canvas.drawCircle(cx, cy, r, fillPaint)
            canvas.drawCircle(cx, cy, r, strokePaint)

            val label = if (isStart) "${node.id}起" else "${node.id}终"
            val offset = (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(label, cx, cy - offset, textPaint)
        }
    }

    /**
     * 滑动轨迹箭头连线透明画布 View
     */
    private inner class SwipeLineCanvasView(
        context: Context,
        private val node: ActionNode
    ) : View(context) {

        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(3).toFloat()
            color = Color.parseColor("#CC8B5CF6")
            pathEffect = DashPathEffect(floatArrayOf(dp(8).toFloat(), dp(5).toFloat()), 0f)
        }

        private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = dp(2).toFloat()
            color = Color.parseColor("#DD0D9488")
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val sx = node.x.toFloat()
            val sy = node.y.toFloat()
            val ex = node.endX.toFloat()
            val ey = node.endY.toFloat()

            canvas.drawLine(sx, sy, ex, ey, linePaint)

            // 在轨迹中点绘制方向箭头
            val mx = (sx + ex) / 2f
            val my = (sy + ey) / 2f
            val angle = atan2((ey - sy).toDouble(), (ex - sx).toDouble())
            val arrowLen = dp(10).toFloat()
            val wingAngle = Math.toRadians(28.0)

            val p1 = PointF(
                (mx - arrowLen * cos(angle - wingAngle)).toFloat(),
                (my - arrowLen * sin(angle - wingAngle)).toFloat()
            )
            val p2 = PointF(
                (mx - arrowLen * cos(angle + wingAngle)).toFloat(),
                (my - arrowLen * sin(angle + wingAngle)).toFloat()
            )
            canvas.drawLine(mx, my, p1.x, p1.y, arrowPaint)
            canvas.drawLine(mx, my, p2.x, p2.y, arrowPaint)
        }
    }
}
