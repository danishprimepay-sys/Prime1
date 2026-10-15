# SpamShield community-reports API

Collects anonymous "spam / not spam" reports from SpamShield installs, turns
them into a per-number community score, and publishes a blocklist that the
Android and iOS apps download.

Stack: Python 3.11+, FastAPI, uvicorn, stdlib `sqlite3`.

## Endpoints

### `GET /healthz`

```sh
curl http://localhost:8080/healthz
# {"ok":true}
```

### `POST /v1/reports`

```sh
curl -X POST http://localhost:8080/v1/reports \
  -H 'content-type: application/json' \
  -d '{"number":"(202) 555-0123","channel":"call","verdict":"spam",
       "category":"irs_scam","installId":"3f6c1d2e-8a9b-4c1d-9e2f-0a1b2c3d4e5f"}'
# 202 {"accepted":true,"number":"+12025550123"}
```

| Field | Rules |
| --- | --- |
| `number` | Raw or normalized; 1–128 chars. Normalized per `shared/SPEC.md` §1 with `COUNTRY_CODE`. |
| `channel` | `"call"` or `"sms"` |
| `verdict` | `"spam"` or `"not_spam"` |
| `category` | optional, ≤ 40 chars |
| `installId` | 16–128 chars of `[A-Za-z0-9_-]`, stable per install |

After normalization the number must be E.164 (`^\+[0-9]{8,15}$`), a short code
(`^[0-9]{3,8}$`) or an alphanumeric sender ID (`^[A-Z0-9@._-]{2,64}$`, containing
at least one letter). Emergency/service numbers (911, 112, 999, 000, 988, 311,
411, 511, 611, 711, 811) are rejected.

Each install has one active report per number: reporting again replaces the
previous report (so a user can flip spam ↔ not_spam), and resets its age.

Responses: `202` accepted · `400` invalid or emergency number · `422` malformed
body · `429` rate limited (with `Retry-After`). Errors are `{"detail": ...}`.

### `GET /v1/numbers/{number}`

The number may be raw or normalized; URL-encode it (`+` → `%2B`).

```sh
curl http://localhost:8080/v1/numbers/%2B12025550123
# {"number":"+12025550123","score":0.8111,"spamReports":5,"notSpamReports":0,
#  "lastReportedAt":"2026-10-15T00:27:56Z"}
```

Unknown numbers return `200` with score `0`, counts `0` and `lastReportedAt: null`.
Invalid numbers return `400`. Counts are numbers of active reports, not weights.

### `GET /v1/blocklist?minScore=0.5`

```sh
curl -i http://localhost:8080/v1/blocklist?minScore=0.5
# ETag: "40174c7d…"
# {"generatedAt":"2026-10-15T00:27:56Z","minScore":0.5,
#  "entries":[{"number":"+12025550123","score":0.8111}]}

curl -i -H 'If-None-Match: "40174c7d…"' http://localhost:8080/v1/blocklist
# HTTP/1.1 304 Not Modified
```

- Only numbers with **≥ 3 distinct spam reporters** and `score ≥ minScore`.
- `minScore` defaults to 0.5 and is clamped to `[0.3, 1]`; the response echoes the clamped value.
- At most 200,000 entries (highest scores kept), sorted ascending by number string.
- Strong `ETag` (SHA-256 of the body); `If-None-Match` → `304`.
- The list is cached in memory per `minScore`. The cache is dropped on every new
  report and recomputed at least every `BLOCKLIST_CACHE_SECONDS` so score decay
  is reflected even without new reports.

## Scoring

For a number, take each install's single active report. A report aged
`age_days` weighs `0.5 ** (age_days / 90)` (90-day half-life).

```
S = Σ spam weights          N = Σ not_spam weights
confidence = 1 - exp(-S / 3)
ratio      = S / (S + N)    (0 when S == 0)
score      = round(ratio * confidence, 4)
```

Fresh spam reports only: 1 → 0.2835, 3 → 0.6321, 5 → 0.8111, 7 → 0.9030.

## Privacy

- The raw `installId` is never stored: only `sha256(installId + INSTALL_SALT)`.
- No message content and nothing about the reporter's own phone number is
  accepted or stored. Unknown request fields are ignored, not persisted.
- Stored per report: hashed install, normalized reported number, channel,
  verdict, optional category, timestamp.
- Client IPs are only held in memory, for rate limiting, for at most an hour.

## Abuse controls

In-memory sliding-window rate limits on `POST /v1/reports`: per hashed install
(default 30/hour) and per client IP (default 120/hour). With `TRUST_PROXY=1`
the client IP is the right-most `X-Forwarded-For` entry (the address our proxy
saw); otherwise the socket peer address is used.

Limits are per process. Run a single worker, or move them to a shared store
(e.g. Redis) before scaling out.

**Production TODO (out of scope here):** verify that reports come from genuine
app installs with Play Integrity (Android) and App Attest / DeviceCheck (iOS)
before accepting them. Without attestation, a determined attacker can mint
install IDs and push numbers onto (or off) the blocklist, limited only by IP rate
limits and the 3-reporter threshold.

## Configuration

| Variable | Default | Meaning |
| --- | --- | --- |
| `DATABASE_PATH` | `./spamshield.sqlite3` | SQLite file (created if missing) |
| `INSTALL_SALT` | dev value + startup warning | Secret salt for install hashes. **Required in production**; startup fails if unset and `SPAMSHIELD_ENV=production`. Changing it orphans existing reports' install identity. |
| `SPAMSHIELD_ENV` | — | Set to `production` to enforce `INSTALL_SALT` |
| `COUNTRY_CODE` | `1` | Default country code for normalization |
| `RATE_LIMIT_INSTALL_PER_HOUR` | `30` | Reports per install per hour |
| `RATE_LIMIT_IP_PER_HOUR` | `120` | Reports per client IP per hour |
| `TRUST_PROXY` | off | `1` to honour `X-Forwarded-For` |
| `BLOCKLIST_CACHE_SECONDS` | `300` | Max age of the cached blocklist |

## Running locally

```sh
cd backend
python3 -m venv .venv
.venv/bin/pip install -r requirements-dev.txt
.venv/bin/python -m pytest
.venv/bin/uvicorn spamshield_api.main:app --reload --port 8080
```

Tests build the app with `create_app(Settings(...), clock=...)`, using a
temporary database and a fake clock.

## Deploying

```sh
docker build -t spamshield-api backend
docker run -p 8080:8080 -v spamshield-data:/data \
  -e INSTALL_SALT="$(openssl rand -hex 32)" -e SPAMSHIELD_ENV=production \
  -e TRUST_PROXY=1 spamshield-api
```

The image runs uvicorn on port 8080 as a non-root user and keeps the database
at `/data/spamshield.sqlite3`; mount a persistent volume there. Keep a single
instance (SQLite + in-memory rate limits and cache), back up the volume, store
`INSTALL_SALT` in a secret manager, and terminate TLS at the proxy/load balancer
(set `TRUST_PROXY=1` only when such a proxy is in front).
