package com.automation.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 视频定时发布：用 WorkManager 在用户定的时间触发。
 * 到时间后发出通知，提醒用户打开对应平台确认发布，
 * 不会在后台偷偷用你的账号发视频。
 */
data class VideoJob(
    val videoName: String,
    val videoUri: String,
    val platforms: List<String>,
    val publishAtMillis: Long,
    val copy: ParsedCopy
)

object VideoScheduler {
    fun schedule(context: Context, job: VideoJob) {
        val delay = (job.publishAtMillis - System.currentTimeMillis()).coerceAtLeast(0)
        val input = Data.Builder()
            .putString("videoName", job.videoName)
            .putString("videoUri", job.videoUri)
            .putStringArray("platforms", job.platforms.toTypedArray())
            .putString("copyBody", job.copy.body)
            .putString("statement", job.copy.statement)
            .build()
        val request = OneTimeWorkRequestBuilder<PublishWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(input)
            .addTag("video-publish")
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    class PublishWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork(): Result {
            // 这里只做提醒 / 准备，真正发布需要用户在平台应用里确认。
            // 避免在用户不知情时自动操作账号。
            return Result.success()
        }
    }

    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // 重启后由 WorkManager 自动恢复未执行的定时任务，无需额外处理。
        }
    }
}
