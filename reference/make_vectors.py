"""Generate shared/test-vectors/*.json from the reference implementation.

Every engine case also carries a hand-written expected action that is asserted
here, so a rules or model change that flips an important outcome fails loudly
instead of silently rewriting the vectors.

Usage: python3 reference/make_vectors.py [--check]
  --check  fail if the committed vectors differ from what would be generated
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

from spamshield_ref import ROOT, Classifier, Engine, glob_match, normalize, prepare_pattern

OUT = ROOT / "shared" / "test-vectors"

NORMALIZE = [
    (None, "1"), ("", "1"), ("   ", "1"), ("(202) 555-0123", "1"), ("202.555.0123", "1"),
    ("1-202-555-0123", "1"), ("+1 202 555 0123", "1"), ("+44 20 7946 0958", "1"),
    ("011 44 20 7946 0958", "1"), ("0044 20 7946 0958", "1"), ("72345", "1"), ("555-0123", "1"),
    ("Amazon ", "1"), ("john@example.com", "1"), ("Bank Alert", "1"), ("020 7946 0958", "44"),
    ("07700 900123", "44"), ("+", "1"), ("--", "1"), ("12025550123", "1"), ("2025550123", "44"),
    ("\u00a0+1\u2009202 555 0123\u3000", "1"), ("Bank\u00a0Alert", "1"),
]

PATTERNS = [
    ("+1800555*", "+18005551234"), ("+1800555*", "+18005561234"), ("+1212555????", "+12125550123"),
    ("+1212555????", "+121255501234"), ("*", "+12025550123"), ("+1*0123", "+12025550123"),
    ("(202) 555-0123", "+12025550123"), ("202-555-0123", "+12025550124"), ("72345", "72345"),
    ("AMAZON", "AMAZON"), ("+1 (800) 555-*", "+18005550000"), ("*99", "+12025550199"),
    ("bank*", "BANKALERT"), ("bank*", "+12025550123"), ("", "+12025550123"),
]

TEXTS = [
    "", "Hey, running 10 min late!", "WINNER!! Claim your £1000 prize: call 09061701461 now",
    "USPS: Your package is on hold. Update address at https://usps-help.top/track?id=7",
    "Pay $6.99 toll at 192.168.1.20/pay or call (800) 555-0199", "Your code is 482913",
    "Visit bit.ly/abc. Thanks", "café ÜBER naïve 2024 a b cd", "price 5,000.50 dollars, ref 123456789",
]

NUM = "+12025550123"
USER = "(202) 555-9876"


def call(number=NUM, **kw):
    return {"channel": "call", "number": number, **kw}


def sms(body, number=NUM, **kw):
    return {"channel": "sms", "number": number, "body": body, **kw}


NO_ML = {"useClassifier": False}

# (name, event, context, expected action)
ENGINE = [
    ("contact always allowed", call(isContact=True, verification="failed"), {"blocklist": ["*"]}, "ALLOW"),
    ("allowlist beats blocklist", call(), {"allowlist": ["202-555-0123"], "blocklist": ["*"]}, "ALLOW"),
    ("exact blocklist", call(), {"blocklist": ["(202) 555-0123"]}, "BLOCK"),
    ("wildcard blocklist", call("+1 800 555 4444"), {"blocklist": ["+1800555*"]}, "BLOCK"),
    ("clean call", call(), {}, "ALLOW"),
    ("hidden caller warns", call(None), {}, "WARN"),
    ("hidden caller blocked by setting", call(""), {"settings": {"blockHiddenNumbers": True}}, "BLOCK"),
    ("stir/shaken failed warns", call(verification="failed"), {}, "WARN"),
    ("stir/shaken passed is neutral", call(verification="passed"), {}, "ALLOW"),
    ("invalid caller number", call("12345"), {}, "WARN"),
    ("alphanumeric caller invalid", call("SPAM LIKELY"), {}, "WARN"),
    ("bad NANP area code", call("+1 102 555 0123"), {}, "WARN"),
    ("neighbor spoof alone", call("202-555-1111"), {"settings": {"userNumber": USER}}, "ALLOW"),
    ("neighbor spoof plus failed verification", call("202-555-1111", verification="failed"),
     {"settings": {"userNumber": USER}}, "BLOCK"),
    ("neighbor detection disabled", call("202-555-1111", verification="failed"),
     {"settings": {"userNumber": USER, "detectNeighborSpoofing": False}}, "WARN"),
    ("own number is not a neighbor", call(USER), {"settings": {"userNumber": USER}}, "ALLOW"),
    ("community strong", call(), {"community": {NUM: 0.92}}, "BLOCK"),
    ("community medium", call(), {"community": {NUM: 0.6}}, "WARN"),
    ("community disabled", call(), {"community": {NUM: 0.92}, "settings": {"useCommunity": False}}, "ALLOW"),
    ("community uses normalized key", call("1 (202) 555-0123"), {"community": {NUM: 0.92}}, "BLOCK"),
    ("international caller valid", call("+44 20 7946 0958"), {}, "ALLOW"),
    ("sms plain ham", sms("Can you pick up milk on the way home?"), {}, "ALLOW"),
    ("sms otp ham", sms("Your verification code is 482913. Do not share it.", "72345"), {}, "ALLOW"),
    ("sms package scam rules only", sms("USPS: your package is on hold. Update address: usps-help.top/x"), {"settings": NO_ML}, "WARN"),
    ("sms package scam full", sms("USPS: your package is on hold. Update address: usps-help.top/x"), {}, "BLOCK"),
    ("sms toll scam", sms("E-ZPass: unpaid toll balance of $6.99. Pay now at ezpass-pay.icu"), {}, "BLOCK"),
    ("sms ip url", sms("Your account is locked, verify now http://10.2.3.4/login"), {"settings": NO_ML}, "WARN"),
    ("sms shortener only", sms("photos from saturday: bit.ly/3abc"), {"settings": NO_ML}, "ALLOW"),
    ("sms prize", sms("Congratulations! You won a $500 gift card. Claim at rb.gy/gift"), {}, "BLOCK"),
    ("sms email sender", sms("hi", "promo@deals.example"), {"settings": NO_ML}, "ALLOW"),
    ("sms email sender with link", sms("Your parcel is on hold. redeliver at parcel.top", "promo@deals.example"),
     {"settings": NO_ML}, "WARN"),
    ("sms wrong number opener rules only", sms("Hey is this Jessica?"), {"settings": NO_ML}, "ALLOW"),
    ("sms contact bypasses content", sms("You WON a prize! claim at bit.ly/x", isContact=True), {}, "ALLOW"),
    ("sms short code blocklisted", sms("Deals!", "72345"), {"blocklist": ["72345"]}, "BLOCK"),
    ("sms community", sms("hello"), {"community": {NUM: 0.9}, "settings": NO_ML}, "BLOCK"),
    ("sms no body", sms(None), {}, "ALLOW"),
    ("sms carriage return inside rule window", sms("Your account\r\nhas been suspended"), {"settings": NO_ML}, "ALLOW"),
    ("sms carriage return is not a line break", sms("Your account\rhas been suspended"), {"settings": NO_ML}, "ALLOW"),
    ("sms opener with trailing newline", sms("Hey is this Jessica?\n"), {"settings": NO_ML}, "ALLOW"),
    ("sms opener with u2028", sms("Hey is this Jessica?\u2028Sorry"), {"settings": NO_ML}, "ALLOW"),
    ("sms government", sms("IRS: you are eligible for a tax refund of $1,284. Submit your claim today"), {}, "BLOCK"),
    ("sms crypto", sms("Earn guaranteed profit daily with our crypto investment group"), {}, "BLOCK"),
]


def build() -> dict[str, list]:
    engine = Engine.load()
    clf = Classifier.load()
    engine_cases = []
    for name, event, context, expected in ENGINE:
        verdict = engine.evaluate(event, context)
        if verdict.action != expected:
            raise SystemExit(f"engine case {name!r}: expected {expected}, got {verdict}")
        engine_cases.append({
            "name": name, "event": event, "context": context,
            "expect": {"action": verdict.action, "reasons": verdict.reasons, "score": round(verdict.score, 12)},
        })
    return {
        "normalize.json": [{"raw": r, "countryCode": cc, "expected": normalize(r, cc)} for r, cc in NORMALIZE],
        "patterns.json": [
            {"pattern": p, "number": n, "matches": glob_match(prepare_pattern(p) or "", n)} for p, n in PATTERNS
        ],
        "tokenize.json": [{"text": t, "tokens": clf.tokenize(t)} for t in TEXTS],
        "classifier.json": [{"text": t, "probability": round(clf.probability(t), 12)} for t in TEXTS + [
            c[1]["body"] for c in ENGINE if c[1]["channel"] == "sms" and c[1]["body"]]],
        "engine.json": engine_cases,
    }


def main():
    check = "--check" in sys.argv
    OUT.mkdir(parents=True, exist_ok=True)
    stale = []
    for name, data in build().items():
        text = json.dumps(data, indent=1, ensure_ascii=False) + "\n"
        path = OUT / name
        if check:
            if not path.exists() or path.read_text(encoding="utf-8") != text:
                stale.append(name)
        else:
            path.write_text(text, encoding="utf-8")
    if stale:
        raise SystemExit(f"stale test vectors: {stale}; run python3 reference/make_vectors.py")
    print("vectors " + ("up to date" if check else f"written to {OUT}"))


if __name__ == "__main__":
    main()
