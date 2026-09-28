package app.juiz.core.archive

import app.juiz.core.util.randomBytes
import java.nio.ByteBuffer
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 加密备份格式：
 *   "JUIZBK01"(8) | 迭代次数(4, 大端) | salt(16) | iv(12) | AES-256-GCM 密文+标签
 * 密钥由主人口令经 PBKDF2-HMAC-SHA256 派生。备份写到主人指定的独立位置。
 */
object BackupCrypto {
    private val MAGIC = "JUIZBK01".toByteArray()
    const val DEFAULT_ITERATIONS = 310_000

    class WrongPassphrase : Exception("口令错误或备份已损坏")

    fun encrypt(plain: ByteArray, passphrase: CharArray, iterations: Int = DEFAULT_ITERATIONS): ByteArray {
        val salt = randomBytes(16)
        val iv = randomBytes(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD(MAGIC)
        val ct = cipher.doFinal(plain)
        return ByteBuffer.allocate(MAGIC.size + 4 + salt.size + iv.size + ct.size)
            .put(MAGIC).putInt(iterations).put(salt).put(iv).put(ct).array()
    }

    fun decrypt(blob: ByteArray, passphrase: CharArray): ByteArray {
        val buf = ByteBuffer.wrap(blob)
        val magic = ByteArray(MAGIC.size).also { buf.get(it) }
        require(magic.contentEquals(MAGIC)) { "不是Juiz 备份文件" }
        val iterations = buf.int
        require(iterations in 10_000..10_000_000) { "迭代次数异常" }
        val salt = ByteArray(16).also { buf.get(it) }
        val iv = ByteArray(12).also { buf.get(it) }
        val ct = ByteArray(buf.remaining()).also { buf.get(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD(MAGIC)
        return try {
            cipher.doFinal(ct)
        } catch (e: AEADBadTagException) {
            throw WrongPassphrase()
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }
}
