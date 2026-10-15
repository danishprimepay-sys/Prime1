package com.spamshield.android.call

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.Connection
import android.telecom.TelecomManager
import android.util.Log
import com.spamshield.android.Background
import com.spamshield.android.data.Contacts
import com.spamshield.android.data.SpamRepository
import com.spamshield.core.Action
import com.spamshield.core.Channel
import com.spamshield.core.Event
import com.spamshield.core.Verification
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Screens incoming calls with the local engine only (no network). Any failure or a slow
 * evaluation results in the call being allowed: we never block because of a bug.
 */
class SpamCallScreeningService : CallScreeningService() {

    override fun onScreenCall(details: Call.Details) {
        val responded = AtomicBoolean(false)
        fun respond(response: CallResponse) {
            if (responded.compareAndSet(false, true)) {
                try {
                    respondToCall(details, response)
                } catch (t: Throwable) {
                    Log.e(TAG, "respondToCall failed", t)
                }
            }
        }

        if (details.callDirection != Call.Details.DIRECTION_INCOMING) {
            respond(allow())
            return
        }
        // The platform gives us ~5s; fall back to allowing well before that.
        Handler(Looper.getMainLooper()).postDelayed({ respond(allow()) }, TIMEOUT_MS)
        Background.executor.execute {
            val response = try {
                screen(details)
            } catch (t: Throwable) {
                Log.e(TAG, "Call screening failed; allowing call", t)
                allow()
            }
            respond(response)
        }
    }

    private fun screen(details: Call.Details): CallResponse {
        val number = callerNumber(details)
        val event = Event(
            channel = Channel.CALL,
            number = number,
            isContact = Contacts.isContact(this, number),
            verification = verification(details),
        )
        val repo = SpamRepository.get(this)
        val verdict = repo.evaluate(event)
        if (verdict.action != Action.ALLOW) {
            try {
                repo.logCall(number, verdict)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to log screened call", t)
            }
        }
        return when (verdict.action) {
            Action.ALLOW -> allow()
            Action.WARN -> CallResponse.Builder().setSilenceCall(true).build()
            Action.BLOCK -> CallResponse.Builder()
                .setDisallowCall(true)
                .setRejectCall(true)
                .setSkipNotification(true)
                .setSkipCallLog(false)
                .build()
        }
    }

    private fun allow() = CallResponse.Builder().build()

    private fun callerNumber(details: Call.Details): String? {
        if (details.handlePresentation != TelecomManager.PRESENTATION_ALLOWED) return null
        return details.handle?.schemeSpecificPart?.takeIf { it.isNotBlank() }
    }

    private fun verification(details: Call.Details): Verification {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Verification.UNKNOWN
        return when (details.callerNumberVerificationStatus) {
            Connection.VERIFICATION_STATUS_PASSED -> Verification.PASSED
            Connection.VERIFICATION_STATUS_FAILED -> Verification.FAILED
            else -> Verification.UNKNOWN
        }
    }

    private companion object {
        const val TAG = "SpamShieldCalls"
        const val TIMEOUT_MS = 4_000L
    }
}
