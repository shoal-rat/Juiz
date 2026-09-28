package app.juiz.call

import android.telecom.Call
import android.telecom.CallAudioState
import app.juiz.core.model.CallerInfo
import app.juiz.core.policy.EscalationSignal
import app.juiz.core.rules.CallDecision
import app.juiz.core.voice.Caption
import app.juiz.core.voice.ShieldMode
import app.juiz.core.voice.VoiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class LiveLine(val speaker: String, val text: String, val at: Long = System.currentTimeMillis())

enum class AiMode { NONE, AI_VOICE, SHIELD, VALIDATION }

data class CallUi(
    val id: String,
    val caller: CallerInfo,
    val state: Int,
    val incoming: Boolean,
    val decision: CallDecision? = null,
    val countdown: Int? = null,
    val aiMode: AiMode = AiMode.NONE,
    val voiceState: VoiceState? = null,
    val shieldArmed: Boolean = false,
    val shieldMode: ShieldMode = ShieldMode.QUIET_WITH_CAPTIONS,
    val lines: List<LiveLine> = emptyList(),
    val captions: List<Caption> = emptyList(),
    val intensity: Int = 0,
    val escalations: List<EscalationSignal> = emptyList(),
    val connectedAt: Long? = null,
    val notice: String? = null,
    /** 选句代答：本人不开口，从候选回复中选择。 */
    val replyMode: Boolean = false,
    val suggestions: List<app.juiz.core.detox.ReplyOption> = emptyList(),
    val speaking: Boolean = false,
) {
    val stateLabel: String get() = when (state) {
        Call.STATE_RINGING -> "来电"
        Call.STATE_DIALING, Call.STATE_CONNECTING -> "拨号中"
        Call.STATE_ACTIVE -> if (aiMode == AiMode.SHIELD) "通话中 · 情绪滤网" else "通话中"
        Call.STATE_HOLDING -> "保持中"
        Call.STATE_DISCONNECTING, Call.STATE_DISCONNECTED -> "已结束"
        12 -> when (aiMode) {
            AiMode.AI_VOICE -> "Juiz 代接中"
            AiMode.SHIELD -> "通话中 · 情绪滤网"
            AiMode.VALIDATION -> "验证中"
            AiMode.NONE -> "后台处理中"
        }
        13 -> "等待本人接听"
        else -> "状态 $state"
    }
}

/** Call.getState() 在 API 31 起改由 Call.Details 提供；minSdk 29 需要两条路。 */
val Call.currentState: Int
    get() = if (android.os.Build.VERSION.SDK_INT >= 31) details.state else @Suppress("DEPRECATION") state

/** 进程内的通话状态中心：InCallService 写，界面读。 */
object CallRegistry {
    private val _calls = MutableStateFlow<List<CallUi>>(emptyList())
    val calls: StateFlow<List<CallUi>> = _calls
    val audioState = MutableStateFlow<CallAudioState?>(null)
    val telecom = mutableMapOf<String, Call>()
    @Volatile var service: JuizInCallService? = null
    /** 通话界面在前台时不再弹横幅通知（避免盖住自己的界面）。 */
    @Volatile var screenVisible: Boolean = false

    fun put(ui: CallUi) = _calls.update { list -> list.filterNot { it.id == ui.id } + ui }
    fun remove(id: String) {
        _calls.update { list -> list.filterNot { it.id == id } }
        telecom.remove(id)
    }
    fun get(id: String): CallUi? = _calls.value.firstOrNull { it.id == id }
    fun update(id: String, f: (CallUi) -> CallUi) = _calls.update { list -> list.map { if (it.id == id) f(it) else it } }
    fun addLine(id: String, speaker: String, text: String) = update(id) { it.copy(lines = (it.lines + LiveLine(speaker, text)).takeLast(80)) }

    fun idOf(call: Call): String = call.details.creationTimeMillis.toString() + ":" + (call.details.handle?.schemeSpecificPart ?: "?")
}
