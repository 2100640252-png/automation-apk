package com.automation.work

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AccessibilityScriptService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        TaskStore.appendLog(this, "无障碍脚本已连接。")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance == this) instance = null
        super.onDestroy()
    }

    fun runCommentFlow(link: String, text: String) {
        Thread {
            try {
                flow(link.trim(), text)
            } catch (e: Exception) {
                fail("执行异常：${e.message}")
            }
        }.start()
    }

    private fun log(message: String) {
        lastStatus = message
        TaskStore.appendLog(this, "无障碍脚本：$message")
    }

    private fun fail(message: String) {
        log("失败：$message")
    }

    private fun flow(link: String, text: String) {
        if (link.isBlank()) { fail("链接为空"); return }
        if (text.isBlank()) { fail("评论内容为空"); return }
        log("打开链接…")
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        if (!waitForTargetWindow()) { fail("链接打开后没有等到目标页面"); return }
        if (detectBlocker()) return

        log("寻找评论入口…")
        if (!openCommentArea()) { fail("没找到评论入口，也没有可用的评论点位"); return }
        Thread.sleep(1200)
        if (detectBlocker()) return

        log("寻找评论输入框…")
        val input = findEditable()
        if (input == null) { fail("没找到评论输入框"); return }
        clickNodeOrTap(input)
        Thread.sleep(800)
        val filled = fillText(input, text)
        if (!filled) { fail("评论没有填进输入框"); return }
        Thread.sleep(800)

        val baseline = countOutsideInput(text)
        log("寻找发布按钮…")
        if (!tapSend()) { fail("没找到发布按钮，也没有可用的发布点位"); return }
        Thread.sleep(2000)

        val inputStillHas = findEditable()?.text?.contains(text.take(12)) == true
        val appeared = countOutsideInput(text) > baseline
        if (!inputStillHas && appeared) {
            log("已验证：评论已发出。")
        } else if (!inputStillHas) {
            log("输入框已清空，但评论区没看到新评论，请人工确认。")
        } else {
            fail("发布点击后输入框里还有评论，发布没有生效")
        }
    }

    private fun waitForTargetWindow(): Boolean {
        repeat(20) {
            val root = rootInActiveWindow
            val pkg = root?.packageName?.toString() ?: ""
            if (root != null && pkg.isNotBlank() && pkg != packageName) return true
            Thread.sleep(500)
        }
        return false
    }

    private fun detectBlocker(): Boolean {
        val allText = collectText(rootInActiveWindow)
        val blockers = listOf("验证码", "滑块", "安全验证", "请先登录", "登录后")
        val hit = blockers.firstOrNull { allText.contains(it) }
        if (hit != null) {
            fail("页面出现“$hit”，已停止，请人工处理")
            return true
        }
        return false
    }

    private fun openCommentArea(): Boolean {
        val entry = findNode { node ->
            val label = labelOf(node)
            (label.contains("评论") || label.contains("comment", ignoreCase = true)) && isTappable(node)
        }
        if (entry != null) return clickNodeOrTap(entry)
        val point = ScriptStore.loadSteps(this).firstOrNull {
            it.type == ScriptStore.TYPE_TAP && it.name.contains("评论")
        }
        if (point != null) return tapFraction(point.x, point.y)
        return false
    }

    private fun tapSend(): Boolean {
        val send = findNode { node ->
            val label = labelOf(node)
            (label == "发送" || label == "发布" || label.contains("发送") || label.contains("发布")) && isTappable(node)
        }
        if (send != null) return clickNodeOrTap(send)
        val point = ScriptStore.loadSteps(this).firstOrNull {
            it.type == ScriptStore.TYPE_TAP && (it.name.contains("发送") || it.name.contains("发布"))
        }
        if (point != null) return tapFraction(point.x, point.y)
        return false
    }

    private fun fillText(input: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            Thread.sleep(500)
            if (findEditable()?.text?.contains(text.take(12)) == true) return true
        }
        return try {
            val clipboard = getSystemService(ClipboardManager::class.java) ?: return false
            clipboard.setPrimaryClip(ClipData.newPlainText("comment", text))
            input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            input.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            Thread.sleep(500)
            findEditable()?.text?.contains(text.take(12)) == true
        } catch (_: Exception) {
            false
        }
    }

    private fun findEditable(): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collect(rootInActiveWindow) { node ->
            if (node.isEditable || node.className?.toString()?.contains("EditText") == true) candidates.add(node)
        }
        return candidates.maxByOrNull { node ->
            val rect = Rect().also { node.getBoundsInScreen(it) }
            rect.bottom
        }
    }

    private fun countOutsideInput(text: String): Int {
        val probe = text.take(12)
        var count = 0
        collect(rootInActiveWindow) { node ->
            val editable = node.isEditable || node.className?.toString()?.contains("EditText") == true
            if (!editable && node.text?.contains(probe) == true) count++
        }
        return count
    }

    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        collect(rootInActiveWindow) { node -> if (found == null && predicate(node)) found = node }
        return found
    }

    private fun collect(node: AccessibilityNodeInfo?, visit: (AccessibilityNodeInfo) -> Unit) {
        if (node == null) return
        visit(node)
        for (i in 0 until node.childCount) collect(node.getChild(i), visit)
    }

    private fun collectText(node: AccessibilityNodeInfo?): String {
        val sb = StringBuilder()
        collect(node) { sb.append(labelOf(it)).append(' ') }
        return sb.toString()
    }

    private fun labelOf(node: AccessibilityNodeInfo): String =
        "${node.text ?: ""} ${node.contentDescription ?: ""} ${node.hintText ?: ""}"

    private fun isTappable(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable && current.isEnabled) return true
            current = current.parent
        }
        val rect = Rect().also { node.getBoundsInScreen(it) }
        return rect.width() > 0 && rect.height() > 0
    }

    private fun clickNodeOrTap(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable && current.isEnabled && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current.parent
        }
        val rect = Rect().also { node.getBoundsInScreen(it) }
        if (rect.width() <= 0 || rect.height() <= 0) return false
        return tapPx(rect.centerX().toFloat(), rect.centerY().toFloat())
    }

    private fun tapFraction(x: Float, y: Float): Boolean {
        val dm = resources.displayMetrics
        return tapPx(x * dm.widthPixels, y * dm.heightPixels)
    }

    private fun tapPx(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        val latch = CountDownLatch(1)
        var completed = false
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { completed = true; latch.countDown() }
            override fun onCancelled(gestureDescription: GestureDescription?) { latch.countDown() }
        }, null)
        if (!dispatched) return false
        latch.await(2, TimeUnit.SECONDS)
        return completed
    }

    companion object {
        @Volatile var instance: AccessibilityScriptService? = null
            private set
        @Volatile var lastStatus: String = "无障碍脚本未连接"
            private set
    }
}
