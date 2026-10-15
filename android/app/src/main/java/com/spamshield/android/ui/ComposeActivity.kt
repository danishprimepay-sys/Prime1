package com.spamshield.android.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import com.spamshield.android.runAsync
import com.spamshield.android.sms.Recipients
import com.spamshield.android.sms.SmsSender

/** New message screen; also handles SEND / SENDTO for sms:, smsto:, mms: and mmsto: links. */
class ComposeActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableUpNavigation("New message")
        val initialRecipients = Recipients.fromUri(intent.data)
        val initialBody = intent.getStringExtra("sms_body")
            ?: intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: Recipients.bodyFromUri(intent.data)

        setContentView(scrolling(vertical {
            val to = input("To (separate several numbers with commas)", initialRecipients.joinToString(", "), InputType.TYPE_CLASS_PHONE)
            val body = input(
                "Message", initialBody,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
            ).apply { minLines = 3 }
            button("Send") {
                val recipients = Recipients.parse(to.text.toString())
                val text = body.text.toString()
                when {
                    recipients.isEmpty() -> toast("Add a recipient")
                    text.isBlank() -> toast("Type a message")
                    else -> runAsync({ SmsSender.send(this@ComposeActivity, recipients, text) }) { result ->
                        result.fold(
                            onSuccess = {
                                if (recipients.size == 1) startActivity(ThreadActivity.intent(this@ComposeActivity, recipients.single()))
                                finish()
                            },
                            onFailure = { toast("Couldn't send: ${it.message}") },
                        )
                    }
                }
            }
        }))
    }
}
