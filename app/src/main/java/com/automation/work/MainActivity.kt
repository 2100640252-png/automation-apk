package com.automation.work

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    var taskStatus by mutableStateOf("未开始")
    var serviceStatus by mutableStateOf("无障碍服务状态读取中…")
    var gateReason by mutableStateOf("触发自检未通过：尚未读取服务状态")
    var accessibilityEnabled by mutableStateOf(false)
    var logText by mutableStateOf("暂无运行记录。")
    var errorReportText by mutableStateOf("暂无错误报告。")
    var targetEchoText by mutableStateOf("触发检测按钮回显：未回显")
    var targetX by mutableStateOf(0)
    var targetY by mutableStateOf(0)
    var pointRefresh by mutableStateOf(0)
    var researchRefresh by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshState()
        setContent { AutomationApp(this) }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
        taskStatus = AutomationService.lastStatus
    }

    fun currentVersionName(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "4.5.0"
        } catch (_: Exception) {
            "4.5.0"
        }
    }

    fun refreshState() {
        accessibilityEnabled = PermissionHelper.isAccessibilityEnabled(this)
        serviceStatus = AutomationService.statusSummary()
        gateReason = AutomationService.gateFailureReason() ?: "已通过：无障碍服务、悬浮按钮和触发自检都已就绪，可以开始任务。"
        logText = TaskStore.loadLog(this)
        errorReportText = TaskStore.loadLastErrorReport(this)
    }

    fun openAccessibilitySettings() {
        PermissionHelper.openAccessibilitySettings(this)
        taskStatus = "已打开无障碍设置：开启“自动化屏幕控制”后回来，屏幕右侧应出现“控”按钮。"
        refreshState()
    }

    fun restoreOverlay() {
        if (AutomationService.instance == null) {
            taskStatus = AutomationService.gateFailureReason() ?: "无障碍服务未连接。"
            PermissionHelper.openAccessibilitySettings(this)
            refreshState()
            return
        }
        AutomationService.showOverlayNow(this)
        taskStatus = "已请求恢复悬浮按钮：看右侧“控”按钮和日志里的添加结果。"
        refreshState()
    }

    fun showOverlayControl() {
        if (AutomationService.instance == null) {
            taskStatus = AutomationService.gateFailureReason() ?: "无障碍服务未连接。"
            PermissionHelper.openAccessibilitySettings(this)
            refreshState()
            return
        }
        AutomationService.showInterventionNow(this)
        taskStatus = "悬浮控制窗已打开：可以测试触发、校准点位、停止任务和保存报告。"
        refreshState()
    }

    fun runTriggerSelfTest() {
        if (targetX <= 0 || targetY <= 0) {
            taskStatus = "触发自检失败：检测按钮坐标还没测到，请等界面完全显示后再点“测试触发”。"
            TaskStore.appendLog(this, taskStatus)
            refreshState()
            return
        }
        taskStatus = "触发自检进行中：正在向检测按钮派发无障碍手势，等待手势回调和按钮回显…"
        AutomationService.runTriggerSelfTest(this, targetX, targetY, "主界面检测按钮") { _, message ->
            taskStatus = message
            refreshState()
        }
        refreshState()
    }

    fun onTriggerTargetClicked() {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        targetEchoText = "触发检测按钮回显：已点击 $time"
        AutomationService.markTriggerEcho(this, "主界面检测按钮")
        taskStatus = targetEchoText
        refreshState()
    }

    fun startComment(link: String, comment: String) {
        val cleanLink = link.trim()
        if (cleanLink.isBlank()) {
            taskStatus = "开始失败：先填写一条视频链接。"
            refreshState()
            return
        }
        if (comment.isBlank()) {
            taskStatus = "开始失败：先填写评论内容。"
            refreshState()
            return
        }
        val gate = AutomationService.gateFailureReason()
        if (gate != null) {
            taskStatus = gate
            TaskStore.appendLog(this, gate)
            refreshState()
            return
        }
        AutomationService.startLinkComment(this, cleanLink, comment)
        taskStatus = "评论任务已开始：正在应用内解析链接并直达目标 App…"
        refreshState()
    }

    fun startSearchAssist(platform: String, topic: String, quantity: Int) {
        if (topic.isBlank()) {
            taskStatus = "搜索辅助失败：先填写话题。"
            refreshState()
            return
        }
        val gate = AutomationService.gateFailureReason()
        if (gate != null) {
            taskStatus = gate
            TaskStore.appendLog(this, gate)
            refreshState()
            return
        }
        AutomationService.startSearchAssist(this, platform, topic, quantity)
        taskStatus = "搜索辅助已开始：打开 $platform 搜索“${topic.trim()}”，停在结果页；数量只作候选确认上限，不自动评论。"
        refreshState()
    }

    fun unsupportedResearchMessage(step: String, platform: String): String {
        val info = PlatformRegistry.find(platform)
        return if (info == null) {
            "失败类别：SEARCH_UNSUPPORTED_PLATFORM_MODE\n步骤：$step\n平台：$platform\n期望包名：未内置\n实际前台包名：未知\n原因：未内置平台：$platform\n下一步：请选择内置平台后重试；已停止，不回退其他平台或浏览器。"
        } else {
            "失败类别：SEARCH_UNSUPPORTED_PLATFORM_MODE\n步骤：$step\n平台：${info.label}\n期望包名：${info.packageName}\n实际前台包名：未知\n原因：${info.unsupportedReason}\n下一步：请选择支持的平台后重试；已停止，不回退其他平台或浏览器。"
        }
    }

    fun startResearchSearch(inputs: TaskStore.ResearchInputs) {
        if (inputs.topic.isBlank()) {
            taskStatus = "话题找素材失败：先填写话题。"
            refreshState()
            return
        }
        val info = PlatformRegistry.find(inputs.platform)
        if (info == null) {
            taskStatus = unsupportedResearchMessage("打开搜索", inputs.platform)
            TaskStore.appendLog(this, taskStatus)
            refreshState()
            return
        }
        if (!info.supported) {
            taskStatus = unsupportedResearchMessage("打开搜索", inputs.platform)
            TaskStore.appendLog(this, taskStatus)
            refreshState()
            return
        }
        val gate = AutomationService.gateFailureReason()
        if (gate != null) {
            taskStatus = gate
            TaskStore.appendLog(this, gate)
            refreshState()
            return
        }
        AutomationService.startResearchSearch(this, inputs.copy(platform = info.label))
        taskStatus = "话题找素材已开始：打开 ${info.label}（${info.packageName}）搜索“${inputs.topic.trim()}”，停在结果页后手动滑动并采集当前可见。"
        refreshState()
    }

    fun collectResearch(inputs: TaskStore.ResearchInputs) {
        if (inputs.topic.isBlank()) {
            taskStatus = "话题找素材失败：先填写话题。"
            refreshState()
            return
        }
        val info = PlatformRegistry.find(inputs.platform)
        if (info == null || !info.supported) {
            taskStatus = unsupportedResearchMessage("采集当前可见", inputs.platform)
            TaskStore.appendLog(this, taskStatus)
            refreshState()
            return
        }
        if (AutomationService.instance == null) {
            taskStatus = AutomationService.gateFailureReason() ?: "无障碍服务未连接。"
            PermissionHelper.openAccessibilitySettings(this)
            refreshState()
            return
        }
        AutomationService.collectVisibleResearch(this, inputs.copy(platform = info.label))
        taskStatus = AutomationService.lastStatus
        researchRefresh++
        refreshState()
    }

    fun recordResearchLink(inputs: TaskStore.ResearchInputs) {
        if (inputs.topic.isBlank()) {
            taskStatus = "记录当前链接失败：先填写话题。"
            refreshState()
            return
        }
        val info = PlatformRegistry.find(inputs.platform)
        if (info == null || !info.supported) {
            taskStatus = unsupportedResearchMessage("记录当前链接", inputs.platform)
            TaskStore.appendLog(this, taskStatus)
            refreshState()
            return
        }
        if (AutomationService.instance == null) {
            taskStatus = AutomationService.gateFailureReason() ?: "无障碍服务未连接。"
            PermissionHelper.openAccessibilitySettings(this)
            refreshState()
            return
        }
        AutomationService.recordCurrentResearchLink(this, inputs.copy(platform = info.label))
        taskStatus = AutomationService.lastStatus
        researchRefresh++
        refreshState()
    }

    fun collectPublicResearch(inputs: TaskStore.ResearchInputs) {
        if (inputs.topic.isBlank()) {
            taskStatus = "公开网页采集失败：先填写话题。"
            refreshState()
            return
        }
        val info = PlatformRegistry.find(inputs.platform)
        if (info == null || !info.supported) {
            taskStatus = unsupportedResearchMessage("公开网页采集", inputs.platform)
            TaskStore.appendLog(this, taskStatus)
            refreshState()
            return
        }
        if (!info.publicWebSupported) {
            taskStatus = "失败类别：SEARCH_UNSUPPORTED_PLATFORM_MODE\n步骤：公开网页采集\n平台：${info.label}\n期望包名：${info.packageName}\n实际前台包名：未知\n原因：${info.publicWebReason}\n下一步：请使用 App内搜索，并确保 ${info.packageName} 已安装登录。"
            TaskStore.appendLog(this, taskStatus)
            refreshState()
            return
        }
        if (AutomationService.instance == null) {
            taskStatus = AutomationService.gateFailureReason() ?: "无障碍服务未连接。"
            PermissionHelper.openAccessibilitySettings(this)
            refreshState()
            return
        }
        AutomationService.collectPublicResearch(this, inputs.copy(platform = info.label, collectionMode = "公开网页"))
        taskStatus = AutomationService.lastStatus
        researchRefresh++
        refreshState()
    }

    fun copyResearchList() {
        val text = TaskStore.researchExportText(this)
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("话题找素材链接清单", text))
        taskStatus = "链接清单已复制：共 ${TaskStore.loadResearchItems(this).size} 条；没有真实链接的条目会显示待补链接。"
        TaskStore.appendLog(this, taskStatus)
        refreshState()
    }

    fun clearResearchList() {
        TaskStore.clearResearchItems(this)
        taskStatus = "素材清单已清空。"
        researchRefresh++
        refreshState()
    }

    fun startCommentCurrentPage(platform: String, comment: String) {
        if (comment.isBlank()) {
            taskStatus = "评论这一条失败：先填写评论内容。"
            refreshState()
            return
        }
        val gate = AutomationService.gateFailureReason()
        if (gate != null) {
            taskStatus = gate
            TaskStore.appendLog(this, gate)
            refreshState()
            return
        }
        AutomationService.startCommentCurrentPage(this, platform, comment)
        taskStatus = "已确认评论当前这一条：只评论这一条，成功后记录防重复；下一条需手动选择后再次确认。"
        refreshState()
    }

    fun startDirectOpenTest(link: String) {
        val cleanLink = link.trim()
        if (cleanLink.isBlank()) {
            taskStatus = "直达测试失败：先填写视频链接。"
            refreshState()
            return
        }
        val gate = AutomationService.gateFailureReason()
        if (gate != null) {
            taskStatus = gate
            TaskStore.appendLog(this, gate)
            refreshState()
            return
        }
        AutomationService.startDirectOpenTest(this, cleanLink)
        taskStatus = "直达测试已开始：应用内解析后直达目标 App，不进入浏览器。"
        refreshState()
    }

    fun startPublishAssist(link: String, caption: String) {
        val cleanLink = link.trim()
        if (cleanLink.isBlank()) {
            taskStatus = "发布辅助失败：先填写视频链接。"
            refreshState()
            return
        }
        val gate = AutomationService.gateFailureReason()
        if (gate != null) {
            taskStatus = gate
            TaskStore.appendLog(this, gate)
            refreshState()
            return
        }
        AutomationService.startPublishAssistByLink(this, cleanLink, caption)
        taskStatus = "发布辅助已开始：先直达目标 App，再验证发布页；成功只认点击后新出现的发布成功状态。"
        refreshState()
    }

    fun stopTask() {
        AutomationService.stopTask(this)
        taskStatus = "任务已停止；悬浮按钮保留在屏幕右侧。"
        refreshState()
    }

    fun startCalibration(platform: String, kind: String) {
        if (AutomationService.instance == null) {
            taskStatus = AutomationService.gateFailureReason() ?: "无障碍服务未连接。"
            PermissionHelper.openAccessibilitySettings(this)
            refreshState()
            return
        }
        val role = PointStore.kindToRole(kind)
        AutomationService.startCalibration(this, platform, role)
        taskStatus = if (role == PointStore.ROLE_SEND) {
            "重新校准发送键：先切到$platform 视频页并打开评论键盘，让发送键可见，再把十字圆心对准发送键保存；保存后还要测试点位点击。"
        } else {
            "重新校准${PointStore.roleDisplayName(role)}：切到$platform 目标页面，把十字圆心对准目标保存；保存后还要测试点位点击。"
        }
        pointRefresh++
        refreshState()
    }

    fun startSyncRecognition(platform: String) {
        if (AutomationService.instance == null) {
            taskStatus = AutomationService.gateFailureReason() ?: "无障碍服务未连接。"
            PermissionHelper.openAccessibilitySettings(this)
            refreshState()
            return
        }
        AutomationService.startSyncRecognition(this, platform)
        taskStatus = "同步识别已开始：切到$platform 目标页面，工具记录点击前窗口；你点目标按钮后，它会反馈候选角色，确认命名后才保存。"
        pointRefresh++
        refreshState()
    }

    fun testSemanticPoint(platform: String, role: String) {
        if (AutomationService.instance == null) {
            taskStatus = AutomationService.gateFailureReason() ?: "无障碍服务未连接。"
            PermissionHelper.openAccessibilitySettings(this)
            refreshState()
            return
        }
        AutomationService.testSemanticPoint(this, platform, role)
        taskStatus = "点位真实点击验证进行中：$platform ${PointStore.roleDisplayName(role)}；手势完成且转换验证通过才算真实点击。"
        pointRefresh++
        refreshState()
    }

    fun deleteSemanticPoint(platform: String, role: String) {
        PointStore.deleteSemantic(this, platform, role)
        taskStatus = "语义点位已删除：$platform ${PointStore.roleDisplayName(role)}"
        pointRefresh++
        refreshState()
    }

    fun startPointLoop(platform: String, roles: List<String>, rounds: Int, intervalSeconds: Int) {
        val gate = AutomationService.gateFailureReason()
        if (gate != null) {
            taskStatus = gate
            TaskStore.appendLog(this, gate)
            refreshState()
            return
        }
        AutomationService.startPointLoop(this, platform, roles, rounds, intervalSeconds)
        taskStatus = "点位循环已开始：只循环导航/测试语义点位，SEND 已排除；任一步转换验证失败就停止。"
        pointRefresh++
        refreshState()
    }

    fun showPointPreview(platform: String) {
        if (AutomationService.instance == null) {
            taskStatus = AutomationService.gateFailureReason() ?: "无障碍服务未连接。"
            PermissionHelper.openAccessibilitySettings(this)
            refreshState()
            return
        }
        AutomationService.startPointPreview(this, platform)
        taskStatus = "语义点位预览已打开：切到$platform 页面查看各角色位置，拖动改坐标后必须测试点位点击。"
        pointRefresh++
        refreshState()
    }

    fun copyErrorReport() {
        val report = TaskStore.loadLastErrorReport(this)
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("automation_error_report", report))
        taskStatus = "错误报告已复制，可以发出来定位是哪一步失败。"
        refreshState()
    }

    fun clearLog() {
        TaskStore.clearLog(this)
        taskStatus = "日志已清空。"
        refreshState()
    }

    fun clearErrorReports() {
        TaskStore.clearErrorReports(this)
        taskStatus = "错误报告已清空。"
        refreshState()
    }
}

@Composable
private fun StatusChip(text: String, ok: Boolean) {
    Text(
        text,
        modifier = Modifier
            .background(if (ok) Color(0xFFDCFCE7) else Color(0xFFFEF3C7), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        color = if (ok) Color(0xFF166534) else Color(0xFF92400E),
        style = MaterialTheme.typography.labelMedium
    )
}

@Composable
private fun GateChips(activity: MainActivity) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusChip(if (activity.accessibilityEnabled) "无障碍已开" else "无障碍未开", activity.accessibilityEnabled)
        StatusChip(if (AutomationService.overlayReady) "悬浮按钮已显示" else "悬浮按钮未就绪", AutomationService.overlayReady)
        StatusChip(if (AutomationService.triggerSelfTestPassed) "触发自检通过" else "触发自检未通过", AutomationService.triggerSelfTestPassed)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationApp(activity: MainActivity) {
    val savedComment = remember { TaskStore.loadCommentInputs(activity) }
    val savedVideo = remember { TaskStore.loadVideoInputs(activity) }
    val savedSearch = remember { TaskStore.loadSearchInputs(activity) }
    val savedResearch = remember { TaskStore.loadResearchInputs(activity) }
    var selectedTab by remember { mutableStateOf(0) }
    var commentLink by remember { mutableStateOf(savedComment.first.firstOrNull() ?: "") }
    var commentText by remember { mutableStateOf(savedComment.second) }
    var videoLink by remember { mutableStateOf(savedVideo.link) }
    var videoCaption by remember { mutableStateOf(savedVideo.caption) }
    var videoMaterial by remember { mutableStateOf(savedVideo.material) }
    var videoSchedule by remember { mutableStateOf(savedVideo.schedule) }
    var platform by remember { mutableStateOf(PlatformRegistry.canonicalLabel(savedSearch.platform)) }
    var searchTopic by remember { mutableStateOf(savedSearch.topic) }
    var searchQuantity by remember { mutableStateOf(savedSearch.quantity.toString()) }
    var researchPlatform by remember { mutableStateOf(PlatformRegistry.canonicalLabel(savedResearch.platform)) }
    var researchTopic by remember { mutableStateOf(savedResearch.topic) }
    var researchContentType by remember { mutableStateOf(savedResearch.contentType) }
    var researchSort by remember { mutableStateOf(savedResearch.sortPreference) }
    var researchLimit by remember { mutableStateOf(savedResearch.limit.toString()) }
    var researchMode by remember { mutableStateOf(savedResearch.collectionMode) }
    val tabs = listOf("评论", "视频", "素材", "点位", "日志")
    val platforms = PlatformRegistry.labels
    val researchItems = remember(activity.researchRefresh, activity.logText, researchSort) {
        TaskStore.sortResearchItems(TaskStore.loadResearchItems(activity), researchSort)
    }
    val gatePassed = activity.gateReason.startsWith("已通过")
    val semanticPoints = remember(platform, activity.pointRefresh, activity.logText) {
        PointStore.listSemantic(activity, platform).associateBy { it.role }
    }
    val savedLoop = remember(platform, activity.pointRefresh) { PointStore.loadLoop(activity, platform) }
    var loopRoles by remember(platform) { mutableStateOf(savedLoop.roles.toSet()) }
    var loopRounds by remember(platform) { mutableStateOf(savedLoop.rounds.toString()) }
    var loopInterval by remember(platform) { mutableStateOf(savedLoop.intervalSeconds.toString()) }

    fun saveVideo(link: String, caption: String, material: String, schedule: String) {
        TaskStore.saveVideoInputs(activity, TaskStore.VideoInputs(link, caption, material, schedule))
    }

    fun saveSearch(platform: String, topic: String, quantityText: String) {
        val quantity = quantityText.toIntOrNull()?.coerceIn(1, 100) ?: 10
        TaskStore.saveSearchInputs(activity, TaskStore.SearchInputs(platform, topic, quantity))
    }

    fun currentResearchInputs(): TaskStore.ResearchInputs {
        return TaskStore.ResearchInputs(
            platform = researchPlatform,
            topic = researchTopic,
            contentType = researchContentType,
            sortPreference = researchSort,
            limit = researchLimit.toIntOrNull()?.coerceIn(1, 100) ?: 20,
            collectionMode = researchMode
        )
    }

    fun saveResearch() {
        TaskStore.saveResearchInputs(activity, currentResearchInputs())
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text("无障碍触发版 v${activity.currentVersionName()}") })
                TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            when (selectedTab) {
                0 -> {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))) {
                        Column(Modifier.padding(18.dp)) {
                            Text("评论", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            GateChips(activity)
                            Spacer(Modifier.height(8.dp))
                            Text(activity.serviceStatus, color = Color(0xFFCBD5E1))
                            Text(activity.gateReason, color = if (gatePassed) Color(0xFF86EFAC) else Color(0xFFFDE68A))
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { activity.openAccessibilitySettings() }, modifier = Modifier.weight(1f)) { Text("无障碍设置") }
                                OutlinedButton(onClick = { activity.restoreOverlay() }, modifier = Modifier.weight(1f)) { Text("恢复悬浮按钮") }
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("第一关：测试触发", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("手势完成且检测按钮回显才算通过；只派发手势不算通过。")
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { activity.runTriggerSelfTest() }, modifier = Modifier.weight(1f)) { Text("测试触发") }
                                OutlinedButton(
                                    onClick = { activity.onTriggerTargetClicked() },
                                    modifier = Modifier.weight(1f).onGloballyPositioned { coords ->
                                        val pos = coords.positionInWindow()
                                        activity.targetX = (pos.x + coords.size.width / 2f).roundToInt()
                                        activity.targetY = (pos.y + coords.size.height / 2f).roundToInt()
                                    }
                                ) { Text("触发检测按钮") }
                            }
                            Text(activity.targetEchoText)
                            Text("检测按钮坐标：X=${activity.targetX} Y=${activity.targetY}")
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("话题搜索辅助", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("只打开搜索并停在结果页；数量是本轮可确认的不同候选上限，不自动评论下一条。已验证评论的视频会记录防重复。")
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        val next = (platforms.indexOf(platform).coerceAtLeast(0) + 1) % platforms.size
                                        platform = platforms[next]
                                        saveSearch(platform, searchTopic, searchQuantity)
                                        activity.pointRefresh++
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("平台：$platform") }
                                OutlinedTextField(
                                    value = searchQuantity,
                                    onValueChange = {
                                        searchQuantity = it.filter { ch -> ch.isDigit() }.take(3)
                                        saveSearch(platform, searchTopic, searchQuantity)
                                    },
                                    label = { Text("候选上限") },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = searchTopic,
                                onValueChange = {
                                    searchTopic = it
                                    saveSearch(platform, it, searchQuantity)
                                },
                                label = { Text("搜索话题") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = { activity.startSearchAssist(platform, searchTopic, searchQuantity.toIntOrNull() ?: 10) },
                                enabled = gatePassed && searchTopic.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("打开搜索并停在结果页") }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { activity.startCommentCurrentPage(platform, commentText) },
                                enabled = gatePassed && commentText.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("评论这一条") }
                            Text("流程：搜索停在结果页后，你手动点开一条视频，再点“评论这一条”确认；成功后记录防重复，下一条仍需手动确认。已记录防重复 ${TaskStore.commentedTargetCount(activity)} 条。")
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("一条链接评论", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = commentLink,
                                onValueChange = {
                                    commentLink = it
                                    TaskStore.saveCommentInputs(activity, listOf(it), commentText, "10")
                                },
                                label = { Text("视频链接（一条）") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = commentText,
                                onValueChange = {
                                    commentText = it
                                    TaskStore.saveCommentInputs(activity, listOf(commentLink), it, "10")
                                },
                                label = { Text("评论内容") },
                                modifier = Modifier.fillMaxWidth().height(96.dp)
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = { activity.startComment(commentLink, commentText) },
                                enabled = gatePassed && commentLink.isNotBlank() && commentText.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("开始评论") }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { activity.stopTask() }, modifier = Modifier.fillMaxWidth()) { Text("停止任务") }
                            Text("状态：${activity.taskStatus}")
                            if (!gatePassed) Text("开始按钮未启用：先通过测试触发。评论成功只认输入框外新出现的评论。")
                        }
                    }
                }
                1 -> {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF111827))) {
                        Column(Modifier.padding(18.dp)) {
                            Text("视频", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            GateChips(activity)
                            Spacer(Modifier.height(8.dp))
                            Text(activity.gateReason, color = if (gatePassed) Color(0xFF86EFAC) else Color(0xFFFDE68A))
                            Text("状态：${activity.taskStatus}", color = Color(0xFFCBD5E1))
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("视频链接与发布辅助", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("链接在应用内解析并直达目标 App；只有浏览器可打开时会停止，不进入浏览器。")
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = videoLink,
                                onValueChange = {
                                    videoLink = it
                                    saveVideo(it, videoCaption, videoMaterial, videoSchedule)
                                },
                                label = { Text("视频链接（一条）") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = videoCaption,
                                onValueChange = {
                                    videoCaption = it
                                    saveVideo(videoLink, it, videoMaterial, videoSchedule)
                                },
                                label = { Text("发布文案") },
                                modifier = Modifier.fillMaxWidth().height(88.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = videoMaterial,
                                onValueChange = {
                                    videoMaterial = it
                                    saveVideo(videoLink, videoCaption, it, videoSchedule)
                                },
                                label = { Text("素材说明（只保存）") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = videoSchedule,
                                onValueChange = {
                                    videoSchedule = it
                                    saveVideo(videoLink, videoCaption, videoMaterial, it)
                                },
                                label = { Text("排期备注（只保存，不自动执行）") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = { activity.startPublishAssist(videoLink, videoCaption) },
                                enabled = gatePassed && videoLink.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("开始发布辅助") }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { activity.startDirectOpenTest(videoLink) },
                                    enabled = gatePassed && videoLink.isNotBlank(),
                                    modifier = Modifier.weight(1f)
                                ) { Text("直达测试") }
                                OutlinedButton(onClick = { activity.stopTask() }, modifier = Modifier.weight(1f)) { Text("停止任务") }
                            }
                            Text("发布成功只认点击后新出现的发布成功状态；直达后会验证前台包名。")
                            if (!gatePassed) Text("视频动作未启用：先到评论页通过测试触发。")
                        }
                    }
                }
                2 -> {
                    val researchInfo = PlatformRegistry.find(researchPlatform)
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))) {
                        Column(Modifier.padding(18.dp)) {
                            Text("话题找素材", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            GateChips(activity)
                            Spacer(Modifier.height(8.dp))
                            Text("按话题找高流量、新发视频/图文，只采集链接清单供学习；不从清单自动评论。", color = Color(0xFFCBD5E1))
                            Text(PlatformRegistry.routeSummary(researchPlatform), color = Color(0xFF93C5FD))
                            Text("App内搜索需要所选 App 已安装并登录；未安装会按包名失败。公开网页只采集公开可见结果，可能不完整，不用于评论证明。", color = Color(0xFFFDE68A))
                            if (researchInfo != null && !researchInfo.supported) {
                                Text("该平台不支持：${researchInfo.unsupportedReason}", color = Color(0xFFFCA5A5))
                            }
                            if (researchMode == "公开网页" && researchInfo != null && !researchInfo.publicWebSupported) {
                                Text("公开网页模式不支持：${researchInfo.publicWebReason}", color = Color(0xFFFCA5A5))
                            }
                            Text("状态：${activity.taskStatus}", color = Color(0xFFCBD5E1))
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        val next = (platforms.indexOf(researchPlatform).coerceAtLeast(0) + 1) % platforms.size
                                        researchPlatform = platforms[next]
                                        saveResearch()
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("平台：$researchPlatform") }
                                OutlinedButton(
                                    onClick = {
                                        researchMode = if (researchMode == "公开网页") "App内搜索" else "公开网页"
                                        saveResearch()
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("方式：$researchMode") }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        val types = listOf("全部", "视频", "图文")
                                        researchContentType = types[(types.indexOf(researchContentType).coerceAtLeast(0) + 1) % types.size]
                                        saveResearch()
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("类型：$researchContentType") }
                                OutlinedButton(
                                    onClick = {
                                        val sorts = listOf("综合", "高流量优先", "新发优先")
                                        researchSort = sorts[(sorts.indexOf(researchSort).coerceAtLeast(0) + 1) % sorts.size]
                                        saveResearch()
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("排序：$researchSort") }
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = researchTopic,
                                onValueChange = {
                                    researchTopic = it
                                    saveResearch()
                                },
                                label = { Text("话题") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = researchLimit,
                                onValueChange = {
                                    researchLimit = it.filter { ch -> ch.isDigit() }.take(3)
                                    saveResearch()
                                },
                                label = { Text("采集上限") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    if (researchMode == "公开网页") activity.collectPublicResearch(currentResearchInputs()) else activity.startResearchSearch(currentResearchInputs())
                                },
                                enabled = researchTopic.isNotBlank() && (researchMode == "公开网页" || gatePassed),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("打开搜索") }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { activity.collectResearch(currentResearchInputs()) },
                                    enabled = researchTopic.isNotBlank(),
                                    modifier = Modifier.weight(1f)
                                ) { Text("采集当前可见") }
                                OutlinedButton(
                                    onClick = { activity.recordResearchLink(currentResearchInputs()) },
                                    enabled = researchTopic.isNotBlank(),
                                    modifier = Modifier.weight(1f)
                                ) { Text("记录当前链接") }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { activity.copyResearchList() }, modifier = Modifier.weight(1f)) { Text("复制清单") }
                                OutlinedButton(onClick = { activity.clearResearchList() }, modifier = Modifier.weight(1f)) { Text("清空清单") }
                                OutlinedButton(onClick = { activity.researchRefresh++; activity.refreshState() }, modifier = Modifier.weight(1f)) { Text("刷新清单") }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("已采集 ${researchItems.size} 条 · 已去重 ${TaskStore.researchLastDedupedCount(activity)} 条 · 链接清单按${researchSort}排序。没有暴露链接/ID 的条目会显示待补链接，不编造 URL。")
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    if (researchItems.isEmpty()) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text("链接清单为空：先打开搜索，停在结果页后手动滑动，再点“采集当前可见”。")
                            }
                        }
                    } else {
                        researchItems.forEachIndexed { index, item ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp)) {
                                    Text("${index + 1}. ${item.title.ifBlank { "未识别标题" }}", fontWeight = FontWeight.Bold)
                                    Text("${item.scoreReason} · ${item.contentType} · ${item.author.ifBlank { "未知作者" }}")
                                    Text("${item.metricsText.ifBlank { "未识别流量" }} · ${item.timeText.ifBlank { "未识别时间" }}")
                                    Text(item.link.ifBlank { "待补链接" })
                                    Text("状态：${item.status}")
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }
                3 -> {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("语义点位", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            GateChips(activity)
                            Spacer(Modifier.height(8.dp))
                            Text("当前平台：$platform")
                            Text("点位保存角色、命名、节点签名和前后窗口证据。保存坐标不等于真实点击，必须点“测试点位点击”看到转换验证通过。")
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = { activity.startSyncRecognition(platform) }, modifier = Modifier.fillMaxWidth()) { Text("同步识别") }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { activity.showPointPreview(platform) }, modifier = Modifier.weight(1f)) { Text("预览点位") }
                                OutlinedButton(
                                    onClick = {
                                        val next = (platforms.indexOf(platform).coerceAtLeast(0) + 1) % platforms.size
                                        platform = platforms[next]
                                        saveSearch(platform, searchTopic, searchQuantity)
                                        activity.pointRefresh++
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("切换平台") }
                                OutlinedButton(onClick = { activity.showOverlayControl() }, modifier = Modifier.weight(1f)) { Text("悬浮控制窗") }
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    PointStore.roles.forEach { role ->
                        val point = semanticPoints[role]
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(PointStore.roleDisplayName(role), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    StatusChip(if (point == null) "未配置" else "已配置", point != null)
                                }
                                Text(PointStore.describeSemantic(point))
                                if (role == PointStore.ROLE_SEND) Text("发送键要在键盘打开、发送键可见时校准；键盘没打开时校准的位置会偏。")
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { activity.testSemanticPoint(platform, role) },
                                        enabled = point != null,
                                        modifier = Modifier.weight(1f)
                                    ) { Text("测试点位点击") }
                                    OutlinedButton(
                                        onClick = { activity.startCalibration(platform, role) },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("重新校准") }
                                    OutlinedButton(
                                        onClick = { activity.deleteSemanticPoint(platform, role) },
                                        enabled = point != null,
                                        modifier = Modifier.weight(1f)
                                    ) { Text("删除") }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("点位循环", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("只循环导航/测试语义点位，SEND 已排除；任一步转换验证失败就停止。")
                            Spacer(Modifier.height(8.dp))
                            PointStore.roles.filter { it != PointStore.ROLE_SEND }.forEach { role ->
                                val selected = role in loopRoles
                                OutlinedButton(
                                    onClick = {
                                        loopRoles = if (selected) loopRoles - role else loopRoles + role
                                        PointStore.saveLoop(activity, platform, loopRoles.toList(), loopRounds.toIntOrNull() ?: 1, loopInterval.toIntOrNull() ?: 2)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text(if (selected) "✓ ${PointStore.roleDisplayName(role)}" else PointStore.roleDisplayName(role)) }
                                Spacer(Modifier.height(6.dp))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = loopRounds,
                                    onValueChange = {
                                        loopRounds = it.filter { ch -> ch.isDigit() }.take(2)
                                        PointStore.saveLoop(activity, platform, loopRoles.toList(), loopRounds.toIntOrNull() ?: 1, loopInterval.toIntOrNull() ?: 2)
                                    },
                                    label = { Text("轮数 1-20") },
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = loopInterval,
                                    onValueChange = {
                                        loopInterval = it.filter { ch -> ch.isDigit() }.take(2)
                                        PointStore.saveLoop(activity, platform, loopRoles.toList(), loopRounds.toIntOrNull() ?: 1, loopInterval.toIntOrNull() ?: 2)
                                    },
                                    label = { Text("间隔秒") },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = { activity.startPointLoop(platform, loopRoles.toList(), loopRounds.toIntOrNull() ?: 1, loopInterval.toIntOrNull() ?: 2) },
                                enabled = gatePassed && loopRoles.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("开始点位循环") }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { activity.stopTask() }, modifier = Modifier.fillMaxWidth()) { Text("停止点位循环") }
                        }
                    }
                }
                else -> {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("日志", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            GateChips(activity)
                            Spacer(Modifier.height(8.dp))
                            Text(activity.serviceStatus)
                            Text("状态：${activity.taskStatus}")
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = { activity.copyErrorReport() }, modifier = Modifier.fillMaxWidth()) { Text("复制错误报告") }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { activity.refreshState() }, modifier = Modifier.weight(1f)) { Text("刷新状态") }
                                OutlinedButton(onClick = { activity.clearLog() }, modifier = Modifier.weight(1f)) { Text("清空日志") }
                                OutlinedButton(onClick = { activity.clearErrorReports() }, modifier = Modifier.weight(1f)) { Text("清空报告") }
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("运行日志", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        activity.logText,
                        modifier = Modifier.fillMaxWidth().height(260.dp).verticalScroll(rememberScrollState())
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("最近错误报告", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        activity.errorReportText,
                        modifier = Modifier.fillMaxWidth().height(180.dp).verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}
