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
    private const val KEY_SEARCH_INPUTS = "search_inputs_json"
    private const val KEY_COMMENTED_TARGETS = "commented_target_keys_json"
    private const val KEY_RESEARCH_INPUTS = "research_inputs_json"
    private const val KEY_RESEARCH_ITEMS = "research_items_json"
    private const val KEY_RESEARCH_LAST_DEDUPED = "research_last_deduped"

    data class SearchInputs(
        val platform: String = "抖音",
        val topic: String = "",
        val quantity: Int = 10
    )

    data class VideoInputs(
        val link: String = "",
        val caption: String = "",
        val material: String = "",
        val schedule: String = ""
    )

    data class ResearchInputs(
        val platform: String = "抖音",
        val topic: String = "",
        val contentType: String = "全部",
        val sortPreference: String = "综合",
        val limit: Int = 20,
        val collectionMode: String = "App内搜索"
    )

    data class ResearchItem(
        val platform: String,
        val topic: String,
        val title: String,
        val author: String,
        val metricsText: String,
        val metricScore: Long,
        val timeText: String,
        val newnessScore: Int,
        val score: Double,
        val scoreReason: String,
        val contentType: String,
        val link: String,
        val status: String,
        val dedupeKey: String,
        val capturedAt: Long
    )

    data class ResearchMergeResult(
        val items: List<ResearchItem>,
        val added: Int,
        val deduped: Int
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

    fun saveSearchInputs(context: Context, inputs: SearchInputs) {
        val o = JSONObject()
        o.put("platform", inputs.platform)
        o.put("topic", inputs.topic)
        o.put("quantity", inputs.quantity.coerceIn(1, 100))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SEARCH_INPUTS, o.toString()).apply()
    }

    fun loadSearchInputs(context: Context): SearchInputs {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SEARCH_INPUTS, null) ?: return SearchInputs()
        return try {
            val o = JSONObject(raw)
            SearchInputs(
                platform = o.optString("platform", "抖音"),
                topic = o.optString("topic", ""),
                quantity = o.optInt("quantity", 10).coerceIn(1, 100)
            )
        } catch (_: Exception) {
            SearchInputs()
        }
    }

    fun commentedTargetKeys(context: Context): Set<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_COMMENTED_TARGETS, null) ?: return emptySet()
        return try {
            val arr = JSONArray(raw)
            val out = linkedSetOf<String>()
            for (i in 0 until arr.length()) {
                val key = arr.optString(i).trim()
                if (key.isNotBlank()) out.add(key)
            }
            out
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun hasCommentedTarget(context: Context, targetKey: String): Boolean {
        if (targetKey.isBlank()) return false
        return targetKey in commentedTargetKeys(context)
    }

    fun recordCommentedTarget(context: Context, targetKey: String): Boolean {
        val key = targetKey.trim()
        if (key.isBlank()) return false
        val keys = commentedTargetKeys(context).toMutableSet()
        val added = keys.add(key)
        val arr = JSONArray()
        keys.take(1000).forEach { arr.put(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_COMMENTED_TARGETS, arr.toString())
            .apply()
        return added
    }

    fun commentedTargetCount(context: Context): Int = commentedTargetKeys(context).size

    fun saveResearchInputs(context: Context, inputs: ResearchInputs) {
        val o = JSONObject()
        o.put("platform", inputs.platform)
        o.put("topic", inputs.topic)
        o.put("contentType", inputs.contentType)
        o.put("sortPreference", inputs.sortPreference)
        o.put("limit", inputs.limit.coerceIn(1, 100))
        o.put("collectionMode", inputs.collectionMode)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_RESEARCH_INPUTS, o.toString()).apply()
    }

    fun loadResearchInputs(context: Context): ResearchInputs {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RESEARCH_INPUTS, null) ?: return ResearchInputs()
        return try {
            val o = JSONObject(raw)
            ResearchInputs(
                platform = o.optString("platform", "抖音"),
                topic = o.optString("topic", ""),
                contentType = o.optString("contentType", "全部"),
                sortPreference = o.optString("sortPreference", "综合"),
                limit = o.optInt("limit", 20).coerceIn(1, 100),
                collectionMode = o.optString("collectionMode", "App内搜索")
            )
        } catch (_: Exception) {
            ResearchInputs()
        }
    }

    private fun researchItemToJson(item: ResearchItem): JSONObject {
        val o = JSONObject()
        o.put("platform", item.platform)
        o.put("topic", item.topic)
        o.put("title", item.title)
        o.put("author", item.author)
        o.put("metricsText", item.metricsText)
        o.put("metricScore", item.metricScore)
        o.put("timeText", item.timeText)
        o.put("newnessScore", item.newnessScore)
        o.put("score", item.score)
        o.put("scoreReason", item.scoreReason)
        o.put("contentType", item.contentType)
        o.put("link", item.link)
        o.put("status", item.status)
        o.put("dedupeKey", item.dedupeKey)
        o.put("capturedAt", item.capturedAt)
        return o
    }

    private fun researchItemFromJson(o: JSONObject): ResearchItem {
        return ResearchItem(
            platform = o.optString("platform", ""),
            topic = o.optString("topic", ""),
            title = o.optString("title", ""),
            author = o.optString("author", ""),
            metricsText = o.optString("metricsText", ""),
            metricScore = o.optLong("metricScore", 0L),
            timeText = o.optString("timeText", ""),
            newnessScore = o.optInt("newnessScore", 0),
            score = o.optDouble("score", 0.0),
            scoreReason = o.optString("scoreReason", ""),
            contentType = o.optString("contentType", "全部"),
            link = o.optString("link", ""),
            status = o.optString("status", "待补链接"),
            dedupeKey = o.optString("dedupeKey", ""),
            capturedAt = o.optLong("capturedAt", 0L)
        )
    }

    fun sortResearchItems(items: List<ResearchItem>, sortPreference: String): List<ResearchItem> {
        return when (sortPreference) {
            "高流量优先" -> items.sortedWith(compareByDescending<ResearchItem> { it.metricScore }.thenByDescending { it.score }.thenByDescending { it.newnessScore })
            "新发优先" -> items.sortedWith(compareByDescending<ResearchItem> { it.newnessScore }.thenByDescending { it.score }.thenByDescending { it.metricScore })
            else -> items.sortedWith(compareByDescending<ResearchItem> { it.score }.thenByDescending { it.metricScore }.thenByDescending { it.newnessScore })
        }
    }

    fun loadResearchItems(context: Context): List<ResearchItem> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RESEARCH_ITEMS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<ResearchItem>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { out.add(researchItemFromJson(it)) }
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveResearchItems(context: Context, items: List<ResearchItem>, sortPreference: String, limit: Int) {
        val sorted = sortResearchItems(items, sortPreference).take(limit.coerceIn(1, 100))
        val arr = JSONArray()
        sorted.forEach { arr.put(researchItemToJson(it)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_RESEARCH_ITEMS, arr.toString()).apply()
    }

    fun addResearchItems(context: Context, incoming: List<ResearchItem>, inputs: ResearchInputs): ResearchMergeResult {
        val existing = loadResearchItems(context).toMutableList()
        val keys = existing.map { it.dedupeKey }.toMutableSet()
        val links = existing.map { it.link }.filter { it.isNotBlank() }.toMutableSet()
        var added = 0
        var deduped = 0
        for (item in incoming) {
            val linkKey = item.link.trim()
            if (item.dedupeKey.isNotBlank() && item.dedupeKey in keys || linkKey.isNotBlank() && linkKey in links) {
                deduped++
                continue
            }
            existing.add(item)
            if (item.dedupeKey.isNotBlank()) keys.add(item.dedupeKey)
            if (linkKey.isNotBlank()) links.add(linkKey)
            added++
        }
        val sorted = sortResearchItems(existing, inputs.sortPreference).take(inputs.limit.coerceIn(1, 100))
        saveResearchItems(context, sorted, inputs.sortPreference, inputs.limit)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_RESEARCH_LAST_DEDUPED, deduped).apply()
        return ResearchMergeResult(sorted, added, deduped)
    }

    fun upsertResearchItem(context: Context, item: ResearchItem, inputs: ResearchInputs): ResearchMergeResult {
        val existing = loadResearchItems(context).toMutableList()
        val index = existing.indexOfFirst {
            (item.dedupeKey.isNotBlank() && it.dedupeKey == item.dedupeKey) ||
                (item.link.isNotBlank() && it.link == item.link) ||
                (it.platform == item.platform && it.title == item.title && it.author == item.author && item.title.isNotBlank())
        }
        var added = 0
        var deduped = 0
        if (index >= 0) {
            val old = existing[index]
            existing[index] = if (item.link.isNotBlank()) item else old.copy(status = old.status.ifBlank { item.status })
            deduped = 1
        } else {
            existing.add(item)
            added = 1
        }
        val sorted = sortResearchItems(existing, inputs.sortPreference).take(inputs.limit.coerceIn(1, 100))
        saveResearchItems(context, sorted, inputs.sortPreference, inputs.limit)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_RESEARCH_LAST_DEDUPED, deduped).apply()
        return ResearchMergeResult(sorted, added, deduped)
    }

    fun clearResearchItems(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_RESEARCH_ITEMS, "[]")
            .putInt(KEY_RESEARCH_LAST_DEDUPED, 0)
            .apply()
    }

    fun researchLastDedupedCount(context: Context): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_RESEARCH_LAST_DEDUPED, 0)
    }

    fun researchExportText(context: Context): String {
        val inputs = loadResearchInputs(context)
        val items = sortResearchItems(loadResearchItems(context), inputs.sortPreference)
        val sb = StringBuilder()
        sb.append("话题找素材链接清单\n")
        sb.append("平台：${inputs.platform} · 话题：${inputs.topic.ifBlank { "未填写" }} · 类型：${inputs.contentType} · 排序：${inputs.sortPreference} · 方式：${inputs.collectionMode}\n")
        if (items.isEmpty()) {
            sb.append("暂无采集结果。")
            return sb.toString()
        }
        items.forEachIndexed { index, item ->
            val linkText = item.link.ifBlank { "待补链接" }
            val metrics = item.metricsText.ifBlank { "未识别流量" }
            val time = item.timeText.ifBlank { "未识别时间" }
            val author = item.author.ifBlank { "未知作者" }
            sb.append("${index + 1}. [${item.scoreReason}] ${item.title.ifBlank { "未识别标题" }} | $author | $metrics · $time | ${item.contentType} | $linkText\n")
        }
        return sb.toString().trim()
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
