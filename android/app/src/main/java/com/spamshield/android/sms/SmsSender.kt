package com.spamshield.android.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager

/** Sends SMS and records them in the provider's Sent box, as the default SMS app must. */
object SmsSender {
    /** Blocking (provider writes); call off the main thread. */
    fun send(context: Context, recipients: List<String>, body: String, subscriptionId: Int = -1) {
        if (body.isEmpty()) return
        val manager = smsManager(context, subscriptionId)
        for (address in recipients) {
            val row = SmsStore.insertSent(context, address, body, subscriptionId)
            val parts = manager.divideMessage(body)
            val sentIntent = row?.let {
                val intent = Intent(context, SmsSentReceiver::class.java).setData(it)
                PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            }
            val sentIntents = ArrayList<PendingIntent?>(parts.size).apply { repeat(parts.size) { add(sentIntent) } }
            manager.sendMultipartTextMessage(address, null, parts, sentIntents, null)
        }
    }

    @Suppress("DEPRECATION")
    private fun smsManager(context: Context, subscriptionId: Int): SmsManager {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = checkNotNull(context.getSystemService(SmsManager::class.java)) { "No SmsManager" }
            return if (subscriptionId >= 0) base.createForSubscriptionId(subscriptionId) else base
        }
        return if (subscriptionId >= 0) SmsManager.getSmsManagerForSubscriptionId(subscriptionId) else SmsManager.getDefault()
    }
}
