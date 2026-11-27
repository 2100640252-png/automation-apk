package com.automation.work

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

object InputEngine {
    fun backendName(): String {
        if (RootHelper.checkRoot().first) return "Root 输入"
        if (ShizukuHelper.isAvailable() && ShizukuHelper.isPermissionGranted()) return "Shizuku 输入"
        return "输入不可用：需要 Root 或 Shizuku 授权"
    }

    private fun run(cmd: String): RootHelper.CmdResult {
        val root = RootHelper.runAsRoot(cmd)
        if (root.exitCode == 0) return root
        if (ShizukuHelper.isAvailable() && ShizukuHelper.isPermissionGranted()) {
            val sz = ShizukuHelper.run(cmd)
            if (sz.exitCode == 0) return sz
        }
        return RootHelper.runShell(cmd)
    }

    fun tap(xPx: Int, yPx: Int): Boolean = run("input tap $xPx $yPx").exitCode == 0

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean =
        run("input swipe $x1 $y1 $x2 $y2 ${durationMs.coerceAtLeast(80)}").exitCode == 0

    fun paste(context: Context, text: String): Boolean {
        return try {
            val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
            clipboard.setPrimaryClip(ClipData.newPlainText("floating_script", text))
            run("input keyevent 279").exitCode == 0
        } catch (_: Exception) {
            false
        }
    }
}
