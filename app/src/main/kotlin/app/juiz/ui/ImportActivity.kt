package app.juiz.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.juiz.JuizApp
import app.juiz.core.model.TaskStatus
import app.juiz.core.work.LocalExchangeFolder
import app.juiz.core.work.ManifestBuilder
import app.juiz.ui.theme.J
import app.juiz.ui.theme.JuizTheme
import java.io.File

/**
 * 从 ChatGPT App（或任何应用）分享成品回 Juiz：挂到一个进行中的委托上，
 * 由 Juiz 自己计算哈希、生成清单、核验，然后可以起草外发、等你批准。
 */
class ImportActivity : ComponentActivity() {
    private data class Incoming(val name: String, val bytes: ByteArray)

    private fun readIncoming(): List<Incoming> {
        val out = mutableListOf<Incoming>()
        val uris = when (intent.action) {
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        }
        for (u in uris) {
            val name = contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                ?: u.lastPathSegment ?: "file"
            contentResolver.openInputStream(u)?.use { out += Incoming(name.replace('/', '_'), it.readBytes()) }
        }
        intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { out += Incoming("ChatGPT-回复.md", it.toByteArray()) }
        return out
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val incoming = runCatching { readIncoming() }.getOrDefault(emptyList())
        setContent {
            JuizTheme {
                val c = J.c
                var msg by remember { mutableStateOf<String?>(null) }
                val tasks = query { JuizApp.core.tasks.all().filter { !it.status.terminal } }
                LazyColumn(
                    Modifier.fillMaxSize().nightGrid(c).windowInsetsPadding(WindowInsets.safeDrawing),
                    contentPadding = PaddingValues(18.dp),
                ) {
                    item {
                        SectionHeader("import", "导入到哪个委托？")
                        Text("收到 ${incoming.size} 个文件：${incoming.joinToString { it.name }}", color = c.sub, fontSize = 13.sp)
                        msg?.let { Text(it, color = c.amber, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
                        Spacer(Modifier.height(10.dp))
                    }
                    if (tasks != null && tasks.isEmpty()) item { EmptyState("没有进行中的委托") }
                    items(tasks.orEmpty()) { t ->
                        JuizCard(Modifier.padding(bottom = 8.dp), onClick = {
                            act({ msg = it }) {
                                val core = JuizApp.core
                                val base = File(filesDir, "cloud").apply { mkdirs() }
                                val dir = File(base, t.id)
                                File(dir, "out").mkdirs()
                                incoming.forEach { File(dir, "out/${it.name}").writeBytes(it.bytes) }
                                ManifestBuilder.write(dir, ManifestBuilder.build(dir, t.id, ok = true, summaryFallback = "由本人从其他应用导入"))
                                if (t.status == TaskStatus.PENDING_CONFIRMATION) core.handoff().confirmManualHandoff(t.id)
                                val r = core.handoff().verify(t.id, LocalExchangeFolder(base))
                                msg = if (r.ok) "已导入并核验：${r.verified.joinToString()}" else "已导入，但核验未通过：${r.issues.joinToString("；")}"
                                if (r.ok) runOnUiThread { finish() }
                            }
                        }) {
                            Column {
                                Text(t.title, color = c.text, fontSize = 15.sp)
                                Mono("${t.id} · ${t.status.zh}", c.faint, 11)
                            }
                        }
                    }
                }
            }
        }
    }
}
