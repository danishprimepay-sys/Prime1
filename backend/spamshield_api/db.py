"""SQLite storage for community reports.

Each row is the single active report of one (hashed) install for one number.
No message content, raw install IDs, or reporter phone numbers are stored.
"""

from __future__ import annotations

import sqlite3
import threading
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS reports (
    install_hash TEXT NOT NULL,
    number       TEXT NOT NULL,
    channel      TEXT NOT NULL CHECK (channel IN ('call', 'sms')),
    verdict      TEXT NOT NULL CHECK (verdict IN ('spam', 'not_spam')),
    category     TEXT,
    created_at   REAL NOT NULL,
    PRIMARY KEY (install_hash, number)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS reports_by_number ON reports (number, verdict);
"""


@dataclass(frozen=True)
class NumberReports:
    reports: list[tuple[str, float]]  # (verdict, created_at)

    @property
    def spam_count(self) -> int:
        return sum(1 for v, _ in self.reports if v == "spam")

    @property
    def not_spam_count(self) -> int:
        return len(self.reports) - self.spam_count

    @property
    def last_reported_at(self) -> float | None:
        return max((t for _, t in self.reports), default=None)


class Database:
    def __init__(self, path: str):
        if path != ":memory:":
            Path(path).parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self._lock = threading.Lock()
        with self._lock:
            self._conn.execute("PRAGMA journal_mode=WAL")
            self._conn.execute("PRAGMA synchronous=NORMAL")
            self._conn.executescript(SCHEMA)

    def close(self) -> None:
        with self._lock:
            self._conn.close()

    def upsert_report(
        self,
        install_hash: str,
        number: str,
        channel: str,
        verdict: str,
        category: str | None,
        created_at: float,
    ) -> None:
        with self._lock:
            self._conn.execute(
                """
                INSERT INTO reports (install_hash, number, channel, verdict, category, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (install_hash, number) DO UPDATE SET
                    channel = excluded.channel,
                    verdict = excluded.verdict,
                    category = excluded.category,
                    created_at = excluded.created_at
                """,
                (install_hash, number, channel, verdict, category, created_at),
            )

    def reports_for(self, number: str) -> NumberReports:
        with self._lock:
            rows = self._conn.execute(
                "SELECT verdict, created_at FROM reports WHERE number = ?", (number,)
            ).fetchall()
        return NumberReports([(v, t) for v, t in rows])

    def blocklist_candidates(self, min_spam_reporters: int) -> dict[str, list[tuple[str, float]]]:
        """All active reports for numbers with at least `min_spam_reporters` spam reports."""
        with self._lock:
            rows = self._conn.execute(
                """
                SELECT number, verdict, created_at FROM reports
                WHERE number IN (
                    SELECT number FROM reports WHERE verdict = 'spam'
                    GROUP BY number HAVING COUNT(*) >= ?
                )
                """,
                (min_spam_reporters,),
            ).fetchall()
        grouped: dict[str, list[tuple[str, float]]] = defaultdict(list)
        for number, verdict, created_at in rows:
            grouped[number].append((verdict, created_at))
        return grouped
