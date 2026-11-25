package com.automation.work

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
            if (AppDetector.publishVideo(this, platform, video.path, caption)) ok++ else fail++
        }
        taskStatus = if (fail == 0) {
            "已把视频交给 ${platforms.joinToString("、")} 的发布页，请在各平台里点发布确认（共 $ok 个平台）。"
        } else {
            "发布结果：成功 $ok 个平台，失败 $fail 个平台。失败原因写在日志里，请先看日志。"
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
        if (AppDetector.publishVideo(this, platform, video.path, caption)) {
            taskStatus = "已把随机视频交给 $platform 发布页：${video.name}，请在 $platform 里点发布确认。"
        } else {
            taskStatus = "发视频到 $platform 失败，原因写在日志里，请先看日志。"
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

    fun refreshLog() {
        logText = TaskStore.loadLog(this)
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

    /** 启动自检（v1.6）：逐项实测，逐项报通过或失败，不写假的“已完成”。 */
    fun runSelfCheck() {
        val lines = mutableListOf<String>()
        val (rootOk, rootMsg) = RootHelper.checkRoot()
        rootStatus = if (rootOk) "已 Root" else "未 Root"
        lines.add("Root 权限：${if (rootOk) "通过" else "不通过"}——$rootMsg")
        val (szOk, szMsg) = ShizukuHelper.checkShizuku()
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
        taskStatus = if (rootOk && dy?.installed == true) "自检通过，可以开始 Root 自动化。" else "自检有不通过的项，请先看上面哪一项。"
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

    /** 用 Root 直接启动抖音（monkey 按包名启动，不依赖无障碍）。 */
    fun openDouyinByRoot() {
        val (rootOk, rootMsg) = RootHelper.checkRoot()
        if (!rootOk) {
            taskStatus = "Root 不可用：$rootMsg"
            return
        }
        val r = RootHelper.runAsRoot("monkey -p com.ss.android.ugc.aweme -c android.intent.category.LAUNCHER 1")
        taskStatus = if (r.exitCode == 0) "Root 已启动抖音。" else "Root 启动抖音失败：${r.output.take(150)}"
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
        val (rootOk, rootMsg) = RootHelper.checkRoot()
        if (!rootOk) {
            taskStatus = "Root 不可用，跑不了 Root 评论：$rootMsg"
            return
        }
        taskStatus = "Root 评论任务开始：共 ${items.size} 条…"
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
                    taskStatus = "Root 评论进行中：成功 $ok 条，失败 $fail 条（共 ${items.size} 条）"
                    refreshLog()
                }
                if (i < items.size - 1) Thread.sleep(intervalSec.coerceAtLeast(1) * 1000L)
            }
            runOnUiThread {
                taskStatus = "Root 评论任务结束：成功 $ok 条，失败 $fail 条。" +
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
    var tab by remember { mutableStateOf(0) } // 0 视频，1 评论，2 更新
    var copyText by remember { mutableStateOf("") }
    var commentText by remember { mutableStateOf("") }
    var interval by remember { mutableStateOf("10") }
    var minutesAfter by remember { mutableStateOf("10") }
    val links = remember { mutableStateListOf<String>().apply { repeat(10) { add("") } } }
    val selectedPlatforms = remember { mutableStateListOf<String>() }
    var selectedVideoId by remember { mutableStateOf<String?>(null) }
    val parsed = remember(copyText) { if (copyText.isBlank()) null else CopyParser.parse(copyText) }
    val platforms = listOf("抖音", "快手", "小红书", "视频号", "哔哩哔哩", "TikTok", "YouTube", "微博")
    val missing = activity.permissionStatus.filter { !it.granted }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("自动化工作 v${UpdateChecker.currentVersionName(activity)}") },
                actions = {
                    TextButton(onClick = { activity.checkUpdate() }) { Text("检查更新") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
            if (activity.startupError.isNotBlank()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(activity.startupError)
                        Text("把这段文字截图发给我，我按这个修。")
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            // 权限面板：一打开就索取，缺哪项直接显示、点一下就去开。
            if (missing.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("还需要这些权限（点一下去开启）：", style = MaterialTheme.typography.titleSmall)
                        missing.forEach { p ->
                            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("• ${p.name}：未开启")
                                TextButton(onClick = {
                                    if (p.key.startsWith("runtime:")) activity.requestPermissions()
                                    else PermissionHelper.openSettingsFor(activity, p.key)
                                }) { Text("去开启") }
                            }
                        }
                        TextButton(onClick = { activity.requestPermissions() }) { Text("重新申请全部运行时权限") }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("视频版") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("评论版") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("更新") })
                Tab(selected = tab == 3, onClick = { tab = 3; activity.refreshPlatforms() }, text = { Text("平台") })
                Tab(selected = tab == 4, onClick = { tab = 4; activity.runSelfCheck() }, text = { Text("Root") })
            }
            Spacer(Modifier.height(12.dp))

            if (tab == 0) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("视频版：选视频、存进库、定时间按时发", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { activity.pickVideo() }, modifier = Modifier.fillMaxWidth()) {
                        Text("选择视频（存进视频库）")
                    }
                    Spacer(Modifier.height(8.dp))
                    if (activity.videos.isEmpty()) {
                        Text("视频库还是空的，点上面按钮选一个视频。")
                    } else {
                        activity.videos.forEach { v ->
                            Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                                Column(Modifier.padding(10.dp)) {
                                    Text("${v.name}（${v.sizeBytes / 1024} KB）")
                                    Text("状态：${v.status}")
                                    Row {
                                        TextButton(onClick = { selectedVideoId = v.id }) {
                                            Text(if (selectedVideoId == v.id) "已选中 ✓" else "选中这个视频")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("要发的平台（可多选）：")
                    platforms.chunked(4).forEach { row ->
                        Row {
                            row.forEach { p ->
                                Row(Modifier.padding(end = 8.dp)) {
                                    Checkbox(
                                        checked = selectedPlatforms.contains(p),
                                        onCheckedChange = {
                                            if (it) selectedPlatforms.add(p) else selectedPlatforms.remove(p)
                                        }
                                    )
                                    Text(p)
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = minutesAfter,
                        onValueChange = { minutesAfter = it },
                        label = { Text("几分钟后发布") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = copyText,
                        onValueChange = { copyText = it },
                        label = { Text("粘贴文案（正文 + #话题 + 声明）") },
                        modifier = Modifier.fillMaxWidth().height(120.dp)
                    )
                    parsed?.let {
                        Text("识别：正文：${it.body}")
                        Text("话题：${it.topics.joinToString(" ") { t -> "#$t" }}")
                        if (it.statement.isNotEmpty()) Text("声明：${it.statement}")
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val video = activity.videos.firstOrNull { it.id == selectedVideoId }
                                ?: activity.videos.firstOrNull()
                            if (video == null) {
                                activity.taskStatus = "请先选择一个视频。"
                            } else {
                                val copy = parsed ?: CopyParser.parse(copyText)
                                activity.publishNow(video, selectedPlatforms.toList(), copy)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("立即发布这个视频（直交平台发布页）") }
                    Spacer(Modifier.height(8.dp))
                    Text("定时只是提醒：到时间发通知，点通知回来再一键发布，软件不能在后台悄悄替你发。")
                    Button(
                        onClick = {
                            val video = activity.videos.firstOrNull { it.id == selectedVideoId }
                                ?: activity.videos.firstOrNull()
                            if (video == null) {
                                activity.taskStatus = "请先选择一个视频。"
                            } else {
                                val copy = parsed ?: CopyParser.parse(copyText)
                                activity.scheduleVideo(
                                    video, selectedPlatforms.toList(),
                                    minutesAfter.toIntOrNull() ?: 10, copy
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("定时提醒这个视频") }
                    Text(activity.taskStatus)
                }
            } else if (tab == 1) {
                Text("评论版：一次最多 10 个链接", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = commentText,
                    onValueChange = { commentText = it },
                    label = { Text("自定义评论内容（空白文本）") },
                    modifier = Modifier.fillMaxWidth().height(100.dp)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = interval,
                    onValueChange = { interval = it },
                    label = { Text("发送间隔（秒）") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(10) { i ->
                        OutlinedTextField(
                            value = links[i],
                            onValueChange = { links[i] = it },
                            label = { Text("视频链接 ${i + 1}") },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            activity.startCommentTask(
                                links.toList(), commentText,
                                interval.toIntOrNull() ?: 10
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("创建并开始任务") }
                    OutlinedButton(
                        onClick = { activity.stopCommentTask() },
                        modifier = Modifier.weight(1f)
                    ) { Text("停止任务") }
                }
                Spacer(Modifier.height(8.dp))
                Text(activity.taskStatus)
                Text("运行日志（失败会写原因、自动重试）：", style = MaterialTheme.typography.titleSmall)
                Text(
                    activity.logText,
                    modifier = Modifier.fillMaxWidth().height(140.dp).verticalScroll(rememberScrollState())
                )
                TextButton(onClick = {
                    TaskStore.clearLog(activity)
                    activity.refreshLog()
                }) { Text("清空日志") }
            } else if (tab == 3) {
                // 平台页：检测手机装了什么，直接打开；随机发布、随机评论测试。
                var myLinksText by remember(activity.myPublishedLinks) { mutableStateOf(activity.myPublishedLinks.joinToString("\n")) }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("平台检测：手机上装了什么，直接打开", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { activity.refreshPlatforms() }) { Text("重新检测") }
                    activity.detectedPlatforms.forEach { p ->
                        Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            Row(
                                Modifier.fillMaxWidth().padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("${p.name}：${if (p.installed) "已安装 ${p.versionName}" else "未安装"}")
                                if (p.installed) {
                                    Row {
                                        TextButton(onClick = { activity.openPlatform(p.packageName) }) { Text("打开") }
                                        TextButton(onClick = { activity.publishRandomVideoToPlatform(p.name) }) { Text("发视频") }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("抖音真实发布测试", style = MaterialTheme.typography.titleMedium)
                    Text("从视频库随机挑一个视频，交给抖音发布页，文案随机，点发布前在抖音里确认。")
                    Button(
                        onClick = { activity.publishRandomVideoToDouyin() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("随机发布一条视频到抖音") }
                    Spacer(Modifier.height(12.dp))
                    Text("随机评论到我已发布的视频", style = MaterialTheme.typography.titleMedium)
                    Text("先把你抖音账号里已发布视频的链接贴下面，每行一条，保存后点随机评论，会随机挑一条、随机写一句评论去发。")
                    OutlinedTextField(
                        value = myLinksText,
                        onValueChange = { myLinksText = it },
                        label = { Text("我已发布的视频链接，每行一条") },
                        modifier = Modifier.fillMaxWidth().height(120.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { activity.saveMyPublishedLinks(myLinksText) }) { Text("保存链接") }
                        Button(onClick = { activity.commentRandomToMyVideo() }) { Text("随机评论一条") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(activity.taskStatus)
                    Text(activity.logText, modifier = Modifier.fillMaxWidth().height(120.dp).verticalScroll(rememberScrollState()))
                }
            } else if (tab == 4) {
                // Root 页：Root 自动化引擎 + 启动自检，逐项实测、不写假结果。
                var rootLinksText by remember(activity.myPublishedLinks) { mutableStateOf(activity.myPublishedLinks.joinToString("\n")) }
                var rootComment by remember { mutableStateOf("") }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Root 自动化：全程手机本地跑，不用接电脑", style = MaterialTheme.typography.titleMedium)
                    Text("Root 就是开发者权限：有了它，软件自己在手机上跑系统命令（打开应用、读界面、点按屏幕），不用无障碍服务一直活着，也不用电脑。")
                    Spacer(Modifier.height(8.dp))
                    Text("Root 状态：${activity.rootStatus}")
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("快速设置向导（按顺序点）", style = MaterialTheme.typography.titleSmall)
                            val step1 = if (activity.shizukuInstalled) "已完成" else "未完成"
                            val step2 = if (activity.shizukuRunning) "已完成" else "未完成"
                            val step3 = if (activity.shizukuGranted) "已完成" else "未完成"
                            val step4 = if (activity.accessibilityOn) "已完成" else "未完成"
                            Text("第 1 步：安装 Shizuku —— $step1")
                            if (!activity.shizukuInstalled) {
                                Button(onClick = { activity.openShizukuDownload() }, modifier = Modifier.fillMaxWidth()) {
                                    Text("去下载 Shizuku")
                                }
                            }
                            Text("第 2 步：启动 Shizuku —— $step2")
                            if (!activity.shizukuRunning) {
                                Text("先去开发者选项打开“无线调试”，再打开 Shizuku，点“通过无线调试启动”，按提示配对。")
                                Button(onClick = { activity.openDeveloperOptions() }, modifier = Modifier.fillMaxWidth()) {
                                    Text("打开开发者选项")
                                }
                                Button(onClick = { activity.openShizukuApp() }, modifier = Modifier.fillMaxWidth()) {
                                    Text("打开 Shizuku")
                                }
                            }
                            Text("第 3 步：给本软件授权 —— $step3")
                            if (!activity.shizukuGranted) {
                                Button(onClick = { ShizukuHelper.requestPermission() }, modifier = Modifier.fillMaxWidth()) {
                                    Text("申请 Shizuku 授权")
                                }
                            }
                            Text("第 4 步：开无障碍（填中文评论要用） —— $step4")
                            if (!activity.accessibilityOn) {
                                Button(onClick = { PermissionHelper.openAccessibilitySettings(activity) }, modifier = Modifier.fillMaxWidth()) {
                                    Text("去开启无障碍")
                                }
                            }
                            if (activity.shizukuInstalled && activity.shizukuRunning && activity.shizukuGranted && activity.accessibilityOn) {
                                Text("设置完成，可以开始 Root 自动化。")
                            }
                            OutlinedButton(onClick = {
                                activity.refreshSetupState()
                                activity.runSelfCheck()
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text("重新检测")
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { activity.runSelfCheck() }, modifier = Modifier.fillMaxWidth()) {
                        Text("运行启动自检")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(activity.selfCheckText)
                    Spacer(Modifier.height(12.dp))
                    Text("Root 测试", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { activity.openDouyinByRoot() }) { Text("用 Root 打开抖音") }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Root 评论测试", style = MaterialTheme.typography.titleMedium)
                    Text("用 Root 引擎给已发布视频发评论：先打开链接、读界面确认、点评论入口、填评论、点发送，每一步都验证，失败写原因。")
                    OutlinedTextField(
                        value = rootComment,
                        onValueChange = { rootComment = it },
                        label = { Text("评论内容") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("链接用平台页保存的已发布视频链接（${activity.myPublishedLinks.size} 条），没有就先去平台页保存。")
                    Button(
                        onClick = { activity.startRootCommentTask(activity.myPublishedLinks, rootComment, 10) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("开始 Root 评论任务") }
                    Spacer(Modifier.height(8.dp))
                    Text(activity.taskStatus)
                    Text(activity.logText, modifier = Modifier.fillMaxWidth().height(140.dp).verticalScroll(rememberScrollState()))
                }
            } else {
                // 更新页：软件内更新，不用去网页下载、不用解压。
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("软件内更新", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("当前版本：v${UpdateChecker.currentVersionName(activity)}")
                    Text("新版发布后，点“检查更新”，有新版直接在软件内下载安装，不用去网页、不用解压 zip。")
                    Spacer(Modifier.height(8.dp))
                    Text("不用填 Token，直接点“检查更新”就行。")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { activity.checkUpdate() }) { Text("检查更新") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(activity.updateStatus)
                    if (activity.updateInfo != null) {
                        Spacer(Modifier.height(8.dp))
                        Text("发现新版：${activity.updateInfo!!.tag} ${activity.updateInfo!!.name}")
                        Button(onClick = { activity.downloadUpdate() }, modifier = Modifier.fillMaxWidth()) {
                            Text("下载并安装新版")
                        }
                    }
                }
            }
        }
    }
}
