package app.juiz.core.errand

import app.juiz.core.tasks.DefinitelyNotSent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 极简 SMTP 客户端（不引入邮件库）：SSL（465）或 STARTTLS（587），AUTH LOGIN，multipart/mixed 附件。
 * 适用于 QQ 邮箱、163、企业邮箱等支持"授权码"的服务。凭证由主人在自己手机上填写，保存在系统安全存储里。
 *
 * 失败语义：DATA 被服务器接受之前的任何失败都确定"没发出去"（DefinitelyNotSent）；
 * 之后的失败无法确定，交由上层标记为 UNKNOWN，不自动重试。
 */
class SmtpMailer(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val from: String = username,
    private val fromName: String = "Juiz",
    private val startTls: Boolean = port == 587,
    private val timeoutMs: Int = 20_000,
    /** 仅供测试：连接本地的明文假服务器。 */
    private val plaintextForTests: Boolean = false,
) : Mailer {
    override val id: String get() = "smtp:$host"

    override suspend fun send(to: String, subject: String, body: String, attachments: List<MailAttachment>): String =
        withContext(Dispatchers.IO) {
            var socket: Socket = if (startTls || plaintextForTests) Socket() else SSLSocketFactory.getDefault().createSocket()
            try {
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.soTimeout = timeoutMs
                var io = Conversation(socket)
                io.expect(220)
                io.cmd("EHLO juiz.local", 250)
                if (startTls) {
                    io.cmd("STARTTLS", 220)
                    socket = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket, host, port, true) as SSLSocket
                    (socket as SSLSocket).startHandshake()
                    io = Conversation(socket)
                    io.cmd("EHLO juiz.local", 250)
                }
                io.cmd("AUTH LOGIN", 334)
                io.cmd(b64(username), 334)
                io.cmd(b64(password), 235, redact = true)
                io.cmd("MAIL FROM:<$from>", 250)
                io.cmd("RCPT TO:<$to>", 250, 251)
                io.cmd("DATA", 354)
                val message = MimeBuilder.build(from, fromName, to, subject, body, attachments)
                io.raw(dotStuff(message) + "\r\n.\r\n")
                // 从这里开始，服务器可能已经接受了邮件
                val receipt = try {
                    io.expect(250)
                } catch (e: Exception) {
                    throw IllegalStateException("邮件已提交但未收到确认：${e.message}")
                }
                runCatching { io.cmd("QUIT", 221) }
                receipt
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Exception) {
                throw DefinitelyNotSent("SMTP 发送失败：${e.message}")
            } finally {
                runCatching { socket.close() }
            }
        }

    private class Conversation(socket: Socket) {
        private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        private val out: OutputStream = socket.getOutputStream()

        fun raw(s: String) {
            out.write(s.toByteArray(Charsets.UTF_8))
            out.flush()
        }

        fun cmd(line: String, vararg ok: Int, redact: Boolean = false): String {
            raw("$line\r\n")
            return expect(*ok, context = if (redact) "(凭证)" else line.take(30))
        }

        fun expect(vararg ok: Int, context: String = ""): String {
            val sb = StringBuilder()
            var code: Int
            while (true) {
                val l = reader.readLine() ?: throw java.io.IOException("连接被关闭 $context")
                sb.append(l).append('\n')
                code = l.take(3).toIntOrNull() ?: throw java.io.IOException("无法解析的响应：$l")
                if (l.length < 4 || l[3] != '-') break
            }
            if (code !in ok) throw java.io.IOException("$context → ${sb.toString().trim()}")
            return sb.toString().trim()
        }
    }

    companion object {
        private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

        /** SMTP 透明性：以 . 开头的行要再加一个 . */
        fun dotStuff(s: String): String = s.split("\r\n").joinToString("\r\n") { if (it.startsWith(".")) ".$it" else it }
    }
}

object MimeBuilder {
    private fun encodedWord(s: String) = "=?UTF-8?B?" + Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8)) + "?="

    private fun wrap76(b64: String) = b64.chunked(76).joinToString("\r\n")

    fun build(from: String, fromName: String, to: String, subject: String, body: String, attachments: List<MailAttachment>, boundary: String = "juiz-" + System.nanoTime()): String {
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US).format(ZonedDateTime.now())
        val sb = StringBuilder()
        sb.append("From: ${encodedWord(fromName)} <$from>\r\n")
        sb.append("To: <$to>\r\n")
        sb.append("Subject: ${encodedWord(subject)}\r\n")
        sb.append("Date: $date\r\n")
        sb.append("MIME-Version: 1.0\r\n")
        sb.append("X-Mailer: Juiz\r\n")
        sb.append("Content-Type: multipart/mixed; boundary=\"$boundary\"\r\n\r\n")
        sb.append("--$boundary\r\n")
        sb.append("Content-Type: text/plain; charset=UTF-8\r\n")
        sb.append("Content-Transfer-Encoding: base64\r\n\r\n")
        sb.append(wrap76(Base64.getEncoder().encodeToString(body.toByteArray(Charsets.UTF_8)))).append("\r\n")
        for (a in attachments) {
            sb.append("--$boundary\r\n")
            sb.append("Content-Type: ${a.mime}; name=\"${encodedWord(a.name)}\"\r\n")
            sb.append("Content-Transfer-Encoding: base64\r\n")
            sb.append("Content-Disposition: attachment; filename=\"${encodedWord(a.name)}\"\r\n\r\n")
            sb.append(wrap76(Base64.getEncoder().encodeToString(a.bytes))).append("\r\n")
        }
        sb.append("--$boundary--")
        return sb.toString()
    }
}
