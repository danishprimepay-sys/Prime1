"""Settings loaded from environment variables."""

from __future__ import annotations

import os
from dataclasses import dataclass

DEV_INSTALL_SALT = "spamshield-dev-salt-do-not-use-in-production"


def _env_bool(name: str) -> bool:
    return os.environ.get(name, "").strip().lower() in {"1", "true", "yes", "on"}


@dataclass(frozen=True)
class Settings:
    database_path: str = "./spamshield.sqlite3"
    install_salt: str = DEV_INSTALL_SALT
    country_code: str = "1"
    install_reports_per_hour: int = 30
    ip_reports_per_hour: int = 120
    trust_proxy: bool = False
    blocklist_cache_seconds: float = 300.0

    @property
    def using_dev_salt(self) -> bool:
        return self.install_salt == DEV_INSTALL_SALT

    @classmethod
    def from_env(cls) -> "Settings":
        env = os.environ
        salt = env.get("INSTALL_SALT", "")
        if not salt and env.get("SPAMSHIELD_ENV", "").lower() == "production":
            raise RuntimeError("INSTALL_SALT must be set when SPAMSHIELD_ENV=production")
        return cls(
            database_path=env.get("DATABASE_PATH", cls.database_path),
            install_salt=salt or DEV_INSTALL_SALT,
            country_code=env.get("COUNTRY_CODE", cls.country_code),
            install_reports_per_hour=int(env.get("RATE_LIMIT_INSTALL_PER_HOUR", cls.install_reports_per_hour)),
            ip_reports_per_hour=int(env.get("RATE_LIMIT_IP_PER_HOUR", cls.ip_reports_per_hour)),
            trust_proxy=_env_bool("TRUST_PROXY"),
            blocklist_cache_seconds=float(env.get("BLOCKLIST_CACHE_SECONDS", cls.blocklist_cache_seconds)),
        )
