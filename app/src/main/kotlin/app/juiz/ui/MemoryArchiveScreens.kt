package app.juiz.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import app.juiz.JuizApp
import app.juiz.core.archive.BackupCrypto
import app.juiz.core.archive.ExportBundle
import app.juiz.core.model.FactSource
import app.juiz.core.model.FactStatus
import app.juiz.ui.theme.J
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId

@Composable
fun MemoryScreen(activity: MainActivity) {
    val c = J.c
    val ctx = LocalContext.current
    val facts = query { JuizApp.core.memory.all() }
    var adding by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)) {
        item {
            SectionHeader("memory", "记忆") {
                IconButton(onClick = { ctx.shareText("Juiz 记忆导出", JuizApp.core.memory.exportJson()) }) { Icon(Icons.Outlined.Share, "导出", tint = c.sub) }
                IconButton(onClick = { adding = true }) { Icon(Icons.Outlined.Add, "添加", tint = c.sora) }
            }
            Text("手机只保存必要、可控的记录。来电方提供的信息先放在「待确认」，你确认后才生效；只有打开「通话可引用」的记录会被 Juiz 在电话里提到。", color = c.sub, fontSize = 12.sp)
        }
        val pending = facts.orEmpty().filter { it.status == FactStatus.PENDING }
        if (pending.isNotEmpty()) {
            item { SectionHeader("pending", "待你确认") }
            items(pending, key = { it.id }) { f ->
                JuizCard(Modifier.padding(bottom = 8.dp), accent = c.amber) {
                    Text(f.subject, color = c.sub, fontSize = 12.sp)
                    Text(f.text, color = c.text, fontSize = 14.sp)
                    Mono("来源：${sourceZh(f.source)} · ${fmtTime(f.createdAt)}", c.faint, 10)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton("确认", Modifier.weight(1f), color = c.mint) { act { JuizApp.core.memory.confirm(f.id) } }
                        GhostButton("驳回", Modifier.weight(1f), color = c.rose) { act { JuizApp.core.memory.reject(f.id) } }
                    }
                }
            }
        }
        item { SectionHeader("confirmed", "已确认") }
        val confirmed = facts.orEmpty().filter { it.status == FactStatus.CONFIRMED }
        if (facts != null && confirmed.isEmpty()) item { EmptyState("还没有记录", "・・・") }
        items(confirmed, key = { it.id }) { f ->
            JuizCard(Modifier.padding(bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.subject, color = c.sub, fontSize = 12.sp)
                        Text(f.text, color = c.text, fontSize = 14.sp)
                    }
                    IconButton(onClick = { act { JuizApp.core.memory.delete(f.id) } }) { Icon(Icons.Outlined.Delete, "删除", tint = c.faint) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("通话可引用", color = c.sub, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Switch(
                        f.shareableInCalls, { v -> act { JuizApp.core.memory.update(f.id, f.subject, f.text, f.status, v) } },
                        colors = SwitchDefaults.colors(checkedTrackColor = c.sora),
                    )
                }
                Mono("来源：${sourceZh(f.source)} · 更新 ${fmtTime(f.updatedAt)}", c.faint, 10)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    if (adding) {
        var subject by remember { mutableStateOf("") }
        var text by remember { mutableStateOf("") }
        var share by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("添加记录") },
            text = {
                Column {
                    OutlinedTextField(subject, { subject = it }, label = { Text("关于谁/什么") })
                    OutlinedTextField(text, { text = it }, label = { Text("内容") })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(share, { share = it })
                        Spacer(Modifier.width(8.dp))
                        Text("通话中可引用", fontSize = 13.sp)
                    }
                }
            },
            confirmButton = { TextButton({ adding = false; act { JuizApp.core.memory.add(subject.ifBlank { "备注" }, text, FactSource.OWNER, null, share) } }) { Text("保存") } },
            dismissButton = { TextButton({ adding = false }) { Text("取消") } },
            containerColor = c.surfaceHi,
        )
    }
}

private fun sourceZh(s: FactSource) = when (s) {
    FactSource.OWNER -> "本人"
    FactSource.CALLER -> "来电方"
    FactSource.WORK -> "Work"
    FactSource.SYSTEM -> "系统"
}

@Composable
fun ArchiveScreen(activity: MainActivity) {
    val c = J.c
    val ctx = LocalContext.current
    var verifyMsg by remember { mutableStateOf<String?>(null) }
    var backupDialog by remember { mutableStateOf(false) }
    val events = query { JuizApp.core.archive.recent(150) }
    val head = query { JuizApp.core.archive.head() }
    LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)) {
        item { SectionHeader("archive", "档案") }
        item {
            JuizCard(accent = c.sora) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.VerifiedUser, null, tint = c.sora)
                    Spacer(Modifier.width(8.dp))
                    Text("追加式哈希链", color = c.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    TextButton({ act { verifyMsg = JuizApp.core.archive.verify().message } }) { Text("校验", color = c.sora) }
                }
                verifyMsg?.let { Text(it, color = if ("完整" in it) c.mint else c.rose, fontSize = 13.sp) }
                head?.let {
                    Spacer(Modifier.height(6.dp))
                    Text("链头锚点（建议定期抄到手机以外的地方）", color = c.sub, fontSize = 12.sp)
                    Mono("#${it.seq}  ${it.hash}", c.text, 11)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("导出档案包", Modifier.weight(1f), icon = Icons.Outlined.Share) {
                        act {
                            val core = JuizApp.core
                            val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
                            val f = File(dir, "juiz-archive-${System.currentTimeMillis()}.zip")
                            f.outputStream().use { ExportBundle.write(it, core.archive.all(), emptyList(), core.settings.ownerProfile().ownerName, ZoneId.systemDefault(), Instant.now()) }
                            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
                            activity.runOnUiThread {
                                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "导出档案包"))
                            }
                        }
                    }
                    GhostButton("加密备份", Modifier.weight(1f), icon = Icons.Outlined.Lock) { backupDialog = true }
                }
                Text("档案包里有 timeline.html，不需要 Juiz 或 ChatGPT 也能阅读和校验。", color = c.faint, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        item { SectionHeader("timeline", "时间线") }
        items(events.orEmpty(), key = { it.seq }) { e ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Mono("#${e.seq}", c.faint, 10, Modifier.width(44.dp))
                Column(Modifier.weight(1f)) {
                    Text(eventLabel(e.type), color = c.text, fontSize = 13.sp)
                    Text(e.payload.entries.take(3).joinToString(" · ") { (k, v) -> "$k=${v.str().take(40)}" }, color = c.sub, fontSize = 11.sp, maxLines = 2)
                }
                Mono(fmtTime(e.at), c.faint, 10)
            }
            Divider()
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    if (backupDialog) {
        var pass by remember { mutableStateOf("") }
        var pass2 by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { backupDialog = false },
            title = { Text("加密备份") },
            text = {
                Column {
                    Text("AES-256-GCM 加密，口令经 PBKDF2 派生。口令丢失则无法恢复，Juiz 不保存它。", fontSize = 12.sp)
                    OutlinedTextField(pass, { pass = it }, label = { Text("口令（至少 8 位）") }, visualTransformation = PasswordVisualTransformation())
                    OutlinedTextField(pass2, { pass2 = it }, label = { Text("再输一次") }, visualTransformation = PasswordVisualTransformation())
                }
            },
            confirmButton = {
                TextButton({
                    if (pass.length >= 8 && pass == pass2) {
                        backupDialog = false
                        val pw = pass.toCharArray()
                        activity.createFile("juiz-backup-${System.currentTimeMillis()}.juizbk") { uri ->
                            act {
                                val core = JuizApp.core
                                val zip = ByteArrayOutputStream().also { ExportBundle.write(it, core.archive.all(), emptyList(), core.settings.ownerProfile().ownerName, ZoneId.systemDefault(), Instant.now()) }.toByteArray()
                                val blob = BackupCrypto.encrypt(zip, pw)
                                ctx.contentResolver.openOutputStream(uri)?.use { it.write(blob) }
                                core.archive.append("backup.written", null) { put("bytes", blob.size); put("anchor_seq", core.archive.head()?.seq ?: 0) }
                            }
                        }
                    }
                }) { Text("选择位置并备份") }
            },
            dismissButton = { TextButton({ backupDialog = false }) { Text("取消") } },
            containerColor = c.surfaceHi,
        )
    }
}

fun eventLabel(type: String): String = when (type) {
    "conversation.started" -> "会话开始"
    "conversation.ended" -> "会话结束"
    "turn" -> "对话（仅哈希）"
    "tool.called" -> "工具调用"
    "tool.rejected" -> "工具被拒"
    "task.created" -> "创建任务"
    "task.status" -> "任务状态"
    "task.handoff" -> "交接 Work"
    "task.verification" -> "交付物核验"
    "action.proposed" -> "外发提议"
    "action.approved" -> "审批"
    "action.status" -> "外发状态"
    "memory.added", "memory.updated", "memory.deleted" -> "记忆变更"
    "consent.changed" -> "同意变更"
    "call.decision" -> "来电决策"
    "message.taken" -> "留言"
    "errand.file" -> "代办：发送文件"
    "errand.grant.active" -> "代办授权生效"
    "shield.digest" -> "去情绪摘要"
    "sms.sent", "sms.received" -> "短信"
    "backup.written" -> "加密备份"
    "digest.sent" -> "晨间简报"
    else -> type
}
