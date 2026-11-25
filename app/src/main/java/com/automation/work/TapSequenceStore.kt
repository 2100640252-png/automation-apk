package com.automation.work

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 模拟真人器点位记忆：每个平台一套点击序列。
 * 每一步要么是真实点击（屏幕比例坐标 + 点击次数），要么是填入评论。
 * 用户点哪里，软件记哪里；下次按顺序真实回放。
 */
object TapSequenceStore {

    private const val PREFS = "tap_sequences"

    const val TYPE_TAP = "tap"
    const val TYPE_FILL = "fill"

    data class Step(val type: String, val x: Float, val y: Float, val count: Int)

    private fun stepsKey(platform: String) = "steps_$platform"
    private fun repeatKey(platform: String) = "repeat_$platform"

    fun saveSteps(context: Context, platform: String, steps: List<Step>) {
        val arr = JSONArray()
        for (s in steps) {
            val o = JSONObject()
            o.put("type", s.type)
            o.put("x", s.x.toDouble())
            o.put("y", s.y.toDouble())
            o.put("count", s.count)
            arr.put(o)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(stepsKey(platform), arr.toString()).apply()
    }

    fun loadSteps(context: Context, platform: String): List<Step> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(stepsKey(platform), null) ?: return emptyList()
        val out = mutableListOf<Step>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    Step(
                        o.optString("type", TYPE_TAP),
                        o.optDouble("x", 0.0).toFloat(),
                        o.optDouble("y", 0.0).toFloat(),
                        o.optInt("count", 1).coerceAtLeast(1)
                    )
                )
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun saveRepeat(context: Context, platform: String, repeat: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(repeatKey(platform), repeat.coerceAtLeast(1)).apply()
    }

    fun loadRepeat(context: Context, platform: String): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(repeatKey(platform), 1).coerceAtLeast(1)
    }
}
