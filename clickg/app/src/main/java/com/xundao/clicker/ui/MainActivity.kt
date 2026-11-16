package com.xundao.clicker.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.xundao.clicker.model.ScheduledClickConfig
import com.xundao.clicker.model.TimeServerSource
import com.xundao.clicker.repository.ConfigRepository
import com.xundao.clicker.service.AutoClickAccessibilityService
import com.xundao.clicker.time.TimeSyncEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 自动点击器高级版 (com.xundao.clicker) - 毫秒授时与定点定时点击主界面
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
                    background = Color(0xFFF1F5F9),
                    surface = Color.White
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TimeSyncedClickerScreen(repository = repository)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimeSyncedClickerScreen(repository: ConfigRepository) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    val dm = context.resources.displayMetrics
    var activeConfig by remember {
        mutableStateOf(repository.loadActiveConfig(dm.widthPixels, dm.heightPixels))
    }
    var savedPresets by remember { mutableStateOf(repository.loadSavedPresets()) }

    val isAccessibilityReady by AutoClickAccessibilityService.isServiceConnected.collectAsState()
    var isOverlayReady by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    val syncResult by TimeSyncEngine.lastSyncResult.collectAsState()
    val isSyncing by TimeSyncEngine.isSyncing.collectAsState()
    var liveServerEpochMs by remember { mutableLongStateOf(TimeSyncEngine.nowServerMillis()) }

    // 表单状态
    var selectedSource by remember(activeConfig) { mutableStateOf(activeConfig.timeSource) }
    var targetHourText by remember(activeConfig) { mutableStateOf(activeConfig.targetHour.toString()) }
    var targetMinText by remember(activeConfig) { mutableStateOf(activeConfig.targetMinute.toString()) }
    var targetSecText by remember(activeConfig) { mutableStateOf(activeConfig.targetSecond.toString()) }
    var targetMsText by remember(activeConfig) { mutableStateOf(activeConfig.targetMillisecond.toString()) }
    var advanceMsText by remember(activeConfig) { mutableStateOf(activeConfig.advanceOffsetMs.toString()) }
    var targetXText by remember(activeConfig) { mutableStateOf(activeConfig.targetX.toString()) }
    var targetYText by remember(activeConfig) { mutableStateOf(activeConfig.targetY.toString()) }
    var burstCountText by remember(activeConfig) { mutableStateOf(activeConfig.burstCount.toString()) }
    var intervalMsText by remember(activeConfig) { mutableStateOf(activeConfig.intervalMs.toString()) }
    var pressDurationText by remember(activeConfig) { mutableStateOf(activeConfig.pressDurationMs.toString()) }
    var randomRadiusText by remember(activeConfig) { mutableStateOf(activeConfig.randomRadiusPx.toString()) }

    // 首次进入自动同步当前选中的时间源
    LaunchedEffect(Unit) {
        TimeSyncEngine.syncWithServer(selectedSource, sampleCount = 4)
    }

    // 60fps 刷新主界面毫秒时钟
    LaunchedEffect(Unit) {
        while (isActive) {
            liveServerEpochMs = TimeSyncEngine.nowServerMillis()
            delay(16L)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isOverlayReady = Settings.canDrawOverlays(context)
                activeConfig = repository.loadActiveConfig(dm.widthPixels, dm.heightPixels)
                savedPresets = repository.loadSavedPresets()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun buildConfigFromInputs(): ScheduledClickConfig {
        return activeConfig.copy(
            name = "${selectedSource.displayName.substringBefore(" ")} ${
                String.format(
                    "%02d:%02d:%02d.%03d",
                    targetHourText.toIntOrNull()?.coerceIn(0, 23) ?: 20,
                    targetMinText.toIntOrNull()?.coerceIn(0, 59) ?: 0,
                    targetSecText.toIntOrNull()?.coerceIn(0, 59) ?: 0,
                    targetMsText.toIntOrNull()?.coerceIn(0, 999) ?: 0
                )
            }",
            timeSource = selectedSource,
            targetHour = targetHourText.toIntOrNull()?.coerceIn(0, 23) ?: 20,
            targetMinute = targetMinText.toIntOrNull()?.coerceIn(0, 59) ?: 0,
            targetSecond = targetSecText.toIntOrNull()?.coerceIn(0, 59) ?: 0,
            targetMillisecond = targetMsText.toIntOrNull()?.coerceIn(0, 999) ?: 0,
            advanceOffsetMs = advanceMsText.toLongOrNull()?.coerceIn(-5000L, 5000L) ?: 0L,
            targetX = targetXText.toIntOrNull()?.coerceIn(1, dm.widthPixels) ?: (dm.widthPixels / 2),
            targetY = targetYText.toIntOrNull()?.coerceIn(1, dm.heightPixels) ?: (dm.heightPixels / 2),
            burstCount = burstCountText.toIntOrNull()?.coerceIn(1, 5000) ?: 10,
            intervalMs = intervalMsText.toLongOrNull()?.coerceAtLeast(15L) ?: 35L,
            pressDurationMs = pressDurationText.toLongOrNull()?.coerceIn(10L, 10_000L) ?: 28L,
            randomRadiusPx = randomRadiusText.toIntOrNull()?.coerceIn(0, 200) ?: 2
        )
    }

    Scaffold(
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A))
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Column {
                    Text(
                        text = "自动点击器高级版 · 毫秒授时定点版",
                        color = Color.White,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "com.xundao.clicker · 支持京东/阿里/NTP多源毫秒校准与亚毫秒定时坐标点击",
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
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. 实时毫秒服务器时钟看板与时间源选择卡片
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "当前授时源：${selectedSource.displayName}",
                                color = Color(0xFF38BDF8),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        TimeSyncEngine.syncWithServer(selectedSource, sampleCount = 4)
                                    }
                                },
                                enabled = !isSyncing,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                            ) {
                                Text(if (isSyncing) "校准中..." else "🔄 立即毫秒校准", fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = ScheduledClickConfig.formatEpochFullMs(liveServerEpochMs),
                            color = Color.White,
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = syncResult.message,
                            color = if (syncResult.success) Color(0xFF34D399) else Color(0xFFFBBF24),
                            fontSize = 12.sp
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "切换时间服务器（点击立即同步）：",
                            color = Color(0xFFCBD5E1),
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            TimeServerSource.values().forEach { src ->
                                val isSelected = src == selectedSource
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        selectedSource = src
                                        coroutineScope.launch {
                                            TimeSyncEngine.syncWithServer(src, sampleCount = 4)
                                        }
                                    },
                                    label = {
                                        Text(
                                            text = src.displayName.substringBefore(" "),
                                            fontSize = 12.sp,
                                            color = if (isSelected) Color(0xFF0F172A) else Color(0xFF94A3B8)
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 2. 权限状态检查卡片
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("运行权限自检", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isAccessibilityReady) "✅ 无障碍点击服务：已就绪" else "❌ 无障碍点击服务：未开启",
                                color = if (isAccessibilityReady) Color(0xFF059669) else Color(0xFFDC2626),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.5.sp
                            )
                            OutlinedButton(onClick = {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }) {
                                Text(if (isAccessibilityReady) "已开启" else "去开启", fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isOverlayReady) "✅ 悬浮时钟与坐标靶心：已授权" else "❌ 悬浮时钟与坐标靶心：未授权",
                                color = if (isOverlayReady) Color(0xFF059669) else Color(0xFFDC2626),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.5.sp
                            )
                            OutlinedButton(onClick = {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            }) {
                                Text(if (isOverlayReady) "已授权" else "去授权", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // 3. 定时触发时刻 (HH:mm:ss.SSS) 与目标坐标 (X, Y) 设定卡片
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("设定触发时间 (精确到毫秒)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            TextButton(onClick = {
                                // 快捷设为当前服务器时间 + 1 分钟整点，方便立刻测试
                                val cal = Calendar.getInstance().apply {
                                    timeInMillis = TimeSyncEngine.nowServerMillis() + 60_000L
                                }
                                targetHourText = cal.get(Calendar.HOUR_OF_DAY).toString()
                                targetMinText = cal.get(Calendar.MINUTE).toString()
                                targetSecText = "0"
                                targetMsText = "0"
                            }) {
                                Text("快捷设为下一分钟整点", fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = targetHourText,
                                onValueChange = { targetHourText = it },
                                label = { Text("时(0-23)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = targetMinText,
                                onValueChange = { targetMinText = it },
                                label = { Text("分(0-59)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = targetSecText,
                                onValueChange = { targetSecText = it },
                                label = { Text("秒(0-59)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = targetMsText,
                                onValueChange = { targetMsText = it },
                                label = { Text("毫秒(0-999)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1.2f),
                                singleLine = true
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = advanceMsText,
                            onValueChange = { advanceMsText = it },
                            label = { Text("提前触发补偿量 (毫秒，例如填 -25 表示提前25毫秒发出点击)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(14.dp))
                        Text("目标点击坐标 (X, Y) 与连发参数", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(
                            text = "提示：既可在此直接输入像素坐标，也可启动悬浮窗后直接用手指拖动准星定位",
                            color = Color(0xFF64748B),
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = targetXText,
                                onValueChange = { targetXText = it },
                                label = { Text("X 坐标 (px)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = targetYText,
                                onValueChange = { targetYText = it },
                                label = { Text("Y 坐标 (px)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = burstCountText,
                                onValueChange = { burstCountText = it },
                                label = { Text("连发次数(1=单次)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = intervalMsText,
                                onValueChange = { intervalMsText = it },
                                label = { Text("连发间隔(ms)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = pressDurationText,
                                onValueChange = { pressDurationText = it },
                                label = { Text("按压时长(ms)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = randomRadiusText,
                            onValueChange = { randomRadiusText = it },
                            label = { Text("防检测随机坐标微偏移半径 (px，填 0 为绝对固定点)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (!Settings.canDrawOverlays(context)) {
                                        Toast.makeText(context, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
                                        context.startActivity(
                                            Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:${context.packageName}")
                                            )
                                        )
                                        return@Button
                                    }
                                    val service = AutoClickAccessibilityService.instance
                                    if (service == null || !isAccessibilityReady) {
                                        Toast.makeText(context, "请先开启【自动点击器高级版】无障碍服务", Toast.LENGTH_LONG).show()
                                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                        return@Button
                                    }
                                    val cfg = buildConfigFromInputs()
                                    activeConfig = cfg
                                    repository.saveActiveConfig(cfg)
                                    repository.savePreset(cfg)
                                    savedPresets = repository.loadSavedPresets()

                                    service.showOverlay(cfg)
                                    Toast.makeText(
                                        context,
                                        "毫秒悬浮窗已开启！将准星拖到目标按钮上，点击【🎯 定时待命】即可",
                                        Toast.LENGTH_LONG
                                    ).show()
                                },
                                modifier = Modifier.weight(1.4f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                            ) {
                                Text("🚀 启动毫秒悬浮窗与准星", fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = {
                                    val cfg = buildConfigFromInputs()
                                    activeConfig = cfg
                                    repository.saveActiveConfig(cfg)
                                    repository.savePreset(cfg)
                                    savedPresets = repository.loadSavedPresets()
                                    Toast.makeText(context, "已保存至方案列表", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("💾 保存方案")
                            }
                        }
                    }
                }
            }

            // 4. 已保存的定时定点方案列表
            if (savedPresets.isNotEmpty()) {
                item {
                    Text(
                        text = "已保存的定时方案 (${savedPresets.size})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color(0xFF0F172A)
                    )
                }
                items(savedPresets, key = { it.id }) { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                activeConfig = item
                            },
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(
                                    text = "坐标: (${item.targetX}, ${item.targetY}) · 提前量: ${item.advanceOffsetMs}ms · 连发: ${item.burstCount}次",
                                    color = Color(0xFF64748B),
                                    fontSize = 12.sp
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(onClick = {
                                    activeConfig = item
                                    repository.saveActiveConfig(item)
                                    AutoClickAccessibilityService.instance?.showOverlay(item)
                                }) {
                                    Text("加载", fontSize = 12.sp)
                                }
                                TextButton(onClick = {
                                    repository.deletePreset(item.id)
                                    savedPresets = repository.loadSavedPresets()
                                }) {
                                    Text("删除", color = Color(0xFFEF4444), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
