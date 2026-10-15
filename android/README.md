# SpamShield for Android

Spam call and text blocker for Android 10+ (minSdk 29, target/compile SDK 35). Detection follows
[`shared/SPEC.md`](../shared/SPEC.md) exactly and is verified against every file in
`shared/test-vectors/`.

## Modules

| Module | What it is |
| --- | --- |
| `core` | Pure Kotlin/JVM library: number normalization, block/allow patterns, URL signals, SMS tokenizer + classifier, `Engine.evaluate()`, data classes (`Rules`, `ClassifierModel`, `Settings`, `Event`, `EngineContext`, `Verdict`) and the community backend client (`CommunityClient` interface + `HttpCommunityClient` on `HttpURLConnection`). |
| `app` | The Android application (`com.spamshield.android`). Framework APIs only, with no AndroidX or Google Maven libraries. The UI is built in code (no XML layouts, no `R` references). Only part of the build when an Android SDK is available. |
| `app-typecheck` | Only part of the build when **no** SDK is available. It compiles `app/src/main/java` against Robolectric's `android-all` (API 35) jar from Maven Central, so the app code can be type-checked without the SDK or AGP. |

`settings.gradle.kts` picks between `:app` and `:app-typecheck` by checking for `ANDROID_HOME`,
`ANDROID_SDK_ROOT` or `sdk.dir` in `local.properties` (it must point to an existing directory).

The rules (`shared/rules.json`) and model (`shared/model/sms_classifier.json`) are not copied.
`core`'s `processResources` bundles the single shared copy as classpath resources under
`spamshield/`, and `Engine.loadBundled()` reads them with `getResourceAsStream`. This works the same
on the JVM and inside the APK.

### App structure (`app/src/main/java/com/spamshield/android`)

- `data/SpamRepository`: the single facade. It owns the engine, the SQLite database
  (`SpamDatabase`: block/allow lists, community cache, call log, SMS quarantine) and
  `SettingsStore` (SharedPreferences). `contextFor(number)` builds the engine context: both lists,
  the settings, and only the community score for the normalized number being checked.
- `call/SpamCallScreeningService`: call screening.
- `sms/`: everything a default SMS app needs. That covers `SmsDeliverReceiver`, `MmsDeliverReceiver`,
  `HeadlessSmsSendService`, `SmsFilter` (verdict to filing), `SmsStore` (system SMS provider),
  `SmsSender`, `SmsSentReceiver` and `Notifications`.
- `community/`: `CommunitySync` (blocklist sync, reports, lookups) and `SyncJobService` (daily job).
- `ui/`: `MainActivity` (roles, permissions, settings, community), conversation list, thread view,
  compose, list editors, call log and spam-text quarantine.

## How it works

### Call screening

The user grants `ROLE_CALL_SCREENING` from the main screen. `SpamCallScreeningService.onScreenCall`
handles **incoming** calls only and allows every other call:

- The number is `handle.schemeSpecificPart`. It counts as hidden (`null`) when the presentation is
  not `PRESENTATION_ALLOWED` (restricted, unknown, payphone, unavailable) or the number is empty.
- Verification comes from `callerNumberVerificationStatus` on API 30+ (passed, failed or unknown).
  It is always `unknown` on API 29.
- `isContact` comes from `ContactsContract.PhoneLookup` when `READ_CONTACTS` is granted.
- Evaluation is entirely local. It never touches the network; community data comes from the
  locally cached blocklist. It runs on a worker thread. If the worker hasn't answered after 4 s, or
  if anything throws, the call is **allowed**, so a bug never blocks a call.

| Verdict | Effect |
| --- | --- |
| `ALLOW` | Normal call. |
| `WARN` | The phone doesn't ring (`setSilenceCall`), but the call still shows and can be answered. It is logged. |
| `BLOCK` | Rejected (`setDisallowCall` + `setRejectCall`) with no missed-call notification. It stays in the system call log and is logged in the app. |

Every non-`ALLOW` verdict is recorded under **Blocked & flagged calls** with the number, time,
verdict, score and reasons. Each entry has actions: add to blocklist or allowlist, report as spam or
not spam.

### SMS filtering

Android only lets the **default SMS app** intercept texts, so SpamShield must hold `ROLE_SMS`. It
therefore implements a complete, minimal messaging app: a conversation list, a thread view with a
send box, a compose screen (`SEND`/`SENDTO` for `sms:`/`smsto:`/`mms:`/`mmsto:`), and "respond via
message".

On `SMS_DELIVER`, the receiver joins the multipart PDUs (`getMessagesFromIntent`) and evaluates the
message. The DB and provider work runs under `goAsync()` on a background thread.

| Verdict | Effect |
| --- | --- |
| `ALLOW` | Written to `Telephony.Sms.Inbox` with a normal notification. |
| `WARN` | Written to the inbox, with a low-importance notification titled "Possible spam". |
| `BLOCK` | **Not** written to the system provider. It goes into the app's quarantine (**Spam texts**) with no notification. |

If evaluation fails, the message is delivered normally, so texts are never lost.

From the quarantine, the user can:

- **Restore to inbox**: writes the message to the system inbox.
- **Report spam & delete**: reports the sender as spam and removes the message from quarantine.
- **Not spam**: reports `not_spam`, adds the sender to the allowlist and restores the message.
- **Delete**: removes the message from quarantine.

Sent messages are written to `Telephony.Sms.Sent`. If the radio reports a send error, the row is
marked failed. Opening a thread marks its messages read.

### Lists and settings

- Block and allow lists accept numbers or wildcard patterns: `*` matches any run of characters and
  `?` matches exactly one. Examples: `+1800*`, `+1212555????`, `SPAM*`. Patterns are prepared per
  SPEC §2, and entries that could never match are rejected.
- The allowlist is checked before the blocklist, and contacts are checked before both.
- Settings:
  - block hidden numbers
  - neighbor-spoof detection, plus "my phone number"
  - on-device classifier
  - community reports
  - country calling code (default `1`)
  - community server URL

### Community backend

`CommunityConfig.DEFAULT_BASE_URL = "https://api.spamshield.example"` (editable in settings).

- `GET /v1/blocklist?minScore=0.5` with `If-None-Match`. A 304 means nothing changed. On a 200, the
  local community cache (normalized number to score) is replaced in a single transaction and the
  ETag is stored. The sync runs when the app opens and daily through `JobScheduler`. The daily job
  requires a network connection and persists across reboots. Network errors are recorded as the
  sync status and otherwise ignored.
- `POST /v1/reports`: `{number, channel, verdict, category?, installId}`. The `installId` is a
  random UUID generated once and stored in preferences. The number sent is the normalized one.
- `GET /v1/numbers/{number}`: only for the "Look up a number" button on the main screen. It is never
  called during screening.

## Building

```sh
cd android
./gradlew :core:test                 # engine + all shared test vectors (no SDK needed)
./gradlew :app:assembleDebug         # needs an Android SDK (ANDROID_HOME or local.properties)
./gradlew :app-typecheck:compileKotlin   # without an SDK: type-check the app sources
```

The APK is written to `app/build/outputs/apk/debug/`. CI (`.github/workflows/android.yml`) runs
`:core:test :app:assembleDebug` on JDK 17 and uploads the debug APK.

## Limitations

- **MMS is not supported in v1.** The `WAP_PUSH_DELIVER` receiver exists, as the SMS role requires,
  but it doesn't download or store MMS; it only posts a notification. While SpamShield is the
  default SMS app, picture and group messages (MMS), including those from unknown senders, will not
  be shown. Switch the default SMS app back to view them.
- **RCS / chat features** belong to the carrier's or Google's messaging app and are not visible to
  SpamShield.
- **Play Store policy:** SMS permissions are only granted to apps that are genuinely the user's
  default SMS handler. SpamShield qualifies only because it implements a full SMS client, so these
  components must not be removed. Distributing on Google Play also requires the Permissions
  Declaration Form.
- `WARN` calls are silenced, not labelled. The framework call screening API has no way to show a
  "suspected spam" label on the incoming-call screen.
- Caller-ID verification is unavailable on Android 10 (API 29). It is treated as `unknown` there.
- The conversation list and thread view are deliberately minimal: no search, no attachments, no
  per-message delete. On dual-SIM phones, outgoing texts use the default SMS subscription.
