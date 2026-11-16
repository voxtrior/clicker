package com.xundao.clicker.repository

import android.content.Context
import com.xundao.clicker.model.ScheduledClickConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * 定时定点方案持久化仓库
 */
class ConfigRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadActiveConfig(screenW: Int, screenH: Int): ScheduledClickConfig {
        val raw = prefs.getString(KEY_ACTIVE_CONFIG, null)
        if (raw != null) {
            runCatching {
                return ScheduledClickConfig.fromJson(JSONObject(raw))
            }
        }
        return ScheduledClickConfig(
            name = "京东/淘宝整点秒杀方案",
            targetX = (screenW * 0.78).toInt().coerceAtLeast(100),
            targetY = (screenH * 0.92).toInt().coerceAtLeast(200),
            targetHour = 20,
            targetMinute = 0,
            targetSecond = 0,
            targetMillisecond = 0,
            advanceOffsetMs = -25L,
            burstCount = 12,
            intervalMs = 35L,
            pressDurationMs = 28L,
            randomRadiusPx = 2
        )
    }

    fun saveActiveConfig(config: ScheduledClickConfig) {
        config.updatedAt = System.currentTimeMillis()
        prefs.edit().putString(KEY_ACTIVE_CONFIG, config.toJson().toString()).apply()
    }

    fun loadSavedPresets(): List<ScheduledClickConfig> {
        val raw = prefs.getString(KEY_PRESET_LIST, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val list = mutableListOf<ScheduledClickConfig>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { list.add(ScheduledClickConfig.fromJson(it)) }
            }
            list.sortedByDescending { it.updatedAt }
        }.getOrDefault(emptyList())
    }

    fun savePreset(config: ScheduledClickConfig) {
        val current = loadSavedPresets().toMutableList()
        config.updatedAt = System.currentTimeMillis()
        val idx = current.indexOfFirst { it.id == config.id }
        if (idx >= 0) {
            current[idx] = config
        } else {
            current.add(0, config)
        }
        persistPresets(current)
    }

    fun deletePreset(id: String) {
        val filtered = loadSavedPresets().filterNot { it.id == id }
        persistPresets(filtered)
    }

    private fun persistPresets(list: List<ScheduledClickConfig>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_PRESET_LIST, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "xundao_time_clicker_pro_prefs"
        private const val KEY_ACTIVE_CONFIG = "key_active_config"
        private const val KEY_PRESET_LIST = "key_preset_list"
    }
}
