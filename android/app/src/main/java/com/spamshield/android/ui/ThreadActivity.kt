package com.spamshield.android.ui

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import com.spamshield.android.data.Contacts
import com.spamshield.android.runAsync
import com.spamshield.android.sms.Notifications
import com.spamshield.android.sms.SmsRow
import com.spamshield.android.sms.SmsSender
import com.spamshield.android.sms.SmsStore

/** One conversation: messages oldest-first plus a send box. Opening it marks the thread read. */
class ThreadActivity : BaseActivity() {
    private lateinit var address: String
    @Volatile private var threadId = -1L
    private lateinit var adapter: SimpleListAdapter<SmsRow>
    private lateinit var list: ListView
    private lateinit var input: EditText
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = reload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        address = intent.getStringExtra(EXTRA_ADDRESS).orEmpty()
        threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1L)
        enableUpNavigation(address)
        runAsync({ Contacts.displayName(this, address) }) { result -> result.getOrNull()?.let { title = it } }

        adapter = SimpleListAdapter(this) { message, row -> bindMessage(message, row) }
        list = ListView(this).apply {
            adapter = this@ThreadActivity.adapter
            divider = null
            transcriptMode = ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL
            isStackFromBottom = true
        }
        val sendRow = horizontal {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            input = EditText(context).apply {
                hint = "Text message"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                maxLines = 5
            }
            addWeighted(input)
            button("Send") { send() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(sendRow)
        })
    }

    override fun onStart() {
        super.onStart()
        contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        Notifications.cancelFor(this, address)
        reload()
    }

    override fun onStop() {
        contentResolver.unregisterContentObserver(observer)
        super.onStop()
    }

    private fun bindMessage(message: SmsRow, row: LinearLayout) {
        row.gravity = if (message.isIncoming) Gravity.START else Gravity.END
        row.setPadding(dp(8), dp(4), dp(8), dp(4))
        row.text(message.body, 15f).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (message.isIncoming) Color.rgb(0xEC, 0xEF, 0xF1) else Color.rgb(0xC8, 0xE6, 0xC9))
            }
            setTextColor(Color.BLACK)
        }
        val status = if (message.isFailed) " · Not sent" else ""
        row.text(formatTime(message.date) + status, 11f)
    }

    private fun reload() {
        runAsync({
            if (threadId < 0 && address.isNotEmpty()) threadId = SmsStore.threadIdFor(this, address)
            // Marking read requires the default SMS role; reading does not.
            runCatching { SmsStore.markThreadRead(this, threadId) }
            SmsStore.messages(this, threadId)
        }) { result -> result.getOrNull()?.let { adapter.items = it } }
    }

    private fun send() {
        val body = input.text.toString()
        if (body.isBlank() || address.isEmpty()) return
        input.setText("")
        runAsync({ SmsSender.send(this, listOf(address), body) }) { result ->
            result.onFailure { toast("Couldn't send: ${it.message}") }
            reload()
        }
    }

    companion object {
        private const val EXTRA_ADDRESS = "address"
        private const val EXTRA_THREAD_ID = "thread_id"

        fun intent(context: Context, address: String, threadId: Long = -1L): Intent =
            Intent(context, ThreadActivity::class.java)
                .putExtra(EXTRA_ADDRESS, address)
                .putExtra(EXTRA_THREAD_ID, threadId)
    }
}
