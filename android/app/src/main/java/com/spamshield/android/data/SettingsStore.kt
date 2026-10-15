package com.spamshield.android.data

import android.content.Context
import com.spamshield.android.community.CommunityConfig
import com.spamshield.core.Numbers
import com.spamshield.core.Settings
import java.util.UUID

/** User preferences and small bits of sync state, backed by SharedPreferences. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("spamshield", Context.MODE_PRIVATE)

    var blockHiddenNumbers: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_HIDDEN, false)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_HIDDEN, value).apply()

    var detectNeighborSpoofing: Boolean
        get() = prefs.getBoolean(KEY_NEIGHBOR, true)
        set(value) = prefs.edit().putBoolean(KEY_NEIGHBOR, value).apply()

    var userNumber: String?
        get() = prefs.getString(KEY_USER_NUMBER, null)
        set(value) = prefs.edit().putString(KEY_USER_NUMBER, value?.trim()?.ifEmpty { null }).apply()

    var useClassifier: Boolean
        get() = prefs.getBoolean(KEY_CLASSIFIER, true)
        set(value) = prefs.edit().putBoolean(KEY_CLASSIFIER, value).apply()

    var useCommunity: Boolean
        get() = prefs.getBoolean(KEY_COMMUNITY, true)
        set(value) = prefs.edit().putBoolean(KEY_COMMUNITY, value).apply()

    var countryCode: String
        get() = prefs.getString(KEY_COUNTRY_CODE, null) ?: Numbers.DEFAULT_COUNTRY_CODE
        set(value) = prefs.edit().putString(KEY_COUNTRY_CODE, value.filter(Char::isDigit).ifEmpty { Numbers.DEFAULT_COUNTRY_CODE }).apply()

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, null) ?: CommunityConfig.DEFAULT_BASE_URL
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value.trim().ifEmpty { CommunityConfig.DEFAULT_BASE_URL }).apply()

    var blocklistEtag: String?
        get() = prefs.getString(KEY_ETAG, null)
        set(value) = prefs.edit().putString(KEY_ETAG, value).apply()

    var lastSyncAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC_AT, value).apply()

    var lastSyncStatus: String?
        get() = prefs.getString(KEY_LAST_SYNC_STATUS, null)
        set(value) = prefs.edit().putString(KEY_LAST_SYNC_STATUS, value).apply()

    /** Random, stable per-install identifier sent with community reports. */
    val installId: String
        @Synchronized get() = prefs.getString(KEY_INSTALL_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_INSTALL_ID, it).commit()
        }

    fun engineSettings() = Settings(
        blockHiddenNumbers = blockHiddenNumbers,
        detectNeighborSpoofing = detectNeighborSpoofing,
        userNumber = userNumber,
        useClassifier = useClassifier,
        useCommunity = useCommunity,
        countryCode = countryCode,
    )

    private companion object {
        const val KEY_BLOCK_HIDDEN = "block_hidden_numbers"
        const val KEY_NEIGHBOR = "detect_neighbor_spoofing"
        const val KEY_USER_NUMBER = "user_number"
        const val KEY_CLASSIFIER = "use_classifier"
        const val KEY_COMMUNITY = "use_community"
        const val KEY_COUNTRY_CODE = "country_code"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_ETAG = "blocklist_etag"
        const val KEY_LAST_SYNC_AT = "last_sync_at"
        const val KEY_LAST_SYNC_STATUS = "last_sync_status"
        const val KEY_INSTALL_ID = "install_id"
    }
}
