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
            packageManager.getPackageInfo(packageName, 0).versionName ?: "4.3.0"
        } catch (_: Exception) {
            "4.3.0"
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
        AutomationService.startCalibration(this, platform, kind)
        taskStatus = if (kind == PointStore.KIND_SEND) {
            "校准发送点：先切到$platform 视频页并打开评论键盘，让发送键可见，再把十字圆心对准发送键保存。"
        } else {
            "校准评论点：切到$platform 视频页，把十字圆心对准评论入口保存。"
        }
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
        taskStatus = "点位预览已打开：切到$platform 页面，绿圈是评论点、红圈是发送点，拖动可改。"
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
    var selectedTab by remember { mutableStateOf(0) }
    var commentLink by remember { mutableStateOf(savedComment.first.firstOrNull() ?: "") }
    var commentText by remember { mutableStateOf(savedComment.second) }
    var videoLink by remember { mutableStateOf(savedVideo.link) }
    var videoCaption by remember { mutableStateOf(savedVideo.caption) }
    var videoMaterial by remember { mutableStateOf(savedVideo.material) }
    var videoSchedule by remember { mutableStateOf(savedVideo.schedule) }
    var platform by remember { mutableStateOf("抖音") }
    val tabs = listOf("评论", "视频", "点位", "日志")
    val platforms = listOf("抖音", "快手", "小红书", "哔哩哔哩", "微博")
    val gatePassed = activity.gateReason.startsWith("已通过")
    val commentPoint = remember(platform, activity.pointRefresh, activity.logText) {
        PointStore.describe(activity, platform, PointStore.KIND_COMMENT)
    }
    val sendPoint = remember(platform, activity.pointRefresh, activity.logText) {
        PointStore.describe(activity, platform, PointStore.KIND_SEND)
    }

    fun saveVideo(link: String, caption: String, material: String, schedule: String) {
        TaskStore.saveVideoInputs(activity, TaskStore.VideoInputs(link, caption, material, schedule))
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
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("点位", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            GateChips(activity)
                            Spacer(Modifier.height(8.dp))
                            Text("当前平台：$platform")
                            Text("发送点要在键盘打开、发送键可见时校准；键盘没打开时校准的位置会偏。")
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = { activity.showPointPreview(platform) }, modifier = Modifier.fillMaxWidth()) { Text("预览点位") }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        val next = (platforms.indexOf(platform).coerceAtLeast(0) + 1) % platforms.size
                                        platform = platforms[next]
                                        activity.pointRefresh++
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("切换平台") }
                                OutlinedButton(onClick = { activity.showOverlayControl() }, modifier = Modifier.weight(1f)) { Text("悬浮控制窗") }
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("评论点", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                StatusChip(if (commentPoint == "未配置") "未配置" else "已配置", commentPoint != "未配置")
                            }
                            Text("坐标：$commentPoint")
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { activity.startCalibration(platform, PointStore.KIND_COMMENT) }, modifier = Modifier.fillMaxWidth()) { Text("校准评论点") }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("发送点", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                StatusChip(if (sendPoint == "未配置") "未配置" else "已配置", sendPoint != "未配置")
                            }
                            Text("坐标：$sendPoint")
                            Text("校准前先打开评论键盘，让发送键可见。")
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { activity.startCalibration(platform, PointStore.KIND_SEND) }, modifier = Modifier.fillMaxWidth()) { Text("校准发送点") }
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
