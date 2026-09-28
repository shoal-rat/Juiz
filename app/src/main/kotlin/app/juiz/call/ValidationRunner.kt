package app.juiz.call

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.telecom.Call
import android.telecom.VideoProfile
import app.juiz.core.voice.Pcm
import app.juiz.platform.CapabilityProbe
import app.juiz.platform.PrivilegedCallAudioPort
import app.juiz.platform.SystemCallApi
import app.juiz.platform.ValidationReport
import app.juiz.platform.ValidationStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * 真机验证向导（第一阶段验收）。需要另一部手机打进来，测试者按屏幕提示配合。
 * 能自动判定的步骤自动判定；"对方是否听到了合成声音"这类无法从本机判断的，必须人工确认。
 * 报告与系统指纹绑定；只有步骤 1–5 全部通过，本机才开放 L1 自动代接。
 */
class ValidationRunner(private val ctx: Context) {
    data class State(val steps: List<ValidationStep>, val prompt: String, val callId: String? = null, val busy: Boolean = false)

    private val initial = listOf(
        ValidationStep("ring_detect", "1 收到来电（记录是否锁屏）"),
        ValidationStep("enter_processing", "2 进入后台音频处理"),
        ValidationStep("downlink", "3 取得对方声音"),
        ValidationStep("uplink", "4 合成声音送入通话", manual = true),
        ValidationStep("takeover", "5 本人接管后双向正常", manual = true),
        ValidationStep("simulated_ring", "6 模拟响铃接管（可选）"),
        ValidationStep("background", "7a 应用在后台时来电", manual = true),
        ValidationStep("headset", "7b 通话中切换耳机", manual = true),
        ValidationStep("weak_network", "7c 弱网下 AI 失败能交还本人", manual = true),
    )

    private val _state = MutableStateFlow(State(initial, "准备好后点「开始」，再用另一部手机拨打本机号码。"))
    val state: StateFlow<State> = _state
    var armed = false
        private set
    private var call: Call? = null
    private var port: PrivilegedCallAudioPort? = null

    fun arm() {
        armed = true
        _state.value = State(initial, "等待来电：请用另一部手机拨打本机。可以先锁屏再打，验证锁屏来电。")
    }

    private fun mark(id: String, passed: Boolean?, measured: String? = null) {
        _state.value = _state.value.copy(steps = _state.value.steps.map { if (it.id == id) it.copy(passed = passed, measured = measured ?: it.measured) else it })
        save()
    }

    private fun prompt(p: String, busy: Boolean = false) { _state.value = _state.value.copy(prompt = p, busy = busy) }

    fun attach(id: String, c: Call) {
        call = c
        _state.value = _state.value.copy(callId = id)
        val locked = ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        mark("ring_detect", true, if (locked) "锁屏状态下收到" else "未锁屏")
        prompt("来电已检测到。点「进入后台处理」——对方会感觉电话被接起，但本机听筒和麦克风不接入。")
    }

    fun enterProcessing() = CallController.scope.launch(Dispatchers.Default) {
        val c = call ?: return@launch
        prompt("正在进入后台音频处理…", busy = true)
        val ok = runCatching {
            withContext(Dispatchers.Main) { SystemCallApi.enterBackgroundAudioProcessing(c) }
            awaitState(c, SystemCallApi.STATE_AUDIO_PROCESSING, 4000)
        }.getOrElse { e -> prompt("失败：${e.message}"); false }
        mark("enter_processing", ok, "state=${c.currentState}")
        if (!ok) return@launch
        port = runCatching { PrivilegedCallAudioPort.open(ctx) }.getOrElse { e ->
            mark("downlink", false, e.message)
            prompt("打开通话音频失败：${e.message}。点「接管」恢复通话。")
            return@launch
        }
        measureDownlink()
    }

    private suspend fun measureDownlink() {
        val p = port ?: return
        prompt("请对方连续说话 5 秒（例如从一数到十）…", busy = true)
        var peak = 0.0
        val start = System.currentTimeMillis()
        runCatching {
            p.captured().takeWhile { System.currentTimeMillis() - start < 5_000 }.collect { peak = max(peak, Pcm.rms(it)) }
        }
        val ok = peak > 600
        mark("downlink", ok, "峰值 RMS ${peak.toInt()}")
        prompt(if (ok) "取得了对方声音。点「播放测试音」，然后问对方有没有听到三声「嘀」。" else "没有测到对方声音（峰值 ${peak.toInt()}）。可以重试，或点「接管」。")
    }

    fun playUplinkTest() = CallController.scope.launch(Dispatchers.Default) {
        val p = port ?: return@launch
        prompt("正在向通话播放三声测试音…", busy = true)
        val rate = p.playbackRate
        repeat(3) {
            p.play(ShortArray(rate * 300 / 1000) { i -> (8000 * sin(2 * PI * 1000 * i / rate)).toInt().toShort() })
            p.play(ShortArray(rate * 250 / 1000))
        }
        prompt("测试音已播放。点「接管」恢复通话后，问对方是否听到三声「嘀」，再在下面确认。")
    }

    fun takeover(id: String) {
        if (id != _state.value.callId) return
        CallController.scope.launch(Dispatchers.Default) {
            val c = call ?: return@launch
            port?.release()
            port = null
            if (c.currentState == SystemCallApi.STATE_AUDIO_PROCESSING) {
                withContext(Dispatchers.Main) { SystemCallApi.exitBackgroundAudioProcessing(c, false) }
                val ok = awaitState(c, Call.STATE_ACTIVE, 4000)
                mark("takeover", if (ok) null else false, "state=${c.currentState}")
                prompt(if (ok) "已恢复为普通通话。请和对方确认：双方都能正常听到吗？测试音听到了吗？" else "没有恢复为普通通话（state=${c.currentState}）。")
            }
        }
    }

    fun confirm(stepId: String, passed: Boolean) = mark(stepId, passed)

    /** 可选：模拟响铃接管。先再次进入后台处理，再以"响铃"方式退出，本人接听。 */
    fun testSimulatedRing() = CallController.scope.launch(Dispatchers.Default) {
        val c = call ?: return@launch
        prompt("进入后台处理后将让手机重新响铃，请直接接听…", busy = true)
        val ok = runCatching {
            withContext(Dispatchers.Main) { SystemCallApi.enterBackgroundAudioProcessing(c) }
            check(awaitState(c, SystemCallApi.STATE_AUDIO_PROCESSING, 4000))
            delay(1500)
            withContext(Dispatchers.Main) { SystemCallApi.exitBackgroundAudioProcessing(c, true) }
            check(awaitState(c, SystemCallApi.STATE_SIMULATED_RINGING, 4000))
            withContext(Dispatchers.Main) { c.answer(VideoProfile.STATE_AUDIO_ONLY) }
            awaitState(c, Call.STATE_ACTIVE, 30_000)
        }.getOrElse { false }
        mark("simulated_ring", ok, "state=${c.currentState}")
        prompt(if (ok) "模拟响铃接管成功。" else "模拟响铃接管未成功（state=${c.currentState}）。")
    }

    fun onState(id: String, state: Int) = Unit

    fun onRemoved(id: String) {
        if (id == _state.value.callId) {
            port?.release()
            port = null
            call = null
            armed = false
            prompt("通话已结束。报告已保存；未确认的步骤可以在下面补充确认。")
        }
    }

    private fun save() {
        CapabilityProbe.saveValidation(
            ValidationReport(Build.FINGERPRINT, "${Build.MANUFACTURER} ${Build.MODEL}", Build.VERSION.SDK_INT, System.currentTimeMillis(), _state.value.steps),
        )
    }
}
