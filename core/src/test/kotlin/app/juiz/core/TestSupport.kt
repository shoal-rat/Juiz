package app.juiz.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.juiz.core.util.FixedClock
import java.time.Instant

class MapSecrets(private val m: Map<String, String> = emptyMap()) : Secrets {
    override fun get(key: String): String? = m[key]
}

/** 每个测试一个内存数据库，时间固定在 2026-09-28 10:00（北京时间）。 */
fun testCore(
    at: String = "2026-09-28T02:00:00Z",
    secrets: Map<String, String> = emptyMap(),
    catalog: app.juiz.core.errand.FileCatalog? = null,
    recordingDir: java.io.File? = null,
): Pair<JuizCore, FixedClock> {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    JuizCore.createSchema(driver)
    val clock = FixedClock(Instant.parse(at))
    val cipher = recordingDir?.let { app.juiz.core.recording.AesGcmCipher(ByteArray(32) { i -> i.toByte() }) }
    return JuizCore(driver, clock, MapSecrets(secrets), fileCatalog = { catalog }, recordingDir = recordingDir, recordingCipher = cipher) to clock
}
