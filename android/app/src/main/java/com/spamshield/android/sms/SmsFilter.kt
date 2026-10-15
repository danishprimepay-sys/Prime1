package com.spamshield.android.sms

import android.content.Context
import android.util.Log
import com.spamshield.android.data.Contacts
import com.spamshield.android.data.IncomingSms
import com.spamshield.android.data.SpamRepository
import com.spamshield.core.Action
import com.spamshield.core.Channel
import com.spamshield.core.Event
import com.spamshield.core.Verdict

/** Files an incoming SMS according to its verdict. Blocking; call off the main thread. */
object SmsFilter {
    private const val TAG = "SpamShieldSms"

    fun handle(context: Context, sms: IncomingSms) {
        val repo = SpamRepository.get(context)
        // Never lose a message because of an engine bug: fall back to delivering it.
        val verdict = try {
            repo.evaluate(
                Event(
                    channel = Channel.SMS,
                    number = sms.address,
                    body = sms.body,
                    isContact = Contacts.isContact(context, sms.address),
                )
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Evaluation failed; delivering message", t)
            Verdict(Action.ALLOW, 0.0, emptyList())
        }

        when (verdict.action) {
            Action.BLOCK -> try {
                repo.quarantine(sms, verdict)
            } catch (t: Throwable) {
                Log.e(TAG, "Quarantine failed; delivering message", t)
                deliver(context, sms, possibleSpam = true)
            }
            Action.WARN -> deliver(context, sms, possibleSpam = true)
            Action.ALLOW -> deliver(context, sms, possibleSpam = false)
        }
    }

    private fun deliver(context: Context, sms: IncomingSms, possibleSpam: Boolean) {
        SmsStore.insertInbox(context, sms)
        Notifications.notifyMessage(context, sms, possibleSpam)
    }
}
