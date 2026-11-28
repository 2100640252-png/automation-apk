package com.automation.work

/**
 * 内置平台注册表：平台选择、包名路由和搜索入口提示都从这里取。
 * 选择平台后，搜索辅助和话题找素材必须路由到同一个包名；不支持的平台明确失败，不静默无操作。
 */
object PlatformRegistry {

    data class PlatformInfo(
        val label: String,
        val packageName: String,
        val supported: Boolean = true,
        val unsupportedReason: String = "",
        val aliases: List<String> = emptyList(),
        val searchTexts: List<String> = listOf("搜索", "Search"),
        val searchDescriptions: List<String> = listOf("搜索", "Search"),
        val searchViewIdHints: List<String> = listOf("search"),
        val metricHints: List<String> = emptyList(),
        val timeHints: List<String> = emptyList(),
        val publicWebSupported: Boolean = false,
        val publicWebReason: String = "公开网页结果不稳定或有登录墙，无法可靠采集"
    )

    val platforms: List<PlatformInfo> = listOf(
        PlatformInfo(
            label = "抖音",
            packageName = "com.ss.android.ugc.aweme",
            searchTexts = listOf("搜索", "Search"),
            searchDescriptions = listOf("搜索", "Search"),
            searchViewIdHints = listOf("search", "et_search", "search_input"),
            metricHints = listOf("点赞", "赞", "评论", "分享", "收藏", "播放"),
            timeHints = listOf("分钟前", "小时前", "昨天", "今天")
        ),
        PlatformInfo(
            label = "快手",
            packageName = "com.smile.gifmaker",
            searchTexts = listOf("搜索", "Search"),
            searchDescriptions = listOf("搜索", "Search"),
            searchViewIdHints = listOf("search", "search_input", "et_search"),
            metricHints = listOf("点赞", "赞", "评论", "分享", "收藏", "播放"),
            timeHints = listOf("分钟前", "小时前", "昨天", "今天")
        ),
        PlatformInfo(
            label = "小红书",
            packageName = "com.xingin.xhs",
            searchTexts = listOf("搜索", "Search"),
            searchDescriptions = listOf("搜索", "Search"),
            searchViewIdHints = listOf("search", "search_input", "et_search"),
            metricHints = listOf("点赞", "赞", "收藏", "评论", "笔记"),
            timeHints = listOf("分钟前", "小时前", "昨天", "今天")
        ),
        PlatformInfo(
            label = "B站",
            packageName = "com.bilibili.app.in",
            aliases = listOf("哔哩哔哩", "Bilibili", "bilibili"),
            searchTexts = listOf("搜索", "Search"),
            searchDescriptions = listOf("搜索", "Search"),
            searchViewIdHints = listOf("search", "search_input", "et_search"),
            metricHints = listOf("播放", "弹幕", "点赞", "投币", "收藏", "评论"),
            timeHints = listOf("分钟前", "小时前", "昨天", "今天")
        ),
        PlatformInfo(
            label = "YouTube",
            packageName = "com.google.android.youtube",
            aliases = listOf("youtube", "youtu.be"),
            searchTexts = listOf("Search", "搜索", "Search YouTube"),
            searchDescriptions = listOf("Search", "搜索", "Search YouTube", "Voice search"),
            searchViewIdHints = listOf("search", "search_edit_text", "search_box", "menu_item_search", "search_button"),
            metricHints = listOf("views", "view", "likes", "like", "comments", "观看", "播放", "点赞", "评论"),
            timeHints = listOf("minutes ago", "hours ago", "days ago", "yesterday", "分钟前", "小时前", "昨天"),
            publicWebSupported = true,
            publicWebReason = "公开网页只采集公开可见结果，可能不完整"
        ),
        PlatformInfo(
            label = "微博",
            packageName = "com.sina.weibo",
            searchTexts = listOf("搜索", "搜微博", "Search"),
            searchDescriptions = listOf("搜索", "搜微博", "Search"),
            searchViewIdHints = listOf("search", "search_input", "et_search"),
            metricHints = listOf("转发", "评论", "赞", "点赞", "阅读"),
            timeHints = listOf("分钟前", "小时前", "昨天", "今天")
        ),
        PlatformInfo(
            label = "TikTok",
            packageName = "com.zhiliaoapp.musically",
            aliases = listOf("tiktok"),
            searchTexts = listOf("Search", "搜索"),
            searchDescriptions = listOf("Search", "搜索", "Discover"),
            searchViewIdHints = listOf("search", "search_input", "discover"),
            metricHints = listOf("likes", "like", "comments", "shares", "views", "点赞", "评论", "分享"),
            timeHints = listOf("minutes ago", "hours ago", "days ago", "yesterday", "分钟前", "小时前", "昨天")
        ),
        PlatformInfo(
            label = "X/推特",
            packageName = "com.twitter.android",
            aliases = listOf("X", "Twitter", "推特", "x.com", "twitter.com"),
            searchTexts = listOf("Search", "搜索", "Search X"),
            searchDescriptions = listOf("Search", "搜索", "Search X", "Explore"),
            searchViewIdHints = listOf("search", "search_box", "search_input", "query"),
            metricHints = listOf("likes", "like", "reposts", "repost", "comments", "views", "点赞", "转发", "评论", "观看"),
            timeHints = listOf("minutes ago", "hours ago", "days ago", "yesterday", "分钟前", "小时前", "昨天"),
            publicWebSupported = false,
            publicWebReason = "X/推特公开网页有登录墙，无法可靠采集"
        ),
        PlatformInfo(
            label = "微信/视频号",
            packageName = "com.tencent.mm",
            supported = false,
            unsupportedReason = "微信/视频号没有可稳定直达的公开搜索入口，当前版本不支持话题找素材搜索",
            aliases = listOf("微信", "视频号", "WeChat"),
            searchTexts = emptyList(),
            searchDescriptions = emptyList(),
            searchViewIdHints = emptyList()
        )
    )

    val labels: List<String> = platforms.map { it.label }

    fun find(platform: String): PlatformInfo? {
        val clean = platform.trim()
        if (clean.isBlank()) return null
        return platforms.firstOrNull { info ->
            info.label == clean || info.packageName == clean || info.aliases.any { it.equals(clean, ignoreCase = true) }
        }
    }

    fun canonicalLabel(platform: String): String = find(platform)?.label ?: platform.trim().ifBlank { "抖音" }

    fun packageFor(platform: String): String = find(platform)?.packageName ?: ""

    fun isSupported(platform: String): Boolean = find(platform)?.supported == true

    fun unsupportedReason(platform: String): String {
        val info = find(platform) ?: return "未内置平台：$platform"
        return if (info.supported) "" else info.unsupportedReason
    }

    fun routeSummary(platform: String): String {
        val info = find(platform) ?: return "平台路由失败：未内置平台 $platform"
        return if (info.supported) {
            "平台路由：${info.label} -> ${info.packageName}"
        } else {
            "平台不支持：${info.label} -> ${info.packageName}；${info.unsupportedReason}"
        }
    }
}
