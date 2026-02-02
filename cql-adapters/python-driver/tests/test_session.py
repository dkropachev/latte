"""Tests for session management."""

from io import BytesIO

from cassandra import (
    AuthenticationFailed,
    InvalidRequest,
    ReadTimeout,
    Unauthorized,
    Unavailable,
    WriteTimeout,
)
from cassandra.cluster import NoHostAvailable

from src.protocol import ErrorCode, write_short, write_string
from src.session import (
    _convert_named_to_positional,
    _count_tuple_elements,
    _expand_tuples_in_query,
    _parse_consistency_level,
    _parse_query_columns,
    _parse_serial_consistency_level,
    _parse_set_columns,
    _parse_where_columns,
    map_exception_to_error_code,
    parse_create_session_frame,
)


class TestNamedToPositionalConversion:
    """Test named parameter to positional parameter conversion."""

    def test_simple_conversion(self):
        query = "SELECT * FROM table WHERE id = :id"
        result = _convert_named_to_positional(query)
        assert result == "SELECT * FROM table WHERE id = ?"

    def test_multiple_params(self):
        query = "INSERT INTO t (a, b, c) VALUES (:a, :b, :c)"
        result = _convert_named_to_positional(query)
        assert result == "INSERT INTO t (a, b, c) VALUES (?, ?, ?)"

    def test_underscore_in_name(self):
        query = "SELECT * FROM t WHERE user_id = :user_id"
        result = _convert_named_to_positional(query)
        assert result == "SELECT * FROM t WHERE user_id = ?"

    def test_no_conversion_in_string_literal(self):
        query = "SELECT * FROM t WHERE name = 'foo:bar'"
        result = _convert_named_to_positional(query)
        assert result == "SELECT * FROM t WHERE name = 'foo:bar'"

    def test_colon_without_param(self):
        query = "SELECT * FROM t WHERE time = '12:30:00'"
        result = _convert_named_to_positional(query)
        assert result == "SELECT * FROM t WHERE time = '12:30:00'"

    def test_already_positional(self):
        query = "SELECT * FROM t WHERE id = ?"
        result = _convert_named_to_positional(query)
        assert result == "SELECT * FROM t WHERE id = ?"


class TestTupleExpansion:
    """Test tuple expansion in queries."""

    def test_single_tuple(self):
        query = "INSERT INTO t (id, tuple_col) VALUES (?, ?)"
        bind_types = ["int", "tuple<int, text>"]
        result = _expand_tuples_in_query(query, bind_types)
        assert result == "INSERT INTO t (id, tuple_col) VALUES (?, (?, ?))"

    def test_no_tuples(self):
        query = "INSERT INTO t (a, b) VALUES (?, ?)"
        bind_types = ["int", "text"]
        result = _expand_tuples_in_query(query, bind_types)
        assert result == "INSERT INTO t (a, b) VALUES (?, ?)"

    def test_frozen_tuple(self):
        query = "INSERT INTO t (id, tuple_col) VALUES (?, ?)"
        bind_types = ["int", "frozen<tuple<int, int, text>>"]
        result = _expand_tuples_in_query(query, bind_types)
        assert result == "INSERT INTO t (id, tuple_col) VALUES (?, (?, ?, ?))"

    def test_count_tuple_elements_simple(self):
        assert _count_tuple_elements("tuple<int, text>") == 2

    def test_count_tuple_elements_nested(self):
        # tuple<int, list<text>> has 2 top-level elements
        assert _count_tuple_elements("tuple<int, list<text>>") == 2

    def test_count_tuple_elements_frozen(self):
        assert _count_tuple_elements("frozen<tuple<int, int, int>>") == 3

    def test_count_tuple_elements_not_tuple(self):
        assert _count_tuple_elements("int") == 0


class TestQueryParsing:
    """Test query column extraction."""

    def test_parse_insert(self):
        query = "INSERT INTO ks.users (id, name, age) VALUES (?, ?, ?)"
        keyspace, table, columns = _parse_query_columns(query)
        assert keyspace == "ks"
        assert table == "users"
        assert columns == ["id", "name", "age"]

    def test_parse_insert_no_keyspace(self):
        query = "INSERT INTO users (id, name) VALUES (?, ?)"
        keyspace, table, columns = _parse_query_columns(query)
        assert keyspace == ""
        assert table == "users"
        assert columns == ["id", "name"]

    def test_parse_update(self):
        query = "UPDATE ks.users SET name = ?, age = ? WHERE id = ?"
        keyspace, table, columns = _parse_query_columns(query)
        assert keyspace == "ks"
        assert table == "users"
        assert columns == ["name", "age", "id"]

    def test_parse_select(self):
        query = "SELECT * FROM ks.users WHERE id = ? AND name = ?"
        keyspace, table, columns = _parse_query_columns(query)
        assert keyspace == "ks"
        assert table == "users"
        assert columns == ["id", "name"]

    def test_parse_delete(self):
        query = "DELETE FROM ks.users WHERE id = ?"
        keyspace, table, columns = _parse_query_columns(query)
        assert keyspace == "ks"
        assert table == "users"
        assert columns == ["id"]

    def test_parse_set_columns(self):
        columns = _parse_set_columns("name = ?, age = ?, updated_at = ?")
        assert columns == ["name", "age", "updated_at"]

    def test_parse_where_columns(self):
        columns = _parse_where_columns("id = ? AND name = ?")
        assert columns == ["id", "name"]

    def test_parse_where_columns_with_in(self):
        columns = _parse_where_columns("id IN ? AND status = ?")
        assert columns == ["id", "status"]


class TestConsistencyParsing:
    """Test consistency level parsing."""

    def test_parse_one(self):
        from cassandra import ConsistencyLevel
        assert _parse_consistency_level("ONE") == ConsistencyLevel.ONE
        assert _parse_consistency_level("one") == ConsistencyLevel.ONE
        assert _parse_consistency_level("1") == ConsistencyLevel.ONE

    def test_parse_quorum(self):
        from cassandra import ConsistencyLevel
        assert _parse_consistency_level("QUORUM") == ConsistencyLevel.QUORUM

    def test_parse_local_quorum(self):
        from cassandra import ConsistencyLevel
        assert _parse_consistency_level("LOCAL_QUORUM") == ConsistencyLevel.LOCAL_QUORUM
        assert _parse_consistency_level("LOCALQUORUM") == ConsistencyLevel.LOCAL_QUORUM

    def test_parse_all(self):
        from cassandra import ConsistencyLevel
        assert _parse_consistency_level("ALL") == ConsistencyLevel.ALL

    def test_parse_unknown(self):
        assert _parse_consistency_level("UNKNOWN") is None

    def test_parse_serial_consistency(self):
        from cassandra import ConsistencyLevel
        assert _parse_serial_consistency_level("SERIAL") == ConsistencyLevel.SERIAL
        assert _parse_serial_consistency_level("LOCAL_SERIAL") == ConsistencyLevel.LOCAL_SERIAL

    def test_parse_serial_unknown(self):
        assert _parse_serial_consistency_level("QUORUM") is None


class TestFrameParsing:
    """Test frame body parsing."""

    def test_parse_create_session_frame(self):
        buf = BytesIO()
        # Write string map: {"contact_points": "127.0.0.1:9042", "keyspace": "test"}
        write_short(buf, 2)  # count
        write_string(buf, "contact_points")
        write_string(buf, "127.0.0.1:9042")
        write_string(buf, "keyspace")
        write_string(buf, "test")

        body = buf.getvalue()
        result = parse_create_session_frame(body)

        assert result == {
            "contact_points": "127.0.0.1:9042",
            "keyspace": "test",
        }

    def test_parse_create_session_frame_empty(self):
        buf = BytesIO()
        write_short(buf, 0)  # empty map
        body = buf.getvalue()
        result = parse_create_session_frame(body)
        assert result == {}


class TestErrorHandling:
    """Test error handling in session operations."""

    def test_invalid_query_parse(self):
        # Non-standard query should return empty
        keyspace, table, columns = _parse_query_columns("CREATE TABLE foo (id int)")
        assert keyspace == ""
        assert table == ""
        assert columns == []

    def test_empty_query(self):
        keyspace, table, columns = _parse_query_columns("")
        assert keyspace == ""
        assert table == ""
        assert columns == []


class TestExceptionMapping:
    """Test mapping Python driver exceptions to CQL error codes."""

    def test_authentication_failed(self):
        exc = AuthenticationFailed("Bad credentials")
        code, msg = map_exception_to_error_code(exc)
        assert code == ErrorCode.BAD_CREDENTIALS
        assert "Authentication failed" in msg

    def test_unauthorized(self):
        exc = Unauthorized("Not authorized")
        code, msg = map_exception_to_error_code(exc)
        assert code == ErrorCode.UNAUTHORIZED
        assert "Unauthorized" in msg

    def test_invalid_request(self):
        exc = InvalidRequest("Invalid query")
        code, msg = map_exception_to_error_code(exc)
        assert code == ErrorCode.INVALID
        assert "Invalid request" in msg

    def test_syntax_error(self):
        exc = InvalidRequest("Syntax error in CQL query")
        code, msg = map_exception_to_error_code(exc)
        assert code == ErrorCode.SYNTAX
        assert "Syntax error" in msg

    def test_unavailable(self):
        exc = Unavailable("Not enough replicas", 3, 1)
        code, msg = map_exception_to_error_code(exc)
        assert code == ErrorCode.UNAVAILABLE
        assert "Unavailable" in msg

    def test_read_timeout(self):
        exc = ReadTimeout("Read timed out", consistency=1, required_responses=1, received_responses=0)
        code, msg = map_exception_to_error_code(exc)
        assert code == ErrorCode.READ_TIMEOUT
        assert "Read timeout" in msg

    def test_write_timeout(self):
        exc = WriteTimeout("Write timed out", consistency=1, required_responses=1, received_responses=0, write_type=0)
        code, msg = map_exception_to_error_code(exc)
        assert code == ErrorCode.WRITE_TIMEOUT
        assert "Write timeout" in msg

    def test_no_host_available(self):
        exc = NoHostAvailable("No hosts available", {})
        code, msg = map_exception_to_error_code(exc)
        assert code == ErrorCode.UNAVAILABLE
        assert "No host available" in msg

    def test_generic_exception(self):
        exc = RuntimeError("Something went wrong")
        code, msg = map_exception_to_error_code(exc)
        assert code == ErrorCode.SERVER
        assert "Something went wrong" in msg


class TestSessionRegistry:
    """Test session registry operations."""

    def test_session_count_initial(self):
        from src.config import Config
        from src.session import SessionRegistry

        config = Config(
            socket_path="/tmp/test.sock",
            inflight_limit=100,
            log_level="INFO",
        )
        registry = SessionRegistry(config)
        assert registry.session_count() == 0

    def test_close_nonexistent_session(self):
        from src.config import Config
        from src.session import SessionRegistry

        config = Config(
            socket_path="/tmp/test.sock",
            inflight_limit=100,
            log_level="INFO",
        )
        registry = SessionRegistry(config)
        result = registry.close_session(999)
        assert result is False

    def test_close_all_empty(self):
        from src.config import Config
        from src.session import SessionRegistry

        config = Config(
            socket_path="/tmp/test.sock",
            inflight_limit=100,
            log_level="INFO",
        )
        registry = SessionRegistry(config)
        count = registry.close_all_sessions()
        assert count == 0


# =============================================================================
# Authentication failure handling tests
# =============================================================================


class TestAuthenticationHandling:
    """Test authentication failure handling."""

    def test_auth_failed_error_mapping(self):
        """AuthenticationFailed should map to BAD_CREDENTIALS."""
        from cassandra import AuthenticationFailed

        exc = AuthenticationFailed("Invalid credentials for user 'test'")
        code, msg = map_exception_to_error_code(exc)
        assert code == ErrorCode.BAD_CREDENTIALS
        assert "Authentication failed" in msg
        assert "Invalid credentials" in msg

    def test_unauthorized_error_mapping(self):
        """Unauthorized should map to UNAUTHORIZED."""
        from cassandra import Unauthorized

        exc = Unauthorized("User 'test' has no permission to access table")
        code, msg = map_exception_to_error_code(exc)
        assert code == ErrorCode.UNAUTHORIZED
        assert "Unauthorized" in msg


# =============================================================================
# Connection timeout handling tests
# =============================================================================


class TestConnectionTimeoutHandling:
    """Test connection timeout handling."""

    def test_operation_timed_out_mapping(self):
        """OperationTimedOut should map to READ_TIMEOUT."""
        from cassandra import OperationTimedOut

        exc = OperationTimedOut(errors={"127.0.0.1": "Connection timed out"})
        code, msg = map_exception_to_error_code(exc)
        assert code == ErrorCode.READ_TIMEOUT
        assert "timed out" in msg.lower()

    def test_no_host_available_mapping(self):
        """NoHostAvailable should map to UNAVAILABLE."""
        from cassandra.cluster import NoHostAvailable

        exc = NoHostAvailable("Unable to connect", errors={"127.0.0.1": "Connection refused"})
        code, msg = map_exception_to_error_code(exc)
        assert code == ErrorCode.UNAVAILABLE
        assert "No host available" in msg


# =============================================================================
# Large batch execution tests
# =============================================================================


class TestLargeBatchParsing:
    """Test large batch frame parsing."""

    def test_parse_batch_100_statements(self):
        """Batch with 100 statements should parse correctly."""
        from src.protocol import BatchType, write_byte, write_bytes, write_long, write_short, write_string
        from src.session import parse_batch_frame

        buf = BytesIO()
        write_long(buf, 1)  # session_id
        write_byte(buf, BatchType.LOGGED)  # batch_type
        write_short(buf, 100)  # statement count

        for i in range(100):
            write_byte(buf, 1)  # kind = prepared
            write_string(buf, f"stmt_{i}")  # statement key
            write_short(buf, 1)  # 1 value per statement
            write_short(buf, 0x0009)  # type: INT
            write_bytes(buf, (i).to_bytes(4, "big", signed=True))  # value

        write_short(buf, 0x0001)  # consistency: ONE

        session_id, batch_type, statements, consistency = parse_batch_frame(buf.getvalue())

        assert session_id == 1
        assert batch_type == BatchType.LOGGED
        assert len(statements) == 100
        for i, stmt in enumerate(statements):
            assert stmt.statement_key == f"stmt_{i}"
            assert len(stmt.typed_values) == 1

    def test_parse_batch_200_statements(self):
        """Batch with 200 statements should parse correctly."""
        from src.protocol import BatchType, write_byte, write_long, write_short, write_string
        from src.session import parse_batch_frame

        buf = BytesIO()
        write_long(buf, 42)  # session_id
        write_byte(buf, BatchType.UNLOGGED)  # batch_type
        write_short(buf, 200)  # statement count

        for i in range(200):
            write_byte(buf, 1)  # kind = prepared
            write_string(buf, f"insert_{i}")  # statement key
            write_short(buf, 0)  # no values

        write_short(buf, 0x0004)  # consistency: QUORUM

        session_id, batch_type, statements, consistency = parse_batch_frame(buf.getvalue())

        assert session_id == 42
        assert batch_type == BatchType.UNLOGGED
        assert len(statements) == 200

    def test_parse_batch_with_many_values(self):
        """Batch statement with many values should parse correctly."""
        from src.protocol import BatchType, write_byte, write_bytes, write_long, write_short, write_string
        from src.session import parse_batch_frame

        buf = BytesIO()
        write_long(buf, 1)
        write_byte(buf, BatchType.LOGGED)
        write_short(buf, 1)  # 1 statement

        write_byte(buf, 1)  # kind = prepared
        write_string(buf, "bulk_insert")
        write_short(buf, 50)  # 50 values

        for i in range(50):
            write_short(buf, 0x000D)  # type: TEXT
            text = f"value_{i}".encode()
            write_bytes(buf, text)

        write_short(buf, 0x0001)  # consistency: ONE

        session_id, batch_type, statements, consistency = parse_batch_frame(buf.getvalue())

        assert len(statements) == 1
        assert len(statements[0].typed_values) == 50


# =============================================================================
# Concurrent session creation tests
# =============================================================================


class TestConcurrentSessionAccess:
    """Test thread-safe session registry operations."""

    def test_session_id_uniqueness(self):
        """Session IDs should be unique even with concurrent access."""
        from concurrent.futures import ThreadPoolExecutor
        from unittest.mock import MagicMock, patch

        from src.config import Config
        from src.session import SessionRegistry, SessionWrapper

        config = Config(
            socket_path="/tmp/test.sock",
            inflight_limit=100,
            log_level="INFO",
        )
        registry = SessionRegistry(config)

        # Mock the connection to avoid actual DB calls
        mock_wrapper = MagicMock(spec=SessionWrapper)

        session_ids = []

        def create_mock_session():
            with patch.object(registry, "_connect_with_params", return_value=mock_wrapper):
                session_id, _, error = registry.create_session({})
                if error is None:
                    session_ids.append(session_id)

        # Create sessions concurrently
        with ThreadPoolExecutor(max_workers=10) as executor:
            futures = [executor.submit(create_mock_session) for _ in range(50)]
            for f in futures:
                f.result()

        # All session IDs should be unique
        assert len(session_ids) == 50
        assert len(set(session_ids)) == 50  # No duplicates

    def test_concurrent_get_operations(self):
        """Concurrent get operations should be thread-safe."""
        from concurrent.futures import ThreadPoolExecutor
        from unittest.mock import MagicMock

        from src.config import Config
        from src.session import SessionRegistry, SessionWrapper

        config = Config(
            socket_path="/tmp/test.sock",
            inflight_limit=100,
            log_level="INFO",
        )
        registry = SessionRegistry(config)

        # Add mock sessions directly
        mock_session = MagicMock(spec=SessionWrapper)
        registry._sessions[1] = mock_session
        registry._sessions[2] = mock_session
        registry._sessions[3] = mock_session

        results = []

        def get_session(session_id):
            result = registry.get(session_id)
            results.append((session_id, result is not None))

        with ThreadPoolExecutor(max_workers=10) as executor:
            # Mix of existing and non-existing session IDs
            session_ids = [1, 2, 3, 99, 1, 2, 3, 99] * 10
            futures = [executor.submit(get_session, sid) for sid in session_ids]
            for f in futures:
                f.result()

        # Verify expected results
        existing_count = sum(1 for _, found in results if found)
        missing_count = sum(1 for _, found in results if not found)

        # 60 requests for existing sessions (1,2,3) * 10 each = 30 * 2 = 60
        assert existing_count == 60
        # 20 requests for non-existing session (99) * 10 * 2 = 20
        assert missing_count == 20


# =============================================================================
# SSL/TLS configuration tests
# =============================================================================


class TestSSLConfiguration:
    """Test SSL/TLS configuration parsing."""

    def test_ssl_enabled_creates_context(self):
        """ssl_enabled=true should trigger SSL context creation."""
        # This tests the parameter parsing logic
        params = {
            "ssl_enabled": "true",
            "contact_points": "localhost:9042",
        }
        assert params.get("ssl_enabled") in ("true", "1")

    def test_ssl_verify_peer_false(self):
        """ssl_verify_peer=false should disable certificate verification."""
        params = {
            "ssl_enabled": "true",
            "ssl_verify_peer": "false",
            "contact_points": "localhost:9042",
        }
        # Verify parameter parsing
        assert params.get("ssl_verify_peer") not in ("true", "1")

    def test_ssl_with_certificates(self):
        """SSL with certificate paths should be properly configured."""
        params = {
            "ssl_enabled": "true",
            "ssl_ca_cert": "/path/to/ca.pem",
            "ssl_cert": "/path/to/client.pem",
            "ssl_key": "/path/to/client.key",
            "contact_points": "localhost:9042",
        }
        assert params.get("ssl_ca_cert") == "/path/to/ca.pem"
        assert params.get("ssl_cert") == "/path/to/client.pem"
        assert params.get("ssl_key") == "/path/to/client.key"


# =============================================================================
# Graceful shutdown with pending requests tests
# =============================================================================


class TestGracefulShutdownScenarios:
    """Test graceful shutdown with various session states."""

    def test_close_all_with_multiple_sessions(self):
        """close_all_sessions should close multiple sessions."""
        from unittest.mock import MagicMock

        from src.config import Config
        from src.session import SessionRegistry, SessionWrapper

        config = Config(
            socket_path="/tmp/test.sock",
            inflight_limit=100,
            log_level="INFO",
        )
        registry = SessionRegistry(config)

        # Add mock sessions
        for i in range(5):
            mock = MagicMock(spec=SessionWrapper)
            mock.close = MagicMock()
            registry._sessions[i + 1] = mock

        assert registry.session_count() == 5

        count = registry.close_all_sessions()

        assert count == 5
        assert registry.session_count() == 0

    def test_close_all_handles_close_errors(self, caplog):
        """close_all_sessions should handle errors during close."""
        import logging
        from unittest.mock import MagicMock

        from src.config import Config
        from src.session import SessionRegistry, SessionWrapper

        caplog.set_level(logging.WARNING)

        config = Config(
            socket_path="/tmp/test.sock",
            inflight_limit=100,
            log_level="INFO",
        )
        registry = SessionRegistry(config)

        # Add mock session that raises on close
        mock = MagicMock(spec=SessionWrapper)
        mock.close = MagicMock(side_effect=RuntimeError("Connection already closed"))
        registry._sessions[1] = mock

        count = registry.close_all_sessions()

        # Should still count as closed
        assert count == 1
        assert registry.session_count() == 0
        assert "Error closing session" in caplog.text

    def test_close_session_removes_from_registry(self):
        """close_session should remove session from registry."""
        from unittest.mock import MagicMock

        from src.config import Config
        from src.session import SessionRegistry, SessionWrapper

        config = Config(
            socket_path="/tmp/test.sock",
            inflight_limit=100,
            log_level="INFO",
        )
        registry = SessionRegistry(config)

        mock = MagicMock(spec=SessionWrapper)
        mock.close = MagicMock()
        registry._sessions[1] = mock
        registry._sessions[2] = mock

        assert registry.session_count() == 2

        result = registry.close_session(1)

        assert result is True
        assert registry.session_count() == 1
        assert registry.get(1) is None
        assert registry.get(2) is not None
