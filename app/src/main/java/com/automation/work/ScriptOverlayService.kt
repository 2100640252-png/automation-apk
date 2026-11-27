package com.automation.work

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class ScriptOverlayService : Service() {
    private var rootView: View? = null
    private var statusView: TextView? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        showMini()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        removeRoot()
        super.onDestroy()
    }

    private fun params(w: Int, h: Int, focusable: Boolean = false): WindowManager.LayoutParams {
        val flags = if (focusable) {
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }
        return WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        )
    }

    private fun removeRoot() {
        val root = rootView ?: return
        rootView = null
        statusView = null
        try {
            getSystemService(WindowManager::class.java)?.removeView(root)
        } catch (_: Exception) {
        }
    }

    private fun showMini() {
        removeRoot()
        val wm = getSystemService(WindowManager::class.java) ?: return
        val mini = Button(this).apply {
            text = "脚本"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(240, 15, 23, 42))
            setOnClickListener { showPanel() }
        }
        val p = params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        mini.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; startX = p.x; startY = p.y; false
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = startX - (event.rawX - downX).toInt()
                    p.y = startY + (event.rawY - downY).toInt()
                    try { wm.updateViewLayout(mini, p) } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }
        try {
            wm.addView(mini, p)
            rootView = mini
            TaskStore.appendLog(this, "悬浮脚本已打开：右侧脚本按钮可拖动，点它展开。")
        } catch (e: Exception) {
            TaskStore.appendLog(this, "悬浮脚本打开失败：${e.message}")
        }
    }

    private fun showPanel() {
        removeRoot()
        val wm = getSystemService(WindowManager::class.java) ?: return
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(242, 15, 23, 42))
            setPadding(22, 16, 22, 16)
        }
        val title = TextView(this).apply {
            text = "悬浮脚本"
            setTextColor(Color.WHITE)
            textSize = 17f
        }
        val status = TextView(this).apply {
            setTextColor(Color.rgb(191, 219, 254))
            textSize = 13f
        }
        statusView = status
        fun row(vararg buttons: Button): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                buttons.forEach { addView(it, LinearLayout.LayoutParams(0, WindowManager.LayoutParams.WRAP_CONTENT, 1f)) }
            }
        }
        fun btn(text: String, action: () -> Unit): Button = Button(this).apply {
            this.text = text
            setOnClickListener { action() }
        }
        panel.addView(title)
        panel.addView(status)
        panel.addView(row(
            btn("捕捉点位") { showCapture() },
            btn("运行脚本") { runScript() },
            btn("停止") { running = false; setStatus("已停止") }
        ))
        panel.addView(row(
            btn("最小化") { showMini() },
            btn("刷新") { refreshStatus() },
            btn("关闭") { stopSelf() }
        ))
        val p = params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, focusable = true).apply {
            gravity = Gravity.BOTTOM
        }
        try {
            wm.addView(panel, p)
            rootView = panel
            refreshStatus()
        } catch (e: Exception) {
            TaskStore.appendLog(this, "悬浮脚本面板打开失败：${e.message}")
        }
    }

    private fun refreshStatus() {
        val steps = ScriptStore.loadSteps(this)
        val names = steps.mapIndexed { i, s -> "${i + 1}.${s.name}" }.joinToString("  ")
        setStatus("输入：${InputEngine.backendName()}\n点位：${steps.size} 个 $names")
    }

    private fun setStatus(text: String) {
        statusView?.text = text
        TaskStore.appendLog(this, "悬浮脚本：$text")
    }

    private fun showCapture() {
        removeRoot()
        val wm = getSystemService(WindowManager::class.java) ?: return
        val capture = View(this).apply {
            setBackgroundColor(Color.argb(70, 0, 0, 0))
        }
        var downX = 0f
        var downY = 0f
        var downTime = 0L
        capture.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x; downY = event.y; downTime = System.currentTimeMillis(); true
                }
                MotionEvent.ACTION_UP -> {
                    val w = capture.width.coerceAtLeast(1)
                    val h = capture.height.coerceAtLeast(1)
                    val dx = abs(event.x - downX)
                    val dy = abs(event.y - downY)
                    val index = ScriptStore.loadSteps(this).size + 1
                    val step = if (dx > 60f || dy > 60f) {
                        ScriptStore.Step(
                            type = ScriptStore.TYPE_SWIPE,
                            name = "滑动$index",
                            x = downX / w,
                            y = downY / h,
                            x2 = event.x / w,
                            y2 = event.y / h,
                            durationMs = (System.currentTimeMillis() - downTime).toInt().coerceIn(120, 1200)
                        )
                    } else {
                        ScriptStore.Step(
                            type = ScriptStore.TYPE_TAP,
                            name = "点位$index",
                            x = event.x / w,
                            y = event.y / h
                        )
                    }
                    ScriptStore.addStep(this, step)
                    Toast.makeText(this, "已捕捉：${step.name} X ${(step.x * 100).toInt()}% Y ${(step.y * 100).toInt()}%", Toast.LENGTH_SHORT).show()
                    TaskStore.appendLog(this, "悬浮脚本已捕捉：${step.name} ${step.type} X ${(step.x * 100).toInt()}% Y ${(step.y * 100).toInt()}%")
                    showPanel()
                    true
                }
                else -> true
            }
        }
        try {
            wm.addView(capture, params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT))
            rootView = capture
            Toast.makeText(this, "点一下要记的位置，滑动就拖动", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            TaskStore.appendLog(this, "捕捉层打开失败：${e.message}")
            showPanel()
        }
    }

    private fun runScript() {
        val steps = ScriptStore.loadSteps(this)
        if (steps.isEmpty()) {
            setStatus("没有点位，先捕捉点位")
            return
        }
        running = true
        showMini()
        Thread {
            val dm = resources.displayMetrics
            for ((i, step) in steps.withIndex()) {
                if (!running) break
                val ok = when (step.type) {
                    ScriptStore.TYPE_TAP -> InputEngine.tap((step.x * dm.widthPixels).toInt(), (step.y * dm.heightPixels).toInt())
                    ScriptStore.TYPE_SWIPE -> InputEngine.swipe(
                        (step.x * dm.widthPixels).toInt(), (step.y * dm.heightPixels).toInt(),
                        (step.x2 * dm.widthPixels).toInt(), (step.y2 * dm.heightPixels).toInt(), step.durationMs
                    )
                    ScriptStore.TYPE_WAIT -> { Thread.sleep(step.durationMs.toLong().coerceAtLeast(100)); true }
                    ScriptStore.TYPE_PASTE -> InputEngine.paste(this, step.text.ifBlank { ScriptStore.loadText(this) })
                    else -> false
                }
                TaskStore.appendLog(this, "脚本第 ${i + 1} 步 ${step.name}：${if (ok) "已执行" else "失败"}；${InputEngine.lastResult}")
                if (!ok) break
                Thread.sleep(450)
            }
            running = false
            TaskStore.appendLog(this, "悬浮脚本运行结束")
        }.start()
    }

    companion object {
        const val ACTION_STOP = "com.automation.work.STOP_SCRIPT_OVERLAY"
    }
}
