"""FastAPI application: community spam reports, number lookups and the blocklist."""

from __future__ import annotations

import hashlib
import json
import logging
import math
import threading
import time
from collections.abc import Callable
from contextlib import asynccontextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Literal, Optional

from fastapi import FastAPI, HTTPException, Query, Request, Response
from pydantic import BaseModel, Field

from . import scoring
from .config import Settings
from .db import Database
from .numbers import InvalidNumber, normalize_reportable
from .ratelimit import SlidingWindowLimiter

log = logging.getLogger("spamshield_api")

MIN_SPAM_REPORTERS = 3
MIN_SCORE_FLOOR = 0.3
MAX_BLOCKLIST_ENTRIES = 200_000
_MAX_CACHED_BODIES = 32


class ReportIn(BaseModel):
    number: str = Field(min_length=1, max_length=128)
    channel: Literal["call", "sms"]
    verdict: Literal["spam", "not_spam"]
    category: Optional[str] = Field(default=None, max_length=40)
    installId: str = Field(min_length=16, max_length=128, pattern=r"^[A-Za-z0-9_-]+$")


def iso8601(ts: float) -> str:
    return datetime.fromtimestamp(ts, timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


@dataclass(frozen=True)
class _CachedBody:
    body: bytes
    etag: str


class BlocklistCache:
    """Caches serialized blocklists per minScore.

    Invalidated whenever a report is written, and recomputed after `ttl` seconds
    regardless, because report weights decay with time.
    """

    def __init__(self, db: Database, clock: Callable[[], float], ttl: float):
        self._db = db
        self._clock = clock
        self._ttl = ttl
        self._lock = threading.Lock()
        self._scores: list[tuple[str, float]] | None = None
        self._computed_at = 0.0
        self._bodies: dict[float, _CachedBody] = {}

    def invalidate(self) -> None:
        with self._lock:
            self._scores = None
            self._bodies.clear()

    def get(self, min_score: float) -> _CachedBody:
        with self._lock:
            now = self._clock()
            if self._scores is None or now - self._computed_at >= self._ttl:
                self._scores = self._compute_scores(now)
                self._computed_at = now
                self._bodies.clear()
            cached = self._bodies.get(min_score)
            if cached is None:
                cached = self._render(min_score)
                if len(self._bodies) >= _MAX_CACHED_BODIES:
                    self._bodies.clear()
                self._bodies[min_score] = cached
            return cached

    def _compute_scores(self, now: float) -> list[tuple[str, float]]:
        """All candidate numbers, highest score first (ties broken by number)."""
        candidates = self._db.blocklist_candidates(MIN_SPAM_REPORTERS)
        scored = [(number, scoring.score(reports, now)) for number, reports in candidates.items()]
        scored.sort(key=lambda e: (-e[1], e[0]))
        return scored

    def _render(self, min_score: float) -> _CachedBody:
        assert self._scores is not None
        entries = [e for e in self._scores if e[1] >= min_score][:MAX_BLOCKLIST_ENTRIES]
        entries.sort(key=lambda e: e[0])
        payload = {
            "generatedAt": iso8601(self._computed_at),
            "minScore": min_score,
            "entries": [{"number": n, "score": s} for n, s in entries],
        }
        body = json.dumps(payload, separators=(",", ":")).encode()
        return _CachedBody(body=body, etag='"' + hashlib.sha256(body).hexdigest() + '"')


def _etag_matches(if_none_match: str | None, etag: str) -> bool:
    if not if_none_match:
        return False
    for candidate in if_none_match.split(","):
        candidate = candidate.strip()
        if candidate == "*" or candidate.removeprefix("W/") == etag:
            return True
    return False


def create_app(settings: Settings | None = None, clock: Callable[[], float] = time.time) -> FastAPI:
    settings = settings or Settings.from_env()
    if settings.using_dev_salt:
        log.warning("INSTALL_SALT is not set; using an insecure development salt")

    db = Database(settings.database_path)
    blocklist = BlocklistCache(db, clock, settings.blocklist_cache_seconds)
    install_limiter = SlidingWindowLimiter(settings.install_reports_per_hour, 3600, clock)
    ip_limiter = SlidingWindowLimiter(settings.ip_reports_per_hour, 3600, clock)

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        yield
        db.close()

    app = FastAPI(title="SpamShield API", version="1.0.0", lifespan=lifespan)
    app.state.settings = settings
    app.state.db = db

    def hash_install(install_id: str) -> str:
        return hashlib.sha256((install_id + settings.install_salt).encode()).hexdigest()

    def client_ip(request: Request) -> str:
        if settings.trust_proxy:
            forwarded = request.headers.get("x-forwarded-for", "")
            hops = [h.strip() for h in forwarded.split(",") if h.strip()]
            if hops:
                # The right-most hop was appended by our trusted proxy; anything
                # to its left is client-controlled and can be forged.
                return hops[-1]
        return request.client.host if request.client else "unknown"

    def normalize_or_400(raw: str) -> str:
        try:
            return normalize_reportable(raw, settings.country_code)
        except InvalidNumber as e:
            raise HTTPException(status_code=400, detail=str(e)) from None

    @app.get("/healthz")
    def healthz() -> dict:
        return {"ok": True}

    @app.post("/v1/reports", status_code=202)
    def submit_report(report: ReportIn, request: Request) -> dict:
        install_hash = hash_install(report.installId)
        ip_key = client_ip(request)
        wait = max(install_limiter.retry_after(install_hash), ip_limiter.retry_after(ip_key))
        if wait > 0:
            raise HTTPException(
                status_code=429,
                detail="too many reports; try again later",
                headers={"Retry-After": str(math.ceil(wait))},
            )
        install_limiter.hit(install_hash)
        ip_limiter.hit(ip_key)

        number = normalize_or_400(report.number)
        db.upsert_report(
            install_hash=install_hash,
            number=number,
            channel=report.channel,
            verdict=report.verdict,
            category=report.category or None,
            created_at=clock(),
        )
        blocklist.invalidate()
        return {"accepted": True, "number": number}

    @app.get("/v1/numbers/{number}")
    def lookup_number(number: str) -> dict:
        normalized = normalize_or_400(number)
        reports = db.reports_for(normalized)
        last = reports.last_reported_at
        return {
            "number": normalized,
            "score": scoring.score(reports.reports, clock()),
            "spamReports": reports.spam_count,
            "notSpamReports": reports.not_spam_count,
            "lastReportedAt": iso8601(last) if last is not None else None,
        }

    @app.get("/v1/blocklist")
    def get_blocklist(request: Request, minScore: float = Query(0.5)) -> Response:
        if not math.isfinite(minScore):
            raise HTTPException(status_code=422, detail="minScore must be a finite number")
        min_score = min(1.0, max(MIN_SCORE_FLOOR, minScore))
        cached = blocklist.get(min_score)
        headers = {"ETag": cached.etag, "Cache-Control": "no-cache"}
        if _etag_matches(request.headers.get("if-none-match"), cached.etag):
            return Response(status_code=304, headers=headers)
        return Response(content=cached.body, media_type="application/json", headers=headers)

    return app
