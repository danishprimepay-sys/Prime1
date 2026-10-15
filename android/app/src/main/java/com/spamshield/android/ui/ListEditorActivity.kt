package com.spamshield.android.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ListView
import com.spamshield.android.data.ListEntry
import com.spamshield.android.data.ListKind
import com.spamshield.android.data.SpamRepository
import com.spamshield.android.runAsync
import com.spamshield.core.Patterns

/** Add/remove block- or allowlist patterns. */
class ListEditorActivity : BaseActivity() {
    private val repo by lazy { SpamRepository.get(this) }
    private val kind by lazy { ListKind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: ListKind.BLOCK.name) }
    private lateinit var adapter: SimpleListAdapter<ListEntry>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableUpNavigation(if (kind == ListKind.BLOCK) "Blocklist" else "Allowlist")
        adapter = SimpleListAdapter(this) { entry, row ->
            row.text(entry.pattern, 16f, bold = true)
            val prepared = Patterns.prepare(entry.pattern, repo.settings.countryCode)
            if (prepared != null && prepared != entry.pattern) row.text("matches as $prepared", 13f)
        }
        val list = ListView(this).apply {
            adapter = this@ListEditorActivity.adapter
            setOnItemClickListener { _, _, position, _ -> confirmRemove(this@ListEditorActivity.adapter.getItem(position)) }
        }
        val top = vertical {
            text(
                if (kind == ListKind.BLOCK) "Calls and texts from these numbers are always blocked."
                else "Calls and texts from these numbers are never blocked (checked before the blocklist).",
            )
            text("Enter a number, or a pattern: * matches any run of characters, ? matches exactly one. " +
                "Examples: +1800* (all 1-800 numbers), +1212555???? , SPAM* (sender IDs). Tap an entry to remove it.", 13f)
            val field = input("Number or pattern")
            button("Add") {
                val value = field.text.toString()
                runAsync({ repo.addEntry(kind, value) }) { result ->
                    if (result.getOrDefault(false)) {
                        field.setText("")
                        reload()
                    } else {
                        toast("Not a valid number or pattern")
                    }
                }
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(top)
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        reload()
    }

    private fun reload() {
        runAsync({ repo.entries(kind) }) { result -> result.getOrNull()?.let { adapter.items = it } }
    }

    private fun confirmRemove(entry: ListEntry) {
        AlertDialog.Builder(this)
            .setTitle("Remove ${entry.pattern}?")
            .setPositiveButton("Remove") { _, _ -> runAsync({ repo.removeEntry(entry.id) }) { reload() } }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val EXTRA_KIND = "kind"

        fun intent(context: Context, kind: ListKind): Intent =
            Intent(context, ListEditorActivity::class.java).putExtra(EXTRA_KIND, kind.name)
    }
}
