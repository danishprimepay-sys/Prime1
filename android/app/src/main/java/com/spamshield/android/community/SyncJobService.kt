package com.spamshield.android.community

import android.app.job.JobParameters
import android.app.job.JobService
import com.spamshield.android.Background

/** Daily community blocklist refresh, scheduled by [CommunitySync.schedulePeriodic]. */
class SyncJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Background.run {
            try {
                CommunitySync.syncNow(applicationContext)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
