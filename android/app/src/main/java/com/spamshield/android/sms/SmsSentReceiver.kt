package com.spamshield.android.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.spamshield.android.Background

/** Marks a Sent-box row as failed when the radio reports a send error. */
class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val message = intent.data ?: return
        if (resultCode == Activity.RESULT_OK) return
        val pending = goAsync()
        Background.run {
            try {
                SmsStore.markFailed(context.applicationContext, message)
            } finally {
                pending.finish()
            }
        }
    }
}
