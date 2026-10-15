package com.spamshield.android.sms

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import com.spamshield.android.data.IncomingSms

data class Conversation(val threadId: Long, val address: String, val snippet: String, val date: Long, val unread: Boolean)

data class SmsRow(val id: Long, val body: String, val date: Long, val type: Int) {
    val isIncoming get() = type == Telephony.Sms.MESSAGE_TYPE_INBOX
    val isFailed get() = type == Telephony.Sms.MESSAGE_TYPE_FAILED
}

/** Reads and writes the system SMS provider. Writes only succeed while we are the default SMS app. */
object SmsStore {
    private val SMS = Telephony.Sms.CONTENT_URI

    fun insertInbox(context: Context, sms: IncomingSms, read: Boolean = false): Uri? {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, sms.address)
            put(Telephony.Sms.BODY, sms.body)
            put(Telephony.Sms.DATE, sms.receivedAt)
            put(Telephony.Sms.DATE_SENT, sms.sentAt)
            put(Telephony.Sms.READ, if (read) 1 else 0)
            put(Telephony.Sms.SEEN, if (read) 1 else 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            if (sms.subscriptionId >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, sms.subscriptionId)
        }
        return context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
    }

    fun insertSent(context: Context, address: String, body: String, subscriptionId: Int): Uri? {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
            if (subscriptionId >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, subscriptionId)
        }
        return context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
    }

    fun markFailed(context: Context, message: Uri) {
        val values = ContentValues().apply { put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED) }
        context.contentResolver.update(message, values, null, null)
    }

    /** Latest message per thread, newest first. */
    fun conversations(context: Context): List<Conversation> {
        val projection = arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.READ, Telephony.Sms.TYPE)
        val byThread = LinkedHashMap<Long, Conversation>()
        context.contentResolver.query(SMS, projection, null, null, "${Telephony.Sms.DATE} DESC")?.use { c ->
            while (c.moveToNext()) {
                val threadId = c.getLong(0)
                val unread = c.getInt(5) == Telephony.Sms.MESSAGE_TYPE_INBOX && c.getInt(4) == 0
                val existing = byThread[threadId]
                if (existing == null) {
                    byThread[threadId] = Conversation(threadId, c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getLong(3), unread)
                } else if (unread && !existing.unread) {
                    byThread[threadId] = existing.copy(unread = true)
                }
            }
        }
        return byThread.values.toList()
    }

    fun messages(context: Context, threadId: Long): List<SmsRow> {
        val projection = arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE)
        val out = mutableListOf<SmsRow>()
        context.contentResolver.query(SMS, projection, "${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId.toString()), "${Telephony.Sms.DATE} ASC")?.use { c ->
            while (c.moveToNext()) out += SmsRow(c.getLong(0), c.getString(1).orEmpty(), c.getLong(2), c.getInt(3))
        }
        return out
    }

    fun markThreadRead(context: Context, threadId: Long) {
        val values = ContentValues().apply {
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
        }
        context.contentResolver.update(SMS, values, "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0", arrayOf(threadId.toString()))
    }

    fun threadIdFor(context: Context, address: String): Long = Telephony.Threads.getOrCreateThreadId(context, address)
}
