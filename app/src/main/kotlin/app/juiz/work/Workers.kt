package app.juiz.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.juiz.JuizApp
import app.juiz.call.Notifications
import app.juiz.core.JuizCore
import app.juiz.core.model.ActionStatus
import app.juiz.core.model.TaskStatus
import app.juiz.platform.Saf
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime

object Workers {
    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        val now = ZonedDateTime.now()
        var next = now.with(LocalTime.of(7, 30))
        if (!next.isAfter(now)) next = next.plusDays(1)
        wm.enqueueUniquePeriodicWork(
            "daily-digest", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<DailyDigestWorker>(Duration.ofDays(1)).setInitialDelay(Duration.between(now, next)).build(),
        )
        wm.enqueueUniquePeriodicWork(
            "work-poll", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WorkPollWorker>(Duration.ofMinutes(15))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
        )
    }
}

/** 简报内容：待确认/待审批/待核实的数量、昨夜代办、以及档案链头（外部锚点）。 */
object Brief {
    fun build(core: JuizCore, sinceMillis: Long): String = buildString {
        val pending = core.tasks.withStatus(TaskStatus.PENDING_CONFIRMATION).size
        val approval = core.tasks.withStatus(TaskStatus.AWAITING_APPROVAL).size
        val verify = core.tasks.withStatus(TaskStatus.NEEDS_VERIFICATION).size
        val unknown = core.tasks.actionsWithStatus(ActionStatus.UNKNOWN).size
        append("待确认 $pending · 待审批 $approval · 待核实 $verify")
        if (unknown > 0) append(" · 发送结果待你核实 $unknown")
        val night = core.archive.recent(300).filter { it.at >= sinceMillis }
        val errands = night.filter { it.type == "errand.file" }
        if (errands.isNotEmpty()) {
            append("\n昨夜代办：")
            errands.reversed().forEach { e ->
                val p = e.payload
                append("\n· 向 ${p["to"].toString().trim('"')} 发送《${p["file"].toString().trim('"')}》")
            }
        }
        val messages = night.count { it.type == "message.taken" }
        if (messages > 0) append("\n新留言 $messages 条")
        core.archive.head()?.let { append("\n档案锚点 #${it.seq} ${it.hash.take(16)}（请在别处留存，用于日后核对）") }
    }
}

class DailyDigestWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val core = JuizApp.core
        runCatching { core.startupMaintenance() }
        val text = Brief.build(core, System.currentTimeMillis() - 24 * 3_600_000L)
        Notifications.simple(applicationContext, JuizApp.CH_DIGEST, Notifications.ID_DIGEST, "Juiz 晨间简报", text)
        core.archive.append("digest.sent", null) { put("chars", text.length) }
        return Result.success()
    }
}

/** 轮询已交给 Workspace Agent 的任务；API 报告完成后，尝试在交换目录里核验结果清单。 */
class WorkPollWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val core = JuizApp.core
        val handoff = core.handoff()
        val active = core.tasks.withStatus(TaskStatus.HANDED_OFF, TaskStatus.IN_PROGRESS, TaskStatus.AWAITING_APPROVAL, TaskStatus.NEEDS_VERIFICATION)
        val folder = Saf.exchangeFolder(applicationContext)
        for (t in active) {
            runCatching { handoff.poll(t.id) }
            val cur = core.tasks.get(t.id) ?: continue
            if (folder != null && cur.status == TaskStatus.NEEDS_VERIFICATION) {
                val r = handoff.verify(t.id, folder)
                if (r.ok) Notifications.simple(applicationContext, JuizApp.CH_TASKS, Notifications.ID_TASKS, "任务已完成并核验", "${t.id}「${t.title}」：${r.verified.joinToString()}")
            }
        }
        return Result.success()
    }
}
