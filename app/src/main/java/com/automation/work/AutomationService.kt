package com.automation.work

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
 * 只用无障碍服务和它自己的悬浮层。流程（一条用户手动开始的链接）：
 *  步骤1-3 LinkResolver 在后台线程解析链接、直开对应平台 App（绝不走浏览器）
 *  步骤4 验证当前界面确实是目标 App（被浏览器接走就报失败）
 *  步骤5 找评论入口并点开
 *  步骤6 填入评论
 *  步骤7 点发送
 *  步骤8 验证评论真的发出去了（输入框清空或评论出现在界面上）
 * 每一步失败都写明卡在哪一步、为什么，不谎报成功。
 *
 * 本版只处理一条用户手动开始的链接评论任务；触发自检未通过时不开始评论。
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

        // 触发优先门禁：服务实例、悬浮按钮添加结果、触发自检回显都必须真实成立。
        @Volatile var overlayReady: Boolean = false
        @Volatile var overlayStatus: String = "悬浮按钮未添加：无障碍服务未连接"
        @Volatile var triggerSelfTestPassed: Boolean = false
        @Volatile var triggerSelfTestStatus: String = "触发自检未通过：尚未运行测试触发"
        @Volatile var triggerSelfTestAtMillis: Long = 0L
        @Volatile var lastTriggerEchoAtMillis: Long = 0L
        @Volatile var lastTriggerEchoSource: String = ""
        @Volatile var overlayTapEchoCount: Int = 0
        @Volatile var lastOverlayTapEchoAtMillis: Long = 0L
        private var pendingSelfTestStartedAt: Long = 0L
        private var pendingSelfTestSource: String = ""

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
        var publishBaselineSuccessCount: Int = -1
        var publishClicked: Boolean = false

        // 搜索辅助与点位循环状态：搜索只停在结果页；循环只跑导航/测试语义点，禁止 SEND。
        var searchRunning: Boolean = false
        var searchPlatform: String = ""
        var searchPackage: String = ""
        var searchTopic: String = ""
        var searchStage: Int = 0
        var searchAttempts: Int = 0
        var searchCandidateCap: Int = 10
        var searchSessionKeys: MutableSet<String> = linkedSetOf()
        var currentTargetKey: String = ""
        var currentPageMode: Boolean = false
        var loopRunning: Boolean = false
        var loopPlatform: String = ""
        var loopRoles: List<String> = emptyList()
        var loopRounds: Int = 1
        var loopIntervalSeconds: Int = 2
        var loopRound: Int = 1
        var loopIndex: Int = 0

        private val handler = Handler(Looper.getMainLooper())
        private var stepRunnable: Runnable? = null
        private var publishRunnable: Runnable? = null

        private fun timeText(millis: Long): String {
            if (millis <= 0L) return "无"
            return java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(millis))
        }

        fun gateFailureReason(): String? {
            if (instance == null) {
                return "步骤0 失败：无障碍服务没在运行，请先在设置 > 无障碍里开启“自动化屏幕控制”。"
            }
            if (!overlayReady) {
                return "步骤0 失败：悬浮按钮未有效启用：$overlayStatus"
            }
            if (!triggerSelfTestPassed) {
                return "步骤0 失败：触发自检未通过：$triggerSelfTestStatus"
            }
            return null
        }

        fun statusSummary(): String {
            val serviceText = if (instance == null) "未连接" else "已连接"
            val overlayEcho = if (lastOverlayTapEchoAtMillis > 0L) {
                "已回显 $overlayTapEchoCount 次，最近 ${timeText(lastOverlayTapEchoAtMillis)}"
            } else {
                "未回显"
            }
            return "无障碍服务：$serviceText\n悬浮按钮：$overlayStatus\n触发自检：$triggerSelfTestStatus\n悬浮按钮回显：$overlayEcho\n任务状态：$lastStatus"
        }

        fun markTriggerEcho(context: Context, source: String) {
            lastTriggerEchoAtMillis = System.currentTimeMillis()
            lastTriggerEchoSource = source
            TaskStore.appendLog(context, "触发回显：$source 已收到点击回显，时间=${timeText(lastTriggerEchoAtMillis)}")
        }

        fun runTriggerSelfTest(context: Context, xPx: Int, yPx: Int, source: String, onResult: (Boolean, String) -> Unit) {
            val svc = instance
            if (svc == null) {
                triggerSelfTestPassed = false
                triggerSelfTestStatus = "触发自检失败：无障碍服务未连接，无法派发手势"
                TaskStore.appendLog(context, triggerSelfTestStatus)
                onResult(false, triggerSelfTestStatus)
                return
            }
            svc.runTriggerSelfTestInternal(xPx, yPx, source, onResult)
        }

        fun showOverlayNow(context: Context) {
            val svc = instance
            if (svc == null) {
                overlayReady = false
                overlayStatus = "悬浮按钮添加失败：无障碍服务未连接，无法调用 WindowManager.addView"
                TaskStore.appendLog(context, overlayStatus)
                return
            }
            svc.showMiniOverlay()
        }

        fun startTask(context: Context, task: CommentTask) {
            val gate = gateFailureReason()
            if (gate != null) {
                running = false
                lastStatus = gate
                TaskStore.appendLog(context, lastStatus)
                return
            }
            if (task.items.size != 1) {
                running = false
                lastStatus = "步骤0 失败：本版只允许一条用户手动开始的链接任务，收到 ${task.items.size} 条。"
                TaskStore.appendLog(context, lastStatus)
                return
            }
            if (task.commentText.isBlank() || task.items.first().link.isBlank()) {
                running = false
                lastStatus = "步骤0 失败：链接或评论为空，不能开始。"
                TaskStore.appendLog(context, lastStatus)
                return
            }
            currentTask = task
            currentIndex = 0
            currentPageMode = false
            currentTargetKey = ""
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

        /** 点位校准：弹出可拖动标记，用户把标记拖到语义点位正中心保存。 */
        fun startCalibration(context: Context, platform: String, kind: String) {
            val svc = instance
            if (svc == null) {
                TaskStore.appendLog(context, "校准失败：无障碍服务没在运行，请先在设置 > 无障碍里开启“自动化屏幕控制”。")
                return
            }
            svc.showCalibration(platform, PointStore.kindToRole(kind))
        }

        fun startSyncRecognition(context: Context, platform: String) {
            val svc = instance
            if (svc == null) {
                TaskStore.appendLog(context, "同步识别失败：无障碍服务没在运行，请先开启无障碍。")
                return
            }
            svc.startSyncRecognitionInternal(platform)
        }

        fun testSemanticPoint(context: Context, platform: String, role: String) {
            val svc = instance
            if (svc == null) {
                TaskStore.appendLog(context, "点位真实点击验证失败：无障碍服务没在运行，请先开启无障碍。")
                return
            }
            svc.testSemanticPointInternal(platform, PointStore.kindToRole(role))
        }

        fun startSearchAssist(context: Context, platform: String, topic: String, quantity: Int = 10) {
            val gate = gateFailureReason()
            if (gate != null) {
                lastStatus = gate
                TaskStore.appendLog(context, gate)
                return
            }
            if (topic.isBlank()) {
                lastStatus = "搜索辅助失败：先填写话题。"
                TaskStore.appendLog(context, lastStatus)
                return
            }
            instance?.startSearchAssistInternal(platform, topic.trim(), quantity.coerceIn(1, 100))
        }

        fun startCommentCurrentPage(context: Context, platform: String, comment: String) {
            val gate = gateFailureReason()
            if (gate != null) {
                lastStatus = gate
                TaskStore.appendLog(context, gate)
                return
            }
            if (comment.isBlank()) {
                lastStatus = "评论这一条失败：先填写评论内容。"
                TaskStore.appendLog(context, lastStatus)
                return
            }
            instance?.startCommentCurrentPageInternal(platform, comment)
        }

        fun startPointLoop(context: Context, platform: String, roles: List<String>, rounds: Int, intervalSeconds: Int) {
            val gate = gateFailureReason()
            if (gate != null) {
                lastStatus = gate
                TaskStore.appendLog(context, gate)
                return
            }
            instance?.startPointLoopInternal(platform, roles, rounds, intervalSeconds)
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

        fun stopTask(context: Context) {
            running = false
            publishRunning = false
            searchRunning = false
            loopRunning = false
            pausedForHuman = false
            stepRunnable?.let { handler.removeCallbacks(it) }
            publishRunnable?.let { handler.removeCallbacks(it) }
            instance?.cancelSyncRecognition()
            lastStatus = "任务已停止"
            TaskStore.appendLog(context, lastStatus)
            instance?.showMiniOverlay()
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
                    currentTargetKey = result.targetKey
                    if (TaskStore.hasCommentedTarget(context, currentTargetKey)) {
                        val reason = "防重复拦截：这个视频已验证评论过，防重复键=$currentTargetKey；已停止，不重复评论同一视频。"
                        lastStatus = reason
                        TaskStore.appendLog(context, reason)
                        instance?.interveneCurrent(context, task, reason, "DEDUPE")
                        return@post
                    }
                    TaskStore.appendLog(context, "步骤4 等待目标窗口：期望前台包名=${result.packageName}，打开方式=${result.method}，防重复键=$currentTargetKey；超时内验证，浏览器在前台或目标未到前台会停在步骤4。")
                    instance?.resetSendState()
                    instance?.scheduleStep(context, 3000L)
                }
            }.start()
        }

        fun markItemSuccess(context: Context) {
            successCount++
            if (currentTargetKey.isNotBlank()) {
                val added = TaskStore.recordCommentedTarget(context, currentTargetKey)
                TaskStore.appendLog(context, "防重复已记录：防重复键=$currentTargetKey，新增记录=$added，已记录总数=${TaskStore.commentedTargetCount(context)}。")
            }
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

        fun startDirectOpenTest(context: Context, link: String) {
            val gate = gateFailureReason()
            if (gate != null) {
                lastStatus = gate
                TaskStore.appendLog(context, gate)
                return
            }
            if (link.isBlank()) {
                lastStatus = "直达测试失败：先填写视频链接。"
                TaskStore.appendLog(context, lastStatus)
                return
            }
            instance?.showMiniOverlay()
            lastStatus = "直达测试开始：正在应用内解析链接并打开目标 App…"
            TaskStore.appendLog(context, lastStatus)
            Thread {
                val result = LinkResolver.openVideoLinkDetailed(context, link)
                handler.post {
                    if (!result.success) {
                        lastStatus = result.reason
                        TaskStore.appendLog(context, "直达测试停止：${result.reason}")
                        TaskStore.saveErrorReport(
                            context,
                            "错误报告\n时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}\n任务：直达测试\n链接：$link\n平台：${result.platform}\n期望包名：${result.packageName ?: "未知"}\n错误：${result.reason}\n"
                        )
                        instance?.showInterventionPanel(result.reason, pauseAutomation = false)
                        return@post
                    }
                    expectedPackage = result.packageName
                    expectedPlatform = result.platform
                    lastStatus = "直达测试：VIEW intent 已发出，等待目标 App 到前台（期望包名=${result.packageName}）"
                    TaskStore.appendLog(context, "步骤4 等待目标窗口：期望前台包名=${result.packageName}，打开方式=${result.method}；超时内验证，浏览器在前台或目标未到前台会停在步骤4。")
                    instance?.scheduleDirectVerify(context, 1500L)
                }
            }.start()
        }

        fun startPublishAssistByLink(context: Context, link: String, caption: String) {
            val gate = gateFailureReason()
            if (gate != null) {
                lastStatus = gate
                TaskStore.appendLog(context, gate)
                return
            }
            if (link.isBlank()) {
                lastStatus = "发布辅助失败：先填写视频链接。"
                TaskStore.appendLog(context, lastStatus)
                return
            }
            instance?.showMiniOverlay()
            publishRunning = false
            publishCaption = caption
            publishAttempts = 0
            publishBaselineSuccessCount = -1
            publishClicked = false
            lastStatus = "发布辅助开始：正在应用内解析视频链接并直达目标 App…"
            TaskStore.appendLog(context, lastStatus)
            Thread {
                val result = LinkResolver.openVideoLinkDetailed(context, link)
                handler.post {
                    expectedPackage = result.packageName
                    expectedPlatform = result.platform
                    publishPlatform = result.platform
                    publishPackage = result.packageName ?: ""
                    if (!result.success) {
                        publishRunning = false
                        lastStatus = result.reason
                        TaskStore.appendLog(context, "发布辅助停止：${result.reason}")
                        TaskStore.saveErrorReport(
                            context,
                            "错误报告\n时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}\n任务：发布辅助\n链接：$link\n平台：${result.platform}\n期望包名：${result.packageName ?: "未知"}\n错误：${result.reason}\n"
                        )
                        instance?.showInterventionPanel(result.reason, pauseAutomation = false)
                        return@post
                    }
                    publishRunning = true
                    pausedForHuman = false
                    lastFailureReason = ""
                    lastFailureKind = ""
                    lastStatus = "发布辅助：已直达${result.platform}，等待目标窗口和发布页验证…"
                    TaskStore.appendLog(context, "步骤4 等待目标窗口：期望前台包名=${result.packageName}，打开方式=${result.method}；发布成功只认点击后新出现的发布成功状态。")
                    instance?.schedulePublishStep(context, 2500L)
                }
            }.start()
        }
    }

    private var attempts: Int = 0
    private var directVerifyAttempts: Int = 0
    private var sendStage: Int = 0
    private var sendWaitAttempts: Int = 0
    private var pasteTried: Boolean = false
    private var lastVisionSummary: String = "未使用截图视觉兜底"
    private var preSendProbeCount: Int = 0
    private var interventionRoot: View? = null
    private var overlaySelfTestTarget: Button? = null
    private var interventionNote: EditText? = null
    private var interventionStatus: TextView? = null
    private var interventionSnapshot: TextView? = null
    private var panelRefreshScheduled: Boolean = false
    private var overlayPlatform: String = "抖音"
    private var overlayMode: String = "none"
    private var panelReason: String = ""
    private var gestureInProgress: Boolean = false
    private var semanticTapInProgress: Boolean = false
    private var syncActive: Boolean = false
    private var syncPlatform: String = "抖音"
    private var syncBefore: WindowSnapshot? = null
    private var syncTappedNode: NodeSignature? = null
    private var syncPollAttempts: Int = 0
    private var lastEventClassName: String = ""
    private var searchRunnable: Runnable? = null

    private data class NodeSignature(
        val text: String,
        val description: String,
        val className: String,
        val viewId: String,
        val x: Float,
        val y: Float,
        val boundsText: String,
        val clickable: Boolean,
        val editable: Boolean,
        val focused: Boolean
    ) {
        fun summary(): String {
            val label = listOf(text, description).firstOrNull { it.isNotBlank() }?.take(40) ?: ""
            return "text=$label class=$className viewId=$viewId bounds=$boundsText clickable=$clickable editable=$editable focused=$focused"
        }
    }

    private data class WindowSnapshot(
        val packageName: String,
        val activityName: String,
        val signature: String,
        val allText: String,
        val hasCommentInput: Boolean,
        val hasCommentPanel: Boolean,
        val hasSearchEditable: Boolean,
        val searchFocused: Boolean,
        val hasResults: Boolean,
        val hasSend: Boolean,
        val editableText: String,
        val outsideProbeCount: Int,
        val signatures: List<NodeSignature>
    )

    private data class SyncCandidate(
        val role: String,
        val label: String,
        val x: Float,
        val y: Float,
        val nodeSignature: String,
        val evidence: String,
        val confidence: Int
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlayReady = false
        overlayStatus = "无障碍服务已连接，正在用 TYPE_ACCESSIBILITY_OVERLAY 添加悬浮按钮"
        triggerSelfTestPassed = false
        triggerSelfTestStatus = "触发自检未通过：服务刚连接，尚未运行测试触发"
        triggerSelfTestAtMillis = 0L
        TaskStore.appendLog(this, "无障碍服务已连接：服务实例已建立，立即添加悬浮按钮；评论开始前必须先通过测试触发。")
        showMiniOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        lastEventClassName = event.className?.toString() ?: lastEventClassName
        if (syncActive) {
            handleSyncEvent(event)
            return
        }
        if (gestureInProgress) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        if (interventionRoot == null && calibrationRoot == null) {
            when (overlayMode) {
                "panel" -> showInterventionPanel(panelReason.ifBlank { "悬浮控制窗" }, pauseAutomation = pausedForHuman)
                "mini" -> showMiniOverlay()
                "manual" -> showManualBar()
                else -> {
                    if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && !overlayReady) {
                        showMiniOverlay()
                    }
                }
            }
        }
        if (pausedForHuman) return
        if (publishRunning) {
            schedulePublishStep(this, 1500L)
            return
        }
        if (searchRunning) {
            scheduleSearchStep(1200L)
            return
        }
        if (!running) return
        scheduleStep(this, 1500L)
    }

    private fun scheduleStep(context: Context, delayMs: Long) {
        if (pausedForHuman || gestureInProgress || semanticTapInProgress) return
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

    private fun scheduleSearchStep(delayMs: Long) {
        if (pausedForHuman || gestureInProgress || semanticTapInProgress) return
        searchRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable { trySearchStep() }
        searchRunnable = r
        handler.postDelayed(r, delayMs)
    }

    private fun scheduleDirectVerify(context: Context, delayMs: Long) {
        handler.postDelayed({ verifyDirectForeground(context) }, delayMs)
    }

    private fun verifyDirectForeground(context: Context) {
        val expected = expectedPackage
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString() ?: ""
        if (expected != null && pkg == expected) {
            directVerifyAttempts = 0
            lastStatus = "直达验证通过：前台包名=$pkg，平台=$expectedPlatform"
            TaskStore.appendLog(context, "步骤4 直达验证通过：期望前台包名=$expected，实际前台包名=$pkg")
            showMiniOverlay()
            return
        }
        if (pkg in browserPackages) {
            val reason = "步骤4 失败：浏览器在前台（实际包名=$pkg），目标 App 没有接管；已停止，不用浏览器页面状态作证明"
            lastStatus = reason
            saveCurrentErrorReport(context, reason, "")
            TaskStore.appendLog(context, reason)
            showInterventionPanel(reason, pauseAutomation = false)
            return
        }
        directVerifyAttempts++
        TaskStore.appendLog(context, "步骤4 直达验证：期望前台包名=${expected ?: "未知"}，实际前台包名=${pkg.ifBlank { "拿不到窗口" }}（第 $directVerifyAttempts 次）")
        if (directVerifyAttempts >= 5) {
            directVerifyAttempts = 0
            val reason = "步骤4 失败：超时后目标 App 仍未到前台（期望包名=${expected ?: "未知"}，实际包名=${pkg.ifBlank { "拿不到窗口" }}）"
            lastStatus = reason
            saveCurrentErrorReport(context, reason, "")
            TaskStore.appendLog(context, reason)
            showInterventionPanel(reason, pauseAutomation = false)
        } else {
            scheduleDirectVerify(context, 2000L)
        }
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

    private fun normalizedText(text: String): String {
        return text.trim().replace(Regex("\\s+"), " ")
    }

    private fun inputMatchesComment(inputText: String, commentText: String): Boolean {
        val input = normalizedText(inputText)
        val comment = normalizedText(commentText)
        if (comment.isBlank()) return false
        return input == comment || input.contains(comment)
    }

    private fun isLoginBlock(text: String): Boolean {
        return listOf("请先登录", "立即登录", "登录后", "登录账号", "未登录", "去登录").any { text.contains(it) }
    }

    private fun isSecurityBlock(text: String): Boolean {
        return listOf(
            "验证码", "滑块", "安全验证", "身份验证", "人机验证", "请完成验证",
            "拖动滑块", "账号异常", "风险提示", "操作频繁", "请稍后再试"
        ).any { text.contains(it) }
    }

    fun resetSendState() {
        sendStage = 0
        sendWaitAttempts = 0
        pasteTried = false
        lastVisionSummary = "未使用截图视觉兜底"
        preSendProbeCount = 0
    }

    private fun packageForPlatform(platform: String): String {
        return when (platform) {
            "抖音" -> "com.ss.android.ugc.aweme"
            "快手" -> "com.smile.gifmaker"
            "小红书" -> "com.xingin.xhs"
            "哔哩哔哩" -> "com.bilibili.app.in"
            "微博" -> "com.sina.weibo"
            else -> ""
        }
    }

    private fun nodeSignature(node: AccessibilityNodeInfo): NodeSignature {
        val b = nodeBounds(node)
        val dm = resources.displayMetrics
        val x = if (dm.widthPixels > 0) b.centerX().toFloat() / dm.widthPixels else 0.5f
        val y = if (dm.heightPixels > 0) b.centerY().toFloat() / dm.heightPixels else 0.5f
        return NodeSignature(
            text = node.text?.toString()?.trim() ?: "",
            description = node.contentDescription?.toString()?.trim() ?: "",
            className = node.className?.toString() ?: "",
            viewId = node.viewIdResourceName ?: "",
            x = x.coerceIn(0f, 1f),
            y = y.coerceIn(0f, 1f),
            boundsText = "${b.left},${b.top},${b.right},${b.bottom}",
            clickable = node.isClickable,
            editable = node.isEditable,
            focused = node.isFocused
        )
    }

    private fun collectSignatures(root: AccessibilityNodeInfo): List<NodeSignature> {
        val out = mutableListOf<NodeSignature>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 220 && out.size < 90) {
            val node = queue.removeFirst()
            visited++
            val text = nodeText(node)
            val relevant = node.isClickable || node.isEditable || node.isFocused ||
                listOf("评论", "搜索", "发送", "发布", "视频", "综合", "全部").any { text.contains(it) }
            if (relevant) out.add(nodeSignature(node))
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return out
    }

    private fun captureWindowSnapshot(topic: String = "", probe: String = ""): WindowSnapshot {
        val root = rootInActiveWindow
        if (root == null) {
            return WindowSnapshot("", lastEventClassName, "no-window", "", false, false, false, false, false, false, "", 0, emptyList())
        }
        val pkg = root.packageName?.toString() ?: ""
        val allText = treeText(root)
        val input = findEditableCommentBox(root)
        val searchBox = findSearchBox(root)
        val signatures = collectSignatures(root)
        val clickableCount = signatures.count { it.clickable }
        val hasCommentInput = input != null
        val hasCommentPanel = hasCommentInput || listOf("全部评论", "评论区", "留下你的精彩评论", "写评论").any { allText.contains(it) }
        val hasSearchEditable = searchBox != null
        val searchFocused = searchBox?.isFocused == true
        val topicHit = topic.isBlank() || allText.contains(topic)
        val resultIndicators = listOf("综合", "视频", "用户", "全部", "相关", "作品", "搜索结果").any { allText.contains(it) }
        val hasResults = topicHit && (resultIndicators || clickableCount >= 8) && !hasSearchEditable
        val hasSend = findSendButton(root, input) != null
        val editableText = input?.text?.toString() ?: searchBox?.text?.toString() ?: ""
        val outsideCount = if (probe.isBlank()) 0 else countOccurrences(textOutsideInput(root, input), probe)
        val textHash = normalizedText(allText).take(800).hashCode()
        val signature = "pkg=$pkg activity=$lastEventClassName input=$hasCommentInput panel=$hasCommentPanel search=$hasSearchEditable focused=$searchFocused results=$hasResults send=$hasSend editableLen=${editableText.length} outside=$outsideCount clickable=$clickableCount textHash=$textHash"
        return WindowSnapshot(pkg, lastEventClassName, signature, allText, hasCommentInput, hasCommentPanel, hasSearchEditable, searchFocused, hasResults, hasSend, editableText, outsideCount, signatures)
    }

    private fun snapshotSummary(snapshot: WindowSnapshot): String {
        val texts = snapshot.signatures.map { listOf(it.text, it.description).firstOrNull { s -> s.isNotBlank() } ?: "" }
            .filter { it.isNotBlank() }.distinct().take(8).joinToString(" / ")
        return "${snapshot.signature} texts=$texts"
    }

    private fun findNodeBySignature(root: AccessibilityNodeInfo, signatureText: String): AccessibilityNodeInfo? {
        if (signatureText.isBlank() || signatureText.contains("无节点签名")) return null
        val viewId = Regex("viewId=([^\\s]+)").find(signatureText)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val label = Regex("text=(.*?) class=").find(signatureText)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var labelMatch: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (viewId.isNotBlank() && node.viewIdResourceName == viewId) return clickableAncestor(node) ?: node
            if (label.isNotBlank() && nodeText(node).contains(label)) labelMatch = clickableAncestor(node) ?: node
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return labelMatch
    }

    private fun findSearchBox(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE
        val rootBounds = nodeBounds(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isEditable) {
                val text = nodeText(node)
                val b = nodeBounds(node)
                var score = 0
                if (text.contains("搜索") || text.contains("search", ignoreCase = true)) score += 800
                if (node.isFocused) score += 300
                if (b.centerY() < rootBounds.height() * 0.45) score += 250
                if (b.width() > rootBounds.width() * 0.3) score += 100
                if (score > bestScore) {
                    bestScore = score
                    best = node
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return if (bestScore >= 250) best else null
    }

    private fun findSearchEntry(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = nodeText(node)
            if (text.contains("搜索") || text.contains("search", ignoreCase = true)) {
                val target = clickableAncestor(node) ?: node
                var score = nodeBounds(target).centerY()
                if (clickableAncestor(node) != null) score += 500
                if (score > bestScore) {
                    bestScore = score
                    best = target
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return best
    }

    private fun findSearchSubmit(root: AccessibilityNodeInfo, input: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val inputBounds = input?.let { nodeBounds(it) }
        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = nodeText(node)
            if (listOf("搜索", "Search", "确定").any { text.contains(it) }) {
                val target = clickableAncestor(node) ?: node
                if (target === input) continue
                val b = nodeBounds(target)
                var score = 0
                if (clickableAncestor(node) != null) score += 500
                if (inputBounds != null && kotlin.math.abs(b.centerY() - inputBounds.centerY()) < 220) score += 300
                if (b.centerX() > nodeBounds(root).width() * 0.55) score += 150
                if (score > bestScore) {
                    bestScore = score
                    best = target
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return best
    }

    private fun findRoleNode(root: AccessibilityNodeInfo, role: String): AccessibilityNodeInfo? {
        return when (PointStore.kindToRole(role)) {
            PointStore.ROLE_COMMENT_ENTRY -> findCommentEntry(root) ?: findPlaceholderInput(root)
            PointStore.ROLE_COMMENT_INPUT -> findEditableCommentBox(root)
            PointStore.ROLE_SEND -> findSendButton(root, findEditableCommentBox(root))?.first
            PointStore.ROLE_SEARCH_BOX -> findSearchBox(root) ?: findSearchEntry(root)
            PointStore.ROLE_SEARCH_SUBMIT -> findSearchSubmit(root, findSearchBox(root))
            PointStore.ROLE_VIDEO_RESULT -> findVideoResultNode(root)
            else -> null
        }
    }

    private fun findVideoResultNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val target = clickableAncestor(node)
            if (target != null) {
                val text = nodeText(node).trim()
                val b = nodeBounds(target)
                if (text.length >= 4 && b.width() > 120 && b.height() > 60) {
                    val score = b.width() + b.height() + text.length
                    if (score > bestScore) {
                        bestScore = score
                        best = target
                    }
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return best
    }

    private fun verifySemanticTransition(role: String, before: WindowSnapshot, after: WindowSnapshot, topic: String, probe: String): Boolean {
        return when (PointStore.kindToRole(role)) {
            PointStore.ROLE_COMMENT_ENTRY -> after.hasCommentInput || (after.hasCommentPanel && !before.hasCommentPanel)
            PointStore.ROLE_COMMENT_INPUT -> after.hasCommentInput && after.signature != before.signature
            PointStore.ROLE_SEND -> {
                val inputCleared = probe.isBlank() || !after.editableText.contains(probe)
                if (probe.isNotBlank()) inputCleared && after.outsideProbeCount > before.outsideProbeCount
                else before.editableText.isNotBlank() && after.editableText.isBlank() && after.hasCommentPanel
            }
            PointStore.ROLE_SEARCH_BOX -> after.hasSearchEditable && (after.searchFocused || !before.hasSearchEditable || after.signature != before.signature)
            PointStore.ROLE_SEARCH_SUBMIT -> after.hasResults || (topic.isNotBlank() && after.allText.contains(topic) && after.signature != before.signature)
            PointStore.ROLE_VIDEO_RESULT -> after.signature != before.signature && (after.hasCommentPanel || after.allText.contains("评论") || !after.hasResults)
            else -> after.signature != before.signature
        }
    }

    private fun executeSemanticPoint(
        platform: String,
        role: String,
        label: String,
        topic: String = "",
        commentProbe: String = "",
        onResult: (Boolean, Boolean, String) -> Unit
    ) {
        val normalizedRole = PointStore.kindToRole(role)
        if (semanticTapInProgress) {
            onResult(false, false, "点位真实点击验证失败：已有语义点位正在执行")
            return
        }
        val point = PointStore.loadSemantic(this, platform, normalizedRole)
        if (point == null) {
            val msg = "点位真实点击验证失败：$platform ${PointStore.roleDisplayName(normalizedRole)} 未配置语义点位"
            TaskStore.appendLog(this, msg)
            onResult(false, false, msg)
            return
        }
        semanticTapInProgress = true
        removeCalibration()
        val before = captureWindowSnapshot(topic, commentProbe)
        fun finishAfterAction(gestureOk: Boolean, method: String) {
            handler.postDelayed({
                val after = captureWindowSnapshot(topic, commentProbe)
                val transitionOk = gestureOk && verifySemanticTransition(normalizedRole, before, after, topic, commentProbe)
                semanticTapInProgress = false
                val msg = "点位真实点击验证：平台=$platform 角色=${PointStore.roleDisplayName(normalizedRole)} 方法=$method 手势完成=$gestureOk 转换验证=$transitionOk 期望=${point.expectedTransition} before=${snapshotSummary(before)} after=${snapshotSummary(after)}"
                TaskStore.appendLog(this, msg)
                onResult(gestureOk, transitionOk, msg)
            }, if (normalizedRole == PointStore.ROLE_SEND) 1500L else 1000L)
        }
        val root = rootInActiveWindow
        val node = root?.let { findNodeBySignature(it, point.nodeSignature) ?: findRoleNode(it, normalizedRole) }
        if (node != null) {
            if (clickNode(node)) {
                TaskStore.appendLog(this, "$label：已按语义签名/角色控件点击，等待转换验证")
                finishAfterAction(true, "控件点击")
            } else {
                tapNodeCenter(node, label) { ok -> finishAfterAction(ok, "控件坐标点击") }
            }
        } else {
            tapAtFraction(point.x, point.y, label) { ok -> finishAfterAction(ok, "语义坐标点击") }
        }
    }

    private fun testSemanticPointInternal(platform: String, role: String) {
        val point = PointStore.loadSemantic(this, platform, role)
        if (point == null) {
            val msg = "点位真实点击验证失败：$platform ${PointStore.roleDisplayName(role)} 未配置，先同步识别或重新校准"
            lastStatus = msg
            TaskStore.appendLog(this, msg)
            showInterventionPanel(msg, pauseAutomation = false)
            return
        }
        lastStatus = "点位真实点击验证进行中：$platform ${PointStore.roleDisplayName(role)}"
        TaskStore.appendLog(this, "点位真实点击验证开始：平台=$platform 角色=${PointStore.roleDisplayName(role)} 标签=${point.label} 坐标 X ${(point.x * 100).toInt()}% · Y ${(point.y * 100).toInt()}%")
        executeSemanticPoint(platform, role, "测试点位点击", searchTopic, "") { gestureOk, transitionOk, msg ->
            lastStatus = if (gestureOk && transitionOk) {
                "点位真实点击验证通过：手势完成且转换验证通过"
            } else {
                "点位真实点击验证失败：手势完成=$gestureOk 转换验证=$transitionOk"
            }
            TaskStore.appendLog(this, lastStatus)
            if (!(gestureOk && transitionOk)) showInterventionPanel(msg, pauseAutomation = false) else showMiniOverlay()
        }
    }

    private fun startSyncRecognitionInternal(platform: String) {
        syncActive = true
        syncPlatform = platform
        overlayPlatform = platform
        syncTappedNode = null
        syncPollAttempts = 0
        val rootPkg = rootInActiveWindow?.packageName?.toString() ?: ""
        syncBefore = if (rootPkg.isNotBlank() && rootPkg != packageName && rootPkg !in browserPackages) {
            captureWindowSnapshot(searchTopic, "")
        } else {
            null
        }
        TaskStore.appendLog(this, "同步识别开始：平台=$platform。切到目标 App 后工具记录点击前窗口；你点目标按钮后，工具对比变化并反馈候选角色，确认命名后才保存。")
        showSyncControl()
        handler.postDelayed({ pollSyncRecognition() }, 800L)
    }

    fun cancelSyncRecognition() {
        if (!syncActive) return
        syncActive = false
        syncBefore = null
        syncTappedNode = null
        TaskStore.appendLog(this, "同步识别已取消")
    }

    private fun showSyncControl() {
        removeIntervention()
        val wm = getSystemService(WindowManager::class.java) ?: return
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(235, 15, 23, 42))
            setPadding(16, 10, 16, 10)
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(this).apply {
            text = if (syncBefore == null) "同步识别：切到目标App" else "同步识别：点目标按钮"
            setTextColor(Color.WHITE)
            textSize = 13f
        }
        bar.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Button(this).apply {
            text = "记录点击前"
            setOnClickListener {
                syncBefore = captureWindowSnapshot(searchTopic, "")
                syncPollAttempts = 0
                TaskStore.appendLog(this@AutomationService, "同步识别已记录点击前窗口：${syncBefore?.let { snapshotSummary(it) } ?: "无"}")
                showSyncControl()
            }
        })
        bar.addView(Button(this).apply {
            text = "识别变化"
            setOnClickListener { finishSyncRecognition("手动识别") }
        })
        bar.addView(Button(this).apply {
            text = "取消"
            setOnClickListener {
                cancelSyncRecognition()
                showMiniOverlay()
            }
        })
        try {
            wm.addView(bar, interventionParams(Gravity.TOP, focusable = false))
            interventionRoot = bar
            overlayMode = "sync"
            overlayReady = true
            overlayStatus = "同步识别小控制条已显示：只保留顶部小条可触摸，目标 App 可正常点击"
            TaskStore.appendLog(this, overlayStatus)
        } catch (e: Exception) {
            overlayReady = false
            overlayStatus = "同步识别小控制条添加失败：WindowManager.addView 失败：${e.javaClass.simpleName}: ${e.message}"
            TaskStore.appendLog(this, overlayStatus)
        }
    }

    private fun handleSyncEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            event.source?.let { syncTappedNode = nodeSignature(it) }
            if (syncBefore != null) {
                handler.postDelayed({ finishSyncRecognition("点击事件") }, 900L)
            }
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            handler.postDelayed({ pollSyncRecognition() }, 500L)
        }
    }

    private fun pollSyncRecognition() {
        if (!syncActive) return
        val rootPkg = rootInActiveWindow?.packageName?.toString() ?: ""
        if (syncBefore == null) {
            if (rootPkg.isNotBlank() && rootPkg != packageName && rootPkg !in browserPackages) {
                syncBefore = captureWindowSnapshot(searchTopic, "")
                syncPollAttempts = 0
                TaskStore.appendLog(this, "同步识别已记录点击前窗口：${snapshotSummary(syncBefore!!)}")
                showSyncControl()
            } else {
                handler.postDelayed({ pollSyncRecognition() }, 800L)
            }
            return
        }
        syncPollAttempts++
        val after = captureWindowSnapshot(searchTopic, "")
        if (after.signature != syncBefore!!.signature && after.packageName == syncBefore!!.packageName) {
            finishSyncRecognition("窗口变化")
            return
        }
        if (syncPollAttempts >= 40) {
            val msg = "同步识别失败：超时内没检测到窗口变化，请确认已点目标按钮，或点“识别变化”手动对比"
            TaskStore.appendLog(this, msg)
            showInterventionPanel(msg, pauseAutomation = false)
            syncActive = false
            return
        }
        handler.postDelayed({ pollSyncRecognition() }, 800L)
    }

    private fun finishSyncRecognition(source: String) {
        if (!syncActive) return
        val before = syncBefore
        if (before == null) {
            TaskStore.appendLog(this, "同步识别未完成：还没记录点击前窗口，先切到目标 App 并点“记录点击前”")
            showSyncControl()
            return
        }
        val after = captureWindowSnapshot(searchTopic, "")
        syncActive = false
        val candidate = inferSyncCandidate(before, after, syncTappedNode)
        TaskStore.appendLog(this, "同步识别候选：来源=$source 角色=${PointStore.roleDisplayName(candidate.role)} 置信度=${candidate.confidence}% 证据=${candidate.evidence} before=${snapshotSummary(before)} after=${snapshotSummary(after)}")
        showSyncCandidatePanel(before, after, candidate)
    }

    private fun inferSyncCandidate(before: WindowSnapshot, after: WindowSnapshot, tapped: NodeSignature?): SyncCandidate {
        fun sigFor(role: String): NodeSignature? {
            if (tapped != null) return tapped
            return when (role) {
                PointStore.ROLE_COMMENT_ENTRY -> before.signatures.firstOrNull { it.text.contains("评论") || it.description.contains("评论") }
                PointStore.ROLE_SEARCH_BOX -> after.signatures.firstOrNull { it.editable && (it.text.contains("搜索") || it.description.contains("搜索") || it.focused) }
                    ?: before.signatures.firstOrNull { it.text.contains("搜索") || it.description.contains("搜索") }
                PointStore.ROLE_SEARCH_SUBMIT -> before.signatures.firstOrNull { it.text.contains("搜索") || it.description.contains("搜索") }
                PointStore.ROLE_SEND -> before.signatures.firstOrNull { it.text.contains("发送") || it.description.contains("发送") || it.text.contains("发布") }
                PointStore.ROLE_VIDEO_RESULT -> before.signatures.firstOrNull { it.clickable && it.text.length >= 4 }
                else -> before.signatures.firstOrNull { it.clickable } ?: after.signatures.firstOrNull { it.clickable }
            }
        }
        val tappedText = listOf(tapped?.text ?: "", tapped?.description ?: "").joinToString(" ")
        val role: String
        val evidence: String
        val confidence: Int
        when {
            after.hasCommentInput && !before.hasCommentInput -> {
                role = PointStore.ROLE_COMMENT_ENTRY
                evidence = "点击后评论输入框/评论面板出现"
                confidence = 88
            }
            after.hasSearchEditable && (!before.hasSearchEditable || after.searchFocused) -> {
                role = PointStore.ROLE_SEARCH_BOX
                evidence = "点击后搜索输入框出现或获得焦点"
                confidence = 86
            }
            after.hasResults && !before.hasResults -> {
                role = if (before.hasSearchEditable || tappedText.contains("搜索")) PointStore.ROLE_SEARCH_SUBMIT else PointStore.ROLE_VIDEO_RESULT
                evidence = "点击后结果页出现并包含话题/结果条目"
                confidence = 84
            }
            before.editableText.isNotBlank() && after.editableText.isBlank() && after.hasCommentPanel -> {
                role = PointStore.ROLE_SEND
                evidence = "点击后输入框清空且评论面板仍在"
                confidence = 72
            }
            tappedText.contains("评论") -> {
                role = PointStore.ROLE_COMMENT_ENTRY
                evidence = "被点控件文字包含评论"
                confidence = 76
            }
            tappedText.contains("搜索") -> {
                role = if (after.hasResults) PointStore.ROLE_SEARCH_SUBMIT else PointStore.ROLE_SEARCH_BOX
                evidence = "被点控件文字包含搜索"
                confidence = 74
            }
            tappedText.contains("发送") || tappedText.contains("发布") -> {
                role = PointStore.ROLE_SEND
                evidence = "被点控件文字包含发送/发布"
                confidence = 74
            }
            else -> {
                role = PointStore.ROLE_CUSTOM
                evidence = "窗口签名发生变化，但角色证据不足，请手动选择"
                confidence = 40
            }
        }
        val sig = sigFor(role)
        return SyncCandidate(
            role = role,
            label = PointStore.roleDisplayName(role),
            x = sig?.x ?: 0.5f,
            y = sig?.y ?: 0.5f,
            nodeSignature = sig?.summary() ?: "同步识别：无节点签名",
            evidence = evidence,
            confidence = confidence
        )
    }

    private fun showSyncCandidatePanel(before: WindowSnapshot, after: WindowSnapshot, candidate: SyncCandidate) {
        removeIntervention()
        val wm = getSystemService(WindowManager::class.java) ?: return
        var selectedRole = candidate.role
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(242, 15, 23, 42))
            setPadding(24, 18, 24, 18)
        }
        val title = TextView(this).apply {
            text = "同步识别结果：请确认命名"
            setTextColor(Color.WHITE)
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val detail = TextView(this).apply {
            text = "候选角色：${PointStore.roleDisplayName(selectedRole)} · 置信度 ${candidate.confidence}%\n证据：${candidate.evidence}\n坐标：X ${(candidate.x * 100).toInt()}% · Y ${(candidate.y * 100).toInt()}%\n节点：${candidate.nodeSignature}\n期望转换：${PointStore.expectedTransitionFor(selectedRole)}"
            setTextColor(Color.rgb(203, 213, 225))
            textSize = 13f
        }
        val labelInput = EditText(this).apply {
            setText(candidate.label)
            hint = "给这个点位命名，例如：评论点"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        fun refreshDetail() {
            detail.text = "候选角色：${PointStore.roleDisplayName(selectedRole)} · 置信度 ${candidate.confidence}%\n证据：${candidate.evidence}\n坐标：X ${(candidate.x * 100).toInt()}% · Y ${(candidate.y * 100).toInt()}%\n节点：${candidate.nodeSignature}\n期望转换：${PointStore.expectedTransitionFor(selectedRole)}"
        }
        panel.addView(title)
        panel.addView(detail)
        panel.addView(labelInput)
        PointStore.roles.chunked(3).forEach { chunk ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { role ->
                row.addView(Button(this).apply {
                    text = PointStore.roleDisplayName(role)
                    setOnClickListener {
                        selectedRole = role
                        if (labelInput.text?.toString().isNullOrBlank() || labelInput.text.toString() == candidate.label) {
                            labelInput.setText(PointStore.roleDisplayName(role))
                        }
                        refreshDetail()
                    }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            panel.addView(row)
        }
        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actionRow.addView(Button(this).apply {
            text = "确认保存"
            setOnClickListener {
                val label = labelInput.text?.toString()?.trim().orEmpty().ifBlank { PointStore.roleDisplayName(selectedRole) }
                val point = PointStore.SemanticPoint(
                    platform = syncPlatform,
                    packageName = after.packageName.ifBlank { before.packageName },
                    role = selectedRole,
                    label = label,
                    x = candidate.x,
                    y = candidate.y,
                    nodeSignature = candidate.nodeSignature,
                    beforeSignature = snapshotSummary(before),
                    afterSignature = snapshotSummary(after),
                    expectedTransition = PointStore.expectedTransitionFor(selectedRole),
                    confidence = if (selectedRole == candidate.role) maxOf(candidate.confidence, 70) else 65,
                    updatedAt = System.currentTimeMillis()
                )
                val ok = PointStore.saveSemantic(this@AutomationService, point)
                TaskStore.appendLog(this@AutomationService, "同步识别已保存：平台=$syncPlatform 角色=${PointStore.roleDisplayName(selectedRole)} 标签=$label 保存=$ok 证据=${candidate.evidence}")
                Toast.makeText(this@AutomationService, if (ok) "语义点位已保存：$label" else "保存失败", Toast.LENGTH_SHORT).show()
                showMiniOverlay()
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        actionRow.addView(Button(this).apply {
            text = "取消"
            setOnClickListener { showMiniOverlay() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(actionRow)
        val scroll = ScrollView(this).apply { addView(panel) }
        try {
            wm.addView(scroll, interventionParams(Gravity.BOTTOM, focusable = true))
            interventionRoot = scroll
            overlayMode = "syncCandidate"
            overlayReady = true
            overlayStatus = "同步识别候选已显示：确认角色和命名后才保存"
            TaskStore.appendLog(this, overlayStatus)
        } catch (e: Exception) {
            overlayReady = false
            overlayStatus = "同步识别候选添加失败：WindowManager.addView 失败：${e.javaClass.simpleName}: ${e.message}"
            TaskStore.appendLog(this, overlayStatus)
        }
    }

    private fun startSearchAssistInternal(platform: String, topic: String, quantity: Int) {
        val pkg = packageForPlatform(platform)
        if (pkg.isBlank()) {
            lastStatus = "搜索辅助失败：不支持的平台 $platform"
            TaskStore.appendLog(this, lastStatus)
            return
        }
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) {
            lastStatus = "搜索辅助失败：手机没装 $platform（$pkg）"
            TaskStore.appendLog(this, lastStatus)
            showInterventionPanel(lastStatus, pauseAutomation = false)
            return
        }
        searchRunning = true
        searchPlatform = platform
        searchPackage = pkg
        searchTopic = topic
        searchCandidateCap = quantity.coerceIn(1, 100)
        searchSessionKeys = linkedSetOf()
        searchStage = 0
        searchAttempts = 0
        expectedPlatform = platform
        expectedPackage = pkg
        pausedForHuman = false
        showMiniOverlay()
        lastStatus = "搜索辅助开始：打开 $platform 搜索“$topic”，候选确认上限 $searchCandidateCap 条；停在结果页，不自动评论。"
        TaskStore.appendLog(this, lastStatus)
        try {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
            scheduleSearchStep(2500L)
        } catch (e: Exception) {
            failSearch("搜索辅助失败：打开 $platform 出错 ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun failSearch(reason: String) {
        searchRunning = false
        lastStatus = reason
        saveCurrentErrorReport(this, reason, "")
        TaskStore.appendLog(this, reason)
        showInterventionPanel(reason, pauseAutomation = false)
    }

    private fun fillSearchText(input: AccessibilityNodeInfo, topic: String): Boolean {
        if (setText(input, topic)) {
            val fresh = rootInActiveWindow?.let { findSearchBox(it) } ?: input
            if ((fresh.text?.toString() ?: "").contains(topic)) return true
        }
        val fresh = rootInActiveWindow?.let { findSearchBox(it) } ?: input
        return pasteText(fresh, topic, topic)
    }

    private fun trySearchStep() {
        if (!searchRunning || pausedForHuman || semanticTapInProgress) return
        val root = rootInActiveWindow ?: run {
            searchAttempts++
            if (searchAttempts >= 5) failSearch("搜索辅助失败：拿不到当前窗口，期望包名=$searchPackage") else scheduleSearchStep(2000L)
            return
        }
        val pkg = root.packageName?.toString() ?: ""
        if (pkg in browserPackages) {
            failSearch("搜索辅助失败：浏览器在前台（实际包名=$pkg），已停止，不用浏览器页面状态作证明")
            return
        }
        if (pkg != searchPackage) {
            searchAttempts++
            TaskStore.appendLog(this, "搜索辅助验证窗口：期望前台包名=$searchPackage，实际前台包名=$pkg（第 $searchAttempts 次）")
            if (searchAttempts >= 5) failSearch("搜索辅助失败：超时后 $searchPlatform 仍未到前台（期望包名=$searchPackage，实际包名=$pkg）") else scheduleSearchStep(2000L)
            return
        }
        val allText = treeText(root)
        if (isLoginBlock(allText) || isSecurityBlock(allText)) {
            failSearch("搜索辅助失败：$searchPlatform 出现登录或安全检查，已停止自动操作")
            return
        }
        when (searchStage) {
            0 -> {
                val input = findSearchBox(root)
                if (input != null) {
                    clickNode(input)
                    input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    handler.postDelayed({
                        val fresh = rootInActiveWindow?.let { findSearchBox(it) } ?: input
                        if (fillSearchText(fresh, searchTopic)) {
                            searchStage = 1
                            searchAttempts = 0
                            TaskStore.appendLog(this, "搜索框已填入话题：$searchTopic，准备提交搜索")
                            scheduleSearchStep(900L)
                        } else {
                            searchAttempts++
                            if (searchAttempts >= 4) failSearch("搜索辅助失败：话题填不进搜索框") else scheduleSearchStep(1500L)
                        }
                    }, 700L)
                    return
                }
                val point = PointStore.loadSemantic(this, searchPlatform, PointStore.ROLE_SEARCH_BOX)
                if (point != null) {
                    executeSemanticPoint(searchPlatform, PointStore.ROLE_SEARCH_BOX, "搜索框", searchTopic, "") { _, transitionOk, msg ->
                        if (transitionOk) scheduleSearchStep(800L) else {
                            searchAttempts++
                            if (searchAttempts >= 3) failSearch("搜索辅助失败：搜索框语义点位没有产生期望转换。$msg") else scheduleSearchStep(1500L)
                        }
                    }
                    return
                }
                val entry = findSearchEntry(root)
                if (entry != null) {
                    if (clickNode(entry)) {
                        TaskStore.appendLog(this, "搜索框：已点搜索入口，等待搜索输入框出现")
                        scheduleSearchStep(1200L)
                    } else {
                        tapNodeCenter(entry, "搜索框") { ok ->
                            if (ok) scheduleSearchStep(1200L) else {
                                searchAttempts++
                                if (searchAttempts >= 3) failSearch("搜索辅助失败：搜索入口点击手势失败") else scheduleSearchStep(1500L)
                            }
                        }
                    }
                    return
                }
                searchAttempts++
                if (searchAttempts >= 4) failSearch("搜索辅助失败：页面里没有搜索框或搜索入口，请先同步识别搜索框") else scheduleSearchStep(2000L)
            }
            1 -> {
                val input = findSearchBox(root)
                val submit = findSearchSubmit(root, input)
                if (submit != null) {
                    if (clickNode(submit)) {
                        searchStage = 2
                        TaskStore.appendLog(this, "搜索提交：已点搜索提交控件，等待结果页验证")
                        scheduleSearchStep(1600L)
                    } else {
                        tapNodeCenter(submit, "搜索提交") { ok ->
                            if (ok) {
                                searchStage = 2
                                scheduleSearchStep(1600L)
                            } else {
                                searchAttempts++
                                if (searchAttempts >= 3) failSearch("搜索辅助失败：搜索提交点击手势失败") else scheduleSearchStep(1500L)
                            }
                        }
                    }
                    return
                }
                val point = PointStore.loadSemantic(this, searchPlatform, PointStore.ROLE_SEARCH_SUBMIT)
                if (point != null) {
                    executeSemanticPoint(searchPlatform, PointStore.ROLE_SEARCH_SUBMIT, "搜索提交", searchTopic, "") { gestureOk, transitionOk, msg ->
                        if (gestureOk) {
                            searchStage = 2
                            scheduleSearchStep(1200L)
                        } else if (!transitionOk) {
                            searchAttempts++
                            if (searchAttempts >= 3) failSearch("搜索辅助失败：搜索提交语义点位失败。$msg") else scheduleSearchStep(1500L)
                        }
                    }
                    return
                }
                if (input != null && input.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
                    searchStage = 2
                    TaskStore.appendLog(this, "搜索提交：已用键盘回车提交，等待结果页验证")
                    scheduleSearchStep(1600L)
                    return
                }
                searchAttempts++
                if (searchAttempts >= 4) failSearch("搜索辅助失败：找不到搜索提交控件，请先同步识别搜索提交") else scheduleSearchStep(1500L)
            }
            else -> {
                val snapshot = captureWindowSnapshot(searchTopic, "")
                if (snapshot.hasResults) {
                    searchRunning = false
                    lastStatus = "搜索辅助已停在结果页：话题“$searchTopic”，候选确认上限 $searchCandidateCap 条。请手动选择一条视频，确认后再点“评论这一条”。"
                    TaskStore.appendLog(this, "搜索辅助停在结果页：${snapshotSummary(snapshot)}")
                    showMiniOverlay()
                } else {
                    searchAttempts++
                    TaskStore.appendLog(this, "搜索辅助验证结果页：还没验证到结果页（第 $searchAttempts 次）${snapshotSummary(snapshot)}")
                    if (searchAttempts >= 5) failSearch("搜索辅助失败：提交后没验证到结果页") else scheduleSearchStep(1800L)
                }
            }
        }
    }

    private fun currentPageTargetKey(platform: String, snapshot: WindowSnapshot): String {
        val excluded = listOf("评论", "发送", "搜索", "关注", "点赞", "收藏", "分享", "全部评论", "发布")
        val stable = snapshot.signatures.map { listOf(it.text, it.description).firstOrNull { s -> s.isNotBlank() } ?: "" }
            .map { normalizedText(it) }
            .filter { it.length >= 4 && excluded.none { ex -> it == ex } }
            .distinct()
            .take(5)
            .joinToString("|")
        val basis = if (stable.isNotBlank()) stable else snapshot.signature
        return "$platform|current|${basis.hashCode().toString(16)}"
    }

    private fun startCommentCurrentPageInternal(platform: String, comment: String) {
        val snapshot = captureWindowSnapshot(searchTopic, "")
        val pkg = snapshot.packageName
        if (pkg.isBlank() || pkg == packageName || pkg in browserPackages) {
            lastStatus = "评论这一条失败：当前不在目标 App 视频页（实际包名=${pkg.ifBlank { "拿不到窗口" }}）"
            TaskStore.appendLog(this, lastStatus)
            showInterventionPanel(lastStatus, pauseAutomation = false)
            return
        }
        val key = currentPageTargetKey(platform, snapshot)
        if (TaskStore.hasCommentedTarget(this, key)) {
            lastStatus = "防重复拦截：这一条已验证评论过，防重复键=$key；已停止，不重复评论同一视频。"
            TaskStore.appendLog(this, lastStatus)
            showInterventionPanel(lastStatus, pauseAutomation = false)
            return
        }
        if (key !in searchSessionKeys && searchSessionKeys.size >= searchCandidateCap) {
            lastStatus = "候选确认上限已达：本轮最多确认 $searchCandidateCap 条不同视频；已停止，不自动评论更多视频。"
            TaskStore.appendLog(this, lastStatus)
            showInterventionPanel(lastStatus, pauseAutomation = false)
            return
        }
        searchSessionKeys.add(key)
        currentPageMode = true
        currentTargetKey = key
        currentTask = CommentTask.fromLinks(listOf("current-page"), comment)
        currentIndex = 0
        successCount = 0
        failCount = 0
        pausedForHuman = false
        lastFailureReason = ""
        lastFailureKind = ""
        currentFailureCounted = false
        expectedPlatform = platform
        expectedPackage = pkg
        attempts = 0
        resetSendState()
        running = true
        lastStatus = "评论这一条已确认：第 ${searchSessionKeys.size}/$searchCandidateCap 条不同候选，防重复键=$key；只评论当前这一条。"
        TaskStore.appendLog(this, lastStatus)
        showMiniOverlay()
        scheduleStep(this, 800L)
    }

    private fun startPointLoopInternal(platform: String, roles: List<String>, rounds: Int, intervalSeconds: Int) {
        val cleanRoles = roles.map { PointStore.kindToRole(it) }.filter { it != PointStore.ROLE_SEND }.distinct()
        if (cleanRoles.isEmpty()) {
            lastStatus = "点位循环失败：没有可循环的导航/测试语义点位，SEND 不允许循环"
            TaskStore.appendLog(this, lastStatus)
            return
        }
        PointStore.saveLoop(this, platform, cleanRoles, rounds, intervalSeconds)
        loopRunning = true
        loopPlatform = platform
        loopRoles = cleanRoles
        loopRounds = rounds.coerceIn(1, 20)
        loopIntervalSeconds = intervalSeconds.coerceIn(1, 60)
        loopRound = 1
        loopIndex = 0
        lastStatus = "点位循环开始：平台=$platform 角色=${cleanRoles.joinToString { PointStore.roleDisplayName(it) }} 轮数=$loopRounds 间隔=${loopIntervalSeconds}秒；SEND 已排除"
        TaskStore.appendLog(this, lastStatus)
        showMiniOverlay()
        runNextLoopPoint()
    }

    private fun runNextLoopPoint() {
        if (!loopRunning) return
        if (loopRound > loopRounds) {
            loopRunning = false
            lastStatus = "点位循环完成：共 $loopRounds 轮，每一步转换验证都通过"
            TaskStore.appendLog(this, lastStatus)
            showMiniOverlay()
            return
        }
        val role = loopRoles.getOrNull(loopIndex)
        if (role == null) {
            loopRunning = false
            lastStatus = "点位循环停止：没有下一个语义点位"
            TaskStore.appendLog(this, lastStatus)
            return
        }
        executeSemanticPoint(loopPlatform, role, "点位循环", searchTopic, "") { _, transitionOk, msg ->
            if (!loopRunning) return@executeSemanticPoint
            if (!transitionOk) {
                loopRunning = false
                lastStatus = "点位循环停止：第 $loopRound 轮 ${PointStore.roleDisplayName(role)} 转换验证失败"
                TaskStore.appendLog(this, lastStatus)
                showInterventionPanel(msg, pauseAutomation = false)
                return@executeSemanticPoint
            }
            loopIndex++
            if (loopIndex >= loopRoles.size) {
                loopIndex = 0
                loopRound++
            }
            handler.postDelayed({ runNextLoopPoint() }, loopIntervalSeconds * 1000L)
        }
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
        if (isLoginBlock(allText)) {
            interveneCurrent(context, task, "步骤5 失败：$expectedPlatform 要求登录。期望状态=已登录的视频页，实际状态=页面出现登录提示；请先在 App 里登录账号")
            return
        }
        if (isSecurityBlock(allText)) {
            interveneCurrent(context, task, "步骤5 失败：$expectedPlatform 弹出了安全检查。期望状态=可评论的视频页，实际状态=页面出现验证码/滑块/安全验证；已停止自动操作")
            return
        }
        // 步骤6：找评论输入框。快手/抖音的发送键常是右下角图标、没有文字，
        // 必须先点输入框拿到焦点、填入并验证文字真的进去了，再找发送键。
        val input = findEditableCommentBox(root)
        if (input != null) {
            val probe = commentProbe(task)
            val existingText = input.text?.toString() ?: ""
            if (inputMatchesComment(existingText, task.commentText)) {
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
        // 用户配置了语义评论入口就按角色执行：先签名/控件，后坐标；必须等手势回调并验证评论框出现。
        val commentPoint = PointStore.loadSemantic(context, expectedPlatform, PointStore.ROLE_COMMENT_ENTRY)
        if (commentPoint != null) {
            executeSemanticPoint(expectedPlatform, PointStore.ROLE_COMMENT_ENTRY, "步骤5 打开评论区", searchTopic, "") { gestureOk, transitionOk, msg ->
                if (gestureOk && transitionOk) {
                    attempts = 0
                    scheduleStep(context, 900L)
                } else {
                    attempts++
                    if (attempts >= 3) {
                        attempts = 0
                        interveneCurrent(context, task, "步骤5 失败：语义评论入口没有产生期望转换。$msg")
                    } else scheduleStep(context, 1800L)
                }
            }
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
        if (input == null || !inputMatchesComment(inputText, task.commentText)) {
            TaskStore.appendLog(context, "步骤7 点发送：输入框里没有待发送评论，回到填评论步骤重新确认。")
            scheduleStep(context, 1200L)
            return
        }
        // 用户配置了语义发送键就先按角色执行；手势完成后仍由步骤8防假成功验证，不把手势完成当发送成功。
        val sendPoint = PointStore.loadSemantic(context, expectedPlatform, PointStore.ROLE_SEND)
        if (sendPoint != null && sendStage == 0) {
            sendStage = 1
            executeSemanticPoint(expectedPlatform, PointStore.ROLE_SEND, "步骤7 点发送", searchTopic, probe) { gestureOk, _, _ ->
                if (gestureOk) {
                    handler.postDelayed({ verifySent(context, task) }, 500L)
                } else {
                    handler.postDelayed({ trySend(context, task) }, 500L)
                }
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
        val allText = root?.let { treeText(it) } ?: ""
        if (isLoginBlock(allText) || isSecurityBlock(allText)) {
            interveneCurrent(context, task, "步骤8 失败：发送后出现登录或安全检查。期望状态=评论出现在输入框外，实际状态=页面出现登录/验证码/安全验证；已停止自动操作")
            return
        }
        val input = root?.let { findEditableCommentBox(it) }
        val inputText = input?.text?.toString() ?: ""
        val outsideText = root?.let { textOutsideInput(it, input) } ?: ""
        val outsideCount = countOccurrences(outsideText, probe)
        val inputContains = inputText.contains(probe) || inputMatchesComment(inputText, task.commentText)
        val inputCleared = input == null || inputText.isBlank() || !inputContains
        val sent = !inputContains && outsideCount > preSendProbeCount
        TaskStore.appendLog(context, "步骤8 防假成功验证：期望输入框外同文案 > $preSendProbeCount，实际 $outsideCount；输入框仍含评论=$inputContains（输入框文字不计入成功）。")
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
            TaskStore.appendLog(context, "发视频步骤4 验证窗口：拿不到当前窗口（第 $publishAttempts 次），期望包名=$publishPackage")
            if (publishAttempts >= 6) {
                intervenePublish(context, "发视频步骤4 失败：超时后拿不到目标窗口（期望包名=$publishPackage）")
            } else schedulePublishStep(context, 3000L)
            return
        }
        val rootPkg = root.packageName?.toString() ?: ""
        if (rootPkg in browserPackages) {
            intervenePublish(context, "发视频步骤4 失败：浏览器在前台（实际包名=$rootPkg），目标 App 没有接管；已停止，不用浏览器页面状态作证明")
            return
        }
        if (rootPkg != publishPackage) {
            publishAttempts++
            TaskStore.appendLog(context, "发视频步骤4 验证窗口：期望前台包名=$publishPackage，实际前台包名=$rootPkg（第 $publishAttempts 次）")
            if (publishAttempts >= 6) {
                intervenePublish(context, "发视频步骤4 失败：超时后目标 App 仍未到前台（期望包名=$publishPackage，实际包名=$rootPkg）")
            } else schedulePublishStep(context, 3000L)
            return
        }
        val allText = treeText(root)
        if (isLoginBlock(allText) || isSecurityBlock(allText)) {
            intervenePublish(context, "发视频步骤5 失败：$publishPlatform 出现登录或安全检查。期望状态=已登录的发布页，实际状态=页面出现登录/验证码/安全验证；已停止自动操作")
            return
        }
        fun successCount(): Int {
            return listOf("发布成功", "已发布", "投稿成功", "发布完成").sumOf { countOccurrences(allText, it) }
        }
        val currentSuccessCount = successCount()
        if (publishBaselineSuccessCount < 0) {
            publishBaselineSuccessCount = currentSuccessCount
            TaskStore.appendLog(context, "发视频步骤5 记录基线：点击发布前成功字样出现 $publishBaselineSuccessCount 次；已有字样不计入本次成功。")
        }
        if (publishClicked && currentSuccessCount > publishBaselineSuccessCount) {
            publishRunning = false
            pausedForHuman = false
            showMiniOverlay()
            lastStatus = "视频发布已验证：$publishPlatform 出现新的发布成功状态（基线 $publishBaselineSuccessCount -> $currentSuccessCount）。"
            TaskStore.appendLog(context, "发视频步骤8 验证：$publishPlatform 出现新的发布成功状态（基线 $publishBaselineSuccessCount -> $currentSuccessCount）。")
            return
        }
        val input = findEditableCommentBox(root)
        val publishIndicators = listOf("发布作品", "添加标题", "标题", "作品描述", "发布设置", "下一步", "发布", "Post")
        val isPublishPage = input != null || publishIndicators.any { allText.contains(it) }
        if (!isPublishPage) {
            publishAttempts++
            val summary = allText.replace(Regex("\\s+"), " ").trim().take(120)
            TaskStore.appendLog(context, "发视频步骤6 识别发布页：已直达 $publishPlatform，但当前不是发布页（第 $publishAttempts 次），页面摘要=$summary")
            if (publishAttempts >= 6) {
                intervenePublish(context, "发视频步骤6 失败：已直达目标 App，但当前不是发布页；请手动进入发布页后点继续")
            } else schedulePublishStep(context, 3000L)
            return
        }
        if (publishCaption.isNotBlank()) {
            if (input == null) {
                publishAttempts++
                TaskStore.appendLog(context, "发视频步骤6 填文案：发布页没有可填输入框（第 $publishAttempts 次）")
                if (publishAttempts >= 6) {
                    intervenePublish(context, "发视频步骤6 失败：发布页找不到可填文案的输入框")
                } else schedulePublishStep(context, 3000L)
                return
            }
            val cur = input.text?.toString() ?: ""
            if (!inputMatchesComment(cur, publishCaption)) {
                val filled = fillCommentText(input, publishCaption, publishCaption.take(12))
                if (filled) {
                    TaskStore.appendLog(context, "发视频步骤6 填文案：已填入并验证文案在输入框里。")
                } else {
                    publishAttempts++
                    TaskStore.appendLog(context, "发视频步骤6 填文案：填入后验证不到完整文案（第 $publishAttempts 次）")
                    if (publishAttempts >= 6) {
                        intervenePublish(context, "发视频步骤6 失败：文案填不进去或验证不到完整文案")
                    } else schedulePublishStep(context, 3000L)
                    return
                }
            }
        }
        val btn = findPublishButton(root)
        if (btn != null) {
            if (clickNode(btn)) {
                publishClicked = true
                publishAttempts++
                TaskStore.appendLog(context, "发视频步骤7 点发布：已点击发布控件，等待点击后新出现的发布成功状态（第 $publishAttempts 次）")
                if (publishAttempts >= 8) {
                    intervenePublish(context, "发视频步骤8 失败：点了发布但没有验证到新的发布成功状态（基线 $publishBaselineSuccessCount，当前 $currentSuccessCount）")
                } else schedulePublishStep(context, 3000L)
                return
            }
            publishClicked = true
            publishAttempts++
            tapNodeCenter(btn, "发视频步骤7 点发布") {
                TaskStore.appendLog(context, "发视频步骤7 点发布：发布控件点击无效，已按坐标点击，等待新的发布成功状态")
                if (publishAttempts >= 8) {
                    intervenePublish(context, "发视频步骤8 失败：点了发布但没有验证到新的发布成功状态（基线 $publishBaselineSuccessCount，当前 $currentSuccessCount）")
                } else schedulePublishStep(context, 3000L)
            }
            return
        }
        publishAttempts++
        TaskStore.appendLog(context, "发视频步骤7 点发布：发布页上没有找到发布按钮（第 $publishAttempts 次）")
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
        if (currentIndex >= task.items.size) {
            showMiniOverlay()
            running = false
            lastStatus = if (currentPageMode) {
                "这一条已完成：已记录防重复。搜索辅助不会自动评论下一条，请手动选择下一条后再点“评论这一条”。"
            } else {
                finishStatus()
            }
            TaskStore.appendLog(context, lastStatus)
            return
        }
        showMiniOverlay()
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
        val wm = getSystemService(WindowManager::class.java)
        if (wm == null) {
            overlayReady = false
            overlayStatus = "悬浮控制窗添加失败：WindowManager 不可用，无法调用 addView"
            TaskStore.appendLog(this, overlayStatus)
            return
        }
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
        val selfTestTarget = Button(this).apply {
            text = "触发检测区：未回显"
            setOnClickListener {
                markTriggerEcho(this@AutomationService, "悬浮窗检测区")
                text = "触发检测区：已回显 ${timeText(lastTriggerEchoAtMillis)}"
                updateInterventionPanel()
            }
        }
        overlaySelfTestTarget = selfTestTarget
        panel.addView(selfTestTarget)
        panel.addView(row(
            btn("重试当前步") { resumeAfterIntervention(this) },
            btn("我来操作") { showManualBar() },
            btn("开始/继续") { resumeAfterIntervention(this) }
        ))
        panel.addView(row(
            btn("平台：${platformForIntervention()}") {
                val platforms = listOf("抖音", "快手", "小红书", "哔哩哔哩", "微博")
                val current = platformForIntervention()
                overlayPlatform = platforms[(platforms.indexOf(current).coerceAtLeast(0) + 1) % platforms.size]
                updateInterventionPanel()
                Toast.makeText(this, "校准平台已切到$overlayPlatform", Toast.LENGTH_SHORT).show()
            },
            btn("同步识别") {
                startSyncRecognitionInternal(platformForIntervention())
            },
            btn("测试评论点") {
                testSemanticPointInternal(platformForIntervention(), PointStore.ROLE_COMMENT_ENTRY)
            }
        ))
        panel.addView(row(
            btn("保存报告") {
                saveCurrentErrorReport(this, reason, note.text?.toString() ?: "")
                Toast.makeText(this, "错误报告已保存", Toast.LENGTH_SHORT).show()
            },
            btn("测试触发") { runOverlayTriggerSelfTest() },
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
            overlayReady = true
            overlayStatus = "悬浮控制窗已显示：TYPE_ACCESSIBILITY_OVERLAY 添加成功"
            TaskStore.appendLog(this, overlayStatus)
            updateInterventionPanel()
            schedulePanelRefresh()
        } catch (e: Exception) {
            overlayReady = false
            overlayStatus = "悬浮控制窗添加失败：WindowManager.addView 失败：${e.javaClass.simpleName}: ${e.message}"
            TaskStore.appendLog(this, overlayStatus)
        }
    }

    private fun updateInterventionPanel() {
        val platform = platformForIntervention()
        val lastLog = TaskStore.loadLog(this).lines().lastOrNull { it.isNotBlank() } ?: "暂无日志"
        val semanticSummary = PointStore.roles.joinToString(" · ") { role ->
            "${PointStore.roleDisplayName(role)}=${PointStore.describe(this, platform, role)}"
        }
        interventionStatus?.text = "实时状态：$lastStatus\n悬浮按钮：$overlayStatus\n触发自检：$triggerSelfTestStatus\n最新日志：$lastLog\n语义点位：$platform · $semanticSummary"
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
        val wm = getSystemService(WindowManager::class.java)
        if (wm == null) {
            overlayReady = false
            overlayStatus = "悬浮按钮添加失败：WindowManager 不可用，无法调用 addView"
            TaskStore.appendLog(this, overlayStatus)
            return
        }
        val mini = Button(this).apply {
            text = "控"
            textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(235, 15, 23, 42))
            setOnClickListener {
                overlayTapEchoCount++
                lastOverlayTapEchoAtMillis = System.currentTimeMillis()
                text = "已点"
                TaskStore.appendLog(this@AutomationService, "悬浮按钮回显：右侧“控”按钮被点击，第 $overlayTapEchoCount 次，时间=${timeText(lastOverlayTapEchoAtMillis)}")
                handler.postDelayed({
                    showInterventionPanel(panelReason.ifBlank { "悬浮控制窗" }, pauseAutomation = pausedForHuman)
                }, 250L)
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
            overlayReady = true
            overlayStatus = "悬浮按钮已显示：TYPE_ACCESSIBILITY_OVERLAY 添加成功，右侧“控”按钮可见"
            TaskStore.appendLog(this, overlayStatus)
        } catch (e: Exception) {
            overlayReady = false
            overlayStatus = "悬浮按钮添加失败：WindowManager.addView 失败：${e.javaClass.simpleName}: ${e.message}"
            TaskStore.appendLog(this, overlayStatus)
        }
    }

    private fun showManualBar() {
        removeIntervention()
        pausedForHuman = true
        val wm = getSystemService(WindowManager::class.java)
        if (wm == null) {
            overlayReady = false
            overlayStatus = "真人操作条添加失败：WindowManager 不可用，无法调用 addView"
            TaskStore.appendLog(this, overlayStatus)
            return
        }
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
            overlayReady = true
            overlayStatus = "真人操作条已显示：TYPE_ACCESSIBILITY_OVERLAY 添加成功"
            TaskStore.appendLog(this, "真人接管：自动化已暂停，你可以手动操作；处理完点顶部“继续识别”。")
        } catch (e: Exception) {
            overlayReady = false
            overlayStatus = "真人操作条添加失败：WindowManager.addView 失败：${e.javaClass.simpleName}: ${e.message}"
            TaskStore.appendLog(this, overlayStatus)
        }
    }

    fun removeIntervention() {
        val root = interventionRoot
        interventionRoot = null
        overlaySelfTestTarget = null
        interventionNote = null
        interventionStatus = null
        interventionSnapshot = null
        panelRefreshScheduled = false
        overlayMode = "none"
        overlayReady = false
        if (root == null) return
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
            val fresh = rootInActiveWindow?.let { findEditableCommentBox(it) } ?: node
            pasted && inputMatchesComment(fresh.text?.toString() ?: "", text)
        } catch (_: Exception) {
            false
        }
    }

    private fun fillCommentText(node: AccessibilityNodeInfo, text: String, probe: String): Boolean {
        if (setText(node, text)) {
            val afterSet = rootInActiveWindow?.let { findEditableCommentBox(it) } ?: node
            if (inputMatchesComment(afterSet.text?.toString() ?: "", text)) return true
        }
        val fresh = rootInActiveWindow?.let { findEditableCommentBox(it) } ?: node
        if (inputMatchesComment(fresh.text?.toString() ?: "", text)) return true
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

    /** 语义点位预览：把已保存语义点位显示在悬浮窗里，拖动哪个就更新哪个坐标。 */
    private inner class PreviewView(context: Context, private val platform: String) : View(context) {
        private val dm = resources.displayMetrics
        private val points = PointStore.listSemantic(context, platform).toMutableList()
        private var draggingRole: String? = null
        private var dragX: Float = 0f
        private var dragY: Float = 0f

        private fun colorFor(role: String): Int = when (role) {
            PointStore.ROLE_COMMENT_ENTRY -> Color.GREEN
            PointStore.ROLE_SEND -> Color.RED
            PointStore.ROLE_SEARCH_BOX -> Color.CYAN
            PointStore.ROLE_SEARCH_SUBMIT -> Color.rgb(251, 146, 60)
            PointStore.ROLE_COMMENT_INPUT -> Color.rgb(96, 165, 250)
            PointStore.ROLE_VIDEO_RESULT -> Color.rgb(192, 132, 252)
            else -> Color.YELLOW
        }

        private fun paint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        private fun textPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = 42f
            isFakeBoldText = true
        }

        private fun drawPoint(canvas: Canvas, point: PointStore.SemanticPoint) {
            val x = if (point.role == draggingRole) dragX else point.x * dm.widthPixels
            val y = if (point.role == draggingRole) dragY else point.y * dm.heightPixels
            val color = colorFor(point.role)
            canvas.drawCircle(x, y, 62f, paint(color))
            canvas.drawLine(x - 86f, y, x + 86f, y, paint(color))
            canvas.drawLine(x, y - 86f, x, y + 86f, paint(color))
            canvas.drawText(point.label, (x + 74f).coerceAtMost(width - 260f), (y - 74f).coerceAtLeast(60f), textPaint(color))
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            points.forEach { drawPoint(canvas, it) }
            if (points.isEmpty()) {
                canvas.drawText("这个平台还没配语义点位", 60f, height / 2f, textPaint(Color.RED))
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val nearest = points.minByOrNull { p ->
                        kotlin.math.hypot(event.x - p.x * dm.widthPixels, event.y - p.y * dm.heightPixels)
                    }
                    val dist = nearest?.let { kotlin.math.hypot(event.x - it.x * dm.widthPixels, event.y - it.y * dm.heightPixels) } ?: Float.MAX_VALUE
                    draggingRole = if (dist < 240f) nearest?.role else null
                    dragX = event.x
                    dragY = event.y
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (draggingRole != null) {
                        dragX = event.x
                        dragY = event.y
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val role = draggingRole
                    draggingRole = null
                    if (role != null && width > 0 && height > 0) {
                        val fx = dragX / width
                        val fy = dragY / height
                        val ok = PointStore.updateCoordinate(this@AutomationService, platform, role, fx, fy)
                        TaskStore.appendLog(this@AutomationService, "语义点位坐标已更新：$platform ${PointStore.roleDisplayName(role)} X ${(fx * 100).toInt()}% · Y ${(fy * 100).toInt()}% 保存=$ok；请再点“测试点位点击”验证真实转换")
                        val idx = points.indexOfFirst { it.role == role }
                        if (idx >= 0) points[idx] = points[idx].copy(x = fx, y = fy)
                        invalidate()
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
        val role = PointStore.kindToRole(kind)
        val kindName = PointStore.roleDisplayName(role)
        val cross = CrosshairView(this, kindName)
        val saved = PointStore.loadSemantic(this, platform, role)
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
                val pkg = rootInActiveWindow?.packageName?.toString() ?: packageForPlatform(platform)
                val savedOk = PointStore.saveManual(this@AutomationService, platform, pkg, role, fx, fy)
                val verified = PointStore.loadSemantic(this@AutomationService, platform, role)
                if (savedOk && verified != null) {
                    TaskStore.appendLog(
                        this@AutomationService,
                        "语义点位已保存：$platform $kindName X ${(verified.x * 100).toInt()}% · Y ${(verified.y * 100).toInt()}%；这只证明坐标写进去，请再点“测试点位点击”验证真实转换。"
                    )
                    Toast.makeText(this@AutomationService, "语义点位已保存：$platform $kindName", Toast.LENGTH_SHORT).show()
                } else {
                    TaskStore.appendLog(this@AutomationService, "语义点位保存失败：$platform $kindName 没写进去")
                    Toast.makeText(this@AutomationService, "语义点位保存失败", Toast.LENGTH_SHORT).show()
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
        val barLabel = if (role == PointStore.ROLE_SEND) {
            "$platform $kindName：先打开键盘让发送键可见，再对准发送键中心"
        } else {
            "$platform $kindName：十字圆心对准按键中心"
        }
        val bar = topBar(barLabel, listOf(saveBtn, cancelBtn))
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
            val warning = if (role == PointStore.ROLE_SEND) "发送键要在键盘打开、发送键可见时校准；" else ""
            TaskStore.appendLog(this, "校准悬浮窗已打开：$platform $kindName，$warning 切到 $platform 视频页，十字圆心对准按键中心点保存。")
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
            setOnClickListener {
                removeCalibration()
                showMiniOverlay()
            }
        }
        val bar = topBar("$platform 语义点位悬浮窗：拖动可改坐标，改后必须测试点位点击", listOf(closeBtn))
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
        if (pausedForHuman) {
            showInterventionPanel(lastFailureReason.ifBlank { "悬浮控制窗：点位和状态已更新" })
        } else {
            showMiniOverlay()
        }
    }


    private fun runTriggerSelfTestInternal(xPx: Int, yPx: Int, source: String, onResult: (Boolean, String) -> Unit) {
        if (xPx <= 0 || yPx <= 0) {
            triggerSelfTestPassed = false
            triggerSelfTestStatus = "触发自检失败：检测目标坐标无效（X=$xPx Y=$yPx）"
            TaskStore.appendLog(this, triggerSelfTestStatus)
            onResult(false, triggerSelfTestStatus)
            return
        }
        pendingSelfTestStartedAt = System.currentTimeMillis()
        pendingSelfTestSource = source
        triggerSelfTestPassed = false
        triggerSelfTestStatus = "触发自检进行中：正在向$source 派发无障碍手势（X=$xPx Y=$yPx）"
        TaskStore.appendLog(this, "触发自检开始：期望手势完成且$source 回显，目标坐标 X=$xPx Y=$yPx")
        tapAtPixel(xPx.toFloat(), yPx.toFloat(), "触发自检", suspendOwnOverlays = source != "悬浮窗检测区") { gestureOk ->
            if (!gestureOk) {
                triggerSelfTestPassed = false
                triggerSelfTestStatus = "触发自检失败：手势未完成（派发失败或被系统取消），检测目标没有可信回显"
                TaskStore.appendLog(this, triggerSelfTestStatus)
                onResult(false, triggerSelfTestStatus)
                return@tapAtPixel
            }
            handler.postDelayed({
                val echoOk = lastTriggerEchoAtMillis >= pendingSelfTestStartedAt && lastTriggerEchoSource == source
                if (echoOk) {
                    triggerSelfTestPassed = true
                    triggerSelfTestAtMillis = System.currentTimeMillis()
                    triggerSelfTestStatus = "触发自检通过：手势完成且$source 已回显，时间=${timeText(lastTriggerEchoAtMillis)}"
                } else {
                    triggerSelfTestPassed = false
                    triggerSelfTestStatus = "触发自检失败：手势已完成，但$source 没有回显（期望回显来源=$source，实际来源=${lastTriggerEchoSource.ifBlank { "无" }}；目标可能不在前台、坐标不对或被遮挡）"
                }
                TaskStore.appendLog(this, triggerSelfTestStatus)
                onResult(triggerSelfTestPassed, triggerSelfTestStatus)
            }, 900L)
        }
    }

    private fun runOverlayTriggerSelfTest() {
        val target = overlaySelfTestTarget
        if (target == null || target.width <= 0 || target.height <= 0) {
            triggerSelfTestPassed = false
            triggerSelfTestStatus = "触发自检失败：悬浮窗检测区还没显示或尺寸为 0"
            TaskStore.appendLog(this, triggerSelfTestStatus)
            updateInterventionPanel()
            return
        }
        val loc = IntArray(2)
        target.getLocationOnScreen(loc)
        val cx = loc[0] + target.width / 2
        val cy = loc[1] + target.height / 2
        target.text = "触发检测区：等待回显"
        runTriggerSelfTestInternal(cx, cy, "悬浮窗检测区") { ok, message ->
            overlaySelfTestTarget?.text = if (ok) {
                "触发检测区：已回显 ${timeText(lastTriggerEchoAtMillis)}"
            } else {
                "触发检测区：未回显"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            updateInterventionPanel()
        }
    }

    private fun dispatchTap(xPx: Float, yPx: Float, label: String, suspendOwnOverlays: Boolean, onDone: ((Boolean) -> Unit)? = null) {
        val dm = resources.displayMetrics
        val fx = if (dm.widthPixels > 0) xPx / dm.widthPixels else 0f
        val fy = if (dm.heightPixels > 0) yPx / dm.heightPixels else 0f
        val coordText = "X=${xPx.toInt()} Y=${yPx.toInt()}（X ${(fx * 100).toInt()}% · Y ${(fy * 100).toInt()}%）"
        var suspended = false
        if (suspendOwnOverlays) {
            removeCalibration()
            if (interventionRoot != null) {
                removeIntervention()
                suspended = true
            }
            TaskStore.appendLog(this, "$label：点位真实点击验证，点击前已移除校准/预览和自身悬浮层，避免拦截手势（$coordText）")
        }
        gestureInProgress = true
        val path = Path().apply {
            moveTo(xPx, yPx)
            lineTo(xPx, yPx)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 120))
            .build()
        var finished = false
        fun restoreOverlay() {
            if (!suspended) return
            if (pausedForHuman) {
                showInterventionPanel(panelReason.ifBlank { lastFailureReason.ifBlank { "悬浮控制窗" } })
            } else if (!syncActive) {
                showMiniOverlay()
            }
        }
        fun finish(ok: Boolean, message: String) {
            if (finished) return
            finished = true
            gestureInProgress = false
            TaskStore.appendLog(this@AutomationService, message)
            restoreOverlay()
            onDone?.invoke(ok)
        }
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                finish(true, "$label：手势已完成（$coordText）")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                finish(false, "$label：手势被系统取消（$coordText）")
            }
        }, handler)
        if (!dispatched) {
            finish(false, "$label：手势派发失败（$coordText）")
        } else {
            TaskStore.appendLog(this, "$label：手势已派发，等待系统完成回调（$coordText）")
        }
    }

    private fun tapAtPixel(xPx: Float, yPx: Float, label: String, suspendOwnOverlays: Boolean = true, onDone: ((Boolean) -> Unit)? = null) {
        dispatchTap(xPx, yPx, label, suspendOwnOverlays, onDone)
    }

    /** 按保存的屏幕比例真实点击一次（手势注入，点的是屏幕坐标），并等待系统完成/取消回调。 */
    private fun tapAtFraction(x: Float, y: Float, label: String, onDone: ((Boolean) -> Unit)? = null) {
        val dm = resources.displayMetrics
        val px = x.coerceIn(0f, 1f) * dm.widthPixels
        val py = y.coerceIn(0f, 1f) * dm.heightPixels
        dispatchTap(px, py, label, suspendOwnOverlays = true, onDone = onDone)
    }

    override fun onInterrupt() {
        running = false
        publishRunning = false
    }

    override fun onUnbind(intent: Intent?): Boolean {
        syncActive = false
        searchRunning = false
        loopRunning = false
        removeCalibration()
        removeIntervention()
        overlayReady = false
        overlayStatus = "悬浮按钮已移除：无障碍服务已解绑"
        triggerSelfTestPassed = false
        triggerSelfTestStatus = "触发自检未通过：无障碍服务已解绑"
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        syncActive = false
        searchRunning = false
        loopRunning = false
        removeCalibration()
        removeIntervention()
        overlayReady = false
        overlayStatus = "悬浮按钮已移除：无障碍服务已销毁"
        triggerSelfTestPassed = false
        triggerSelfTestStatus = "触发自检未通过：无障碍服务已销毁"
        super.onDestroy()
        instance = null
    }
}
