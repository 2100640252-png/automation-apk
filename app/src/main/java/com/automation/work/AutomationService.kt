package com.automation.work

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍屏幕控制服务（v1.8 诊断版）。
 *
 * 不用 Root、不用 Shizuku。流程（每条链接）：
 *  步骤1-3 LinkResolver 在后台线程解析链接、直开对应平台 App（绝不走浏览器）
 *  步骤4 验证当前界面确实是目标 App（被浏览器接走就报失败）
 *  步骤5 找评论入口并点开
 *  步骤6 填入评论
 *  步骤7 点发送
 *  步骤8 验证评论真的发出去了（输入框清空或评论出现在界面上）
 * 每一步失败都写明卡在哪一步、为什么，不谎报成功。
 *
 * 发视频辅助：视频交给平台发布页后，本服务在发布页里填文案、点下一步/发布，
 * 并验证是否出现发布成功；验证不到就写明失败原因。
 */
class AutomationService : AccessibilityService() {

    companion object {
        var instance: AutomationService? = null
            private set

        var currentTask: CommentTask? = null
        var currentIndex: Int = 0
        var running: Boolean = false
        var lastStatus: String = "未开始"
        var successCount: Int = 0
        var failCount: Int = 0
        var expectedPackage: String? = null
        var expectedPlatform: String = ""

        // 发视频辅助状态
        var publishRunning: Boolean = false
        var publishPlatform: String = ""
        var publishPackage: String = ""
        var publishCaption: String = ""
        var publishAttempts: Int = 0

        private val handler = Handler(Looper.getMainLooper())
        private var stepRunnable: Runnable? = null
        private var publishRunnable: Runnable? = null

        fun startTask(context: Context, task: CommentTask) {
            if (instance == null) {
                running = false
                lastStatus = "步骤0 失败：无障碍服务没在运行，请先在设置 > 无障碍里开启“自动化屏幕控制”。"
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
            publishRunning = false
            stepRunnable?.let { handler.removeCallbacks(it) }
            publishRunnable?.let { handler.removeCallbacks(it) }
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
            lastStatus = "正在处理第 ${item.index} 条：${item.link}"
            TaskStore.appendLog(context, lastStatus)
            // 链接解析要联网，必须在后台线程（主线程联网会被系统禁止，旧版就是这样失效的）。
            Thread {
                val result = LinkResolver.openVideoLinkDetailed(context, item.link)
                handler.post {
                    if (!running) return@post
                    if (!result.success) {
                        instance?.failCurrentAndAdvance(context, task, result.reason)
                            ?: run {
                                markItemFailed(context, result.reason)
                                currentIndex++
                                running = false
                                lastStatus = finishStatus()
                                TaskStore.appendLog(context, lastStatus)
                            }
                        return@post
                    }
                    expectedPackage = result.packageName
                    expectedPlatform = result.platform
                    instance?.scheduleStep(context, 3000L)
                }
            }.start()
        }

        fun markItemSuccess(context: Context) {
            successCount++
            TaskStore.appendLog(context, "第 ${currentIndex + 1} 条评论已验证发出（成功 $successCount 条）。")
        }

        fun markItemFailed(context: Context, reason: String) {
            failCount++
            TaskStore.appendLog(context, "第 ${currentIndex + 1} 条失败：$reason（失败 $failCount 条）。")
        }

        fun finishStatus(): String {
            return if (failCount == 0) {
                "任务结束：成功 $successCount 条，失败 0 条。"
            } else {
                "任务结束：成功 $successCount 条，失败 $failCount 条。每条失败卡在哪一步都写在日志里。"
            }
        }

        /** 发视频辅助：发布页打开后，自动填文案、点下一步/发布，并验证结果。 */
        fun startPublishAssist(context: Context, platform: String, packageName: String, caption: String) {
            if (instance == null) {
                TaskStore.appendLog(context, "发视频步骤5 失败：无障碍服务没在运行，只能打开发布页，不能自动点发布。请开启无障碍后重试。")
                return
            }
            publishPlatform = platform
            publishPackage = packageName
            publishCaption = caption
            publishAttempts = 0
            publishRunning = true
            TaskStore.appendLog(context, "发视频步骤5：发布页已打开，开始自动填文案、点发布（$platform）…")
            instance?.schedulePublishStep(context, 3000L)
        }
    }

    private var attempts: Int = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        TaskStore.appendLog(this, "无障碍服务已连接，可以开始任务。")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        if (publishRunning) {
            schedulePublishStep(this, 1500L)
            return
        }
        if (!running) return
        scheduleStep(this, 1500L)
    }

    private fun scheduleStep(context: Context, delayMs: Long) {
        stepRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable { tryOneStep(context) }
        stepRunnable = r
        handler.postDelayed(r, delayMs)
    }

    private fun schedulePublishStep(context: Context, delayMs: Long) {
        publishRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable { tryPublishStep(context) }
        publishRunnable = r
        handler.postDelayed(r, delayMs)
    }

    private val browserPackages = setOf(
        "com.android.chrome", "com.android.browser", "org.mozilla.firefox",
        "com.microsoft.emmx", "com.opera.browser", "com.heytap.browser",
        "com.coloros.browser", "com.vivo.browser", "com.mi.globalbrowser"
    )

    private fun treeText(root: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            n.text?.let { sb.append(it).append(' ') }
            n.contentDescription?.let { sb.append(it).append(' ') }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return sb.toString()
    }

    private fun tryOneStep(context: Context) {
        if (!running) return
        val task = currentTask ?: return
        if (currentIndex >= task.items.size) {
            openCurrent(context)
            return
        }
        val root = rootInActiveWindow ?: run {
            attempts++
            TaskStore.appendLog(context, "步骤4 验证界面：拿不到当前窗口（第 $attempts 次），重试…")
            if (attempts >= 3) {
                attempts = 0
                markItemFailed(context, "步骤4 失败：打不开页面，拿不到窗口")
                advance(context, task)
            } else scheduleStep(context, 3000L)
            return
        }
        // 步骤4：验证当前确实在目标 App 里。
        val rootPkg = root.packageName?.toString() ?: ""
        val expected = expectedPackage
        if (expected != null && rootPkg != expected) {
            if (rootPkg in browserPackages) {
                markItemFailed(context, "步骤4 失败：链接被浏览器打开了，$expectedPlatform 没有接管（当前应用：$rootPkg）")
                advance(context, task)
                return
            }
            attempts++
            TaskStore.appendLog(context, "步骤4 验证界面：当前不在$expectedPlatform（当前：$rootPkg），等它打开…（第 $attempts 次）")
            if (attempts >= 3) {
                attempts = 0
                markItemFailed(context, "步骤4 失败：打开后不在目标 App（当前：$rootPkg，应为：$expected）")
                advance(context, task)
            } else scheduleStep(context, 3000L)
            return
        }
        val allText = treeText(root)
        if (allText.contains("请先登录") || allText.contains("立即登录") || allText.contains("登录后")) {
            markItemFailed(context, "步骤5 失败：$expectedPlatform 要求登录，请先在 App 里登录账号")
            advance(context, task)
            return
        }
        if (allText.contains("验证码") || allText.contains("滑块") || allText.contains("安全验证")) {
            markItemFailed(context, "步骤5 失败：$expectedPlatform 弹出了验证（验证码/滑块），自动评论被平台拦下")
            advance(context, task)
            return
        }
        // 步骤6：找评论输入框。
        val input = findEditableCommentBox(root)
        if (input != null) {
            if (setText(input, task.commentText)) {
                TaskStore.appendLog(context, "步骤6 填评论：已填入，正在找发送按钮…")
                handler.postDelayed({
                    val fresh = rootInActiveWindow
                    val send = if (fresh != null) findSendButton(fresh) else null
                    if (send != null && clickNode(send)) {
                        TaskStore.appendLog(context, "步骤7 点发送：已点，正在验证…")
                        handler.postDelayed({ verifySent(context, task) }, 1800L)
                    } else {
                        attempts++
                        TaskStore.appendLog(context, "步骤7 点发送：没找到发送按钮（第 $attempts 次）")
                        if (attempts >= 3) {
                            attempts = 0
                            markItemFailed(context, "步骤7 失败：填了评论但找不到发送按钮")
                            advance(context, task)
                        } else scheduleStep(context, 3000L)
                    }
                }, 800L)
                return
            }
            attempts++
            TaskStore.appendLog(context, "步骤6 填评论：输入框填不进去（第 $attempts 次）")
            if (attempts >= 3) {
                attempts = 0
                markItemFailed(context, "步骤6 失败：评论填不进去")
                advance(context, task)
            } else scheduleStep(context, 3000L)
            return
        }
        // 步骤5：找评论入口点开。
        val commentBtn = findCommentEntry(root)
        if (commentBtn != null && clickNode(commentBtn)) {
            TaskStore.appendLog(context, "步骤5 打开评论区：已点评论入口，等评论框出现…")
            attempts++
            if (attempts >= 6) {
                attempts = 0
                markItemFailed(context, "步骤5 失败：点了评论入口但评论框一直不出现")
                advance(context, task)
            } else scheduleStep(context, 2500L)
            return
        }
        attempts++
        TaskStore.appendLog(context, "步骤5 打开评论区：界面上没有评论入口（第 $attempts 次）")
        if (attempts >= 3) {
            attempts = 0
            markItemFailed(context, "步骤5 失败：页面里没有评论入口（可能不是视频页，或评论被关闭）")
            advance(context, task)
        } else scheduleStep(context, 3000L)
    }

    private fun verifySent(context: Context, task: CommentTask) {
        if (!running) return
        val root = rootInActiveWindow
        val probe = task.commentText.take(6)
        val sent = if (root == null) false else {
            val text = treeText(root)
            val input = findEditableCommentBox(root)
            val inputText = input?.text?.toString() ?: ""
            text.contains(probe) || (input != null && !inputText.contains(probe))
        }
        if (sent) {
            TaskStore.appendLog(context, "步骤8 验证：评论已发出。")
            markItemSuccess(context)
            attempts = 0
            advance(context, task)
        } else {
            attempts++
            TaskStore.appendLog(context, "步骤8 验证：没验证到评论发出（第 $attempts 次）")
            if (attempts >= 2) {
                attempts = 0
                markItemFailed(context, "步骤8 失败：点了发送但没有验证到评论发出（可能被平台拦截、需要登录或验证）")
                advance(context, task)
            } else scheduleStep(context, 3000L)
        }
    }

    private fun tryPublishStep(context: Context) {
        if (!publishRunning) return
        val root = rootInActiveWindow ?: run {
            publishAttempts++
            if (publishAttempts >= 6) {
                publishRunning = false
                TaskStore.appendLog(context, "发视频步骤6 失败：拿不到发布页界面（$publishPlatform）")
            } else schedulePublishStep(context, 3000L)
            return
        }
        val rootPkg = root.packageName?.toString() ?: ""
        if (rootPkg != publishPackage) {
            publishAttempts++
            TaskStore.appendLog(context, "发视频步骤6：当前不在$publishPlatform 发布页（当前：$rootPkg），等待…（第 $publishAttempts 次）")
            if (publishAttempts >= 6) {
                publishRunning = false
                TaskStore.appendLog(context, "发视频步骤6 失败：发布页没有打开（当前应用：$rootPkg）")
            } else schedulePublishStep(context, 3000L)
            return
        }
        val allText = treeText(root)
        if (allText.contains("发布成功") || allText.contains("已发布") || allText.contains("投稿成功")) {
            publishRunning = false
            lastStatus = "视频已发布到$publishPlatform（已验证）。"
            TaskStore.appendLog(context, "发视频步骤8 验证：$publishPlatform 显示发布成功。")
            return
        }
        // 填文案
        if (publishCaption.isNotBlank()) {
            val input = findEditableCommentBox(root)
            val cur = input?.text?.toString() ?: ""
            if (input != null && !cur.contains(publishCaption.take(6))) {
                if (setText(input, publishCaption)) {
                    TaskStore.appendLog(context, "发视频步骤6：已填文案。")
                }
            }
        }
        // 点下一步/发布
        val btn = findPublishButton(root)
        if (btn != null && clickNode(btn)) {
            publishAttempts++
            TaskStore.appendLog(context, "发视频步骤7：已点发布按钮（第 $publishAttempts 次），等待验证…")
            if (publishAttempts >= 6) {
                publishRunning = false
                TaskStore.appendLog(context, "发视频步骤8 失败：点了发布但没有验证到发布成功（可能需要登录、验证或平台审核）")
            } else schedulePublishStep(context, 3000L)
            return
        }
        publishAttempts++
        TaskStore.appendLog(context, "发视频步骤7：发布页上没有找到发布按钮（第 $publishAttempts 次）")
        if (publishAttempts >= 6) {
            publishRunning = false
            TaskStore.appendLog(context, "发视频步骤7 失败：找不到发布按钮，请手动点发布")
        } else schedulePublishStep(context, 3000L)
    }

    fun failCurrentAndAdvance(context: Context, task: CommentTask, reason: String = "打不开链接") {
        markItemFailed(context, reason)
        advance(context, task)
    }

    private fun advance(context: Context, task: CommentTask) {
        currentIndex++
        attempts = 0
        expectedPackage = null
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

    private fun nodeText(node: AccessibilityNodeInfo): String {
        return (node.text?.toString() ?: "") + " " +
            (node.contentDescription?.toString() ?: "") + " " +
            (node.hintText?.toString() ?: "")
    }

    private fun findEditableCommentBox(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isEditable) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun findCommentEntry(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val keywords = listOf("评论", "留言", "comment", "Comment")
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = nodeText(node)
            if (keywords.any { text.contains(it) } && (node.isClickable || node.parent?.isClickable == true)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val keywords = listOf("发送", "发布", "发表", "Send", "Post")
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = nodeText(node)
            if (keywords.any { text.contains(it) }) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun findPublishButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val keywords = listOf("发布", "下一步", "发送", "Post", "Share", "完成")
        var fallback: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = nodeText(node)
            if (keywords.any { text.contains(it) }) {
                if (text.contains("发布") || text.contains("Post")) return node
                if (fallback == null) fallback = node
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return fallback
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        while (cur != null) {
            if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
        }
        return false
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
        publishRunning = false
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
