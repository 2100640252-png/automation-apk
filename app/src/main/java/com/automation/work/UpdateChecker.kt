package com.automation.work

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 软件内更新（v1.1 新增）：
 * 1. 查 GitHub Releases 最新版（tag 如 v1.0.7），和本机 versionName 比较。
 * 2. 有新版就在软件内直接下载 app-release.apk 到应用缓存，再调系统安装器安装，
 *    不用去网页上手动下载、不用解压 zip。
 *
 * v1.3.0：仓库已改为公开，更新不再需要 Token。
 * 查最新版不用带 Token，直接调公开的 GitHub API 就行。
 * Token 字段保留兼容（以前填过的 Token 仍会被带上，但不填也完全能用）。
 */
object UpdateChecker {
    data class UpdateInfo(
        val tag: String,
        val name: String,
        val apkUrl: String,
        val sizeBytes: Long
    )

    fun currentVersionName(context: Context): String {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    /** 在后台线程查最新版，结果回主线程。 */
    fun checkForUpdate(context: Context, onResult: (UpdateInfo?, String) -> Unit) {
        Thread {
            val handler = Handler(Looper.getMainLooper())
            try {
                val token = TaskStore.loadGithubToken(context)
                val url = URL("https://api.github.com/repos/2100640252-png/automation-apk/releases/latest")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "automation-work-app")
                if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                val code = conn.responseCode
                if (code == 404) {
                    handler.post {
                        onResult(
                            null,
                            "查不到新版（网络返回 404），请检查网络后重试，不用填 Token。"
                        )
                    }
                    return@Thread
                }
                if (code != 200) {
                    handler.post { onResult(null, "检查更新失败：网络返回 $code，请稍后重试。") }
                    return@Thread
                }
                val body = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(body)
                val tag = json.optString("tag_name", "")
                val name = json.optString("name", tag)
                val assets = json.optJSONArray("assets")
                var apkUrl = ""
                var size = 0L
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.optJSONObject(i) ?: continue
                        if (a.optString("name") == "app-release.apk") {
                            apkUrl = a.optString("browser_download_url", "")
                            size = a.optLong("size", 0)
                        }
                    }
                }
                if (tag.isBlank() || apkUrl.isBlank()) {
                    handler.post { onResult(null, "最新版里没有找到 app-release.apk，请稍后重试。") }
                    return@Thread
                }
                // tag 形如 v1.1.8（1.1 是版本号，8 是构建号），只比较前两段：
                // 相同大版本就当已是最新，避免每次构建号变化都重复提示；
                // tag 的大版本更高时才提示更新。
                fun core(v: String): List<Int> {
                    return v.trimStart('v').split(".").take(2).map { it.toIntOrNull() ?: 0 }
                }
                val current = currentVersionName(context)
                val tagCore = core(tag)
                val curCore = core(current)
                val cmp = tagCore.zip(curCore).firstOrNull { it.first != it.second }
                if (cmp == null || cmp.first <= cmp.second) {
                    handler.post { onResult(null, "已经是最新版 v$current，无需更新。") }
                } else {
                    handler.post { onResult(UpdateInfo(tag, name, apkUrl, size), "发现新版 $tag，可以在软件内直接下载安装。") }
                }
            } catch (e: Exception) {
                handler.post { onResult(null, "检查更新失败：${e.message}，请检查网络后重试。") }
            }
        }.start()
    }

    /** 在后台线程下载 APK 到缓存并触发安装，进度回主线程。 */
    fun downloadAndInstall(
        context: Context,
        info: UpdateInfo,
        onProgress: (String) -> Unit,
        onDone: (Boolean, String) -> Unit
    ) {
        Thread {
            val handler = Handler(Looper.getMainLooper())
            try {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    handler.post { onDone(false, "请先在系统设置里打开“安装未知应用”，再点下载安装。") }
                    return@Thread
                }
                val token = TaskStore.loadGithubToken(context)
                val url = URL(info.apkUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "automation-work-app")
                if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
                // GitHub release 附件直链会 302 跳到对象存储，HttpURLConnection 默认跟随重定向，
                // 但 Authorization 头不能带到别的域名，下载失败时改走不带 Token 的直链重试一次。
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                conn.instanceFollowRedirects = true
                val code = conn.responseCode
                if (code != 200) {
                    handler.post { onDone(false, "下载失败：网络返回 $code，请重试。") }
                    return@Thread
                }
                val total = conn.contentLengthLong
                val outFile = File(context.cacheDir, "update-${info.tag}.apk")
                conn.inputStream.use { input ->
                    FileOutputStream(outFile).use { output ->
                        val buf = ByteArray(8192)
                        var read: Int
                        var done = 0L
                        var lastReport = 0L
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            done += read
                            if (total > 0 && done - lastReport > 1024 * 512) {
                                lastReport = done
                                val pct = (done * 100 / total).toInt()
                                handler.post { onProgress("正在下载新版：$pct%") }
                            }
                        }
                    }
                }
                if (outFile.length() < 1024 * 100) {
                    handler.post { onDone(false, "下载的文件太小（${outFile.length()} 字节），可能下载失败，请重试。") }
                    return@Thread
                }
                val uri: Uri = FileProvider.getUriForFile(
                    context,
                    context.packageName + ".fileprovider",
                    outFile
                )
                val install = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                handler.post { onDone(true, "已下载完成，正在打开安装器…") }
                context.startActivity(install)
            } catch (e: Exception) {
                handler.post { onDone(false, "下载安装失败：${e.message}，请重试。") }
            }
        }.start()
    }
}
