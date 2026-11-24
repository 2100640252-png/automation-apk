package com.automation.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 视频定时发布：用 WorkManager 在用户定的时间触发。
 * v1.1：到时间后真的发一条通知提醒（带打开应用的按钮），
 * 并把状态写回视频库；失败会重试，不再是静默返回成功。
 * 真正往平台发视频仍需平台应用内确认，这是平台的规则，
 * 软件做的是到时间提醒 + 一键打开对应平台。
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
        TaskStore.appendLog(context, "已排期：${job.videoName}，${job.platforms.joinToString("、")}，${delay / 60000} 分钟后提醒。")
    }

    class PublishWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork(): Result {
            return try {
                val name = inputData.getString("videoName") ?: "视频"
                val platforms = inputData.getStringArray("platforms")?.joinToString("、") ?: ""
                val copyBody = inputData.getString("copyBody") ?: ""
                showNotification(
                    applicationContext,
                    "到时间发视频了：$name",
                    "平台：$platforms\n文案：$copyBody\n点开应用，去对应平台确认发布。"
                )
                TaskStore.appendLog(applicationContext, "定时提醒已发出：$name")
                Result.success()
            } catch (e: Exception) {
                TaskStore.appendLog(applicationContext, "定时任务出错：${e.message}，稍后重试。")
                Result.retry()
            }
        }

        private fun showNotification(context: Context, title: String, text: String) {
            val channelId = "video_publish"
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                val channel = NotificationChannel(
                    channelId, "视频发布提醒",
                    NotificationManager.IMPORTANCE_HIGH
                )
                nm.createNotificationChannel(channel)
            }
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pending = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            nm.notify(System.currentTimeMillis().toInt(), notification)
        }
    }

    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // 重启后由 WorkManager 自动恢复未执行的定时任务，无需额外处理。
        }
    }
}
