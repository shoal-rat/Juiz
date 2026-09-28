package app.juiz.core.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId

/** 可注入的时钟，测试和仿真用固定时间。 */
interface Clock {
    fun now(): Instant
    fun zone(): ZoneId = ZoneId.systemDefault()
    fun millis(): Long = now().toEpochMilli()
}

object SystemClock : Clock {
    override fun now(): Instant = Instant.now()
}

class FixedClock(var instant: Instant, private val zoneId: ZoneId = ZoneId.of("Asia/Shanghai")) : Clock {
    override fun now(): Instant = instant
    override fun zone(): ZoneId = zoneId
    fun advanceSeconds(s: Long) { instant = instant.plusSeconds(s) }
}

val JuizJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

private val HEX = "0123456789abcdef".toCharArray()

fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xff
        out[i * 2] = HEX[v ushr 4]
        out[i * 2 + 1] = HEX[v and 0x0f]
    }
    return String(out)
}

fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

fun sha256Hex(bytes: ByteArray): String = sha256(bytes).toHex()

fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

/**
 * 规范化 JSON：对象键按字典序排列、无多余空白。审批哈希和档案哈希都基于它，
 * 保证同样的内容在任何设备上得到同样的哈希。
 */
fun canonicalJson(element: JsonElement): String = buildString { writeCanonical(element, this) }

private fun writeCanonical(e: JsonElement, sb: StringBuilder) {
    when (e) {
        is JsonObject -> {
            sb.append('{')
            e.keys.sorted().forEachIndexed { i, k ->
                if (i > 0) sb.append(',')
                sb.append(JsonPrimitive(k).toString()).append(':')
                writeCanonical(e.getValue(k), sb)
            }
            sb.append('}')
        }
        is JsonArray -> {
            sb.append('[')
            e.forEachIndexed { i, v ->
                if (i > 0) sb.append(',')
                writeCanonical(v, sb)
            }
            sb.append(']')
        }
        JsonNull -> sb.append("null")
        is JsonPrimitive -> sb.append(e.toString())
    }
}

private val random = SecureRandom()

fun randomId(prefix: String, length: Int = 12): String {
    val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
    val sb = StringBuilder(prefix)
    repeat(length) { sb.append(alphabet[random.nextInt(alphabet.length)]) }
    return sb.toString()
}

fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

/** 电话号码归一化：去掉空格、横线、括号；+86 前缀转成国内号码形式便于匹配通讯录。 */
fun normalizeNumber(raw: String): String {
    val digits = raw.filter { it.isDigit() || it == '+' }
    return when {
        digits.startsWith("+86") -> digits.removePrefix("+86")
        digits.startsWith("0086") -> digits.removePrefix("0086")
        else -> digits
    }
}
