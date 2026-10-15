import pytest

from spamshield_api.scoring import report_weight, score

NOW = 1_790_000_000.0
DAY = 86400.0


def fresh(verdict, n):
    return [(verdict, NOW)] * n


@pytest.mark.parametrize("n,expected", [(0, 0.0), (1, 0.2835), (3, 0.6321), (5, 0.8111), (7, 0.9030)])
def test_fresh_spam_reports(n, expected):
    assert score(fresh("spam", n), NOW) == expected


def test_only_not_spam_scores_zero():
    assert score(fresh("not_spam", 4), NOW) == 0.0


def test_ratio_penalises_not_spam():
    # S=3, N=1: ratio 0.75, confidence 1-e^-1
    assert score(fresh("spam", 3) + fresh("not_spam", 1), NOW) == round(0.75 * 0.6321205588, 4)


def test_half_life_is_90_days():
    assert report_weight(NOW - 90 * DAY, NOW) == pytest.approx(0.5)
    assert report_weight(NOW - 180 * DAY, NOW) == pytest.approx(0.25)
    assert report_weight(NOW, NOW) == 1.0


def test_future_reports_do_not_exceed_full_weight():
    assert report_weight(NOW + DAY, NOW) == 1.0


def test_decayed_reports_score_lower():
    # Six reports aged one half-life weigh the same as three fresh ones.
    aged = [("spam", NOW - 90 * DAY)] * 6
    assert score(aged, NOW) == score(fresh("spam", 3), NOW)
