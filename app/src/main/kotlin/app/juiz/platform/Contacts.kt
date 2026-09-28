package app.juiz.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import app.juiz.JuizApp
import app.juiz.core.model.CallerInfo
import app.juiz.core.model.ContactTier
import app.juiz.core.util.JuizJson
import app.juiz.core.util.normalizeNumber
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class NumberTag(val number: String, val tier: ContactTier, val note: String = "")

/**
 * 来电方分级：主人手动标记（重要/骚扰）优先，其次通讯录收藏（重要）、通讯录（认识），否则陌生。
 * 分级只影响规则和提示词，不代表任何授权。
 */
object Contacts {
    private val ser = ListSerializer(NumberTag.serializer())

    fun tags(): List<NumberTag> = JuizApp.core.settings.raw("number_tags")?.let {
        runCatching { JuizJson.decodeFromString(ser, it) }.getOrNull()
    }.orEmpty()

    fun saveTags(list: List<NumberTag>) = JuizApp.core.settings.putRaw("number_tags", JuizJson.encodeToString(ser, list))

    fun tag(number: String, tier: ContactTier, note: String = "") {
        val n = normalizeNumber(number)
        saveTags(tags().filterNot { normalizeNumber(it.number) == n } + NumberTag(n, tier, note))
    }

    fun resolve(context: Context, rawNumber: String?): CallerInfo {
        val number = rawNumber?.takeIf { it.isNotBlank() } ?: return CallerInfo("未知号码", null, ContactTier.UNKNOWN)
        val manual = tags().firstOrNull { normalizeNumber(it.number) == normalizeNumber(number) }
        var name: String? = null
        var starred = false
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            runCatching {
                context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.STARRED), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0)
                        starred = c.getInt(1) == 1
                    }
                }
            }
        }
        val tier = manual?.tier ?: when {
            starred -> ContactTier.VIP
            name != null -> ContactTier.KNOWN
            else -> ContactTier.UNKNOWN
        }
        return CallerInfo(number, name, tier)
    }
}
