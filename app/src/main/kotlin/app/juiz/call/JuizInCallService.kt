package app.juiz.call

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallScreeningService
import android.telecom.InCallService
import app.juiz.core.model.ContactTier
import app.juiz.platform.Contacts

/** 默认拨号应用的通话服务：系统在有通话时绑定它，Juiz 由此接管来电与通话界面。 */
class JuizInCallService : InCallService() {
    override fun onCreate() {
        super.onCreate()
        CallRegistry.service = this
    }

    override fun onDestroy() {
        if (CallRegistry.service === this) CallRegistry.service = null
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) = CallController.onCallAdded(this, call)

    override fun onCallRemoved(call: Call) = CallController.onCallRemoved(this, call)

    @Deprecated("Deprecated in API 34")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        CallRegistry.audioState.value = audioState
    }
}

/** 响铃前的筛选：主人标记为骚扰的号码直接拦下，不打扰。 */
class JuizScreeningService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        val number = details.handle?.schemeSpecificPart
        val spam = details.callDirection == Call.Details.DIRECTION_INCOMING &&
            number != null && Contacts.tags().any { it.tier == ContactTier.SPAM && app.juiz.core.util.normalizeNumber(it.number) == app.juiz.core.util.normalizeNumber(number) }
        val response = CallResponse.Builder()
            .setDisallowCall(spam)
            .setRejectCall(spam)
            .setSkipCallLog(false)
            .setSkipNotification(spam)
            .build()
        respondToCall(details, response)
    }
}
