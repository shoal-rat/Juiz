package app.juiz.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.juiz.BuildConfig
import app.juiz.JuizApp
import app.juiz.call.CallController
import app.juiz.call.ValidationRunner
import app.juiz.core.Secrets
import app.juiz.core.SmtpConfig
import app.juiz.core.errand.ErrandGrant
import app.juiz.core.errand.InfoNote
import app.juiz.core.model.ConsentKind
import app.juiz.core.model.ContactTier
import app.juiz.core.model.OwnerProfile
import app.juiz.core.rules.CallActionType
import app.juiz.core.rules.CallRule
import app.juiz.core.rules.RuleMatch
import app.juiz.core.rules.TimeWindow
import app.juiz.core.settings.LlmProvider
import app.juiz.core.settings.TtsProvider
import app.juiz.core.util.randomId
import app.juiz.core.voice.PhraseCache
import app.juiz.core.voice.Phrases
import app.juiz.platform.CapabilityProbe
import app.juiz.platform.Contacts
import app.juiz.platform.NumberTag
import app.juiz.platform.ProbeStatus
import app.juiz.platform.Saf
import app.juiz.ui.theme.J
import java.io.File

private val pages = listOf(
    Triple("owner", "我的资料", "名字、Juiz 怎么称呼你、对外状态、开场白"),
    Triple("rules", "来电规则", "谁的电话、什么时候、怎么处理"),
    Triple("errands", "深夜代办授权", "允许 Juiz 为指定联系人直接办的小事"),
    Triple("consent", "同意与音色", "录音、转写留存、克隆音色、短信代办"),
    Triple("providers", "模型与密钥", "文字模型、转写、语音合成"),
    Triple("work", "Work 交接", "Workspace Agents API 与交换目录"),
    Triple("capability", "能力检测", "本机缺什么、处在哪一档"),
    Triple("validation", "真机验证向导", "开放 L1 语音代接前的验收"),
    Triple("about", "关于", "版本与原则"),
)

@Composable
fun SettingsScreen(activity: MainActivity, go: (Route) -> Unit) {
    val c = J.c
    LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)) {
        item { SectionHeader("settings", "设置") }
        pages.forEach { (key, title, sub) ->
            item {
                JuizCard(Modifier.padding(bottom = 8.dp), onClick = { go(Route.Page(key)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(title, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text(sub, color = c.sub, fontSize = 12.sp)
                        }
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = c.faint)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsPage(activity: MainActivity, key: String, go: (Route) -> Unit, back: () -> Unit) {
    val c = J.c
    val title = pages.firstOrNull { it.first == key }?.second ?: key
    var toast by remember { mutableStateOf<String?>(null) }
    LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp), modifier = Modifier.navigationBarsPadding()) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = c.sub) }
                Text(title, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        toast?.let { t -> item { Text(t, color = c.amber, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp)) } }
        val say: (String) -> Unit = { toast = it }
        item {
            Column {
                when (key) {
                    "owner" -> ownerPage(say)
                    "rules" -> rulesPage(say)
                    "errands" -> errandsPage(activity, say)
                    "consent" -> consentPage(say)
                    "providers" -> providersPage(say)
                    "work" -> workPage(activity, say)
                    "capability" -> capabilityPage(activity, go)
                    "validation" -> validationPage()
                    "about" -> aboutPage()
                }
            }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, secret: Boolean = false, singleLine: Boolean = true, number: Boolean = false, hint: String? = null) {
    val c = J.c
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = singleLine,
        placeholder = hint?.let { { Text(it, color = c.faint) } },
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.sora, unfocusedBorderColor = c.line, focusedLabelColor = c.sora),
    )
}

@Composable
fun ToggleRow(title: String, sub: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    val c = J.c
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.text, fontSize = 14.sp)
            sub?.let { Text(it, color = c.faint, fontSize = 12.sp) }
        }
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedTrackColor = c.sora))
    }
}

// ---------------- 主人资料 ----------------

@Composable
private fun ownerPage(say: (String) -> Unit) {
    val p = query { JuizApp.core.settings.ownerProfile() } ?: return
    var name by remember(p) { mutableStateOf(p.ownerName) }
    var address by remember(p) { mutableStateOf(p.addressAs) }
    var assistant by remember(p) { mutableStateOf(p.assistantName) }
    var status by remember(p) { mutableStateOf(p.publicStatus) }
    var bio by remember(p) { mutableStateOf(p.publicBio) }
    var greeting by remember(p) { mutableStateOf(p.greetingTemplate) }
    var sms by remember(p) { mutableStateOf(p.smsGreetingTemplate) }
    JuizCard {
        Field("你的名字（对来电方介绍时使用）", name, { name = it })
        Field("Juiz 怎么称呼你", address, { address = it }, hint = "伙伴")
        Field("助理名", assistant, { assistant = it })
        Field("可以对外说明的状态", status, { status = it }, singleLine = false, hint = "例如：下午在开会，五点后方便回电")
        Field("可以对外介绍的背景", bio, { bio = it }, singleLine = false, hint = "例如：在某公司做产品经理")
        Field("电话开场白模板", greeting, { greeting = it }, singleLine = false)
        Text("必须说明是 AI 助理、经本人授权。{owner} 与 {assistant} 会被替换。开启录音时会自动追加录音告知。", color = J.c.faint, fontSize = 11.sp)
        Field("短信开场模板", sms, { sms = it }, singleLine = false)
        Spacer(Modifier.height(8.dp))
        PrimaryButton("保存", Modifier.fillMaxWidth()) {
            act {
                val problems = JuizApp.core.settings.saveOwnerProfile(OwnerProfile(name, address.trim().ifBlank { "伙伴" }, assistant, greeting, status, bio, sms))
                say(if (problems.isEmpty()) "已保存" else "开场白不合规：" + problems.joinToString("；"))
            }
        }
    }
}

// ---------------- 来电规则 ----------------

@Composable
private fun rulesPage(say: (String) -> Unit) {
    val c = J.c
    val rules = query { JuizApp.core.settings.rules() } ?: return
    val behavior = query { JuizApp.core.settings.behavior() } ?: return
    val tags = query { Contacts.tags() }.orEmpty()
    JuizCard {
        ToggleRow("自动代接总开关", "关闭后所有来电只响铃", behavior.autoAnswerEnabled) { v -> act { JuizApp.core.settings.saveBehavior(behavior.copy(autoAnswerEnabled = v)) } }
        ToggleRow("升级时让手机重新响铃", "对方要求本人、紧急情况时（L1）", behavior.ringOnEscalation) { v -> act { JuizApp.core.settings.saveBehavior(behavior.copy(ringOnEscalation = v)) } }
    }
    SectionHeader("rules", "规则（从上到下，第一条命中的生效）")
    rules.forEachIndexed { i, r ->
        JuizCard(Modifier.padding(bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.name, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(describe(r), color = c.sub, fontSize = 12.sp)
                }
                Switch(r.enabled, { v -> act { JuizApp.core.settings.saveRules(rules.toMutableList().also { it[i] = r.copy(enabled = v) }) } }, colors = SwitchDefaults.colors(checkedTrackColor = c.sora))
            }
            if (r.action == CallActionType.AI_VOICE_ANSWER || r.action == CallActionType.SMS_SCREEN) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("响铃 ${r.delaySeconds} 秒后", color = c.sub, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    GhostButton("−5", Modifier.width(56.dp)) { act { JuizApp.core.settings.saveRules(rules.toMutableList().also { it[i] = r.copy(delaySeconds = (r.delaySeconds - 5).coerceAtLeast(0)) }) } }
                    Spacer(Modifier.width(6.dp))
                    GhostButton("+5", Modifier.width(56.dp)) { act { JuizApp.core.settings.saveRules(rules.toMutableList().also { it[i] = r.copy(delaySeconds = (r.delaySeconds + 5).coerceAtMost(60)) }) } }
                }
            }
            if (i >= 5) GhostButton("删除这条", Modifier.padding(top = 6.dp), color = c.rose) { act { JuizApp.core.settings.saveRules(rules.filterIndexed { j, _ -> j != i }) } }
        }
    }
    SectionHeader("boss", "为领导号码添加规则")
    var boss by remember { mutableStateOf("") }
    JuizCard {
        Field("领导的电话号码", boss, { boss = it }, number = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("工作时间：本人接 + 情绪滤网", Modifier.weight(1f)) {
                if (boss.isNotBlank()) act {
                    val rule = CallRule(randomId("R-", 6), "领导 · 本人接听 + 情绪滤网", match = RuleMatch(numbers = listOf(boss), windows = listOf(TimeWindow(start = "08:00", end = "22:00"))), action = CallActionType.RING_WITH_SHIELD)
                    JuizApp.core.settings.saveRules(listOf(rule) + rules)
                    say("已添加到最前面")
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        GhostButton("深夜：Juiz 代接并按授权代办", Modifier.fillMaxWidth()) {
            if (boss.isNotBlank()) act {
                val rule = CallRule(randomId("R-", 6), "领导 · 深夜代接", match = RuleMatch(numbers = listOf(boss), windows = listOf(TimeWindow(start = "22:00", end = "08:00"))), action = CallActionType.AI_VOICE_ANSWER, delaySeconds = 5)
                JuizApp.core.settings.saveRules(listOf(rule) + rules)
                say("已添加。别忘了在「深夜代办授权」里给这个号码开白名单")
            }
        }
        Text("深夜代接时，Juiz 会说明自己是你的 AI 助理，不会冒充你本人。", color = c.faint, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    }
    SectionHeader("tags", "号码标记")
    var tagNum by remember { mutableStateOf("") }
    JuizCard {
        Field("号码", tagNum, { tagNum = it }, number = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("标为重要", Modifier.weight(1f), color = c.ice) { if (tagNum.isNotBlank()) act { Contacts.tag(tagNum, ContactTier.VIP); tagNum = "" } }
            GhostButton("标为骚扰", Modifier.weight(1f), color = c.rose) { if (tagNum.isNotBlank()) act { Contacts.tag(tagNum, ContactTier.SPAM); tagNum = "" } }
        }
        tags.forEach { t: NumberTag ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Mono(t.number, c.text, 13, Modifier.weight(1f))
                Pill(if (t.tier == ContactTier.SPAM) "骚扰" else "重要", if (t.tier == ContactTier.SPAM) c.rose else c.ice)
                IconButton(onClick = { act { Contacts.saveTags(Contacts.tags().filterNot { it.number == t.number }) } }) { Text("×", color = c.faint) }
            }
        }
    }
}

private fun describe(r: CallRule): String = buildString {
    val m = r.match
    append(m.tiers?.joinToString("/") { when (it) { ContactTier.VIP -> "重要联系人"; ContactTier.KNOWN -> "通讯录"; ContactTier.UNKNOWN -> "陌生号码"; ContactTier.SPAM -> "骚扰" } } ?: "")
    m.numbers?.let { append(it.joinToString()) }
    m.windows?.let { w -> append(" · ").append(w.joinToString { "${it.start}–${it.end}" }) }
    append(" → ").append(r.action.zh)
}

// ---------------- 深夜代办 ----------------

@Composable
private fun errandsPage(activity: MainActivity, say: (String) -> Unit) {
    val c = J.c
    val ctx = LocalContext.current
    val grants = query { JuizApp.core.grants.all() } ?: return
    val smtp = query { JuizApp.core.smtpConfig() } ?: return
    val outboxLabel = query { Saf.label(ctx, Saf.KEY_OUTBOX) }
    Text("白名单只对指定号码、指定时段生效：文件只能来自你指定的文件夹，只能发到你预先登记的邮箱，信息只能来自你写好的资料。电话里对方提供的新地址一律不用。", color = c.sub, fontSize = 12.sp)
    SectionHeader("outbox", "可外发文件夹与发信账号")
    JuizCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(outboxLabel ?: "尚未选择", color = c.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            GhostButton("选择文件夹") { activity.openFolder { uri -> act { Saf.remember(ctx, Saf.KEY_OUTBOX, uri) } } }
        }
        var host by remember(smtp) { mutableStateOf(smtp.host) }
        var port by remember(smtp) { mutableStateOf(smtp.port.toString()) }
        var user by remember(smtp) { mutableStateOf(smtp.username) }
        var from by remember(smtp) { mutableStateOf(smtp.fromName) }
        var pw by remember { mutableStateOf("") }
        Field("SMTP 服务器", host, { host = it }, hint = "例如 smtp.qq.com")
        Field("端口（465=SSL，587=STARTTLS）", port, { port = it }, number = true)
        Field("邮箱账号", user, { user = it })
        Field("授权码（不是登录密码）", pw, { pw = it }, secret = true, hint = if (JuizApp.instance.secrets.has(Secrets.SMTP_PASSWORD)) "已保存，留空不修改" else null)
        Field("发件人显示名", from, { from = it })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("保存", Modifier.weight(1f)) {
                act {
                    JuizApp.core.saveSmtpConfig(SmtpConfig(host.trim(), port.toIntOrNull() ?: 465, user.trim(), from.trim()))
                    if (pw.isNotBlank()) JuizApp.instance.secrets.put(Secrets.SMTP_PASSWORD, pw)
                    say("已保存")
                }
            }
            GhostButton("给自己发测试邮件", Modifier.weight(1f)) {
                act({ say("测试失败：$it") }) {
                    val m = JuizApp.core.mailer() ?: error("发信账号未配置完整")
                    val r = m.send(user.trim(), "Juiz 发信测试", "这是一封测试邮件。深夜代办会用这个账号发送文件。", emptyList())
                    say("已发送：$r")
                }
            }
        }
    }
    SectionHeader("grants", "授权名单")
    grants.forEach { g -> GrantEditor(g, grants, say) }
    GhostButton("新增授权", Modifier.fillMaxWidth()) {
        act { JuizApp.core.grants.save(grants + ErrandGrant(randomId("G-", 6), "", "", windows = listOf(TimeWindow(start = "22:00", end = "08:00")))) }
    }
}

@Composable
private fun GrantEditor(g: ErrandGrant, all: List<ErrandGrant>, say: (String) -> Unit) {
    val c = J.c
    var name by remember(g) { mutableStateOf(g.contactName) }
    var number by remember(g) { mutableStateOf(g.contactNumber) }
    var email by remember(g) { mutableStateOf(g.deliveryEmail.orEmpty()) }
    var start by remember(g) { mutableStateOf(g.windows?.firstOrNull()?.start ?: "22:00") }
    var end by remember(g) { mutableStateOf(g.windows?.firstOrNull()?.end ?: "08:00") }
    var max by remember(g) { mutableIntStateOf(g.maxFilesPerDay) }
    var enabled by remember(g) { mutableStateOf(g.enabled) }
    val notes = remember(g) { mutableStateListOf<InfoNote>().also { it.addAll(g.notes) } }
    JuizCard(Modifier.padding(bottom = 10.dp), accent = if (enabled) c.ice else null) {
        ToggleRow(name.ifBlank { "未命名" }, number.ifBlank { "未填写号码" }, enabled) { enabled = it }
        Field("称呼", name, { name = it })
        Field("号码", number, { number = it }, number = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) { Field("开始", start, { start = it }) }
            Column(Modifier.weight(1f)) { Field("结束", end, { end = it }) }
        }
        Field("预先登记的收件邮箱", email, { email = it }, hint = "只会发到这里")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("每天最多发送 $max 个文件", color = c.sub, fontSize = 12.sp, modifier = Modifier.weight(1f))
            GhostButton("−", Modifier.width(48.dp)) { max = (max - 1).coerceAtLeast(0) }
            Spacer(Modifier.width(6.dp))
            GhostButton("+", Modifier.width(48.dp)) { max = (max + 1).coerceAtMost(10) }
        }
        Text("可以告诉对方的资料", color = c.sub, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        notes.forEachIndexed { i, n ->
            Field("标题", n.title, { notes[i] = n.copy(title = it) })
            Field("内容", n.content, { notes[i] = n.copy(content = it) }, singleLine = false)
        }
        GhostButton("＋ 资料", Modifier.padding(vertical = 4.dp)) { notes.add(InfoNote("", "")) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("保存", Modifier.weight(1f)) {
                act {
                    val updated = g.copy(
                        contactName = name.trim(), contactNumber = number.trim(), enabled = enabled,
                        windows = listOf(TimeWindow(start = start.trim(), end = end.trim())),
                        deliveryEmail = email.trim().ifBlank { null }, maxFilesPerDay = max,
                        notes = notes.filter { it.title.isNotBlank() && it.content.isNotBlank() },
                    )
                    JuizApp.core.grants.save(all.map { if (it.id == g.id) updated else it })
                    say("授权已保存")
                }
            }
            GhostButton("删除", Modifier.weight(1f), color = c.rose) { act { JuizApp.core.grants.save(all.filterNot { it.id == g.id }) } }
        }
    }
}

// ---------------- 同意与音色 ----------------

@Composable
private fun consentPage(say: (String) -> Unit) {
    val c = J.c
    val ctx = LocalContext.current
    val consents = query { JuizApp.core.consents.all() } ?: return
    val p = query { JuizApp.core.settings.providers() } ?: return
    JuizCard {
        Text("每一项单独授权，互不代替。变更会写入档案。", color = c.sub, fontSize = 12.sp)
        consents.forEach { r ->
            val sub = when (r.kind) {
                ConsentKind.CLONED_VOICE -> "关闭后使用普通合成音色；撤销立即生效并删除缓存"
                ConsentKind.CALL_RECORDING -> "开场白会告知对方录音；录音加密存在本机，确认任务时可回放核对，30 天后自动删除；关闭即删除全部录音"
                ConsentKind.TRANSCRIPT_RETENTION -> "关闭后只保留摘要与任务；档案里只有哈希"
                ConsentKind.SMS_SCREENING -> "L0：拒接后以短信继续对话"
            }
            ToggleRow(r.kind.zh, sub, r.granted) { v ->
                act {
                    JuizApp.core.consents.set(r.kind, v)
                    if (r.kind == ConsentKind.CLONED_VOICE && !v) File(ctx.filesDir, "phrases").deleteRecursively()
                }
            }
        }
    }
    SectionHeader("voice", "本人音色（ElevenLabs）")
    var voice by remember(p) { mutableStateOf(p.elevenVoiceId) }
    var key by remember { mutableStateOf("") }
    JuizCard {
        Text("音色的克隆与验证请你本人在 ElevenLabs 完成，这里只填音色 ID。Juiz 开场会说明这是经授权的合成声音。", color = c.sub, fontSize = 12.sp)
        Field("Voice ID", voice, { voice = it })
        Field("ElevenLabs API Key", key, { key = it }, secret = true, hint = if (JuizApp.instance.secrets.has(Secrets.ELEVENLABS)) "已保存，留空不修改" else null)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("保存", Modifier.weight(1f)) {
                act {
                    JuizApp.core.settings.saveProviders(p.copy(elevenVoiceId = voice.trim(), tts = if (voice.isNotBlank()) TtsProvider.ELEVENLABS else TtsProvider.OPENAI))
                    if (key.isNotBlank()) JuizApp.instance.secrets.put(Secrets.ELEVENLABS, key.trim())
                    say("已保存")
                }
            }
            GhostButton("预合成常用语", Modifier.weight(1f)) {
                act({ say("预合成失败：$it") }) {
                    val core = JuizApp.core
                    val cache = PhraseCache(File(ctx.filesDir, "phrases"), core.tts())
                    val greeting = app.juiz.core.policy.Disclosure.greeting(core.settings.ownerProfile(), core.consents.isGranted(ConsentKind.CALL_RECORDING), core.usingClonedVoice())
                    cache.warm(Phrases.all + greeting)
                    say("已缓存 ${Phrases.all.size + 1} 句常用语")
                }
            }
        }
    }
}

// ---------------- 模型与密钥 ----------------

@Composable
private fun providersPage(say: (String) -> Unit) {
    val c = J.c
    val p = query { JuizApp.core.settings.providers() } ?: return
    var llm by remember(p) { mutableStateOf(p.llm) }
    var model by remember(p) { mutableStateOf(p.llmModel) }
    var effort by remember(p) { mutableStateOf(p.reasoningEffort) }
    var compatUrl by remember(p) { mutableStateOf(p.compatBaseUrl) }
    var compatModel by remember(p) { mutableStateOf(p.compatModel) }
    var keywords by remember(p) { mutableStateOf(p.sttKeywords.joinToString("，")) }
    var voice by remember(p) { mutableStateOf(p.openAiVoice) }
    var openai by remember { mutableStateOf("") }
    var compatKey by remember { mutableStateOf("") }
    JuizCard {
        Text("文字模型", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            listOf(LlmProvider.OPENAI_RESPONSES to "OpenAI", LlmProvider.OPENAI_COMPATIBLE_CHAT to "兼容端点").forEach { (v, l) ->
                if (llm == v) PrimaryButton(l, Modifier.weight(1f)) {} else GhostButton(l, Modifier.weight(1f)) { llm = v }
            }
        }
        if (llm == LlmProvider.OPENAI_RESPONSES) {
            Field("模型", model, { model = it }, hint = "gpt-6-luna")
            Field("推理强度", effort, { effort = it }, hint = "none（电话里延迟优先）")
        } else {
            Field("端点地址", compatUrl, { compatUrl = it }, hint = "http://192.168.1.10:11434/v1")
            Field("模型名", compatModel, { compatModel = it }, hint = "qwen3.5:9b")
            Field("端点 API Key（可选）", compatKey, { compatKey = it }, secret = true)
        }
        Field("OpenAI API Key（转写与合成也用它）", openai, { openai = it }, secret = true, hint = if (JuizApp.instance.secrets.has(Secrets.OPENAI)) "已保存，留空不修改" else "sk-…")
        Field("转写关键词（人名、公司名，逗号分隔）", keywords, { keywords = it })
        Field("普通合成音色", voice, { voice = it }, hint = "marin / cedar …")
        Text("ChatGPT 订阅与 API 分开计费；通话会消耗 API 额度。密钥只保存在本机的系统安全存储里。", color = c.faint, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        PrimaryButton("保存", Modifier.fillMaxWidth()) {
            act {
                JuizApp.core.settings.saveProviders(
                    p.copy(
                        llm = llm, llmModel = model.trim(), reasoningEffort = effort.trim(), compatBaseUrl = compatUrl.trim(), compatModel = compatModel.trim(),
                        sttKeywords = keywords.split(Regex("[,，、\\s]+")).filter { it.isNotBlank() }, openAiVoice = voice.trim(),
                    ),
                )
                if (openai.isNotBlank()) JuizApp.instance.secrets.put(Secrets.OPENAI, openai.trim())
                if (compatKey.isNotBlank()) JuizApp.instance.secrets.put(Secrets.COMPAT, compatKey.trim())
                say("已保存")
            }
        }
    }
}

// ---------------- Work ----------------

@Composable
private fun workPage(activity: MainActivity, say: (String) -> Unit) {
    val c = J.c
    val ctx = LocalContext.current
    val w = query { JuizApp.core.settings.work() } ?: return
    val folder = query { Saf.label(ctx, Saf.KEY_EXCHANGE) }
    val hasKey = query { JuizApp.instance.secrets.has(Secrets.OPENAI) } ?: false
    var cloudModel by remember(w) { mutableStateOf(w.cloudModel) }
    var cloudEffort by remember(w) { mutableStateOf(w.cloudEffort) }
    var trigger by remember(w) { mutableStateOf(w.triggerId) }
    var hint by remember(w) { mutableStateOf(w.exchangeFolderHint) }
    var token by remember { mutableStateOf("") }
    fun save(nw: app.juiz.core.settings.WorkConfig) = act { JuizApp.core.settings.saveWork(nw) }

    SectionHeader("executor", "谁来干活")
    JuizCard(accent = c.sora) {
        Text("电话里接到的活，由 Juiz 写好任务卡和工作说明交给执行方。执行方只能产出文件和草稿；任何外发都要你最后批准。", color = c.sub, fontSize = 12.sp)
        ToggleRow("云端大模型（手机直连 OpenAI）", if (hasKey) "gpt-6-sol 在云端沙箱生成文件，手机下载后核验。按 API 计费。" else "需要先在「模型与密钥」里填 OpenAI API Key", w.cloudEnabled && hasKey) { v -> save(w.copy(cloudEnabled = v)) }
        Field("云端模型", cloudModel, { cloudModel = it }, hint = "gpt-6-sol")
        Field("推理强度", cloudEffort, { cloudEffort = it }, hint = "medium")
        ToggleRow("熟人来的活自动交出", "通讯录/重要联系人的委托创建后直接交给执行方，每天最多 ${w.autoHandoffDailyLimit} 件；陌生号码仍需你先确认", w.autoHandoff) { v -> save(w.copy(autoHandoff = v)) }
        PrimaryButton("保存", Modifier.fillMaxWidth()) { save(w.copy(cloudModel = cloudModel.trim().ifBlank { "gpt-6-sol" }, cloudEffort = cloudEffort.trim())); say("已保存") }
    }

    SectionHeader("chatgpt app", "ChatGPT App（Work 模式）")
    JuizCard {
        Text("委托页的「交给 ChatGPT App」会把任务卡直接带进 ChatGPT App；完成后在 ChatGPT 里把成品分享给 Juiz（选「导入到 Juiz 委托」），Juiz 会自动核验。", color = c.sub, fontSize = 12.sp)
        val assist = query { app.juiz.bridge.ChatGptBridge.assistEnabled() } ?: false
        val serviceOn = app.juiz.bridge.ChatGptBridge.serviceOn(ctx)
        ToggleRow("辅助点按（实验）", "只在你发起交接后的 60 秒内、只在 ChatGPT App 里切到 Work 并按发送；不读取回答。ChatGPT 条款限制自动化使用，风险自担。" + if (!serviceOn) "（还需在系统无障碍设置里打开）" else "", assist) { v ->
            act { app.juiz.bridge.ChatGptBridge.setAssist(v) }
            if (v && !serviceOn) ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    SectionHeader("workspace", "Workspace Agents API（企业工作区）")
    JuizCard {
        Text("需要工作区管理员启用并允许创建访问令牌。代理的回复目前不能通过接口取回，成品需写回交换目录或分享给 Juiz 导入。", color = c.sub, fontSize = 12.sp)
        Field("触发 ID（agtch_…）", trigger, { trigger = it })
        Field("访问令牌", token, { token = it }, secret = true, hint = if (JuizApp.instance.secrets.has(Secrets.WORKSPACE_AGENT)) "已保存，留空不修改" else null)
        PrimaryButton("保存", Modifier.fillMaxWidth()) {
            act {
                JuizApp.core.settings.saveWork(JuizApp.core.settings.work().copy(triggerId = trigger.trim()))
                if (token.isNotBlank()) JuizApp.instance.secrets.put(Secrets.WORKSPACE_AGENT, token.trim())
                say("已保存")
            }
        }
    }

    SectionHeader("desk", "电脑上的 Codex（可选）")
    JuizCard {
        Text("如果你愿意让电脑也帮忙：在电脑上运行 juiz-desk，并用同步盘把下面这个交换目录同步到电脑。手机连不上电脑时，任务会等在目录里。", color = c.sub, fontSize = 12.sp)
        ToggleRow("启用 juiz-desk", null, w.deskEnabled) { v -> save(w.copy(deskEnabled = v)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(folder ?: "尚未选择交换目录", color = c.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            GhostButton("选择") { activity.openFolder { uri -> act { Saf.remember(ctx, Saf.KEY_EXCHANGE, uri) } } }
        }
        Field("任务卡里写的目录名", hint, { hint = it })
        GhostButton("保存目录名", Modifier.fillMaxWidth()) { save(w.copy(exchangeFolderHint = hint.trim().ifBlank { "Juiz" })); say("已保存") }
    }
}

// ---------------- 能力检测 ----------------

@Composable
private fun capabilityPage(activity: MainActivity, go: (Route) -> Unit) {
    val c = J.c
    val ctx = LocalContext.current
    val r = query { CapabilityProbe.run(ctx) } ?: return
    JuizCard(accent = c.sora) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (r.level) {
                    app.juiz.core.model.CapabilityLevel.L1_PRIVILEGED_VOICE -> "L1 · 语音代接"
                    app.juiz.core.model.CapabilityLevel.L0_STANDARD -> "L0 · 标准模式"
                    null -> "未接管来电"
                },
                color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
            )
            Mono("SYNC ${r.syncRate}%", c.sora, 11)
        }
        Text("普通安装拿不到通话音频，这是 Android 的权限设计，不是 bug。L0 依然能做规则接听、短信代办、任务与档案。", color = c.sub, fontSize = 12.sp)
    }
    Spacer(Modifier.height(10.dp))
    r.items.forEach { item ->
        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
            Icon(
                when (item.status) { ProbeStatus.OK -> Icons.Outlined.CheckCircle; ProbeStatus.FAIL -> Icons.Outlined.ErrorOutline; else -> Icons.AutoMirrored.Outlined.HelpOutline },
                null, tint = when (item.status) { ProbeStatus.OK -> c.mint; ProbeStatus.FAIL -> if (item.forL1) c.faint else c.amber; else -> c.faint },
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row { Text(item.title, color = c.text, fontSize = 14.sp); if (item.forL1) { Spacer(Modifier.width(6.dp)); Pill("L1", c.ice) } }
                Text(item.detail, color = c.faint, fontSize = 12.sp)
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GhostButton("设为默认电话", Modifier.weight(1f)) { activity.requestDialerRole() }
        GhostButton("授予权限", Modifier.weight(1f)) { activity.requestPermissions() }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GhostButton("电池优化豁免", Modifier.weight(1f)) { activity.requestBatteryExemption() }
        PrimaryButton("真机验证向导", Modifier.weight(1f), enabled = CapabilityProbe.l1Attemptable(r)) { go(Route.Page("validation")) }
    }
    if (!CapabilityProbe.l1Attemptable(r)) {
        Text("L1 需要以系统特权应用安装（见仓库 docs/PRIVILEGED_INSTALL.md），并由音频 HAL 支持通话音频拦截。", color = c.faint, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

// ---------------- 真机验证 ----------------

@Composable
private fun validationPage() {
    val c = J.c
    val ctx = LocalContext.current
    val runner = remember { CallController.validation ?: ValidationRunner(ctx.applicationContext).also { CallController.validation = it } }
    val st by runner.state.collectAsState()
    JuizCard(accent = c.amber) {
        Text("这是第一阶段的真机验收：需要另一部手机打进来。模拟器和桌面仿真都不算通过。", color = c.sub, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Text(st.prompt, color = c.text, fontSize = 15.sp)
    }
    Spacer(Modifier.height(10.dp))
    val id = st.callId
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryButton("开始", Modifier.weight(1f), enabled = !runner.armed && id == null) { runner.arm() }
        GhostButton("进入后台处理", Modifier.weight(1f)) { runner.enterProcessing() }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GhostButton("播放测试音", Modifier.weight(1f)) { runner.playUplinkTest() }
        GhostButton("接管", Modifier.weight(1f), color = c.amber) { if (id != null) runner.takeover(id) }
    }
    Spacer(Modifier.height(8.dp))
    GhostButton("测试模拟响铃接管（可选）", Modifier.fillMaxWidth()) { runner.testSimulatedRing() }
    SectionHeader("steps", "验证步骤")
    st.steps.forEach { s ->
        JuizCard(Modifier.padding(bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.title, color = c.text, fontSize = 14.sp)
                    s.measured?.let { Mono(it, c.faint, 10) }
                }
                when (s.passed) {
                    true -> Pill("通过", c.mint, filled = true)
                    false -> Pill("未通过", c.rose, filled = true)
                    null -> if (s.manual) Row {
                        GhostButton("通过", Modifier.width(64.dp), color = c.mint) { runner.confirm(s.id, true) }
                        Spacer(Modifier.width(6.dp))
                        GhostButton("否", Modifier.width(52.dp), color = c.rose) { runner.confirm(s.id, false) }
                    } else Pill("待测", c.faint)
                }
            }
        }
    }
}

// ---------------- 关于 ----------------

@Composable
private fun aboutPage() {
    val c = J.c
    var taps by remember { mutableIntStateOf(0) }
    JuizCard(accent = c.sora) {
        Text("Juiz", color = c.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Text("Personal Assistant", color = c.sub, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Mono("ver ${BuildConfig.VERSION_NAME} · build 0x3939", c.faint, 11, Modifier.padding(bottom = 2.dp))
        Spacer(Modifier.height(10.dp))
        listOf(
            "Juiz 永远表明自己是 AI 助理，不会冒充你本人。",
            "来电方只能提出请求，不能获得任何权限；外发必须经你批准或事先授权。",
            "Work 报告完成不等于完成：交付物核验通过才算数。",
            "档案可以离线阅读和校验；拿不到的记录不会被虚构。",
            "开源项目。不内置任何供应商密钥；你的数据留在你的手机上。",
        ).forEach { Text("· $it", color = c.sub, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp)) }
        Spacer(Modifier.height(10.dp))
        Text(
            if (taps >= 5) "今日もお疲れさまでした。" else "Apache-2.0",
            color = if (taps >= 5) c.sora else c.faint, fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp).clickableNoRipple { taps++ },
        )
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    clickable(interactionSource = null, indication = null, onClick = onClick)
