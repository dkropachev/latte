"""Unix socket server for the driver adapter."""

import asyncio
import contextlib
import itertools
import logging
import os
import signal
import struct
import time
from collections import OrderedDict
from pathlib import Path

from .config import Config
from .protocol import (
    ErrorCode,
    Frame,
    FrameHeader,
    Opcode,
    encode_frame,
    error_frame,
    session_created_frame,
)
from .session import (
    SessionRegistry,
    parse_batch_frame,
    parse_create_session_frame,
    parse_execute_frame,
    parse_prepare_frame,
    parse_query_frame,
)

logger = logging.getLogger(__name__)

# Thread-safe request counter using itertools.count()
_request_counter = itertools.count(1)


def _next_request_id() -> int:
    """Generate a unique request ID for correlation (thread-safe)."""
    return next(_request_counter)

# Rate limiting for error logging with LRU eviction
ERROR_LOG_INTERVAL = 5.0  # seconds between logging the same error
ERROR_LOG_THRESHOLD = 10  # log every N occurrences
ERROR_CACHE_MAX_SIZE = 100  # max error types to track (LRU eviction)


class _LRUErrorCache:
    """LRU cache for error rate limiting to prevent unbounded memory growth."""

    def __init__(self, max_size: int):
        self._max_size = max_size
        self._counts: OrderedDict[str, int] = OrderedDict()
        self._last_logged: OrderedDict[str, float] = OrderedDict()

    def get_and_increment(self, key: str) -> tuple[int, float]:
        """Get count and last logged time, incrementing count. Returns (count, last_logged)."""
        # Move to end (most recently used) and increment
        if key in self._counts:
            self._counts.move_to_end(key)
            self._counts[key] += 1
            self._last_logged.move_to_end(key)
        else:
            # Evict oldest if at capacity
            if len(self._counts) >= self._max_size:
                self._counts.popitem(last=False)
                self._last_logged.popitem(last=False)
            self._counts[key] = 1
            self._last_logged[key] = 0.0

        return self._counts[key], self._last_logged.get(key, 0.0)

    def update_last_logged(self, key: str, timestamp: float) -> None:
        """Update the last logged timestamp for a key."""
        if key in self._last_logged:
            self._last_logged[key] = timestamp


_error_cache = _LRUErrorCache(ERROR_CACHE_MAX_SIZE)


def _rate_limited_error_log(error_key: str, message: str) -> None:
    """Log an error with rate limiting to prevent log spam during high error rates.

    In benchmarking scenarios, the same error can occur thousands of times per
    second (e.g., connection issues, query errors). Logging every occurrence
    would overwhelm the log output and potentially impact performance.

    This function implements a hybrid rate limiting strategy:
    1. First occurrence: Always logged immediately for quick detection
    2. Time-based: Log if ERROR_LOG_INTERVAL seconds have passed
    3. Count-based: Log every ERROR_LOG_THRESHOLD occurrences

    Uses LRU cache to limit memory usage (max ERROR_CACHE_MAX_SIZE error types).

    The error_key should uniquely identify the error type, typically composed
    of the operation and exception type (e.g., "handler_error:EXECUTE:Timeout").

    When logging after the first occurrence, the message includes the total
    count to indicate how many errors were suppressed.

    Args:
        error_key: Unique identifier for this error type
        message: The error message to log
    """
    count, last_logged = _error_cache.get_and_increment(error_key)
    now = time.time()

    should_log = (
        count == 1  # First occurrence - always log
        or (now - last_logged) >= ERROR_LOG_INTERVAL  # Time threshold passed
        or count % ERROR_LOG_THRESHOLD == 0  # Count threshold reached
    )

    if should_log:
        if count > 1:
            logger.warning(f"{message} (occurred {count} times)")
        else:
            logger.warning(message)
        _error_cache.update_last_logged(error_key, now)

HEADER_LENGTH = 9


class Server:
    """Unix socket server for handling Latte requests."""

    def __init__(self, config: Config, registry: SessionRegistry):
        self._config = config
        self._registry = registry
        self._semaphore = asyncio.Semaphore(config.inflight_limit)
        self._shutdown_event = asyncio.Event()
        self._server: asyncio.Server | None = None

    async def run(self) -> None:
        """Start the server and listen for connections."""
        socket_path = Path(self._config.socket_path)

        # Create parent directory
        socket_path.parent.mkdir(parents=True, exist_ok=True)

        # Remove stale socket
        if socket_path.exists():
            socket_path.unlink()

        # Set up signal handlers for graceful shutdown
        loop = asyncio.get_event_loop()
        for sig in (signal.SIGTERM, signal.SIGINT):
            loop.add_signal_handler(sig, lambda s=sig: asyncio.create_task(self._shutdown(s)))

        # Create Unix socket server
        self._server = await asyncio.start_unix_server(
            self._handle_connection,
            path=str(socket_path),
        )

        # Set socket permissions
        os.chmod(str(socket_path), 0o666)

        logger.info(
            f"Driver adapter listening on {socket_path}, "
            f"inflight_limit={self._config.inflight_limit}, "
            f"contact_points={self._config.contact_points}"
        )

        async with self._server:
            # Wait for either serve_forever or shutdown signal
            serve_task = asyncio.create_task(self._server.serve_forever())
            shutdown_task = asyncio.create_task(self._shutdown_event.wait())

            done, pending = await asyncio.wait(
                [serve_task, shutdown_task],
                return_when=asyncio.FIRST_COMPLETED,
            )

            # Cancel pending tasks
            for task in pending:
                task.cancel()
                with contextlib.suppress(asyncio.CancelledError):
                    await task

        # Clean up socket file
        if socket_path.exists():
            socket_path.unlink()
            logger.info(f"Removed socket file: {socket_path}")

    async def _shutdown(self, sig: signal.Signals) -> None:
        """Handle shutdown signal."""
        logger.info(f"Received {sig.name}, initiating graceful shutdown...")

        # Close all sessions
        session_count = self._registry.close_all_sessions()
        if session_count > 0:
            logger.info(f"Closed {session_count} session(s)")

        # Signal shutdown
        self._shutdown_event.set()

        # Close the server
        if self._server:
            self._server.close()
            await self._server.wait_closed()

        logger.info("Shutdown complete")

    async def _handle_connection(
        self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter
    ) -> None:
        """Handle a client connection."""
        logger.info("Latte host connected")

        # Response queue for the writer
        response_queue: asyncio.Queue[Frame | None] = asyncio.Queue()

        # Start writer task
        writer_task = asyncio.create_task(self._writer_loop(writer, response_queue))

        # Pending tasks for graceful shutdown
        pending_tasks: set[asyncio.Task] = set()

        try:
            while True:
                # Read frame header
                header_bytes = await reader.readexactly(HEADER_LENGTH)
                version, flags, stream, opcode, body_length = struct.unpack(
                    ">BBHBI", header_bytes
                )

                # Read body
                body = b""
                if body_length > 0:
                    body = await reader.readexactly(body_length)

                frame = Frame(
                    header=FrameHeader(
                        version=version,
                        flags=flags,
                        stream=stream,
                        opcode=Opcode(opcode),
                        body_length=body_length,
                    ),
                    body=body,
                )

                # Dispatch request
                task = asyncio.create_task(
                    self._dispatch_with_semaphore(frame, response_queue)
                )
                pending_tasks.add(task)
                task.add_done_callback(pending_tasks.discard)

        except asyncio.IncompleteReadError:
            logger.info("Latte host disconnected")
        except Exception as e:
            _rate_limited_error_log(f"connection_error:{type(e).__name__}", f"Connection error: {e}")
        finally:
            # Wait for pending tasks with timeout for graceful shutdown
            if pending_tasks:
                logger.debug(f"Waiting for {len(pending_tasks)} pending tasks to complete")
                try:
                    await asyncio.wait_for(
                        asyncio.gather(*pending_tasks, return_exceptions=True),
                        timeout=5.0,
                    )
                except TimeoutError:
                    logger.warning(f"Timeout waiting for {len(pending_tasks)} pending tasks")
                    for task in pending_tasks:
                        task.cancel()

            # Signal writer to stop
            await response_queue.put(None)
            await writer_task

            writer.close()
            await writer.wait_closed()

            # Clean up sessions when connection terminates
            session_count = self._registry.close_all_sessions()
            if session_count > 0:
                logger.info(f"Closed {session_count} session(s) on disconnect")

    async def _dispatch_with_semaphore(
        self, frame: Frame, response_queue: asyncio.Queue[Frame | None]
    ) -> None:
        """Dispatch a request with semaphore for concurrency control."""
        async with self._semaphore:
            response = await self._dispatch(frame)
            await response_queue.put(response)

    async def _dispatch(self, frame: Frame) -> Frame:
        """Dispatch a request to the appropriate handler."""
        request_id = _next_request_id()
        stream_id = frame.header.stream
        opcode_name = frame.header.opcode.name

        logger.debug(f"[req={request_id} stream={stream_id}] Dispatching {opcode_name}")

        try:
            match frame.header.opcode:
                case Opcode.CREATE_SESSION:
                    return await self._handle_create_session(frame, request_id)
                case Opcode.QUERY:
                    return await self._handle_query(frame, request_id)
                case Opcode.PREPARE:
                    return await self._handle_prepare(frame, request_id)
                case Opcode.EXECUTE:
                    return await self._handle_execute(frame, request_id)
                case Opcode.BATCH:
                    return await self._handle_batch(frame, request_id)
                case _:
                    logger.warning(f"[req={request_id} stream={stream_id}] Unsupported opcode: {frame.header.opcode:#x}")
                    return error_frame(
                        frame.header.stream,
                        ErrorCode.PROTOCOL,
                        f"Unsupported opcode: {frame.header.opcode:#x}",
                    )
        except Exception as e:
            error_key = f"handler_error:{opcode_name}:{type(e).__name__}"
            _rate_limited_error_log(error_key, f"[req={request_id} stream={stream_id}] Handler error for {opcode_name}: {e}")
            return error_frame(frame.header.stream, ErrorCode.SERVER, str(e))

    async def _handle_create_session(self, frame: Frame, request_id: int) -> Frame:
        """Handle CREATE_SESSION request."""
        params = parse_create_session_frame(frame.body)

        logger.debug(f"[req={request_id}] Creating session with params: {list(params.keys())}")

        # Run blocking connection in thread pool
        loop = asyncio.get_event_loop()
        session_id, session, error = await loop.run_in_executor(
            None, self._registry.create_session, params
        )

        if error:
            logger.warning(f"[req={request_id}] Failed to create session: {error}")
            return error_frame(frame.header.stream, ErrorCode.SERVER, error)

        logger.info(f"[req={request_id}] Created session {session_id}")
        return session_created_frame(frame.header.stream, session_id)

    async def _handle_query(self, frame: Frame, request_id: int) -> Frame:
        """Handle QUERY request."""
        session_id, query, consistency = parse_query_frame(frame.body)

        session = self._registry.get(session_id)
        if session is None:
            logger.warning(f"[req={request_id}] Unknown session ID: {session_id}")
            return error_frame(
                frame.header.stream,
                ErrorCode.PROTOCOL,
                f"Unknown session ID: {session_id}",
            )

        logger.debug(f"[req={request_id} session={session_id}] Executing query")

        # Execute in thread pool - latency is measured inside the session method
        loop = asyncio.get_event_loop()
        result, latency_ns = await loop.run_in_executor(
            None, session.execute_query, frame.header.stream, query, consistency
        )

        logger.debug(f"[req={request_id} session={session_id}] Query completed in {latency_ns / 1_000_000:.2f}ms")

        return _append_latency_to_frame(result, latency_ns)

    async def _handle_prepare(self, frame: Frame, request_id: int) -> Frame:
        """Handle PREPARE request."""
        session_id, query, statement_key = parse_prepare_frame(frame.body)

        session = self._registry.get(session_id)
        if session is None:
            logger.warning(f"[req={request_id}] Unknown session ID: {session_id}")
            return error_frame(
                frame.header.stream,
                ErrorCode.PROTOCOL,
                f"Unknown session ID: {session_id}",
            )

        logger.debug(f"[req={request_id} session={session_id}] Preparing statement: {statement_key}")

        # Execute in thread pool
        loop = asyncio.get_event_loop()
        result = await loop.run_in_executor(
            None, session.prepare, frame.header.stream, query, statement_key
        )

        return result

    async def _handle_execute(self, frame: Frame, request_id: int) -> Frame:
        """Handle EXECUTE request."""
        session_id, statement_key, consistency, typed_values = parse_execute_frame(frame.body)

        session = self._registry.get(session_id)
        if session is None:
            logger.warning(f"[req={request_id}] Unknown session ID: {session_id}")
            return error_frame(
                frame.header.stream,
                ErrorCode.PROTOCOL,
                f"Unknown session ID: {session_id}",
            )

        logger.debug(f"[req={request_id} session={session_id}] Executing {statement_key} with {len(typed_values)} values")

        # Execute in thread pool - latency is measured inside the session method
        loop = asyncio.get_event_loop()
        result, latency_ns = await loop.run_in_executor(
            None,
            session.execute,
            frame.header.stream,
            statement_key,
            consistency,
            typed_values,
        )

        logger.debug(f"[req={request_id} session={session_id}] Execute completed in {latency_ns / 1_000_000:.2f}ms")

        return _append_latency_to_frame(result, latency_ns)

    async def _handle_batch(self, frame: Frame, request_id: int) -> Frame:
        """Handle BATCH request."""
        session_id, batch_type, statements, consistency = parse_batch_frame(frame.body)

        session = self._registry.get(session_id)
        if session is None:
            logger.warning(f"[req={request_id}] Unknown session ID: {session_id}")
            return error_frame(
                frame.header.stream,
                ErrorCode.PROTOCOL,
                f"Unknown session ID: {session_id}",
            )

        logger.debug(f"[req={request_id} session={session_id}] Executing batch with {len(statements)} statements")

        # Execute in thread pool - latency is measured inside the session method
        loop = asyncio.get_event_loop()
        result, latency_ns = await loop.run_in_executor(
            None,
            session.execute_batch,
            frame.header.stream,
            batch_type,
            statements,
            consistency,
        )

        logger.debug(f"[req={request_id} session={session_id}] Batch completed in {latency_ns / 1_000_000:.2f}ms")

        return _append_latency_to_frame(result, latency_ns)

    async def _writer_loop(
        self,
        writer: asyncio.StreamWriter,
        response_queue: asyncio.Queue[Frame | None],
    ) -> None:
        """Write responses to the client with write coalescing.

        Instead of draining after each response, batch multiple responses
        together to reduce syscall overhead. This coalesces writes while
        maintaining low latency when the queue is empty.
        """
        try:
            while True:
                # Wait for at least one frame (blocking)
                frame = await response_queue.get()
                if frame is None:
                    break

                # Collect frames to write
                batch = [encode_frame(frame)]

                # Grab more frames without waiting (up to 64)
                while len(batch) < 64:
                    try:
                        frame = response_queue.get_nowait()
                        if frame is None:
                            # Write what we have, then exit
                            writer.writelines(batch)
                            await writer.drain()
                            return
                        batch.append(encode_frame(frame))
                    except asyncio.QueueEmpty:
                        break

                # Write all collected frames at once
                writer.writelines(batch)
                await writer.drain()
        except Exception as e:
            logger.warning(f"Writer error: {e}")


def _append_latency_to_frame(frame: Frame, latency_ns: int) -> Frame:
    """Append driver-side latency measurement to the response frame.

    Latte tracks two latency components for analysis:
    1. Driver latency: Time spent in the driver adapter (this measurement)
    2. Network latency: Calculated as total - driver latency

    The latency is appended as an 8-byte big-endian unsigned integer
    (nanoseconds) at the end of the frame body. Latte reads this value
    after processing the main response.

    This allows Latte to distinguish between:
    - Database query execution time
    - Protocol encoding/decoding overhead
    - Network round-trip time

    Args:
        frame: The response frame to augment
        latency_ns: Driver-side latency in nanoseconds

    Returns:
        New frame with latency appended to body
    """
    latency_bytes = struct.pack(">Q", latency_ns)
    new_body = frame.body + latency_bytes

    return Frame(
        header=FrameHeader(
            version=frame.header.version,
            flags=frame.header.flags,
            stream=frame.header.stream,
            opcode=frame.header.opcode,
            body_length=len(new_body),
        ),
        body=new_body,
    )
