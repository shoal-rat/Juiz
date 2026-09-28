package app.juiz.call

import android.content.Context
import android.telecom.Call
import app.juiz.JuizApp
import app.juiz.core.conversation.ConversationService
import app.juiz.core.conversation.EngineOutput
import app.juiz.core.detox.CallDigest
import app.juiz.core.model.CallerInfo
import app.juiz.core.model.Channel
import app.juiz.core.model.ConsentKind
import app.juiz.core.policy.Disclosure
import app.juiz.core.policy.EscalationReason
import app.juiz.core.policy.EscalationSignal
import app.juiz.core.policy.Urgency
import app.juiz.core.util.JuizJson
import app.juiz.core.voice.Caption
import app.juiz.core.voice.EndReason
import app.juiz.core.voice.PhraseCache
import app.juiz.core.voice.ShieldListener
import app.juiz.core.voice.ShieldMode
import app.juiz.core.voice.ShieldSession
import app.juiz.core.voice.VoiceConfig
import app.juiz.core.voice.VoiceSession
import app.juiz.core.voice.VoiceSessionListener
import app.juiz.core.voice.VoiceState
import app.juiz.platform.PhoneOwnerAudioPort
import app.juiz.platform.PrivilegedCallAudioPort
import app.juiz.platform.SystemCallApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import java.io.File

/** 等待通话进入某个状态（例如 enterBackgroundAudioProcessing 之后的 AUDIO_PROCESSING）。 */
internal suspend fun awaitState(call: Call, target: Int, timeoutMs: Long): Boolean {
    if (call.currentState == target) return true
    val d = CompletableDeferred<Unit>()
    val cb = object : Call.Callback() {
        override fun onStateChanged(c: Call, state: Int) { if (state == target) d.complete(Unit) }
    }
    withContext(Dispatchers.Main) { call.registerCallback(cb) }
    return try {
        if (call.currentState == target) true else withTimeoutOrNull(timeoutMs) { d.await() } != null
    } finally {
        withContext(Dispatchers.Main) { call.unregisterCallback(cb) }
    }
}

/**
 * L1 语音代接的一次运行：
 *   后台音频处理 → 打开下行/上行 → VoiceSession → 按结束原因交还通话
 * 任何异常都执行 exitBackgroundAudioProcessing(true)：让手机重新响铃，把来电交还主人。
 */
class AiCallRunner(
    private val ctx: Context,
    private val id: String,
    private val call: Call,
    private val caller: CallerInfo,
) {
    @Volatile private var session: VoiceSession? = null
    @Volatile private var pendingTakeover = false

    fun takeover() {
        pendingTakeover = true
        session?.takeover()
    }

    fun remoteHangup() {
        session?.remoteHangup()
    }

    suspend fun run() {
        val core = JuizApp.core
        var port: PrivilegedCallAudioPort? = null
        var conversationId: String? = null
        val summary = ConversationService.SummaryBuilder()
        var reason = EndReason.ERROR
        // 开了录音同意（开场白会告知对方）才录：左声道对方、右声道 Juiz，结束后加密保存，确认任务时可回放核对
        val recorder = if (core.shouldRecordCalls()) app.juiz.core.recording.CallRecorder(java.io.File(ctx.noBackupFilesDir, "rec-tmp")) else null
        try {
            enterAiAudio()
            port = PrivilegedCallAudioPort.open(ctx)
            val tts = core.tts()
            val phrases = PhraseCache(File(ctx.filesDir, "phrases"), tts)
            val engine = core.conversations.start(caller, Channel.VOICE, "L1-voice", core.chatModel())
            conversationId = engine.context.conversationId
            val greeting = Disclosure.greeting(core.settings.ownerProfile(), core.consents.isGranted(ConsentKind.CALL_RECORDING), core.usingClonedVoice())
            val behavior = core.settings.behavior()
            val audio = recorder?.let { app.juiz.core.recording.RecordingAudioPort(port, it) } ?: port
            val s = VoiceSession(audio, core.stt(), tts, engine, phrases, listener(summary, behavior.ringOnEscalation), VoiceConfig(maxDurationMs = behavior.maxCallMinutes * 60_000L))
            session = s
            if (pendingTakeover) s.takeover()
            reason = s.run(greeting)
        } catch (e: Exception) {
            CallRegistry.update(id) { it.copy(notice = "AI 代接失败，已交还给你：${e.message}") }
            reason = EndReason.ERROR
        } finally {
            port?.release()
            handBack(reason)
            runCatching {
                val wav = recorder?.finish()
                val id = conversationId
                if (wav != null && id != null && core.shouldRecordCalls()) core.recordings?.save(id, wav)
            }.onFailure { recorder?.discard() }
            conversationId?.let { core.conversations.end(it, reason.name, summary.build()) }
            notifyOutcome(summary.build())
        }
    }

    /** true：走"接听 + 静音本机麦克风"的备用路径（设备不支持来电筛选模式）。 */
    private var mutedPath = false

    private suspend fun enterAiAudio() {
        val am = ctx.getSystemService(android.media.AudioManager::class.java)
        if (SystemCallApi.screeningModeSupported(am)) {
            withContext(Dispatchers.Main) { SystemCallApi.enterBackgroundAudioProcessing(call) }
            check(awaitState(call, SystemCallApi.STATE_AUDIO_PROCESSING, 4000)) { "通话没有进入后台音频处理状态" }
        } else {
            mutedPath = true
            withContext(Dispatchers.Main) {
                call.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY)
                CallRegistry.service?.setMuted(true)
            }
            check(awaitState(call, Call.STATE_ACTIVE, 6000)) { "通话没有接通" }
            withContext(Dispatchers.Main) { CallRegistry.service?.setMuted(true) }
        }
    }

    private suspend fun handBack(reason: EndReason) = withContext(Dispatchers.Main) {
        if (mutedPath) {
            // 备用路径：交还就是取消静音；无法"模拟响铃"，升级改为通知
            when (reason) {
                EndReason.TAKEOVER, EndReason.ESCALATED, EndReason.ERROR -> CallRegistry.service?.setMuted(false)
                EndReason.REMOTE_HANGUP -> Unit
                else -> call.disconnect()
            }
            return@withContext
        }
        val inProcessing = call.currentState == SystemCallApi.STATE_AUDIO_PROCESSING
        when (reason) {
            EndReason.TAKEOVER -> if (inProcessing) SystemCallApi.exitBackgroundAudioProcessing(call, false)
            EndReason.ESCALATED, EndReason.ERROR -> {
                if (inProcessing) SystemCallApi.exitBackgroundAudioProcessing(call, true)
                // 模拟响铃一分钟无人接：结束通话并登记回电（开场已告知对方）
                CallController.scope.launch {
                    delay(60_000)
                    if (call.currentState == SystemCallApi.STATE_SIMULATED_RINGING) {
                        call.disconnect()
                        JuizApp.core.tasks.create(
                            app.juiz.core.tasks.NewTask("回电：${caller.label}", "AI 转接本人未接通，请回电", app.juiz.core.model.TaskKind.CALLBACK, caller.number, caller.displayName, null),
                        )
                    }
                }
            }
            EndReason.REMOTE_HANGUP -> Unit
            else -> call.disconnect()
        }
    }

    private fun listener(summary: ConversationService.SummaryBuilder, ringOnEscalation: Boolean) = object : VoiceSessionListener {
        override fun onState(state: VoiceState) = CallRegistry.update(id) { it.copy(voiceState = state) }
        override fun onCallerFinal(text: String) = CallRegistry.addLine(id, "caller", text)
        override fun onAssistant(text: String) = CallRegistry.addLine(id, "juiz", text)
        override fun onOutput(output: EngineOutput) {
            summary.add(output)
            when (output) {
                is EngineOutput.TaskCreated -> CallRegistry.addLine(id, "system", "已记录任务 ${output.task.id}「${output.task.title}」")
                is EngineOutput.MessageTaken -> CallRegistry.addLine(id, "system", "已记录留言")
                else -> Unit
            }
        }
        override fun onEscalation(signal: EscalationSignal): Boolean {
            CallRegistry.update(id) { it.copy(escalations = it.escalations + signal) }
            Notifications.escalation(ctx, CallRegistry.get(id), signal)
            val handOver = signal.reason in setOf(EscalationReason.OWNER_REQUESTED, EscalationReason.EMERGENCY, EscalationReason.VIP_CALLER) ||
                (signal.reason == EscalationReason.MODEL_REQUESTED && signal.urgency == Urgency.URGENT)
            return ringOnEscalation && handOver
        }
    }

    private fun notifyOutcome(summary: String?) {
        if (summary != null) Notifications.simple(ctx, JuizApp.CH_TASKS, Notifications.ID_TASKS, "Juiz 代接：${caller.label}", summary)
    }
}

/**
 * 情绪滤网的一次运行：本人亲自通话，Juiz 只改变本人听到的内容。
 * 结束（关闭滤网或挂断）时退出后台音频处理、恢复普通通话，并生成去情绪摘要。
 */
class ShieldRunner(private val ctx: Context, private val id: String, private val call: Call) {
    @Volatile private var session: ShieldSession? = null
    private var job: Job? = null

    fun setMode(mode: ShieldMode) { session?.mode = mode }

    fun stop() { job?.cancel() }

    fun revealRaw(captionId: Int): String? = session?.revealRaw(captionId)

    fun setOwnerMic(live: Boolean) { session?.ownerMicLive = live }

    suspend fun say(text: String) {
        (session ?: throw IllegalStateException("滤网尚未就绪")).say(text)
    }

    suspend fun run(initialMode: ShieldMode) = coroutineScope {
        job = coroutineContext[Job]
        val core = JuizApp.core
        var port: PrivilegedCallAudioPort? = null
        var owner: PhoneOwnerAudioPort? = null
        try {
            withContext(Dispatchers.Main) { SystemCallApi.enterBackgroundAudioProcessing(call) }
            check(awaitState(call, SystemCallApi.STATE_AUDIO_PROCESSING, 4000)) { "通话没有进入后台音频处理状态" }
            port = PrivilegedCallAudioPort.open(ctx)
            owner = PhoneOwnerAudioPort(ctx)
            val tts = runCatching { core.tts() }.getOrNull()
            val s = ShieldSession(
                port, owner, core.stt(), core.detoxRewriter(), tts,
                object : ShieldListener {
                    override fun onCaption(caption: Caption) = CallRegistry.update(id) { ui ->
                        ui.copy(captions = (ui.captions.filterNot { it.id == caption.id } + caption).sortedBy { it.id }.takeLast(40))
                    }
                    override fun onOwnerLine(text: String) = CallRegistry.addLine(id, "owner", text)
                    override fun onIntensity(level: Int) = CallRegistry.update(id) { it.copy(intensity = level) }
                    override fun onSuggestions(captionId: Int, options: List<app.juiz.core.detox.ReplyOption>) =
                        CallRegistry.update(id) { it.copy(suggestions = options) }
                    override fun onSpoken(text: String) = CallRegistry.addLine(id, "owner", "🔊 $text")
                },
                replyTts = tts,
                suggester = app.juiz.core.detox.ReplySuggester(runCatching { core.chatModel() }.getOrNull()),
            )
            s.mode = initialMode
            s.ownerMicLive = CallRegistry.get(id)?.replyMode != true
            session = s
            s.run()
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) {
                CallRegistry.update(id) { it.copy(notice = "情绪滤网启动失败，已恢复普通通话：${e.message}") }
            }
        } finally {
            port?.release()
            owner?.release()
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                if (call.currentState == SystemCallApi.STATE_AUDIO_PROCESSING) SystemCallApi.exitBackgroundAudioProcessing(call, false)
            }
            val transcript = session?.transcriptSnapshot().orEmpty()
            if (transcript.isNotEmpty()) {
                CoroutineScope(Dispatchers.Default).launch { saveDigest(transcript) }
            }
        }
    }

    private suspend fun saveDigest(transcript: List<Pair<String, String>>) {
        val core = JuizApp.core
        val ui = CallRegistry.get(id)
        val digest = core.digestBuilder().build(transcript)
        core.archive.append("shield.digest", id, JsonObject(
            (JuizJson.encodeToJsonElement(CallDigest.serializer(), digest) as JsonObject) + mapOf(
                "caller" to kotlinx.serialization.json.JsonPrimitive(ui?.caller?.label ?: "来电"),
            ),
        ))
        Notifications.simple(ctx, JuizApp.CH_TASKS, Notifications.ID_TASKS, "去情绪摘要已生成", digest.summary.take(120))
    }
}
