package com.automation.work

/**
 * 评论任务：链接不限数量，按填写顺序依次处理。
 * - links: 用户依次填写的链接；遇到第一个空链接就截止，后面的不处理
 * - commentText: 可自定义的评论内容
 * - intervalSeconds: 每条之间的间隔，避免短时间内连续操作被平台限流
 * - status: 每条的状态，运行中实时更新
 */
data class CommentItem(
    val index: Int,
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
        fun fromLinks(links: List<String>, commentText: String, intervalSeconds: Int = 10): CommentTask {
            val items = mutableListOf<CommentItem>()
            for (raw in links) {
                val link = raw.trim()
                if (link.isEmpty()) break
                items.add(CommentItem(index = items.size + 1, link = link))
            }
            return CommentTask(commentText = commentText, intervalSeconds = intervalSeconds, items = items)
        }
    }
}
