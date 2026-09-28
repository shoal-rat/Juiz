package app.juiz.core.policy

import app.juiz.core.model.OwnerProfile

/**
 * 披露由模板生成而不是由模型生成：开场白必须明确"AI 助理""经授权"，
 * 开启录音时自动追加录音告知。不合规的模板无法保存。
 */
object Disclosure {

    fun validate(template: String): List<String> {
        val problems = mutableListOf<String>()
        if (!Regex("AI|人工智能", RegexOption.IGNORE_CASE).containsMatchIn(template)) problems += "必须说明是 AI（含“AI”或“人工智能”）"
        if (!("助理" in template || "助手" in template)) problems += "必须说明是助理或助手"
        if ("授权" !in template) problems += "必须说明经本人授权"
        return problems
    }

    fun greeting(profile: OwnerProfile, recording: Boolean, clonedVoice: Boolean): String {
        val base = fill(profile.greetingTemplate, profile)
        val voiceNote = if (clonedVoice) "我使用的是经本人授权的合成声音。" else ""
        val recNote = if (recording) "本次通话将被录音。" else ""
        return insertBeforeQuestion(base, voiceNote + recNote)
    }

    fun smsGreeting(profile: OwnerProfile): String = fill(profile.smsGreetingTemplate, profile)

    private fun fill(t: String, p: OwnerProfile) = t.replace("{owner}", p.ownerName).replace("{assistant}", p.assistantName)

    /** 把附加告知放在最后一个问句之前，读起来更自然。 */
    private fun insertBeforeQuestion(text: String, note: String): String {
        if (note.isEmpty()) return text
        val idx = text.lastIndexOf("请问")
        return if (idx > 0) text.substring(0, idx) + note + text.substring(idx) else text + note
    }
}
