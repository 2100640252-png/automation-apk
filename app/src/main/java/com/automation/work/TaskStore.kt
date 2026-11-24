package com.automation.work

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地存储：视频库、评论任务、运行日志。
 * 全部存在应用私有 SharedPreferences + 应用内 files/videos，
 * 不上传、不读密码。日志用于“遇到错误自己修复”时定位问题，
 * 也让用户在界面上直接看到每一步做了什么、卡在哪一步。
 */
data class StoredVideo(
    val id: String,
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val addedAt: Long,
    val publishAtMillis: Long,
    val platforms: List<String>,
    val status: String
)

object TaskStore {
    private const val PREFS = "automation_work"
    private const val KEY_VIDEOS = "videos_json"
    private const val KEY_LOG = "run_log"
    private const val KEY_TOKEN = "github_token"
    private const val KEY_COMMENT_TASK = "comment_task_json"

    fun loadVideos(context: Context): List<StoredVideo> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_VIDEOS, "[]") ?: "[]"
        val out = mutableListOf<StoredVideo>()
        val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val plats = mutableListOf<String>()
            val pa = o.optJSONArray("platforms")
            if (pa != null) for (j in 0 until pa.length()) plats.add(pa.optString(j))
            out.add(
                StoredVideo(
                    o.optString("id"),
                    o.optString("name"),
                    o.optString("path"),
                    o.optLong("sizeBytes"),
                    o.optLong("addedAt"),
                    o.optLong("publishAtMillis"),
                    plats,
                    o.optString("status", "已保存")
                )
            )
        }
        return out
    }

    fun saveVideos(context: Context, videos: List<StoredVideo>) {
        val arr = JSONArray()
        for (v in videos) {
            val o = JSONObject()
            o.put("id", v.id)
            o.put("name", v.name)
            o.put("path", v.path)
            o.put("sizeBytes", v.sizeBytes)
            o.put("addedAt", v.addedAt)
            o.put("publishAtMillis", v.publishAtMillis)
            val pa = JSONArray()
            for (p in v.platforms) pa.put(p)
            o.put("platforms", pa)
            o.put("status", v.status)
            arr.put(o)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_VIDEOS, arr.toString()).apply()
    }

    fun addVideo(context: Context, video: StoredVideo) {
        val list = loadVideos(context).toMutableList()
        list.add(0, video)
        saveVideos(context, list)
    }

    fun updateVideoStatus(context: Context, id: String, status: String) {
        val list = loadVideos(context).map {
            if (it.id == id) it.copy(status = status) else it
        }
        saveVideos(context, list)
    }

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

    fun saveGithubToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TOKEN, token.trim()).apply()
    }

    fun loadGithubToken(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, "") ?: ""
    }

    fun saveCommentTask(context: Context, json: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_COMMENT_TASK, json).apply()
    }

    fun loadCommentTask(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_COMMENT_TASK, null)
    }
}
