package com.automation.work

/**
 * 评论任务：一次最多 10 条视频链接。
 * - links: 10 个空白槽，空的自动跳过
 * - commentText: 可自定义的评论内容
 * - intervalSeconds: 每条之间的间隔，避免短时间内连续操作被平台限流
 * - status: 每条的状态，运行中实时更新
 */
data class CommentItem(
    val index: Int, // 1..10
    val link: String,
    val platform: String = CopyParser.detectPlatform(link),
    var status: String = "等待中"
)

data class CommentTask(
    val commentText: String,
    val intervalSeconds: Int = 10,
    val items: List<CommentItem>
) {
    companion object {
        const val MAX_LINKS = 10

        fun fromLinks(links: List<String>, commentText: String, intervalSeconds: Int = 10): CommentTask {
            val items = links.take(MAX_LINKS)
                .mapIndexedNotNull { i, raw ->
                    val link = raw.trim()
                    if (link.isEmpty()) null
                    else CommentItem(index = i + 1, link = link)
                }
            return CommentTask(commentText = commentText, intervalSeconds = intervalSeconds, items = items)
        }
    }
}
