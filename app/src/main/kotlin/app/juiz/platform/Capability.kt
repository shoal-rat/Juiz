package app.juiz.platform

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.telecom.Call
import app.juiz.JuizApp
import app.juiz.core.model.CapabilityLevel
import app.juiz.core.util.JuizJson
import kotlinx.serialization.Serializable

enum class ProbeStatus { OK, FAIL, UNKNOWN, NA }

@Serializable
data class ValidationStep(val id: String, val title: String, val passed: Boolean? = null, val measured: String? = null, val manual: Boolean = false)

/** 真机验证报告：和系统指纹绑定，系统更新后需要重新验证。 */
@Serializable
data class ValidationReport(
    val fingerprint: String,
    val device: String,
    val sdk: Int,
    val startedAt: Long,
    val steps: List<ValidationStep>,
) {
    /** 步骤 1–5 全部通过才开放 L1 自动代接。 */
    val passesCore: Boolean get() = CORE_STEPS.all { id -> steps.firstOrNull { it.id == id }?.passed == true }

    companion object {
        val CORE_STEPS = listOf("ring_detect", "enter_processing", "downlink", "uplink", "takeover")
    }
}

data class ProbeItem(val id: String, val title: String, val status: ProbeStatus, val detail: String, val forL1: Boolean = false)

data class ProbeResult(val items: List<ProbeItem>, val level: CapabilityLevel?, val syncRate: Int) {
    val defaultDialer: Boolean get() = items.firstOrNull { it.id == "dialer" }?.status == ProbeStatus.OK
}

/**
 * 能力探针：逐项说明本机缺什么，而不是笼统地说"不支持"。
 * 返回的 level 为 null 表示尚未成为默认拨号应用（Juiz 还不能接管来电）。
 */
object CapabilityProbe {
    private fun granted(ctx: Context, perm: String) = ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    fun isDefaultDialer(ctx: Context): Boolean =
        ctx.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_DIALER) == true

    fun isPrivilegedInstall(ctx: Context): Boolean {
        val ai = ctx.applicationInfo
        val system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0
        val privFlag = runCatching {
            val f = ApplicationInfo::class.java.getField("privateFlags").getInt(ai)
            f and 8 != 0 // PRIVATE_FLAG_PRIVILEGED
        }.getOrElse { ai.sourceDir.contains("/priv-app/") }
        return system && privFlag
    }

    fun validationReport(): ValidationReport? = JuizApp.core.settings.raw("validation_report")?.let {
        runCatching { JuizJson.decodeFromString(ValidationReport.serializer(), it) }.getOrNull()
    }

    fun saveValidation(r: ValidationReport) =
        JuizApp.core.settings.putRaw("validation_report", JuizJson.encodeToString(ValidationReport.serializer(), r))

    fun run(ctx: Context): ProbeResult {
        val items = mutableListOf<ProbeItem>()
        fun add(id: String, title: String, ok: Boolean?, detail: String, forL1: Boolean = false) {
            items += ProbeItem(id, title, when (ok) { true -> ProbeStatus.OK; false -> ProbeStatus.FAIL; null -> ProbeStatus.UNKNOWN }, detail, forL1)
        }
        val sdk = Build.VERSION.SDK_INT
        add("sdk", "系统版本", sdk >= 29, "Android API $sdk（L1 需要 33+）")
        val dialer = isDefaultDialer(ctx)
        add("dialer", "默认拨号应用", dialer, if (dialer) "Juiz 负责接听与通话界面" else "需要把 Juiz 设为默认电话应用")
        add("notify", "通知权限", sdk < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS), "来电与升级提醒")
        val nm = ctx.getSystemService(NotificationManager::class.java)
        add("fsi", "全屏来电界面", if (sdk >= 34) nm.canUseFullScreenIntent() else true, "锁屏时弹出来电界面")
        add("contacts", "通讯录", granted(ctx, Manifest.permission.READ_CONTACTS), "区分认识的人与陌生号码")
        add("sms", "短信收发", granted(ctx, Manifest.permission.SEND_SMS) && granted(ctx, Manifest.permission.RECEIVE_SMS), "L0 短信代办")
        val pm = ctx.getSystemService(PowerManager::class.java)
        add("battery", "电池优化豁免", pm.isIgnoringBatteryOptimizations(ctx.packageName), "后台时仍能及时处理来电")

        val priv = isPrivilegedInstall(ctx)
        add("priv", "系统特权安装", priv, if (priv) "已作为 priv-app 安装" else "普通安装：拿不到通话音频（这是 Android 的限制，不是 bug）", forL1 = true)
        val intercept = granted(ctx, "android.permission.CALL_AUDIO_INTERCEPTION")
        add("perm_intercept", "CALL_AUDIO_INTERCEPTION", intercept, "通话音频拦截权限（signature|privileged）", forL1 = true)
        add("perm_capture", "CAPTURE_AUDIO_OUTPUT", granted(ctx, "android.permission.CAPTURE_AUDIO_OUTPUT"), "旧式下行采集权限（可选）", forL1 = true)
        val api = SystemCallApi.methodExists(Call::class.java, "enterBackgroundAudioProcessing") &&
            SystemCallApi.methodExists(AudioManager::class.java, "getCallUplinkInjectionAudioTrack")
        add("hidden_api", "系统接口可访问", api, if (api) "后台音频处理与注入接口存在" else "本 ROM 未提供或禁止访问", forL1 = true)
        val hal: Boolean? = if (intercept && sdk >= 33) {
            runCatching { SystemCallApi.isPstnCallAudioInterceptable(ctx.getSystemService(AudioManager::class.java)) }.getOrNull()
        } else null
        add("hal", "音频 HAL 支持电话音频拦截", hal, when (hal) {
            true -> "isPstnCallAudioInterceptable = true"
            false -> "本机音频 HAL 不支持（同系统版本不同机型结果可能不同）"
            null -> "缺少权限，无法检测"
        }, forL1 = true)

        val report = validationReport()
        val validated = report != null && report.fingerprint == Build.FINGERPRINT && report.passesCore
        add("validated", "真机验证（本系统版本）", if (report == null) null else validated, when {
            report == null -> "尚未进行真机验证"
            report.fingerprint != Build.FINGERPRINT -> "系统已更新，需要重新验证"
            validated -> "步骤 1–5 全部通过"
            else -> "有步骤未通过：${report.steps.filter { it.passed != true }.joinToString { it.title }}"
        }, forL1 = true)

        val l1 = dialer && priv && intercept && api && hal == true && validated
        val level = when {
            !dialer -> null
            l1 -> CapabilityLevel.L1_PRIVILEGED_VOICE
            else -> CapabilityLevel.L0_STANDARD
        }
        val applicable = items.filter { it.status != ProbeStatus.NA }
        val sync = if (applicable.isEmpty()) 0 else applicable.count { it.status == ProbeStatus.OK } * 100 / applicable.size
        return ProbeResult(items, level, sync)
    }

    /** L1 的"能尝试"条件（不含真机验证），验证向导用它判断能否开始。 */
    fun l1Attemptable(r: ProbeResult) = r.items.filter { it.forL1 && it.id != "validated" && it.id != "perm_capture" }.all { it.status == ProbeStatus.OK }
}
