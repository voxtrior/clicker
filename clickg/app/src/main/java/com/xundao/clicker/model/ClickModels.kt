package com.xundao.clicker.model

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 预置时间服务器枚举（涵盖京东、阿里/淘宝、阿里云NTP、腾讯云NTP、中科院国家授时中心、拼多多、美团、苏宁、小米及本地时钟）
 */
enum class TimeServerSource(
    val displayName: String,
    val subtitle: String,
    val protocol: String,
    val endpoint: String
) {
    JD_API(
        displayName = "京东服务器时间 (JD)",
        subtitle = "api.m.jd.com 毫秒级授时校准",
        protocol = "HTTPS_JD",
        endpoint = "https://api.m.jd.com"
    ),
    TAOBAO_API(
        displayName = "阿里/淘宝服务器时间 (Taobao)",
        subtitle = "acs.m.taobao.com 官方毫秒时间戳接口",
        protocol = "HTTPS_TAOBAO",
        endpoint = "https://acs.m.taobao.com/gw/mtop.common.getTimestamp/"
    ),
    ALIYUN_NTP(
        displayName = "阿里云 NTP 授时中心",
        subtitle = "ntp.aliyun.com (UDP 123 亚毫秒级 SNTP)",
        protocol = "SNTP",
        endpoint = "ntp.aliyun.com"
    ),
    TENCENT_NTP(
        displayName = "腾讯云 NTP 授时中心",
        subtitle = "ntp.tencent.com (UDP 123 亚毫秒级 SNTP)",
        protocol = "SNTP",
        endpoint = "ntp.tencent.com"
    ),
    NTSC_NTP(
        displayName = "中科院国家授时中心 (NTSC)",
        subtitle = "ntp.ntsc.ac.cn (UDP 123 国家标准北京时间)",
        protocol = "SNTP",
        endpoint = "ntp.ntsc.ac.cn"
    ),
    PDD_API(
        displayName = "拼多多服务器时间 (PDD)",
        subtitle = "api.pinduoduo.com 毫秒时间戳接口",
        protocol = "HTTPS_PDD",
        endpoint = "https://api.pinduoduo.com/api/server/_stm"
    ),
    MEITUAN_API(
        displayName = "美团服务器时间 (Meituan)",
        subtitle = "cube.meituan.com 毫秒时间戳接口",
        protocol = "HTTPS_MEITUAN",
        endpoint = "https://cube.meituan.com/ipromotion/cube/toc/component/base/getServerCurrentTime"
    ),
    SUNING_API(
        displayName = "苏宁易购服务器时间 (Suning)",
        subtitle = "quan.suning.com 实时系统授时接口",
        protocol = "HTTPS_SUNING",
        endpoint = "https://quan.suning.com/getSysTime.do"
    ),
    XIAOMI_API(
        displayName = "小米商城服务器时间 (Xiaomi)",
        subtitle = "tp.hd.xiaomi.com 毫秒时间戳接口",
        protocol = "HTTPS_XIAOMI",
        endpoint = "https://tp.hd.xiaomi.com/gettimestamp"
    ),
    LOCAL_SYSTEM(
        displayName = "本机系统时钟 (离线免同步)",
        subtitle = "直接使用手机当前系统毫秒时间",
        protocol = "LOCAL",
        endpoint = "localhost"
    )
}

/**
 * 触发执行模式：
 * 1. SCHEDULED_AT_TIME: 到达设定时间 (HH:mm:ss.SSS) 准时触发点击（支持单次精准点或突发连发）
 * 2. CONTINUOUS_LOOP: 传统手动启动连续循环点击
 */
enum class TriggerMode {
    SCHEDULED_AT_TIME,
    CONTINUOUS_LOOP
}

/**
 * 定时任务运行状态
 */
enum class SchedulerState {
    IDLE,           // 未启动 / 编辑坐标状态（靶心可拖拽）
    ARMED_WAITING,  // 定时待命倒计时中（靶心已锁定并开启触摸穿透，等待毫秒时刻到来）
    FIRING,         // 正在执行毫秒级连发点击
    COMPLETED       // 本轮定时点击已完成
}

/**
 * 时间同步测量结果
 */
data class TimeSyncResult(
    val source: TimeServerSource,
    val success: Boolean,
    val clockOffsetMs: Long,     // 服务器真实时间 - 本机 System.currentTimeMillis()
    val rttMs: Long,             // 最优采样往返网络延迟 (RTT)
    val syncedElapsedRealtime: Long, // 同步完成时刻的 SystemClock.elapsedRealtime()
    val syncedServerEpochMs: Long,   // 同步完成时刻对应的服务器真实毫秒时间戳
    val message: String
)

/**
 * 点击坐标与触发配置模型
 */
data class ScheduledClickConfig(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "定点定时点击方案",
    var timeSource: TimeServerSource = TimeServerSource.JD_API,
    var triggerMode: TriggerMode = TriggerMode.SCHEDULED_AT_TIME,
    var targetX: Int = 540,
    var targetY: Int = 1600,
    // 设定触发时间：时、分、秒、毫秒 (例如 20:00:00.000)
    var targetHour: Int = 20,
    var targetMinute: Int = 0,
    var targetSecond: Int = 0,
    var targetMillisecond: Int = 0,
    // 提前触发补偿量 (毫秒)：例如设为 -35 表示在 19:59:59.965 提前发出手势以抵消触控与网络传输耗时；0 表示整点触发
    var advanceOffsetMs: Long = 0L,
    // 到达目标时刻后的连发点击次数（1 = 单次精准点击；>1 = 毫秒级突发连点抢购，例如连点 15 次）
    var burstCount: Int = 10,
    // 连发间隔 (毫秒，建议 25~60ms)
    var intervalMs: Long = 40L,
    // 单次按压持续时长 (毫秒，建议 25~45ms)
    var pressDurationMs: Long = 30L,
    // 防检测随机坐标微偏移半径 (px，0 表示严格固定坐标点)
    var randomRadiusPx: Int = 2,
    var updatedAt: Long = System.currentTimeMillis()
) {
    /**
     * 根据当前已校准的服务器时间戳，计算下一个目标触发时刻的 Epoch 毫秒值。
     * 若今天的目标时间已过（超过 2 秒以上），则自动顺延至次日同一时刻；若在今日未来或刚刚到达，则取今日时刻。
     */
    fun computeNextTargetEpochMs(currentServerEpochMs: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = currentServerEpochMs
            set(Calendar.HOUR_OF_DAY, targetHour.coerceIn(0, 23))
            set(Calendar.MINUTE, targetMinute.coerceIn(0, 59))
            set(Calendar.SECOND, targetSecond.coerceIn(0, 59))
            set(Calendar.MILLISECOND, targetMillisecond.coerceIn(0, 999))
        }
        var targetMs = cal.timeInMillis + advanceOffsetMs
        if (targetMs < currentServerEpochMs - 1500L) {
            targetMs += 24L * 3600L * 1000L
        }
        return targetMs
    }

    fun formatTargetTimeString(): String {
        return String.format(
            Locale.US,
            "%02d:%02d:%02d.%03d",
            targetHour,
            targetMinute,
            targetSecond,
            targetMillisecond
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("timeSource", timeSource.name)
        put("triggerMode", triggerMode.name)
        put("targetX", targetX)
        put("targetY", targetY)
        put("targetHour", targetHour)
        put("targetMinute", targetMinute)
        put("targetSecond", targetSecond)
        put("targetMillisecond", targetMillisecond)
        put("advanceOffsetMs", advanceOffsetMs)
        put("burstCount", burstCount)
        put("intervalMs", intervalMs)
        put("pressDurationMs", pressDurationMs)
        put("randomRadiusPx", randomRadiusPx)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(obj: JSONObject): ScheduledClickConfig = ScheduledClickConfig(
            id = obj.optString("id", UUID.randomUUID().toString()),
            name = obj.optString("name", "定点定时方案"),
            timeSource = runCatching {
                TimeServerSource.valueOf(obj.optString("timeSource", "JD_API"))
            }.getOrDefault(TimeServerSource.JD_API),
            triggerMode = runCatching {
                TriggerMode.valueOf(obj.optString("triggerMode", "SCHEDULED_AT_TIME"))
            }.getOrDefault(TriggerMode.SCHEDULED_AT_TIME),
            targetX = obj.optInt("targetX", 540),
            targetY = obj.optInt("targetY", 1600),
            targetHour = obj.optInt("targetHour", 20).coerceIn(0, 23),
            targetMinute = obj.optInt("targetMinute", 0).coerceIn(0, 59),
            targetSecond = obj.optInt("targetSecond", 0).coerceIn(0, 59),
            targetMillisecond = obj.optInt("targetMillisecond", 0).coerceIn(0, 999),
            advanceOffsetMs = obj.optLong("advanceOffsetMs", 0L).coerceIn(-5000L, 5000L),
            burstCount = obj.optInt("burstCount", 10).coerceIn(1, 5000),
            intervalMs = obj.optLong("intervalMs", 40L).coerceAtLeast(15L),
            pressDurationMs = obj.optLong("pressDurationMs", 30L).coerceIn(10L, 10_000L),
            randomRadiusPx = obj.optInt("randomRadiusPx", 2).coerceIn(0, 200),
            updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
        )

        fun formatEpochFullMs(epochMs: Long): String {
            val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
            return sdf.format(Date(epochMs))
        }
    }
}
