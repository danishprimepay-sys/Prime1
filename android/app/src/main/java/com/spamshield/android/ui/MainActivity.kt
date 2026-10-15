package com.spamshield.android.ui

import android.Manifest
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.spamshield.android.community.CommunitySync
import com.spamshield.android.data.ListKind
import com.spamshield.android.data.SpamRepository
import com.spamshield.android.runAsync

/** Status of the two roles, settings, list editors and entry points to logs and messages. */
class MainActivity : BaseActivity() {
    private val repo by lazy { SpamRepository.get(this) }
    private val roleManager: RoleManager by lazy { getSystemService(RoleManager::class.java)!! }

    private lateinit var callRoleStatus: TextView
    private lateinit var callRoleButton: Button
    private lateinit var smsRoleStatus: TextView
    private lateinit var smsRoleButton: Button
    private lateinit var permissionStatus: TextView
    private lateinit var permissionButton: Button
    private lateinit var syncStatus: TextView
    private lateinit var callLogButton: Button
    private lateinit var quarantineButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "SpamShield"
        setContentView(scrolling(vertical { buildContent() }))
        if (savedInstanceState == null) CommunitySync.syncInBackground(this)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun LinearLayout.buildContent() {
        header("Protection")
        callRoleStatus = text("")
        callRoleButton = button("Use SpamShield to screen calls") { requestRole(RoleManager.ROLE_CALL_SCREENING) }
        smsRoleStatus = text("")
        smsRoleButton = button("Make SpamShield the default SMS app") { requestRole(RoleManager.ROLE_SMS) }
        permissionStatus = text("")
        permissionButton = button("Grant permissions") { requestRuntimePermissions() }

        header("Messages")
        button("Conversations") { startActivity(Intent(this@MainActivity, ConversationsActivity::class.java)) }
        button("New message") { startActivity(Intent(this@MainActivity, ComposeActivity::class.java)) }

        header("Blocked & flagged")
        callLogButton = button("Blocked & flagged calls") { startActivity(Intent(this@MainActivity, CallLogActivity::class.java)) }
        quarantineButton = button("Spam texts") { startActivity(Intent(this@MainActivity, QuarantineActivity::class.java)) }

        header("Your lists")
        button("Blocklist") { startActivity(ListEditorActivity.intent(this@MainActivity, ListKind.BLOCK)) }
        button("Allowlist") { startActivity(ListEditorActivity.intent(this@MainActivity, ListKind.ALLOW)) }

        header("Settings")
        val settings = repo.settings
        toggle("Block hidden / private numbers", settings.blockHiddenNumbers) { settings.blockHiddenNumbers = it }
        toggle("Detect neighbor spoofing", settings.detectNeighborSpoofing) { settings.detectNeighborSpoofing = it }
        text("Calls that share your area code and exchange are flagged. Needs your number:", 13f)
        val myNumber = input("My phone number", settings.userNumber, InputType.TYPE_CLASS_PHONE)
        val countryCode = input("Country calling code (1 = US/Canada)", settings.countryCode, InputType.TYPE_CLASS_NUMBER)
        toggle("Use on-device text classifier", settings.useClassifier) { settings.useClassifier = it }
        toggle("Use community reports", settings.useCommunity) { settings.useCommunity = it }
        val serverUrl = input("Community server URL", settings.serverUrl, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        button("Save") {
            settings.userNumber = myNumber.text.toString()
            settings.countryCode = countryCode.text.toString()
            val urlChanged = serverUrl.text.toString().trim() != settings.serverUrl
            settings.serverUrl = serverUrl.text.toString()
            if (urlChanged) settings.blocklistEtag = null
            countryCode.setText(settings.countryCode)
            serverUrl.setText(settings.serverUrl)
            toast("Saved")
        }

        header("Community")
        syncStatus = text("")
        button("Sync community blocklist now") { syncNow() }
        val lookupInput = input("Look up a number", inputType = InputType.TYPE_CLASS_PHONE)
        button("Look up") { lookUp(lookupInput.text.toString()) }
    }

    private fun LinearLayout.toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        addView(Switch(context).apply {
            text = label
            textSize = 15f
            isChecked = checked
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _: CompoundButton, value: Boolean -> onChange(value) }
        })
    }

    private fun refreshStatus() {
        val hasCallRole = roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
        callRoleStatus.text = if (hasCallRole) "✓ Screening incoming calls" else "✗ Call screening is off"
        callRoleButton.isEnabled = !hasCallRole && roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)

        val hasSmsRole = roleManager.isRoleHeld(RoleManager.ROLE_SMS)
        smsRoleStatus.text = if (hasSmsRole) "✓ Filtering text messages (default SMS app)"
        else "✗ Text filtering is off. SpamShield must be your default SMS app to filter texts."
        smsRoleButton.isEnabled = !hasSmsRole && roleManager.isRoleAvailable(RoleManager.ROLE_SMS)

        val missing = missingPermissions()
        permissionStatus.text = if (missing.isEmpty()) "✓ Contacts and notification access granted"
        else "Contacts access lets known callers through; notifications show new texts."
        permissionButton.isEnabled = missing.isNotEmpty()

        val settings = repo.settings
        runAsync({ Triple(repo.communityCount(), repo.callLog().size, repo.quarantineCount()) }) { result ->
            val (community, calls, texts) = result.getOrNull() ?: return@runAsync
            val last = settings.lastSyncAt.takeIf { it > 0 }?.let(::formatTime) ?: "never"
            syncStatus.text = "$community numbers cached · last sync: $last\n${settings.lastSyncStatus.orEmpty()}"
            callLogButton.text = "Blocked & flagged calls ($calls)"
            quarantineButton.text = "Spam texts ($texts)"
        }
    }

    private fun requestRole(role: String) {
        startActivityForResult(roleManager.createRequestRoleIntent(role), REQUEST_ROLE)
    }

    private fun missingPermissions(): List<String> {
        val wanted = buildList {
            add(Manifest.permission.READ_CONTACTS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun requestRuntimePermissions() {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        refreshStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    private fun syncNow() {
        syncStatus.text = "Syncing…"
        runAsync({ CommunitySync.syncNow(this) }) { refreshStatus() }
    }

    private fun lookUp(number: String) {
        if (number.isBlank()) return
        runAsync({ CommunitySync.lookup(this, number) }) { result ->
            val message = result.fold(
                onSuccess = { info ->
                    buildString {
                        append("Spam score: ${"%.0f".format(info.score * 100)}%\n")
                        append("Spam reports: ${info.spamReports}\n")
                        append("Not-spam reports: ${info.notSpamReports}\n")
                        append("Last reported: ${info.lastReportedAt ?: "never"}")
                    }
                },
                onFailure = { "Lookup failed: ${it.message}" },
            )
            AlertDialog.Builder(this).setTitle(repo.normalize(number) ?: number).setMessage(message)
                .setPositiveButton(android.R.string.ok, null).show()
        }
    }

    private companion object {
        const val REQUEST_ROLE = 1
        const val REQUEST_PERMISSIONS = 2
    }
}
