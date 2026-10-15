package com.spamshield.android.ui

import android.app.AlertDialog
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ListView
import com.spamshield.android.data.CallLogEntry
import com.spamshield.android.data.ListKind
import com.spamshield.android.data.SpamRepository
import com.spamshield.android.runAsync
import com.spamshield.core.Action
import com.spamshield.core.community.ReportChannel
import com.spamshield.core.community.ReportVerdict

/** Calls SpamShield blocked or silenced. */
class CallLogActivity : BaseActivity() {
    private val repo by lazy { SpamRepository.get(this) }
    private lateinit var adapter: SimpleListAdapter<CallLogEntry>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableUpNavigation("Blocked & flagged calls")
        adapter = SimpleListAdapter(this) { entry, row ->
            val verb = if (entry.action == Action.BLOCK) "Blocked" else "Silenced (possible spam)"
            row.text(entry.number ?: "Hidden number", 16f, bold = true)
            row.text("$verb · ${formatTime(entry.at)}", 13f)
            row.text("Why: ${describeReasons(entry.reasons)} (score ${"%.2f".format(entry.score)})", 13f)
        }
        val list = ListView(this).apply {
            adapter = this@CallLogActivity.adapter
            setOnItemClickListener { _, _, position, _ -> showActions(this@CallLogActivity.adapter.getItem(position)) }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(vertical(padding = 8) {
                button("Clear log") {
                    AlertDialog.Builder(this@CallLogActivity).setTitle("Clear the call log?")
                        .setPositiveButton("Clear") { _, _ -> runAsync({ repo.clearCallLog() }) { reload() } }
                        .setNegativeButton(android.R.string.cancel, null).show()
                }
            })
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        reload()
    }

    private fun reload() {
        runAsync({ repo.callLog() }) { result -> result.getOrNull()?.let { adapter.items = it } }
    }

    private fun showActions(entry: CallLogEntry) {
        val number = entry.number
        val actions = buildList<Pair<String, () -> Unit>> {
            if (number != null) {
                add("Add to blocklist" to { addToList(ListKind.BLOCK, number) })
                add("Add to allowlist (never block)" to { addToList(ListKind.ALLOW, number) })
                add("Report as spam" to { reportNumber(number, ReportChannel.CALL, ReportVerdict.SPAM) })
                add("Report as not spam" to { reportNumber(number, ReportChannel.CALL, ReportVerdict.NOT_SPAM) })
            }
            add("Delete entry" to { runAsync({ repo.deleteCallLogEntry(entry.id) }) { reload() } })
        }
        AlertDialog.Builder(this)
            .setTitle(number ?: "Hidden number")
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }
}

internal fun describeReasons(reasons: List<String>): String = reasons.joinToString(", ") { reason ->
    when {
        reason.startsWith("keyword:") -> "matches \"" + reason.removePrefix("keyword:").replace('_', ' ') + "\" scam pattern"
        else -> REASON_LABELS[reason] ?: reason
    }
}.ifEmpty { "no signals" }

private val REASON_LABELS = mapOf(
    "contact" to "in contacts",
    "allowlist" to "on your allowlist",
    "blocklist" to "on your blocklist",
    "hidden_number" to "hidden number",
    "verification_failed" to "caller ID verification failed",
    "invalid_number" to "invalid number",
    "neighbor_spoof" to "neighbor spoofing",
    "community" to "reported by the community",
    "sender_email" to "sent from an email address",
    "url_ip_host" to "link to a raw IP address",
    "url_shortener" to "shortened link",
    "url_suspicious_tld" to "link to a suspicious domain",
    "classifier" to "looks like spam",
)
