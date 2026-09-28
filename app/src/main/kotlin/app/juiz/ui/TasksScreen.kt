package app.juiz.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.juiz.JuizApp
import app.juiz.core.model.ActionStatus
import app.juiz.core.model.OutboundAction
import app.juiz.core.model.Task
import app.juiz.core.model.TaskStatus
import app.juiz.core.util.JuizJson
import app.juiz.core.work.HandoffResult
import app.juiz.core.work.HandoffRoute
import app.juiz.core.work.VerificationReport
import app.juiz.platform.Saf
import app.juiz.ui.theme.J

@Composable
fun statusColor(s: TaskStatus): Color = when (s) {
    TaskStatus.PENDING_CONFIRMATION, TaskStatus.AWAITING_APPROVAL -> J.c.amber
    TaskStatus.HANDED_OFF, TaskStatus.IN_PROGRESS -> J.c.sora
    TaskStatus.NEEDS_VERIFICATION -> J.c.ice
    TaskStatus.COMPLETED -> J.c.mint
    TaskStatus.FAILED -> J.c.rose
    TaskStatus.CANCELLED -> J.c.faint
}

@Composable
fun TasksScreen(go: (Route) -> Unit) {
    val c = J.c
    var filter by remember { mutableStateOf<TaskStatus?>(null) }
    val tasks = query(filter) { JuizApp.core.tasks.all().filter { filter == null || it.status == filter } }
    LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)) {
        item { SectionHeader("requests", "委托") }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterPill("全部", filter == null) { filter = null }
                listOf(TaskStatus.PENDING_CONFIRMATION, TaskStatus.IN_PROGRESS, TaskStatus.AWAITING_APPROVAL, TaskStatus.NEEDS_VERIFICATION, TaskStatus.COMPLETED, TaskStatus.FAILED)
                    .forEach { s -> FilterPill(s.zh, filter == s) { filter = s } }
            }
        }
        if (tasks != null && tasks.isEmpty()) item { EmptyState("暂无委托", "本日も異常なし") }
        items(tasks.orEmpty(), key = { it.id }) { t ->
            JuizCard(Modifier.padding(bottom = 8.dp), onClick = { go(Route.TaskDetail(t.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(t.status.zh, statusColor(t.status), filled = true)
                    Spacer(Modifier.width(8.dp))
                    Mono(t.id, c.faint, 11)
                    Spacer(Modifier.weight(1f))
                    Mono(fmtTime(t.createdAt), c.faint, 10)
                }
                Spacer(Modifier.height(8.dp))
                Text(t.title, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    listOfNotNull(t.contactName ?: t.contactNumber, t.due?.let { "截止 $it" }).joinToString(" · "),
                    color = c.sub, fontSize = 12.sp,
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun FilterPill(text: String, on: Boolean, onClick: () -> Unit) {
    val c = J.c
    androidx.compose.material3.Surface(
        onClick = onClick, shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
        color = if (on) c.sora.copy(alpha = 0.16f) else c.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (on) c.sora else c.line),
    ) { Text(text, color = if (on) c.sora else c.sub, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
}

private data class DetailData(val task: Task, val actions: List<OutboundAction>, val card: String, val routes: List<HandoffRoute>, val exchange: Boolean)

@Composable
fun TaskDetailScreen(activity: MainActivity, id: String, back: () -> Unit) {
    val ctx = LocalContext.current
    val c = J.c
    var message by remember { mutableStateOf<String?>(null) }
    var noteDialog by remember { mutableStateOf(false) }
    var shareConfirm by remember { mutableStateOf(false) }
    var showCard by remember { mutableStateOf(false) }
    val d = query(id) {
        val core = JuizApp.core
        val t = core.tasks.get(id)!!
        val h = core.handoff()
        DetailData(t, core.tasks.actionsForTask(id), h.cardFor(t), h.availableRoutes, Saf.exchangeFolder(ctx) != null)
    } ?: return
    val t = d.task
    val err: (String) -> Unit = { message = it }

    LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp), modifier = Modifier.navigationBarsPadding()) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = c.sub) }
                Mono(t.id, c.faint, 12)
                Spacer(Modifier.weight(1f))
                Pill(t.status.zh, statusColor(t.status), filled = true)
            }
        }
        item {
            Text(t.title, color = c.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(12.dp))
            JuizCard {
                KeyValue("请求方", listOfNotNull(t.contactName, t.contactNumber).joinToString(" "))
                KeyValue("请求原意", t.request)
                t.deliverable?.let { KeyValue("交付要求", it) }
                t.due?.let { KeyValue("截止", it) }
                t.confirmedFields.forEach { (k, v) -> KeyValue("✓ $k", v) }
                t.handoffRoute?.let { KeyValue("交接方式", HandoffRoute.valueOf(it).zh) }
                t.workRunId?.let { KeyValue("运行 ID", it, mono = true) }
                t.note?.let { KeyValue("备注", it) }
                KeyValue("创建", fmtTime(t.createdAt), mono = true)
            }
        }
        message?.let { m -> item { Text(m, color = c.amber, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) } }

        item { SectionHeader("handoff", "交给 ChatGPT Work") }
        item {
            JuizCard {
                when (t.status) {
                    TaskStatus.PENDING_CONFIRMATION -> {
                        Text("确认这个请求值得办，再交给 Work。交接只携带任务卡里的信息。", color = c.sub, fontSize = 13.sp)
                        Spacer(Modifier.height(10.dp))
                        if (HandoffRoute.WORKSPACE_AGENT in d.routes) {
                            PrimaryButton("触发 Workspace Agent", Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Outlined.Send) {
                                activity.confirmOwner("确认交给 Work") {
                                    act(err) {
                                        when (val r = JuizApp.core.handoff().handoff(id, HandoffRoute.WORKSPACE_AGENT)) {
                                            is HandoffResult.Failed -> message = r.reason
                                            is HandoffResult.Triggered -> message = "已触发。代理的回复无法通过接口取回，交付物请写入交换目录。"
                                            else -> Unit
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        GhostButton("分享任务卡给 ChatGPT（半自动）", Modifier.fillMaxWidth(), icon = Icons.Outlined.Share) {
                            act(err) {
                                val r = JuizApp.core.handoff().handoff(id, HandoffRoute.MANUAL_SHARE)
                                if (r is HandoffResult.ShareNeeded) {
                                    activity.runOnUiThread { ctx.shareText("Juiz 任务卡 ${t.id}", r.cardText); shareConfirm = true }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GhostButton("本人已处理", Modifier.weight(1f), color = c.mint) { noteDialog = true }
                            GhostButton("取消", Modifier.weight(1f), color = c.rose) { act(err) { JuizApp.core.tasks.transition(id, TaskStatus.CANCELLED, "主人取消") } }
                        }
                    }
                    TaskStatus.HANDED_OFF, TaskStatus.IN_PROGRESS, TaskStatus.AWAITING_APPROVAL, TaskStatus.NEEDS_VERIFICATION -> {
                        Text(
                            when (t.status) {
                                TaskStatus.NEEDS_VERIFICATION -> "Work 报告已完成。只有交付物核验通过（或你亲自核对）后，才会标记完成。"
                                TaskStatus.AWAITING_APPROVAL -> "Work 在等待外部操作或审批，请到 ChatGPT 中处理。"
                                else -> "已交给 Work。完成后会在交换目录写回交付物和 result.json。"
                            },
                            color = c.sub, fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        t.workConversationUrl?.let { url ->
                            GhostButton("在 ChatGPT 中打开", Modifier.fillMaxWidth()) { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                            Spacer(Modifier.height(8.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (t.workRunId != null) GhostButton("刷新状态", Modifier.weight(1f)) { act(err) { JuizApp.core.handoff().poll(id) } }
                            PrimaryButton("核验交付物", Modifier.weight(1f), icon = Icons.Outlined.Verified, enabled = d.exchange) {
                                act(err) {
                                    val folder = Saf.exchangeFolder(ctx) ?: error("请先在设置里选择交换目录")
                                    val r = JuizApp.core.handoff().verify(id, folder)
                                    message = if (r.ok) "核验通过：${r.verified.joinToString()}" else "未通过：${r.issues.joinToString("；")}"
                                }
                            }
                        }
                        if (!d.exchange) Text("需要先在「设置 → Work 交接」里选择交换目录", color = c.faint, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                        Spacer(Modifier.height(8.dp))
                        GhostButton("我已亲自核对，标记完成", Modifier.fillMaxWidth(), color = c.mint) { noteDialog = true }
                    }
                    TaskStatus.FAILED -> PrimaryButton("重新交接", Modifier.fillMaxWidth()) { act(err) { JuizApp.core.tasks.transition(id, TaskStatus.PENDING_CONFIRMATION, "主人要求重试") } }
                    else -> Text("任务已结束。", color = c.sub, fontSize = 13.sp)
                }
                t.verification?.let { v ->
                    val r = runCatching { JuizJson.decodeFromString(VerificationReport.serializer(), v) }.getOrNull()
                    if (r != null) {
                        Spacer(Modifier.height(10.dp))
                        Divider()
                        Spacer(Modifier.height(8.dp))
                        Text(if (r.ok) "上次核验：通过" else "上次核验：未通过", color = if (r.ok) c.mint else c.amber, fontSize = 13.sp)
                        r.issues.forEach { Text("· $it", color = c.sub, fontSize = 12.sp) }
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(10.dp))
            TextButtonMono(if (showCard) "收起任务卡" else "查看任务卡") { showCard = !showCard }
            if (showCard) JuizCard { Text(d.card, color = c.sub, fontSize = 12.sp, fontFamily = J.mono) }
        }
        if (d.actions.isNotEmpty()) {
            item { SectionHeader("outbound", "外发与审批") }
            items(d.actions) { a -> ActionCard(activity, a, err) }
        }
        item { Spacer(Modifier.height(30.dp)) }
    }

    if (noteDialog) NoteDialog(onDismiss = { noteDialog = false }) { note ->
        noteDialog = false
        act(err) { JuizApp.core.tasks.completeByOwner(id, note) }
    }
    if (shareConfirm) AlertDialog(
        onDismissRequest = { shareConfirm = false },
        title = { Text("已经交给 ChatGPT 了吗？") },
        text = { Text("确认后任务状态会变为「已交接」。这是半自动路径：完成后请把交付物放进交换目录或亲自核对。") },
        confirmButton = { TextButton({ shareConfirm = false; act(err) { JuizApp.core.handoff().confirmManualHandoff(id) } }) { Text("已交给") } },
        dismissButton = { TextButton({ shareConfirm = false }) { Text("还没有") } },
        containerColor = c.surfaceHi,
    )
}

@Composable
private fun TextButtonMono(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Mono(text, J.c.sora, 12) }
}

@Composable
fun NoteDialog(title: String = "核对说明", hint: String = "例如：已回电，对方问题已解决", onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, placeholder = { Text(hint) }) },
        confirmButton = { TextButton({ if (text.isNotBlank()) onOk(text) }) { Text("确定") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
        containerColor = J.c.surfaceHi,
    )
}

@Composable
private fun ActionCard(activity: MainActivity, a: OutboundAction, err: (String) -> Unit) {
    val c = J.c
    var resolve by remember { mutableStateOf(false) }
    JuizCard(Modifier.padding(bottom = 8.dp), accent = if (a.status == ActionStatus.PROPOSED) c.amber else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${a.kind.name} → ${a.target}", color = c.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Pill(a.status.name, if (a.status == ActionStatus.DONE) c.mint else if (a.status == ActionStatus.UNKNOWN) c.rose else c.amber)
        }
        a.content.subject?.let { Text(it, color = c.text, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
        Text(a.content.body, color = c.sub, fontSize = 13.sp)
        a.content.attachments.forEach { Mono("📎 ${it.name} · ${it.bytes} B · ${it.sha256.take(12)}", c.faint, 10) }
        Mono("内容哈希 ${a.contentHash.take(16)}", c.faint, 10, Modifier.padding(top = 4.dp))
        a.receipt?.let { Mono("回执 $it", c.faint, 10) }
        when (a.status) {
            ActionStatus.PROPOSED -> {
                Spacer(Modifier.height(8.dp))
                PrimaryButton("核对无误，批准", Modifier.fillMaxWidth(), color = c.amber) {
                    activity.confirmOwner("批准外发") { act(err) { JuizApp.core.tasks.approve(a.id, a.contentHash) } }
                }
            }
            ActionStatus.UNKNOWN -> {
                Text("发送过程中断，无法确定是否已发出。请到邮箱「已发送」里核实：", color = c.rose, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("已发出", Modifier.weight(1f), color = c.mint) { act(err) { JuizApp.core.tasks.resolveUnknown(a.id, true, "在已发送中找到") } }
                    GhostButton("没发出", Modifier.weight(1f), color = c.rose) { resolve = true }
                }
            }
            else -> Unit
        }
    }
    if (resolve) NoteDialog("没有发出", "说明情况", { resolve = false }) { n -> resolve = false; act(err) { JuizApp.core.tasks.resolveUnknown(a.id, false, n) } }
}
