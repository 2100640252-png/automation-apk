package com.automation.work

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地存储：评论输入、运行日志、错误报告。
 * 全部存在应用私有 SharedPreferences，不上传、不读密码。
 * 输入每次编辑就保存，关软件或更新后能恢复；日志让用户看到每一步卡在哪。
 */
object TaskStore {
    private const val PREFS = "automation_work"
    private const val KEY_LOG = "run_log"
    private const val KEY_COMMENT_TASK = "comment_task_json"
    private const val KEY_LAST_ERROR_REPORT = "last_error_report"
    private const val KEY_ERROR_REPORTS = "error_reports"
    private const val KEY_COMMENT_INPUTS = "comment_inputs_json"
    private const val KEY_VIDEO_INPUTS = "video_inputs_json"

    data class VideoInputs(
        val link: String = "",
        val caption: String = "",
        val material: String = "",
        val schedule: String = ""
    )

    /** 运行日志：最多保留 200 行，新日志在前。 */
    fun appendLog(context: Context, line: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = prefs.getString(KEY_LOG, "") ?: ""
        val stamped = "[${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}] $line"
        val lines = (stamped + "\n" + old).split("\n").take(200)
        prefs.edit().putString(KEY_LOG, lines.joinToString("\n")).apply()
    }

    fun loadLog(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LOG, "暂无运行记录。") ?: "暂无运行记录。"
    }

    fun clearLog(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LOG, "").apply()
    }

    fun saveErrorReport(context: Context, report: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = prefs.getString(KEY_ERROR_REPORTS, "") ?: ""
        val merged = (report.trim() + "\n\n---\n\n" + old).take(30000)
        prefs.edit()
            .putString(KEY_LAST_ERROR_REPORT, report.trim())
            .putString(KEY_ERROR_REPORTS, merged)
            .apply()
    }

    fun loadLastErrorReport(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_ERROR_REPORT, "暂无错误报告。") ?: "暂无错误报告。"
    }

    fun loadErrorReports(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ERROR_REPORTS, "暂无错误报告。") ?: "暂无错误报告。"
    }

    fun clearErrorReports(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_ERROR_REPORT, "")
            .putString(KEY_ERROR_REPORTS, "")
            .apply()
    }

    fun saveCommentTask(context: Context, json: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_COMMENT_TASK, json).apply()
    }

    fun loadCommentTask(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_COMMENT_TASK, null)
    }

    /**
     * 评论输入：本版界面只使用第一条链接，但存储仍兼容旧版多链接格式。
     * 每次输入就保存；遇到第一个空链接就截止。
     */
    fun saveCommentInputs(context: Context, links: List<String>, text: String, interval: String) {
        val o = JSONObject()
        val arr = JSONArray()
        for (l in links) {
            val link = l.trim()
            if (link.isEmpty()) break
            arr.put(link)
        }
        o.put("links", arr)
        o.put("text", text)
        o.put("interval", interval)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_COMMENT_INPUTS, o.toString()).apply()
    }

    fun loadCommentInputs(context: Context): Triple<List<String>, String, String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_COMMENT_INPUTS, null)
        if (raw != null) {
            try {
                val o = JSONObject(raw)
                val arr = o.optJSONArray("links") ?: JSONArray()
                val links = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val link = arr.optString(i).trim()
                    if (link.isEmpty()) break
                    links.add(link)
                }
                return Triple(links, o.optString("text", ""), o.optString("interval", "10"))
            } catch (_: Exception) {
            }
        }
        // 旧版没存输入时，从上次开始任务时保存的记录里恢复链接和评论。
        val task = loadCommentTask(context)
        if (task != null && task.contains("\n---\n")) {
            val parts = task.split("\n---\n", limit = 2)
            val links = mutableListOf<String>()
            for (line in parts[0].split("\n")) {
                val link = line.trim()
                if (link.isEmpty()) break
                links.add(link)
            }
            return Triple(links, parts.getOrElse(1) { "" }, "10")
        }
        return Triple(emptyList(), "", "10")
    }

    fun saveVideoInputs(context: Context, inputs: VideoInputs) {
        val o = JSONObject()
        o.put("link", inputs.link)
        o.put("caption", inputs.caption)
        o.put("material", inputs.material)
        o.put("schedule", inputs.schedule)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_VIDEO_INPUTS, o.toString()).apply()
    }

    fun loadVideoInputs(context: Context): VideoInputs {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_VIDEO_INPUTS, null) ?: return VideoInputs()
        return try {
            val o = JSONObject(raw)
            VideoInputs(
                link = o.optString("link", ""),
                caption = o.optString("caption", ""),
                material = o.optString("material", ""),
                schedule = o.optString("schedule", "")
            )
        } catch (_: Exception) {
            VideoInputs()
        }
    }
}
