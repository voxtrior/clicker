package com.xundao.clicker.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 动作类型：单击、长按、滑动
 */
enum class ActionType {
    CLICK,
    LONG_PRESS,
    SWIPE
}

/**
 * 停止条件类型：无限循环、指定时长(秒)、指定循环次数
 */
enum class StopConditionType {
    INFINITE,
    DURATION_SECONDS,
    CYCLE_COUNT
}

/**
 * 单个触控节点模型（支持单击、长按、滑动及防检测随机参数）
 */
data class ActionNode(
    val id: Int,
    val type: ActionType = ActionType.CLICK,
    var x: Int = 540,
    var y: Int = 960,
    var endX: Int = 540,
    var endY: Int = 500,
    var delayBeforeMs: Long = 0L,
    var intervalMs: Long = 100L,
    var durationMs: Long = 45L,
    var repeatCount: Int = 1,
    var randomOffsetRadiusPx: Int = 6,
    var randomIntervalJitterMs: Long = 15L
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("x", x)
        put("y", y)
        put("endX", endX)
        put("endY", endY)
        put("delayBeforeMs", delayBeforeMs)
        put("intervalMs", intervalMs)
        put("durationMs", durationMs)
        put("repeatCount", repeatCount)
        put("randomOffsetRadiusPx", randomOffsetRadiusPx)
        put("randomIntervalJitterMs", randomIntervalJitterMs)
    }

    companion object {
        fun fromJson(obj: JSONObject): ActionNode = ActionNode(
            id = obj.optInt("id", 1),
            type = runCatching { ActionType.valueOf(obj.optString("type", "CLICK")) }.getOrDefault(ActionType.CLICK),
            x = obj.optInt("x", 540),
            y = obj.optInt("y", 960),
            endX = obj.optInt("endX", 540),
            endY = obj.optInt("endY", 500),
            delayBeforeMs = obj.optLong("delayBeforeMs", 0L),
            intervalMs = obj.optLong("intervalMs", 100L).coerceAtLeast(10L),
            durationMs = obj.optLong("durationMs", 45L).coerceAtLeast(10L),
            repeatCount = obj.optInt("repeatCount", 1).coerceAtLeast(1),
            randomOffsetRadiusPx = obj.optInt("randomOffsetRadiusPx", 6).coerceAtLeast(0),
            randomIntervalJitterMs = obj.optLong("randomIntervalJitterMs", 15L).coerceAtLeast(0L)
        )
    }
}

/**
 * 完整脚本配置方案
 */
data class ScriptConfig(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "默认连点方案",
    var isMultiMode: Boolean = false,
    var nodes: MutableList<ActionNode> = mutableListOf(),
    var stopConditionType: StopConditionType = StopConditionType.INFINITE,
    var stopValue: Long = 0L,
    var antiDetectionEnabled: Boolean = true,
    var updatedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("isMultiMode", isMultiMode)
        put("stopConditionType", stopConditionType.name)
        put("stopValue", stopValue)
        put("antiDetectionEnabled", antiDetectionEnabled)
        put("updatedAt", updatedAt)
        val arr = JSONArray()
        nodes.forEach { arr.put(it.toJson()) }
        put("nodes", arr)
    }

    companion object {
        fun fromJson(obj: JSONObject): ScriptConfig {
            val nodeList = mutableListOf<ActionNode>()
            val arr = obj.optJSONArray("nodes")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { nodeList.add(ActionNode.fromJson(it)) }
                }
            }
            return ScriptConfig(
                id = obj.optString("id", UUID.randomUUID().toString()),
                name = obj.optString("name", "未命名方案"),
                isMultiMode = obj.optBoolean("isMultiMode", false),
                nodes = nodeList,
                stopConditionType = runCatching {
                    StopConditionType.valueOf(obj.optString("stopConditionType", "INFINITE"))
                }.getOrDefault(StopConditionType.INFINITE),
                stopValue = obj.optLong("stopValue", 0L),
                antiDetectionEnabled = obj.optBoolean("antiDetectionEnabled", true),
                updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
            )
        }
    }
}

/**
 * 全局默认设置参数
 */
data class GlobalSettings(
    val defaultIntervalMs: Long = 100L,
    val defaultDurationMs: Long = 45L,
    val defaultSwipeDurationMs: Long = 320L,
    val defaultRandomRadiusPx: Int = 6,
    val defaultRandomJitterMs: Long = 15L,
    val antiDetectionEnabled: Boolean = true,
    val defaultStopCondition: StopConditionType = StopConditionType.INFINITE,
    val defaultStopValue: Long = 60L
)
