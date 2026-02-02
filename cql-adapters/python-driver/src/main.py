"""Main entry point for the Python driver adapter."""

import asyncio
import logging
import signal
import sys

from .config import Config
from .server import Server
from .session import SessionRegistry

# Try to use uvloop for better async performance (optional dependency)
_UVLOOP_AVAILABLE = False
try:
    import uvloop
    _UVLOOP_AVAILABLE = True
except ImportError:
    pass


def main() -> None:
    """Run the driver adapter."""
    # Load configuration
    config = Config.from_env()

    # Configure logging
    logging.basicConfig(
        level=getattr(logging, config.log_level, logging.INFO),
        format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
        stream=sys.stderr,
    )
    logger = logging.getLogger(__name__)

    # Suppress noisy cassandra-driver logs
    logging.getLogger("cassandra").setLevel(logging.WARNING)

    # Use uvloop if available for better async performance
    if _UVLOOP_AVAILABLE:
        uvloop.install()
        logger.info("Using uvloop for improved async performance")

    logger.info(
        f"Starting latte python driver adapter: "
        f"socket={config.socket_path}, "
        f"inflight_limit={config.inflight_limit}"
    )

    # Create session registry and server
    registry = SessionRegistry(config)
    server = Server(config, registry)

    # Run the server
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)

    # Handle shutdown signals
    def shutdown_handler():
        logger.info("Shutdown signal received")
        loop.stop()

    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, shutdown_handler)

    try:
        loop.run_until_complete(server.run())
    except KeyboardInterrupt:
        logger.info("Interrupted")
    finally:
        loop.close()


if __name__ == "__main__":
    main()
