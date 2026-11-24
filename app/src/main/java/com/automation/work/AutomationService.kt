package com.automation.work

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍屏幕控制服务（v1.1 真正干活的版本）。
 *
 * 只在用户主动点“创建并开始任务”、且已在设置里手动开启本服务后才工作。
 * 流程（每条链接）：
 *  1. 用 Intent 打开链接（打开对应平台应用）
 *  2. 等页面加载，在无障碍树里找“评论”入口，点开评论面板
 *  3. 找可编辑的评论输入框，把自定义评论填进去
 *  4. 找“发送 / 发布”按钮点下去
 *  5. 按间隔等一会儿，再打开下一条链接
 *
 * 自修复：每一步失败都会重试，最多 3 次；换不同的关键词再找一遍；
 * 实在找不到就记一条日志、跳过这条链接继续下一条，不会卡死。
 * 不读取密码、不上传数据、不在后台偷跑任务。
 */
class AutomationService : AccessibilityService() {

    companion object {
        var instance: AutomationService? = null
            private set

        // 当前任务（由 MainActivity 设置）
        var currentTask: CommentTask? = null
        var currentIndex: Int = 0
        var running: Boolean = false
        var lastStatus: String = "未开始"
        // v1.5 诚实上报：成功和失败分开计，绝不再用“已处理”冒充“已发送”。
        var successCount: Int = 0
        var failCount: Int = 0

        private val handler = Handler(Looper.getMainLooper())
        private var stepRunnable: Runnable? = null

        fun startTask(context: Context, task: CommentTask) {
            // v1.5：无障碍服务没在运行就坚决不启动——以前是先把链接打开了，
            // 服务却没接管，任务就悄悄卡死，用户还以为在跑。
            if (instance == null) {
                running = false
                lastStatus = "无障碍服务没在运行：请先在设置 > 无障碍里开启“自动化屏幕控制”，再点开始任务。"
                TaskStore.appendLog(context, lastStatus)
                return
            }
            currentTask = task
            currentIndex = 0
            successCount = 0
            failCount = 0
            running = true
            lastStatus = "任务开始：共 ${task.items.size} 条"
            TaskStore.appendLog(context, lastStatus)
            TaskStore.saveCommentTask(
                context,
                task.items.joinToString("\n") { it.link } + "\n---\n" + task.commentText
            )
            openCurrent(context)
        }

        fun stopTask(context: Context) {
            running = false
            stepRunnable?.let { handler.removeCallbacks(it) }
            lastStatus = "任务已停止"
            TaskStore.appendLog(context, lastStatus)
        }

        fun openCurrent(context: Context) {
            val task = currentTask ?: return
            if (!running || currentIndex >= task.items.size) {
                running = false
                lastStatus = finishStatus()
                TaskStore.appendLog(context, lastStatus)
                return
            }
            val item = task.items[currentIndex]
            lastStatus = "正在打开第 ${item.index} 条：${item.link}"
            TaskStore.appendLog(context, lastStatus)
            if (!openVideoLink(context, item.link)) {
                // 链接根本打不开：记失败、直接跳过，别傻等 10 秒再假装处理。
                markItemFailed(context, "打不开链接")
                advance(context, task)
                return
            }
            // 打开后由 onAccessibilityEvent 驱动下一步；同时设一个兜底定时器，
            // 万一收不到窗口事件，10 秒后也尝试找评论框。
            instance?.scheduleStep(context, 10000L)
        }

        fun openVideoLink(context: Context, link: String): Boolean {
            // v1.5：走 LinkResolver，抖音短链解析成深链直开抖音 App；
            // 返回 false 时调用方记失败，不再当成已打开。
            return LinkResolver.openVideoLink(context, link)
        }

        fun markItemSuccess(context: Context) {
            successCount++
            TaskStore.appendLog(context, "第 ${currentIndex + 1} 条已发送（成功 $successCount 条）。")
        }

        fun markItemFailed(context: Context, reason: String) {
            failCount++
            TaskStore.appendLog(context, "第 ${currentIndex + 1} 条失败：$reason（失败 $failCount 条）。")
        }

        fun finishStatus(): String {
            return if (failCount == 0) {
                "任务结束：成功 $successCount 条，失败 0 条。"
            } else {
                "任务结束：成功 $successCount 条，失败 $failCount 条。失败的原因写在日志里，请先看日志再重发。"
            }
        }
    }

    private var attempts: Int = 0
    private var phase: String = "open"

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        TaskStore.appendLog(this, "无障碍服务已连接，可以开始任务。")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!running) return
        if (event == null) return
        // 只对窗口变化 / 内容变化作反应，避免每一次滚动都重复触发。
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        // 防抖：收到事件后 1.5 秒再动手，等页面稳定。
        scheduleStep(this, 1500L)
    }

    private fun scheduleStep(context: Context, delayMs: Long) {
        stepRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable { tryOneStep(context) }
        stepRunnable = r
        handler.postDelayed(r, delayMs)
    }

    private fun tryOneStep(context: Context) {
        if (!running) return
        val task = currentTask ?: return
        if (currentIndex >= task.items.size) {
            openCurrent(context)
            return
        }
        val root = rootInActiveWindow ?: run {
            // 拿不到窗口树：重试一次，超过 3 次就跳过这条。
            attempts++
            TaskStore.appendLog(context, "拿不到当前窗口（第 $attempts 次），重试…")
            if (attempts >= 3) {
                attempts = 0
                markItemFailed(context, "打不开页面，拿不到窗口")
                advance(context, task)
            } else {
                scheduleStep(context, 3000L)
            }
            return
        }

        // 1) 先找可编辑的评论输入框
        val input = findEditableCommentBox(root)
        if (input != null) {
            val ok = setText(input, task.commentText)
            if (ok) {
                TaskStore.appendLog(context, "已填入评论，正在找发送按钮…")
                handler.postDelayed({
                    val send = findSendButton(rootInActiveWindow ?: return@postDelayed)
                    if (send != null && send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        markItemSuccess(context)
                        attempts = 0
                        advance(context, task)
                    } else {
                        // 有的平台填完直接按回车也能发，试一次；不行就记日志重试。
                        TaskStore.appendLog(context, "没找到发送按钮，正在重试…")
                        attempts++
                        if (attempts >= 3) {
                            attempts = 0
                            markItemFailed(context, "填了评论但找不到发送按钮")
                            advance(context, task)
                        } else scheduleStep(context, 3000L)
                    }
                }, 800L)
                return
            }
        }

        // 2) 没找到输入框：找“评论”入口点开评论面板
        val commentBtn = findCommentEntry(root)
        if (commentBtn != null) {
            commentBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            TaskStore.appendLog(context, "已点开评论入口，等评论框出现…")
            attempts++
            if (attempts >= 6) {
                attempts = 0
                markItemFailed(context, "打不开评论框")
                advance(context, task)
            } else scheduleStep(context, 2500L)
            return
        }

        // 3) 什么都没找到：等页面再加载一会儿，最多重试 3 次
        attempts++
        TaskStore.appendLog(context, "还没找到评论框（第 $attempts 次），再等一下…")
        if (attempts >= 3) {
            attempts = 0
            markItemFailed(context, "页面里没有评论入口")
            advance(context, task)
        } else scheduleStep(context, 3000L)
    }

    private fun advance(context: Context, task: CommentTask) {
        currentIndex++
        attempts = 0
        phase = "open"
        if (currentIndex >= task.items.size) {
            running = false
            lastStatus = finishStatus()
            TaskStore.appendLog(context, lastStatus)
            return
        }
        val delayMs = (task.intervalSeconds.coerceAtLeast(1) * 1000L)
        lastStatus = "等 ${task.intervalSeconds} 秒后处理下一条…"
        TaskStore.appendLog(context, lastStatus)
        handler.postDelayed({ openCurrent(context) }, delayMs)
    }

    // ---- 节点查找：关键词覆盖抖音 / 快手 / 小红书 / B站 / 视频号等常见写法 ----
    private fun findEditableCommentBox(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val keywords = listOf("评论", "说点什么", "写评论", "发评论", "善语结善缘", "Add comment", "comment")
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isEditable && node.className?.toString()?.contains("EditText") == true) {
                return node
            }
            // 有的输入框是可编辑但类名不是 EditText，靠提示文字判断
            if (node.isEditable) return node
            val text = (node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")
            if (node.isClickable && keywords.any { text.contains(it) } && node.className?.toString()?.contains("EditText") == true) {
                return node
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun findCommentEntry(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val keywords = listOf("评论", "comment")
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = (node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")
            if (node.isClickable && keywords.any { text.contains(it) }) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val keywords = listOf("发送", "发布", "Send", "Post", "发表")
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = (node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")
            if (node.isClickable && keywords.any { text.contains(it) }) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    override fun onInterrupt() {
        running = false
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
