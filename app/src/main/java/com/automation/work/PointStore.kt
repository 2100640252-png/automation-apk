package com.automation.work

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 语义点位记忆：点位不只是坐标，还保存角色、用户命名、节点签名和前后窗口证据。
 * 坐标存屏幕比例（0~1）。执行时先按签名找控件，找不到再按坐标点，并验证期望转换。
 */
object PointStore {

    private const val PREFS = "tap_points"
    private const val KEY_SEMANTIC = "semantic_points_json"
    private const val KEY_LOOP_PREFIX = "semantic_loop_"

    // 兼容旧版 kind 名称；新代码统一使用语义角色。
    const val KIND_COMMENT = "comment"
    const val KIND_SEND = "send"

    const val ROLE_COMMENT_ENTRY = "COMMENT_ENTRY"
    const val ROLE_COMMENT_INPUT = "COMMENT_INPUT"
    const val ROLE_SEND = "SEND"
    const val ROLE_SEARCH_BOX = "SEARCH_BOX"
    const val ROLE_SEARCH_SUBMIT = "SEARCH_SUBMIT"
    const val ROLE_VIDEO_RESULT = "VIDEO_RESULT"
    const val ROLE_CUSTOM = "CUSTOM"

    val roles: List<String> = listOf(
        ROLE_COMMENT_ENTRY,
        ROLE_COMMENT_INPUT,
        ROLE_SEND,
        ROLE_SEARCH_BOX,
        ROLE_SEARCH_SUBMIT,
        ROLE_VIDEO_RESULT,
        ROLE_CUSTOM
    )

    data class TapPoint(val x: Float, val y: Float)

    data class SemanticPoint(
        val platform: String,
        val packageName: String,
        val role: String,
        val label: String,
        val x: Float,
        val y: Float,
        val nodeSignature: String,
        val beforeSignature: String,
        val afterSignature: String,
        val expectedTransition: String,
        val confidence: Int,
        val updatedAt: Long
    )

    data class LoopConfig(
        val roles: List<String> = emptyList(),
        val rounds: Int = 1,
        val intervalSeconds: Int = 2
    )

    fun roleDisplayName(role: String): String {
        return when (role) {
            ROLE_COMMENT_ENTRY -> "评论入口"
            ROLE_COMMENT_INPUT -> "评论输入框"
            ROLE_SEND -> "发送键"
            ROLE_SEARCH_BOX -> "搜索框"
            ROLE_SEARCH_SUBMIT -> "搜索提交"
            ROLE_VIDEO_RESULT -> "视频结果"
            ROLE_CUSTOM -> "自定义点"
            KIND_SEND -> "发送键"
            KIND_COMMENT -> "评论入口"
            else -> role
        }
    }

    fun displayName(kind: String): String = roleDisplayName(kindToRole(kind))

    fun kindToRole(kind: String): String {
        return when (kind) {
            KIND_COMMENT -> ROLE_COMMENT_ENTRY
            KIND_SEND -> ROLE_SEND
            else -> if (kind in roles) kind else ROLE_CUSTOM
        }
    }

    fun expectedTransitionFor(role: String): String {
        return when (kindToRole(role)) {
            ROLE_COMMENT_ENTRY -> "评论输入框或评论面板出现"
            ROLE_COMMENT_INPUT -> "评论输入框获得焦点且可填入文字"
            ROLE_SEND -> "输入框清空且输入框外同文案评论数量增加"
            ROLE_SEARCH_BOX -> "搜索输入框出现、获得焦点或可填入话题"
            ROLE_SEARCH_SUBMIT -> "结果页出现并包含话题或结果条目"
            ROLE_VIDEO_RESULT -> "进入视频页或视频相关窗口出现"
            else -> "窗口签名发生变化"
        }
    }

    private fun oldKey(platform: String, kind: String, axis: String) = "${platform}_${kind}_$axis"

    private fun loadAll(context: Context): MutableList<SemanticPoint> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_SEMANTIC, null) ?: return mutableListOf()
        val out = mutableListOf<SemanticPoint>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val role = kindToRole(o.optString("role", ROLE_CUSTOM))
                out.add(
                    SemanticPoint(
                        platform = o.optString("platform", ""),
                        packageName = o.optString("packageName", ""),
                        role = role,
                        label = o.optString("label", roleDisplayName(role)),
                        x = o.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f),
                        y = o.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f),
                        nodeSignature = o.optString("nodeSignature", ""),
                        beforeSignature = o.optString("beforeSignature", ""),
                        afterSignature = o.optString("afterSignature", ""),
                        expectedTransition = o.optString("expectedTransition", expectedTransitionFor(role)),
                        confidence = o.optInt("confidence", 30).coerceIn(0, 100),
                        updatedAt = o.optLong("updatedAt", 0L)
                    )
                )
            }
        } catch (_: Exception) {
            return mutableListOf()
        }
        return out
    }

    private fun saveAll(context: Context, points: List<SemanticPoint>): Boolean {
        val arr = JSONArray()
        points.forEach { p ->
            val o = JSONObject()
            o.put("platform", p.platform)
            o.put("packageName", p.packageName)
            o.put("role", kindToRole(p.role))
            o.put("label", p.label.ifBlank { roleDisplayName(p.role) })
            o.put("x", p.x.coerceIn(0f, 1f).toDouble())
            o.put("y", p.y.coerceIn(0f, 1f).toDouble())
            o.put("nodeSignature", p.nodeSignature)
            o.put("beforeSignature", p.beforeSignature)
            o.put("afterSignature", p.afterSignature)
            o.put("expectedTransition", p.expectedTransition.ifBlank { expectedTransitionFor(p.role) })
            o.put("confidence", p.confidence.coerceIn(0, 100))
            o.put("updatedAt", p.updatedAt)
            arr.put(o)
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SEMANTIC, arr.toString())
            .commit()
    }

    private fun migrateOldPoint(context: Context, platform: String, kind: String): SemanticPoint? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val xk = oldKey(platform, kind, "x")
        val yk = oldKey(platform, kind, "y")
        if (!prefs.contains(xk) || !prefs.contains(yk)) return null
        val role = kindToRole(kind)
        val point = SemanticPoint(
            platform = platform,
            packageName = "",
            role = role,
            label = roleDisplayName(role),
            x = prefs.getFloat(xk, 0.5f).coerceIn(0f, 1f),
            y = prefs.getFloat(yk, 0.5f).coerceIn(0f, 1f),
            nodeSignature = "旧版坐标迁移：无节点签名",
            beforeSignature = "",
            afterSignature = "",
            expectedTransition = expectedTransitionFor(role),
            confidence = 25,
            updatedAt = System.currentTimeMillis()
        )
        saveSemantic(context, point)
        return point
    }

    fun saveSemantic(context: Context, point: SemanticPoint): Boolean {
        val role = kindToRole(point.role)
        val normalized = point.copy(
            role = role,
            label = point.label.ifBlank { roleDisplayName(role) },
            x = point.x.coerceIn(0f, 1f),
            y = point.y.coerceIn(0f, 1f),
            expectedTransition = point.expectedTransition.ifBlank { expectedTransitionFor(role) },
            confidence = point.confidence.coerceIn(0, 100),
            updatedAt = if (point.updatedAt > 0L) point.updatedAt else System.currentTimeMillis()
        )
        val all = loadAll(context)
        all.removeAll { it.platform == normalized.platform && it.role == normalized.role }
        all.add(normalized)
        val ok = saveAll(context, all)
        if (ok) {
            val kind = when (normalized.role) {
                ROLE_COMMENT_ENTRY -> KIND_COMMENT
                ROLE_SEND -> KIND_SEND
                else -> null
            }
            if (kind != null) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putFloat(oldKey(normalized.platform, kind, "x"), normalized.x)
                    .putFloat(oldKey(normalized.platform, kind, "y"), normalized.y)
                    .apply()
            }
        }
        return ok
    }

    fun loadSemantic(context: Context, platform: String, role: String): SemanticPoint? {
        val normalizedRole = kindToRole(role)
        val existing = loadAll(context).firstOrNull { it.platform == platform && it.role == normalizedRole }
        if (existing != null) return existing
        return when (normalizedRole) {
            ROLE_COMMENT_ENTRY -> migrateOldPoint(context, platform, KIND_COMMENT)
            ROLE_SEND -> migrateOldPoint(context, platform, KIND_SEND)
            else -> null
        }
    }

    fun listSemantic(context: Context, platform: String): List<SemanticPoint> {
        loadSemantic(context, platform, ROLE_COMMENT_ENTRY)
        loadSemantic(context, platform, ROLE_SEND)
        val byRole = loadAll(context).filter { it.platform == platform }.associateBy { it.role }
        return roles.mapNotNull { byRole[it] }
    }

    fun deleteSemantic(context: Context, platform: String, role: String) {
        val normalizedRole = kindToRole(role)
        val all = loadAll(context)
        all.removeAll { it.platform == platform && it.role == normalizedRole }
        saveAll(context, all)
        val kind = when (normalizedRole) {
            ROLE_COMMENT_ENTRY -> KIND_COMMENT
            ROLE_SEND -> KIND_SEND
            else -> null
        }
        if (kind != null) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(oldKey(platform, kind, "x"))
                .remove(oldKey(platform, kind, "y"))
                .apply()
        }
    }

    fun updateCoordinate(context: Context, platform: String, role: String, x: Float, y: Float): Boolean {
        val existing = loadSemantic(context, platform, role) ?: return false
        return saveSemantic(
            context,
            existing.copy(
                x = x.coerceIn(0f, 1f),
                y = y.coerceIn(0f, 1f),
                confidence = minOf(existing.confidence, 45),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    fun saveManual(context: Context, platform: String, packageName: String, role: String, x: Float, y: Float): Boolean {
        val normalizedRole = kindToRole(role)
        val existing = loadSemantic(context, platform, normalizedRole)
        return saveSemantic(
            context,
            SemanticPoint(
                platform = platform,
                packageName = packageName.ifBlank { existing?.packageName ?: "" },
                role = normalizedRole,
                label = existing?.label ?: roleDisplayName(normalizedRole),
                x = x,
                y = y,
                nodeSignature = existing?.nodeSignature ?: "手动校准：无节点签名",
                beforeSignature = existing?.beforeSignature ?: "",
                afterSignature = existing?.afterSignature ?: "",
                expectedTransition = expectedTransitionFor(normalizedRole),
                confidence = 45,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    fun describeSemantic(point: SemanticPoint?): String {
        if (point == null) return "未配置"
        val evidence = point.nodeSignature.ifBlank { "无节点签名" }.take(90)
        return "${point.label} · X ${(point.x * 100).toInt()}% · Y ${(point.y * 100).toInt()}% · 置信度 ${point.confidence}% · 期望：${point.expectedTransition} · 证据：$evidence"
    }

    fun describe(context: Context, platform: String, kind: String): String {
        return describeSemantic(loadSemantic(context, platform, kindToRole(kind)))
    }

    // 兼容旧调用：保存坐标时同步写入语义点位。
    fun save(context: Context, platform: String, kind: String, x: Float, y: Float): Boolean {
        return saveManual(context, platform, "", kindToRole(kind), x, y)
    }

    fun load(context: Context, platform: String, kind: String): TapPoint? {
        val p = loadSemantic(context, platform, kindToRole(kind)) ?: return null
        return TapPoint(p.x, p.y)
    }

    fun clear(context: Context, platform: String, kind: String) {
        deleteSemantic(context, platform, kindToRole(kind))
    }

    fun saveLoop(context: Context, platform: String, roles: List<String>, rounds: Int, intervalSeconds: Int): Boolean {
        val cleanRoles = roles.map { kindToRole(it) }.filter { it != ROLE_SEND }.distinct()
        val o = JSONObject()
        val arr = JSONArray()
        cleanRoles.forEach { arr.put(it) }
        o.put("roles", arr)
        o.put("rounds", rounds.coerceIn(1, 20))
        o.put("intervalSeconds", intervalSeconds.coerceIn(1, 60))
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LOOP_PREFIX + platform, o.toString())
            .commit()
    }

    fun loadLoop(context: Context, platform: String): LoopConfig {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LOOP_PREFIX + platform, null) ?: return LoopConfig()
        return try {
            val o = JSONObject(raw)
            val arr = o.optJSONArray("roles") ?: JSONArray()
            val roles = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val role = kindToRole(arr.optString(i))
                if (role != ROLE_SEND && role !in roles) roles.add(role)
            }
            LoopConfig(
                roles = roles,
                rounds = o.optInt("rounds", 1).coerceIn(1, 20),
                intervalSeconds = o.optInt("intervalSeconds", 2).coerceIn(1, 60)
            )
        } catch (_: Exception) {
            LoopConfig()
        }
    }
}
