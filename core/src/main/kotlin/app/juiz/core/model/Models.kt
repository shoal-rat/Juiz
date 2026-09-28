package app.juiz.core.model

import kotlinx.serialization.Serializable

/** 来电方分级。只表示"认识程度"，不代表任何授权。 */
@Serializable
enum class ContactTier { VIP, KNOWN, UNKNOWN, SPAM }

@Serializable
data class CallerInfo(
    val number: String,
    val displayName: String? = null,
    val tier: ContactTier = ContactTier.UNKNOWN,
) {
    val label: String get() = displayName?.let { "$it（$number）" } ?: number
}

/** 设备当前可用的最高运行模式，由能力探针和真机验证共同决定。 */
@Serializable
enum class CapabilityLevel {
    /** 默认拨号器 + 短信代办，没有通话音频。 */
    L0_STANDARD,
    /** 特权安装且真机验证通过：AI 语音代接。 */
    L1_PRIVILEGED_VOICE,
}

@Serializable
enum class Channel { VOICE, SMS, TEXT_SIM }

enum class TaskStatus(val zh: String) {
    PENDING_CONFIRMATION("待确认"),
    HANDED_OFF("已交接"),
    IN_PROGRESS("执行中"),
    AWAITING_APPROVAL("待审批"),
    NEEDS_VERIFICATION("待核实"),
    COMPLETED("已完成"),
    FAILED("失败"),
    CANCELLED("已取消");

    val terminal: Boolean get() = this == COMPLETED || this == CANCELLED

    companion object {
        /**
         * 允许的迁移。注意没有任何状态能从 Work 的"完成"直接跳到 COMPLETED：
         * 必须先到 NEEDS_VERIFICATION，经结果清单或主人核验后才算完成。
         */
        private val allowed: Map<TaskStatus, Set<TaskStatus>> = mapOf(
            PENDING_CONFIRMATION to setOf(HANDED_OFF, CANCELLED),
            HANDED_OFF to setOf(IN_PROGRESS, AWAITING_APPROVAL, NEEDS_VERIFICATION, FAILED, CANCELLED),
            IN_PROGRESS to setOf(AWAITING_APPROVAL, NEEDS_VERIFICATION, FAILED, CANCELLED),
            AWAITING_APPROVAL to setOf(IN_PROGRESS, NEEDS_VERIFICATION, FAILED, CANCELLED),
            NEEDS_VERIFICATION to setOf(COMPLETED, FAILED, IN_PROGRESS),
            FAILED to setOf(PENDING_CONFIRMATION, CANCELLED),
            COMPLETED to emptySet(),
            CANCELLED to emptySet(),
        )

        fun canTransition(from: TaskStatus, to: TaskStatus): Boolean = to in allowed.getValue(from)
    }
}

@Serializable
enum class TaskKind { DOCUMENT, EMAIL, MEETING, CALLBACK, INFO, OTHER }

data class Task(
    val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val status: TaskStatus,
    val title: String,
    val request: String,
    val kind: TaskKind,
    val contactNumber: String?,
    val contactName: String?,
    val conversationId: String?,
    val confirmedFields: Map<String, String>,
    val deliverable: String?,
    val due: String?,
    val handoffRoute: String?,
    val handoffAttempt: Int,
    val workRunId: String?,
    val workConversationUrl: String?,
    val resultSummary: String?,
    val verification: String?,
    val note: String?,
) {
    val handoffKey: String get() = "$id#$handoffAttempt"
}

enum class ActionKind { EMAIL, SMS, CALLBACK, SHARE_FILE }

enum class ActionStatus { PROPOSED, APPROVED, EXECUTING, DONE, FAILED, UNKNOWN, REVOKED }

@Serializable
data class ActionContent(
    val subject: String? = null,
    val body: String,
    val attachments: List<AttachmentRef> = emptyList(),
)

@Serializable
data class AttachmentRef(val name: String, val sha256: String, val bytes: Long)

data class OutboundAction(
    val id: String,
    val taskId: String?,
    val kind: ActionKind,
    val target: String,
    val content: ActionContent,
    val contentHash: String,
    val status: ActionStatus,
    val approvalHash: String?,
    val approvedAt: Long?,
    val idempotencyKey: String,
    val receipt: String?,
)

enum class FactSource { OWNER, CALLER, WORK, SYSTEM }

enum class FactStatus { CONFIRMED, PENDING, REJECTED }

data class MemoryFact(
    val id: String,
    val subject: String,
    val text: String,
    val source: FactSource,
    val sourceRef: String?,
    val status: FactStatus,
    val shareableInCalls: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class ConsentKind(val zh: String, val defaultGranted: Boolean) {
    CLONED_VOICE("使用本人克隆音色", false),
    CALL_RECORDING("通话录音", false),
    TRANSCRIPT_RETENTION("转写留存", true),
    SMS_SCREENING("短信代办", false),
}

data class ConsentRecord(val kind: ConsentKind, val granted: Boolean, val scope: String?, val changedAt: Long)

@Serializable
data class OwnerProfile(
    val ownerName: String = "主人",
    val assistantName: String = "Juiz",
    /** 开场白模板，{owner} {assistant} 会被替换。必须包含 AI 身份披露。 */
    val greetingTemplate: String = "您好，我是{owner}的 AI 助理{assistant}，经本人授权代接电话。请问有什么可以帮您？",
    /** 主人允许 AI 对外说明的当前状态，例如"今天下午在开会，大约五点后方便"。 */
    val publicStatus: String = "",
    /** 通话中 AI 可以提到的主人背景（例如职业、公司），由主人自己填写。 */
    val publicBio: String = "",
    val smsGreetingTemplate: String = "您好，我是{owner}的 AI 助理{assistant}。{owner}现在不方便接听，请直接回复短信说明来意，我会整理后转告。",
)
