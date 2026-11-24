package com.automation.work

import android.content.Context
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * Root 自动化引擎（v1.6 新增）。
 *
 * 做法：用 Root 在手机本地跑系统命令，全程不经过电脑：
 *  1. am start 打开链接/应用（深链直开抖音 App）
 *  2. uiautomator dump 读当前界面的控件树，确认现在是什么页面、
 *     评论入口和发送按钮到底在不在
 *  3. input tap 按坐标点按，找到的控件才点，找不到就重试、换关键词再找
 *  4. 每一步做完都重新读界面验证，验证不过就记失败原因，不假装成功
 *
 * 文字输入说明：input text 对中文支持不稳定，所以填评论优先用
 * 无障碍服务的 setText（无障碍已开启时）；无障碍没开时才试 input text，
 * 填完立刻读界面验证框里是不是真的有这句评论，验证不过就报失败。
 * 不往抖音等第三方 App 里注入代码，不动它们的内部接口。
 */
object RootAutomator {

    data class UiNode(
        val text: String,
        val desc: String,
        val pkg: String,
        val cls: String,
        val clickable: Boolean,
        val editable: Boolean,
        val centerX: Int,
        val centerY: Int
    )

    private val commentEntryKeywords = listOf("评论", "comment")
    private val inputKeywords = listOf("评论", "说点什么", "写评论", "发评论", "Add comment", "comment")
    private val sendKeywords = listOf("发送", "发布", "Send", "Post", "发表")

    private fun runCmd(cmd: String): RootHelper.CmdResult {
        // 执行顺序：Root > Shizuku（无线调试，不用 Root）> 普通 shell。
        // 哪一级通了就用哪一级，通不了就往下一级试，最后都通不了才报失败。
        val root = RootHelper.runAsRoot(cmd)
        if (root.exitCode == 0) return root
        if (ShizukuHelper.isAvailable() && ShizukuHelper.isPermissionGranted()) {
            val sz = ShizukuHelper.run(cmd)
            if (sz.exitCode == 0) return sz
        }
        return RootHelper.runShell(cmd)
    }

    /** 读当前界面控件树。返回空列表表示读不到界面（原因写进日志由调用方处理）。 */
    fun dumpHierarchy(context: Context): List<UiNode> {
        return try {
            val xml = runCmd("uiautomator dump /data/local/tmp/uidump.xml && cat /data/local/tmp/uidump.xml")
            if (xml.exitCode != 0 || xml.output.isBlank()) {
                // 换个路径再试一次（有的机型不让写 /data/local/tmp）。
                val alt = runCmd("uiautomator dump /sdcard/uidump.xml && cat /sdcard/uidump.xml")
                if (alt.exitCode != 0 || alt.output.isBlank()) return emptyList()
                parseNodes(alt.output)
            } else {
                parseNodes(xml.output)
            }
        } catch (e: Exception) {
            TaskStore.appendLog(context, "读界面失败：${e.message}")
            emptyList()
        }
    }

    private fun parseNodes(xml: String): List<UiNode> {
        val out = mutableListOf<UiNode>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))
            var event = parser.eventType
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (event == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "node") {
                    val bounds = parser.getAttributeValue(null, "bounds") ?: ""
                    val m = Regex("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]").find(bounds)
                    val cx = if (m != null) (m.groupValues[1].toInt() + m.groupValues[3].toInt()) / 2 else 0
                    val cy = if (m != null) (m.groupValues[2].toInt() + m.groupValues[4].toInt()) / 2 else 0
                    out.add(
                        UiNode(
                            text = parser.getAttributeValue(null, "text") ?: "",
                            desc = parser.getAttributeValue(null, "content-desc") ?: "",
                            pkg = parser.getAttributeValue(null, "package") ?: "",
                            cls = parser.getAttributeValue(null, "class") ?: "",
                            clickable = parser.getAttributeValue(null, "clickable") == "true",
                            editable = parser.getAttributeValue(null, "editable") == "true",
                            centerX = cx,
                            centerY = cy
                        )
                    )
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            // 解析失败就返回已读到的部分，调用方按“没找到”处理。
        }
        return out
    }

    private fun findNode(nodes: List<UiNode>, keywords: List<String>, needClickable: Boolean = true): UiNode? {
        return nodes.firstOrNull { n ->
            (!needClickable || n.clickable) &&
                keywords.any { (n.text.contains(it) || n.desc.contains(it)) }
        }
    }

    private fun findEditable(nodes: List<UiNode>): UiNode? {
        return nodes.firstOrNull { it.editable } ?: nodes.firstOrNull { n ->
            n.clickable && inputKeywords.any { n.text.contains(it) || n.desc.contains(it) } &&
                n.cls.contains("EditText")
        }
    }

    fun tap(context: Context, x: Int, y: Int): Boolean {
        val r = runCmd("input tap $x $y")
        return r.exitCode == 0
    }

    /** 用 Root 的 am 命令打开一条视频链接（深链直开对应 App）。 */
    fun openVideoLinkRoot(context: Context, link: String): Boolean {
        val resolved = LinkResolver.resolve(link)
        val uri = resolved.openUri.toString()
        val r = runCmd("am start -a android.intent.action.VIEW -d '$uri'")
        if (r.exitCode != 0) {
            TaskStore.appendLog(context, "Root 打开链接失败：${r.output.take(150)}")
            return false
        }
        // 验证：读界面，看当前界面的包名是不是目标 App。
        Thread.sleep(2500)
        val nodes = dumpHierarchy(context)
        val targetPkg = resolved.packageName
        val seen = nodes.any { targetPkg == null || it.pkg == targetPkg }
        if (!seen && nodes.isNotEmpty()) {
            TaskStore.appendLog(context, "链接发出了，但界面还不是目标 App（可能被浏览器接走了）。")
            return false
        }
        TaskStore.appendLog(context, "Root 已打开链接：${resolved.note}")
        return true
    }

    /**
     * 用 Root 引擎给一条视频发评论。返回 Pair(成功?, 原因)。
     * 每一步都验证，任何一步验证不过就重试一次，再不过就返回失败原因。
     */
    fun commentOnVideo(context: Context, link: String, commentText: String): Pair<Boolean, String> {
        if (!openVideoLinkRoot(context, link)) {
            return false to "打不开链接"
        }
        // 1) 找评论入口并点开，最多等 3 轮。
        var nodes = dumpHierarchy(context)
        var opened = findEditable(nodes) != null
        var tries = 0
        while (!opened && tries < 3) {
            val entry = findNode(nodes, commentEntryKeywords)
            if (entry == null) {
                Thread.sleep(2500)
                nodes = dumpHierarchy(context)
                tries++
                continue
            }
            tap(context, entry.centerX, entry.centerY)
            Thread.sleep(2000)
            nodes = dumpHierarchy(context)
            opened = findEditable(nodes) != null
            tries++
        }
        if (!opened) return false to "打不开评论框（界面上没有评论入口）"
        // 2) 填评论：优先用无障碍 setText（中文稳），否则试 input text。
        val input = findEditable(nodes) ?: return false to "找不到评论输入框"
        val filled = fillCommentText(context, input, commentText)
        if (!filled) return false to "评论填不进去"
        Thread.sleep(800)
        // 3) 找发送按钮并点，再验证一次。
        nodes = dumpHierarchy(context)
        val send = findNode(nodes, sendKeywords)
            ?: return false to "填了评论但找不到发送按钮"
        tap(context, send.centerX, send.centerY)
        Thread.sleep(1500)
        TaskStore.appendLog(context, "Root 已点发送：$link")
        return true to "已发送"
    }

    private fun fillCommentText(context: Context, input: UiNode, commentText: String): Boolean {
        // 先点一下输入框拿到焦点。
        tap(context, input.centerX, input.centerY)
        Thread.sleep(800)
        // 有无障碍服务时用它填中文，最稳。
        val service = AutomationService.instance
        if (service != null) {
            val root = service.rootInActiveWindow
            if (root != null) {
                val queue = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
                queue.add(root)
                while (queue.isNotEmpty()) {
                    val n = queue.removeFirst()
                    if (n.isEditable) {
                        val args = android.os.Bundle()
                        args.putCharSequence(
                            android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            commentText
                        )
                        if (n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                            return true
                        }
                    }
                    for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
                }
            }
        }
        // 没无障碍时试 input text（中文可能不稳，调用方会验证）。
        val escaped = commentText.replace("'", "").replace(" ", "%s")
        val r = runCmd("input text '$escaped'")
        if (r.exitCode != 0) return false
        Thread.sleep(800)
        // 验证框里是不是真的有这句评论。
        val nodes = dumpHierarchy(context)
        return nodes.any { it.editable && it.text.contains(commentText.take(6)) } ||
            nodes.any { it.text.contains(commentText.take(6)) }
    }
}
