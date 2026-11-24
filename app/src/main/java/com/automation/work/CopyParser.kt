package com.automation.work

/**
 * 文案识别：把整段文案拆成正文、#话题、声明。
 * 例如输入：
 *   今天海边日落很治愈 #日落 #海边
 *   声明：视频由 AI 生成
 * 会得到 body="今天海边日落很治愈"，topics=[日落, 海边]，statement="视频由 AI 生成"
 */
data class ParsedCopy(
    val body: String,
    val topics: List<String>,
    val statement: String
)

object CopyParser {
    private val topicRegex = Regex("#([\\u4e00-\\u9fa5A-Za-z0-9_]+)")
    private val statementRegex = Regex("(?m)^\\s*(声明|免责声明|Statement)[:：]\\s*(.+)$")

    fun parse(raw: String): ParsedCopy {
        val topics = topicRegex.findAll(raw).map { it.groupValues[1] }.toList()
        val statement = statementRegex.find(raw)?.groupValues?.getOrNull(2)?.trim().orEmpty()
        var body = raw
        body = topicRegex.replace(body, " ").trim()
        body = statementRegex.replace(body, "").trim()
        body = body.replace(Regex("\\s+"), " ").trim()
        return ParsedCopy(body = body, topics = topics, statement = statement)
    }

    fun detectPlatform(link: String): String {
        val l = link.lowercase()
        return when {
            "douyin.com" in l || "v.douyin" in l -> "抖音"
            "kuaishou" in l -> "快手"
            "xiaohongshu" in l || "xhslink" in l -> "小红书"
            "bilibili" in l || "b23.tv" in l -> "哔哩哔哩"
            "weixin" in l || "channels" in l -> "视频号"
            "tiktok" in l -> "TikTok"
            "youtube" in l || "youtu.be" in l -> "YouTube"
            "weibo" in l -> "微博"
            else -> "其他平台"
        }
    }
}
