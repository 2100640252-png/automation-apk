package com.automation.work

import android.content.Context

/**
 * 每平台点位记忆：评论点（打开评论区）和发送点（确认发送）由用户校准，
 * 按平台分别保存，软件记住后每次评论都按保存的位置真实点击。
 * 坐标存屏幕比例（0~1），换分辨率/横竖屏变化时按比例换算。
 */
object PointStore {

    private const val PREFS = "tap_points"

    const val KIND_COMMENT = "comment"
    const val KIND_SEND = "send"

    data class TapPoint(val x: Float, val y: Float)

    private fun key(platform: String, kind: String, axis: String) = "${platform}_${kind}_$axis"

    fun save(context: Context, platform: String, kind: String, x: Float, y: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(key(platform, kind, "x"), x.coerceIn(0f, 1f))
            .putFloat(key(platform, kind, "y"), y.coerceIn(0f, 1f))
            .apply()
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
