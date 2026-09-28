package app.juiz.ui

import android.media.MediaDataSource
import android.media.MediaPlayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.juiz.JuizApp
import app.juiz.core.model.Channel
import app.juiz.core.model.ConsentKind
import app.juiz.core.recording.OpenedRecording
import app.juiz.core.recording.RecordingCheck
import app.juiz.ui.theme.J
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * "原始来电"：确认任务之前，随时回放那通电话的录音（左声道对方、右声道 Juiz），或者翻看短信/转写原文。
 * 回放前会用指纹或锁屏密码确认是本人；每次回放都核对档案里记下的哈希，并把"已回放"记入档案。
 */
@Composable
fun OriginalCallSection(activity: MainActivity, conversationId: String?) {
    val c = J.c
    if (conversationId == null) return
    val info = query(conversationId) {
        val core = JuizApp.core
        Triple(core.conversations.get(conversationId), core.recordings?.exists(conversationId) == true, core.conversations.turns(conversationId))
    } ?: return
    val (conv, hasRecording, turns) = info
    if (conv == null) return
    var unlocked by remember { mutableStateOf(false) }
    var showText by remember { mutableStateOf(false) }

    SectionHeader("original", if (conv.channel == Channel.SMS) "短信原文" else "原始来电")
    JuizCard {
        when {
            hasRecording && unlocked -> RecordingPlayer(conversationId)
            hasRecording -> {
                Text("确认之前，可以先听一遍那通电话，核对对方的原话。", color = c.sub, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                PrimaryButton("听原通话录音", Modifier.fillMaxWidth(), icon = Icons.Outlined.PlayArrow) {
                    activity.confirmOwner("回放通话录音") { unlocked = true }
                }
            }
            conv.channel != Channel.SMS && !JuizApp.core.consents.isGranted(ConsentKind.CALL_RECORDING) ->
                Text("这通电话没有录音。在「设置 → 同意与音色」打开「通话录音」后，代接的电话会加密录下，在这里回放核对。", color = c.faint, fontSize = 12.sp)
            conv.channel != Channel.SMS -> Text("这通电话没有录音（可能已超过保留期，或接通时间太短）。", color = c.faint, fontSize = 12.sp)
        }
        if (turns.isNotEmpty()) {
            if (hasRecording || conv.channel != Channel.SMS) Spacer(Modifier.height(10.dp))
            if (conv.channel == Channel.SMS || showText) {
                turns.forEach { t ->
                    val caller = t.speaker == "caller"
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Mono(if (caller) "对方" else "Juiz", if (caller) c.amber else c.sora, 11, Modifier.width(40.dp))
                        Text(t.text, color = if (caller) c.text else c.sub, fontSize = 13.sp)
                    }
                }
            } else {
                GhostButton("看文字原文（${turns.size} 句）", Modifier.fillMaxWidth()) { showText = true }
            }
        }
    }
}

@Composable
private fun RecordingPlayer(conversationId: String) {
    val c = J.c
    var rec by remember { mutableStateOf<OpenedRecording?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableFloatStateOf(0f) }
    val player = remember { MediaPlayer() }
    DisposableEffect(Unit) { onDispose { player.release() } }
    LaunchedEffect(conversationId) {
        val r = withContext(Dispatchers.IO) { runCatching { JuizApp.core.recordings?.open(conversationId) } }
        r.onSuccess { opened ->
            if (opened == null) { error = "录音已不存在"; return@onSuccess }
            runCatching {
                player.setDataSource(BytesDataSource(opened.wav))
                player.setOnCompletionListener { playing = false; pos = 0f }
                player.prepare()
            }.onFailure { error = "无法播放：${it.message}" }
            rec = opened
        }.onFailure { error = "录音无法解密：${it.message}" }
    }
    LaunchedEffect(playing) {
        while (playing) {
            pos = player.currentPosition / 1000f
            delay(200)
        }
    }
    error?.let { Text(it, color = c.rose, fontSize = 13.sp); return }
    val r = rec ?: run { Text("正在解密录音…", color = c.faint, fontSize = 12.sp); return }

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {
            if (playing) { player.pause(); playing = false } else { player.start(); playing = true }
        }) { Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (playing) "暂停" else "播放", tint = c.sora) }
        Slider(
            value = pos.coerceIn(0f, r.seconds.toFloat()),
            onValueChange = { pos = it; player.seekTo((it * 1000).toInt()) },
            valueRange = 0f..r.seconds.toFloat().coerceAtLeast(0.1f),
            colors = SliderDefaults.colors(thumbColor = c.sora, activeTrackColor = c.sora, inactiveTrackColor = c.line),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Mono("${mmss(pos.toDouble())} / ${mmss(r.seconds)}", c.faint, 11)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val ok = r.check == RecordingCheck.VERIFIED
        Icon(if (ok) Icons.Outlined.Verified else Icons.Outlined.WarningAmber, null, tint = if (ok) c.mint else c.rose, modifier = Modifier.size(16.dp))
        Text(r.check.zh, color = if (ok) c.mint else c.rose, fontSize = 12.sp)
    }
    Column(Modifier.padding(top = 4.dp)) {
        Text("左声道是对方，右声道是 Juiz。录音加密存在本机，到期自动删除。", color = c.faint, fontSize = 11.sp)
    }
}

private fun mmss(s: Double): String {
    val t = s.toInt().coerceAtLeast(0)
    return "%d:%02d".format(t / 60, t % 60)
}

/** 解密后的 WAV 只在内存里交给播放器，不落盘。 */
private class BytesDataSource(private val data: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= data.size) return -1
        val n = minOf(size.toLong(), data.size - position).toInt()
        System.arraycopy(data, position.toInt(), buffer, offset, n)
        return n
    }
    override fun getSize(): Long = data.size.toLong()
    override fun close() {}
}
