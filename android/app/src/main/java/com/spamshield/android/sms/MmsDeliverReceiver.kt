package com.spamshield.android.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Required for the default SMS role. Downloading MMS is out of scope for v1, so we only tell
 * the user that a multimedia message arrived.
 */
class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            Log.i("SpamShieldSms", "MMS WAP push received (type=${intent.type}); MMS download is not supported")
            Notifications.notifyUnsupportedMms(context)
        } catch (t: Throwable) {
            Log.e("SpamShieldSms", "Failed to handle MMS push", t)
        }
    }
}
