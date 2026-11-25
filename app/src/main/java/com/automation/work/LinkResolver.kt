package com.automation.work

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL

/**
 * 链接解析器（v1.8 重写）：把用户贴的视频链接变成能直接打开对应 App 的链接。
 *
 * 之前只开浏览器的两个原因（v1.8 修掉）：
 *  1. 旧代码在主线程做网络跳转解析，安卓禁止主线程联网，异常被吞掉，
 *     短链永远解析不出真实地址，只能拿短链去试；
 *  2. 平台 App 没接住链接时，旧代码退回“通用方式”打开——那就是浏览器，
 *     还报“已打开”，浏览器里没有评论框，任务白跑。
 *
 * 现在的做法：
 *  1. 解析必须在后台线程做（调用方负责），短链跟随跳转拿到真实地址；
 *  2. 按平台提取视频 id，依次尝试：平台深链 -> 平台 https 链接 -> 原链接，
 *     全部强制 setPackage 对应平台，逐个用 resolveActivity 确认有人接；
 *  3. 绝不退回浏览器。打不开就返回失败，并写明卡在哪一步：
 *     识别不了平台 / 没装该 App / App 不接这个链接。
 */
object LinkResolver {

    data class ResolvedLink(
        val openUri: Uri,
        val packageName: String?,
        val note: String
    )

    data class OpenResult(
        val success: Boolean,
        val platform: String,
        val packageName: String?,
        val method: String,
        val reason: String
    )

    private data class Detailed(
        val platform: String,
        val packageName: String,
        val id: String?,
        val finalUrl: String,
        val candidates: List<Pair<String, Uri>>
    )

    private val douyinId = Regex("(?:video/|aweme/detail/|share/video/|modal_id=|aweme_id=)(\\d{8,})")
    private val kuaishouId = Regex("(?:short-video/|photoId=|workId=|/fw/photo/|/photo/)([A-Za-z0-9_-]{6,})")
    private val xhsId = Regex("(?:explore/|discovery/item/|/item/)([0-9a-fA-F]{16,})")
    private val biliId = Regex("(BV[0-9A-Za-z]{10}|av\\d+)")
    private val youtubeId = Regex("(?:youtube\\.com/watch\\?v=|youtu\\.be/|youtube\\.com/shorts/)([A-Za-z0-9_-]{6,})")
    private val weiboId = Regex("(?:status/|detail/|mblogid=)([A-Za-z0-9]+)")
    private val tiktokId = Regex("video/(\\d{8,})")

    fun normalize(link: String): String {
        val t = link.trim()
        return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
    }

    /** 跟随跳转拿真实地址（不登录，只读公开跳转头），失败就用原链接。必须在后台线程调用。 */
    fun resolveFinalUrl(link: String): String {
        return try {
            var url = URL(normalize(link))
            var hops = 0
            while (hops < 8) {
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
            normalize(link)
        }
    }

    private fun detect(link: String): Detailed? {
        val original = normalize(link)
        val finalUrl = resolveFinalUrl(original)
        val both = "$original\n$finalUrl"
        fun has(vararg keys: String) = keys.any { both.contains(it, ignoreCase = true) }
        return when {
            has("douyin.com", "iesdouyin.com", "snssdk1128") -> {
                val id = douyinId.find(both)?.groupValues?.getOrNull(1)
                val c = mutableListOf<Pair<String, Uri>>()
                if (id != null) {
                    c.add("抖音深链" to Uri.parse("snssdk1128://aweme/detail/$id"))
                    c.add("抖音网页链接" to Uri.parse("https://www.douyin.com/video/$id"))
                }
                c.add("解析后的链接" to Uri.parse(finalUrl))
                Detailed("抖音", "com.ss.android.ugc.aweme", id, finalUrl, c)
            }
            has("kuaishou.com", "v.kuaishou", "kwai") -> {
                val id = kuaishouId.find(both)?.groupValues?.getOrNull(1)
                val c = mutableListOf<Pair<String, Uri>>()
                if (id != null) {
                    c.add("快手深链" to Uri.parse("kwai://work/$id"))
                    c.add("快手深链2" to Uri.parse("kwai://photo/$id"))
                    c.add("快手网页链接" to Uri.parse("https://www.kuaishou.com/short-video/$id"))
                }
                c.add("解析后的链接" to Uri.parse(finalUrl))
                c.add("原始链接" to Uri.parse(original))
                Detailed("快手", "com.smile.gifmaker", id, finalUrl, c)
            }
            has("xiaohongshu.com", "xhslink.com", "xhsdiscover") -> {
                val id = xhsId.find(both)?.groupValues?.getOrNull(1)
                val c = mutableListOf<Pair<String, Uri>>()
                if (id != null) {
                    c.add("小红书深链" to Uri.parse("xhsdiscover://item/$id"))
                    c.add("小红书网页链接" to Uri.parse("https://www.xiaohongshu.com/explore/$id"))
                }
                c.add("解析后的链接" to Uri.parse(finalUrl))
                Detailed("小红书", "com.xingin.xhs", id, finalUrl, c)
            }
            has("bilibili.com", "b23.tv") -> {
                val id = biliId.find(both)?.groupValues?.getOrNull(1)
                val c = mutableListOf<Pair<String, Uri>>()
                if (id != null) {
                    c.add("B站深链" to Uri.parse("bilibili://video/$id"))
                    c.add("B站网页链接" to Uri.parse("https://www.bilibili.com/video/$id"))
                }
                c.add("解析后的链接" to Uri.parse(finalUrl))
                Detailed("哔哩哔哩", "com.bilibili.app.in", id, finalUrl, c)
            }
            has("youtube.com", "youtu.be") -> {
                val id = youtubeId.find(both)?.groupValues?.getOrNull(1)
                val c = mutableListOf<Pair<String, Uri>>()
                if (id != null) {
                    c.add("YouTube 深链" to Uri.parse("vnd.youtube:$id"))
                    c.add("YouTube 网页链接" to Uri.parse("https://www.youtube.com/watch?v=$id"))
                }
                c.add("解析后的链接" to Uri.parse(finalUrl))
                Detailed("YouTube", "com.google.android.youtube", id, finalUrl, c)
            }
            has("weibo.com", "weibo.cn") -> {
                val id = weiboId.find(both)?.groupValues?.getOrNull(1)
                val c = mutableListOf<Pair<String, Uri>>()
                if (id != null) {
                    c.add("微博深链" to Uri.parse("sinaweibo://detail?mblogid=$id"))
                    c.add("微博网页链接" to Uri.parse("https://m.weibo.cn/status/$id"))
                }
                c.add("解析后的链接" to Uri.parse(finalUrl))
                Detailed("微博", "com.sina.weibo", id, finalUrl, c)
            }
            has("tiktok.com") -> {
                val id = tiktokId.find(both)?.groupValues?.getOrNull(1)
                val c = mutableListOf<Pair<String, Uri>>()
                if (id != null) c.add("TikTok 深链" to Uri.parse("snssdk1233://aweme/detail/$id"))
                c.add("解析后的链接" to Uri.parse(finalUrl))
                Detailed("TikTok", "com.zhiliaoapp.musically", id, finalUrl, c)
            }
            else -> null
        }
    }

    fun resolve(link: String): ResolvedLink {
        val d = detect(link)
        return if (d != null) {
            ResolvedLink(d.candidates.first().second, d.packageName, "${d.platform}链接，用${d.candidates.first().first}直开")
        } else {
            ResolvedLink(Uri.parse(normalize(link)), null, "识别不了平台")
        }
    }

    private fun isInstalled(context: Context, pkg: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 真打开一条视频链接，逐步诊断，绝不退回浏览器。
     * 必须在后台线程调用（里面有网络解析）。
     */
    fun openVideoLinkDetailed(context: Context, link: String): OpenResult {
        val d = detect(link)
        if (d == null) {
            val reason = "步骤1 识别平台失败：这个链接看不出属于哪个平台（不是抖音/快手/小红书/B站/YouTube/微博/TikTok 的视频链接）"
            TaskStore.appendLog(context, reason)
            return OpenResult(false, "未知", null, "", reason)
        }
        TaskStore.appendLog(
            context,
            "步骤1 识别平台：${d.platform}${if (d.id != null) "，视频 id ${d.id}" else "（没提取到视频 id，用解析后的链接试）"}"
        )
        if (!isInstalled(context, d.packageName)) {
            val reason = "步骤2 检查 App 失败：手机没装${d.platform}（${d.packageName}），请先安装并登录"
            TaskStore.appendLog(context, reason)
            return OpenResult(false, d.platform, d.packageName, "", reason)
        }
        TaskStore.appendLog(context, "步骤2 检查 App：${d.platform} 已安装")
        val pm = context.packageManager
        for ((method, uri) in d.candidates) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    setPackage(d.packageName)
                }
                if (intent.resolveActivity(pm) == null) {
                    TaskStore.appendLog(context, "步骤3 尝试打开：$method 没人接，换下一种")
                    continue
                }
                context.startActivity(intent)
                TaskStore.appendLog(context, "步骤3 打开成功：用$method 打开${d.platform}")
                return OpenResult(true, d.platform, d.packageName, method, "已用$method 打开${d.platform}")
            } catch (e: Exception) {
                TaskStore.appendLog(context, "步骤3 尝试打开：$method 出错 ${e.message}，换下一种")
            }
        }
        val reason = "步骤3 打开失败：${d.platform} 已安装，但不接这个链接（深链和网页链接都没人接）。可能链接已失效，或该版本不支持直达"
        TaskStore.appendLog(context, reason)
        return OpenResult(false, d.platform, d.packageName, "", reason)
    }

    fun openVideoLink(context: Context, link: String): Boolean {
        return openVideoLinkDetailed(context, link).success
    }
}
