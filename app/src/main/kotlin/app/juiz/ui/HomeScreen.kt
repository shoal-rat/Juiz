package app.juiz.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallReceived
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.juiz.JuizApp
import app.juiz.R
import app.juiz.call.CallRegistry
import app.juiz.core.Secrets
import app.juiz.core.model.ActionStatus
import app.juiz.core.model.CapabilityLevel
import app.juiz.core.model.TaskStatus
import app.juiz.platform.CapabilityProbe
import app.juiz.ui.theme.J
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
fun fmtTime(ms: Long): String = timeFmt.format(Instant.ofEpochMilli(ms))

private data class HomeData(
    val level: CapabilityLevel?,
    val sync: Int,
    val dialer: Boolean,
    val permsOk: Boolean,
    val hasModelKey: Boolean,
    val ownerSet: Boolean,
    val pending: Int,
    val verify: Int,
    val unknownSends: Int,
    val approvals: Int,
    val convos: List<app.juiz.core.conversation.ConversationSummary>,
    val previews: Map<String, String>,
    val errands: List<app.juiz.core.archive.ArchiveEvent>,
    val digests: List<app.juiz.core.archive.ArchiveEvent>,
    val messages: List<app.juiz.core.archive.ArchiveEvent>,
    val ownerName: String,
    val addressAs: String,
)

@Composable
fun HomeScreen(activity: MainActivity, go: (Route) -> Unit) {
    val ctx = LocalContext.current
    val c = J.c
    val data = query {
        val core = JuizApp.core
        val probe = CapabilityProbe.run(ctx)
        val recent = core.archive.recent(400)
        val dayAgo = System.currentTimeMillis() - 86_400_000L
        HomeData(
            level = probe.level, sync = probe.syncRate, dialer = probe.defaultDialer,
            permsOk = listOf("contacts", "notify", "sms").all { id -> probe.items.firstOrNull { it.id == id }?.status == app.juiz.platform.ProbeStatus.OK },
            hasModelKey = core.secrets.get(Secrets.OPENAI) != null || core.settings.providers().compatBaseUrl.isNotBlank(),
            ownerSet = core.settings.ownerProfile().ownerName != "机主",
            pending = core.tasks.withStatus(TaskStatus.PENDING_CONFIRMATION).size,
            verify = core.tasks.withStatus(TaskStatus.NEEDS_VERIFICATION).size,
            unknownSends = core.tasks.actionsWithStatus(ActionStatus.UNKNOWN).size,
            approvals = core.tasks.actionsWithStatus(ActionStatus.PROPOSED).size,
            convos = core.conversations.recent(12),
            previews = core.tasks.all().let { all -> core.conversations.recent(12).associate { it.id to core.conversations.preview(it, all) } },
            errands = recent.filter { it.type == "errand.file" && it.at > dayAgo },
            digests = recent.filter { it.type == "shield.digest" }.take(5),
            messages = recent.filter { it.type == "message.taken" }.take(8),
            ownerName = core.settings.ownerProfile().ownerName,
            addressAs = core.settings.ownerProfile().addressAs,
        )
    }
    val calls by CallRegistry.calls.collectAsState()
    var taps by remember { mutableIntStateOf(0) }
    var peek by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(taps) {
        if (taps >= 7) { taps = 0; peek = "……呼んだ？" }
    }
    LaunchedEffect(Unit) {
        val h = LocalTime.now().hour
        if (h in 2..4 && !nightPeekShown) { nightPeekShown = true; delay(900); peek = "……もう寝よ？" }
    }
    LaunchedEffect(peek) { if (peek != null) { delay(2600); peek = null } }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Image(painterResource(R.drawable.juiz_mark), null, Modifier.size(26.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Juiz", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = c.text, letterSpacing = 1.sp,
                        modifier = Modifier.clickable(remember { MutableInteractionSource() }, null) { taps++ },
                    )
                    Spacer(Modifier.width(8.dp))
                    Mono("PERSONAL ASSISTANT", c.faint, 9)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { go(Route.Settings) }) { Icon(Icons.Outlined.Settings, "设置", tint = c.sub) }
                }
            }
            if (data != null && (!data.dialer || !data.permsOk || !data.hasModelKey || !data.ownerSet)) {
                item { SetupCard(activity, data, go) }
            }
            item { StatusCard(data, calls.firstOrNull()) }
            if (data != null) {
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("待确认", data.pending, c.amber, Modifier.weight(1f)) { go(Route.Tasks) }
                        StatTile("待核实", data.verify, c.ice, Modifier.weight(1f)) { go(Route.Tasks) }
                        StatTile("待审批", data.approvals + data.unknownSends, c.rose, Modifier.weight(1f)) { go(Route.Tasks) }
                    }
                }
                if (data.errands.isNotEmpty()) {
                    item { SectionHeader("while you slept", "昨夜代办") }
                    item {
                        JuizCard(accent = c.ice) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.DarkMode, null, tint = c.ice, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("你休息时，Juiz 按授权办好了这些事", color = c.sub, fontSize = 13.sp)
                            }
                            data.errands.forEach { e ->
                                Spacer(Modifier.height(8.dp))
                                Text("向 ${e.payload["to"].str()} 发送《${e.payload["file"].str()}》", color = c.text, fontSize = 14.sp)
                                Mono(fmtTime(e.at) + " · " + e.payload["outcome"].str().take(40), c.faint, 10)
                            }
                        }
                    }
                }
                if (data.digests.isNotEmpty()) {
                    item { SectionHeader("filtered", "去情绪摘要") }
                    items(data.digests) { e -> DigestCard(e) }
                }
                if (data.messages.isNotEmpty()) {
                    item { SectionHeader("messages", "留言") }
                    items(data.messages) { e ->
                        JuizCard(Modifier.padding(bottom = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconBadge(Icons.Outlined.Mail, if (e.payload["urgency"].str() == "high") c.amber else c.sora)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(e.payload["summary"].str(), color = c.text, fontSize = 14.sp)
                                    Mono("${e.payload["name"].str().ifEmpty { e.payload["from"].str() }} · ${fmtTime(e.at)}", c.faint, 10)
                                }
                            }
                        }
                    }
                }
                item { SectionHeader("recent", "最近的会话") }
                if (data.convos.isEmpty()) item { EmptyState("还没有代接过来电", "本日も異常なし") }
                items(data.convos) { cv ->
                    JuizCard(Modifier.padding(bottom = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(
                                when {
                                    cv.channel.name == "SMS" -> Icons.Outlined.Sms
                                    cv.mode.contains("shield") -> Icons.Outlined.Shield
                                    else -> Icons.AutoMirrored.Outlined.CallReceived
                                },
                                c.sora,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(cv.contactName ?: cv.number, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Text(data.previews[cv.id] ?: cv.summary ?: "（无事项）", color = c.sub, fontSize = 13.sp, maxLines = 2)
                            }
                            Mono(fmtTime(cv.startedAt), c.faint, 10)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
        MascotPeek(peek != null, peek ?: "", Modifier.align(Alignment.BottomEnd).padding(end = 8.dp))
    }
}

private var nightPeekShown = false

fun kotlinx.serialization.json.JsonElement?.str(): String = when (this) {
    null -> ""
    is kotlinx.serialization.json.JsonPrimitive -> this.content
    else -> this.toString()
}

@Composable
private fun SetupCard(activity: MainActivity, d: HomeData, go: (Route) -> Unit) {
    val c = J.c
    JuizCard(Modifier.padding(top = 10.dp), padding = PaddingValues(0.dp)) {
        Box(Modifier.fillMaxWidth().height(150.dp)) {
            Image(painterResource(R.drawable.hero_night), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, c.surface))))
            Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                Mono("SETUP", c.sora, 10)
                Text("把来电交给 Juiz", color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Column(Modifier.padding(16.dp)) {
            SetupRow("设为默认电话应用", "Juiz 需要负责接听与通话界面", d.dialer) { activity.requestDialerRole() }
            SetupRow("授予通讯录、通知、短信权限", "区分熟人与陌生号码；短信代办", d.permsOk, doneLabel = "去授予") { activity.requestPermissions() }
            SetupRow("填写我的资料", "你的称呼、可对外说明的状态", d.ownerSet) { go(Route.Page("owner")) }
            SetupRow("配置模型与密钥", "OpenAI 或兼容端点（例如本机 ollama）", d.hasModelKey) { go(Route.Page("providers")) }
        }
    }
}

@Composable
private fun SetupRow(title: String, sub: String, done: Boolean, doneLabel: String = "去设置", onClick: () -> Unit) {
    val c = J.c
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable(enabled = !done, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(if (done) c.mint else c.amber)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = c.text, fontSize = 14.sp)
            Text(sub, color = c.faint, fontSize = 12.sp)
        }
        if (!done) Text(doneLabel, color = c.sora, fontSize = 13.sp)
    }
}

@Composable
private fun StatusCard(d: HomeData?, live: app.juiz.call.CallUi?) {
    val c = J.c
    val liveLine = live?.let {
        when (it.aiMode) {
            app.juiz.call.AiMode.AI_VOICE -> "我正在接听${it.caller.displayName ?: it.caller.number}的电话，你随时可以接管。"
            app.juiz.call.AiMode.SHIELD -> "难听的话我挡住了，你只看要点就好。"
            else -> null
        }
    }
    val ctx = BuddyContext(
        hour = java.time.LocalTime.now().hour,
        pendingTasks = d?.pending ?: 0,
        needsVerify = d?.verify ?: 0,
        errandsLastNight = d?.errands?.size ?: 0,
        recentDigest = d?.digests?.any { System.currentTimeMillis() - it.at < 3 * 3_600_000L } == true,
        dialerReady = d?.dialer ?: true,
        liveCall = liveLine,
        ownerName = d?.ownerName.orEmpty(),
        addressAs = d?.addressAs ?: "伙伴",
    )
    val brain = remember { runCatching { app.juiz.core.buddy.BuddyBrain(JuizApp.core.chatModel()) }.getOrNull() }
    var chat by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf<String?>(null) }
    JuizCard(Modifier.padding(top = 12.dp), accent = c.sora) {
        Box(Modifier.fillMaxWidth()) {
            if (d != null) JuizBuddy(ctx, height = 156.dp, brain = brain, chatText = sent)
            if (d != null) Mono("SYNC ${d.sync}%", c.faint, 9, Modifier.align(Alignment.TopEnd))
        }
        if (brain != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                androidx.compose.foundation.text.BasicTextField(
                    chat, { chat = it }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = c.text, fontSize = 14.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(c.sora),
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(c.surfaceHi).padding(horizontal = 12.dp, vertical = 10.dp),
                    decorationBox = { inner -> if (chat.isEmpty()) Text("和 Juiz 说句话…", color = c.faint, fontSize = 14.sp); inner() },
                )
                Spacer(Modifier.width(8.dp))
                Text("说", color = if (chat.isBlank()) c.faint else c.sora, fontSize = 15.sp, modifier = Modifier.clickable(enabled = chat.isNotBlank()) { sent = chat.trim(); chat = "" }.padding(8.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (live != null) c.amber else if (d?.level != null) c.mint else c.faint, pulse = live != null)
            Spacer(Modifier.width(6.dp))
            Text(
                when {
                    live != null -> "${live.caller.displayName ?: live.caller.number} · ${live.stateLabel}"
                    d?.level == null -> "尚未接管来电"
                    else -> "待命中"
                },
                color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            Text(
                when (d?.level) {
                    CapabilityLevel.L1_PRIVILEGED_VOICE -> "L1 语音代接"
                    CapabilityLevel.L0_STANDARD -> "L0 规则接听 + 短信代办"
                    null -> "未设为默认电话"
                },
                color = c.sub, fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun StatTile(label: String, n: Int, color: Color, modifier: Modifier, onClick: () -> Unit) {
    val c = J.c
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(c.surface).clickable(onClick = onClick).padding(12.dp),
    ) {
        Column {
            Text(n.toString(), fontFamily = J.mono, fontSize = 24.sp, color = if (n > 0) color else c.faint, fontWeight = FontWeight.Medium)
            Text(label, color = c.sub, fontSize = 12.sp)
        }
    }
}

@Composable
fun DigestCard(e: app.juiz.core.archive.ArchiveEvent) {
    val c = J.c
    val p = e.payload
    val tone = p["tone_level"].str().toIntOrNull() ?: 0
    JuizCard(Modifier.padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p["caller"].str(), color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            repeat(3) { i -> Box(Modifier.padding(start = 3.dp).size(6.dp).clip(RoundedCornerShape(3.dp)).background(if (i < tone) c.amber else c.line)) }
        }
        Spacer(Modifier.height(6.dp))
        Text(p["summary"].str(), color = c.sub, fontSize = 13.sp)
        val items = (p["action_items"] as? kotlinx.serialization.json.JsonArray).orEmpty()
        items.forEach { it ->
            val o = it as? kotlinx.serialization.json.JsonObject ?: return@forEach
            Text("☐ ${o["what"].str()}${o["due"].str().takeIf { d -> d.isNotEmpty() && d != "null" }?.let { d -> " · $d" } ?: ""}", color = c.text, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Mono("${fmtTime(e.at)} · 已过滤 ${p["filtered_count"].str()} 处情绪化表达", c.faint, 10, Modifier.padding(top = 6.dp))
    }
}
