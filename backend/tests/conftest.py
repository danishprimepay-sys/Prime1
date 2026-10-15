from __future__ import annotations

import itertools

import pytest
from fastapi.testclient import TestClient

from spamshield_api import Settings, create_app

DAY = 86400.0


class FakeClock:
    def __init__(self, now: float = 1_790_000_000.0):
        self.now = now

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


_install_ids = itertools.count()


def new_install_id() -> str:
    return f"test-install-{next(_install_ids):08d}"


@pytest.fixture
def clock() -> FakeClock:
    return FakeClock()


@pytest.fixture
def make_client(tmp_path, clock):
    clients = []

    def factory(**overrides) -> TestClient:
        settings = Settings(
            database_path=str(tmp_path / f"db{len(clients)}.sqlite3"),
            install_salt="test-salt",
            **overrides,
        )
        client = TestClient(create_app(settings, clock=clock))
        client.__enter__()
        clients.append(client)
        return client

    yield factory
    for c in clients:
        c.__exit__(None, None, None)


@pytest.fixture
def client(make_client) -> TestClient:
    return make_client(install_reports_per_hour=10_000, ip_reports_per_hour=10_000)


def report(client: TestClient, number: str, verdict: str = "spam", install_id: str | None = None,
           channel: str = "call", **extra):
    body = {"number": number, "channel": channel, "verdict": verdict,
            "installId": install_id or new_install_id(), **extra}
    return client.post("/v1/reports", json=body)
