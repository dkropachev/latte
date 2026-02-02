"""Tests for server functionality."""

import asyncio
import struct
import time
from io import BytesIO
from unittest.mock import AsyncMock, MagicMock

import pytest

from src.config import Config
from src.protocol import (
    ErrorCode,
    Frame,
    FrameHeader,
    Opcode,
    encode_frame,
    error_frame,
    void_result_frame,
    write_byte,
    write_long,
    write_long_string,
    write_short,
    write_string,
)
from src.server import (
    ERROR_LOG_INTERVAL,
    ERROR_LOG_THRESHOLD,
    HEADER_LENGTH,
    Server,
    _append_latency_to_frame,
    _error_cache,
    _next_request_id,
    _rate_limited_error_log,
)
from src.session import SessionRegistry

# =============================================================================
# Test fixtures
# =============================================================================


@pytest.fixture
def config():
    """Create a test configuration."""
    return Config(
        socket_path="/tmp/test-server.sock",
        inflight_limit=100,
        log_level="INFO",
    )


@pytest.fixture
def registry(config):
    """Create a test session registry."""
    return SessionRegistry(config)


@pytest.fixture
def server(config, registry):
    """Create a test server instance."""
    return Server(config, registry)


# =============================================================================
# Rate-limited logging tests
# =============================================================================


class TestRateLimitedLogging:
    """Test rate-limited error logging."""

    def setup_method(self):
        """Clear error tracking state before each test."""
        _error_cache._counts.clear()
        _error_cache._last_logged.clear()

    def test_first_occurrence_logged(self, caplog):
        """First occurrence of an error should always be logged."""
        import logging

        caplog.set_level(logging.WARNING)

        _rate_limited_error_log("test_error", "Test error message")

        assert "Test error message" in caplog.text
        assert _error_cache._counts["test_error"] == 1

    def test_repeated_errors_rate_limited(self, caplog):
        """Repeated errors should be rate limited."""
        import logging

        caplog.set_level(logging.WARNING)

        # Log the first occurrence
        _rate_limited_error_log("test_error", "Test error message")
        caplog.clear()

        # Immediately log again - should not appear
        _rate_limited_error_log("test_error", "Test error message")
        assert _error_cache._counts["test_error"] == 2
        # Second occurrence within interval shouldn't log
        assert "Test error message" not in caplog.text

    def test_threshold_triggers_log(self, caplog):
        """Error logged when count threshold is reached."""
        import logging

        caplog.set_level(logging.WARNING)

        # Log first occurrence
        _rate_limited_error_log("test_error", "Test error message")
        caplog.clear()

        # Log up to threshold - 1 times (should not log)
        for _ in range(ERROR_LOG_THRESHOLD - 2):
            _rate_limited_error_log("test_error", "Test error message")

        assert "Test error message" not in caplog.text

        # One more should trigger the threshold
        _rate_limited_error_log("test_error", "Test error message")
        assert "Test error message" in caplog.text
        assert f"occurred {ERROR_LOG_THRESHOLD} times" in caplog.text

    def test_time_interval_triggers_log(self, caplog, monkeypatch):
        """Error logged when time interval has passed."""
        import logging

        caplog.set_level(logging.WARNING)

        # Log first occurrence
        _rate_limited_error_log("test_error", "Test error message")
        caplog.clear()

        # Simulate time passing
        _error_cache._last_logged["test_error"] = time.time() - ERROR_LOG_INTERVAL - 1

        # Should log again after interval
        _rate_limited_error_log("test_error", "Test error message")
        assert "Test error message" in caplog.text

    def test_different_errors_tracked_separately(self, caplog):
        """Different error keys should be tracked separately."""
        import logging

        caplog.set_level(logging.WARNING)

        _rate_limited_error_log("error_a", "Error A")
        _rate_limited_error_log("error_b", "Error B")

        assert _error_cache._counts["error_a"] == 1
        assert _error_cache._counts["error_b"] == 1
        assert "Error A" in caplog.text
        assert "Error B" in caplog.text


# =============================================================================
# Server initialization tests
# =============================================================================


class TestServerInitialization:
    """Test server initialization."""

    def test_server_creation(self, config, registry):
        """Server should initialize with correct configuration."""
        server = Server(config, registry)
        assert server._config == config
        assert server._registry == registry
        assert server._server is None
        assert not server._shutdown_event.is_set()

    def test_semaphore_limit(self, config, registry):
        """Server semaphore should match inflight limit."""
        config.inflight_limit = 50
        server = Server(config, registry)
        # Semaphore initial value matches inflight_limit
        # We can verify this by checking we can acquire that many times
        assert server._semaphore._value == 50


# =============================================================================
# Latency frame appending tests
# =============================================================================


class TestLatencyAppending:
    """Test latency measurement appending to frames."""

    def test_append_latency_to_void_result(self):
        """Latency should be appended to VOID result frames."""
        frame = void_result_frame(stream=1)
        latency_ns = 1_000_000  # 1ms

        result = _append_latency_to_frame(frame, latency_ns)

        # Body should be original body + 8 bytes for latency
        assert len(result.body) == len(frame.body) + 8
        assert result.body[: len(frame.body)] == frame.body

        # Extract and verify latency
        latency_bytes = result.body[-8:]
        extracted_latency = struct.unpack(">Q", latency_bytes)[0]
        assert extracted_latency == latency_ns

    def test_append_latency_to_error_frame(self):
        """Latency should be appended to error frames."""
        frame = error_frame(stream=2, code=ErrorCode.SERVER, message="Test error")
        latency_ns = 500_000  # 0.5ms

        result = _append_latency_to_frame(frame, latency_ns)

        assert len(result.body) == len(frame.body) + 8
        latency_bytes = result.body[-8:]
        extracted_latency = struct.unpack(">Q", latency_bytes)[0]
        assert extracted_latency == latency_ns

    def test_append_latency_preserves_header(self):
        """Latency appending should preserve frame header fields."""
        frame = void_result_frame(stream=42)
        latency_ns = 2_000_000

        result = _append_latency_to_frame(frame, latency_ns)

        assert result.header.version == frame.header.version
        assert result.header.flags == frame.header.flags
        assert result.header.stream == frame.header.stream
        assert result.header.opcode == frame.header.opcode
        # Body length should be updated
        assert result.header.body_length == len(result.body)

    def test_append_latency_large_value(self):
        """Large latency values should be handled correctly."""
        frame = void_result_frame(stream=1)
        latency_ns = 10_000_000_000  # 10 seconds in ns

        result = _append_latency_to_frame(frame, latency_ns)

        latency_bytes = result.body[-8:]
        extracted_latency = struct.unpack(">Q", latency_bytes)[0]
        assert extracted_latency == latency_ns


# =============================================================================
# Request ID generation tests
# =============================================================================


class TestRequestIdGeneration:
    """Test request ID generation."""

    def test_request_id_increments(self):
        """Request IDs should increment monotonically."""
        id1 = _next_request_id()
        id2 = _next_request_id()
        id3 = _next_request_id()

        assert id2 == id1 + 1
        assert id3 == id2 + 1


# =============================================================================
# Frame dispatch tests
# =============================================================================


class TestFrameDispatch:
    """Test frame dispatch logic."""

    @pytest.mark.asyncio
    async def test_dispatch_unsupported_opcode(self, server):
        """Unsupported opcodes should return protocol error."""
        # OPTIONS opcode (0x05) is not supported
        frame = Frame(
            header=FrameHeader(
                version=0x04,
                flags=0,
                stream=1,
                opcode=Opcode.OPTIONS,
                body_length=0,
            ),
            body=b"",
        )

        result = await server._dispatch(frame)

        assert result.header.opcode == Opcode.ERROR
        # Parse error code from body
        error_code = struct.unpack(">i", result.body[:4])[0]
        assert error_code == ErrorCode.PROTOCOL

    @pytest.mark.asyncio
    async def test_dispatch_create_session_no_db(self, server):
        """CREATE_SESSION should return error when DB is unavailable."""
        # Build CREATE_SESSION frame body
        buf = BytesIO()
        write_short(buf, 1)  # 1 param
        write_string(buf, "contact_points")
        write_string(buf, "invalid-host:9042")

        frame = Frame(
            header=FrameHeader(
                version=0x04,
                flags=0,
                stream=1,
                opcode=Opcode.CREATE_SESSION,
                body_length=len(buf.getvalue()),
            ),
            body=buf.getvalue(),
        )

        result = await server._dispatch(frame)

        # Should return error (can't connect to invalid host)
        assert result.header.opcode == Opcode.ERROR

    @pytest.mark.asyncio
    async def test_dispatch_query_unknown_session(self, server):
        """QUERY with unknown session ID should return error."""
        # Build QUERY frame body
        # Format: session_id (8), long_string query, short consistency, byte flags
        buf = BytesIO()
        write_long(buf, 99999)  # Non-existent session ID
        write_long_string(buf, "SELECT * FROM test")
        write_short(buf, 0x0001)  # Consistency: ONE
        write_byte(buf, 0)  # Flags

        frame = Frame(
            header=FrameHeader(
                version=0x04,
                flags=0,
                stream=1,
                opcode=Opcode.QUERY,
                body_length=len(buf.getvalue()),
            ),
            body=buf.getvalue(),
        )

        result = await server._dispatch(frame)

        assert result.header.opcode == Opcode.ERROR
        error_code = struct.unpack(">i", result.body[:4])[0]
        assert error_code == ErrorCode.PROTOCOL

    @pytest.mark.asyncio
    async def test_dispatch_prepare_unknown_session(self, server):
        """PREPARE with unknown session ID should return error."""
        # Build PREPARE frame body
        buf = BytesIO()
        write_long(buf, 99999)  # Non-existent session ID
        write_string(buf, "SELECT * FROM test WHERE id = ?")
        write_string(buf, "stmt_key")

        frame = Frame(
            header=FrameHeader(
                version=0x04,
                flags=0,
                stream=1,
                opcode=Opcode.PREPARE,
                body_length=len(buf.getvalue()),
            ),
            body=buf.getvalue(),
        )

        result = await server._dispatch(frame)

        assert result.header.opcode == Opcode.ERROR

    @pytest.mark.asyncio
    async def test_dispatch_execute_unknown_session(self, server):
        """EXECUTE with unknown session ID should return error."""
        # Build EXECUTE frame body
        buf = BytesIO()
        write_long(buf, 99999)  # Non-existent session ID
        write_string(buf, "stmt_key")
        write_string(buf, "ONE")  # Consistency
        write_short(buf, 0)  # No values

        frame = Frame(
            header=FrameHeader(
                version=0x04,
                flags=0,
                stream=1,
                opcode=Opcode.EXECUTE,
                body_length=len(buf.getvalue()),
            ),
            body=buf.getvalue(),
        )

        result = await server._dispatch(frame)

        assert result.header.opcode == Opcode.ERROR

    @pytest.mark.asyncio
    async def test_dispatch_batch_unknown_session(self, server):
        """BATCH with unknown session ID should return error."""
        # Build BATCH frame body
        buf = BytesIO()
        write_long(buf, 99999)  # Non-existent session ID
        buf.write(bytes([0]))  # Batch type: LOGGED
        write_short(buf, 0)  # No statements
        write_string(buf, "ONE")  # Consistency

        frame = Frame(
            header=FrameHeader(
                version=0x04,
                flags=0,
                stream=1,
                opcode=Opcode.BATCH,
                body_length=len(buf.getvalue()),
            ),
            body=buf.getvalue(),
        )

        result = await server._dispatch(frame)

        assert result.header.opcode == Opcode.ERROR


# =============================================================================
# Semaphore concurrency tests
# =============================================================================


class TestConcurrencyControl:
    """Test semaphore-based concurrency control."""

    @pytest.mark.asyncio
    async def test_semaphore_limits_concurrent_dispatches(self, config, registry):
        """Semaphore should limit concurrent request handling."""
        config.inflight_limit = 2
        server = Server(config, registry)

        # Track concurrent executions
        concurrent_count = 0
        max_concurrent = 0
        lock = asyncio.Lock()

        async def slow_dispatch(frame):
            nonlocal concurrent_count, max_concurrent
            async with lock:
                concurrent_count += 1
                max_concurrent = max(max_concurrent, concurrent_count)
            await asyncio.sleep(0.05)  # Simulate work
            async with lock:
                concurrent_count -= 1
            return void_result_frame(frame.header.stream)

        # Mock _dispatch to track concurrency
        server._dispatch = slow_dispatch

        # Create test frames
        frames = [
            Frame(
                header=FrameHeader(
                    version=0x04, flags=0, stream=i, opcode=Opcode.QUERY, body_length=0
                ),
                body=b"",
            )
            for i in range(5)
        ]

        # Create a response queue
        response_queue: asyncio.Queue = asyncio.Queue()

        # Dispatch all frames concurrently
        tasks = [
            asyncio.create_task(server._dispatch_with_semaphore(f, response_queue))
            for f in frames
        ]
        await asyncio.gather(*tasks)

        # Max concurrent should not exceed semaphore limit
        assert max_concurrent <= config.inflight_limit


# =============================================================================
# Writer loop tests
# =============================================================================


class TestWriterLoop:
    """Test response writer functionality."""

    @pytest.mark.asyncio
    async def test_writer_loop_writes_frames(self, server):
        """Writer loop should write encoded frames to the stream using write coalescing."""
        # Create mock writer
        writer = MagicMock()
        writer.writelines = MagicMock()
        writer.drain = AsyncMock()

        response_queue: asyncio.Queue = asyncio.Queue()

        # Start writer loop
        writer_task = asyncio.create_task(
            server._writer_loop(writer, response_queue)
        )

        # Queue some frames
        frame1 = void_result_frame(stream=1)
        frame2 = void_result_frame(stream=2)

        await response_queue.put(frame1)
        await response_queue.put(frame2)
        await response_queue.put(None)  # Signal to stop

        await writer_task

        # Writer uses writelines for coalesced writes
        assert writer.writelines.call_count >= 1
        assert writer.drain.call_count >= 1

        # Collect all frames written across all writelines calls
        all_frames = []
        for call in writer.writelines.call_args_list:
            all_frames.extend(call[0][0])

        assert encode_frame(frame1) in all_frames
        assert encode_frame(frame2) in all_frames

    @pytest.mark.asyncio
    async def test_writer_loop_stops_on_none(self, server):
        """Writer loop should stop when receiving None."""
        writer = MagicMock()
        writer.writelines = MagicMock()
        writer.drain = AsyncMock()

        response_queue: asyncio.Queue = asyncio.Queue()

        writer_task = asyncio.create_task(
            server._writer_loop(writer, response_queue)
        )

        # Send stop signal immediately
        await response_queue.put(None)

        await asyncio.wait_for(writer_task, timeout=1.0)

        # No frames should have been written
        assert writer.writelines.call_count == 0


# =============================================================================
# Connection handling tests
# =============================================================================


class TestConnectionHandling:
    """Test client connection handling."""

    @pytest.mark.asyncio
    async def test_handle_connection_disconnect(self, server, caplog):
        """Connection handler should handle clean disconnects."""
        import logging

        caplog.set_level(logging.INFO)

        # Create mock reader that simulates disconnect
        reader = AsyncMock()
        reader.readexactly = AsyncMock(side_effect=asyncio.IncompleteReadError(b"", HEADER_LENGTH))

        # Create mock writer
        writer = MagicMock()
        writer.close = MagicMock()
        writer.wait_closed = AsyncMock()

        await server._handle_connection(reader, writer)

        assert "Latte host connected" in caplog.text
        assert "Latte host disconnected" in caplog.text
        writer.close.assert_called_once()

    @pytest.mark.asyncio
    async def test_handle_connection_reads_frames(self, server):
        """Connection handler should read and dispatch frames."""
        # Build a valid CREATE_SESSION frame
        buf = BytesIO()
        write_short(buf, 1)
        write_string(buf, "contact_points")
        write_string(buf, "invalid:9042")
        body = buf.getvalue()

        header = struct.pack(">BBHBI", 0x04, 0, 1, Opcode.CREATE_SESSION, len(body))

        # Mock reader
        read_count = 0

        async def mock_readexactly(n):
            nonlocal read_count
            read_count += 1
            if read_count == 1:
                return header
            elif read_count == 2:
                return body
            else:
                raise asyncio.IncompleteReadError(b"", n)

        reader = AsyncMock()
        reader.readexactly = mock_readexactly

        # Mock writer - _writer_loop uses writelines for coalesced writes
        writer = MagicMock()
        writer.writelines = MagicMock()
        writer.drain = AsyncMock()
        writer.close = MagicMock()
        writer.wait_closed = AsyncMock()

        await server._handle_connection(reader, writer)

        # Should have written a response via writelines
        assert writer.writelines.call_count >= 1


# =============================================================================
# Graceful shutdown tests
# =============================================================================


class TestGracefulShutdown:
    """Test graceful shutdown behavior."""

    @pytest.mark.asyncio
    async def test_shutdown_sets_event(self, server):
        """Shutdown should set the shutdown event."""
        import signal

        # Mock the server object
        server._server = MagicMock()
        server._server.close = MagicMock()
        server._server.wait_closed = AsyncMock()

        await server._shutdown(signal.SIGTERM)

        assert server._shutdown_event.is_set()
        server._server.close.assert_called_once()

    @pytest.mark.asyncio
    async def test_shutdown_closes_sessions(self, server, registry):
        """Shutdown should close all sessions."""
        import signal

        # Mock the server
        server._server = MagicMock()
        server._server.close = MagicMock()
        server._server.wait_closed = AsyncMock()

        # Add mock session
        registry._sessions[1] = MagicMock()
        registry._sessions[1].shutdown = MagicMock()

        await server._shutdown(signal.SIGTERM)

        # Sessions should be closed
        assert registry.session_count() == 0


# =============================================================================
# Handler exception tests
# =============================================================================


class TestHandlerExceptions:
    """Test exception handling in dispatch handlers."""

    @pytest.mark.asyncio
    async def test_dispatch_catches_handler_exceptions(self, server):
        """Dispatch should catch and convert handler exceptions to error frames."""
        # Create a frame that will cause an exception in parsing
        # Invalid QUERY frame (missing fields)
        frame = Frame(
            header=FrameHeader(
                version=0x04,
                flags=0,
                stream=1,
                opcode=Opcode.QUERY,
                body_length=0,
            ),
            body=b"",  # Empty body will cause parse error
        )

        result = await server._dispatch(frame)

        assert result.header.opcode == Opcode.ERROR
        error_code = struct.unpack(">i", result.body[:4])[0]
        assert error_code == ErrorCode.SERVER
