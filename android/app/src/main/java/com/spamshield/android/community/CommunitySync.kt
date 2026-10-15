package com.spamshield.android.community

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.spamshield.android.Background
import com.spamshield.android.data.SpamRepository
import com.spamshield.core.community.BlocklistResult
import com.spamshield.core.community.CommunityClient
import com.spamshield.core.community.HttpCommunityClient
import com.spamshield.core.community.NumberInfo
import com.spamshield.core.community.Report
import com.spamshield.core.community.ReportChannel
import com.spamshield.core.community.ReportVerdict
import java.util.concurrent.TimeUnit

object CommunityConfig {
    const val DEFAULT_BASE_URL = "https://api.spamshield.example"
    const val MIN_SCORE = 0.5
}

/** Community blocklist sync, reports and lookups. Never used from the call-screening path. */
object CommunitySync {
    private const val TAG = "SpamShieldSync"
    private const val JOB_ID = 4201
    private val lock = Any()

    private fun client(repo: SpamRepository): CommunityClient = HttpCommunityClient(repo.settings.serverUrl)

    /** Schedules the daily sync (requires network); a no-op when already scheduled. */
    fun schedulePeriodic(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (scheduler.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, SyncJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(TimeUnit.DAYS.toMillis(1))
            .setPersisted(true)
            .build()
        scheduler.schedule(job)
    }

    /** Blocking sync. Returns a short human-readable status; never throws. */
    fun syncNow(context: Context): String = synchronized(lock) {
        val repo = SpamRepository.get(context)
        val settings = repo.settings
        val status = try {
            when (val result = client(repo).fetchBlocklist(CommunityConfig.MIN_SCORE, settings.blocklistEtag)) {
                BlocklistResult.NotModified -> "Up to date"
                is BlocklistResult.Updated -> {
                    val entries = result.blocklist.entries.mapNotNull { e ->
                        repo.normalize(e.number)?.let { it to e.score.coerceIn(0.0, 1.0) }
                    }
                    repo.replaceCommunity(entries)
                    settings.blocklistEtag = result.etag
                    "Updated: ${entries.size} numbers"
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Community sync failed", e)
            return@synchronized "Sync failed: ${e.message ?: e.javaClass.simpleName}".also { settings.lastSyncStatus = it }
        }
        settings.lastSyncAt = System.currentTimeMillis()
        settings.lastSyncStatus = status
        status
    }

    fun syncInBackground(context: Context) {
        val app = context.applicationContext
        Background.run { syncNow(app) }
    }

    /** Blocking report; throws on failure. Skips numbers that cannot be normalized (hidden callers). */
    fun report(context: Context, rawNumber: String?, channel: ReportChannel, verdict: ReportVerdict, category: String? = null) {
        val repo = SpamRepository.get(context)
        val number = repo.normalize(rawNumber) ?: throw IllegalArgumentException("No number to report")
        client(repo).report(Report(number, channel, verdict, repo.settings.installId, category))
    }

    /** Blocking, on-demand lookup for the UI. */
    fun lookup(context: Context, rawNumber: String): NumberInfo {
        val repo = SpamRepository.get(context)
        val number = repo.normalize(rawNumber) ?: throw IllegalArgumentException("Not a valid number")
        return client(repo).lookup(number)
    }
}
