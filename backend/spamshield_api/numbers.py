"""Phone-number normalization and validation for reported numbers."""

from __future__ import annotations

import re
from typing import Optional

_ASCII_LETTER = re.compile(r"[A-Za-z]")

_E164 = re.compile(r"^\+[0-9]{8,15}$")
_SHORT_CODE = re.compile(r"^[0-9]{3,8}$")
# Sender IDs come from normalization step 2, so they always contain a letter;
# bare digit strings longer than a short code are unplaceable and rejected.
_SENDER_ID = re.compile(r"^[A-Z0-9@._-]{2,64}$")

EMERGENCY_NUMBERS = frozenset(
    {"911", "112", "999", "000", "988", "311", "411", "511", "611", "711", "811"}
)


def normalize(raw: Optional[str], country_code: str = "1") -> Optional[str]:
    """SPEC section 1. Mirrors `normalize()` in reference/spamshield_ref.py;
    keep the two in sync (both are checked against shared/test-vectors/normalize.json)."""
    if raw is None or raw.strip() == "":
        return None
    if _ASCII_LETTER.search(raw):
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


class InvalidNumber(ValueError):
    pass


def normalize_reportable(raw: str, country_code: str) -> str:
    """Normalize `raw` and ensure it is something the community may report.

    Raises InvalidNumber for unparseable input and for emergency/service numbers.
    """
    number = normalize(raw, country_code)
    if number is None:
        raise InvalidNumber("number is empty or hidden")
    if number in EMERGENCY_NUMBERS or number.lstrip("+") in EMERGENCY_NUMBERS:
        raise InvalidNumber("emergency and service numbers cannot be reported")
    is_sender_id = bool(_SENDER_ID.match(number) and _ASCII_LETTER.search(number))
    if not (_E164.match(number) or _SHORT_CODE.match(number) or is_sender_id):
        raise InvalidNumber("not a valid phone number, short code or sender ID")
    return number
