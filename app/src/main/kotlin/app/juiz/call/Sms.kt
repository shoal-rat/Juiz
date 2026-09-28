package app.juiz.call

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telecom.Call
import android.telephony.SmsManager
import app.juiz.JuizApp
import app.juiz.core.conversation.ConversationEngine
import app.juiz.core.conversation.EngineOutput
import app.juiz.core.model.CallerInfo
import app.juiz.core.model.Channel
import app.juiz.core.model.ContactTier
import app.juiz.core.policy.Disclosure
import app.juiz.core.util.normalizeNumber
import app.juiz.platform.Contacts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.put

/**
 * L0 短信代办：拒接来电后发一条披露身份的短信，并为该号码开一个有时限的会话窗口。
 * 只回复窗口内、未超次数的号码；不回复短号码和服务号，避免被利用刷短信或形成回复循环。
 */
object SmsScreening {
    private val engines = mutableMapOf<String, ConversationEngine>()
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun canSend(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    private fun looksPersonal(number: String): Boolean {
        val n = normalizeNumber(number)
        return n.length >= 11 && !n.startsWith("106") && !n.startsWith("95") && !n.startsWith("10086") && !n.startsWith("10010")
    }

    fun send(ctx: Context, to: String, text: String) {
        val sms = ctx.getSystemService(SmsManager::class.java)
        val parts = sms.divideMessage(text)
        if (parts.size > 1) sms.sendMultipartTextMessage(to, null, parts, null, null) else sms.sendTextMessage(to, null, text, null, null)
    }

    suspend fun screenCall(ctx: Context, call: Call, caller: CallerInfo) {
        val core = JuizApp.core
        val greeting = Disclosure.smsGreeting(core.settings.ownerProfile())
        val personal = looksPersonal(caller.number) && caller.tier != ContactTier.SPAM
        kotlinx.coroutines.withContext(Dispatchers.Main) {
            if (personal && !canSend(ctx)) call.reject(true, greeting) else call.reject(false, null)
        }
        if (!personal) return
        if (canSend(ctx)) send(ctx, caller.number, greeting)
        val engine = core.conversations.start(caller, Channel.SMS, "L0-sms", runCatching { core.chatModel() }.getOrElse { return })
        engine.opening(greeting)
        val hours = core.settings.behavior().smsSessionHours
        val now = core.clock.millis()
        core.db.juizQueries.upsertSmsSession(normalizeNumber(caller.number), engine.context.conversationId, now, now + hours * 3_600_000L)
        core.archive.append("sms.sent", engine.context.conversationId) {
            put("to", caller.number)
            put("kind", "greeting")
        }
        lock.withLock { engines[normalizeNumber(caller.number)] = engine }
    }

    fun onSms(ctx: Context, from: String, body: String) = scope.launch {
        val core = JuizApp.core
        val key = normalizeNumber(from)
        val session = core.db.juizQueries.selectSmsSession(key).executeAsOneOrNull() ?: return@launch
        val now = core.clock.millis()
        if (session.closed == 1L || now > session.expires_at) return@launch
        core.archive.append("sms.received", session.conversation_id) { put("from", from); put("chars", body.length) }
        val max = core.settings.behavior().smsMaxReplies
        lock.withLock {
            if (session.replies >= max) {
                if (session.replies == max.toLong()) {
                    send(ctx, from, "您的信息都已转告${core.settings.ownerProfile().ownerName}本人，稍后由本人回复您。")
                    core.db.juizQueries.incrementSmsReplies(key)
                }
                core.db.juizQueries.closeSmsSession(key)
                return@withLock
            }
            val caller = Contacts.resolve(ctx, from)
            val engine = engines[key] ?: core.conversations.resume(session.conversation_id, caller, Channel.SMS, core.chatModel()).also { engines[key] = it }
            val reply = StringBuilder()
            engine.respond(body) { o ->
                when (o) {
                    is EngineOutput.Speech -> reply.append(o.text)
                    is EngineOutput.Escalation -> Notifications.escalation(ctx, null, o.signal)
                    else -> Unit
                }
            }
            if (reply.isNotBlank() && canSend(ctx)) {
                send(ctx, from, reply.toString().take(300))
                core.db.juizQueries.incrementSmsReplies(key)
                core.archive.append("sms.sent", session.conversation_id) { put("to", from); put("chars", reply.length) }
            }
            if (engine.context.ended) core.db.juizQueries.closeSmsSession(key)
        }
    }
}

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        msgs.groupBy { it.originatingAddress }.forEach { (from, parts) ->
            if (from != null) SmsScreening.onSms(context.applicationContext, from, parts.joinToString("") { it.messageBody.orEmpty() })
        }
    }
}
