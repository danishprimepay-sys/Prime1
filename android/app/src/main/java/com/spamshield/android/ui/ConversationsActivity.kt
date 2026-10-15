package com.spamshield.android.ui

import android.app.role.RoleManager
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.spamshield.android.data.Contacts
import com.spamshield.android.runAsync
import com.spamshield.android.sms.Conversation
import com.spamshield.android.sms.SmsStore

/** Latest message per conversation from the system SMS provider. */
class ConversationsActivity : BaseActivity() {
    private lateinit var adapter: SimpleListAdapter<Pair<Conversation, String>>
    private lateinit var banner: TextView
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = reload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableUpNavigation("Messages")
        adapter = SimpleListAdapter(this) { (conversation, name), row ->
            row.text(name, 16f, bold = conversation.unread)
            row.text(conversation.snippet.take(100), 14f).apply {
                if (conversation.unread) setTypeface(typeface, Typeface.BOLD)
                maxLines = 2
            }
            row.text(formatTime(conversation.date), 12f)
        }
        val list = ListView(this).apply {
            adapter = this@ConversationsActivity.adapter
            setOnItemClickListener { _, _, position, _ ->
                val conversation = this@ConversationsActivity.adapter.getItem(position).first
                startActivity(ThreadActivity.intent(this@ConversationsActivity, conversation.address, conversation.threadId))
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(vertical(padding = 8) {
                banner = text("", 13f)
                button("New message") { startActivity(Intent(this@ConversationsActivity, ComposeActivity::class.java)) }
            })
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })
    }

    override fun onStart() {
        super.onStart()
        contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        reload()
    }

    override fun onStop() {
        contentResolver.unregisterContentObserver(observer)
        super.onStop()
    }

    private fun reload() {
        val isDefault = getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true
        banner.text = if (isDefault) "" else "SpamShield isn't your default SMS app, so it can't receive or send texts."
        banner.visibility = if (isDefault) TextView.GONE else TextView.VISIBLE
        runAsync({
            SmsStore.conversations(this).map { it to (Contacts.displayName(this, it.address) ?: it.address) }
        }) { result ->
            result.onSuccess { adapter.items = it }.onFailure { banner.text = "Can't read messages: ${it.message}"; banner.visibility = TextView.VISIBLE }
        }
    }
}
