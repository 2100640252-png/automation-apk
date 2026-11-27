package com.automation.work

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

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

        // 真人介入状态：失败后暂停自动化，弹出悬浮窗让真人操作、设定、报告错误。
        var pausedForHuman: Boolean = false
        var lastFailureReason: String = ""
        var lastFailureKind: String = ""
        var currentFailureCounted: Boolean = false

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
            pausedForHuman = false
            lastFailureReason = ""
            lastFailureKind = ""
            currentFailureCounted = false
            instance?.showMiniOverlay()
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

        /** 点位预览悬浮窗：把已保存点位直接显示在平台界面上核对位置。 */
        fun startPointPreview(context: Context, platform: String) {
            val svc = instance
            if (svc == null) {
                TaskStore.appendLog(context, "悬浮窗失败：无障碍服务没在运行，请先开启无障碍。")
                return
            }
            svc.showPointPreview(platform)
        }

        fun hidePointPreview() {
            instance?.removeCalibration()
        }


        /** 只打开链接（不评论）：解析链接直达对应平台 App。 */
        fun openLinkOnly(context: Context, link: String) {
            Thread {
                val result = LinkResolver.openVideoLinkDetailed(context, link)
                TaskStore.appendLog(context, "打开链接：${result.reason}")
                if (!result.success) {
                    TaskStore.saveErrorReport(
                        context,
                        "错误报告\n时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}\n任务：只打开链接\n链接：$link\n平台：${result.platform}\n错误：${result.reason}\n"
                    )
                }
            }.start()
        }

        /** 单条链接评论：走状态机自动打开、识别、填评论、发送、验证，不再盲目回放点击序列。 */
        fun startLinkComment(context: Context, link: String, comment: String) {
            if (instance == null) {
                TaskStore.appendLog(context, "开始失败：无障碍服务没在运行，请先开启无障碍。")
                return
            }
            startTask(context, CommentTask.fromLinks(listOf(link), comment))
        }

        fun showInterventionNow(context: Context) {
            val svc = instance
            if (svc == null) {
                TaskStore.appendLog(context, "真人介入窗失败：无障碍服务没在运行，请先开启无障碍。")
                return
            }
            svc.showInterventionPanel(lastFailureReason.ifBlank { "手动打开悬浮控制窗：可以手动操作、重新识别、校准点位、记录点击、保存错误报告。" }, pauseAutomation = false)
        }

        fun runSequenceNow(context: Context, platform: String, comment: String) {
            val svc = instance
            if (svc == null) {
                TaskStore.appendLog(context, "执行失败：无障碍服务没在运行，请先开启无障碍。")
                return
            }
            svc.runSequence(platform, comment)
        }

        fun startRecorder(context: Context, platform: String) {
            val svc = instance
            if (svc == null) {
                TaskStore.appendLog(context, "记录失败：无障碍服务没在运行，请先开启无障碍。")
                return
            }
            svc.showRecorder(platform)
        }

        fun stopTask(context: Context) {
            running = false
            publishRunning = false
            pausedForHuman = false
            stepRunnable?.let { handler.removeCallbacks(it) }
            publishRunnable?.let { handler.removeCallbacks(it) }
            instance?.removeIntervention()
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
                        instance?.interveneCurrent(context, task, result.reason, "LINK")
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
                    instance?.resetSendState()
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
            pausedForHuman = false
            lastFailureReason = ""
            lastFailureKind = ""
            instance?.showMiniOverlay()
            publishRunning = true
            TaskStore.appendLog(context, "发视频步骤5：发布页已打开，开始自动填文案、点发布（$platform）…")
            instance?.schedulePublishStep(context, 3000L)
        }
    }

    private var attempts: Int = 0
    private var sendStage: Int = 0
    private var sendWaitAttempts: Int = 0
    private var pasteTried: Boolean = false
    private var lastVisionSummary: String = "未使用截图视觉兜底"
    private var preSendProbeCount: Int = 0
    private var interventionRoot: View? = null
    private var interventionNote: EditText? = null
    private var interventionStatus: TextView? = null
    private var interventionSnapshot: TextView? = null
    private var panelRefreshScheduled: Boolean = false
    private var overlayPlatform: String = "抖音"
    private var overlayMode: String = "none"
    private var panelReason: String = ""

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
        if (interventionRoot == null) {
            when (overlayMode) {
                "panel" -> showInterventionPanel(panelReason.ifBlank { "悬浮控制窗" }, pauseAutomation = pausedForHuman)
                "mini" -> showMiniOverlay()
                "manual" -> showManualBar()
            }
        }
        if (pausedForHuman) return
        if (publishRunning) {
            schedulePublishStep(this, 1500L)
            return
        }
        if (!running) return
        scheduleStep(this, 1500L)
    }

    private fun scheduleStep(context: Context, delayMs: Long) {
        if (pausedForHuman) return
        stepRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable { tryOneStep(context) }
        stepRunnable = r
        handler.postDelayed(r, delayMs)
    }

    private fun schedulePublishStep(context: Context, delayMs: Long) {
        if (pausedForHuman) return
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

    private fun commentProbe(task: CommentTask): String {
        val normalized = task.commentText.trim().replace(Regex("\\s+"), " ")
        return normalized.take(12).ifBlank { normalized }
    }

    private fun textOutsideInput(root: AccessibilityNodeInfo, input: AccessibilityNodeInfo?): String {
        val sb = StringBuilder()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            val isInput = n === input || n.isEditable || n.className?.toString()?.contains("EditText") == true
            if (!isInput) {
                n.text?.let { sb.append(it).append(' ') }
                n.contentDescription?.let { sb.append(it).append(' ') }
                n.hintText?.let { sb.append(it).append(' ') }
            }
            if (!isInput) {
                for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
            }
        }
        return sb.toString()
    }

    private fun countOccurrences(text: String, probe: String): Int {
        if (probe.isBlank()) return 0
        var count = 0
        var index = 0
        while (true) {
            val found = text.indexOf(probe, index)
            if (found < 0) break
            count++
            index = found + probe.length
        }
        return count
    }

    fun resetSendState() {
        sendStage = 0
        sendWaitAttempts = 0
        pasteTried = false
        lastVisionSummary = "未使用截图视觉兜底"
        preSendProbeCount = 0
    }

    private fun tryOneStep(context: Context) {
        if (!running || pausedForHuman) return
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
                interveneCurrent(context, task, "步骤4 失败：打不开页面，拿不到窗口")
            } else scheduleStep(context, 3000L)
            return
        }
        // 步骤4：验证当前确实在目标 App 里。
        val rootPkg = root.packageName?.toString() ?: ""
        val expected = expectedPackage
        if (expected != null && rootPkg != expected) {
            if (rootPkg in browserPackages) {
                interveneCurrent(context, task, "步骤4 失败：链接被浏览器打开了，$expectedPlatform 没有接管（当前应用：$rootPkg）")
                return
            }
            attempts++
            TaskStore.appendLog(context, "步骤4 验证界面：当前不在$expectedPlatform（当前：$rootPkg），等它打开…（第 $attempts 次）")
            if (attempts >= 3) {
                attempts = 0
                interveneCurrent(context, task, "步骤4 失败：打开后不在目标 App（当前：$rootPkg，应为：$expected）")
            } else scheduleStep(context, 3000L)
            return
        }
        val allText = treeText(root)
        if (allText.contains("请先登录") || allText.contains("立即登录") || allText.contains("登录后")) {
            interveneCurrent(context, task, "步骤5 失败：$expectedPlatform 要求登录，请先在 App 里登录账号")
            return
        }
        if (allText.contains("验证码") || allText.contains("滑块") || allText.contains("安全验证")) {
            interveneCurrent(context, task, "步骤5 失败：$expectedPlatform 弹出了验证（验证码/滑块），自动评论被平台拦下")
            return
        }
        // 步骤6：找评论输入框。快手/抖音的发送键常是右下角图标、没有文字，
        // 必须先点输入框拿到焦点、填入并验证文字真的进去了，再找发送键。
        val input = findEditableCommentBox(root)
        if (input != null) {
            val probe = commentProbe(task)
            val existingText = input.text?.toString() ?: ""
            if (existingText.contains(probe)) {
                if (sendStage == 0) {
                    preSendProbeCount = countOccurrences(textOutsideInput(root, input), probe)
                    TaskStore.appendLog(context, "步骤6 填评论：输入框里已有评论，直接进入发送（发送前评论区同文案出现 $preSendProbeCount 次）。")
                    handler.postDelayed({ trySend(context, task) }, 500L)
                } else {
                    handler.postDelayed({ verifySent(context, task) }, 500L)
                }
                return
            }
            clickNode(input)
            input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            handler.postDelayed({
                val freshRoot = rootInActiveWindow
                val freshInput = freshRoot?.let { findEditableCommentBox(it) } ?: input
                val verified = fillCommentText(freshInput, task.commentText, probe)
                if (verified) {
                    sendStage = 0
                    sendWaitAttempts = 0
                    val rootNow = rootInActiveWindow
                    val inputNow = rootNow?.let { findEditableCommentBox(it) }
                    preSendProbeCount = if (rootNow != null) countOccurrences(textOutsideInput(rootNow, inputNow), probe) else 0
                    TaskStore.appendLog(context, "步骤6 填评论：已填入并验证文字在输入框里，正在找发送键…（发送前评论区同文案出现 $preSendProbeCount 次）")
                    handler.postDelayed({ trySend(context, task) }, 1500L)
                } else {
                    attempts++
                    TaskStore.appendLog(context, "步骤6 填评论：填入后验证不到文字（第 $attempts 次）")
                    if (attempts >= 3) {
                        attempts = 0
                        interveneCurrent(context, task, "步骤6 失败：评论填不进去（输入框不接受填入）")
                    } else scheduleStep(context, 3000L)
                }
            }, 1200L)
            return
        }
        // 用户配置了评论点就按保存位置真实点击打开评论区（每个平台位置不同，按平台记忆）。
        val commentPoint = PointStore.load(context, expectedPlatform, PointStore.KIND_COMMENT)
        if (commentPoint != null) {
            tapAtFraction(commentPoint.x, commentPoint.y, "步骤5 打开评论区")
            attempts++
            if (attempts >= 6) {
                attempts = 0
                interveneCurrent(context, task, "步骤5 失败：按配置评论点点了但评论框一直不出现（点位可能偏了，去点位页重新校准）")
            } else scheduleStep(context, 2500L)
            return
        }
        // 输入框还没变成可编辑时，评论区底部常是一个“说点什么”占位，先点它。
        val placeholder = findPlaceholderInput(root)
        if (placeholder != null) {
            if (clickNode(placeholder)) {
                TaskStore.appendLog(context, "步骤5 打开评论区：已点评论输入框，等待键盘和输入框出现…")
            } else {
                tapNodeCenter(placeholder, "步骤5 打开评论区")
                TaskStore.appendLog(context, "步骤5 打开评论区：评论输入框控件点击无效，已按它的位置坐标点击…")
            }
            attempts++
            if (attempts >= 6) {
                attempts = 0
                interveneCurrent(context, task, "步骤5 失败：点了评论输入框但输入框一直不出现")
            } else scheduleStep(context, 2200L)
            return
        }
        // 步骤5：找评论入口点开。
        val commentBtn = findCommentEntry(root)
        if (commentBtn != null) {
            if (clickNode(commentBtn)) {
                TaskStore.appendLog(context, "步骤5 打开评论区：已点评论入口，等评论框出现…")
            } else {
                tapNodeCenter(commentBtn, "步骤5 打开评论区")
                TaskStore.appendLog(context, "步骤5 打开评论区：评论入口控件点击无效，已按它的位置坐标点击…")
            }
            attempts++
            if (attempts >= 6) {
                attempts = 0
                interveneCurrent(context, task, "步骤5 失败：点了评论入口但评论框一直不出现")
            } else scheduleStep(context, 2500L)
            return
        }
        if (treeText(root).contains("全部评论") && attempts < 3) {
            tapAtFraction(0.5f, 0.92f, "步骤5 打开评论区")
            TaskStore.appendLog(context, "步骤5 打开评论区：页面已有全部评论但输入框没露出来，已点底部评论栏位置…")
            attempts++
            scheduleStep(context, 2200L)
            return
        }
        if ((expectedPlatform == "抖音" || expectedPlatform == "快手") && attempts < 3) {
            val y = listOf(0.60f, 0.64f, 0.56f)[attempts]
            tapAtFraction(0.925f, y, "步骤5 打开评论区")
            TaskStore.appendLog(context, "步骤5 打开评论区：界面没暴露评论入口，已按$expectedPlatform 右侧评论图标位置坐标点击（y=$y）…")
            attempts++
            scheduleStep(context, 2500L)
            return
        }
        attempts++
        TaskStore.appendLog(context, "步骤5 打开评论区：界面上没有评论入口（第 $attempts 次）")
        if (attempts >= 3) {
            attempts = 0
            interveneCurrent(context, task, "步骤5 失败：页面里没有评论入口（可能不是视频页，或评论被关闭）")
        } else scheduleStep(context, 3000L)
    }

    private fun trySend(context: Context, task: CommentTask) {
        if (!running || pausedForHuman) return
        val root = rootInActiveWindow
        if (root == null) {
            attempts++
            if (attempts >= 3) {
                attempts = 0
                interveneCurrent(context, task, "步骤7 失败：找不到发送键（拿不到界面）")
            } else scheduleStep(context, 3000L)
            return
        }
        val input = findEditableCommentBox(root)
        val probe = commentProbe(task)
        val inputText = input?.text?.toString() ?: ""
        if (input == null || !inputText.contains(probe)) {
            TaskStore.appendLog(context, "步骤7 点发送：输入框里没有待发送评论，回到填评论步骤重新确认。")
            scheduleStep(context, 1200L)
            return
        }
        // 用户配置了发送点就先按保存位置真实点击；没生效时再换自动找键和回车，不重复假报成功。
        val sendPoint = PointStore.load(context, expectedPlatform, PointStore.KIND_SEND)
        if (sendPoint != null && sendStage == 0) {
            sendStage = 1
            tapAtFraction(sendPoint.x, sendPoint.y, "步骤7 点发送") {
                handler.postDelayed({ verifySent(context, task) }, 1500L)
            }
            return
        }
        val found = findSendButton(root, input)
        if (found == null && sendStage == 0 && sendWaitAttempts < 3) {
            sendWaitAttempts++
            if (sendWaitAttempts == 1) {
                input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                clickNode(input)
                TaskStore.appendLog(context, "步骤7 点发送：发送键还没出现，已重新聚焦输入框触发发送键显示…")
            } else if (sendWaitAttempts == 2 && !pasteTried) {
                pasteTried = true
                if (pasteText(input, task.commentText, probe)) {
                    TaskStore.appendLog(context, "步骤7 点发送：发送键还没出现，已用粘贴方式重新填入评论，等待发送键激活…")
                }
            } else {
                TaskStore.appendLog(context, "步骤7 点发送：发送键还没出现，等键盘和发送键稳定后重试（第 $sendWaitAttempts 次）…")
            }
            handler.postDelayed({ trySend(context, task) }, 1300L)
            return
        }
        if (found != null) sendWaitAttempts = 0
        when (sendStage) {
            0 -> {
                sendStage = 1
                if (found != null && clickNode(found.first)) {
                    TaskStore.appendLog(context, "步骤7 点发送：已用${found.second}点击发送控件，正在验证…")
                    handler.postDelayed({ verifySent(context, task) }, 1600L)
                    return
                }
                TaskStore.appendLog(context, "步骤7 点发送：发送控件点击无效，改用坐标点击…")
                handler.postDelayed({ trySend(context, task) }, 500L)
                return
            }
            1 -> {
                sendStage = 2
                if (found != null) {
                    tapNodeCenter(found.first, "步骤7 点发送") {
                        TaskStore.appendLog(context, "步骤7 点发送：已用${found.second}坐标点击发送控件，正在验证…")
                        handler.postDelayed({ verifySent(context, task) }, 1600L)
                    }
                    return
                }
                handler.postDelayed({ trySend(context, task) }, 300L)
                return
            }
            2 -> {
                sendStage = 3
                findSendByScreenshot(input) { point ->
                    if (point != null) {
                        tapAtFraction(point.first, point.second, "步骤7 点发送") {
                            TaskStore.appendLog(context, "步骤7 点发送：已按截图视觉找到的发送图标点击，正在验证…")
                            handler.postDelayed({ verifySent(context, task) }, 1600L)
                        }
                    } else {
                        TaskStore.appendLog(context, "步骤7 点发送：截图视觉没找到发送图标，改用输入框右侧位置…")
                        handler.postDelayed({ trySend(context, task) }, 300L)
                    }
                }
                return
            }
            3 -> {
                sendStage = 4
                tapInputRight(input, "步骤7 点发送") {
                    TaskStore.appendLog(context, "步骤7 点发送：已按输入框右侧发送图标位置点击，正在验证…")
                    handler.postDelayed({ verifySent(context, task) }, 1600L)
                }
                return
            }
            4 -> {
                sendStage = 5
                if (input.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
                    TaskStore.appendLog(context, "步骤7 点发送：已用回车发送，正在验证…")
                    handler.postDelayed({ verifySent(context, task) }, 1600L)
                    return
                }
            }
        }
        attempts++
        TaskStore.appendLog(context, "步骤7 点发送：发送键没找到或点击没生效（第 $attempts 次，发送阶段 $sendStage）")
        if (attempts >= 3 || sendStage >= 5) {
            attempts = 0
            interveneCurrent(context, task, "步骤7 失败：发送键点击没有生效（已试控件点击、发送键坐标、截图视觉、输入框右侧位置和回车；请真人确认发送键位置或校准发送点）")
        } else scheduleStep(context, 2500L)
    }

    private fun verifySent(context: Context, task: CommentTask) {
        if (!running || pausedForHuman) return
        val root = rootInActiveWindow
        val probe = commentProbe(task)
        val input = root?.let { findEditableCommentBox(it) }
        val inputText = input?.text?.toString() ?: ""
        val outsideText = root?.let { textOutsideInput(it, input) } ?: ""
        val outsideCount = countOccurrences(outsideText, probe)
        val inputContains = inputText.contains(probe)
        val inputCleared = input == null || inputText.isBlank() || !inputContains
        val successWords = outsideText.contains("评论成功") || outsideText.contains("发送成功")
        val sent = !inputContains && (outsideCount > preSendProbeCount || successWords)
        if (sent) {
            TaskStore.appendLog(context, "步骤8 验证：评论已发出（输入框外同文案从 $preSendProbeCount 次变为 $outsideCount 次）。")
            markItemSuccess(context)
            attempts = 0
            advance(context, task)
        } else if (inputContains) {
            TaskStore.appendLog(context, "步骤8 验证：输入框里还有评论，说明还没发出去（发送阶段 $sendStage）。")
            if (sendStage < 5) {
                handler.postDelayed({ trySend(context, task) }, 900L)
            } else {
                interveneCurrent(context, task, "步骤8 失败：输入框里还有评论，发送点击没有生效（已试控件点击、发送键坐标、截图视觉、输入框右侧位置和回车）")
            }
        } else if (inputCleared) {
            interveneCurrent(context, task, "步骤8 失败：输入框已清空，但没有在输入框外验证到新评论出现，已暂停等真人确认，避免重复发送")
        } else {
            attempts++
            TaskStore.appendLog(context, "步骤8 验证：没验证到评论发出（第 $attempts 次）")
            if (attempts >= 2) {
                attempts = 0
                interveneCurrent(context, task, "步骤8 失败：点了发送但没有验证到评论发出（可能被平台拦截、需要登录或验证）")
            } else scheduleStep(context, 3000L)
        }
    }

    private fun tryPublishStep(context: Context) {
        if (!publishRunning || pausedForHuman) return
        val root = rootInActiveWindow ?: run {
            publishAttempts++
            if (publishAttempts >= 6) {
                intervenePublish(context, "发视频步骤6 失败：拿不到发布页界面（$publishPlatform）")
            } else schedulePublishStep(context, 3000L)
            return
        }
        val rootPkg = root.packageName?.toString() ?: ""
        if (rootPkg != publishPackage) {
            publishAttempts++
            TaskStore.appendLog(context, "发视频步骤6：当前不在$publishPlatform 发布页（当前：$rootPkg），等待…（第 $publishAttempts 次）")
            if (publishAttempts >= 6) {
                intervenePublish(context, "发视频步骤6 失败：发布页没有打开（当前应用：$rootPkg）")
            } else schedulePublishStep(context, 3000L)
            return
        }
        val allText = treeText(root)
        if (allText.contains("发布成功") || allText.contains("已发布") || allText.contains("投稿成功")) {
            publishRunning = false
            pausedForHuman = false
            removeIntervention()
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
                intervenePublish(context, "发视频步骤8 失败：点了发布但没有验证到发布成功（可能需要登录、验证或平台审核）")
            } else schedulePublishStep(context, 3000L)
            return
        }
        publishAttempts++
        TaskStore.appendLog(context, "发视频步骤7：发布页上没有找到发布按钮（第 $publishAttempts 次）")
        if (publishAttempts >= 6) {
            intervenePublish(context, "发视频步骤7 失败：找不到发布按钮，请手动点发布")
        } else schedulePublishStep(context, 3000L)
    }

    fun failCurrentAndAdvance(context: Context, task: CommentTask, reason: String = "打不开链接") {
        interveneCurrent(context, task, reason)
    }

    private fun advance(context: Context, task: CommentTask) {
        currentIndex++
        attempts = 0
        resetSendState()
        expectedPackage = null
        pausedForHuman = false
        lastFailureReason = ""
        lastFailureKind = ""
        currentFailureCounted = false
        removeIntervention()
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

    // ---------- 真人介入：失败报错、暂停、悬浮窗、错误报告 ----------

    fun interveneCurrent(context: Context, task: CommentTask, reason: String, kind: String = "STEP") {
        lastFailureReason = reason
        lastFailureKind = kind
        pausedForHuman = true
        stepRunnable?.let { handler.removeCallbacks(it) }
        lastStatus = "需要真人介入：$reason"
        saveCurrentErrorReport(context, reason, "")
        TaskStore.appendLog(context, "真人介入：$reason 已暂停，悬浮窗里可以手动操作、重新识别、校准、记录点击、保存报告。")
        showInterventionPanel(reason)
    }

    private fun intervenePublish(context: Context, reason: String) {
        lastFailureReason = reason
        lastFailureKind = "PUBLISH"
        pausedForHuman = true
        publishRunnable?.let { handler.removeCallbacks(it) }
        lastStatus = "需要真人介入：$reason"
        saveCurrentErrorReport(context, reason, "")
        TaskStore.appendLog(context, "真人介入：$reason 已暂停，悬浮窗里可以手动操作、重新识别、校准、记录点击、保存报告。")
        showInterventionPanel(reason)
    }

    private fun platformForIntervention(): String {
        if (publishRunning && publishPlatform.isNotBlank()) return publishPlatform
        if (expectedPlatform.isNotBlank()) return expectedPlatform
        val itemPlatform = currentTask?.items?.getOrNull(currentIndex)?.platform ?: ""
        return itemPlatform.ifBlank { overlayPlatform }
    }

    private fun saveCurrentErrorReport(context: Context, reason: String, userNote: String) {
        val report = buildDiagnosticReport(reason, userNote)
        TaskStore.saveErrorReport(context, report)
        TaskStore.appendLog(context, "错误报告已保存：$reason")
    }

    private fun buildDiagnosticReport(reason: String, userNote: String): String {
        val root = rootInActiveWindow
        val rootPkg = root?.packageName?.toString() ?: "拿不到窗口"
        val pageText = root?.let { treeText(it).replace(Regex("\\s+"), " ").trim().take(600) } ?: ""
        val item = currentTask?.items?.getOrNull(currentIndex)
        val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        return buildString {
            appendLine("错误报告")
            appendLine("时间：$time")
            appendLine("错误：$reason")
            appendLine("失败类型：${lastFailureKind.ifBlank { "STEP" }}")
            appendLine("平台：${platformForIntervention()}")
            appendLine("期望包名：${expectedPackage ?: publishPackage.ifBlank { "未知" }}")
            appendLine("当前包名：$rootPkg")
            if (item != null) {
                appendLine("第几条：第 ${item.index} 条")
                appendLine("链接：${item.link}")
                appendLine("评论：${currentTask?.commentText ?: ""}")
            }
            if (publishRunning) {
                appendLine("发视频平台：$publishPlatform")
                appendLine("视频文案：$publishCaption")
            }
            if (userNote.isNotBlank()) appendLine("真人补充：$userNote")
            appendLine("发送状态：阶段=$sendStage 等待次数=$sendWaitAttempts 粘贴兜底=$pasteTried")
            appendLine("截图视觉摘要：$lastVisionSummary")
            appendLine("页面文字摘要：$pageText")
            appendLine("控件摘要：")
            append(nodeSummary(root))
        }
    }

    private fun nodeSummary(root: AccessibilityNodeInfo?): String {
        if (root == null) return "拿不到当前窗口控件。\n"
        val lines = mutableListOf<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 160 && lines.size < 40) {
            val node = queue.removeFirst()
            visited++
            val text = nodeText(node).replace(Regex("\\s+"), " ").trim()
            if (node.isClickable || node.isEditable || text.isNotBlank()) {
                val b = Rect()
                node.getBoundsInScreen(b)
                val cls = node.className?.toString()?.substringAfterLast('.') ?: ""
                lines.add("- $cls text=${text.take(60)} bounds=${b.left},${b.top},${b.right},${b.bottom} clickable=${node.isClickable} editable=${node.isEditable} focused=${node.isFocused}")
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return if (lines.isEmpty()) "没有可读控件。\n" else lines.joinToString("\n") + "\n"
    }

    private fun resumeAfterIntervention(context: Context) {
        showMiniOverlay()
        pausedForHuman = false
        attempts = 0
        publishAttempts = 0
        TaskStore.appendLog(context, "真人介入结束：重新识别当前页面并继续。")
        if (publishRunning) {
            schedulePublishStep(context, 800L)
        } else if (lastFailureKind == "LINK") {
            val rootPkg = rootInActiveWindow?.packageName?.toString() ?: ""
            if (rootPkg.isNotBlank() && rootPkg !in browserPackages && rootPkg != packageName) {
                expectedPackage = rootPkg
                if (expectedPlatform.isBlank()) expectedPlatform = platformForIntervention()
                scheduleStep(context, 800L)
            } else {
                openCurrent(context)
            }
        } else {
            scheduleStep(context, 800L)
        }
    }

    private fun skipAfterIntervention(context: Context) {
        showMiniOverlay()
        pausedForHuman = false
        if (publishRunning) {
            publishRunning = false
            lastStatus = "发视频已跳过：${lastFailureReason}"
            TaskStore.appendLog(context, lastStatus)
            return
        }
        val task = currentTask ?: return
        if (!currentFailureCounted) {
            markItemFailed(context, lastFailureReason.ifBlank { "真人跳过" })
            currentFailureCounted = true
        }
        advance(context, task)
    }

    private fun interventionParams(gravityValue: Int, focusable: Boolean): WindowManager.LayoutParams {
        val flags = if (focusable) {
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = gravityValue }
    }

    fun showInterventionPanel(reason: String, pauseAutomation: Boolean = true) {
        removeIntervention()
        pausedForHuman = pauseAutomation
        panelReason = reason
        val wm = getSystemService(WindowManager::class.java) ?: return
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(238, 18, 18, 18))
            setPadding(24, 18, 24, 18)
        }
        val title = TextView(this).apply {
            text = "悬浮控制窗"
            setTextColor(Color.WHITE)
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val status = TextView(this).apply {
            setTextColor(Color.rgb(147, 197, 253))
            textSize = 13f
        }
        interventionStatus = status
        val error = TextView(this).apply {
            text = reason
            setTextColor(Color.rgb(255, 220, 160))
            textSize = 14f
        }
        val snapshot = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 12f
        }
        interventionSnapshot = snapshot
        val note = EditText(this).apply {
            hint = "补充错误说明（可选）：比如按钮在右下角、页面弹了登录"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            minLines = 1
            maxLines = 3
        }
        interventionNote = note
        fun row(vararg buttons: Button): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                buttons.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
            }
        }
        fun btn(text: String, action: () -> Unit): Button {
            return Button(this).apply {
                this.text = text
                setOnClickListener { action() }
            }
        }
        panel.addView(title)
        panel.addView(status)
        panel.addView(error)
        panel.addView(snapshot)
        panel.addView(note)
        panel.addView(row(
            btn("重试当前步") { resumeAfterIntervention(this) },
            btn("我来操作") { showManualBar() },
            btn("继续识别") { resumeAfterIntervention(this) }
        ))
        panel.addView(row(
            btn("平台：${platformForIntervention()}") {
                val platforms = listOf("抖音", "快手", "小红书", "哔哩哔哩", "微博")
                val current = platformForIntervention()
                overlayPlatform = platforms[(platforms.indexOf(current).coerceAtLeast(0) + 1) % platforms.size]
                updateInterventionPanel()
                Toast.makeText(this, "校准平台已切到$overlayPlatform", Toast.LENGTH_SHORT).show()
            },
            btn("校准评论点") {
                removeIntervention()
                showCalibration(platformForIntervention(), PointStore.KIND_COMMENT)
            },
            btn("校准发送点") {
                removeIntervention()
                showCalibration(platformForIntervention(), PointStore.KIND_SEND)
            }
        ))
        panel.addView(row(
            btn("保存报告") {
                saveCurrentErrorReport(this, reason, note.text?.toString() ?: "")
                Toast.makeText(this, "错误报告已保存", Toast.LENGTH_SHORT).show()
            },
            btn("记录点击") {
                removeIntervention()
                showRecorder(platformForIntervention())
            },
            btn("刷新状态") { updateInterventionPanel() }
        ))
        panel.addView(row(
            btn("跳过这一条") { skipAfterIntervention(this) },
            btn("停止任务") { stopTask(this) },
            btn("最小化") { showMiniOverlay() }
        ))
        val scroll = ScrollView(this).apply { addView(panel) }
        try {
            wm.addView(scroll, interventionParams(Gravity.BOTTOM, focusable = true))
            interventionRoot = scroll
            overlayMode = "panel"
            updateInterventionPanel()
            schedulePanelRefresh()
        } catch (e: Exception) {
            TaskStore.appendLog(this, "真人介入窗打开失败：${e.message}")
        }
    }

    private fun updateInterventionPanel() {
        val platform = platformForIntervention()
        val lastLog = TaskStore.loadLog(this).lines().lastOrNull { it.isNotBlank() } ?: "暂无日志"
        interventionStatus?.text = "实时状态：$lastStatus\n最新日志：$lastLog\n校准平台：$platform · 评论点 ${PointStore.describe(this, platform, PointStore.KIND_COMMENT)} · 发送点 ${PointStore.describe(this, platform, PointStore.KIND_SEND)}"
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString() ?: "拿不到窗口"
        val txt = root?.let { treeText(it).replace(Regex("\\s+"), " ").trim().take(180) } ?: ""
        interventionSnapshot?.text = "当前应用：$pkg\n页面摘要：$txt"
    }

    private fun schedulePanelRefresh() {
        if (panelRefreshScheduled) return
        panelRefreshScheduled = true
        handler.postDelayed({
            panelRefreshScheduled = false
            if (interventionRoot != null) {
                updateInterventionPanel()
                schedulePanelRefresh()
            }
        }, 1000L)
    }

    fun showMiniOverlay() {
        removeIntervention()
        val wm = getSystemService(WindowManager::class.java) ?: return
        val mini = Button(this).apply {
            text = "控"
            textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(235, 15, 23, 42))
            setOnClickListener { showInterventionPanel(panelReason.ifBlank { "悬浮控制窗" }, pauseAutomation = pausedForHuman) }
            setOnLongClickListener {
                overlayMode = "none"
                removeIntervention()
                true
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        try {
            wm.addView(mini, params)
            interventionRoot = mini
            overlayMode = "mini"
            TaskStore.appendLog(this, "悬浮窗已最小化：右侧“控”按钮会保留，点它展开控制窗，长按关闭。")
        } catch (e: Exception) {
            TaskStore.appendLog(this, "悬浮窗最小化失败：${e.message}")
        }
    }

    private fun showManualBar() {
        removeIntervention()
        pausedForHuman = true
        val wm = getSystemService(WindowManager::class.java) ?: return
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(225, 18, 18, 18))
            setPadding(16, 10, 16, 10)
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(this).apply {
            text = "真人操作中"
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        bar.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Button(this).apply {
            text = "控制窗"
            setOnClickListener { showInterventionPanel(lastFailureReason.ifBlank { "手动打开悬浮控制窗" }) }
        })
        bar.addView(Button(this).apply {
            text = "继续识别"
            setOnClickListener { resumeAfterIntervention(this@AutomationService) }
        })
        bar.addView(Button(this).apply {
            text = "保存报告"
            setOnClickListener {
                saveCurrentErrorReport(this@AutomationService, lastFailureReason.ifBlank { "真人手动报告" }, "")
                Toast.makeText(this@AutomationService, "错误报告已保存", Toast.LENGTH_SHORT).show()
            }
        })
        bar.addView(Button(this).apply {
            text = "停止"
            setOnClickListener { stopTask(this@AutomationService) }
        })
        try {
            wm.addView(bar, interventionParams(Gravity.TOP, focusable = false))
            interventionRoot = bar
            overlayMode = "manual"
            TaskStore.appendLog(this, "真人接管：自动化已暂停，你可以手动操作；处理完点顶部“继续识别”。")
        } catch (e: Exception) {
            TaskStore.appendLog(this, "真人操作条打开失败：${e.message}")
        }
    }

    fun removeIntervention() {
        val root = interventionRoot ?: return
        interventionRoot = null
        interventionNote = null
        interventionStatus = null
        interventionSnapshot = null
        panelRefreshScheduled = false
        overlayMode = "none"
        try {
            getSystemService(WindowManager::class.java)?.removeView(root)
        } catch (_: Exception) {
        }
    }

    private fun nodeText(node: AccessibilityNodeInfo): String {
        return (node.text?.toString() ?: "") + " " +
            (node.contentDescription?.toString() ?: "") + " " +
            (node.hintText?.toString() ?: "") + " " +
            (node.viewIdResourceName ?: "")
    }

    private fun findEditableCommentBox(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val rootBounds = nodeBounds(root)
        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isEditable) {
                val b = nodeBounds(node)
                val text = nodeText(node)
                var score = 0
                if (node.className?.toString()?.contains("EditText") == true) score += 120
                if (text.contains("评论") || text.contains("说点什么") || text.contains("精彩") || text.contains("写评论")) score += 600
                if (node.isFocused) score += 250
                if (b.centerY() > rootBounds.height() * 0.5) score += 250
                if (b.width() > rootBounds.width() * 0.35) score += 120
                score += b.centerY() / 10
                if (score > bestScore) {
                    bestScore = score
                    best = node
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return best
    }

    private fun findPlaceholderInput(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val rootBounds = nodeBounds(root)
        val keywords = listOf(
            "说点什么", "写评论", "发评论", "善语结善缘", "发条友善的评论", "Add comment", "发表评论",
            "留下你的精彩评论吧", "留下你的精彩评论", "精彩评论", "写下你的评论", "评论一下", "说点什么吧", "爱评论的人运气都不差"
        )
        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = nodeText(node)
            if (keywords.any { text.contains(it) }) {
                val target = clickableAncestor(node) ?: node
                val b = nodeBounds(target)
                var score = b.centerY()
                if (clickableAncestor(node) != null) score += 500
                if (b.centerY() > rootBounds.height() * 0.55) score += 500
                if (b.width() > rootBounds.width() * 0.35) score += 200
                if (score > bestScore) {
                    bestScore = score
                    best = target
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return best
    }

    private fun findCommentEntry(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val rootBounds = nodeBounds(root)
        val keywords = listOf("评论", "留言", "comment", "Comment")
        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = nodeText(node)
            if (keywords.any { text.contains(it) }) {
                val target = clickableAncestor(node) ?: node
                val b = nodeBounds(target)
                var score = 0
                if (clickableAncestor(node) != null) score += 600
                if (b.centerX() > rootBounds.width() * 0.68) score += 500
                if (b.centerY() > rootBounds.height() * 0.3 && b.centerY() < rootBounds.height() * 0.85) score += 250
                if (text.trim() == "评论" || text.contains("评论 ")) score += 200
                if (b.width() <= rootBounds.width() * 0.3 && b.height() <= rootBounds.height() * 0.2) score += 150
                score += b.centerX() / 10
                if (score > bestScore) {
                    bestScore = score
                    best = target
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return best
    }

    private fun nodeBounds(node: AccessibilityNodeInfo): Rect {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return rect
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        repeat(5) {
            val current = cur ?: return null
            if (current.isClickable) return current
            cur = current.parent
        }
        return null
    }

    private fun tapNodeCenter(node: AccessibilityNodeInfo, label: String, onDone: ((Boolean) -> Unit)? = null) {
        val bounds = nodeBounds(node)
        val dm = resources.displayMetrics
        val x = if (dm.widthPixels > 0) bounds.centerX().toFloat() / dm.widthPixels else 0.9f
        val y = if (dm.heightPixels > 0) bounds.centerY().toFloat() / dm.heightPixels else 0.9f
        tapAtFraction(x.coerceIn(0.01f, 0.99f), y.coerceIn(0.01f, 0.99f), label, onDone)
    }

    private fun tapInputRight(input: AccessibilityNodeInfo, label: String, onDone: ((Boolean) -> Unit)? = null) {
        val bounds = nodeBounds(input)
        val dm = resources.displayMetrics
        val xPx = if (bounds.right < dm.widthPixels * 0.88) {
            bounds.right + dm.widthPixels * 0.045f
        } else {
            bounds.right - dm.widthPixels * 0.035f
        }
        val x = if (dm.widthPixels > 0) xPx / dm.widthPixels else 0.94f
        val y = if (dm.heightPixels > 0) bounds.centerY().toFloat() / dm.heightPixels else 0.9f
        tapAtFraction(x.coerceIn(0.01f, 0.99f), y.coerceIn(0.01f, 0.99f), label, onDone)
    }

    private fun findSendByScreenshot(input: AccessibilityNodeInfo, onResult: (Pair<Float, Float>?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            lastVisionSummary = "截图视觉不可用：系统版本低于 Android 11"
            onResult(null)
            return
        }
        val inputBounds = nodeBounds(input)
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                java.util.concurrent.Executors.newSingleThreadExecutor(),
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        var point: Pair<Float, Float>? = null
                        try {
                            val hardwareBitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                            val bitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            if (bitmap != null) {
                                point = findRedSendIcon(bitmap, inputBounds)
                                bitmap.recycle()
                            } else {
                                lastVisionSummary = "截图视觉失败：截图转成可读图片失败"
                            }
                            hardwareBitmap?.recycle()
                        } catch (e: Exception) {
                            lastVisionSummary = "截图视觉失败：${e.message}"
                        } finally {
                            try {
                                screenshot.hardwareBuffer.close()
                            } catch (_: Exception) {
                            }
                        }
                        handler.post { onResult(point) }
                    }

                    override fun onFailure(errorCode: Int) {
                        lastVisionSummary = "截图视觉失败：系统截图错误码 $errorCode"
                        handler.post { onResult(null) }
                    }
                }
            )
        } catch (e: Exception) {
            lastVisionSummary = "截图视觉失败：${e.message}"
            onResult(null)
        }
    }

    private fun findRedSendIcon(bitmap: Bitmap, inputBounds: Rect): Pair<Float, Float>? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null
        val xStart = maxOf(0, inputBounds.right - (width * 0.24f).toInt())
        val xEnd = width
        val yStart = maxOf(0, inputBounds.centerY() - (height * 0.09f).toInt())
        val yEnd = minOf(height, inputBounds.centerY() + (height * 0.09f).toInt())
        var count = 0
        var sumX = 0L
        var sumY = 0L
        val step = 3
        var y = yStart
        while (y < yEnd) {
            var x = xStart
            while (x < xEnd) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                if (r > 165 && g < 115 && b < 135 && r - g > 65) {
                    count++
                    sumX += x
                    sumY += y
                }
                x += step
            }
            y += step
        }
        if (count < 25) {
            lastVisionSummary = "截图视觉：输入框右侧没找到红色发送图标（红色像素样本=$count）"
            return null
        }
        val cx = (sumX / count).toFloat() / width
        val cy = (sumY / count).toFloat() / height
        lastVisionSummary = "截图视觉：找到红色发送图标候选 x=${"%.3f".format(cx)} y=${"%.3f".format(cy)} 红色像素样本=$count"
        return cx.coerceIn(0.01f, 0.99f) to cy.coerceIn(0.01f, 0.99f)
    }

    /** 找发送键：只认可点击且可用的控件；文字发送键优先，图标发送键按输入框右侧位置匹配。 */
    private fun findSendButton(root: AccessibilityNodeInfo, input: AccessibilityNodeInfo?): Pair<AccessibilityNodeInfo, String>? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val keywords = listOf("发送", "发布", "发表", "Send", "Post")
        val all = mutableListOf<AccessibilityNodeInfo>()
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            all.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        val rootBounds = nodeBounds(root)
        val inputBounds = input?.let { nodeBounds(it) }
        var best: AccessibilityNodeInfo? = null
        var bestMethod = ""
        var bestScore = Int.MIN_VALUE
        for (node in all) {
            if (node === input) continue
            val clickable = clickableAncestor(node)
            if (clickable != null && !clickable.isEnabled) continue
            val target = clickable ?: node
            val b = nodeBounds(target)
            if (b.width() <= 0 || b.height() <= 0) continue
            val combinedText = nodeText(node) + " " + nodeText(target)
            val textMatch = keywords.any { combinedText.contains(it) }
            val nearInput = inputBounds == null ||
                (b.centerX() > inputBounds.centerX() && kotlin.math.abs(b.centerY() - inputBounds.centerY()) <= maxOf(180, inputBounds.height() * 3))
            val lowerArea = b.centerY() > rootBounds.height() * 0.4
            val verticalDistance = if (inputBounds != null) kotlin.math.abs(b.centerY() - inputBounds.centerY()) else 0
            val clickableBonus = if (clickable != null) 250 else 0
            val score: Int
            val method: String
            if (textMatch && nearInput) {
                score = 1200 + clickableBonus + (if (lowerArea) 100 else 0) - verticalDistance
                method = "文字匹配"
            } else if (textMatch && clickable != null) {
                score = 900 + clickableBonus + (if (lowerArea) 100 else 0) - verticalDistance
                method = "文字匹配"
            } else if (nearInput && lowerArea && b.width() <= rootBounds.width() * 0.3 && b.height() <= rootBounds.height() * 0.16) {
                score = 600 + clickableBonus + b.centerX() - verticalDistance
                method = "输入框右侧图标匹配"
            } else {
                continue
            }
            if (score > bestScore) {
                bestScore = score
                best = target
                bestMethod = method
            }
        }
        return best?.let { it to bestMethod }
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
        repeat(5) {
            val current = cur ?: return false
            if (current.isClickable && current.isEnabled && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = current.parent
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

    private fun inputContains(node: AccessibilityNodeInfo, probe: String): Boolean {
        return node.text?.toString()?.contains(probe) == true
    }

    private fun pasteText(node: AccessibilityNodeInfo, text: String, probe: String): Boolean {
        return try {
            val clipboard = getSystemService(ClipboardManager::class.java) ?: return false
            clipboard.setPrimaryClip(ClipData.newPlainText("automation_comment", text))
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            pasted && inputContains(node, probe)
        } catch (_: Exception) {
            false
        }
    }

    private fun fillCommentText(node: AccessibilityNodeInfo, text: String, probe: String): Boolean {
        if (setText(node, text) && inputContains(node, probe)) return true
        val fresh = rootInActiveWindow?.let { findEditableCommentBox(it) } ?: node
        if (inputContains(fresh, probe)) return true
        pasteTried = true
        return pasteText(fresh, text, probe)
    }

    // ---------- 点位校准浮窗与坐标点击 ----------

    private var calibrationRoot: FrameLayout? = null

    /** 全屏十字线：横线竖线贯穿全屏，圆心就是要保存的点击位置，拖动任意处移动。 */
    private inner class CrosshairView(context: Context, private val labelText: String) : View(context) {
        var cx: Float = 0f
        var cy: Float = 0f
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.FILL
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            textSize = 44f
            isFakeBoldText = true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawLine(0f, cy, width.toFloat(), cy, linePaint)
            canvas.drawLine(cx, 0f, cx, height.toFloat(), linePaint)
            canvas.drawCircle(cx, cy, 70f, linePaint)
            canvas.drawCircle(cx, cy, 10f, dotPaint)
            canvas.drawText(labelText, (cx + 84f).coerceAtMost(width - 260f), (cy - 84f).coerceAtLeast(60f), textPaint)
        }
    }

    /** 双点位预览：评论点（绿）和发送点（红）同时显示在悬浮窗里，拖动哪个就保存哪个。 */
    private inner class PreviewView(context: Context, private val platform: String) : View(context) {
        private val dm = resources.displayMetrics
        private var commentX: Float? = PointStore.load(context, platform, PointStore.KIND_COMMENT)?.x?.times(dm.widthPixels)
        private var commentY: Float? = PointStore.load(context, platform, PointStore.KIND_COMMENT)?.y?.times(dm.heightPixels)
        private var sendX: Float? = PointStore.load(context, platform, PointStore.KIND_SEND)?.x?.times(dm.widthPixels)
        private var sendY: Float? = PointStore.load(context, platform, PointStore.KIND_SEND)?.y?.times(dm.heightPixels)
        private var dragging: String? = null

        private fun paint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        private fun textPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = 46f
            isFakeBoldText = true
        }

        private fun drawPoint(canvas: Canvas, x: Float?, y: Float?, color: Int, label: String) {
            if (x == null || y == null) return
            canvas.drawCircle(x, y, 66f, paint(color))
            canvas.drawLine(x - 90f, y, x + 90f, y, paint(color))
            canvas.drawLine(x, y - 90f, x, y + 90f, paint(color))
            canvas.drawText(label, (x + 78f).coerceAtMost(width - 220f), (y - 78f).coerceAtLeast(60f), textPaint(color))
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            drawPoint(canvas, commentX, commentY, Color.GREEN, "评论点")
            drawPoint(canvas, sendX, sendY, Color.RED, "发送点")
            if (commentX == null && sendX == null) {
                canvas.drawText("这个平台还没配点位", 60f, height / 2f, textPaint(Color.RED))
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val dc = if (commentX != null && commentY != null)
                        kotlin.math.hypot(event.x - commentX!!, event.y - commentY!!) else Float.MAX_VALUE
                    val ds = if (sendX != null && sendY != null)
                        kotlin.math.hypot(event.x - sendX!!, event.y - sendY!!) else Float.MAX_VALUE
                    dragging = when {
                        dc <= ds && dc < 220f -> PointStore.KIND_COMMENT
                        ds < 220f -> PointStore.KIND_SEND
                        else -> null
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    when (dragging) {
                        PointStore.KIND_COMMENT -> { commentX = event.x; commentY = event.y }
                        PointStore.KIND_SEND -> { sendX = event.x; sendY = event.y }
                    }
                    if (dragging != null) invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val kind = dragging
                    dragging = null
                    if (kind != null && width > 0 && height > 0) {
                        val fx = (if (kind == PointStore.KIND_COMMENT) commentX else sendX)!! / width
                        val fy = (if (kind == PointStore.KIND_COMMENT) commentY else sendY)!! / height
                        PointStore.save(this@AutomationService, platform, kind, fx, fy)
                        val kindName = if (kind == PointStore.KIND_SEND) "发送点" else "评论点"
                        TaskStore.appendLog(this@AutomationService, "点位已保存：$platform $kindName X ${(fx * 100).toInt()}% · Y ${(fy * 100).toInt()}%")
                    }
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }

    private fun overlayParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun topBar(labelText: String, buttons: List<Button>): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(210, 0, 0, 0))
            setPadding(24, 16, 24, 16)
        }
        val label = TextView(this).apply {
            text = labelText
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        bar.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.forEach { bar.addView(it) }
        return bar
    }

    fun showCalibration(platform: String, kind: String) {
        removeCalibration()
        val wm = getSystemService(WindowManager::class.java) ?: return
        val root = FrameLayout(this)
        val kindName = if (kind == PointStore.KIND_SEND) "发送点" else "评论点"
        val cross = CrosshairView(this, kindName)
        val saved = PointStore.load(this, platform, kind)
        root.post {
            cross.cx = (saved?.x ?: 0.5f) * root.width
            cross.cy = (saved?.y ?: 0.5f) * root.height
            cross.invalidate()
        }
        cross.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                cross.cx = event.x.coerceIn(0f, root.width.toFloat())
                cross.cy = event.y.coerceIn(0f, root.height.toFloat())
                cross.invalidate()
            }
            true
        }
        fun nudge(dx: Float, dy: Float) {
            cross.cx = (cross.cx + dx).coerceIn(0f, root.width.toFloat())
            cross.cy = (cross.cy + dy).coerceIn(0f, root.height.toFloat())
            cross.invalidate()
        }
        val saveBtn = Button(this).apply {
            text = "保存点位"
            setOnClickListener {
                if (root.width <= 0 || root.height <= 0) {
                    Toast.makeText(this@AutomationService, "校准窗还没准备好，先等一下再保存", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val fx = cross.cx / root.width
                val fy = cross.cy / root.height
                val savedOk = PointStore.save(this@AutomationService, platform, kind, fx, fy)
                val verified = PointStore.load(this@AutomationService, platform, kind)
                if (savedOk && verified != null) {
                    TaskStore.appendLog(
                        this@AutomationService,
                        "点位已保存并验证：$platform $kindName X ${(verified.x * 100).toInt()}% · Y ${(verified.y * 100).toInt()}%，以后评论按这个位置点。"
                    )
                    Toast.makeText(this@AutomationService, "点位已保存：$platform $kindName", Toast.LENGTH_SHORT).show()
                } else {
                    TaskStore.appendLog(this@AutomationService, "点位保存失败：$platform $kindName 没写进去")
                    Toast.makeText(this@AutomationService, "点位保存失败", Toast.LENGTH_SHORT).show()
                }
                removeCalibration()
                afterSettingOverlayClosed()
            }
        }
        val cancelBtn = Button(this).apply {
            text = "取消"
            setOnClickListener {
                removeCalibration()
                afterSettingOverlayClosed()
            }
        }
        val bar = topBar("$platform $kindName：十字圆心对准按键中心", listOf(saveBtn, cancelBtn))
        val nudgeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            setPadding(24, 8, 24, 8)
        }
        listOf("←" to (-6f to 0f), "→" to (6f to 0f), "↑" to (0f to -6f), "↓" to (0f to 6f)).forEach { (label, d) ->
            nudgeBar.addView(Button(this).apply {
                text = label
                setOnClickListener { nudge(d.first, d.second) }
            })
        }
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bottom.addView(nudgeBar)
        root.addView(cross, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        root.addView(bottom, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        try {
            wm.addView(root, overlayParams())
            calibrationRoot = root
            TaskStore.appendLog(this, "校准悬浮窗已打开：$platform $kindName，切到 $platform 视频页，十字圆心对准按键中心点保存。")
        } catch (e: Exception) {
            TaskStore.appendLog(this, "校准悬浮窗打开失败：${e.message}")
        }
    }

    /** 点位预览悬浮窗：把已保存的评论点、发送点直接显示在平台界面上，拖动即可改。 */
    fun showPointPreview(platform: String) {
        removeCalibration()
        val wm = getSystemService(WindowManager::class.java) ?: return
        val root = FrameLayout(this)
        val preview = PreviewView(this, platform)
        val closeBtn = Button(this).apply {
            text = "关闭悬浮窗"
            setOnClickListener { removeCalibration() }
        }
        val bar = topBar("$platform 点位悬浮窗：绿=评论点 红=发送点，拖动可改", listOf(closeBtn))
        root.addView(preview, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        try {
            wm.addView(root, overlayParams())
            calibrationRoot = root
            TaskStore.appendLog(this, "点位悬浮窗已打开：$platform，切到 $platform 视频页就能看到评论点和发送点对的位置。")
        } catch (e: Exception) {
            TaskStore.appendLog(this, "点位悬浮窗打开失败：${e.message}")
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

    private fun afterSettingOverlayClosed() {
        if (pausedForHuman) showInterventionPanel(lastFailureReason.ifBlank { "悬浮控制窗：点位和状态已更新" })
    }


    // ---------- 模拟真人器：记录点击 + 真实回放 ----------

    private inner class RecorderView(context: Context) : View(context) {
        val points = mutableListOf<Pair<Float, Float>>()
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.FILL
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 40f
            isFakeBoldText = true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            points.forEachIndexed { i, pt ->
                canvas.drawCircle(pt.first, pt.second, 46f, ringPaint)
                canvas.drawCircle(pt.first, pt.second, 30f, dotPaint)
                canvas.drawText("${i + 1}", pt.first - 12f, pt.second + 14f, textPaint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_UP) {
                points.add(event.x to event.y)
                invalidate()
                TaskStore.appendLog(this@AutomationService, "已记录第 ${points.size} 个点击位置")
            }
            return true
        }
    }

    fun showRecorder(platform: String) {
        removeCalibration()
        val wm = getSystemService(WindowManager::class.java) ?: return
        val root = FrameLayout(this)
        val recorder = RecorderView(this)
        val doneBtn = Button(this).apply {
            text = "完成记录"
            setOnClickListener {
                if (root.width > 0 && root.height > 0 && recorder.points.isNotEmpty()) {
                    val steps = recorder.points.map { pt ->
                        TapSequenceStore.Step(
                            TapSequenceStore.TYPE_TAP,
                            pt.first / root.width,
                            pt.second / root.height,
                            1
                        )
                    }
                    TapSequenceStore.saveSteps(this@AutomationService, platform, steps)
                    TaskStore.appendLog(this@AutomationService, "点击序列已保存：$platform 共 ${steps.size} 个点位，软件记住了。")
                    android.widget.Toast.makeText(this@AutomationService, "$platform 已记住 ${steps.size} 个点位", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    TaskStore.appendLog(this@AutomationService, "记录取消：还没有点任何位置。")
                }
                removeCalibration()
                afterSettingOverlayClosed()
            }
        }
        val cancelBtn = Button(this).apply {
            text = "取消"
            setOnClickListener {
                removeCalibration()
                afterSettingOverlayClosed()
            }
        }
        val bar = topBar("记录 $platform 点击：切到 $platform，按顺序点你要点的按键", listOf(doneBtn, cancelBtn))
        root.addView(recorder, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        try {
            wm.addView(root, overlayParams())
            calibrationRoot = root
            android.widget.Toast.makeText(this, "记录悬浮窗已打开", android.widget.Toast.LENGTH_SHORT).show()
            TaskStore.appendLog(this, "记录悬浮窗已打开：切到 $platform，按顺序点评论键、输入框、发送键，点完按完成记录。")
        } catch (e: Exception) {
            TaskStore.appendLog(this, "记录悬浮窗打开失败：${e.message}")
            android.widget.Toast.makeText(this, "悬浮窗打开失败：${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** 按记录的序列真实回放：点位逐个点（每点按设置次数），填评论步填入并验证。 */
    fun runSequence(platform: String, comment: String) {
        val steps = TapSequenceStore.loadSteps(this, platform)
        if (steps.isEmpty()) {
            val reason = "执行失败：$platform 还没有记录点击，先点“开始记录点击”把位置记下来。"
            TaskStore.appendLog(this, reason)
            saveCurrentErrorReport(this, reason, "")
            showInterventionPanel(reason)
            return
        }
        val repeat = TapSequenceStore.loadRepeat(this, platform)
        TaskStore.appendLog(this, "开始执行 $platform 点击序列：${steps.size} 步，重复 $repeat 遍。")
        execStep(platform, steps, comment, 1, repeat, 0)
    }

    private fun execStep(platform: String, steps: List<TapSequenceStore.Step>, comment: String, round: Int, repeat: Int, index: Int) {
        if (index >= steps.size) {
            if (round < repeat) {
                TaskStore.appendLog(this, "第 $round 遍完成，继续第 ${round + 1} 遍…")
                handler.postDelayed({ execStep(platform, steps, comment, round + 1, repeat, 0) }, 1200L)
            } else {
                TaskStore.appendLog(this, "点击序列执行完成（$platform，共 $repeat 遍）。")
            }
            return
        }
        val step = steps[index]
        val nextDelay = 600L + kotlin.random.Random.nextLong(0, 500)
        if (step.type == TapSequenceStore.TYPE_FILL) {
            val root = rootInActiveWindow
            val input = root?.let { findEditableCommentBox(it) }
            if (input != null && setText(input, comment)) {
                val probe = comment.take(6)
                val verified = rootInActiveWindow?.let { findEditableCommentBox(it)?.text?.toString()?.contains(probe) } == true
                TaskStore.appendLog(this, "第 ${index + 1} 步填评论：${if (verified) "已填入并验证" else "已填入，未验证到文字"}")
                if (!verified) {
                    val reason = "第 ${index + 1} 步填评论失败：填入后没有验证到文字，已暂停等真人介入。"
                    saveCurrentErrorReport(this, reason, "")
                    showInterventionPanel(reason)
                    return
                }
            } else {
                val reason = "第 ${index + 1} 步填评论失败：找不到可填的输入框（先点输入框再填），已暂停等真人介入。"
                TaskStore.appendLog(this, reason)
                saveCurrentErrorReport(this, reason, "")
                showInterventionPanel(reason)
                return
            }
            handler.postDelayed({ execStep(platform, steps, comment, round, repeat, index + 1) }, nextDelay)
            return
        }
        tapTimes(step, step.count) {
            handler.postDelayed({ execStep(platform, steps, comment, round, repeat, index + 1) }, nextDelay)
        }
    }

    private fun tapTimes(step: TapSequenceStore.Step, remaining: Int, done: () -> Unit) {
        if (remaining <= 0) {
            done()
            return
        }
        tapAtFraction(step.x, step.y, "真实点击") { ok ->
            if (!ok) {
                val reason = "真实点击失败：手势没有完成（X ${(step.x * 100).toInt()}% · Y ${(step.y * 100).toInt()}%），已暂停等真人介入。"
                saveCurrentErrorReport(this, reason, "")
                showInterventionPanel(reason)
                return@tapAtFraction
            }
            handler.postDelayed({ tapTimes(step, remaining - 1, done) }, 380L)
        }
    }

    /** 按保存的屏幕比例真实点击一次（手势注入，点的是屏幕坐标），并等待系统完成/取消回调。 */
    private fun tapAtFraction(x: Float, y: Float, label: String, onDone: ((Boolean) -> Unit)? = null) {
        val dm = resources.displayMetrics
        val px = x * dm.widthPixels
        val py = y * dm.heightPixels
        val path = Path().apply { moveTo(px, py) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        var finished = false
        fun finish(ok: Boolean, message: String) {
            if (finished) return
            finished = true
            TaskStore.appendLog(this@AutomationService, message)
            onDone?.invoke(ok)
        }
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                finish(true, "$label：手势已完成（X ${(x * 100).toInt()}% · Y ${(y * 100).toInt()}%）")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                finish(false, "$label：坐标点击被系统取消（X ${(x * 100).toInt()}% · Y ${(y * 100).toInt()}%）")
            }
        }, handler)
        if (!dispatched) {
            finish(false, "$label：手势派发失败（X ${(x * 100).toInt()}% · Y ${(y * 100).toInt()}%）")
        } else {
            TaskStore.appendLog(this, "$label：手势已派发，等待系统完成回调（X ${(x * 100).toInt()}% · Y ${(y * 100).toInt()}%）")
        }
    }

    override fun onInterrupt() {
        running = false
        publishRunning = false
    }

    override fun onDestroy() {
        removeCalibration()
        removeIntervention()
        super.onDestroy()
        instance = null
    }
}
