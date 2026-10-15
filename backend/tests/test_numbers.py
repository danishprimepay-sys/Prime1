import json
from pathlib import Path

import pytest

from spamshield_api.numbers import EMERGENCY_NUMBERS, InvalidNumber, normalize, normalize_reportable

VECTORS = Path(__file__).resolve().parents[2] / "shared" / "test-vectors" / "normalize.json"


def _vectors():
    return json.loads(VECTORS.read_text())


@pytest.mark.parametrize("case", _vectors(), ids=lambda c: repr(c["raw"]))
def test_normalize_matches_shared_vectors(case):
    assert normalize(case["raw"], case["countryCode"]) == case["expected"]


@pytest.mark.parametrize("raw,expected", [
    ("(202) 555-0123", "+12025550123"),
    ("+44 20 7946 0958", "+442079460958"),
    ("72345", "72345"),
    ("Amazon", "AMAZON"),
    ("john@example.com", "JOHN@EXAMPLE.COM"),
])
def test_reportable_accepts(raw, expected):
    assert normalize_reportable(raw, "1") == expected


@pytest.mark.parametrize("raw", [
    "", "   ", "+", "--", "12", "+1234567", "+1234567890123456",
    "A", "Bank#Alert", "x" * 65, "123456789012345678",
])
def test_reportable_rejects_invalid(raw):
    with pytest.raises(InvalidNumber):
        normalize_reportable(raw, "1")


@pytest.mark.parametrize("raw", sorted(EMERGENCY_NUMBERS) + ["9-1-1", " 911 ", "+911", "+112"])
def test_reportable_rejects_emergency(raw):
    with pytest.raises(InvalidNumber):
        normalize_reportable(raw, "1")


def test_emergency_rejected_for_other_country_codes():
    with pytest.raises(InvalidNumber):
        normalize_reportable("999", "44")
