package com.spamshield.android.ui

import android.app.Activity
import com.spamshield.android.community.CommunitySync
import com.spamshield.android.data.ListKind
import com.spamshield.android.data.SpamRepository
import com.spamshield.android.runAsync
import com.spamshield.core.community.ReportChannel
import com.spamshield.core.community.ReportVerdict

/** Actions shared by the call log and quarantine screens. */
internal fun Activity.reportNumber(number: String?, channel: ReportChannel, verdict: ReportVerdict, then: () -> Unit = {}) {
    runAsync({ CommunitySync.report(this, number, channel, verdict) }) { result ->
        toast(result.fold({ "Report sent. Thank you!" }, { "Couldn't send report: ${it.message}" }))
        then()
    }
}

internal fun Activity.addToList(kind: ListKind, number: String?, then: () -> Unit = {}) {
    val repo = SpamRepository.get(this)
    val normalized = repo.normalize(number) ?: run {
        toast("No number to add")
        return
    }
    runAsync({ repo.addEntry(kind, normalized) }) { result ->
        val list = if (kind == ListKind.BLOCK) "blocklist" else "allowlist"
        toast(if (result.getOrDefault(false)) "Added $normalized to $list" else "Couldn't add $normalized")
        then()
    }
}
