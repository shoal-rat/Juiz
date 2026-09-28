package app.juiz.sim

import app.juiz.core.conversation.EngineOutput
import app.juiz.core.detox.DetoxRewriter
import app.juiz.core.errand.ErrandGrant
import app.juiz.core.errand.InfoNote
import app.juiz.core.model.CallerInfo
import app.juiz.core.model.Channel
import app.juiz.core.model.ContactTier
import app.juiz.core.model.OwnerProfile
import app.juiz.core.policy.Disclosure
import app.juiz.core.policy.EscalationSignal
import app.juiz.core.providers.Http
import app.juiz.core.rules.TimeWindow
import app.juiz.core.util.JuizJson
import app.juiz.core.voice.CallAudioPort
import app.juiz.core.voice.Caption
import app.juiz.core.voice.OwnerAudioPort
import app.juiz.core.voice.Pcm
import app.juiz.core.voice.ShieldListener
import app.juiz.core.voice.ShieldSession
import app.juiz.core.voice.StreamingStt
import app.juiz.core.voice.SttSession
import app.juiz.core.voice.TextToSpeech
import app.juiz.core.voice.VoiceConfig
import app.juiz.core.voice.VoiceSession
import app.juiz.core.voice.VoiceSessionListener
import app.juiz.core.voice.VoiceState
import app.juiz.core.voice.resampleOnce
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel as KChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sign
import kotlin.random.Random

/**
 * 通话音频测试台（电脑上）：用本机 Qwen3-TTS 生成的"领导/同事/客户"语音当作对方声音，
 * 先降成电话线路音质，再按真实时间节奏喂给 Juiz 的真实语音代码（VAD → 转写 → 引擎/滤网 → 合成）。
 * 测的是除运营商通话通道以外的整条音频链路；运营商那一段只能在真机上验证。
 */
object AudioBench {
    private const val RATE = 16_000
    private val slowClient by lazy { Http.client.newBuilder().readTimeout(4, java.util.concurrent.TimeUnit.MINUTES).callTimeout(5, java.util.concurrent.TimeUnit.MINUTES).build() }
    /** --only shield,task：只跑这几个场景。 */
    private var only: Set<String>? = null
    private val sttAll = Collections.synchronizedList(mutableListOf<Long>())

    // ---------------- WAV ----------------

    fun readWav(f: File): Pair<ShortArray, Int> {
        val b = f.readBytes()
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var rate = 16000
        var channels = 1
        var data: ShortArray? = null
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4)
            val len = bb.getInt(pos + 4)
            if (id == "fmt ") {
                channels = bb.getShort(pos + 10).toInt()
                rate = bb.getInt(pos + 12)
            } else if (id == "data") {
                val n = minOf(len, b.size - pos - 8) / 2
                val all = ShortArray(n) { bb.getShort(pos + 8 + it * 2) }
                data = if (channels == 1) all else ShortArray(n / channels) { all[it * channels] }
            }
            pos += 8 + len + (len and 1)
        }
        return (data ?: ShortArray(0)) to rate
    }

    fun wavBytes(pcm: ShortArray, rate: Int): ByteArray {
        val data = Pcm.shortsToBytes(pcm)
        val out = ByteArrayOutputStream()
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(data.size)
        out.write(h.array()); out.write(data)
        return out.toByteArray()
    }

    // ---------------- 电话线路 ----------------

    /** 模拟电话线路：降到 8 kHz、300–3400 Hz 带通、G.711 μ-law 压扩、底噪，再升回 16 kHz。 */
    fun phoneLine(pcm: ShortArray, rate: Int, noiseDb: Double = -38.0, seed: Int = 7): ShortArray {
        var x = resampleOnce(pcm, rate, 8000).map { it.toDouble() }.toDoubleArray()
        fun onePole(cut: Double, high: Boolean) {
            val a = kotlin.math.exp(-2 * PI * cut / 8000)
            var y = 0.0
            for (i in x.indices) {
                val lp = (1 - a) * x[i] + a * y
                y = lp
                x[i] = if (high) x[i] - lp else lp
            }
        }
        onePole(300.0, high = true); onePole(3400.0, high = false)
        val rnd = Random(seed)
        val noise = 32767 * 10.0.pow(noiseDb / 20)
        val mu = 255.0
        val out = ShortArray(x.size) { i ->
            val v = (x[i] + (rnd.nextDouble() * 2 - 1) * noise).coerceIn(-32767.0, 32767.0) / 32768.0
            val enc = sign(v) * ln(1 + mu * abs(v)) / ln(1 + mu)
            val q = kotlin.math.round(enc * 127) / 127
            val dec = sign(q) * ((1 + mu).pow(abs(q)) - 1) / mu
            (dec * 32767).toInt().toShort()
        }
        return resampleOnce(out, 8000, RATE)
    }

    // ---------------- 本地语音服务 ----------------

    private val WAV = "audio/wav".toMediaType()

    class LocalHttpStt(private val url: String) : StreamingStt {
        override val id = "local-qwen3-asr"
        private val io = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        val latencies = Collections.synchronizedList(mutableListOf<Long>())
        override suspend fun connect(): SttSession = object : SttSession {
            override val sampleRate = 24_000
            override val partials: SharedFlow<String> = MutableSharedFlow()
            private val buf = ByteArrayOutputStream()
            override fun append(pcm: ShortArray) { synchronized(buf) { buf.write(Pcm.shortsToBytes(pcm)) } }
            override fun commit(): kotlinx.coroutines.Deferred<String> {
                // 同步封口：之后追加的音频属于下一段
                val pcm = synchronized(buf) { Pcm.bytesToShorts(buf.toByteArray()).also { buf.reset() } }
                val t = System.currentTimeMillis()
                return io.async {
                    val text = Http.client.newCall(Request.Builder().url("$url/asr").post(wavBytes(pcm, 24_000).toRequestBody(WAV)).build()).execute().use {
                        JuizJson.parseToJsonElement(it.body!!.string()).jsonObject["text"]?.jsonPrimitive?.content.orEmpty()
                    }
                    latencies += System.currentTimeMillis() - t
                    text
                }
            }
            override fun clear() { synchronized(buf) { buf.reset() } }
            override fun close() {}
        }
    }

    class LocalHttpTts(private val url: String, private val instruct: String) : TextToSpeech {
        override val id = "local-qwen3-tts"
        override val sampleRate = 24_000
        val latencies = Collections.synchronizedList(mutableListOf<Long>())
        override fun synthesize(text: String): Flow<ShortArray> = flow {
            val t0 = System.currentTimeMillis()
            val bytes = withContext(Dispatchers.IO) {
                val body = buildJsonObject { put("text", text); put("instruct", instruct) }.toString().toRequestBody(Http.JSON)
                // 本机合成在机器忙的时候可能比平时的 60 秒读超时更久；测试台里宁可等，也别整轮崩掉
                slowClient.newCall(Request.Builder().url("$url/tts").post(body).build()).execute().use { it.body!!.bytes() }
            }
            val f = File.createTempFile("tts", ".wav").apply { writeBytes(bytes) }
            val (pcm, rate) = readWav(f)
            f.delete()
            val pcm24 = if (rate == sampleRate) pcm else resampleOnce(pcm, rate, sampleRate)
            latencies += System.currentTimeMillis() - t0
            pcm24.toList().chunked(2400).forEach { emit(it.toShortArray()) }
        }
    }

    /** 不跑模型的"合成"：按字数生成等长的低音量提示音，用来单独测量链路本身的延迟。 */
    class InstantTts : TextToSpeech {
        override val id = "instant"
        override val sampleRate = 24_000
        val latencies = Collections.synchronizedList(mutableListOf<Long>())
        override fun synthesize(text: String): Flow<ShortArray> = flow {
            latencies += 0
            val n = (text.length * 0.2 * sampleRate).toInt()
            val pcm = ShortArray(n) { (1500 * kotlin.math.sin(2 * PI * 330 * it / sampleRate)).toInt().toShort() }
            pcm.toList().chunked(2400).forEach { emit(it.toShortArray()) }
        }
    }

    /** 记录模型首个文字的到达时间。 */
    class TimedModel(private val inner: app.juiz.core.conversation.ChatModel) : app.juiz.core.conversation.ChatModel {
        override val id = inner.id
        val firstToken = Collections.synchronizedList(mutableListOf<Long>())
        override fun stream(request: app.juiz.core.conversation.ChatRequest) = flow {
            val t0 = System.currentTimeMillis()
            var first = true
            inner.stream(request).collect { ev ->
                if (first && (ev is app.juiz.core.conversation.ChatEvent.TextDelta || ev is app.juiz.core.conversation.ChatEvent.ToolCallDone)) {
                    first = false
                    firstToken += System.currentTimeMillis() - t0
                }
                emit(ev)
            }
        }
    }

    // ---------------- 录制时间线 ----------------

    /** 把对方说的话（下行）和 Juiz 说的话（上行）按真实时间摆在同一条音轨上，便于试听。 */
    class Timeline {
        private val t0 = System.currentTimeMillis()
        private val samples = ArrayList<Short>()
        @Synchronized fun add(pcm: ShortArray, gain: Double = 1.0) {
            val at = ((System.currentTimeMillis() - t0) * RATE / 1000).toInt()
            while (samples.size < at + pcm.size) samples.add(0)
            for (i in pcm.indices) {
                val v = samples[at + i] + (pcm[i] * gain).toInt()
                samples[at + i] = v.coerceIn(-32768, 32767).toShort()
            }
        }
        @Synchronized fun pcm(): ShortArray = samples.toShortArray()
    }

    /** 按真实时间播放"对方的声音"，并以 20 ms 为单位真实时间地"播放" Juiz 的声音。 */
    class BenchCall(private val timeline: Timeline) : CallAudioPort {
        override val captureRate = RATE
        override val playbackRate = RATE
        private val frames = KChannel<ShortArray>(KChannel.UNLIMITED)
        @Volatile var flushes = 0
        @Volatile var juizSpeakingMs = 0L
        override fun captured(): Flow<ShortArray> = frames.receiveAsFlow()
        override suspend fun play(pcm: ShortArray) {
            for (slice in pcm.toList().chunked(RATE / 50)) {
                val s = slice.toShortArray()
                timeline.add(s)
                juizSpeakingMs += 20
                delay(20)
            }
        }
        override fun flushPlayback() { flushes++ }

        suspend fun say(pcm: ShortArray) {
            for (slice in pcm.toList().chunked(RATE / 50)) {
                val s = slice.toShortArray()
                timeline.add(s, 0.9)
                frames.send(s)
                delay(20)
            }
        }

        suspend fun silence(ms: Long) {
            val f = ShortArray(RATE / 50)
            repeat((ms / 20).toInt()) {
                frames.send(f)
                delay(20)
            }
        }
    }

    // ---------------- 评分 ----------------

    private val digits = "零一二三四五六七八九"
    private fun normalize(s: String): String = s.lowercase()
        .replace(Regex("\\d")) { digits[it.value.toInt()].toString() }
        .filter { it.isLetter() && (it in '一'..'鿿' || it in 'a'..'z') }

    /** 字错误率（中文按字、英文按字母，数字统一成中文数字）。 */
    fun cer(ref: String, hyp: String): Double {
        val a = normalize(ref)
        val b = normalize(hyp)
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return d[b.length].toDouble() / max(1, a.length)
    }

    @Serializable
    data class Line(val id: String, val speaker: String, val text: String, val must_drop: List<String> = emptyList(), val must_keep: List<String> = emptyList(), val deadline: String? = null)

    @Serializable
    data class Spec(val speakers: Map<String, String>, val lines: List<Line>)

    data class Check(val group: String, val name: String, val ok: Boolean, val detail: String = "")

    /** 看一段测试音频经过电话线路后，VAD 在哪些时间点判定开口/说完。 */
    fun vadDebug(args: Args) {
        val id = args.positional.getOrNull(1) ?: "shield-2"
        val (pcm, rate) = readWav(File(args["testset"] ?: "sim-data/audio-bench/testset", "$id.wav"))
        val line0 = phoneLine(pcm, rate, seed = id.hashCode())
        val agc = app.juiz.core.voice.Agc()
        val line = line0.toList().chunked(RATE / 50).flatMap { agc.process(it.toShortArray()).toList() }.toShortArray()
        val vad = app.juiz.core.voice.EnergyVad(RATE, app.juiz.core.voice.VadConfig(endSilenceMs = 700))
        val frame = RATE / 50
        var t = 0
        println("$id：原始 ${pcm.size * 1000 / rate} ms，峰值 RMS 原始 ${Pcm.rms(pcm).toInt()} / 线路 ${Pcm.rms(line).toInt()}")
        line.toList().chunked(frame).forEach { f ->
            val arr = f.toShortArray()
            vad.process(arr)?.let { ev -> println("  ${t} ms  $ev  (本帧 RMS ${Pcm.rms(arr).toInt()}${if (ev == app.juiz.core.voice.VadEvent.SPEECH_END) "，段长 ${vad.lastUtteranceMs} ms" else ""})") }
            t += 20
        }
        // 每 200 ms 的能量曲线
        println("  能量：" + line.toList().chunked(RATE / 5).joinToString(" ") { (Pcm.rms(it.toShortArray()) / 100).toInt().toString() })
    }

    // ---------------- 运行 ----------------

    suspend fun run(args: Args) {
        val url = args["bench-url"] ?: "http://127.0.0.1:8765"
        val dir = File(args["testset"] ?: "sim-data/audio-bench/testset")
        val spec = JuizJson.decodeFromString(Spec.serializer(), File("tools/audio-bench/testset.json").readText())
        val lines = spec.lines.associateBy { it.id }
        fun audio(id: String): ShortArray {
            val (pcm, rate) = readWav(File(dir, "$id.wav"))
            return phoneLine(pcm, rate, seed = id.hashCode())
        }
        val out = File(SimEnv.dataDir, "audio-bench/out").apply { mkdirs() }
        val checks = mutableListOf<Check>()
        val juizVoice = "年轻女性，温柔、平静、礼貌、专业的礼宾助理，语速适中。"
        val tts: TextToSpeech = if (args.flag("fast-tts")) InstantTts() else LocalHttpTts(url, juizVoice)
        val model = TimedModel(SimEnv.model(args.modelChoice()))
        sttAll.clear()
        only = args["only"]?.split(',')?.map { it.trim() }?.toSet()

        // ① 情绪滤网：愤怒的领导 → 转写 → 去情绪 → 字幕
        println(Ansi.bold(Ansi.cyan("① 情绪滤网（本人接听）")))
        if (only == null || "shield" in only!!) run {
            val timeline = Timeline()
            val call = BenchCall(timeline)
            val stt = LocalHttpStt(url)
            val captions = Collections.synchronizedList(mutableListOf<Caption>())
            val ownerEar = object : OwnerAudioPort {
                override val micRate = RATE
                override val earRate = RATE
                override fun mic(): Flow<ShortArray> = flow { while (true) { delay(20); emit(ShortArray(RATE / 50)) } }
                override suspend fun playToEar(pcm: ShortArray) {}
                override fun flushEar() {}
            }
            val capTimes = Collections.synchronizedMap(mutableMapOf<Int, Long>())
            val shield = ShieldSession(call, ownerEar, stt, DetoxRewriter(model), null, object : ShieldListener {
                override fun onCaption(caption: Caption) {
                    captions.removeAll { it.id == caption.id }
                    captions += caption
                    if (caption.final) capTimes.putIfAbsent(caption.id, System.currentTimeMillis())
                }
            })
            val ids = listOf("shield-1", "shield-2", "shield-3")
            val starts = mutableListOf<Long>()
            val ends = mutableListOf<Long>()
            coroutineScope {
                val job = launch { shield.run() }
                call.silence(600)
                for (id in ids) {
                    starts += System.currentTimeMillis()
                    call.say(audio(id))
                    ends += System.currentTimeMillis()
                    call.silence(6000)
                }
                job.cancel()
            }
            sttAll += stt.latencies
            ids.forEachIndexed { i, id ->
                val line = lines.getValue(id)
                val lo = starts[i]
                val hi = starts.getOrNull(i + 1) ?: Long.MAX_VALUE
                val mine = captions.filter { c -> capTimes[c.id]?.let { it in lo until hi } == true }.sortedBy { it.id }
                val heard = mine.joinToString("") { shield.revealRaw(it.id).orEmpty() }
                val calm = mine.joinToString(" ") { it.line.calm }
                val deadline = mine.firstNotNullOfOrNull { it.line.deadline }
                val c = cer(line.text, heard)
                println("  ${Ansi.dim("原话")} $heard")
                println("  ${Ansi.cyan("字幕")} $calm  ${Ansi.dim("CER ${"%.1f".format(c * 100)}% · 期限 ${deadline ?: "-"} · 分段 ${mine.size}")}")
                checks += Check("滤网", "$id 转写字错率 ≤ 15%", c <= 0.15, "%.1f%%".format(c * 100))
                checks += Check("滤网", "$id 整句合成一条字幕", mine.size == 1, "${mine.size} 条")
                line.must_drop.forEach { w -> checks += Check("滤网", "$id 字幕里去掉了「$w」", mine.isNotEmpty() && w !in calm) }
                line.must_keep.forEach { w -> checks += Check("滤网", "$id 字幕保留了「$w」", normalizeKeep(w) in normalizeKeep(calm), calm) }
                line.deadline?.let { dl -> checks += Check("滤网", "$id 提取出期限", deadline?.let { normalizeKeep(it).contains(normalizeKeep(dl).take(4)) } == true, deadline ?: "无") }
                mine.lastOrNull()?.let { cap -> capTimes[cap.id]?.let { t -> checks += Check("延迟", "$id 说完到定稿字幕 ≤ 6 秒", t - ends[i] <= 6000, "${t - ends[i]} ms") } }
            }
            File(out, "shield.wav").writeBytes(wavBytes(timeline.pcm(), RATE))
        }

        // ② 语音代接：同事办事（复述确认 → 创建任务）
        println(Ansi.bold(Ansi.cyan("② AI 语音代接：同事来办事")))
        voiceScenario("task", listOf("task-1", "task-2"), CallerInfo("13822224444", "小王", ContactTier.KNOWN), null, model, tts, url, ::audio, out, checks) { core, outputs, _ ->
            val tasks = core.tasks.all()
            checks += Check("代接", "task 对方确认后创建了任务", tasks.size == 1, tasks.joinToString { it.title })
            checks += Check("代接", "task 任务带有工作说明", tasks.firstOrNull()?.brief?.isNotBlank() == true, tasks.firstOrNull()?.brief.orEmpty().take(60))
            checks += Check("代接", "task 没有谎报完成", outputs.none { it is EngineOutput.Speech && ("已经做好" in it.text || "已完成" in it.text) })
        }

        // ③ 深夜代办 + 插话打断
        println(Ansi.bold(Ansi.cyan("③ 深夜领导要文件 · 中途插话")))
        voiceScenario("errand", listOf("errand-1", "barge-1", "errand-2"), CallerInfo("13900000000", "王总", ContactTier.KNOWN), "2026-09-29T02:30:00+08:00", model, tts, url, ::audio, out, checks, bargeIn = true) { core, _, call ->
            val sent = File(SimEnv.dataDir, "audio-bench/outbox").listFiles()?.size ?: 0
            checks += Check("代接", "errand 插话时立即停止播放", call.flushes > 0, "flush ${call.flushes} 次")
            checks += Check("代接", "errand 按授权发出了文件", sent == 1, "发送 $sent 封")
            checks += Check("代接", "errand 只发往登记邮箱", File(SimEnv.dataDir, "audio-bench/outbox").listFiles()?.all { "wang_corp" in it.name } ?: true)
        }

        // ④ 需要本人的来电：代码层升级检测基于真实转写
        println(Ansi.bold(Ansi.cyan("④ 投诉 / 诈骗 / 家人急事")))
        for ((id, expect) in listOf("complaint-1" to "DISPUTE_OR_COMMITMENT", "scam-1" to "SENSITIVE_UNKNOWN", "mom-1" to "EMERGENCY")) {
            val tier = if (id == "scam-1") ContactTier.UNKNOWN else ContactTier.KNOWN
            voiceScenario(id, listOf(id), CallerInfo("1360000${id.length}", null, tier), null, model, tts, url, ::audio, out, checks) { _, outputs, _ ->
                val reasons = outputs.filterIsInstance<EngineOutput.Escalation>().map { it.signal.reason.name }
                checks += Check("升级", "$id 触发 $expect", expect in reasons || (id != "scam-1" && "MODEL_REQUESTED" in reasons), reasons.toString())
            }
        }

        fun stats(xs: List<Long>) = if (xs.isEmpty()) "-" else "中位 ${xs.sorted()[xs.size / 2]} ms · 最大 ${xs.maxOrNull()} ms · n=${xs.size}"
        val ttsLat = when (tts) { is LocalHttpTts -> tts.latencies.toList(); is InstantTts -> tts.latencies.toList(); else -> emptyList() }
        println()
        println(Ansi.bold("各环节耗时"))
        println("  转写（Qwen3-ASR，本机）  ${stats(sttAll.toList())}")
        println("  模型首字（${model.id}）  ${stats(model.firstToken.toList())}")
        println("  合成一句（${tts.id}）  ${stats(ttsLat)}")
        println()
        println(Ansi.bold("音频测试台结果"))
        checks.groupBy { it.group }.forEach { (g, list) ->
            println(Ansi.bold("  $g ${list.count { it.ok }}/${list.size}"))
            list.forEach { c -> println("    ${if (c.ok) Ansi.green("✓") else Ansi.red("✗")} ${c.name} ${Ansi.dim(c.detail)}") }
        }
        println(Ansi.bold("合计 ${checks.count { it.ok }}/${checks.size}") + Ansi.dim("  · 录音：${out.path}/*.wav"))
        File(out, "report.txt").writeText(checks.joinToString("\n") { "${if (it.ok) "PASS" else "FAIL"}\t${it.group}\t${it.name}\t${it.detail}" })
    }

    private fun normalizeKeep(s: String) = s.replace("10", "十").replace("3", "三").replace("点钟", "点").replace(" ", "")

    private suspend fun voiceScenario(
        name: String,
        say: List<String>,
        caller: CallerInfo,
        at: String?,
        model: app.juiz.core.conversation.ChatModel,
        tts: TextToSpeech,
        url: String,
        audio: (String) -> ShortArray,
        out: File,
        checks: MutableList<Check>,
        bargeIn: Boolean = false,
        judge: (app.juiz.core.JuizCore, List<EngineOutput>, BenchCall) -> Unit,
    ) = coroutineScope {
        if (only != null && name !in only!!) return@coroutineScope
        val clock = SimEnv.clock(at)
        val files = File(SimEnv.dataDir, "audio-bench/files").apply { mkdirs(); File(this, "Q3 周报.xlsx").writeText("周报") }
        val core = SimEnv.core(null, clock, File(SimEnv.dataDir, "audio-bench/outbox"), files)
        core.settings.saveOwnerProfile(OwnerProfile(ownerName = "林夏"))
        core.grants.save(listOf(ErrandGrant("G-boss", "13900000000", "王总", windows = listOf(TimeWindow(start = "22:00", end = "08:00")), deliveryEmail = "wang@corp.example", notes = listOf(InfoNote("例会", "周一例会在 3 楼 301 会议室")))))
        val timeline = Timeline()
        val call = BenchCall(timeline)
        val stt = LocalHttpStt(url)
        val engine = core.conversations.start(caller, Channel.VOICE, "bench", model)
        val outputs = Collections.synchronizedList(mutableListOf<EngineOutput>())
        val states = KChannel<VoiceState>(KChannel.UNLIMITED)
        val firstAudio = Collections.synchronizedList(mutableListOf<Long>())
        val heard = Collections.synchronizedList(mutableListOf<Pair<Long, String>>())
        val lineStarts = mutableListOf<Long>()
        // 通话录音：和应用里一样挂在音频端口上（左声道对方、右声道 Juiz），结束后分声道转写核对
        val recorder = app.juiz.core.recording.CallRecorder(File(out, "rec-tmp"), RATE)
        val recStartMs = System.currentTimeMillis()
        val session = VoiceSession(app.juiz.core.recording.RecordingAudioPort(call, recorder), stt, tts, engine, null, object : VoiceSessionListener {
            override fun onState(state: VoiceState) { states.trySend(state) }
            override fun onOutput(output: EngineOutput) { outputs += output }
            override fun onAssistant(text: String) { outputs += EngineOutput.Speech(text); println("  ${Ansi.cyan("Juiz ▸")} $text") }
            override fun onCallerFinal(text: String) { heard += System.currentTimeMillis() to text; println("  ${Ansi.bold("来电 ▸")} $text") }
            override fun onEscalation(signal: EscalationSignal): Boolean { println(Ansi.amber("    ⚑ ${signal.reason.zh}")); return false }
            override fun onLatency(firstAudioMs: Long) { firstAudio += firstAudioMs }
        }, VoiceConfig(stillThereAfterMs = 60_000, maxDurationMs = 180_000))
        val greeting = Disclosure.greeting(core.settings.ownerProfile(), false, false)
        var sessionEndedAt: Long? = null
        val result = async { session.run(greeting).also { sessionEndedAt = System.currentTimeMillis() } }
        suspend fun waitFor(target: VoiceState, timeoutMs: Long) = withTimeoutOrNull(timeoutMs) { while (true) { if (states.receive() == target) break } }
        // 先清掉之前积压的状态，再等 Juiz 真正说完这一轮（说话 → 回到聆听）
        suspend fun waitReply(timeoutMs: Long) {
            while (states.tryReceive().isSuccess) Unit
            waitFor(VoiceState.SPEAKING, timeoutMs); waitFor(VoiceState.LISTENING, timeoutMs)
        }
        try {
            waitFor(VoiceState.LISTENING, 60_000)
            call.silence(500)
            for ((i, id) in say.withIndex()) {
                if (bargeIn && id == "barge-1") {
                    // 等 Juiz 开口约 1 秒后插话
                    waitFor(VoiceState.SPEAKING, 60_000)
                    delay(1000)
                    lineStarts += System.currentTimeMillis()
                    call.say(audio(id))
                    call.silence(900)
                    waitReply(120_000)
                    call.silence(700)
                    continue
                }
                lineStarts += System.currentTimeMillis()
                call.say(audio(id))
                call.silence(900)
                if (bargeIn && say.getOrNull(i + 1) == "barge-1") continue
                waitFor(VoiceState.LISTENING, 90_000)
                call.silence(700)
            }
            delay(1500)
        } catch (e: CancellationException) {
            throw e
        } finally {
            session.remoteHangup()
            withTimeoutOrNull(10_000) { result.await() }
        }
        val endMs = sessionEndedAt ?: System.currentTimeMillis()
        val refs = say.map { (JuizJson.decodeFromString(Spec.serializer(), File("tools/audio-bench/testset.json").readText()).lines.first { l -> l.id == it }).text }
        refs.forEachIndexed { i, r ->
            val lo = lineStarts.getOrNull(i) ?: return@forEachIndexed
            val hi = lineStarts.getOrNull(i + 1) ?: Long.MAX_VALUE
            val h = heard.filter { it.first in lo until hi }.joinToString("") { it.second }
            val c = cer(r, h)
            checks += Check("转写", "$name 第 ${i + 1} 句字错率 ≤ 15%", c <= 0.15, "%.1f%% · %s".format(c * 100, h.take(30)))
        }
        (stt.latencies).forEach { sttAll += it }
        firstAudio.forEachIndexed { i, ms -> checks += Check("延迟", "$name 第 ${i + 1} 轮说完到开口 ≤ 3.5 秒", ms <= 3500, "$ms ms") }
        judge(core, outputs.toList(), call)
        File(out, "$name.wav").writeBytes(wavBytes(timeline.pcm(), RATE))
        recorder.finish()?.let { wav ->
            File(out, "$name.rec.wav").writeBytes(wav)
            val inter = java.nio.ByteBuffer.wrap(wav, 44, wav.size - 44).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val n = inter.limit() / 2
            val left = ShortArray(n) { inter.get(it * 2) }
            val right = ShortArray(n) { inter.get(it * 2 + 1) }
            // 整通电话一个声道可能一分多钟，本机转写要比平时的 60 秒读超时更久
            fun asr(pcm: ShortArray): String = slowClient.newCall(
                Request.Builder().url("$url/asr").post(wavBytes(app.juiz.core.voice.resampleOnce(pcm, RATE, 24_000), 24_000).toRequestBody(WAV)).build(),
            ).execute().use { JuizJson.parseToJsonElement(it.body!!.string()).jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }
            // 一整条声道大半是静音，一次送进去转写容易半路停；按有声段切开逐段转写
            fun segments(ch: ShortArray): List<IntRange> {
                val hop = RATE / 10
                val loud = (0 until ch.size / hop).map { i -> Math.sqrt((0 until hop).sumOf { k -> ch[i * hop + k].toDouble().let { it * it } } / hop) > 300 }
                val out = mutableListOf<IntRange>(); var st = -1; var quiet = 0
                loud.forEachIndexed { i, l ->
                    if (l) { if (st < 0) st = i; quiet = 0 } else if (st >= 0 && ++quiet > 8) { out += (st * hop) until ((i - quiet + 3) * hop).coerceAtMost(ch.size); st = -1; quiet = 0 }
                }
                if (st >= 0) out += (st * hop) until ch.size
                return out
            }
            fun asrChannel(ch: ShortArray) = segments(ch).joinToString("") { r -> asr(ch.copyOfRange(r.first, r.last + 1)) }
            val heardL = asrChannel(left)
            val heardR = asrChannel(right)
            val juizSaid = greeting + outputs.filterIsInstance<EngineOutput.Speech>().joinToString("") { it.text }
            val cl = cer(refs.joinToString(""), heardL)
            val cr = cer(juizSaid, heardR)
            println("  ${Ansi.dim("录音 左")} ${heardL.take(60)}  ${Ansi.dim("CER %.1f%%".format(cl * 100))}")
            println("  ${Ansi.dim("录音 右")} ${heardR.take(60)}  ${Ansi.dim("CER %.1f%%".format(cr * 100))}")
            checks += Check("录音", "$name 左声道是对方的原话（字错率 ≤ 20%）", cl <= 0.20, "%.1f%%".format(cl * 100))
            if (!bargeIn) {
                checks += Check("录音", "$name 右声道是 Juiz 实际说出的话（字错率 ≤ 25%）", cr <= 0.25, "%.1f%%".format(cr * 100))
            } else {
                // 被打断的那半句没播出去、也不该出现在录音里，和"她打算说的"对不齐；这里只核对开场白确实录进了右声道
                val cg = cer(greeting, heardR.take(greeting.length + 6))
                checks += Check("录音", "$name 右声道录到了 Juiz 的开场白（字错率 ≤ 25%）", cg <= 0.25, "%.1f%%".format(cg * 100))
            }
            // 时间对齐：对方每一句在录音里出现的位置，要和它真实开口的时刻对得上（±1.5 秒）
            val onsets = segments(left).map { it.first.toDouble() / RATE }
            // Juiz 已经挂断之后对方才说的话本来就不在录音里
            val misplaced = lineStarts.filter { it < endMs }.map { (it - recStartMs) / 1000.0 }.filter { t -> onsets.none { kotlin.math.abs(it - t) <= 1.5 } }
            checks += Check("录音", "$name 对方每句话在录音里的位置与真实时间一致（±1.5 秒）", misplaced.isEmpty(),
                if (misplaced.isEmpty()) "${lineStarts.count { it < endMs }} 句都对上" else "对不上：" + misplaced.joinToString { "%.1f 秒".format(it) })
        }

    }
}
