"""Configuration module for the Python driver adapter."""

from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass
class Config:
    """Configuration for the driver adapter."""

    socket_path: str
    inflight_limit: int
    log_level: str

    @classmethod
    def from_env(cls) -> Config:
        """Load configuration from environment variables."""
        socket_path = os.environ.get("LATTE_DRIVER_SOCKET", "/tmp/latte-driver.sock")

        inflight_limit = int(os.environ.get("LATTE_DRIVER_INFLIGHT", "512"))

        log_level = os.environ.get("LATTE_DRIVER_LOG_LEVEL", "INFO").upper()

        return cls(
            socket_path=socket_path,
            inflight_limit=inflight_limit,
            log_level=log_level,
        )
