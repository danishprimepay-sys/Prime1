package com.spamshield.android.sms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.spamshield.android.data.Contacts
import com.spamshield.android.data.IncomingSms
import com.spamshield.android.ui.ThreadActivity

object Notifications {
    const val CHANNEL_MESSAGES = "messages"
    const val CHANNEL_SPAM = "possible_spam"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Incoming text messages"
                },
                NotificationChannel(CHANNEL_SPAM, "Possible spam", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Messages SpamShield thinks may be spam"
                },
            )
        )
    }

    /** Posts a notification for a message that was filed in the inbox. */
    fun notifyMessage(context: Context, sms: IncomingSms, possibleSpam: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        val address = sms.address.orEmpty()
        val sender = Contacts.displayName(context, address) ?: address.ifEmpty { "Unknown sender" }
        val intent = ThreadActivity.intent(context, address)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context, address.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, if (possibleSpam) CHANNEL_SPAM else CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(if (possibleSpam) "Possible spam · $sender" else sender)
            .setContentText(sms.body)
            .setStyle(Notification.BigTextStyle().bigText(sms.body))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setWhen(sms.receivedAt)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        manager.notify(notificationId(address), notification)
    }

    fun notifyUnsupportedMms(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        val notification = Notification.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle("Multimedia message received")
            .setContentText("SpamShield can't download MMS yet. Switch default SMS app to view it.")
            .setAutoCancel(true)
            .build()
        manager.notify(MMS_NOTIFICATION_ID, notification)
    }

    fun cancelFor(context: Context, address: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(notificationId(address))
    }

    private fun notificationId(address: String) = address.hashCode() and 0x7fffffff or 1

    private const val MMS_NOTIFICATION_ID = 0
}
