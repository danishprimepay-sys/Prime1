# SpamShield for iOS

The iPhone app, its two system extensions, and `SpamShieldCore`, the Swift
implementation of the shared detection spec (`shared/SPEC.md`).

```
ios/
├── project.yml                 XcodeGen spec (SpamShield.xcodeproj is generated, not committed)
├── SpamShieldCore/             Swift package: engine, storage, backend client (Foundation only)
│   ├── Sources/SpamShieldCore/
│   │   ├── PhoneNumbers, NumberPattern, URLHosts, SMSClassifier, SpamEngine   (SPEC §1–5)
│   │   ├── Rules, Models                       Codable rules/model/event/context/verdict
│   │   ├── SharedStore                         JSON files in the App Group container
│   │   ├── CommunityClient, CommunityAPI       /v1/blocklist, /v1/reports, /v1/numbers
│   │   ├── CallDirectoryBuilder                lists → sorted CallKit entries
│   │   └── Resources/                          copies of shared/rules.json + sms_classifier.json
│   └── Tests/SpamShieldCoreTests/              shared vectors (read from ../shared) + unit tests
└── SpamShield/
    ├── App/              SwiftUI app (com.spamshield.ios)
    ├── CallDirectory/    Call Directory extension (com.spamshield.ios.CallDirectory)
    └── MessageFilter/    SMS filter extension (com.spamshield.ios.MessageFilter)
```

## How it works

All three targets share data through the App Group `group.com.spamshield.ios`.
The app writes the user's lists, settings and the downloaded community
blocklist into it with `SharedStore` (plain JSON files, atomic writes). The
extensions only read it.

- **App** — onboarding, status, block/allow list editor, settings, number
  lookup and reporting, a message tester that runs the engine on pasted text,
  and *Sync now*. Syncing downloads `GET /v1/blocklist` (using its ETag),
  stores it, and calls `CXCallDirectoryManager.reloadExtension`. It also syncs
  on launch when the data is over 6 hours old, and schedules a
  `BGAppRefreshTask` (`com.spamshield.ios.refresh`) to keep it fresh.
- **CallDirectory** — when iOS asks, it builds the entries with
  `CallDirectoryBuilder` and hands them to CallKit. Exact block-list numbers and
  community numbers scoring at or above the block threshold are **blocked**.
  Community numbers between the warn and block thresholds are **labeled**
  "Suspected spam". Allow-listed numbers are left out.
- **MessageFilter** — runs `SpamEngine.evaluate` offline on each text from an
  unknown sender. WARN or BLOCK goes to Junk, ALLOW is delivered, and on any
  failure it answers `.none` so iOS decides. It records flagged senders (but
  not bodies, unless the user opts in) to the shared log on a best-effort basis.

The backend URL defaults to `https://api.spamshield.example`
(`CommunityClient.defaultBaseURL`) and can be changed in Settings. Reports
carry a random install ID that is generated once and stored in the App Group.

## What iOS allows (vs Android)

| | Android | iOS |
| --- | --- | --- |
| Live call screening | Yes (`CallScreeningService` sees each call) | **No.** Apps never see incoming calls. |
| Call blocking | Any rule, decided per call | Only a pre-built list of exact numbers (Call Directory). `*` patterns can't be expressed, so they apply to texts only. `?` patterns are expanded into numbers, up to 10,000 in total. |
| Hidden / No Caller ID calls | Can be blocked | Not visible to apps. Users can turn on **Silence Unknown Callers**. |
| STIR/SHAKEN, neighbor spoofing | Checked per call | Not available for calls. The settings still apply in the tester. |
| SMS | Can read and act on messages | The filter only sees texts from **unknown senders**. It can only route them to Junk (or Transactions/Promotions) and can't delete or reply. |
| Network in extensions | n/a | The extensions here are fully offline. Message filters may only use Apple's deferred-network mechanism, which we don't use. Call Directory data must already be on the device. |

## Build

```sh
# App (requires Xcode 15+)
brew install xcodegen
cd ios && xcodegen && open SpamShield.xcodeproj

# Engine tests (macOS or Linux)
cd ios/SpamShieldCore && swift test
```

The package's `Resources/` are copies of `shared/rules.json` and
`shared/model/sms_classifier.json`, because SwiftPM can't reference files
outside the package. After changing shared data run `scripts/sync-shared.sh`.
CI runs `scripts/sync-shared.sh --check` and fails if the copies are stale. The
tests read `shared/test-vectors/*.json` in place.

CI (`.github/workflows/ios.yml`) runs the package tests and builds the app for
the Simulator without code signing.

## Signing and App Group setup

To run on a device:

1. In the Apple Developer portal, create the App IDs `com.spamshield.ios`,
   `com.spamshield.ios.CallDirectory` and `com.spamshield.ios.MessageFilter`
   (or change the bundle IDs in `project.yml` and `AppIdentifiers.swift` to
   your own prefix).
2. Create the App Group `group.com.spamshield.ios` and enable it for all three
   App IDs. If you rename it, update the three `.entitlements` files and
   `AppIdentifiers.appGroup`.
3. Set `DEVELOPMENT_TEAM` in `project.yml` (or in Xcode for each target), run
   `xcodegen`, and build. Automatic signing creates the profiles.
4. On the phone, turn on SpamShield in **Settings › Phone › Call Blocking &
   Identification** and choose it under **Settings › Apps › Messages › Unknown
   & Spam › SMS Filtering** (on iOS 16–17 this is **Settings › Messages ›
   Unknown & Spam**). The app's setup screen walks through this.

Without the App Group entitlement (for example in unsigned Simulator builds),
the app falls back to private storage and shows a warning. The extensions
then see no data.

## Limitations and notes

- The Call Directory is rebuilt in full on every reload. Large community lists
  are limited by the extension's memory budget. The builder keeps only
  `Int64`s, and wildcard expansion is capped at 10,000 numbers.
- Community scores are refreshed only when the app runs (foreground or
  background refresh), so blocking lags the server.
- iOS may refuse message-filter writes to the shared container. If it does,
  the "Recently filtered texts" list stays empty, but filtering still works.
- The message filter treats every sender as a non-contact (`isContact = false`),
  because iOS never passes contacts' messages to it.
- `Bundle.module` traps if the package resource bundle is missing from an
  extension. Xcode copies it into every target that links the package.
