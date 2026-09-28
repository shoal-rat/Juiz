package app.juiz.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.juiz.call.AiMode
import app.juiz.call.CallController
import app.juiz.call.CallRegistry
import app.juiz.call.CallUi
import app.juiz.core.voice.ShieldMode
import app.juiz.core.voice.VoiceState
import app.juiz.platform.SystemCallApi
import app.juiz.ui.theme.J
import app.juiz.ui.theme.JuizTheme
import kotlinx.coroutines.delay

class InCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JuizTheme {
                val calls by CallRegistry.calls.collectAsState()
                LaunchedEffect(calls.isEmpty()) {
                    if (calls.isEmpty()) { delay(600); finish() }
                }
                val ui = calls.firstOrNull { it.state == Call.STATE_RINGING || it.state == SystemCallApi.STATE_SIMULATED_RINGING } ?: calls.lastOrNull()
                if (ui != null) InCallScreen(ui) else Box(Modifier.fillMaxSize().background(J.c.bg))
            }
        }
    }
}

@Composable
private fun InCallScreen(ui: CallUi) {
    val c = J.c
    var keypad by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val audio by CallRegistry.audioState.collectAsState()
    val ringing = ui.state == Call.STATE_RINGING || ui.state == SystemCallApi.STATE_SIMULATED_RINGING
    val shield = ui.aiMode == AiMode.SHIELD

    Box(Modifier.fillMaxSize().nightGrid(c)) {
        HexBarrier(active = shield && ui.intensity >= 2, color = c.amber, modifier = Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(18.dp))
            Mono(
                when {
                    ringing -> "INCOMING"
                    ui.aiMode == AiMode.AI_VOICE -> "JUIZ ON THE LINE"
                    shield -> "SHIELD ACTIVE"
                    else -> "ON CALL"
                },
                if (shield) c.amber else c.sora, 11,
            )
            Spacer(Modifier.height(8.dp))
            Text(ui.caller.displayName ?: ui.caller.number, color = c.text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            if (ui.caller.displayName != null) Mono(ui.caller.number, c.sub, 13)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ui.stateLabel, color = c.sub, fontSize = 14.sp)
                ui.connectedAt?.let { t ->
                    val s = (now - t) / 1000
                    Spacer(Modifier.width(8.dp))
                    Mono("%02d:%02d".format(s / 60, s % 60), c.sub, 13)
                }
            }
            ui.decision?.let { d ->
                if (ringing && ui.countdown != null) {
                    Spacer(Modifier.height(8.dp))
                    Pill("${ui.countdown} 秒后 ${d.action.zh}", c.sora, filled = true)
                }
                d.downgradeReason?.let { Text(it, color = c.faint, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
            }
            ui.notice?.let { Text(it, color = c.amber, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp)) }
            ui.escalations.lastOrNull()?.let { s ->
                Spacer(Modifier.height(8.dp))
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(c.rose.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text("需要你：${s.reason.zh} · ${s.detail}", color = c.rose, fontSize = 13.sp)
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                when {
                    ui.aiMode == AiMode.AI_VOICE -> Transcript(ui)
                    shield -> Captions(ui)
                    else -> Orb(if (ringing) OrbMood.LISTENING else OrbMood.IDLE, size = 150.dp)
                }
            }

            if (shield) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                    ShieldMode.entries.forEach { m ->
                        val on = ui.shieldMode == m
                        Box(
                            Modifier.clip(RoundedCornerShape(50)).background(if (on) c.amber.copy(alpha = 0.18f) else c.surface)
                                .clickable { CallController.setShieldMode(ui.id, m) }.padding(horizontal = 12.dp, vertical = 7.dp),
                        ) { Text(m.zh, color = if (on) c.amber else c.sub, fontSize = 12.sp) }
                    }
                }
            }

            when {
                ringing -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundButton(Icons.Outlined.CallEnd, "拒接", c.rose) { CallController.reject(ui.id) }
                    if (ui.state == Call.STATE_RINGING) {
                        RoundButton(Icons.Outlined.SupportAgent, "Juiz 代接", c.sora) { CallController.aiAnswerNow(ui.id) }
                        RoundButton(Icons.Outlined.Sms, "短信代办", c.ice) { CallController.smsScreenNow(ui.id) }
                    }
                    RoundButton(Icons.Outlined.Call, "接听", c.mint) { CallController.answer(ui.id) }
                }
                ui.aiMode == AiMode.AI_VOICE -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundButton(Icons.Outlined.PanTool, "立即接管", c.amber, big = true) { CallController.takeover(ui.id) }
                    RoundButton(Icons.Outlined.CallEnd, "挂断", c.rose) { CallController.hangup(ui.id) }
                }
                else -> Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        val muted = audio?.isMuted == true
                        RoundButton(if (muted) Icons.Outlined.MicOff else Icons.Outlined.Mic, if (muted) "取消静音" else "静音", c.ice, toggled = muted) { CallController.setMuted(!muted) }
                        val speaker = audio?.route == CallAudioState.ROUTE_SPEAKER
                        RoundButton(Icons.AutoMirrored.Outlined.VolumeUp, "免提", c.ice, toggled = speaker) { CallController.setSpeaker(!speaker) }
                        RoundButton(Icons.Outlined.Dialpad, "键盘", c.ice) { keypad = !keypad }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        RoundButton(Icons.Outlined.Pause, if (ui.state == Call.STATE_HOLDING) "恢复" else "保持", c.ice, toggled = ui.state == Call.STATE_HOLDING) { CallController.toggleHold(ui.id) }
                        RoundButton(Icons.Outlined.Shield, if (shield) "关闭滤网" else "情绪滤网", c.amber, toggled = shield) { CallController.toggleShield(ui.id) }
                        RoundButton(Icons.Outlined.CallEnd, "挂断", c.rose) { CallController.hangup(ui.id) }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
        if (keypad) Keypad(Modifier.align(Alignment.BottomCenter)) { d -> CallController.dtmf(ui.id, d) }
    }
}

@Composable
private fun Transcript(ui: CallUi) {
    val c = J.c
    val state = rememberLazyListState()
    LaunchedEffect(ui.lines.size) { if (ui.lines.isNotEmpty()) state.animateScrollToItem(ui.lines.lastIndex) }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            StatusDot(c.sora, pulse = ui.voiceState == VoiceState.SPEAKING || ui.voiceState == VoiceState.LISTENING)
            Mono(
                when (ui.voiceState) {
                    VoiceState.GREETING -> "开场披露中"
                    VoiceState.LISTENING -> "正在听"
                    VoiceState.THINKING -> "思考中・・・"
                    VoiceState.SPEAKING -> "Juiz 正在说"
                    VoiceState.ENDED -> "已结束"
                    null -> "准备中"
                },
                c.sub, 11,
            )
        }
        LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
            items(ui.lines) { l ->
                val juiz = l.speaker == "juiz"
                val system = l.speaker == "system"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (juiz) Arrangement.End else Arrangement.Start) {
                    Box(
                        Modifier.clip(RoundedCornerShape(12.dp))
                            .background(if (system) Color.Transparent else if (juiz) c.sora.copy(alpha = 0.14f) else c.surfaceHi)
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    ) {
                        Text(l.text, color = if (system) c.faint else c.text, fontSize = if (system) 11.sp else 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Captions(ui: CallUi) {
    val c = J.c
    var revealed by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val state = rememberLazyListState()
    LaunchedEffect(ui.captions.size) { if (ui.captions.isNotEmpty()) state.animateScrollToItem(ui.captions.lastIndex) }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            Text("对方情绪", color = c.sub, fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
            repeat(3) { i -> Box(Modifier.padding(end = 3.dp).size(width = 18.dp, height = 5.dp).clip(RoundedCornerShape(3.dp)).background(if (i < ui.intensity) c.amber else c.line)) }
            Spacer(Modifier.weight(1f))
            Mono("辱骂与宣泄已过滤", c.faint, 10)
        }
        LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            items(ui.captions, key = { it.id }) { cap ->
                JuizCard(padding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                    Text(cap.line.calm, color = c.text, fontSize = 15.sp)
                    cap.line.requests.forEach { Text("☐ $it", color = c.sora, fontSize = 13.sp) }
                    cap.line.deadline?.let { Mono("期限 $it", c.amber, 11) }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        if (!cap.final) Mono("整理中・・・", c.faint, 10)
                        Spacer(Modifier.weight(1f))
                        val raw = if (cap.id in revealed) CallController.shieldRunners[ui.id]?.revealRaw(cap.id) else null
                        if (raw == null) Mono("查看原话", c.faint, 10, Modifier.clickable { revealed = revealed + cap.id })
                    }
                    if (cap.id in revealed) CallController.shieldRunners[ui.id]?.revealRaw(cap.id)?.let { Text(it, color = c.faint, fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, color: Color, toggled: Boolean = false, big: Boolean = false, onClick: () -> Unit) {
    val c = J.c
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(if (big) 76.dp else 64.dp).clip(CircleShape)
                .background(if (toggled) color.copy(alpha = 0.35f) else color.copy(alpha = 0.16f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = color, modifier = Modifier.size(if (big) 32.dp else 26.dp)) }
        Spacer(Modifier.height(6.dp))
        Text(label, color = c.sub, fontSize = 12.sp)
    }
}

@Composable
private fun Keypad(modifier: Modifier = Modifier, onDigit: (Char) -> Unit) {
    val c = J.c
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(c.surface).padding(18.dp)) {
        listOf("123", "456", "789", "*0#").forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { d ->
                    Box(Modifier.size(64.dp).clip(CircleShape).background(c.surfaceHi).clickable { onDigit(d) }, contentAlignment = Alignment.Center) {
                        Text(d.toString(), color = c.text, fontSize = 24.sp, fontFamily = J.mono)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

/** 默认拨号应用必须提供的拨号界面。 */
class DialerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initial = intent?.data?.schemeSpecificPart.orEmpty()
        setContent {
            JuizTheme {
                var number by remember { mutableStateOf(initial) }
                val c = J.c
                Column(
                    Modifier.fillMaxSize().nightGrid(c).windowInsetsPadding(WindowInsets.safeDrawing).padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Mono("DIAL", c.sora, 11)
                    Spacer(Modifier.weight(1f))
                    Text(number.ifEmpty { " " }, color = c.text, fontSize = 34.sp, fontFamily = J.mono)
                    Spacer(Modifier.height(24.dp))
                    listOf("123", "456", "789", "*0#").forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            row.forEach { d ->
                                Box(Modifier.size(72.dp).clip(CircleShape).background(c.surface).clickable { number += d }, contentAlignment = Alignment.Center) {
                                    Text(d.toString(), color = c.text, fontSize = 26.sp, fontFamily = J.mono)
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.size(64.dp))
                        Box(
                            Modifier.size(72.dp).clip(CircleShape).background(c.mint).clickable(enabled = number.isNotBlank()) { place(number) },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Outlined.Call, "拨打", tint = c.bgDeep, modifier = Modifier.size(30.dp)) }
                        Box(Modifier.size(64.dp).clickable { number = number.dropLast(1) }, contentAlignment = Alignment.Center) { Text("⌫", color = c.sub, fontSize = 22.sp) }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    private fun place(number: String) {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 1)
            return
        }
        getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), Bundle())
        finish()
    }
}
