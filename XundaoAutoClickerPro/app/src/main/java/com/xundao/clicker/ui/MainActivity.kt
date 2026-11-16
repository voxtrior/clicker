package com.xundao.clicker.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.xundao.clicker.model.GlobalSettings
import com.xundao.clicker.model.ScriptConfig
import com.xundao.clicker.model.StopConditionType
import com.xundao.clicker.repository.ConfigRepository
import com.xundao.clicker.service.AutoClickAccessibilityService
import org.json.JSONObject

/**
 * 自动点击器高级版 (com.xundao.clicker) 主界面
 * 采用 Jetpack Compose Material 3 构建，包含权限自检、单点/多点模式启动、防检测参数配置与脚本管理。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = ConfigRepository(this)

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF2563EB),
                    secondary = Color(0xFF0D9488),
                    background = Color(0xFFF8FAFC),
                    surface = Color.White
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AutoClickerHomeScreen(repository = repository)
                }
            }
        }
    }
}

@Composable
fun AutoClickerHomeScreen(repository: ConfigRepository) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val isAccessibilityConnected by AutoClickAccessibilityService.isServiceConnected.collectAsState()
    val isEngineRunning by AutoClickAccessibilityService.isRunning.collectAsState()
    var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    var globalSettings by remember { mutableStateOf(repository.loadGlobalSettings()) }
    var savedScripts by remember { mutableStateOf(repository.loadSavedScripts()) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importJsonText by remember { mutableStateOf("") }

    // 每次从系统设置返回时刷新权限与已保存脚本列表
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canDrawOverlays = Settings.canDrawOverlays(context)
                savedScripts = repository.loadSavedScripts()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun checkPermissionsAndRun(block: (AutoClickAccessibilityService) -> Unit) {
        if (!Settings.canDrawOverlays(context)) {
            Toast.makeText(context, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            context.startActivity(intent)
            return
        }
        val service = AutoClickAccessibilityService.instance
        if (service == null || !isAccessibilityConnected) {
            Toast.makeText(context, "请在无障碍设置中开启【自动点击器高级版】", Toast.LENGTH_LONG).show()
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        block(service)
    }

    Scaffold(
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E293B))
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                Column {
                    Text(
                        text = "自动点击器高级版",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "包名: com.xundao.clicker · 免Root毫秒级连点 / 多点轨迹滑动 / 防检测随机偏移",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. 权限状态检测卡片
            item {
                PermissionStatusCard(
                    isAccessibilityReady = isAccessibilityConnected,
                    isOverlayReady = canDrawOverlays,
                    onOpenAccessibility = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onOpenOverlay = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    }
                )
            }

            // 2. 快速启动模式卡片（单点极速模式 vs 多点/滑动高级模式）
            item {
                QuickLaunchCard(
                    isRunning = isEngineRunning,
                    onStartSingleMode = {
                        checkPermissionsAndRun { service ->
                            val dm = context.resources.displayMetrics
                            val script = repository.createDefaultSingleScript(dm.widthPixels, dm.heightPixels)
                            service.showOverlayWithScript(script)
                            Toast.makeText(context, "单点悬浮窗已启动，拖动靶心至目标位置后点击【启动】", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onStartMultiMode = {
                        checkPermissionsAndRun { service ->
                            val dm = context.resources.displayMetrics
                            val script = repository.createDefaultMultiScript(dm.widthPixels, dm.heightPixels)
                            service.showOverlayWithScript(script)
                            Toast.makeText(context, "多点/滑动悬浮窗已启动，点击靶心可单独编辑参数", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onCloseOverlay = {
                        AutoClickAccessibilityService.instance?.closeOverlay()
                    }
                )
            }

            // 3. 全局参数与防检测高级设置
            item {
                GlobalSettingsCard(
                    settings = globalSettings,
                    onSave = { updated ->
                        globalSettings = updated
                        repository.saveGlobalSettings(updated)
                        Toast.makeText(context, "全局默认参数与防检测配置已保存", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            // 4. 脚本配置方案管理标题与导入按钮
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "已保存的脚本方案 (${savedScripts.size})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Color(0xFF0F172A)
                    )
                    OutlinedButton(onClick = { showImportDialog = true }) {
                        Text("导入 JSON 方案", fontSize = 12.sp)
                    }
                }
            }

            if (savedScripts.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "暂无保存的脚本。开启悬浮窗调整好点击靶心或滑动轨迹后，点击悬浮栏上的【💾 保存】即可存入此处。",
                            modifier = Modifier.padding(16.dp),
                            color = Color(0xFF64748B),
                            fontSize = 13.sp
                        )
                    }
                }
            } else {
                items(savedScripts, key = { it.id }) { script ->
                    SavedScriptItemCard(
                        script = script,
                        onLoad = {
                            checkPermissionsAndRun { service ->
                                service.showOverlayWithScript(script)
                                Toast.makeText(context, "已加载方案：${script.name}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onExport = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("script_json", script.toJson().toString(2)))
                            Toast.makeText(context, "方案 JSON 已复制到剪贴板", Toast.LENGTH_SHORT).show()
                        },
                        onDelete = {
                            repository.deleteScript(script.id)
                            savedScripts = repository.loadSavedScripts()
                        }
                    )
                }
            }
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("导入 JSON 脚本方案") },
            text = {
                OutlinedTextField(
                    value = importJsonText,
                    onValueChange = { importJsonText = it },
                    label = { Text("粘贴脚本 JSON 字符串") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                )
            },
            confirmButton = {
                Button(onClick = {
                    runCatching {
                        val parsed = ScriptConfig.fromJson(JSONObject(importJsonText.trim()))
                        repository.saveScript(parsed)
                        savedScripts = repository.loadSavedScripts()
                        importJsonText = ""
                        showImportDialog = false
                        Toast.makeText(context, "导入成功：${parsed.name}", Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(context, "JSON 格式无效，请检查后重试", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text("确认导入")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun PermissionStatusCard(
    isAccessibilityReady: Boolean,
    isOverlayReady: Boolean,
    onOpenAccessibility: () -> Unit,
    onOpenOverlay: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "核心权限状态",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = Color(0xFF0F172A)
            )
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isAccessibilityReady) "✅ 无障碍手势服务：已激活" else "❌ 无障碍手势服务：未开启",
                        fontWeight = FontWeight.SemiBold,
                        color = if (isAccessibilityReady) Color(0xFF059669) else Color(0xFFDC2626),
                        fontSize = 14.sp
                    )
                    Text(
                        text = "用于执行免Root屏幕点击与滑动手势",
                        color = Color(0xFF64748B),
                        fontSize = 12.sp
                    )
                }
                OutlinedButton(onClick = onOpenAccessibility) {
                    Text(if (isAccessibilityReady) "重新配置" else "去开启", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isOverlayReady) "✅ 悬浮窗控制面板权限：已授权" else "❌ 悬浮窗控制面板权限：未授权",
                        fontWeight = FontWeight.SemiBold,
                        color = if (isOverlayReady) Color(0xFF059669) else Color(0xFFDC2626),
                        fontSize = 14.sp
                    )
                    Text(
                        text = "用于在其它应用上方显示启动控制栏与瞄准靶心",
                        color = Color(0xFF64748B),
                        fontSize = 12.sp
                    )
                }
                OutlinedButton(onClick = onOpenOverlay) {
                    Text(if (isOverlayReady) "已授权" else "去授权", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun QuickLaunchCard(
    isRunning: Boolean,
    onStartSingleMode: () -> Unit,
    onStartMultiMode: () -> Unit,
    onCloseOverlay: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "启动控制面板",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = Color(0xFF0F172A)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "支持单点高频连点与多点顺序点击/滑动编排。轻触悬浮靶心可单独修改该点的延迟、按压时长与重复次数。",
                color = Color(0xFF64748B),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onStartSingleMode,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                ) {
                    Text("🎯 启动单点模式")
                }
                Button(
                    onClick = onStartMultiMode,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))
                ) {
                    Text("🔀 启动多点/滑动模式")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onCloseOverlay,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (isRunning) "⏹ 停止运行并关闭悬浮窗" else "关闭当前悬浮窗",
                    color = Color(0xFFEF4444)
                )
            }
        }
    }
}

@Composable
private fun GlobalSettingsCard(
    settings: GlobalSettings,
    onSave: (GlobalSettings) -> Unit
) {
    var intervalText by remember(settings) { mutableStateOf(settings.defaultIntervalMs.toString()) }
    var durationText by remember(settings) { mutableStateOf(settings.defaultDurationMs.toString()) }
    var swipeDurationText by remember(settings) { mutableStateOf(settings.defaultSwipeDurationMs.toString()) }
    var radiusText by remember(settings) { mutableStateOf(settings.defaultRandomRadiusPx.toString()) }
    var jitterText by remember(settings) { mutableStateOf(settings.defaultRandomJitterMs.toString()) }
    var antiDetect by remember(settings) { mutableStateOf(settings.antiDetectionEnabled) }
    var stopType by remember(settings) { mutableStateOf(settings.defaultStopCondition) }
    var stopValText by remember(settings) { mutableStateOf(settings.defaultStopValue.toString()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "高级参数与防检测设置",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = Color(0xFF0F172A)
            )
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = intervalText,
                    onValueChange = { intervalText = it },
                    label = { Text("默认点击间隔(ms)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = durationText,
                    onValueChange = { durationText = it },
                    label = { Text("默认按压时长(ms)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = swipeDurationText,
                    onValueChange = { swipeDurationText = it },
                    label = { Text("默认滑动时长(ms)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = radiusText,
                    onValueChange = { radiusText = it },
                    label = { Text("随机坐标偏移(px)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("启用随机坐标与随机间隔防检测", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    Text("模拟真人手指微抖动，避免固定坐标与固定周期被识别", color = Color(0xFF64748B), fontSize = 12.sp)
                }
                Switch(checked = antiDetect, onCheckedChange = { antiDetect = it })
            }

            if (antiDetect) {
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = jitterText,
                    onValueChange = { jitterText = it },
                    label = { Text("随机间隔时间抖动范围 (±ms)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text("默认停止条件", fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = stopType == StopConditionType.INFINITE,
                    onClick = { stopType = StopConditionType.INFINITE },
                    label = { Text("无限循环") }
                )
                FilterChip(
                    selected = stopType == StopConditionType.DURATION_SECONDS,
                    onClick = { stopType = StopConditionType.DURATION_SECONDS },
                    label = { Text("定时停止(秒)") }
                )
                FilterChip(
                    selected = stopType == StopConditionType.CYCLE_COUNT,
                    onClick = { stopType = StopConditionType.CYCLE_COUNT },
                    label = { Text("定次停止(轮)") }
                )
            }

            if (stopType != StopConditionType.INFINITE) {
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = stopValText,
                    onValueChange = { stopValText = it },
                    label = {
                        Text(if (stopType == StopConditionType.DURATION_SECONDS) "运行时长上限 (秒)" else "循环次数上限 (轮)")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    onSave(
                        GlobalSettings(
                            defaultIntervalMs = intervalText.toLongOrNull()?.coerceAtLeast(10L) ?: 100L,
                            defaultDurationMs = durationText.toLongOrNull()?.coerceAtLeast(10L) ?: 45L,
                            defaultSwipeDurationMs = swipeDurationText.toLongOrNull()?.coerceAtLeast(50L) ?: 320L,
                            defaultRandomRadiusPx = radiusText.toIntOrNull()?.coerceAtLeast(0) ?: 6,
                            defaultRandomJitterMs = jitterText.toLongOrNull()?.coerceAtLeast(0L) ?: 15L,
                            antiDetectionEnabled = antiDetect,
                            defaultStopCondition = stopType,
                            defaultStopValue = stopValText.toLongOrNull()?.coerceAtLeast(1L) ?: 60L
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("保存全局设置")
            }
        }
    }
}

@Composable
private fun SavedScriptItemCard(
    script: ScriptConfig,
    onLoad: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = script.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color(0xFF0F172A)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "模式: ${if (script.isMultiMode) "多点/滑动" else "单点"} · 节点数: ${script.nodes.size} · 防检测: ${if (script.antiDetectionEnabled) "开启" else "关闭"}",
                    color = Color(0xFF64748B),
                    fontSize = 12.sp
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onLoad) {
                    Text("启动", fontSize = 12.sp)
                }
                OutlinedButton(onClick = onExport) {
                    Text("导出", fontSize = 12.sp)
                }
                TextButton(onClick = onDelete) {
                    Text("删除", color = Color(0xFFEF4444), fontSize = 12.sp)
                }
            }
        }
    }
}
