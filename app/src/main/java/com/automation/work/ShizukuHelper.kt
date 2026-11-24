package com.automation.work

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Shizuku 助手（v1.7 新增）：不用 Root、不用解锁、不用清空数据，
 * 用开发者模式里的“无线调试”给 App 一个接近 ADB 的权限，
 * 就够跑自动化要的系统命令（am / uiautomator / input）。
 *
 * 适用机型：OPPO Find X2 Pro + ColorOS 13.1（Android 13）自带无线调试，
 * 装 Shizuku App、按提示配对启动即可。
 * 权限由用户在 Shizuku 里点“允许”才给，随时可以在 Shizuku 里收回。
 */
object ShizukuHelper {

    /** Shizuku 服务在不在（手机上装了 Shizuku 且已启动）。 */
    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    /** 用户有没有在 Shizuku 里给本 App 授权。 */
    fun isPermissionGranted(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /** 请求授权：会弹出 Shizuku 的授权框，由用户点允许。 */
    fun requestPermission() {
        try {
            Shizuku.requestPermission(0)
        } catch (e: Exception) {
        }
    }

    /** 实测 Shizuku 能不能用：服务在 + 已授权 + 能跑一条命令。 */
    fun checkShizuku(): Pair<Boolean, String> {
        if (!isAvailable()) return false to "Shizuku 没启动：请先装 Shizuku App，用无线调试启动它。"
        if (!isPermissionGranted()) return false to "Shizuku 没授权：点“申请 Shizuku 授权”，在弹出框里点允许。"
        val r = run("id")
        return if (r.exitCode == 0 && r.output.contains("uid=2000")) {
            true to "Shizuku 可用（adb 权限 uid=2000）。"
        } else if (r.exitCode == 0) {
            true to "Shizuku 可用（${r.output.take(60)}）。"
        } else {
            false to "Shizuku 命令跑不通：${r.output.take(120)}"
        }
    }

    /** 通过 Shizuku 跑一条 shell 命令（ADB 级权限）。 */
    fun run(command: String, timeoutMs: Long = 15000L): RootHelper.CmdResult {
        return try {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
                ?: return RootHelper.CmdResult(-1, "Shizuku 启动进程失败")
            val done = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!done) {
                process.destroy()
                return RootHelper.CmdResult(-1, "命令超时：$command")
            }
            val out = process.inputStream.bufferedReader().readText() +
                process.errorStream.bufferedReader().readText()
            RootHelper.CmdResult(process.exitValue(), out.trim())
        } catch (e: Exception) {
            RootHelper.CmdResult(-1, "Shizuku 执行失败：${e.message}")
        }
    }
}
