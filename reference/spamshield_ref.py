"""Reference implementation of the SpamShield detection spec (shared/SPEC.md).

This is the executable source of truth for the shared test vectors. The Kotlin
and Swift engines must produce identical results. Standard library only.
"""

from __future__ import annotations

import json
import math
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

ROOT = Path(__file__).resolve().parent.parent
RULES_PATH = ROOT / "shared" / "rules.json"
MODEL_PATH = ROOT / "shared" / "model" / "sms_classifier.json"

URL_PATTERN = re.compile(
    r"(https?://)?(([a-z0-9-]+[.])+[a-z]{2,24}|[0-9]{1,3}([.][0-9]{1,3}){3})(?![a-z0-9-])"
)
IP_HOST = re.compile(r"^[0-9]{1,3}([.][0-9]{1,3}){3}$")
TOKEN_SPLIT = re.compile(r"[^a-z0-9_]+")
ASCII_LETTER = re.compile(r"[A-Za-z]")


# --- 1. Numbers -------------------------------------------------------------

def normalize(raw: Optional[str], country_code: str = "1") -> Optional[str]:
    if raw is None or raw.strip() == "":
        return None
    if ASCII_LETTER.search(raw):
        return _ascii_upper("".join(raw.split()))
    plus = raw.lstrip().startswith("+")
    digits = "".join(c for c in raw if "0" <= c <= "9")
    if not digits:
        return None
    if plus:
        return "+" + digits
    if country_code == "1" and digits.startswith("011") and len(digits) > 3:
        return "+" + digits[3:]
    if digits.startswith("00") and len(digits) > 2:
        return "+" + digits[2:]
    if country_code == "1":
        if len(digits) == 10:
            return "+1" + digits
        if len(digits) == 11 and digits.startswith("1"):
            return "+" + digits
    elif digits.startswith("0") and len(digits) >= 8:
        return "+" + country_code + digits[1:]
    return digits


def _ascii_upper(s: str) -> str:
    return "".join(chr(ord(c) - 32) if "a" <= c <= "z" else c for c in s)


def is_valid_caller_number(n: str) -> bool:
    if not n.startswith("+"):
        return False
    rest = n[1:]
    if not rest.isdigit() or not rest.isascii():
        return False
    if n.startswith("+1"):
        return len(n) == 12 and n[2] in "23456789" and n[5] in "23456789"
    return 8 <= len(rest) <= 15


def is_neighbor_spoof(caller: Optional[str], user: Optional[str]) -> bool:
    if caller is None or user is None:
        return False
    return (
        caller.startswith("+1") and user.startswith("+1")
        and len(caller) == 12 and len(user) == 12
        and caller != user and caller[2:8] == user[2:8]
    )


# --- 2. Patterns ------------------------------------------------------------

def prepare_pattern(entry: str, country_code: str = "1") -> Optional[str]:
    if "*" in entry or "?" in entry:
        pattern = _ascii_upper("".join(c for c in entry if not c.isspace() and c not in "()-."))
        return pattern or None
    return normalize(entry, country_code)


def glob_match(pattern: str, text: str) -> bool:
    # Iterative wildcard matching with single-star backtracking.
    p = t = 0
    star = -1
    mark = 0
    while t < len(text):
        if p < len(pattern) and (pattern[p] == "?" or pattern[p] == text[t]):
            p += 1
            t += 1
        elif p < len(pattern) and pattern[p] == "*":
            star = p
            mark = t
            p += 1
        elif star != -1:
            p = star + 1
            mark += 1
            t = mark
        else:
            return False
    while p < len(pattern) and pattern[p] == "*":
        p += 1
    return p == len(pattern)


def matches_any(entries, number: str, country_code: str = "1") -> bool:
    for entry in entries:
        pattern = prepare_pattern(entry, country_code)
        if pattern is not None and glob_match(pattern, number):
            return True
    return False


# --- 4. URLs ----------------------------------------------------------------

def url_hosts(text: str) -> list[str]:
    return [m.group(2) for m in URL_PATTERN.finditer(text)]


# --- 5. Classifier ----------------------------------------------------------

class Classifier:
    def __init__(self, model: dict):
        self.bias = float(model["bias"])
        self.weights = {k: float(v) for k, v in model["weights"].items()}
        tok = model["tokenizer"]
        self.replacements = [(re.compile(r["pattern"]), r["token"]) for r in tok["replacements"]]
        self.min_len = int(tok["minTokenLength"])

    @classmethod
    def load(cls, path: Path = MODEL_PATH) -> "Classifier":
        return cls(json.loads(path.read_text()))

    def tokenize(self, text: Optional[str]) -> list[str]:
        return sorted(tokenize(text or "", self.replacements, self.min_len))

    def probability(self, text: Optional[str]) -> float:
        logit = self.bias
        for token in sorted(tokenize(text or "", self.replacements, self.min_len)):
            logit += self.weights.get(token, 0.0)
        return 1.0 / (1.0 + math.exp(-logit))


def tokenize(text: str, replacements, min_len: int) -> set[str]:
    s = text.lower()
    for pattern, token in replacements:
        s = pattern.sub(" " + token + " ", s)
    return {piece for piece in TOKEN_SPLIT.split(s) if len(piece) >= min_len}


# --- 3. Evaluation ----------------------------------------------------------

@dataclass
class Settings:
    blockHiddenNumbers: bool = False
    detectNeighborSpoofing: bool = True
    userNumber: Optional[str] = None
    useClassifier: bool = True
    useCommunity: bool = True
    countryCode: str = "1"


@dataclass
class Verdict:
    action: str
    score: float
    reasons: list[str] = field(default_factory=list)


class Engine:
    def __init__(self, rules: dict, classifier: Optional[Classifier]):
        self.rules = rules
        self.classifier = classifier
        sms = rules["sms"]
        self.keyword_rules = [(r["id"], re.compile(r["pattern"]), float(r["weight"])) for r in sms["keywordRules"]]

    @classmethod
    def load(cls) -> "Engine":
        return cls(json.loads(RULES_PATH.read_text()), Classifier.load())

    def evaluate(self, event: dict, context: Optional[dict] = None) -> Verdict:
        context = context or {}
        settings = Settings(**context.get("settings", {}))
        cc = settings.countryCode
        channel = event["channel"]
        n = normalize(event.get("number"), cc)

        if event.get("isContact", False):
            return Verdict("ALLOW", 0.0, ["contact"])
        if n is not None and matches_any(context.get("allowlist", []), n, cc):
            return Verdict("ALLOW", 0.0, ["allowlist"])
        if n is not None and matches_any(context.get("blocklist", []), n, cc):
            return Verdict("BLOCK", 1.0, ["blocklist"])
        if channel == "call" and n is None and settings.blockHiddenNumbers:
            return Verdict("BLOCK", 1.0, ["hidden_number"])

        signals: list[tuple[str, float]] = []
        community = self._community(n, context, settings)
        if channel == "call":
            call = self.rules["call"]
            if n is None:
                signals.append(("hidden_number", call["hiddenWeight"]))
            if event.get("verification", "unknown") == "failed":
                signals.append(("verification_failed", call["verificationFailedWeight"]))
            if n is not None and not is_valid_caller_number(n):
                signals.append(("invalid_number", call["invalidNumberWeight"]))
            if settings.detectNeighborSpoofing and n is not None:
                user = normalize(settings.userNumber, cc)
                if is_neighbor_spoof(n, user):
                    signals.append(("neighbor_spoof", call["neighborSpoofWeight"]))
            if community is not None:
                signals.append(("community", community))
        else:
            sms = self.rules["sms"]
            body = event.get("body") or ""
            text = body.lower()
            if community is not None:
                signals.append(("community", community))
            if n is not None and "@" in n:
                signals.append(("sender_email", sms["senderEmailWeight"]))
            for rule_id, pattern, weight in self.keyword_rules:
                if pattern.search(text):
                    signals.append(("keyword:" + rule_id, weight))
            signals.extend(self._url_signals(text))
            if settings.useClassifier and self.classifier is not None:
                p = self.classifier.probability(body)
                if p >= sms["classifier"]["minProbability"]:
                    signals.append(("classifier", p * sms["classifier"]["weight"]))

        keep = 1.0
        for _, weight in signals:
            keep *= 1.0 - weight
        score = min(1.0, max(0.0, 1.0 - keep))
        t = self.rules["thresholds"]
        action = "BLOCK" if score >= t["block"] else "WARN" if score >= t["warn"] else "ALLOW"
        return Verdict(action, score, [s[0] for s in signals])

    def _community(self, n, context, settings) -> Optional[float]:
        if not settings.useCommunity or n is None:
            return None
        s = context.get("community", {}).get(n)
        if s is None or s <= 0:
            return None
        return min(1.0, s * self.rules["community"]["weight"])

    def _url_signals(self, text: str) -> list[tuple[str, float]]:
        urls = self.rules["sms"]["urls"]
        ip = short = tld = False
        for host in url_hosts(text):
            if IP_HOST.match(host):
                ip = True
            if any(host == d or host.endswith("." + d) for d in urls["shorteners"]):
                short = True
            if host.rsplit(".", 1)[-1] in urls["suspiciousTlds"]:
                tld = True
        out = []
        if ip:
            out.append(("url_ip_host", urls["ipHostWeight"]))
        if short:
            out.append(("url_shortener", urls["shortenerWeight"]))
        if tld:
            out.append(("url_suspicious_tld", urls["suspiciousTldWeight"]))
        return out
