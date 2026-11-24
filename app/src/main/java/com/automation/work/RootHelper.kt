package com.automation.work

import java.io.File

/**
 * Root 助手（v1.6 新增）：用手机的 Root 权限（su）直接在手机上跑命令，
 * 不用接电脑、不用 ADB、不依赖无障碍服务是否还活着。
 *
 * Root 在这里就是开发者权限：有了它，App 可以自己在手机上执行
 * uiautomator（看界面）、input（点按屏幕）、am（打开应用/链接），
 * 全程都在手机本地完成，这才是“全程手机自动化”。
 *
 * 注意：只用 Root 跑系统自带的命令（su / uiautomator / input / am），
 * 不往任何第三方 App 里注入代码、不改它的内部逻辑。
 */
object RootHelper {

    data class CmdResult(val exitCode: Int, val output: String)

    /** 手机上有没有 su 程序。 */
    fun isSuAvailable(): Boolean {
        if (File("/system/bin/su").exists() || File("/system/xbin/su").exists()) return true
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("which", "su"))
            p.waitFor()
            p.inputStream.bufferedReader().readText().isNotBlank()
        } catch (e: Exception) {
            false
        }
    }

    /** 以 Root 身份跑一条命令，返回退出码和输出。超时返回 -1。 */
    fun runAsRoot(command: String, timeoutMs: Long = 15000L): CmdResult {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val done = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!done) {
                p.destroy()
                return CmdResult(-1, "命令超时：$command")
            }
            val out = p.inputStream.bufferedReader().readText() +
                p.errorStream.bufferedReader().readText()
            CmdResult(p.exitValue(), out.trim())
        } catch (e: Exception) {
            CmdResult(-1, "Root 执行失败：${e.message}")
        }
    }

    /** 不用 Root 跑一条普通 shell 命令。 */
    fun runShell(command: String, timeoutMs: Long = 15000L): CmdResult {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val done = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!done) {
                p.destroy()
                return CmdResult(-1, "命令超时：$command")
            }
            val out = p.inputStream.bufferedReader().readText() +
                p.errorStream.bufferedReader().readText()
            CmdResult(p.exitValue(), out.trim())
        } catch (e: Exception) {
            CmdResult(-1, "执行失败：${e.message}")
        }
    }

    /** 实测 Root 是否真的能用：su -c id 必须返回 uid=0。 */
    fun checkRoot(): Pair<Boolean, String> {
        if (!isSuAvailable()) return false to "手机上没有找到 su，尚未 Root。"
        val r = runAsRoot("id")
        return if (r.exitCode == 0 && r.output.contains("uid=0")) {
            true to "Root 可用（uid=0）。"
        } else {
            false to "su 存在但拿不到 Root：${r.output.take(120)}"
        }
    }
}
