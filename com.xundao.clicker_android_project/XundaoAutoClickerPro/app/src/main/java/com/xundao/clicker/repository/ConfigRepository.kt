package com.xundao.clicker.repository

import android.content.Context
import com.xundao.clicker.model.ActionNode
import com.xundao.clicker.model.ActionType
import com.xundao.clicker.model.GlobalSettings
import com.xundao.clicker.model.ScriptConfig
import com.xundao.clicker.model.StopConditionType
import org.json.JSONArray
import org.json.JSONObject

/**
 * 脚本与全局参数本地持久化仓库（基于 SharedPreferences + JSON，零第三方依赖）
 */
class ConfigRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadGlobalSettings(): GlobalSettings {
        val raw = prefs.getString(KEY_GLOBAL_SETTINGS, null) ?: return GlobalSettings()
        return runCatching {
            val obj = JSONObject(raw)
            GlobalSettings(
                defaultIntervalMs = obj.optLong("defaultIntervalMs", 100L).coerceAtLeast(10L),
                defaultDurationMs = obj.optLong("defaultDurationMs", 45L).coerceAtLeast(10L),
                defaultSwipeDurationMs = obj.optLong("defaultSwipeDurationMs", 320L).coerceAtLeast(50L),
                defaultRandomRadiusPx = obj.optInt("defaultRandomRadiusPx", 6).coerceAtLeast(0),
                defaultRandomJitterMs = obj.optLong("defaultRandomJitterMs", 15L).coerceAtLeast(0L),
                antiDetectionEnabled = obj.optBoolean("antiDetectionEnabled", true),
                defaultStopCondition = runCatching {
                    StopConditionType.valueOf(obj.optString("defaultStopCondition", "INFINITE"))
                }.getOrDefault(StopConditionType.INFINITE),
                defaultStopValue = obj.optLong("defaultStopValue", 60L).coerceAtLeast(1L)
            )
        }.getOrDefault(GlobalSettings())
    }

    fun saveGlobalSettings(settings: GlobalSettings) {
        val obj = JSONObject().apply {
            put("defaultIntervalMs", settings.defaultIntervalMs)
            put("defaultDurationMs", settings.defaultDurationMs)
            put("defaultSwipeDurationMs", settings.defaultSwipeDurationMs)
            put("defaultRandomRadiusPx", settings.defaultRandomRadiusPx)
            put("defaultRandomJitterMs", settings.defaultRandomJitterMs)
            put("antiDetectionEnabled", settings.antiDetectionEnabled)
            put("defaultStopCondition", settings.defaultStopCondition.name)
            put("defaultStopValue", settings.defaultStopValue)
        }
        prefs.edit().putString(KEY_GLOBAL_SETTINGS, obj.toString()).apply()
    }

    fun loadSavedScripts(): List<ScriptConfig> {
        val raw = prefs.getString(KEY_SAVED_SCRIPTS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val list = mutableListOf<ScriptConfig>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { list.add(ScriptConfig.fromJson(it)) }
            }
            list.sortedByDescending { it.updatedAt }
        }.getOrDefault(emptyList())
    }

    fun saveScript(script: ScriptConfig) {
        val current = loadSavedScripts().toMutableList()
        val index = current.indexOfFirst { it.id == script.id }
        script.updatedAt = System.currentTimeMillis()
        if (index >= 0) {
            current[index] = script
        } else {
            current.add(0, script)
        }
        persistScriptList(current)
    }

    fun deleteScript(scriptId: String) {
        val current = loadSavedScripts().filterNot { it.id == scriptId }
        persistScriptList(current)
    }

    fun createDefaultSingleScript(screenW: Int, screenH: Int): ScriptConfig {
        val g = loadGlobalSettings()
        return ScriptConfig(
            name = "单点极速连点",
            isMultiMode = false,
            nodes = mutableListOf(
                ActionNode(
                    id = 1,
                    type = ActionType.CLICK,
                    x = screenW / 2,
                    y = screenH / 2,
                    intervalMs = g.defaultIntervalMs,
                    durationMs = g.defaultDurationMs,
                    randomOffsetRadiusPx = g.defaultRandomRadiusPx,
                    randomIntervalJitterMs = g.defaultRandomJitterMs
                )
            ),
            stopConditionType = g.defaultStopCondition,
            stopValue = g.defaultStopValue,
            antiDetectionEnabled = g.antiDetectionEnabled
        )
    }

    fun createDefaultMultiScript(screenW: Int, screenH: Int): ScriptConfig {
        val g = loadGlobalSettings()
        return ScriptConfig(
            name = "多点组合脚本",
            isMultiMode = true,
            nodes = mutableListOf(
                ActionNode(
                    id = 1,
                    type = ActionType.CLICK,
                    x = screenW / 2,
                    y = (screenH * 0.45).toInt(),
                    intervalMs = g.defaultIntervalMs,
                    durationMs = g.defaultDurationMs,
                    randomOffsetRadiusPx = g.defaultRandomRadiusPx,
                    randomIntervalJitterMs = g.defaultRandomJitterMs
                )
            ),
            stopConditionType = g.defaultStopCondition,
            stopValue = g.defaultStopValue,
            antiDetectionEnabled = g.antiDetectionEnabled
        )
    }

    private fun persistScriptList(list: List<ScriptConfig>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_SAVED_SCRIPTS, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "xundao_clicker_pro_prefs"
        private const val KEY_GLOBAL_SETTINGS = "key_global_settings"
        private const val KEY_SAVED_SCRIPTS = "key_saved_scripts"
    }
}
