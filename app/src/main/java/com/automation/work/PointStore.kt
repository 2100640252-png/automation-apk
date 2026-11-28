package com.automation.work

import android.content.Context

/**
 * 点位记忆：评论点和发送点由用户校准，按平台和名称保存。
 * 坐标存屏幕比例（0~1），换分辨率时按比例换算。
 * 发送点应在键盘打开、发送键可见时校准。
 */
object PointStore {

    private const val PREFS = "tap_points"

    const val KIND_COMMENT = "comment"
    const val KIND_SEND = "send"

    data class TapPoint(val x: Float, val y: Float)

    fun displayName(kind: String): String {
        return if (kind == KIND_SEND) "发送点" else "评论点"
    }

    private fun key(platform: String, kind: String, axis: String) = "${platform}_${kind}_$axis"

    fun save(context: Context, platform: String, kind: String, x: Float, y: Float): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(key(platform, kind, "x"), x.coerceIn(0f, 1f))
            .putFloat(key(platform, kind, "y"), y.coerceIn(0f, 1f))
            .commit()
    }

    fun load(context: Context, platform: String, kind: String): TapPoint? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val xk = key(platform, kind, "x")
        val yk = key(platform, kind, "y")
        if (!prefs.contains(xk) || !prefs.contains(yk)) return null
        return TapPoint(prefs.getFloat(xk, -1f), prefs.getFloat(yk, -1f))
    }

    fun clear(context: Context, platform: String, kind: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(key(platform, kind, "x"))
            .remove(key(platform, kind, "y"))
            .apply()
    }

    fun describe(context: Context, platform: String, kind: String): String {
        val p = load(context, platform, kind) ?: return "未配置"
        return "X ${(p.x * 100).toInt()}% · Y ${(p.y * 100).toInt()}%"
    }
}
