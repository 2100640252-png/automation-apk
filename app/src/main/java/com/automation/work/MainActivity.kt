package com.automation.work

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class MainActivity : ComponentActivity() {

    var videos by mutableStateOf(listOf<StoredVideo>())
    var detectedPlatforms by mutableStateOf(listOf<PlatformApp>())
    var myPublishedLinks by mutableStateOf(listOf<String>())
    var permissionStatus by mutableStateOf(listOf<PermissionHelper.PermissionStatus>())
    var logText by mutableStateOf("暂无运行记录。")
    var errorReportText by mutableStateOf("暂无错误报告。")
    var taskStatus by mutableStateOf("未开始")
    var updateStatus by mutableStateOf("")
    var selfCheckText by mutableStateOf("还没自检，点“运行启动自检”。")
    var rootStatus by mutableStateOf("未知")
    var shizukuInstalled by mutableStateOf(false)
    var shizukuRunning by mutableStateOf(false)
    var shizukuGranted by mutableStateOf(false)
    var accessibilityOn by mutableStateOf(false)
    var startupError by mutableStateOf("")
    var updateInfo: UpdateChecker.UpdateInfo? by mutableStateOf(null)
    var githubToken by mutableStateOf("")

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshPermissions() }

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) savePickedVideo(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 启动容错（v1.7.2）：每一步单独兜底，任何一步出错都不许闪退，
        // 错误写到界面最上方，用户截图就能定位。
        val errors = mutableListOf<String>()
        fun step(name: String, block: () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                errors.add("$name：${e.javaClass.simpleName} ${e.message}")
            }
        }
        step("Shizuku 监听") {
            rikka.shizuku.Shizuku.addRequestPermissionResultListener { _, _ ->
                refreshSetupState()
                runSelfCheck()
            }
        }
        step("读取设置") { githubToken = TaskStore.loadGithubToken(this) }
        step("读取视频库") { refreshVideos() }
        step("读取权限状态") { refreshPermissions() }
        step("读取日志") { refreshLog() }
        step("检测平台") { refreshPlatforms() }
        step("读取已发布链接") { myPublishedLinks = TaskStore.loadMyPublishedLinks(this) }
        step("检测 Shizuku 状态") { refreshSetupState() }
        if (errors.isNotEmpty()) {
            startupError = "启动时有步骤出错（软件已容错打开）：\n" + errors.joinToString("\n")
        }
        setContent { AutomationApp(this) }
        // 界面出来后再申请权限，避免启动瞬间被权限弹窗打断。
        step("申请权限") {
            permissionLauncher.launch(PermissionHelper.runtimePermissions.toTypedArray())
        }
        if (errors.isNotEmpty() && startupError.isBlank()) {
            startupError = "启动时有步骤出错（软件已容错打开）：\n" + errors.joinToString("\n")
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        refreshVideos()
        refreshLog()
        refreshSetupState()
        taskStatus = AutomationService.lastStatus
    }

    fun refreshVideos() {
        videos = TaskStore.loadVideos(this)
    }

    fun refreshPlatforms() {
        detectedPlatforms = AppDetector.detect(this)
    }

    fun openPlatform(packageName: String) {
        if (!AppDetector.openApp(this, packageName)) {
            taskStatus = "打不开：手机没装这个应用，请先安装。"
        } else {
            taskStatus = "已打开应用。"
        }
        refreshLog()
    }

    /** 随机发布测试：从视频库随机挑一个视频，交给抖音发布页，文案随机。 */
    fun publishRandomVideoToDouyin() {
        val video = RandomContent.randomVideo(videos)
        if (video == null) {
            taskStatus = "视频库是空的，请先在视频版选一个视频存进来。"
            return
        }
        if (!detectedPlatforms.any { it.packageName == "com.ss.android.ugc.aweme" && it.installed }) {
            refreshPlatforms()
            if (!detectedPlatforms.any { it.packageName == "com.ss.android.ugc.aweme" && it.installed }) {
                taskStatus = "手机没装抖音，请先安装抖音并登录你的账号。"
                return
            }
        }
        val caption = RandomContent.randomCaption()
        if (AppDetector.openDouyinPublish(this, video.path, caption)) {
            taskStatus = "已把随机视频交给抖音发布页：${video.name}，文案：$caption，请在抖音里点发布确认。"
        } else {
            taskStatus = "打开抖音发布页失败，请确认抖音已安装并登录。"
        }
        refreshLog()
    }

    /** 立即发布：把选中的视频用系统分享直接交给所选平台的发布页（真发布流程）。 */
    fun publishNow(video: StoredVideo, platforms: List<String>, copy: ParsedCopy) {
        if (platforms.isEmpty()) {
            taskStatus = "请先勾选至少一个平台。"
            return
        }
        val caption = listOf(copy.body, copy.topics.joinToString(" ") { "#$it" }, copy.statement)
            .filter { it.isNotBlank() }.joinToString("\n")
        var ok = 0
        var fail = 0
        for (platform in platforms) {
            val (opened, _) = AppDetector.publishVideoDetailed(this, platform, video.path, caption)
            if (opened) {
                ok++
                val pkg = AppDetector.publishPackages[platform]
                if (pkg != null) AutomationService.startPublishAssist(this, platform, pkg, caption)
            } else fail++
        }
        taskStatus = if (fail == 0) {
            "已打开发布页并开始自动发布（共 $ok 个平台），结果看日志里的验证。"
        } else {
            "发布结果：打开发布页 $ok 个平台，失败 $fail 个平台。失败卡在哪一步写在日志里。"
        }
        refreshLog()
    }

    /** 平台页“发视频”：从视频库随机挑一个，直接交给该平台发布页。 */
    fun publishRandomVideoToPlatform(platform: String) {
        val video = RandomContent.randomVideo(videos)
        if (video == null) {
            taskStatus = "视频库是空的，请先在视频版选一个视频存进来。"
            return
        }
        val caption = RandomContent.randomCaption()
        val (opened, reason) = AppDetector.publishVideoDetailed(this, platform, video.path, caption)
        if (opened) {
            val pkg = AppDetector.publishPackages[platform]
            if (pkg != null) AutomationService.startPublishAssist(this, platform, pkg, caption)
            taskStatus = "已打开 $platform 发布页并开始自动发布：${video.name}，结果看日志。"
        } else {
            taskStatus = "发视频到 $platform 失败：$reason"
        }
        refreshLog()
    }

    /** 随机评论到自己已发布的视频：随机挑一条链接 + 随机评论，开始任务。 */
    fun commentRandomToMyVideo() {
        val links = TaskStore.loadMyPublishedLinks(this)
        if (links.isEmpty()) {
            taskStatus = "请先在下方填你抖音账号里已发布视频的链接（每行一条），保存后再点随机评论。"
            return
        }
        val link = links.random()
        val comment = RandomContent.randomComment()
        taskStatus = "随机评论测试：挑中 $link，评论：$comment"
        startCommentTask(listOf(link), comment, 10)
    }

    fun saveMyPublishedLinks(raw: String) {
        val links = raw.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        TaskStore.saveMyPublishedLinks(this, links)
        myPublishedLinks = links
        taskStatus = "已保存 ${links.size} 条已发布视频链接。"
    }

    fun refreshPermissions() {
        permissionStatus = PermissionHelper.allStatus(this)
    }

    fun startCalibration(platform: String, kind: String) {
        val kindName = if (kind == PointStore.KIND_SEND) "发送点" else "评论点"
        if (AutomationService.instance == null) {
            taskStatus = "校准需要先开无障碍：去设置 > 无障碍开启“自动化屏幕控制”，再回来校准。"
            return
        }
        AutomationService.startCalibration(this, platform, kind)
        taskStatus = "校准悬浮窗已打开：切到$platform 视频页，十字圆心对准${kindName}中心，点保存点位。"
        refreshLog()
    }

    fun showPointPreview(platform: String) {
        if (AutomationService.instance == null) {
            taskStatus = "悬浮窗需要先开无障碍：去设置 > 无障碍开启“自动化屏幕控制”。"
            return
        }
        AutomationService.startPointPreview(this, platform)
        taskStatus = "点位悬浮窗已打开：切到$platform 视频页，绿圈是评论点、红圈是发送点，拖动就能改位置。"
        refreshLog()
    }

    fun hidePointPreview() {
        AutomationService.hidePointPreview()
        taskStatus = "点位悬浮窗已关闭。"
    }


    fun openLinkOnly(link: String) {
        if (link.isBlank()) { taskStatus = "先输入链接。"; return }
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link.trim())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            taskStatus = "已用系统跳转打开链接。"
            TaskStore.appendLog(this, "链接直开：${link.trim()}")
        } catch (e: Exception) {
            taskStatus = "链接打不开：${e.message}"
            TaskStore.appendLog(this, taskStatus)
        }
        refreshLog()
    }

    fun startScriptOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            taskStatus = "先允许悬浮窗权限，允许后回来再点打开悬浮脚本。"
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        startService(Intent(this, ScriptOverlayService::class.java))
        taskStatus = "悬浮脚本已打开：右侧脚本按钮可拖动，点它展开。"
        refreshLog()
    }

    fun testShizukuInput(x: Int, y: Int) {
        taskStatus = "正在测试 Shizuku：先查 uid，再真实点一次检测按钮…"
        Thread {
            val (ok, message) = InputEngine.shizukuSelfTest()
            TaskStore.appendLog(this, "Shizuku 自测：$message")
            if (!ok) {
                runOnUiThread { taskStatus = message; refreshLog() }
                return@Thread
            }
            val tapped = InputEngine.tap(x, y)
            TaskStore.appendLog(this, "Shizuku 触发测试：${InputEngine.lastResult}")
            runOnUiThread {
                taskStatus = if (tapped) "已发出真实点击，等检测按钮回显。" else "Shizuku 点击失败：${InputEngine.lastResult}"
                refreshLog()
            }
        }.start()
    }

    fun onShizukuTriggerDetected() {
        taskStatus = "Shizuku 输入已真实触发：检测按钮被脚本点到了。"
        TaskStore.appendLog(this, taskStatus)
        refreshLog()
    }

    fun stopScriptOverlay() {
        startService(Intent(this, ScriptOverlayService::class.java).setAction(ScriptOverlayService.ACTION_STOP))
        taskStatus = "悬浮脚本已关闭。"
        refreshLog()
    }

    fun startLinkComment(link: String, comment: String) {
        if (link.isBlank()) { taskStatus = "先输入链接。"; return }
        if (AutomationService.instance == null) {
            taskStatus = "先开无障碍：去设置 > 无障碍开启“自动化屏幕控制”。"
            PermissionHelper.openAccessibilitySettings(this)
            return
        }
        AutomationService.startLinkComment(this, link.trim(), comment)
        taskStatus = "正在自动打开链接、识别页面并评论…"
        refreshLog()
    }

    fun showIntervention() {
        if (AutomationService.instance == null) {
            taskStatus = "真人介入窗需要无障碍，已打开无障碍设置，开启后回来再点。"
            PermissionHelper.openAccessibilitySettings(this)
            return
        }
        AutomationService.showInterventionNow(this)
        taskStatus = "悬浮控制窗已打开：可以手动操作、校准点位、重新识别、保存错误报告。"
        refreshLog()
    }

    fun copyErrorReport() {
        val report = TaskStore.loadLastErrorReport(this)
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("automation_error_report", report))
        taskStatus = "错误报告已复制，可以发出来定位是哪一步失败。"
        refreshLog()
    }

    fun clearErrorReports() {
        TaskStore.clearErrorReports(this)
        taskStatus = "错误报告已清空。"
        refreshLog()
    }

    fun startRecorder(platform: String) {
        if (AutomationService.instance == null) {
            taskStatus = "记录点击需要无障碍，已打开无障碍设置，开启后回来再点。"
            PermissionHelper.openAccessibilitySettings(this)
            return
        }
        AutomationService.startRecorder(this, platform)
        taskStatus = "记录悬浮窗已打开：切到$platform，按顺序点按键，点完按完成记录。"
        refreshLog()
    }

    fun runSequence(platform: String, comment: String) {
        if (AutomationService.instance == null) {
            taskStatus = "先开无障碍再执行点击序列。"
            PermissionHelper.openAccessibilitySettings(this)
            return
        }
        AutomationService.runSequenceNow(this, platform, comment)
        taskStatus = "正在按记录的点击序列真实点击…"
        refreshLog()
    }

    fun publishVideoSimple(platform: String, caption: String) {
        val video = videos.firstOrNull()
        if (video == null) { taskStatus = "先选择一个视频。"; return }
        val (ok, reason) = AppDetector.publishVideoDetailed(this, platform, video.path, caption)
        TaskStore.appendLog(this, "发视频：$reason")
        if (ok) {
            val pkg = AppDetector.publishPackages[platform]
            if (pkg != null) {
                AutomationService.startPublishAssist(this, platform, pkg, caption)
                taskStatus = "已打开 $platform 发布页，正在自动填文案、点发布并验证…"
            } else {
                taskStatus = reason
            }
        } else {
            taskStatus = reason
        }
        refreshLog()
    }

    fun refreshLog() {
        logText = TaskStore.loadLog(this)
        errorReportText = TaskStore.loadLastErrorReport(this)
    }

    fun requestPermissions() {
        permissionLauncher.launch(PermissionHelper.runtimePermissions.toTypedArray())
    }

    fun pickVideo() {
        pickVideoLauncher.launch("video/*")
    }

    /** 把用户选的视频复制到应用内 files/videos，存进视频库，列表立刻能看到。 */
    private fun savePickedVideo(uri: Uri) {
        try {
            var name = "video_${System.currentTimeMillis()}.mp4"
            var size = 0L
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIdx >= 0) cursor.getString(nameIdx)?.let { name = it }
                    if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                }
            }
            val dir = File(filesDir, "videos").apply { mkdirs() }
            val dest = File(dir, "${UUID.randomUUID()}_$name")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
            if (size == 0L) size = dest.length()
            TaskStore.addVideo(
                this,
                StoredVideo(
                    UUID.randomUUID().toString(), name, dest.absolutePath,
                    size, System.currentTimeMillis(), 0L, emptyList(), "已保存"
                )
            )
            TaskStore.appendLog(this, "已保存视频：$name（${size / 1024} KB）")
            refreshVideos()
            refreshLog()
        } catch (e: Exception) {
            TaskStore.appendLog(this, "保存视频失败：${e.message}")
            refreshLog()
        }
    }

    fun scheduleVideo(video: StoredVideo, platforms: List<String>, minutes: Int, copy: ParsedCopy) {
        if (platforms.isEmpty()) {
            taskStatus = "请先勾选至少一个平台。"
            return
        }
        val job = VideoJob(
            video.name, video.path, platforms,
            System.currentTimeMillis() + minutes.coerceAtLeast(1) * 60_000L, copy
        )
        VideoScheduler.schedule(this, job)
        TaskStore.updateVideoStatus(this, video.id, "已排期：$minutes 分钟后")
        refreshVideos()
        taskStatus = "已排期：${video.name}，$minutes 分钟后提醒发布。"
    }

    fun startCommentTask(links: List<String>, comment: String, intervalSec: Int) {
        if (!PermissionHelper.isAccessibilityEnabled(this)) {
            taskStatus = "请先在权限面板里开启“无障碍屏幕控制”，再开始任务。"
            PermissionHelper.openAccessibilitySettings(this)
            return
        }
        if (comment.isBlank()) {
            taskStatus = "请先填写自定义评论内容。"
            return
        }
        val task = CommentTask.fromLinks(links, comment, intervalSec)
        if (task.items.isEmpty()) {
            taskStatus = "请至少填写 1 个视频链接。"
            return
        }
        taskStatus = "任务开始：${task.items.size} 条，正在打开第 1 条…"
        TaskStore.appendLog(this, taskStatus)
        AutomationService.startTask(this, task)
        refreshLog()
    }

    fun stopCommentTask() {
        AutomationService.stopTask(this)
        taskStatus = "任务已停止"
        refreshLog()
    }

    /** 只测试打开第一条链接（不评论）：用来定位链接卡在哪一步。 */
    fun testOpenFirstLink(links: List<String>) {
        val link = links.firstOrNull { it.isNotBlank() }
        if (link == null) {
            taskStatus = "请先填写至少 1 个视频链接。"
            return
        }
        taskStatus = "正在测试打开链接：$link"
        Thread {
            val result = LinkResolver.openVideoLinkDetailed(this, link)
            runOnUiThread {
                taskStatus = if (result.success) {
                    "测试打开成功：已用${result.method}打开${result.platform}。评论任务会接着找评论框。"
                } else {
                    "测试打开失败：${result.reason}"
                }
                refreshLog()
            }
        }.start()
    }

    /** 自动化引擎是否可用：Root 或 Shizuku 任一可用即可（v1.7.3 修复只认 Root 的拦截）。 */
    fun engineAvailable(): Pair<Boolean, String> {
        val (rootOk, rootMsg) = RootHelper.checkRoot()
        if (rootOk) return true to "Root 可用"
        val (szOk, szMsg) = ShizukuHelper.checkShizuku()
        if (szOk) return true to "Shizuku 可用（不用 Root）"
        return false to "Root 不可用：$rootMsg；Shizuku 不可用：$szMsg"
    }

    /** 启动自检（v1.6）：逐项实测，逐项报通过或失败，不写假的“已完成”。 */
    fun runSelfCheck() {
        val lines = mutableListOf<String>()
        val (rootOk, rootMsg) = RootHelper.checkRoot()
        val (szOk, szMsg) = ShizukuHelper.checkShizuku()
        rootStatus = when {
            rootOk -> "已 Root"
            szOk -> "未 Root · Shizuku 可用"
            else -> "未 Root"
        }
        lines.add("Root 权限：${if (rootOk) "通过" else "未 Root——不用 Root，Shizuku 通过就能跑"}——$rootMsg")
        lines.add("Shizuku：${if (szOk) "通过" else "不通过"}——$szMsg")
        refreshPlatforms()
        val dy = detectedPlatforms.firstOrNull { it.packageName == "com.ss.android.ugc.aweme" }
        lines.add("抖音安装：${if (dy?.installed == true) "通过（${dy.versionName}）" else "不通过——手机没装抖音"}")
        val accOk = PermissionHelper.isAccessibilityEnabled(this)
        lines.add("无障碍服务：${if (accOk) "通过——已开启" else "不通过——没开，Root 评论填中文要用它，去权限面板开"}")
        lines.add("视频库：${videos.size} 个视频${if (videos.isEmpty()) "——还是空的，先存一个视频" else ""}")
        val linksOk = TaskStore.loadMyPublishedLinks(this).isNotEmpty()
        lines.add("已发布链接：${if (linksOk) "通过（${TaskStore.loadMyPublishedLinks(this).size} 条）" else "不通过——平台页里还没保存已发布视频链接"}")
        selfCheckText = lines.joinToString("\n")
        TaskStore.appendLog(this, "启动自检：\n" + selfCheckText)
        taskStatus = if ((rootOk || szOk) && dy?.installed == true) {
            "自检通过：${if (rootOk) "Root" else "Shizuku"} 模式，可以开始自动化。"
        } else {
            "自检有不通过的项，请先看上面哪一项。"
        }
        refreshLog()
    }

    /** 设置向导状态：Shizuku 装没装、启动没启动、授权没授权、无障碍开没开。 */
    fun refreshSetupState() {
        try {
            shizukuInstalled = try {
                packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
                true
            } catch (e: Exception) {
                false
            }
            shizukuRunning = ShizukuHelper.isAvailable()
            shizukuGranted = ShizukuHelper.isPermissionGranted()
            accessibilityOn = PermissionHelper.isAccessibilityEnabled(this)
        } catch (e: Exception) {
            // 检测本身出错也不能让软件打不开，状态保持原样即可。
        }
    }

    /** 打开 Shizuku App；没装就去下载页。 */
    fun openShizukuApp() {
        val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (intent != null) {
            startActivity(intent)
        } else {
            openShizukuDownload()
        }
    }

    /** 打开 Shizuku 官方下载页。 */
    fun openShizukuDownload() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RikkaApps/Shizuku/releases")))
        } catch (e: Exception) {
            taskStatus = "打不开 Shizuku 下载页：${e.message}"
        }
    }

    /** 打开开发者选项（无线调试开关在这里面）。 */
    fun openDeveloperOptions() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e2: Exception) {
                taskStatus = "打不开开发者选项：${e2.message}"
            }
        }
    }

    /** 用自动化引擎启动抖音（Root 或 Shizuku，monkey 按包名启动，不依赖无障碍）。 */
    fun openDouyinByRoot() {
        val (engineOk, engineMsg) = engineAvailable()
        if (!engineOk) {
            taskStatus = "自动化引擎不可用：$engineMsg"
            return
        }
        val cmd = "monkey -p com.ss.android.ugc.aweme -c android.intent.category.LAUNCHER 1"
        val r = if (RootHelper.checkRoot().first) RootHelper.runAsRoot(cmd) else ShizukuHelper.run(cmd)
        taskStatus = if (r.exitCode == 0) "已启动抖音（$engineMsg）。" else "启动抖音失败：${r.output.take(150)}"
        TaskStore.appendLog(this, taskStatus)
        refreshLog()
    }

    /** Root 评论任务：后台线程逐条跑 Root 引擎，成功失败分开计。 */
    fun startRootCommentTask(links: List<String>, comment: String, intervalSec: Int) {
        val items = links.filter { it.isNotBlank() }
        if (items.isEmpty()) {
            taskStatus = "请至少填写 1 个视频链接。"
            return
        }
        if (comment.isBlank()) {
            taskStatus = "请先填写自定义评论内容。"
            return
        }
        val (engineOk, engineMsg) = engineAvailable()
        if (!engineOk) {
            taskStatus = "自动化引擎不可用，跑不了评论：$engineMsg。Shizuku 已运行时，请先点“申请 Shizuku 授权”。"
            return
        }
        taskStatus = "评论任务开始（$engineMsg）：共 ${items.size} 条…"
        TaskStore.appendLog(this, taskStatus)
        refreshLog()
        Thread {
            var ok = 0
            var fail = 0
            for ((i, link) in items.withIndex()) {
                val (done, reason) = RootAutomator.commentOnVideo(this, link, comment)
                if (done) ok++ else {
                    fail++
                    TaskStore.appendLog(this, "第 ${i + 1} 条失败：$reason")
                }
                runOnUiThread {
                    taskStatus = "评论进行中：成功 $ok 条，失败 $fail 条（共 ${items.size} 条）"
                    refreshLog()
                }
                if (i < items.size - 1) Thread.sleep(intervalSec.coerceAtLeast(1) * 1000L)
            }
            runOnUiThread {
                taskStatus = "评论任务结束：成功 $ok 条，失败 $fail 条。" +
                    if (fail > 0) "失败原因写在日志里。" else ""
                TaskStore.appendLog(this, taskStatus)
                refreshLog()
            }
        }.start()
    }

    fun checkUpdate() {
        updateStatus = "正在检查更新…"
        UpdateChecker.checkForUpdate(this) { info, msg ->
            updateInfo = info
            updateStatus = msg
            refreshLog()
        }
    }

    fun downloadUpdate() {
        val info = updateInfo ?: return
        updateStatus = "正在下载新版…"
        UpdateChecker.downloadAndInstall(this, info,
            onProgress = { updateStatus = it },
            onDone = { ok, msg ->
                updateStatus = msg
                if (!ok) TaskStore.appendLog(this, "更新失败：$msg")
                refreshLog()
            })
    }

    fun saveToken(token: String) {
        githubToken = token
        TaskStore.saveGithubToken(this, token)
        updateStatus = "Token 已保存，可以点检查更新了。"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationApp(activity: MainActivity) {
    var link by remember { mutableStateOf(ScriptStore.loadLink(activity)) }
    var pasteText by remember { mutableStateOf(ScriptStore.loadText(activity)) }
    var stepsRefresh by remember { mutableStateOf(0) }
    val steps = remember(stepsRefresh) { ScriptStore.loadSteps(activity) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("悬浮脚本 v${UpdateChecker.currentVersionName(activity)}") },
                actions = { TextButton(onClick = { activity.checkUpdate() }) { Text("检查更新") } }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            Card(
                Modifier.fillMaxWidth().background(Color(0xFF0F172A), RoundedCornerShape(18.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("链接直开 + 悬浮脚本", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("状态：${activity.taskStatus}", color = Color(0xFFCBD5E1))
                    Text("输入层：${InputEngine.backendName()}", color = Color(0xFF93C5FD))
                    Text("已记点位：${steps.size} 个", color = Color(0xFFFDE68A))
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { activity.startScriptOverlay() }, modifier = Modifier.weight(1f)) { Text("打开悬浮脚本") }
                        OutlinedButton(onClick = { activity.stopScriptOverlay() }, modifier = Modifier.weight(1f)) { Text("关闭悬浮") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { ShizukuHelper.requestPermission() }, modifier = Modifier.weight(1f)) { Text("申请 Shizuku 授权") }
                        OutlinedButton(onClick = { activity.openShizukuApp() }, modifier = Modifier.weight(1f)) { Text("打开 Shizuku") }
                    }
                    var testX by remember { mutableStateOf(0) }
                    var testY by remember { mutableStateOf(0) }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { activity.testShizukuInput(testX, testY) }, modifier = Modifier.weight(1f)) { Text("测试 Shizuku 触发") }
                        OutlinedButton(
                            onClick = { activity.onShizukuTriggerDetected() },
                            modifier = Modifier.weight(1f).onGloballyPositioned { coords ->
                                val pos = coords.positionInRoot()
                                testX = (pos.x + coords.size.width / 2).toInt()
                                testY = (pos.y + coords.size.height / 2).toInt()
                            }
                        ) { Text("触发检测按钮") }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("链接直开", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = link,
                onValueChange = { link = it; ScriptStore.saveLink(activity, it) },
                label = { Text("粘贴链接") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { activity.openLinkOnly(link) }, modifier = Modifier.fillMaxWidth()) { Text("直接打开链接") }

            Spacer(Modifier.height(16.dp))
            Text("脚本点位", style = MaterialTheme.typography.titleMedium)
            Text("打开悬浮脚本后点“捕捉点位”，在目标页面点一下或滑动一下，脚本会记住并回显到这里。")
            Spacer(Modifier.height(8.dp))
            if (steps.isEmpty()) {
                Text("还没有点位。")
            } else {
                steps.forEachIndexed { i, s ->
                    var name by remember(i, stepsRefresh) { mutableStateOf(s.name) }
                    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("第 ${i + 1} 步：${s.type} · X ${(s.x * 100).toInt()}% Y ${(s.y * 100).toInt()}%")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = name,
                                    onValueChange = { name = it },
                                    label = { Text("命名") },
                                    modifier = Modifier.weight(1f)
                                )
                                Button(onClick = {
                                    val list = steps.toMutableList(); list[i] = s.copy(name = name.ifBlank { s.name })
                                    ScriptStore.saveSteps(activity, list); stepsRefresh++
                                }) { Text("保存名") }
                                TextButton(onClick = {
                                    val list = steps.toMutableList(); list.removeAt(i)
                                    ScriptStore.saveSteps(activity, list); stepsRefresh++
                                }) { Text("删除") }
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    ScriptStore.addStep(activity, ScriptStore.Step(ScriptStore.TYPE_WAIT, "等待${steps.size + 1}", durationMs = 1000)); stepsRefresh++
                }, modifier = Modifier.weight(1f)) { Text("加等待") }
                OutlinedButton(onClick = {
                    ScriptStore.addStep(activity, ScriptStore.Step(ScriptStore.TYPE_PASTE, "粘贴${steps.size + 1}", text = pasteText)); stepsRefresh++
                }, modifier = Modifier.weight(1f)) { Text("加粘贴") }
                OutlinedButton(onClick = { stepsRefresh++ }, modifier = Modifier.weight(1f)) { Text("刷新点位") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = pasteText,
                onValueChange = { pasteText = it; ScriptStore.saveText(activity, it) },
                label = { Text("粘贴步骤用的文字") },
                modifier = Modifier.fillMaxWidth().height(80.dp)
            )

            Spacer(Modifier.height(16.dp))
            Text("运行日志", style = MaterialTheme.typography.titleSmall)
            Text(activity.logText, modifier = Modifier.fillMaxWidth().height(180.dp).verticalScroll(rememberScrollState()))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { activity.refreshLog() }, modifier = Modifier.weight(1f)) { Text("刷新日志") }
                TextButton(onClick = { TaskStore.clearLog(activity); activity.refreshLog() }, modifier = Modifier.weight(1f)) { Text("清空日志") }
            }
            if (activity.updateInfo != null) {
                Spacer(Modifier.height(8.dp))
                Text("发现新版：${activity.updateInfo!!.tag}")
                Button(onClick = { activity.downloadUpdate() }, modifier = Modifier.fillMaxWidth()) { Text("下载并安装新版") }
            }
        }
    }
}
