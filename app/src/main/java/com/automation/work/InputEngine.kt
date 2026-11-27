package com.automation.work

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

object InputEngine {
    @Volatile var lastResult: String = "尚未执行输入命令"
        private set

    fun backendName(): String {
        if (ShizukuHelper.isAvailable() && ShizukuHelper.isPermissionGranted()) return "Shizuku 输入"
        if (RootHelper.checkRoot().first) return "Root 输入"
        return "输入不可用：需要 Shizuku 授权或 Root"
    }

    private fun run(cmd: String): RootHelper.CmdResult {
        val result = if (ShizukuHelper.isAvailable() && ShizukuHelper.isPermissionGranted()) {
            ShizukuHelper.run(cmd)
        } else {
            val root = RootHelper.runAsRoot(cmd)
            if (root.exitCode == 0) root else RootHelper.runShell(cmd)
        }
        lastResult = if (result.exitCode == 0) {
            "命令成功：$cmd"
        } else {
            "命令失败(${result.exitCode})：$cmd；${result.output.take(160)}"
        }
        return result
    }

    fun shizukuSelfTest(): Pair<Boolean, String> {
        val (ok, message) = ShizukuHelper.checkShizuku()
        lastResult = message
        return ok to message
    }

    fun tap(xPx: Int, yPx: Int): Boolean = run("input tap $xPx $yPx").exitCode == 0

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean =
        run("input swipe $x1 $y1 $x2 $y2 ${durationMs.coerceAtLeast(80)}").exitCode == 0

    fun paste(context: Context, text: String): Boolean {
        return try {
            val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
            clipboard.setPrimaryClip(ClipData.newPlainText("floating_script", text))
            run("input keyevent 279").exitCode == 0
        } catch (e: Exception) {
            lastResult = "粘贴失败：${e.message}"
            false
        }
    }
}
