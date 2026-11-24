package com.automation.work

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍屏幕控制服务。
 *
 * 只在用户主动开始任务、且已在设置里手动开启本服务后才工作。
 * 框架只做三件事：
 *  1. 用 Intent 打开用户填的视频链接（打开对应平台应用）
 *  2. 等待页面加载，在无障碍树里查找评论框
 *  3. 把用户自定义的评论文本填进去，由用户确认后发送
 *
 * 不读取密码、不上传数据、不在后台偷跑任务。
 * 如需全自动发送，请把下面的 TODO 按平台的界面适配后，
 * 再加一个明确的“开始 / 停止”开关和发送间隔控制。
 */
class AutomationService : AccessibilityService() {

    companion object {
        var instance: AutomationService? = null
            private set

        fun openVideoLink(context: android.content.Context, link: String) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // TODO: 在这里根据当前窗口查找评论输入框（按 text / viewId 匹配），
        // 找到后填入 CommentTask.commentText，并等待用户确认发送。
        // 不同平台的界面经常变，务必按当前版本的界面做适配和测试。
    }

    override fun onInterrupt() {
        // 任务被打断时停止当前操作
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
