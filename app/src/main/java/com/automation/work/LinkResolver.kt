package com.automation.work

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内链接直达：不打开浏览器解析或落地。
 *
 * 做法：先从用户输入里提取链接并规范化；短链在应用内手动跟随重定向，
 * 拿到最终 URL、host 和平台 ID；再用最终 URL 构建 ACTION_VIEW，
 * 查询可接管的应用，优先已知非浏览器目标包名并 setPackage。
 * 如果只有浏览器能打开，直接失败并写明 host/finalUrl，不进入浏览器。
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
        val reason: String,
        val targetKey: String = ""
    )

    private data class TargetInfo(
        val platform: String,
        val packageName: String,
        val id: String?,
        val deepLinks: List<Uri>
    )

    private val browserPackages = setOf(
        "com.android.chrome", "com.android.browser", "org.mozilla.firefox",
        "com.microsoft.emmx", "com.opera.browser", "com.heytap.browser",
        "com.coloros.browser", "com.vivo.browser", "com.mi.globalbrowser",
        "com.sec.android.app.sbrowser", "com.brave.browser", "com.duckduckgo.mobile.android"
    )

    private val urlRegex = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
    private val douyinId = Regex("(?:video/|aweme/detail/|share/video/|modal_id=|aweme_id=)(\\d{8,})")
    private val kuaishouId = Regex("(?:short-video/|photoId=|workId=|/fw/photo/|/photo/)([A-Za-z0-9_-]{6,})")
    private val xhsId = Regex("(?:explore/|discovery/item/|/item/)([0-9a-fA-F]{16,})")
    private val biliId = Regex("(BV[0-9A-Za-z]{10}|av\\d+)")
    private val youtubeId = Regex("(?:youtube\\.com/watch\\?v=|youtu\\.be/|youtube\\.com/shorts/)([A-Za-z0-9_-]{6,})")
    private val weiboId = Regex("(?:status/|detail/|mblogid=)([A-Za-z0-9]+)")
    private val tiktokId = Regex("video/(\\d{8,})")
    private val xId = Regex("(?:status/|statuses/)(\\d{6,})")

    fun normalize(link: String): String {
        val raw = link.trim()
        val found = urlRegex.find(raw)?.value?.trimEnd('。', '，', ',', '）', ')', ']', '】')
        val t = (found ?: raw).trim()
        return if (t.startsWith("http://", ignoreCase = true) || t.startsWith("https://", ignoreCase = true)) {
            t
        } else {
            "https://$t"
        }
    }

    /** 手动跟随重定向拿最终 URL（不登录，只读公开跳转头）。必须在后台线程调用。 */
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
        } catch (_: Exception) {
            normalize(link)
        }
    }

    private fun detectKnown(original: String, finalUrl: String): TargetInfo? {
        val both = "$original\n$finalUrl"
        fun has(vararg keys: String) = keys.any { both.contains(it, ignoreCase = true) }
        return when {
            has("douyin.com", "iesdouyin.com", "snssdk1128") -> {
                val id = douyinId.find(both)?.groupValues?.getOrNull(1)
                val deep = mutableListOf<Uri>()
                if (id != null) {
                    deep.add(Uri.parse("snssdk1128://aweme/detail/$id"))
                    deep.add(Uri.parse("https://www.douyin.com/video/$id"))
                }
                TargetInfo("抖音", "com.ss.android.ugc.aweme", id, deep)
            }
            has("kuaishou.com", "v.kuaishou", "kwai") -> {
                val id = kuaishouId.find(both)?.groupValues?.getOrNull(1)
                val deep = mutableListOf<Uri>()
                if (id != null) {
                    deep.add(Uri.parse("kwai://work/$id"))
                    deep.add(Uri.parse("kwai://photo/$id"))
                    deep.add(Uri.parse("https://www.kuaishou.com/short-video/$id"))
                }
                TargetInfo("快手", "com.smile.gifmaker", id, deep)
            }
            has("xiaohongshu.com", "xhslink.com", "xhsdiscover") -> {
                val id = xhsId.find(both)?.groupValues?.getOrNull(1)
                val deep = mutableListOf<Uri>()
                if (id != null) {
                    deep.add(Uri.parse("xhsdiscover://item/$id"))
                    deep.add(Uri.parse("https://www.xiaohongshu.com/explore/$id"))
                }
                TargetInfo("小红书", "com.xingin.xhs", id, deep)
            }
            has("bilibili.com", "b23.tv") -> {
                val id = biliId.find(both)?.groupValues?.getOrNull(1)
                val deep = mutableListOf<Uri>()
                if (id != null) {
                    deep.add(Uri.parse("bilibili://video/$id"))
                    deep.add(Uri.parse("https://www.bilibili.com/video/$id"))
                }
                TargetInfo("哔哩哔哩", "com.bilibili.app.in", id, deep)
            }
            has("youtube.com", "youtu.be") -> {
                val id = youtubeId.find(both)?.groupValues?.getOrNull(1)
                val deep = mutableListOf<Uri>()
                if (id != null) {
                    deep.add(Uri.parse("vnd.youtube:$id"))
                    deep.add(Uri.parse("https://www.youtube.com/watch?v=$id"))
                }
                TargetInfo("YouTube", "com.google.android.youtube", id, deep)
            }
            has("weibo.com", "weibo.cn") -> {
                val id = weiboId.find(both)?.groupValues?.getOrNull(1)
                val deep = mutableListOf<Uri>()
                if (id != null) {
                    deep.add(Uri.parse("sinaweibo://detail?mblogid=$id"))
                    deep.add(Uri.parse("https://m.weibo.cn/status/$id"))
                }
                TargetInfo("微博", "com.sina.weibo", id, deep)
            }
            has("tiktok.com") -> {
                val id = tiktokId.find(both)?.groupValues?.getOrNull(1)
                val deep = mutableListOf<Uri>()
                if (id != null) deep.add(Uri.parse("snssdk1233://aweme/detail/$id"))
                TargetInfo("TikTok", "com.zhiliaoapp.musically", id, deep)
            }
            has("x.com", "twitter.com") -> {
                val id = xId.find(both)?.groupValues?.getOrNull(1)
                val deep = mutableListOf<Uri>()
                if (id != null) deep.add(Uri.parse("https://x.com/i/status/$id"))
                TargetInfo("X/推特", "com.twitter.android", id, deep)
            }
            else -> null
        }
    }

    fun resolve(link: String): ResolvedLink {
        val normalized = normalize(link)
        val finalUrl = resolveFinalUrl(normalized)
        val target = detectKnown(normalized, finalUrl)
        return if (target != null) {
            val uri = target.deepLinks.firstOrNull() ?: Uri.parse(finalUrl)
            ResolvedLink(uri, target.packageName, "${target.platform}链接，应用内直达")
        } else {
            ResolvedLink(Uri.parse(finalUrl), null, "未识别平台，等待查询非浏览器接管应用")
        }
    }

    /**
     * 从可见文本里尽力提取真实链接/ID。只在文本明确暴露 URL 或平台 ID 时生成链接；
     * 没有暴露就返回 null，上层保存为“待补链接”，不编造 URL。
     */
    fun extractResearchLink(platform: String, text: String): String? {
        val clean = text.trim()
        if (clean.isBlank()) return null
        val url = urlRegex.find(clean)?.value?.trimEnd('。', '，', ',', '）', ')', ']', '】', '"', '\'')
        if (!url.isNullOrBlank()) {
            val target = detectKnown(url, url)
            if (target?.id != null) {
                return canonicalResearchLink(target.platform, target.id)
            }
            return normalize(url)
        }
        val id = when (platform) {
            "抖音" -> douyinId.find(clean)?.groupValues?.getOrNull(1)
            "快手" -> kuaishouId.find(clean)?.groupValues?.getOrNull(1)
            "小红书" -> xhsId.find(clean)?.groupValues?.getOrNull(1)
            "哔哩哔哩", "B站" -> biliId.find(clean)?.groupValues?.getOrNull(1)
            "X/推特" -> xId.find(clean)?.groupValues?.getOrNull(1)
            else -> null
        }
        return id?.let { canonicalResearchLink(platform, it) }
    }

    private fun canonicalResearchLink(platform: String, id: String): String? {
        val cleanId = id.trim()
        if (cleanId.isBlank()) return null
        return when (platform) {
            "抖音" -> "https://www.douyin.com/video/$cleanId"
            "快手" -> "https://www.kuaishou.com/short-video/$cleanId"
            "小红书" -> "https://www.xiaohongshu.com/explore/$cleanId"
            "哔哩哔哩", "B站" -> "https://www.bilibili.com/video/$cleanId"
            "X/推特" -> "https://x.com/i/status/$cleanId"
            else -> null
        }
    }

    private fun isInstalled(context: Context, pkg: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun queryResolverPackages(context: Context, uri: Uri): List<String> {
        val intent = Intent(Intent.ACTION_VIEW, uri)
        return try {
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNull { it.activityInfo?.packageName }
                .distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun tryOpenWithPackage(
        context: Context,
        uri: Uri,
        targetPackage: String,
        method: String,
        platform: String,
        targetKey: String = ""
    ): OpenResult? {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage(targetPackage)
        }
        val resolved = intent.resolveActivity(pm)
        if (resolved == null) {
            TaskStore.appendLog(context, "步骤3 尝试直达：$method 没人接（目标包名=$targetPackage，目标URI=$uri），换下一种")
            return null
        }
        return try {
            context.startActivity(intent)
            val actual = resolved.flattenToString()
            val reason = "步骤3 直达成功：VIEW intent 已发出，方式=$method，目标包名=$targetPackage，实际接管=$actual，目标URI=$uri"
            TaskStore.appendLog(context, reason)
            OpenResult(true, platform, targetPackage, method, reason, targetKey)
        } catch (e: Exception) {
            TaskStore.appendLog(context, "步骤3 尝试直达：$method 出错 ${e.javaClass.simpleName}: ${e.message}（目标包名=$targetPackage，目标URI=$uri），换下一种")
            null
        }
    }

    private fun browserOnlyFailure(context: Context, platform: String, packageName: String?, host: String, finalUrl: String, targetKey: String = ""): OpenResult {
        val reason = "直达失败：只有浏览器可打开 host=$host finalUrl=$finalUrl"
        TaskStore.appendLog(context, "步骤3 $reason；已停止，不进入浏览器。")
        return OpenResult(false, platform, packageName, "", reason, targetKey)
    }

    /**
     * 真打开一条链接：应用内解析、非浏览器直达、逐步诊断。
     * 必须在后台线程调用（里面有网络解析）。
     */
    fun openVideoLinkDetailed(context: Context, link: String): OpenResult {
        val normalized = normalize(link)
        if (normalized.isBlank() || normalized == "https://") {
            val reason = "步骤1 识别链接失败：链接为空"
            TaskStore.appendLog(context, reason)
            return OpenResult(false, "未知", null, "", reason)
        }
        val finalUrl = resolveFinalUrl(normalized)
        val host = Uri.parse(finalUrl).host ?: ""
        val target = detectKnown(normalized, finalUrl)
        val targetKey = if (target != null) {
            "${target.platform}|${target.id ?: finalUrl}"
        } else {
            "未知|$finalUrl"
        }
        TaskStore.appendLog(
            context,
            "步骤1 应用内解析：原始链接=$normalized，最终URL=$finalUrl，host=$host${if (target?.id != null) "，平台ID=${target.id}" else ""}，防重复键=$targetKey"
        )

        if (target != null) {
            TaskStore.appendLog(context, "步骤1 识别平台：${target.platform}，目标包名=${target.packageName}")
            if (!isInstalled(context, target.packageName)) {
                val reason = "步骤2 检查 App 失败：手机没装${target.platform}（${target.packageName}），请先安装并登录"
                TaskStore.appendLog(context, reason)
                return OpenResult(false, target.platform, target.packageName, "", reason, targetKey)
            }
            TaskStore.appendLog(context, "步骤2 检查 App：${target.platform} 已安装")

            val candidates = mutableListOf<Pair<String, Uri>>()
            target.deepLinks.forEach { candidates.add("包名深链" to it) }
            candidates.add("最终链接直达" to Uri.parse(finalUrl))
            if (normalized != finalUrl) candidates.add("原始链接直达" to Uri.parse(normalized))

            for ((method, uri) in candidates.distinctBy { it.second.toString() }) {
                val opened = tryOpenWithPackage(context, uri, target.packageName, method, target.platform, targetKey)
                if (opened != null) return opened
            }

            val resolvers = queryResolverPackages(context, Uri.parse(finalUrl))
            val nonBrowser = resolvers.filter { it !in browserPackages && it != context.packageName }
            if (nonBrowser.isEmpty()) {
                return browserOnlyFailure(context, target.platform, target.packageName, host, finalUrl, targetKey)
            }
            val reason = "直达失败：${target.platform} 不接这个链接 host=$host finalUrl=$finalUrl；非浏览器接管包名=${nonBrowser.joinToString(",")}，已停止，不进入浏览器"
            TaskStore.appendLog(context, "步骤3 $reason")
            return OpenResult(false, target.platform, target.packageName, "", reason, targetKey)
        }

        val resolvers = queryResolverPackages(context, Uri.parse(finalUrl))
        val nonBrowser = resolvers.filter { it !in browserPackages && it != context.packageName }
        if (nonBrowser.isEmpty()) {
            return if (resolvers.isNotEmpty()) {
                browserOnlyFailure(context, "未知", null, host, finalUrl, targetKey)
            } else {
                val reason = "直达失败：没有应用可打开 host=$host finalUrl=$finalUrl"
                TaskStore.appendLog(context, "步骤3 $reason；已停止，不进入浏览器。")
                OpenResult(false, "未知", null, "", reason, targetKey)
            }
        }
        val targetPackage = nonBrowser.first()
        TaskStore.appendLog(context, "步骤2 通用直达：最终URL 有非浏览器应用可接管，优先包名=$targetPackage，全部非浏览器包名=${nonBrowser.joinToString(",")}")
        val genericKey = "$targetPackage|$finalUrl"
        val opened = tryOpenWithPackage(context, Uri.parse(finalUrl), targetPackage, "最终链接直达", "直达应用", genericKey)
        if (opened != null) return opened
        val reason = "直达失败：非浏览器应用 $targetPackage 没接住最终链接 host=$host finalUrl=$finalUrl"
        TaskStore.appendLog(context, "步骤3 $reason；已停止，不进入浏览器。")
        return OpenResult(false, "直达应用", targetPackage, "", reason, genericKey)
    }

    fun openVideoLink(context: Context, link: String): Boolean {
        return openVideoLinkDetailed(context, link).success
    }
}
