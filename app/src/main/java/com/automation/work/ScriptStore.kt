package com.automation.work

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object ScriptStore {
    private const val PREFS = "floating_script"
    private const val KEY_STEPS = "steps_json"
    private const val KEY_LINK = "link"
    private const val KEY_TEXT = "paste_text"

    const val TYPE_TAP = "tap"
    const val TYPE_SWIPE = "swipe"
    const val TYPE_WAIT = "wait"
    const val TYPE_PASTE = "paste"

    data class Step(
        val type: String,
        val name: String,
        val x: Float = 0f,
        val y: Float = 0f,
        val x2: Float = 0f,
        val y2: Float = 0f,
        val durationMs: Int = 300,
        val text: String = ""
    )

    fun loadSteps(context: Context): List<Step> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_STEPS, "[]") ?: "[]"
        val out = mutableListOf<Step>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    Step(
                        type = o.optString("type", TYPE_TAP),
                        name = o.optString("name", "点位${i + 1}"),
                        x = o.optDouble("x", 0.0).toFloat(),
                        y = o.optDouble("y", 0.0).toFloat(),
                        x2 = o.optDouble("x2", 0.0).toFloat(),
                        y2 = o.optDouble("y2", 0.0).toFloat(),
                        durationMs = o.optInt("durationMs", 300),
                        text = o.optString("text", "")
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun saveSteps(context: Context, steps: List<Step>) {
        val arr = JSONArray()
        steps.forEach { s ->
            arr.put(
                JSONObject()
                    .put("type", s.type)
                    .put("name", s.name)
                    .put("x", s.x.toDouble())
                    .put("y", s.y.toDouble())
                    .put("x2", s.x2.toDouble())
                    .put("y2", s.y2.toDouble())
                    .put("durationMs", s.durationMs)
                    .put("text", s.text)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_STEPS, arr.toString()).commit()
    }

    fun addStep(context: Context, step: Step): List<Step> {
        val steps = loadSteps(context).toMutableList()
        steps.add(step)
        saveSteps(context, steps)
        return steps
    }

    fun saveLink(context: Context, link: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LINK, link).apply()
    }

    fun loadLink(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LINK, "") ?: ""

    fun saveText(context: Context, text: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TEXT, text).apply()
    }

    fun loadText(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TEXT, "") ?: ""
}
