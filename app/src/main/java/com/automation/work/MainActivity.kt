package com.automation.work

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    private val pickVideo = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> /* 把 uri 存进视频库，见 VideoJob */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AutomationApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationApp() {
    var tab by remember { mutableStateOf(0) } // 0 = 视频版，1 = 评论版
    var copyText by remember { mutableStateOf("") }
    var commentText by remember { mutableStateOf("") }
    var interval by remember { mutableStateOf("10") }
    val links = remember { mutableStateListOf<String>().apply { repeat(10) { add("") } } }
    var taskStatus by remember { mutableStateOf("未开始") }
    val parsed = remember(copyText) { if (copyText.isBlank()) null else CopyParser.parse(copyText) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("自动化工作") }) }
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("视频版") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("评论版") })
            }
            Spacer(Modifier.height(16.dp))

            if (tab == 0) {
                // 视频版：存视频、定时间、识别文案
                Text("视频版：存视频，定时间按时发", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = copyText,
                    onValueChange = { copyText = it },
                    label = { Text("粘贴文案（正文 + #话题 + 声明）") },
                    modifier = Modifier.fillMaxWidth().height(140.dp)
                )
                Spacer(Modifier.height(8.dp))
                parsed?.let {
                    Text("识别结果：正文：${it.body}")
                    Text("话题：${it.topics.joinToString(" ") { t -> "#$t" }}")
                    if (it.statement.isNotEmpty()) Text("声明：${it.statement}")
                }
                Spacer(Modifier.height(8.dp))
                Text("平台：抖音 / 快手 / 小红书 / 视频号 / 哔哩哔哩 / TikTok / YouTube / 微博（在详情里勾选）")
                Text("发布时间：在视频详情里选择，到时间由 WorkManager 触发提醒。")
            } else {
                // 评论版：10 个空白链接槽 + 自定义评论
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
                Button(
                    onClick = {
                        val task = CommentTask.fromLinks(
                            links.toList(), commentText,
                            interval.toIntOrNull() ?: 10
                        )
                        taskStatus = "已创建任务：${task.items.size} 条，开始按顺序发布"
                        // 实际执行：逐条打开链接 -> AutomationService 辅助填评论 -> 间隔等待
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("创建并开始任务") }
                Spacer(Modifier.height(8.dp))
                Text(taskStatus)
            }
        }
    }
}
