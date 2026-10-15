package com.spamshield.android.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import com.spamshield.android.Background
import com.spamshield.android.data.IncomingSms

/** Receives SMS_DELIVER, which only the default SMS app gets. */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val sms = parse(intent) ?: return
        val app = context.applicationContext
        val pending = goAsync()
        Background.run {
            try {
                SmsFilter.handle(app, sms)
            } catch (t: Throwable) {
                Log.e("SpamShieldSms", "Failed to file incoming SMS", t)
            } finally {
                pending.finish()
            }
        }
    }

    /** Joins the parts of a multipart message into one [IncomingSms]. */
    private fun parse(intent: Intent): IncomingSms? {
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.filterNotNull().orEmpty()
        if (parts.isEmpty()) return null
        val first = parts.first()
        return IncomingSms(
            address = first.displayOriginatingAddress ?: first.originatingAddress,
            body = parts.joinToString("") { it.displayMessageBody ?: it.messageBody.orEmpty() },
            sentAt = first.timestampMillis,
            receivedAt = System.currentTimeMillis(),
            subscriptionId = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, -1),
        )
    }
}
