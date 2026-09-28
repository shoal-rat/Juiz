package app.juiz.call

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import app.juiz.JuizApp
import app.juiz.core.model.CallerInfo
import app.juiz.core.model.CapabilityLevel
import app.juiz.core.model.ConsentKind
import app.juiz.core.rules.CallActionType
import app.juiz.core.voice.ShieldMode
import app.juiz.platform.CapabilityProbe
import app.juiz.ui.InCallActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put

/**
 * 来电编排：规则决策 → 倒计时 → 代接（L1 语音 / L0 短信）；主人随时接管。
 * 所有自动动作都在"仍在响铃"时才执行，主人先接起就什么都不做。
 */
object CallController {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val countdowns = mutableMapOf<String, Job>()
    private val runners = mutableMapOf<String, Job>()
    private val callbacks = mutableMapOf<String, Call.Callback>()
    internal val aiRunners = mutableMapOf<String, AiCallRunner>()
    internal val shieldRunners = mutableMapOf<String, ShieldRunner>()
    lateinit var appContext: Context
        private set
    var validation: ValidationRunner? = null

    fun onCallAdded(service: InCallService, call: Call) {
        appContext = service.applicationContext
        val id = CallRegistry.idOf(call)
        CallRegistry.telecom[id] = call
        val incoming = call.details.callDirection == Call.Details.DIRECTION_INCOMING
        val caller = app.juiz.platform.Contacts.resolve(appContext, call.details.handle?.schemeSpecificPart)
        CallRegistry.put(CallUi(id, caller, call.currentState, incoming))
        val cb = object : Call.Callback() {
            override fun onStateChanged(c: Call, state: Int) = onState(id, state)
        }
        call.registerCallback(cb)
        callbacks[id] = cb

        val v = validation
        if (incoming && call.currentState == Call.STATE_RINGING && v != null && v.armed) {
            v.attach(id, call)
        } else if (incoming && call.currentState == Call.STATE_RINGING) {
            decideAndSchedule(id, call, caller)
        }
        refreshNotification(id)
        showCallScreen()
    }

    fun onCallRemoved(service: InCallService, call: Call) {
        val id = CallRegistry.idOf(call)
        countdowns.remove(id)?.cancel()
        aiRunners[id]?.remoteHangup()
        shieldRunners[id]?.stop()
        runners.remove(id)
        callbacks.remove(id)?.let { call.unregisterCallback(it) }
        validation?.onRemoved(id)
        CallRegistry.remove(id)
        if (CallRegistry.calls.value.isEmpty()) {
            service.getSystemService(NotificationManager::class.java).cancel(Notifications.ID_CALL)
            runCatching { service.stopForeground(InCallService.STOP_FOREGROUND_REMOVE) }
        }
    }

    private fun onState(id: String, state: Int) {
        CallRegistry.update(id) { it.copy(state = state, connectedAt = if (state == Call.STATE_ACTIVE && it.connectedAt == null) System.currentTimeMillis() else it.connectedAt) }
        if (state != Call.STATE_RINGING) countdowns.remove(id)?.cancel()
        val ui = CallRegistry.get(id) ?: return
        if (state == Call.STATE_ACTIVE && ui.shieldArmed && ui.aiMode == AiMode.NONE && shieldRunners[id] == null) startShield(id)
        validation?.onState(id, state)
        refreshNotification(id)
    }

    private fun decideAndSchedule(id: String, call: Call, caller: CallerInfo) {
        val core = JuizApp.core
        val level = CapabilityProbe.run(appContext).level ?: CapabilityLevel.L0_STANDARD
        val now = core.clock.now().atZone(core.clock.zone())
        val decision = core.ruleEngine().decide(
            caller, now, level,
            smsScreeningConsented = core.consents.isGranted(ConsentKind.SMS_SCREENING),
            autoAnswerEnabled = core.settings.behavior().autoAnswerEnabled,
        )
        core.archive.append("call.decision", id) {
            put("number", caller.number)
            put("tier", caller.tier.name)
            put("level", level.name)
            put("action", decision.action.name)
            put("delay", decision.delaySeconds)
            decision.ruleId?.let { put("rule", it) }
            decision.downgradeReason?.let { put("downgrade", it) }
        }
        CallRegistry.update(id) { it.copy(decision = decision, shieldArmed = decision.action == CallActionType.RING_WITH_SHIELD) }
        when (decision.action) {
            CallActionType.REJECT_SILENT -> call.reject(false, null)
            CallActionType.AI_VOICE_ANSWER, CallActionType.SMS_SCREEN -> {
                countdowns[id] = scope.launch {
                    for (s in decision.delaySeconds downTo 1) {
                        CallRegistry.update(id) { it.copy(countdown = s) }
                        delay(1000)
                        if (call.currentState != Call.STATE_RINGING) return@launch
                    }
                    CallRegistry.update(id) { it.copy(countdown = null) }
                    if (call.currentState != Call.STATE_RINGING) return@launch
                    if (decision.action == CallActionType.AI_VOICE_ANSWER) startAi(id) else smsScreen(id)
                }
            }
            else -> Unit
        }
    }

    // ---------- 主人操作 ----------

    private fun call(id: String) = CallRegistry.telecom[id]

    fun answer(id: String) {
        countdowns.remove(id)?.cancel()
        call(id)?.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    fun reject(id: String) {
        countdowns.remove(id)?.cancel()
        call(id)?.reject(false, null)
    }

    fun hangup(id: String) {
        aiRunners[id]?.remoteHangup()
        call(id)?.disconnect()
    }

    fun aiAnswerNow(id: String) {
        countdowns.remove(id)?.cancel()
        val level = CapabilityProbe.run(appContext).level
        if (level == CapabilityLevel.L1_PRIVILEGED_VOICE) startAi(id) else smsScreen(id)
    }

    fun smsScreenNow(id: String) {
        countdowns.remove(id)?.cancel()
        smsScreen(id)
    }

    /** 立即接管：停掉 AI 语音，清空待播音频，把通话交回本机听筒和麦克风。 */
    fun takeover(id: String) {
        aiRunners[id]?.takeover()
        validation?.takeover(id)
        showCallScreen()
    }

    fun setMuted(muted: Boolean) = CallRegistry.service?.setMuted(muted)

    @Suppress("DEPRECATION") // requestCallEndpointChange 需要 API 34，minSdk 为 29
    fun setSpeaker(on: Boolean) = CallRegistry.service?.setAudioRoute(if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE)

    fun toggleHold(id: String) {
        val c = call(id) ?: return
        if (c.currentState == Call.STATE_HOLDING) c.unhold() else c.hold()
    }

    fun dtmf(id: String, digit: Char) {
        val c = call(id) ?: return
        c.playDtmfTone(digit)
        c.stopDtmfTone()
    }

    fun toggleShield(id: String) {
        if (shieldRunners[id] != null) shieldRunners[id]?.stop() else startShield(id)
    }

    fun setShieldMode(id: String, mode: ShieldMode) {
        shieldRunners[id]?.setMode(mode)
        CallRegistry.update(id) { it.copy(shieldMode = mode) }
    }

    // ---------- 代接与滤网 ----------

    private fun goForeground(id: String) {
        val svc = CallRegistry.service ?: return
        val ui = CallRegistry.get(id) ?: return
        runCatching {
            svc.startForeground(
                Notifications.ID_CALL, Notifications.ongoing(appContext, ui),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        }
    }

    private fun startAi(id: String) {
        val c = call(id) ?: return
        val ui = CallRegistry.get(id) ?: return
        val runner = AiCallRunner(appContext, id, c, ui.caller)
        aiRunners[id] = runner
        CallRegistry.update(id) { it.copy(aiMode = AiMode.AI_VOICE) }
        goForeground(id)
        runners[id] = scope.launch(Dispatchers.Default) {
            try {
                runner.run()
            } finally {
                aiRunners.remove(id)
                CallRegistry.update(id) { it.copy(aiMode = AiMode.NONE, voiceState = null) }
                refreshNotification(id)
            }
        }
    }

    private fun startShield(id: String) {
        val c = call(id) ?: return
        if (CapabilityProbe.run(appContext).level != CapabilityLevel.L1_PRIVILEGED_VOICE) {
            CallRegistry.update(id) { it.copy(notice = "情绪滤网需要通话音频（L1）。本机当前为 L0，已按普通通话处理。") }
            return
        }
        val runner = ShieldRunner(appContext, id, c)
        shieldRunners[id] = runner
        CallRegistry.update(id) { it.copy(aiMode = AiMode.SHIELD) }
        goForeground(id)
        runners[id] = scope.launch(Dispatchers.Default) {
            try {
                runner.run(CallRegistry.get(id)?.shieldMode ?: ShieldMode.QUIET_WITH_CAPTIONS)
            } finally {
                shieldRunners.remove(id)
                CallRegistry.update(id) { it.copy(aiMode = AiMode.NONE, shieldArmed = false) }
            }
        }
    }

    private fun smsScreen(id: String) {
        val c = call(id) ?: return
        val ui = CallRegistry.get(id) ?: return
        scope.launch(Dispatchers.Default) { SmsScreening.screenCall(appContext, c, ui.caller) }
    }

    fun refreshNotification(id: String) {
        val ui = CallRegistry.get(id) ?: return
        val nm = appContext.getSystemService(NotificationManager::class.java)
        val n = if (ui.state == Call.STATE_RINGING || ui.state == 13) Notifications.incoming(appContext, ui) else Notifications.ongoing(appContext, ui)
        nm.notify(Notifications.ID_CALL, n)
    }

    fun showCallScreen() {
        runCatching {
            appContext.startActivity(Intent(appContext, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
