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
        core = JuizCore(driver, SystemClock, secrets, fileCatalog = { Saf.outboxCatalog(this) })
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
