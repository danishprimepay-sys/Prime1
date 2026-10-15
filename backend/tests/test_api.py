import hashlib
import sqlite3

import pytest

from .conftest import DAY, new_install_id, report

NUMBER = "+12025550123"


def lookup(client, number=NUMBER):
    resp = client.get(f"/v1/numbers/{number}")
    assert resp.status_code == 200, resp.text
    return resp.json()


def test_healthz(client):
    assert client.get("/healthz").json() == {"ok": True}


class TestReports:
    def test_accepts_and_normalizes(self, client):
        resp = report(client, "(202) 555-0123", channel="sms", category="irs_scam")
        assert resp.status_code == 202
        assert resp.json() == {"accepted": True, "number": NUMBER}

    @pytest.mark.parametrize("number,expected", [("72345", "72345"), ("Bank Alert", "BANKALERT")])
    def test_short_codes_and_sender_ids(self, client, number, expected):
        resp = report(client, number, channel="sms")
        assert resp.status_code == 202
        assert resp.json()["number"] == expected

    @pytest.mark.parametrize("field,value", [
        ("channel", "email"),
        ("verdict", "maybe"),
        ("category", "x" * 41),
        ("installId", "short"),
        ("installId", "x" * 129),
        ("installId", "has spaces in it!!"),
        ("number", ""),
    ])
    def test_schema_violations_are_422(self, client, field, value):
        body = {"number": NUMBER, "channel": "call", "verdict": "spam", "installId": new_install_id()}
        body[field] = value
        assert client.post("/v1/reports", json=body).status_code == 422

    def test_missing_fields_are_422(self, client):
        assert client.post("/v1/reports", json={"number": NUMBER}).status_code == 422

    @pytest.mark.parametrize("number", ["--", "+123", "12", "Bank#Alert"])
    def test_invalid_numbers_are_400(self, client, number):
        assert report(client, number).status_code == 400

    @pytest.mark.parametrize("number", ["911", "112", "999", "000", "988", "311", "411",
                                        "511", "611", "711", "811", "9-1-1"])
    def test_emergency_numbers_are_400(self, client, number):
        assert report(client, number).status_code == 400

    def test_same_install_replaces_previous_report(self, client):
        install = new_install_id()
        report(client, NUMBER, "spam", install)
        assert lookup(client)["spamReports"] == 1
        report(client, "202-555-0123", "not_spam", install)
        info = lookup(client)
        assert (info["spamReports"], info["notSpamReports"]) == (0, 1)
        assert info["score"] == 0.0

    def test_stores_only_hashed_install_id(self, client):
        install = new_install_id()
        report(client, NUMBER, install_id=install, category="robocall")
        conn = sqlite3.connect(client.app.state.settings.database_path)
        rows = conn.execute("SELECT * FROM reports").fetchall()
        conn.close()
        assert len(rows) == 1
        assert install not in repr(rows)
        assert rows[0][0] == hashlib.sha256((install + "test-salt").encode()).hexdigest()

    def test_extra_fields_are_not_stored(self, client):
        report(client, NUMBER, messageBody="WIN A PRIZE", ownNumber="+12025559999")
        conn = sqlite3.connect(client.app.state.settings.database_path)
        dump = "\n".join(conn.iterdump())
        conn.close()
        assert "PRIZE" not in dump and "9999" not in dump


class TestLookup:
    def test_unknown_number(self, client):
        assert lookup(client) == {"number": NUMBER, "score": 0.0, "spamReports": 0,
                                  "notSpamReports": 0, "lastReportedAt": None}

    def test_accepts_raw_and_url_encoded_numbers(self, client):
        report(client, NUMBER)
        for path in ["%2B12025550123", "(202)%20555-0123", "2025550123", "+12025550123"]:
            info = lookup(client, path)
            assert info["number"] == NUMBER
            assert info["spamReports"] == 1

    def test_invalid_number_is_400(self, client):
        assert client.get("/v1/numbers/911").status_code == 400
        assert client.get("/v1/numbers/--").status_code == 400

    def test_counts_score_and_timestamp(self, client):
        for _ in range(5):
            report(client, NUMBER, "spam")
        report(client, NUMBER, "not_spam")
        info = lookup(client)
        assert (info["spamReports"], info["notSpamReports"]) == (5, 1)
        assert info["score"] == round((5 / 6) * 0.8111243971, 4)
        assert info["lastReportedAt"].endswith("Z")

    def test_fresh_spam_scores(self, client):
        expected = {1: 0.2835, 5: 0.8111, 7: 0.9030}
        for n in range(1, 8):
            report(client, NUMBER)
            if n in expected:
                assert lookup(client)["score"] == expected[n]

    def test_score_decays_with_age(self, client, clock):
        report(client, NUMBER)
        clock.advance(90 * DAY)
        info = lookup(client)
        # One report at half weight: 1 - exp(-0.5/3)
        assert info["score"] == 0.1535
        assert info["spamReports"] == 1

    def test_replacement_refreshes_report_age(self, client, clock):
        install = new_install_id()
        report(client, NUMBER, install_id=install)
        clock.advance(90 * DAY)
        report(client, NUMBER, install_id=install)
        assert lookup(client)["score"] == 0.2835


class TestRateLimits:
    def test_per_install_limit(self, make_client, clock):
        client = make_client(install_reports_per_hour=3, ip_reports_per_hour=100)
        install = new_install_id()
        for i in range(3):
            assert report(client, f"+1202555010{i}", install_id=install).status_code == 202
        resp = report(client, "+12025550109", install_id=install)
        assert resp.status_code == 429
        assert int(resp.headers["Retry-After"]) > 0
        # A different install from the same IP is unaffected.
        assert report(client, "+12025550109").status_code == 202
        # The window slides.
        clock.advance(3601)
        assert report(client, "+12025550109", install_id=install).status_code == 202

    def test_per_ip_limit(self, make_client):
        client = make_client(install_reports_per_hour=100, ip_reports_per_hour=2)
        assert report(client, NUMBER).status_code == 202
        assert report(client, NUMBER).status_code == 202
        assert report(client, NUMBER).status_code == 429

    def test_forwarded_for_ignored_without_trust_proxy(self, make_client):
        client = make_client(ip_reports_per_hour=1)
        assert report(client, NUMBER).status_code == 202
        resp = client.post("/v1/reports", headers={"X-Forwarded-For": "203.0.113.9"},
                           json={"number": NUMBER, "channel": "call", "verdict": "spam",
                                 "installId": new_install_id()})
        assert resp.status_code == 429

    def test_forwarded_for_honoured_with_trust_proxy(self, make_client):
        client = make_client(ip_reports_per_hour=1, trust_proxy=True)

        def post(ip):
            return client.post("/v1/reports", headers={"X-Forwarded-For": f"10.0.0.1, {ip}"},
                               json={"number": NUMBER, "channel": "call", "verdict": "spam",
                                     "installId": new_install_id()})

        assert post("203.0.113.9").status_code == 202
        assert post("203.0.113.10").status_code == 202
        assert post("203.0.113.9").status_code == 429


def spam(client, number, n):
    for _ in range(n):
        assert report(client, number).status_code == 202


class TestBlocklist:
    def test_empty(self, client):
        resp = client.get("/v1/blocklist")
        assert resp.status_code == 200
        body = resp.json()
        assert body["entries"] == [] and body["minScore"] == 0.5
        assert body["generatedAt"].endswith("Z")

    def test_requires_three_distinct_spam_reporters(self, client):
        spam(client, "+12025550001", 2)
        spam(client, "+12025550002", 3)
        install = new_install_id()
        for _ in range(5):  # repeated reports from one install count once
            report(client, "+12025550003", install_id=install)
        entries = client.get("/v1/blocklist?minScore=0.3").json()["entries"]
        assert entries == [{"number": "+12025550002", "score": 0.6321}]

    def test_min_score_threshold_and_clamping(self, client):
        spam(client, "+12025550003", 3)   # 0.6321
        spam(client, "+12025550005", 5)   # 0.8111
        spam(client, "+12025550007", 7)   # 0.9030
        numbers = lambda q: [e["number"] for e in client.get(f"/v1/blocklist{q}").json()["entries"]]
        assert numbers("") == ["+12025550003", "+12025550005", "+12025550007"]
        assert numbers("?minScore=0.8") == ["+12025550005", "+12025550007"]
        assert numbers("?minScore=0.9") == ["+12025550007"]
        assert client.get("/v1/blocklist?minScore=0").json()["minScore"] == 0.3
        assert client.get("/v1/blocklist?minScore=5").json()["minScore"] == 1.0
        assert client.get("/v1/blocklist?minScore=abc").status_code == 422
        assert client.get("/v1/blocklist?minScore=nan").status_code == 422

    def test_not_spam_reports_lower_score(self, client):
        spam(client, NUMBER, 3)
        for _ in range(3):
            report(client, NUMBER, "not_spam")
        assert client.get("/v1/blocklist?minScore=0.3").json()["entries"] == [
            {"number": NUMBER, "score": 0.3161}]

    def test_sorted_by_number_string(self, client):
        for number in ["+447700900123", "+12025550199", "AMAZON", "72345", "+12025550100"]:
            spam(client, number, 7)
        entries = client.get("/v1/blocklist").json()["entries"]
        assert [e["number"] for e in entries] == sorted(e["number"] for e in entries)
        assert len(entries) == 5

    def test_cap_keeps_highest_scores(self, client, monkeypatch):
        from spamshield_api import app as app_module
        monkeypatch.setattr(app_module, "MAX_BLOCKLIST_ENTRIES", 2)
        spam(client, "+12025550009", 7)
        spam(client, "+12025550001", 3)
        spam(client, "+12025550005", 5)
        entries = client.get("/v1/blocklist?minScore=0.3").json()["entries"]
        assert [e["number"] for e in entries] == ["+12025550005", "+12025550009"]

    def test_etag_and_304(self, client):
        spam(client, NUMBER, 3)
        first = client.get("/v1/blocklist")
        etag = first.headers["ETag"]
        assert etag.startswith('"') and not etag.startswith("W/")
        assert etag == '"' + hashlib.sha256(first.content).hexdigest() + '"'

        cached = client.get("/v1/blocklist", headers={"If-None-Match": etag})
        assert cached.status_code == 304
        assert cached.headers["ETag"] == etag
        assert cached.content == b""
        assert client.get("/v1/blocklist", headers={"If-None-Match": f'"other", {etag}'}).status_code == 304
        assert client.get("/v1/blocklist", headers={"If-None-Match": '"stale"'}).status_code == 200

    def test_new_report_invalidates_cache(self, client):
        spam(client, NUMBER, 3)
        etag = client.get("/v1/blocklist").headers["ETag"]
        report(client, NUMBER)
        resp = client.get("/v1/blocklist", headers={"If-None-Match": etag})
        assert resp.status_code == 200
        assert resp.headers["ETag"] != etag
        assert resp.json()["entries"][0]["score"] == 0.7364

    def test_cache_expires_so_decay_applies(self, client, clock):
        spam(client, NUMBER, 7)
        assert client.get("/v1/blocklist").json()["entries"][0]["score"] == 0.9030
        clock.advance(180 * DAY)
        # 7 reports at quarter weight: S=1.75 -> 0.4420, below default 0.5.
        assert client.get("/v1/blocklist").json()["entries"] == []
        assert client.get("/v1/blocklist?minScore=0.3").json()["entries"][0]["score"] == 0.442
