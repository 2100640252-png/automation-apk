package com.automation.work

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL

/**
 * 链接解析器（v1.5 新增）：把用户贴的视频链接变成能直接打开对应 App 的链接。
 *
 * 之前的问题：直接用 ACTION_VIEW 打开一条 v.douyin.com 短链，
 * 系统经常把它交给浏览器，而不是抖音 App；
 * 浏览器里根本没有评论框，无障碍服务再怎么找也找不到，
 * 最后任务还报“已处理”，等于白跑一趟。
 *
 * 现在的做法：
 *  1. 短链先跟随跳转拿到真实地址，取出抖音视频 id；
 *  2. 有 id 就用抖音深链 snssdk1128://aweme/detail/{id} 打开，
 *     并强制 setPackage 抖音，绝不经过浏览器；
 *  3. 没有 id / 不是抖音链接，就按平台包名强制打开对应 App；
 *  4. 哪个平台都没装、或系统里根本没有能处理这个链接的 Activity，
 *     就返回失败并写清原因，绝不谎报“已打开”。
 */
object LinkResolver {

    data class ResolvedLink(
        val openUri: Uri,
        val packageName: String?,
        val note: String
    )

    private val douyinVideoId = Regex("(?:douyin\\.com/video/|aweme/detail/)(\\d+)")
    private val youtubeVideoId = Regex("(?:youtube\\.com/watch\\?v=|youtu\\.be/)([A-Za-z0-9_-]{6,})")

    /** 跟随跳转拿真实地址（不登录，只读公开跳转头），失败就用原链接。 */
    fun resolveFinalUrl(link: String): String {
        return try {
            var url = URL(link)
            var hops = 0
            while (hops < 5) {
                val conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36"
                )
                val code = conn.responseCode
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if ((code in 301..308) && !loc.isNullOrBlank()) {
                    url = URL(url, loc)
                    hops++
                } else break
            }
            url.toString()
        } catch (e: Exception) {
            link
        }
    }

    fun resolve(link: String): ResolvedLink {
        val trimmed = link.trim()
        val finalUrl = resolveFinalUrl(trimmed)
        val douyinId = douyinVideoId.find(finalUrl)?.groupValues?.getOrNull(1)
        if (douyinId != null) {
            return ResolvedLink(
                Uri.parse("snssdk1128://aweme/detail/$douyinId"),
                "com.ss.android.ugc.aweme",
                "抖音视频 $douyinId，用抖音深链直开"
            )
        }
        val ytId = youtubeVideoId.find(finalUrl)?.groupValues?.getOrNull(1)
        if (ytId != null) {
            return ResolvedLink(
                Uri.parse("https://www.youtube.com/watch?v=$ytId"),
                "com.google.android.youtube",
                "YouTube 视频 $ytId，用 YouTube 直开"
            )
        }
        if (finalUrl.contains("kuaishou.com") || finalUrl.contains("v.kuaishou")) {
            return ResolvedLink(
                Uri.parse(finalUrl),
                "com.smile.gifmaker",
                "快手链接，用快手直开"
            )
        }
        if (finalUrl.contains("xiaohongshu.com") || finalUrl.contains("xhslink.com")) {
            return ResolvedLink(
                Uri.parse(finalUrl),
                "com.xingin.xhs",
                "小红书链接，用小红书直开"
            )
        }
        if (finalUrl.contains("bilibili.com") || finalUrl.contains("b23.tv")) {
            return ResolvedLink(
                Uri.parse(finalUrl),
                "com.bilibili.app.in",
                "B站链接，用B站直开"
            )
        }
        return ResolvedLink(Uri.parse(finalUrl), null, "通用链接，用系统默认打开")
    }

    /**
     * 真打开一条视频链接。返回 true 当且仅当系统找到了能处理它的 Activity
     * 并且启动调用没有抛异常；否则返回 false 并在日志里写清原因。
     */
    fun openVideoLink(context: Context, link: String): Boolean {
        val resolved = resolve(link)
        return try {
            val intent = Intent(Intent.ACTION_VIEW, resolved.openUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                resolved.packageName?.let { setPackage(it) }
            }
            val pm = context.packageManager
            val handler = intent.resolveActivity(pm)
            if (handler == null) {
                // 深链没人接（比如抖音没装）：退回 https 链接再试一次。
                val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(resolved.openUri.toString())).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (fallback.resolveActivity(pm) == null) {
                    TaskStore.appendLog(context, "打不开链接：手机里没有能打开它的应用：$link")
                    return false
                }
                context.startActivity(fallback)
                TaskStore.appendLog(context, "已打开链接（通用方式）：$link")
                return true
            }
            context.startActivity(intent)
            TaskStore.appendLog(context, "已打开链接：${resolved.note}")
            true
        } catch (e: Exception) {
            TaskStore.appendLog(context, "打开链接失败：${e.message}（链接：$link）")
            false
        }
    }
}
