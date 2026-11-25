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
            } catch (e: Exception) {
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
        // v1.5：走 LinkResolver，抖音短链解析成深链直开抖音 App，
        // 不再交给浏览器；打不开就返回 false 并写清原因。
        return LinkResolver.openVideoLink(context, link)
    }

    /** 各平台的包名：系统分享接视频时用 setPackage 直接交给它。 */
    val publishPackages = mapOf(
        "抖音" to "com.ss.android.ugc.aweme",
        "快手" to "com.smile.gifmaker",
        "小红书" to "com.xingin.xhs",
        "视频号" to "com.tencent.mm",
        "哔哩哔哩" to "com.bilibili.app.in",
        "TikTok" to "com.zhiliaoapp.musically",
        "YouTube" to "com.google.android.youtube",
        "微博" to "com.sina.weibo"
    )

    /**
     * 真发布视频到指定平台（v1.5）：把视频用系统分享（ACTION_SEND）直接交给
     * 该平台应用的发布页，视频已选好、文案已带上，用户在平台里点发布确认。
     * 这是安卓上正规的跨应用发视频方式，不绕过平台的登录和发布页——
     * 没有任何 App 能在不登录、不经过平台发布页的情况下悄悄把视频发上去，
     * 声称能做到的都是假的。
     * 返回 true 当且仅当系统确认该平台能接这个分享；否则返回 false，
     * 并在日志里写清是“没装”还是“该平台不接系统分享”，绝不谎报已发。
     */
    fun publishVideo(context: Context, platform: String, videoPath: String, copyText: String): Boolean {
        return publishVideoDetailed(context, platform, videoPath, copyText).first
    }

    /** 发视频逐步诊断版：返回 (是否已打开发布页, 原因)。 */
    fun publishVideoDetailed(context: Context, platform: String, videoPath: String, copyText: String): Pair<Boolean, String> {
        val pkg = publishPackages[platform]
            ?: return false to "发视频步骤1 失败：不支持的平台 $platform".also {
                TaskStore.appendLog(context, it)
            }
        return try {
            val file = File(videoPath)
            if (!file.exists() || file.length() == 0L) {
                val r = "发视频步骤1 失败：视频文件不存在或为空：${file.name}"
                TaskStore.appendLog(context, r)
                return false to r
            }
            TaskStore.appendLog(context, "发视频步骤1 检查视频：${file.name} 正常")
            val installed = try {
                context.packageManager.getPackageInfo(pkg, 0)
                true
            } catch (e: Exception) {
                false
            }
            if (!installed) {
                val r = "发视频步骤2 失败：手机没装$platform，请先安装并登录"
                TaskStore.appendLog(context, r)
                return false to r
            }
            TaskStore.appendLog(context, "发视频步骤2 检查 App：$platform 已安装")
            val uri: Uri = FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/*"
                setPackage(pkg)
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, copyText)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (intent.resolveActivity(context.packageManager) == null) {
                val r = "发视频步骤3 失败：$platform 不接系统视频分享（该版本没有分享入口），请手动打开 $platform 发布"
                TaskStore.appendLog(context, r)
                return false to r
            }
            TaskStore.appendLog(context, "发视频步骤3 检查分享入口：$platform 能接视频分享")
            context.startActivity(intent)
            TaskStore.appendLog(context, "发视频步骤4：已打开 $platform 发布页，视频已选好。")
            true to "已打开 $platform 发布页"
        } catch (e: Exception) {
            val r = "发视频失败：${e.message}"
            TaskStore.appendLog(context, r)
            false to r
        }
    }

    fun openDouyinPublish(context: Context, videoPath: String, copyText: String): Boolean {
        return publishVideo(context, "抖音", videoPath, copyText)
    }
}
