package com.automation.work

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * 平台响应器（v1.2 新增）：
 * 直接读手机上装了哪些平台应用，装了就显示“已安装”，点一下就真打开，
 * 不是只写个名字在界面上。
 *
 * 原理：PackageManager 查包名，getLaunchIntentForPackage 真启动。
 * 需要 Manifest 里声明 <queries>（见 AndroidManifest.xml），
 * 否则 Android 11+ 查不到其他应用。
 */
data class PlatformApp(
    val name: String,
    val packageName: String,
    val installed: Boolean,
    val versionName: String
)

object AppDetector {
    val knownPlatforms = listOf(
        "抖音" to "com.ss.android.ugc.aweme",
        "快手" to "com.smile.gifmaker",
        "小红书" to "com.xingin.xhs",
        "视频号（微信）" to "com.tencent.mm",
        "哔哩哔哩" to "com.bilibili.app.in",
        "TikTok" to "com.zhiliaoapp.musically",
        "YouTube" to "com.google.android.youtube",
        "微博" to "com.sina.weibo"
    )

    fun detect(context: Context): List<PlatformApp> {
        val pm = context.packageManager
        return knownPlatforms.map { (name, pkg) ->
            var installed = false
            var version = ""
            try {
                val info = pm.getPackageInfo(pkg, 0)
                installed = true
                version = info.versionName ?: ""
            } catch (e: PackageManager.NameNotFoundException) {
                installed = false
            }
            PlatformApp(name, pkg, installed, version)
        }
    }

    /** 真打开一个已安装的平台应用，没装返回 false。 */
    fun openApp(context: Context, packageName: String): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            TaskStore.appendLog(context, "已打开应用：$packageName")
            true
        } catch (e: Exception) {
            TaskStore.appendLog(context, "打开应用失败 $packageName：${e.message}")
            false
        }
    }

    /** 真打开一条视频链接（用系统按链接打开对应平台应用）。 */
    fun openLink(context: Context, link: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            TaskStore.appendLog(context, "已打开链接：$link")
            true
        } catch (e: Exception) {
            TaskStore.appendLog(context, "打开链接失败：${e.message}")
            false
        }
    }

    /**
     * 真发布视频：把视频用 ACTION_SEND 交给抖音的发布流程，
     * 抖音会打开它自己的发布页，视频已经选好，文案由用户在抖音里确认后点发布。
     * 这是安卓上正规的跨应用发视频方式，不绕过抖音的登录和发布页。
     */
    fun openDouyinPublish(context: Context, videoPath: String, copyText: String): Boolean {
        return try {
            val file = File(videoPath)
            val uri: Uri = FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/*"
                setPackage("com.ss.android.ugc.aweme")
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, copyText)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            TaskStore.appendLog(context, "已把视频交给抖音发布页：${file.name}")
            true
        } catch (e: Exception) {
            TaskStore.appendLog(context, "打开抖音发布页失败：${e.message}，请确认手机装了抖音。")
            false
        }
    }
}
