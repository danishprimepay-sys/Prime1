package com.spamshield.android

import android.app.Application
import com.spamshield.android.community.CommunitySync
import com.spamshield.android.data.SpamRepository
import com.spamshield.android.sms.Notifications

class SpamShieldApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        CommunitySync.schedulePeriodic(this)
        // Parse the rules and model now so the first screened call doesn't pay for it.
        Background.run { SpamRepository.get(this).engine }
    }
}
