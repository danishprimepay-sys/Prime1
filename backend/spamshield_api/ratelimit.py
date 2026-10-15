"""In-memory sliding-window rate limiter (single process)."""

from __future__ import annotations

import threading
from collections import deque
from collections.abc import Callable


class SlidingWindowLimiter:
    def __init__(self, limit: int, window_seconds: float, clock: Callable[[], float]):
        self.limit = limit
        self.window = window_seconds
        self._clock = clock
        self._hits: dict[str, deque[float]] = {}
        self._lock = threading.Lock()
        self._last_sweep = 0.0

    def retry_after(self, key: str) -> float:
        """Seconds until `key` may act again; 0 when allowed now. Does not record a hit."""
        now = self._clock()
        with self._lock:
            hits = self._prune(key, now)
            if hits is None or len(hits) < self.limit:
                return 0.0
            return max(0.0, hits[0] + self.window - now)

    def hit(self, key: str) -> None:
        now = self._clock()
        with self._lock:
            self._hits.setdefault(key, deque()).append(now)
            if now - self._last_sweep > self.window:
                self._sweep(now)

    def _prune(self, key: str, now: float) -> deque[float] | None:
        hits = self._hits.get(key)
        if hits is None:
            return None
        while hits and hits[0] <= now - self.window:
            hits.popleft()
        return hits

    def _sweep(self, now: float) -> None:
        # Drop idle keys so memory stays bounded by recently active clients.
        for key in list(self._hits):
            if not self._prune(key, now):
                del self._hits[key]
        self._last_sweep = now
