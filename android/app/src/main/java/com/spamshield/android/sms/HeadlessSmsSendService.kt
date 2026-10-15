package com.spamshield.android.sms

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.telephony.TelephonyManager
import com.spamshield.android.Background

/** "Respond via message" from the incoming-call screen; required of a default SMS app. */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != TelephonyManager.ACTION_RESPOND_VIA_MESSAGE) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val recipients = Recipients.fromUri(intent.data)
        val body = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val app = applicationContext
        Background.run {
            try {
                if (recipients.isNotEmpty() && body.isNotEmpty()) SmsSender.send(app, recipients, body)
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }
}
