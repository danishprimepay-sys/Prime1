# SpamShield

Spam call and text blocking for Android and iPhone, backed by an optional
community reputation service.

| Directory | What it is |
| --- | --- |
| [`shared/`](shared/) | The detection spec, `rules.json`, the trained SMS classifier and cross-platform test vectors |
| [`android/`](android/README.md) | Android app (Kotlin): live call screening + SMS filtering as the default SMS app |
| [`ios/`](ios/README.md) | iPhone app (Swift): Call Directory extension + SMS filter extension |
| [`backend/`](backend/README.md) | Community reports & reputation API (FastAPI + SQLite) |
| [`ml/`](ml/) | Training script for the on-device SMS classifier |
| [`reference/`](reference/) | Python reference engine that generates the test vectors |

## How detection works

Every call or text gets a verdict — **ALLOW**, **WARN** or **BLOCK** — from the
same engine on both platforms (see [`shared/SPEC.md`](shared/SPEC.md)):

1. **Contacts and your allowlist** always get through.
2. **Your blocklist** (exact numbers or wildcards like `+1800555*`) always blocks.
3. Otherwise independent signals are combined (`score = 1 − Π(1 − weight)`):
   - **Calls:** hidden caller ID, failed STIR/SHAKEN verification, invalid
     numbers, neighbor spoofing (same area code + exchange as you).
   - **Texts:** scam keyword rules (package delivery, unpaid tolls, account
     alerts, prizes, government impersonation, crypto, job offers, "wrong
     number" openers…), risky links (URL shorteners, raw IP addresses,
     suspicious TLDs), email senders, and an on-device ML classifier.
   - **Community reports:** numbers reported as spam by several users.
4. Score ≥ 0.85 blocks, ≥ 0.55 warns.

Detection runs entirely on the device; the network is only used to sync the
community blocklist and to send reports. Message text never leaves the phone.

| | Android | iPhone |
| --- | --- | --- |
| Calls: BLOCK | Rejected before ringing | Blocked (community/blocklist numbers via Call Directory) |
| Calls: WARN | Rings silently | Labelled "Suspected spam" |
| Calls: live heuristics | Yes (hidden, STIR/SHAKEN, neighbor spoof) | No — iOS has no live call screening API; use "Silence Unknown Callers" |
| Texts: BLOCK | Quarantined, no notification | Junk folder |
| Texts: WARN | Delivered with "Possible spam" label | Junk folder |

Platform limitations are documented in each app's README.

## Keeping the platforms in sync

`rules.json` and the model are data, so tuning them changes both apps at once.
After any change:

```sh
python3 ml/train.py                 # only if retraining the classifier
python3 reference/make_vectors.py   # regenerate shared/test-vectors
scripts/sync-shared.sh              # copy data into the Swift package
```

Then `cd android && ./gradlew :core:test` and
`cd ios/SpamShieldCore && swift test` must both pass. CI enforces all of this.

## Classifier

Logistic regression over token presence, trained on the
[SMS Spam Collection](https://archive.ics.uci.edu/dataset/228/sms+spam+collection)
(Almeida & Hidalgo, CC BY 4.0) plus hand-written modern examples in
`ml/data/supplement.tsv`. Held-out metrics are in `ml/metrics.json`.
