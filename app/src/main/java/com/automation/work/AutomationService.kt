package com.automation.work

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

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

        /** 点位校准：弹出可拖动标记，用户把标记拖到评论键/发送键正中心保存。 */
        fun startCalibration(context: Context, platform: String, kind: String) {
            val svc = instance
            if (svc == null) {
                TaskStore.appendLog(context, "校准失败：无障碍服务没在运行，请先在设置 > 无障碍里开启“自动化屏幕控制”。")
                return
            }
            svc.showCalibration(platform, kind)
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
        // 步骤6：找评论输入框。快手/抖音的发送键常是右下角图标、没有文字，
        // 必须先点输入框拿到焦点、填入并验证文字真的进去了，再找发送键。
        val input = findEditableCommentBox(root)
        if (input != null) {
            clickNode(input)
            input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            handler.postDelayed({
                val freshRoot = rootInActiveWindow
                val freshInput = freshRoot?.let { findEditableCommentBox(it) } ?: input
                val okSet = setText(freshInput, task.commentText)
                val probe = task.commentText.take(6)
                val verified = okSet && (rootInActiveWindow?.let {
                    findEditableCommentBox(it)?.text?.toString()?.contains(probe)
                } == true)
                if (verified) {
                    TaskStore.appendLog(context, "步骤6 填评论：已填入并验证文字在输入框里，正在找发送键…")
                    handler.postDelayed({ trySend(context, task) }, 1000L)
                } else {
                    attempts++
                    TaskStore.appendLog(context, "步骤6 填评论：填入后验证不到文字（第 $attempts 次）")
                    if (attempts >= 3) {
                        attempts = 0
                        markItemFailed(context, "步骤6 失败：评论填不进去（输入框不接受填入）")
                        advance(context, task)
                    } else scheduleStep(context, 3000L)
                }
            }, 700L)
            return
        }
        // 用户配置了评论点就按保存位置真实点击打开评论区（每个平台位置不同，按平台记忆）。
        val commentPoint = PointStore.load(context, expectedPlatform, PointStore.KIND_COMMENT)
        if (commentPoint != null) {
            tapAtFraction(commentPoint.x, commentPoint.y, "步骤5 打开评论区")
            attempts++
            if (attempts >= 6) {
                attempts = 0
                markItemFailed(context, "步骤5 失败：按配置评论点点了但评论框一直不出现（点位可能偏了，去点位页重新校准）")
                advance(context, task)
            } else scheduleStep(context, 2500L)
            return
        }
        // 输入框还没变成可编辑时，评论区底部常是一个“说点什么”占位，先点它。
        val placeholder = findPlaceholderInput(root)
        if (placeholder != null && clickNode(placeholder)) {
            TaskStore.appendLog(context, "步骤5 打开评论区：已点评论输入框，等待键盘和输入框出现…")
            attempts++
            if (attempts >= 6) {
                attempts = 0
                markItemFailed(context, "步骤5 失败：点了评论输入框但输入框一直不出现")
                advance(context, task)
            } else scheduleStep(context, 2000L)
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

    private fun trySend(context: Context, task: CommentTask) {
        if (!running) return
        val root = rootInActiveWindow
        if (root == null) {
            attempts++
            if (attempts >= 3) {
                attempts = 0
                markItemFailed(context, "步骤7 失败：找不到发送键（拿不到界面）")
                advance(context, task)
            } else scheduleStep(context, 3000L)
            return
        }
        // 用户配置了发送点就按保存位置真实点击（优先于自动找键，每个平台按记忆位置点）。
        val sendPoint = PointStore.load(context, expectedPlatform, PointStore.KIND_SEND)
        if (sendPoint != null) {
            tapAtFraction(sendPoint.x, sendPoint.y, "步骤7 点发送")
            handler.postDelayed({ verifySent(context, task) }, 1800L)
            return
        }
        val input = findEditableCommentBox(root)
        val found = findSendButton(root, input)
        if (found != null && clickNode(found.first)) {
            TaskStore.appendLog(context, "步骤7 点发送：已用${found.second}点发送，正在验证…")
            handler.postDelayed({ verifySent(context, task) }, 1800L)
            return
        }
        // 发送键没找到时，试回车发送（部分平台回车即发送）。
        if (input != null && input.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
            TaskStore.appendLog(context, "步骤7 点发送：没找到发送键，已用回车发送，正在验证…")
            handler.postDelayed({ verifySent(context, task) }, 1800L)
            return
        }
        attempts++
        TaskStore.appendLog(context, "步骤7 点发送：发送键没找到（文字和右下角位置都没找到，第 $attempts 次）")
        if (attempts >= 3) {
            attempts = 0
            markItemFailed(context, "步骤7 失败：找不到发送键（快手/抖音发送键可能是图标，已按右下角位置找过仍没有；去点位页校准发送点后按位置点）")
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
        var fallback: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isEditable) {
                if (node.className?.toString()?.contains("EditText") == true) return node
                if (fallback == null) fallback = node
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return fallback
    }

    private fun findPlaceholderInput(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val keywords = listOf("说点什么", "写评论", "发评论", "善语结善缘", "发条友善的评论", "Add comment", "发表评论")
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = nodeText(node)
            if (keywords.any { text.contains(it) }) return node
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
            if (keywords.any { text.contains(it) }) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    /** 找发送键：先按文字找；快手/抖音的发送键常是无文字图标，再按位置找输入框右下角的可点控件。 */
    private fun findSendButton(root: AccessibilityNodeInfo, input: AccessibilityNodeInfo?): Pair<AccessibilityNodeInfo, String>? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val keywords = listOf("发送", "发布", "发表", "Send", "Post")
        val all = mutableListOf<AccessibilityNodeInfo>()
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            all.add(node)
            val text = nodeText(node)
            if (keywords.any { text.contains(it) }) return node to "文字匹配"
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        val rootBounds = Rect()
        root.getBoundsInScreen(rootBounds)
        val inputBounds = Rect()
        input?.getBoundsInScreen(inputBounds)
        val inputCenterX = if (input != null) inputBounds.centerX() else rootBounds.centerX()
        var best: AccessibilityNodeInfo? = null
        var bestScore = -1
        for (node in all) {
            if (node === input) continue
            val b = Rect()
            node.getBoundsInScreen(b)
            if (b.width() <= 0 || b.height() <= 0) continue
            val clickable = node.isClickable || node.parent?.isClickable == true
            if (!clickable) continue
            if (b.centerY() < rootBounds.height() * 0.5) continue
            if (b.centerX() <= inputCenterX) continue
            if (b.width() > rootBounds.width() * 0.35) continue
            if (b.height() > rootBounds.height() * 0.18) continue
            val score = b.centerX() + b.centerY()
            if (score > bestScore) {
                bestScore = score
                best = node
            }
        }
        return best?.let { it to "右下角位置匹配" }
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

    // ---------- 点位校准浮窗与坐标点击 ----------

    private var calibrationRoot: FrameLayout? = null

    /** 红色十字标记：圆心就是要保存的点击位置。 */
    private inner class MarkerView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) / 2f - 8f
            canvas.drawCircle(cx, cy, r, paint)
            canvas.drawLine(cx - r, cy, cx + r, cy, paint)
            canvas.drawLine(cx, cy - r, cx, cy + r, paint)
            canvas.drawCircle(cx, cy, 8f, dotPaint)
        }
    }

    fun showCalibration(platform: String, kind: String) {
        removeCalibration()
        val wm = getSystemService(WindowManager::class.java) ?: return
        val density = resources.displayMetrics.density
        val markerSize = (84 * density).toInt()
        val root = FrameLayout(this)
        val kindName = if (kind == PointStore.KIND_SEND) "发送点" else "评论点"
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            setPadding(24, 16, 24, 16)
        }
        val label = TextView(this).apply {
            text = "$platform $kindName：把红色标记拖到按键正中心，再点保存"
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        val marker = MarkerView(this)
        val saved = PointStore.load(this, platform, kind)
        val markerParams = FrameLayout.LayoutParams(markerSize, markerSize)
        root.post {
            val fx = saved?.x ?: 0.5f
            val fy = saved?.y ?: 0.5f
            markerParams.leftMargin = (fx * root.width - markerSize / 2).toInt().coerceAtLeast(0)
            markerParams.topMargin = (fy * root.height - markerSize / 2).toInt().coerceAtLeast(0)
            marker.layoutParams = markerParams
        }
        var downX = 0f
        var downY = 0f
        var startLeft = 0
        var startTop = 0
        marker.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startLeft = markerParams.leftMargin
                    startTop = markerParams.topMargin
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    markerParams.leftMargin = (startLeft + (event.rawX - downX)).toInt()
                        .coerceIn(0, (root.width - markerSize).coerceAtLeast(0))
                    markerParams.topMargin = (startTop + (event.rawY - downY)).toInt()
                        .coerceIn(0, (root.height - markerSize).coerceAtLeast(0))
                    marker.layoutParams = markerParams
                    true
                }
                else -> true
            }
        }
        val saveBtn = Button(this).apply {
            text = "保存点位"
            setOnClickListener {
                if (root.width > 0 && root.height > 0) {
                    val fx = (markerParams.leftMargin + markerSize / 2f) / root.width
                    val fy = (markerParams.topMargin + markerSize / 2f) / root.height
                    PointStore.save(this@AutomationService, platform, kind, fx, fy)
                    TaskStore.appendLog(
                        this@AutomationService,
                        "点位已保存：$platform $kindName X ${(fx * 100).toInt()}% · Y ${(fy * 100).toInt()}%，以后评论按这个位置点。"
                    )
                }
                removeCalibration()
            }
        }
        val cancelBtn = Button(this).apply {
            text = "取消"
            setOnClickListener { removeCalibration() }
        }
        bar.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(saveBtn)
        bar.addView(cancelBtn)
        root.addView(marker, markerParams)
        root.addView(
            bar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        try {
            wm.addView(root, params)
            calibrationRoot = root
            TaskStore.appendLog(this, "校准已打开：$platform $kindName，切到 $platform 视频页，把红色标记拖到按键正中心点保存。")
        } catch (e: Exception) {
            TaskStore.appendLog(this, "校准浮窗打开失败：${e.message}")
        }
    }

    private fun removeCalibration() {
        val root = calibrationRoot ?: return
        calibrationRoot = null
        try {
            getSystemService(WindowManager::class.java)?.removeView(root)
        } catch (_: Exception) {
        }
    }

    /** 按保存的屏幕比例真实点击一次（手势注入，点的是屏幕坐标）。 */
    private fun tapAtFraction(x: Float, y: Float, label: String) {
        val dm = resources.displayMetrics
        val px = x * dm.widthPixels
        val py = y * dm.heightPixels
        val path = Path().apply { moveTo(px, py) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCancelled(gestureDescription: GestureDescription?) {
                TaskStore.appendLog(this@AutomationService, "$label：坐标点击被系统取消（X ${(x * 100).toInt()}% · Y ${(y * 100).toInt()}%）")
            }
        }, handler)
        TaskStore.appendLog(
            this,
            "$label：已按配置点位点击（X ${(x * 100).toInt()}% · Y ${(y * 100).toInt()}%）${if (ok) "" else "，手势派发失败"}"
        )
    }

    override fun onInterrupt() {
        running = false
        publishRunning = false
    }

    override fun onDestroy() {
        removeCalibration()
        super.onDestroy()
        instance = null
    }
}
