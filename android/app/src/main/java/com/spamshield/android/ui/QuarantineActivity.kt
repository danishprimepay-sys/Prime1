package com.spamshield.android.ui

import android.app.AlertDialog
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ListView
import com.spamshield.android.data.ListKind
import com.spamshield.android.data.QuarantinedSms
import com.spamshield.android.data.SpamRepository
import com.spamshield.android.runAsync
import com.spamshield.android.sms.SmsStore
import com.spamshield.core.community.ReportChannel
import com.spamshield.core.community.ReportVerdict

/** Texts that were blocked: kept here instead of the system inbox. */
class QuarantineActivity : BaseActivity() {
    private val repo by lazy { SpamRepository.get(this) }
    private lateinit var adapter: SimpleListAdapter<QuarantinedSms>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableUpNavigation("Spam texts")
        adapter = SimpleListAdapter(this) { item, row ->
            row.text(item.sms.address ?: "Unknown sender", 16f, bold = true)
            row.text(item.sms.body.take(160), 14f)
            row.text("${formatTime(item.sms.receivedAt)} · ${describeReasons(item.reasons)}", 12f)
        }
        val list = ListView(this).apply {
            adapter = this@QuarantineActivity.adapter
            setOnItemClickListener { _, _, position, _ -> showActions(this@QuarantineActivity.adapter.getItem(position)) }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(vertical(padding = 12) {
                text("Blocked texts are not added to your inbox and don't notify you. Tap one for options.", 13f)
            })
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        reload()
    }

    private fun reload() {
        runAsync({ repo.quarantined() }) { result -> result.getOrNull()?.let { adapter.items = it } }
    }

    private fun showActions(item: QuarantinedSms) {
        val address = item.sms.address
        val content = vertical {
            text(item.sms.body, 15f)
            text("Received ${formatTime(item.sms.receivedAt)}\nWhy: ${describeReasons(item.reasons)} (score ${"%.2f".format(item.score)})", 12f)
        }
        lateinit var dialog: AlertDialog
        content.button("Restore to inbox") { dialog.dismiss(); restore(item) }
        content.button("Report spam & delete") {
            dialog.dismiss()
            reportNumber(address, ReportChannel.SMS, ReportVerdict.SPAM)
            delete(item)
        }
        content.button("Not spam (report, allow sender, restore)") {
            dialog.dismiss()
            reportNumber(address, ReportChannel.SMS, ReportVerdict.NOT_SPAM)
            addToList(ListKind.ALLOW, address)
            restore(item)
        }
        content.button("Delete") { dialog.dismiss(); delete(item) }
        dialog = AlertDialog.Builder(this)
            .setTitle(address ?: "Unknown sender")
            .setView(scrolling(content))
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
    }

    private fun restore(item: QuarantinedSms) {
        runAsync({
            checkNotNull(SmsStore.insertInbox(this, item.sms, read = true)) { "provider rejected the message" }
            repo.deleteQuarantined(item.id)
        }) { result ->
            toast(result.fold({ "Restored to inbox" }, { "Couldn't restore (is SpamShield the default SMS app?)" }))
            reload()
        }
    }

    private fun delete(item: QuarantinedSms) {
        runAsync({ repo.deleteQuarantined(item.id) }) { reload() }
    }
}
