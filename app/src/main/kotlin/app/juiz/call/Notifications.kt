package app.juiz.call

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import app.juiz.JuizApp
import app.juiz.R
import app.juiz.core.policy.EscalationSignal
import app.juiz.core.policy.Urgency
import app.juiz.ui.InCallActivity
import app.juiz.ui.MainActivity

object Notifications {
    const val ID_CALL = 1001
    const val ID_ESCALATION = 1002
    const val ID_DIGEST = 1003
    const val ID_TASKS = 1004

    private fun pi(ctx: Context, action: String, callId: String?, req: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, req,
            Intent(ctx, CallActionReceiver::class.java).setAction(action).putExtra("call", callId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun inCallIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun incoming(ctx: Context, ui: CallUi): Notification {
        val person = Person.Builder().setName(ui.caller.displayName ?: ui.caller.number).setImportant(true).build()
        val b = Notification.Builder(ctx, JuizApp.CH_INCOMING)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle(ui.caller.displayName ?: ui.caller.number)
            .setContentText(ui.decision?.let { "来电 · ${it.action.zh}" + (it.delaySeconds.takeIf { d -> d > 0 }?.let { d -> "（${d} 秒后）" } ?: "") } ?: "来电")
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setFullScreenIntent(inCallIntent(ctx), true)
            .setContentIntent(inCallIntent(ctx))
        if (Build.VERSION.SDK_INT >= 31) {
            // 带全屏意图的来电 CallStyle 是系统允许的；额外加一个「Juiz 代接」
            b.setStyle(Notification.CallStyle.forIncomingCall(person, pi(ctx, CallActionReceiver.DECLINE, ui.id, 2), pi(ctx, CallActionReceiver.ANSWER, ui.id, 1)))
            b.addAction(Notification.Action.Builder(null, "Juiz 代接", pi(ctx, CallActionReceiver.JUIZ, ui.id, 6)).build())
        } else {
            b.addAction(Notification.Action.Builder(null, "拒接", pi(ctx, CallActionReceiver.DECLINE, ui.id, 2)).build())
            b.addAction(Notification.Action.Builder(null, "接听", pi(ctx, CallActionReceiver.ANSWER, ui.id, 1)).build())
        }
        return b.build()
    }

    /**
     * 通话中的常驻通知。CallStyle 只允许前台服务使用（否则系统会直接抛异常），
     * 所以只有 callStyle=true（已进入前台）时才用它，其余情况用普通样式 + 挂断按钮。
     */
    fun ongoing(ctx: Context, ui: CallUi, callStyle: Boolean = false): Notification {
        val person = Person.Builder().setName(ui.caller.displayName ?: ui.caller.number).build()
        val b = Notification.Builder(ctx, JuizApp.CH_ONGOING)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle(ui.caller.displayName ?: ui.caller.number)
            .setContentText(ui.stateLabel)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(inCallIntent(ctx))
        if (callStyle && Build.VERSION.SDK_INT >= 31) {
            b.setStyle(Notification.CallStyle.forOngoingCall(person, pi(ctx, CallActionReceiver.HANGUP, ui.id, 3)))
        } else {
            b.addAction(Notification.Action.Builder(null, "挂断", pi(ctx, CallActionReceiver.HANGUP, ui.id, 3)).build())
        }
        if (ui.aiMode == AiMode.AI_VOICE) {
            b.addAction(Notification.Action.Builder(null, "接管", pi(ctx, CallActionReceiver.TAKEOVER, ui.id, 4)).build())
        }
        return b.build()
    }

    fun escalation(ctx: Context, ui: CallUi?, s: EscalationSignal) = runCatching {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val who = ui?.caller?.label ?: "来电"
        val b = Notification.Builder(ctx, JuizApp.CH_ESCALATION)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle("需要本人：${s.reason.zh}")
            .setContentText("$who · ${s.detail}")
            .setCategory(if (s.urgency == Urgency.URGENT) Notification.CATEGORY_ALARM else Notification.CATEGORY_MESSAGE)
            .setContentIntent(inCallIntent(ctx))
            .setAutoCancel(true)
        if (ui != null && ui.aiMode == AiMode.AI_VOICE) {
            b.addAction(Notification.Action.Builder(null, "立即接管", pi(ctx, CallActionReceiver.TAKEOVER, ui.id, 5)).build())
        }
        nm.notify(ID_ESCALATION, b.build())
    }

    fun simple(ctx: Context, channel: String, id: Int, title: String, text: String) = runCatching {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.notify(
            id,
            Notification.Builder(ctx, channel)
                .setSmallIcon(R.drawable.ic_launcher_fg)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(PendingIntent.getActivity(ctx, id, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true)
                .build(),
        )
    }
}

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("call") ?: return
        when (intent.action) {
            ANSWER -> CallController.answer(id)
            DECLINE -> CallController.reject(id)
            HANGUP -> CallController.hangup(id)
            TAKEOVER -> CallController.takeover(id)
            JUIZ -> CallController.aiAnswerNow(id)
        }
    }

    companion object {
        const val ANSWER = "app.juiz.ANSWER"
        const val DECLINE = "app.juiz.DECLINE"
        const val HANGUP = "app.juiz.HANGUP"
        const val TAKEOVER = "app.juiz.TAKEOVER"
        const val JUIZ = "app.juiz.JUIZ"
    }
}
