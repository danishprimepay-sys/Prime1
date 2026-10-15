"""Community spam score from active reports."""

from __future__ import annotations

import math
from collections.abc import Iterable

HALF_LIFE_DAYS = 90.0
SECONDS_PER_DAY = 86400.0


def report_weight(created_at: float, now: float) -> float:
    age_days = max(0.0, now - created_at) / SECONDS_PER_DAY
    return 0.5 ** (age_days / HALF_LIFE_DAYS)


def score(reports: Iterable[tuple[str, float]], now: float) -> float:
    """Score a number from (verdict, created_at epoch seconds) pairs, one per install."""
    spam = not_spam = 0.0
    for verdict, created_at in reports:
        w = report_weight(created_at, now)
        if verdict == "spam":
            spam += w
        else:
            not_spam += w
    if spam == 0:
        return 0.0
    confidence = 1 - math.exp(-spam / 3)
    ratio = spam / (spam + not_spam)
    return round(ratio * confidence, 4)
