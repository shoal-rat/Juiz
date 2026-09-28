package app.juiz.bridge

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.juiz.JuizApp
import kotlinx.serialization.json.put

/**
 * 把任务交给手机上的 ChatGPT App（Work 模式）。
 * 第一层：系统分享，把任务卡带进 ChatGPT App 输入框——正规途径。
 * 第二层（可选、默认关闭、实验）：无障碍辅助点按，只在你刚刚发起交接后的 60 秒内、只在 ChatGPT App 里，
 *   帮你切到 Work 模式并按发送；不读取、不抓取任何回答。ChatGPT App 界面会变，按钮按文字/说明查找。
 */
object ChatGptBridge {
    const val PACKAGE = "com.openai.chatgpt"
    const val KEY_ASSIST = "chatgpt_assist_enabled"

    data class Pending(val text: String, val at: Long)

    @Volatile var pending: Pending? = null
        private set

    fun installed(ctx: Context): Boolean = runCatching { ctx.packageManager.getPackageInfo(PACKAGE, 0); true }.getOrDefault(false)

    fun assistEnabled(): Boolean = JuizApp.core.settings.raw(KEY_ASSIST) == "1"

    fun setAssist(on: Boolean) = JuizApp.core.settings.putRaw(KEY_ASSIST, if (on) "1" else "0")

    /** 无障碍服务是否已在系统设置里打开。 */
    fun serviceOn(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return enabled.contains(ComponentName(ctx, ChatGptAssistService::class.java).flattenToShortString()) ||
            enabled.contains(ComponentName(ctx, ChatGptAssistService::class.java).flattenToString())
    }

    /** 返回 true 表示已直接打开 ChatGPT App；false 表示走了系统分享面板。 */
    fun send(ctx: Context, taskId: String, text: String): Boolean {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            .putExtra(Intent.EXTRA_SUBJECT, "Juiz 任务卡 $taskId").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (installed(ctx)) {
            if (assistEnabled() && serviceOn(ctx)) pending = Pending(text, System.currentTimeMillis())
            ctx.startActivity(intent.setPackage(PACKAGE))
            true
        } else {
            ctx.startActivity(Intent.createChooser(intent, "交给 ChatGPT").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            false
        }
    }

    internal fun consume(): Pending? {
        val p = pending ?: return null
        if (System.currentTimeMillis() - p.at > 60_000) { pending = null; return null }
        return p
    }

    internal fun done() { pending = null }
}

/**
 * 实验：只监听 ChatGPT App（见 res/xml/chatgpt_assist.xml 的 packageNames），
 * 只在有待提交的任务卡时动作：若输入框里确实是 Juiz 任务卡，先尝试切到 Work，再点发送。
 */
class ChatGptAssistService : AccessibilityService() {
    private val workLabels = listOf("Work", "工作", "工作模式")
    private val sendLabels = listOf("Send", "Send message", "Send prompt", "发送", "发送消息")
    private var triedWork = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != ChatGptBridge.PACKAGE) return
        val p = ChatGptBridge.consume() ?: run { triedWork = false; return }
        val root = rootInActiveWindow ?: return
        // 只有输入框里是 Juiz 任务卡时才动作，避免误点你自己的对话
        if (!containsText(root, "【Juiz 任务卡】")) return
        if (!triedWork) {
            triedWork = true
            findClickable(root, workLabels)?.let { if (!it.isSelected) it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            return
        }
        findClickable(root, sendLabels)?.let {
            if (it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                ChatGptBridge.done()
                triedWork = false
                JuizApp.core.archive.append("chatgpt.assist.sent", null) { put("chars", p.text.length) }
            }
        }
    }

    override fun onInterrupt() = Unit

    private fun containsText(n: AccessibilityNodeInfo, s: String): Boolean {
        if (n.text?.contains(s) == true) return true
        for (i in 0 until n.childCount) n.getChild(i)?.let { if (containsText(it, s)) return true }
        return false
    }

    private fun findClickable(n: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
        val label = (n.contentDescription ?: n.text)?.toString()?.trim()
        if (label != null && labels.any { it.equals(label, ignoreCase = true) } && n.isEnabled) {
            var c: AccessibilityNodeInfo? = n
            while (c != null && !c.isClickable) c = c.parent
            if (c != null) return c
        }
        for (i in 0 until n.childCount) n.getChild(i)?.let { child -> findClickable(child, labels)?.let { return it } }
        return null
    }
}
