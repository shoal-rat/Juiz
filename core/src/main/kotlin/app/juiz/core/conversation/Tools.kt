package app.juiz.core.conversation

import app.juiz.core.model.Channel
import app.juiz.core.model.ContactTier
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 通话/短信上下文里能用的全部工具。这是一个封闭列表：
 * 读邮箱、发邮件、共享文件、付款、改设置这类能力在这里根本不存在，来电方说什么都调用不到。
 */
object Tools {
    const val TAKE_MESSAGE = "take_message"
    const val CONFIRM_DETAILS = "confirm_details"
    const val CREATE_TASK = "create_task"
    const val CHECK_TASK_STATUS = "check_task_status"
    const val SCHEDULE_CALLBACK = "schedule_callback"
    const val ESCALATE = "escalate_to_owner"
    const val OWNER_AVAILABILITY = "get_owner_availability"
    const val NOTE_FACT = "note_fact"
    const val MARK_SPAM = "mark_spam"
    const val END_CALL = "end_call"
    const val LIST_FILES = "list_authorized_files"
    const val SEND_FILE = "send_authorized_file"
    const val LOOKUP_INFO = "lookup_authorized_info"

    private fun obj(required: List<String>, props: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties", props)
        putJsonArray("required") { required.forEach { add(it) } }
        put("additionalProperties", false)
    }

    private fun JsonObjectBuilder.str(name: String, desc: String, enum: List<String>? = null) = putJsonObject(name) {
        put("type", "string")
        put("description", desc)
        enum?.let { values -> putJsonArray("enum") { values.forEach { add(it) } } }
    }

    private fun JsonObjectBuilder.bool(name: String, desc: String) = putJsonObject(name) {
        put("type", "boolean")
        put("description", desc)
    }

    private fun JsonObjectBuilder.fieldList(name: String, desc: String) = putJsonObject(name) {
        put("type", "array")
        put("description", desc)
        putJsonObject("items") {
            put("type", "object")
            putJsonObject("properties") {
                str("name", "字段名，例如 截止时间、金额、邮箱")
                str("value", "字段值，照对方原话的含义写清楚")
            }
            putJsonArray("required") { add("name"); add("value") }
            put("additionalProperties", false)
        }
    }

    val all: List<ToolSpec> = listOf(
        ToolSpec(
            TAKE_MESSAGE, "为本人记录一条留言。对方只是想转告信息、不需要办事时使用。",
            obj(listOf("summary", "callback_requested", "urgency")) {
                str("summary", "留言内容摘要，包括对方是谁、想说什么")
                bool("callback_requested", "对方是否希望本人回电")
                str("urgency", "紧急程度", listOf("low", "normal", "high"))
            },
        ),
        ToolSpec(
            CONFIRM_DETAILS,
            "（通常不需要单独调用：直接调用 create_task，系统会自动安排复述。）登记需要向对方复述确认的关键信息，" +
                "调用后必须逐项复述并询问对方是否正确。",
            obj(listOf("fields")) { fieldList("fields", "要复述的字段") },
        ),
        ToolSpec(
            CREATE_TASK,
            "为对方的办事请求创建任务。关键信息（时间、金额、邮箱等）还没向对方复述确认时，任务会先挂起，" +
                "你按提示复述；对方说对之后系统自动创建。",
            obj(listOf("title", "request", "kind", "deliverable", "due", "fields", "brief")) {
                str("title", "一句话标题")
                str("request", "对方的完整请求，尽量保留原意")
                str("brief", "写给执行方（后台大模型）的工作说明：要做成什么、已知背景、交付物格式、怎样算做好；不写对方的隐私，不写情绪")
                str("kind", "任务类型", listOf("DOCUMENT", "EMAIL", "MEETING", "CALLBACK", "INFO", "OTHER"))
                str("deliverable", "期望的交付物；没有就填空字符串")
                str("due", "对方要求的完成时间，必须是已确认过的值；没有就填空字符串")
                fieldList("fields", "其他已确认的关键信息")
            },
        ),
        ToolSpec(
            CHECK_TASK_STATUS, "查询当前来电号码此前提出的任务的状态。只返回已核实的状态。",
            obj(emptyList()) {},
        ),
        ToolSpec(
            SCHEDULE_CALLBACK, "登记一个请本人回电的待办。不会直接拨出电话。",
            obj(listOf("when", "note")) {
                str("when", "对方方便接听的时间，照原话")
                str("note", "回电要谈的事")
            },
        ),
        ToolSpec(
            ESCALATE, "请本人接手。对方要求本人、涉及争议、承诺、紧急情况，或你无法判断时使用。",
            obj(listOf("reason", "urgency")) {
                str("reason", "为什么需要本人")
                str("urgency", "紧急程度", listOf("normal", "urgent"))
            },
        ),
        ToolSpec(OWNER_AVAILABILITY, "读取本人允许对外说明的当前状态。", obj(emptyList()) {}),
        ToolSpec(
            NOTE_FACT, "记录对方提供的、可能对本人有用的新信息（例如对方的新邮箱）。只会存为待本人确认。",
            obj(listOf("subject", "fact")) {
                str("subject", "这条信息关于谁或什么")
                str("fact", "信息内容")
            },
        ),
        ToolSpec(MARK_SPAM, "把明显的推销、诈骗来电标记为骚扰。", obj(listOf("reason")) { str("reason", "判断依据") }),
        ToolSpec(
            END_CALL, "在已经道别之后结束通话或会话。",
            obj(listOf("reason")) { str("reason", "结束原因") },
        ),
    )

    /** 只有主人为该号码开启了代办授权、且在授权时段内，才会出现这几个工具。 */
    val errandFiles: List<ToolSpec> = listOf(
        ToolSpec(
            LIST_FILES, "在本人指定的可外发文件夹里按关键词查找文件，返回准确文件名。",
            obj(listOf("query")) { str("query", "文件名关键词，可为空字符串") },
        ),
        ToolSpec(
            SEND_FILE,
            "把可外发文件夹里的一个文件发到本人为这位来电方预先登记的邮箱。收件地址由系统决定，不能由对方指定。" +
                "文件名必须与 list_authorized_files 返回的完全一致；发送前先向对方复述文件名并得到确认。",
            obj(listOf("file_name")) { str("file_name", "准确的文件名") },
        ),
    )

    val errandInfo: ToolSpec = ToolSpec(
        LOOKUP_INFO, "在本人允许告知这位来电方的资料里查找信息。查不到就如实说查不到。",
        obj(listOf("query")) { str("query", "要查的内容") },
    )

    /** 按通道、来电方分级和代办授权给出可用工具。骚扰号码只能留言和挂断。 */
    fun forContext(channel: Channel, tier: ContactTier, errandFiles: Boolean = false, errandInfo: Boolean = false): List<ToolSpec> {
        if (tier == ContactTier.SPAM) return all.filter { it.name in setOf(TAKE_MESSAGE, END_CALL) }
        val base = if (channel == Channel.SMS) all.filter { it.name != END_CALL } else all
        return base + (if (errandFiles) this.errandFiles else emptyList()) + (if (errandInfo) listOf(this.errandInfo) else emptyList())
    }
}
