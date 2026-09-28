package app.juiz

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.juiz.core.JuizCore
import app.juiz.core.db.JuizDatabase
import app.juiz.core.util.SystemClock
import app.juiz.platform.KeystoreSecrets
import app.juiz.platform.Saf
import app.juiz.work.Workers
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass

class JuizApp : Application() {
    lateinit var core: JuizCore
        private set
    lateinit var secrets: KeystoreSecrets
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 仅在特权安装（L1）时有意义：允许通过反射调用系统接口；普通安装下调用仍会因缺权限被系统拒绝
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) runCatching { HiddenApiBypass.addHiddenApiExemptions("L") }
        secrets = KeystoreSecrets(this)
        val driver = AndroidSqliteDriver(JuizDatabase.Schema, this, "juiz.db")
        core = JuizCore(
            driver, SystemClock, secrets,
            fileCatalog = { Saf.outboxCatalog(this) },
            exchangeFolder = { Saf.exchangeFolder(this) },
            cloudDir = java.io.File(filesDir, "cloud").apply { mkdirs() },
            recordingDir = java.io.File(noBackupFilesDir, "recordings"),
            recordingCipher = app.juiz.platform.KeystoreRecordingCipher(),
        )
        createChannels()
        runCatching { core.startupMaintenance() }
        Workers.schedule(this)
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_INCOMING, "来电", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "来电与 AI 代接中的通话"
            setSound(null, null)
        })
        nm.createNotificationChannel(NotificationChannel(CH_ONGOING, "通话中", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ESCALATION, "需要本人", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "对方要求本人、紧急情况、争议等需要你立刻处理的事"
        })
        nm.createNotificationChannel(NotificationChannel(CH_DIGEST, "每日简报", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_TASKS, "任务与审批", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private val pollers = mutableSetOf<String>()
    val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    /**
     * 交给执行方后，在应用存活期间每 8 秒查一次状态：云端容器闲置约 20 分钟就会过期，
     * 完成后要尽快把成品下载回来。应用被回收时由 WorkPollWorker 兜底。
     */
    fun watchTask(taskId: String) {
        synchronized(pollers) { if (!pollers.add(taskId)) return }
        appScope.launch {
            try {
                val deadline = System.currentTimeMillis() + 60 * 60_000L
                while (System.currentTimeMillis() < deadline) {
                    runCatching { core.handoff().poll(taskId) }
                    val t = core.tasks.get(taskId) ?: break
                    if (t.status.terminal || t.status == app.juiz.core.model.TaskStatus.FAILED) {
                        if (t.status == app.juiz.core.model.TaskStatus.COMPLETED) {
                            val drafts = core.tasks.actionsForTask(taskId).count { it.status == app.juiz.core.model.ActionStatus.PROPOSED }
                            app.juiz.call.Notifications.simple(this@JuizApp, CH_TASKS, app.juiz.call.Notifications.ID_TASKS, "委托已完成：${t.title}",
                                if (drafts > 0) "成品已核验，有 $drafts 封邮件等你批准发送。" else "成品已核验，可以查看了。")
                        }
                        break
                    }
                    kotlinx.coroutines.delay(8_000)
                }
            } finally {
                synchronized(pollers) { pollers.remove(taskId) }
                app.juiz.ui.UiBus.bump()
            }
        }
    }

    companion object {
        lateinit var instance: JuizApp
            private set
        val core: JuizCore get() = instance.core

        const val CH_INCOMING = "incoming"
        const val CH_ONGOING = "ongoing"
        const val CH_ESCALATION = "escalation"
        const val CH_DIGEST = "digest"
        const val CH_TASKS = "tasks"
    }
}
