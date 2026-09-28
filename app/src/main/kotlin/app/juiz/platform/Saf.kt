package app.juiz.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.juiz.JuizApp
import app.juiz.core.errand.CatalogFile
import app.juiz.core.errand.FileCatalog
import app.juiz.core.util.sha256Hex
import app.juiz.core.work.ExchangeFolder
import java.io.InputStream

/**
 * 系统文件选择器（SAF）授权的目录：
 * - 交换目录：Work 写回交付物和 result.json 的地方（通常由云盘同步到本机，是否可行需按账号实测）；
 * - 可外发文件夹：深夜代办时允许发出的文件。
 */
object Saf {
    const val KEY_EXCHANGE = "saf_exchange"
    const val KEY_OUTBOX = "saf_outbox"

    fun remember(context: Context, key: String, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        JuizApp.core.settings.putRaw(key, uri.toString())
    }

    fun uri(key: String): Uri? = JuizApp.core.settings.raw(key)?.let(Uri::parse)

    fun exchangeFolder(context: Context): ExchangeFolder? = uri(KEY_EXCHANGE)?.let { SafFolder(context, it) }

    fun outboxCatalog(context: Context): FileCatalog? = uri(KEY_OUTBOX)?.let { SafCatalog(context, it) }

    fun label(context: Context, key: String): String? =
        uri(key)?.let { DocumentFile.fromTreeUri(context, it)?.name }
}

private class SafFolder(private val context: Context, private val tree: Uri) : ExchangeFolder {
    private fun find(rel: String): DocumentFile? {
        var dir = DocumentFile.fromTreeUri(context, tree) ?: return null
        val parts = rel.split('/').filter { it.isNotEmpty() }
        for ((i, p) in parts.withIndex()) {
            if (p == "..") return null
            val child = dir.findFile(p) ?: return null
            if (i == parts.lastIndex) return child
            if (!child.isDirectory) return null
            dir = child
        }
        return null
    }

    override fun readText(relPath: String): String? = open(relPath)?.use { it.readBytes().toString(Charsets.UTF_8) }

    override fun open(relPath: String): InputStream? =
        find(relPath)?.takeIf { it.isFile }?.let { context.contentResolver.openInputStream(it.uri) }

    override fun write(relPath: String, bytes: ByteArray): Boolean = runCatching {
        var dir = DocumentFile.fromTreeUri(context, tree) ?: return false
        val parts = relPath.split('/').filter { it.isNotEmpty() && it != ".." }
        for (p in parts.dropLast(1)) dir = dir.findFile(p)?.takeIf { it.isDirectory } ?: dir.createDirectory(p) ?: return false
        val name = parts.last()
        val file = dir.findFile(name) ?: dir.createFile("application/octet-stream", name) ?: return false
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(bytes) } ?: return false
        true
    }.getOrDefault(false)
}

private class SafCatalog(private val context: Context, private val tree: Uri) : FileCatalog {
    private fun files() = DocumentFile.fromTreeUri(context, tree)?.listFiles()?.filter { it.isFile && it.name?.startsWith(".") == false }.orEmpty()

    override fun list(): List<CatalogFile> = files().mapNotNull { f ->
        val bytes = context.contentResolver.openInputStream(f.uri)?.use { it.readBytes() } ?: return@mapNotNull null
        CatalogFile(f.name!!, bytes.size.toLong(), sha256Hex(bytes))
    }.sortedBy { it.name }

    override fun read(name: String): ByteArray? =
        files().firstOrNull { it.name == name }?.let { f -> context.contentResolver.openInputStream(f.uri)?.use { it.readBytes() } }
}
