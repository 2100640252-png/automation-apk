package com.automation.work

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
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
    var permissionStatus by mutableStateOf(listOf<PermissionHelper.PermissionStatus>())
    var logText by mutableStateOf("暂无运行记录。")
    var taskStatus by mutableStateOf("未开始")
    var updateStatus by mutableStateOf("")
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
        githubToken = TaskStore.loadGithubToken(this)
        refreshVideos()
        refreshPermissions()
        refreshLog()
        // 启动就主动索取权限：这是 v1.0 打开没反应的直接原因，v1.1 改成一打开就申请。
        permissionLauncher.launch(PermissionHelper.runtimePermissions.toTypedArray())
        setContent { AutomationApp(this) }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        refreshVideos()
        refreshLog()
        taskStatus = AutomationService.lastStatus
    }

    fun refreshVideos() {
        videos = TaskStore.loadVideos(this)
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
                                activity.scheduleVideo(
                                    video, selectedPlatforms.toList(),
                                    minutesAfter.toIntOrNull() ?: 10, copy
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("定时发布这个视频") }
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
            } else {
                // 更新页：软件内更新，不用去网页下载、不用解压。
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("软件内更新", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("当前版本：v${UpdateChecker.currentVersionName(activity)}")
                    Text("新版发布后，点“检查更新”，有新版直接在软件内下载安装，不用去网页、不用解压 zip。")
                    Spacer(Modifier.height(8.dp))
                    Text("仓库是私有的，第一次需要填一次 GitHub 只读 Token：去 github.com/settings/tokens 建一个 fine-grained Token，只勾 automation-apk 的 Contents 只读，粘贴到下面，保存一次就行，Token 只存在本机。")
                    OutlinedTextField(
                        value = activity.githubToken,
                        onValueChange = { activity.githubToken = it },
                        label = { Text("GitHub 只读 Token（只存本机）") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { activity.saveToken(activity.githubToken) }) { Text("保存 Token") }
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
