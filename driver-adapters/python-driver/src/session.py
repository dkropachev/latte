"""Database session management."""

import logging
import re
import ssl
import threading
import time
import traceback
from collections import namedtuple
from dataclasses import dataclass
from io import BytesIO

from cassandra import (
    AuthenticationFailed,
    ConsistencyLevel,
    InvalidRequest,
    OperationTimedOut,
    ReadFailure,
    ReadTimeout,
    Unauthorized,
    Unavailable,
    WriteFailure,
    WriteTimeout,
)
from cassandra.auth import PlainTextAuthProvider
from cassandra.cluster import Cluster, NoHostAvailable, Session
from cassandra.policies import (
    DCAwareRoundRobinPolicy,
    RoundRobinPolicy,
    TokenAwarePolicy,
)
from cassandra.cqltypes import TupleType as CassTupleType
from cassandra.query import BatchStatement as CassBatchStatement
from cassandra.query import BatchType as CassBatchType
from cassandra.query import SimpleStatement

from .config import Config
from .protocol import (
    BatchType,
    ColumnMeta,
    Consistency,
    ErrorCode,
    Frame,
    TypeCode,
    error_frame,
    prepared_result_frame,
    read_byte,
    read_bytes,
    read_long,
    read_long_string,
    read_short,
    read_string,
    read_string_map,
    rows_result_frame,
    void_result_frame,
)
from .values import decode_typed_value

logger = logging.getLogger(__name__)


def _is_null_value(value, col_type) -> bool:
    """Check if a column value represents NULL.

    The cassandra-driver's Row class may return non-None values for null columns
    in some cases, particularly for complex types like tuples. This function
    detects such cases.

    Args:
        value: The value retrieved from row[i]
        col_type: The column type from result.column_types[i]

    Returns:
        True if the value represents NULL, False otherwise
    """
    # Explicit None is always null
    if value is None:
        return True

    # For tuple types, check if the driver returned an empty representation
    # CQL tuples must have at least one element, so an empty tuple indicates null
    if isinstance(col_type, CassTupleType):
        # Check for empty tuple (driver may return () for null tuple columns)
        if isinstance(value, tuple) and len(value) == 0:
            return True
        # Check for tuple where all elements are None (another null representation)
        if isinstance(value, tuple) and all(v is None for v in value):
            return True

    return False


def map_exception_to_error_code(e: Exception) -> tuple[int, str]:
    """Map a Python Cassandra driver exception to a CQL protocol error code.

    The CQL binary protocol defines specific error codes that clients expect.
    This function translates Python driver exceptions to their corresponding
    protocol error codes for proper error reporting to Latte.

    Error code categories (from CQL protocol spec):
    - 0x0000-0x00FF: Server errors (generic, protocol, bad credentials)
    - 0x1000-0x1FFF: Coordination errors (unavailable, timeout, failure)
    - 0x2000-0x2FFF: Query errors (syntax, unauthorized, invalid, etc.)

    Args:
        e: Exception raised by the Python Cassandra driver

    Returns:
        Tuple of (error_code, error_message) for the ERROR response frame
    """
    if isinstance(e, AuthenticationFailed):
        return ErrorCode.BAD_CREDENTIALS, f"Authentication failed: {e}"
    elif isinstance(e, Unauthorized):
        return ErrorCode.UNAUTHORIZED, f"Unauthorized: {e}"
    elif isinstance(e, InvalidRequest):
        # Check for syntax errors vs other invalid requests
        msg = str(e).lower()
        if "syntax" in msg:
            return ErrorCode.SYNTAX, f"Syntax error: {e}"
        return ErrorCode.INVALID, f"Invalid request: {e}"
    elif isinstance(e, Unavailable):
        return ErrorCode.UNAVAILABLE, f"Unavailable: {e}"
    elif isinstance(e, ReadTimeout):
        return ErrorCode.READ_TIMEOUT, f"Read timeout: {e}"
    elif isinstance(e, WriteTimeout):
        return ErrorCode.WRITE_TIMEOUT, f"Write timeout: {e}"
    elif isinstance(e, ReadFailure):
        return ErrorCode.READ_FAILURE, f"Read failure: {e}"
    elif isinstance(e, WriteFailure):
        return ErrorCode.WRITE_FAILURE, f"Write failure: {e}"
    elif isinstance(e, OperationTimedOut):
        return ErrorCode.READ_TIMEOUT, f"Operation timed out: {e}"
    elif isinstance(e, NoHostAvailable):
        return ErrorCode.UNAVAILABLE, f"No host available: {e}"
    else:
        return ErrorCode.SERVER, str(e)


# Consistency level mapping - array indexed by Consistency enum value for O(1) lookup
# Index: 0=ANY, 1=ONE, 2=TWO, 3=THREE, 4=QUORUM, 5=ALL, 6=LOCAL_QUORUM, 7=EACH_QUORUM, 8-9=unused, 10=LOCAL_ONE
_CONSISTENCY_ARRAY = (0, 1, 2, 3, 4, 5, 6, 7, 1, 1, 10)  # indexes 8,9 default to ONE


def _map_consistency(consistency: Consistency) -> int:
    """Map Consistency enum to cassandra-driver consistency level using array indexing."""
    idx = int(consistency)
    if 0 <= idx < len(_CONSISTENCY_ARRAY):
        return _CONSISTENCY_ARRAY[idx]
    return 1  # Default to ONE


@dataclass(slots=True)
class TypedValue:
    """A value with its CQL type code."""

    type_code: int
    data: bytes | None


@dataclass(slots=True)
class CachedPrepared:
    """Cached prepared statement."""

    query: str
    prepared: object
    bind_types: list[str]
    tuple_element_counts: list[int]
    # Pre-computed set of column indices that need UDT conversion (optimization)
    udt_column_indices: set[int]


@dataclass(slots=True)
class BatchStatement:
    """A statement in a batch."""

    statement_key: str
    typed_values: list[TypedValue]


class SessionWrapper:
    """Wraps a Cassandra session with prepared statement caching."""

    def __init__(self, cluster: Cluster, session: Session):
        self._cluster = cluster
        self._session = session
        self._prepared_cache: dict[str, CachedPrepared] = {}
        self._registered_udts: set[tuple[str, str]] = set()  # (keyspace, udt_name)
        # Store UDT field order: (keyspace, udt_name) -> [field_name, ...]
        self._udt_field_orders: dict[tuple[str, str], list[str]] = {}
        # Store UDT namedtuple classes: (keyspace, udt_name) -> namedtuple class
        self._udt_classes: dict[tuple[str, str], type] = {}
        # Schema metadata cache: (keyspace, table) -> {column_name: type}
        # Avoids repeated system_schema.columns queries during PREPARE
        self._schema_cache: dict[tuple[str, str], dict[str, str]] = {}
        self._lock = threading.Lock()

    def close(self):
        """Close the session and cluster."""
        self._session.shutdown()
        self._cluster.shutdown()

    def execute_query(self, stream: int, query: str, consistency: Consistency) -> tuple[Frame, int]:
        """Execute an unprepared query.

        Returns:
            Tuple of (response frame, driver latency in nanoseconds).
            Latency measures only the driver execution time, excluding frame building.
        """
        try:
            stmt = SimpleStatement(query)
            stmt.consistency_level = _map_consistency(consistency)

            # Measure only the driver execution time
            start_time = time.perf_counter_ns()
            result = self._session.execute(stmt)
            latency_ns = time.perf_counter_ns() - start_time

            return self._build_rows_frame(stream, result), latency_ns
        except Exception as e:
            error_code, error_msg = map_exception_to_error_code(e)
            logger.warning(f"Query failed [{ErrorCode(error_code).name}]: {error_msg}")
            return error_frame(stream, error_code, error_msg), 0

    def prepare(self, stream: int, query: str, statement_key: str) -> Frame:
        """Prepare a statement and cache it."""
        try:
            # Convert named parameters to positional
            positional_query = _convert_named_to_positional(query)

            # Get bind types from schema
            bind_types, tuple_element_counts = self._get_bind_types_from_schema(query)

            # Register any UDTs found in bind types
            keyspace, _, _ = _parse_query_columns(query)
            keyspace = keyspace or self._session.keyspace
            logger.info(f"Prepare: keyspace={keyspace}, bind_types={bind_types[:5]}...")
            if keyspace:
                self._register_udts_for_types(keyspace, bind_types)

            # Pre-compute which columns need UDT conversion (optimization for execute)
            udt_column_indices = set()
            for i, bt in enumerate(bind_types):
                if bt and _extract_udt_names(bt):
                    udt_column_indices.add(i)

            # Expand tuples in the query
            positional_query = _expand_tuples_in_query(positional_query, bind_types)

            # Prepare the statement
            prepared = self._session.prepare(positional_query)

            # Cache it
            with self._lock:
                self._prepared_cache[statement_key] = CachedPrepared(
                    query=positional_query,
                    prepared=prepared,
                    bind_types=bind_types,
                    tuple_element_counts=tuple_element_counts,
                    udt_column_indices=udt_column_indices,
                )

            return prepared_result_frame(stream, statement_key)
        except Exception as e:
            error_code, error_msg = map_exception_to_error_code(e)
            logger.warning(f"Prepare failed for '{statement_key}' [{ErrorCode(error_code).name}]: {error_msg}")
            return error_frame(stream, error_code, error_msg)

    def _register_udts_for_types(self, keyspace: str, bind_types: list[str]) -> None:
        """Register UDTs found in bind types with the cluster."""
        for bt in bind_types:
            if not bt:
                continue
            # Find UDT type names (e.g., "frozen<address>", "map<text, frozen<person>>")
            udt_names = _extract_udt_names(bt)
            if udt_names:
                logger.info(f"Found UDT names in '{bt}': {udt_names}")
            for udt_name in udt_names:
                key = (keyspace, udt_name)
                if key in self._registered_udts:
                    continue

                # Query schema for UDT definition
                try:
                    udt_def = self._get_udt_definition(keyspace, udt_name)
                    logger.info(f"UDT definition for {keyspace}.{udt_name}: {udt_def}")
                    if udt_def:
                        # Create a namedtuple class and register it
                        field_names = list(udt_def.keys())
                        # Store field order for later use in decoding
                        self._udt_field_orders[key] = field_names
                        # Python namedtuple names must be valid identifiers
                        safe_name = udt_name.replace("-", "_")
                        udt_class = namedtuple(safe_name, field_names)
                        self._cluster.register_user_type(keyspace, udt_name, udt_class)
                        # Store the namedtuple class for value conversion
                        self._udt_classes[key] = udt_class
                        self._registered_udts.add(key)
                        logger.info(f"Registered UDT {keyspace}.{udt_name} with fields: {field_names}")
                    else:
                        logger.warning(f"No UDT definition found for {keyspace}.{udt_name}")
                except Exception as e:
                    logger.warning(f"Failed to register UDT {keyspace}.{udt_name}: {e}\n{traceback.format_exc()}")

    def _get_udt_definition(self, keyspace: str, udt_name: str) -> dict[str, str] | None:
        """Get UDT field names and types from schema."""
        try:
            query = """
                SELECT field_names, field_types
                FROM system_schema.types
                WHERE keyspace_name = %s AND type_name = %s
            """
            rows = list(self._session.execute(query, [keyspace, udt_name]))
            logger.debug(f"UDT query for {keyspace}.{udt_name} returned {len(rows)} rows: {rows}")
            if not rows:
                return None
            row = rows[0]
            # field_names and field_types are lists in the same order
            if hasattr(row, "field_names") and hasattr(row, "field_types"):
                return dict(zip(row.field_names, row.field_types))
            return None
        except Exception as e:
            logger.warning(f"Failed to get UDT definition for {keyspace}.{udt_name}: {e}\n{traceback.format_exc()}")
            return None

    def _convert_value_udts(self, value: object, target_type: str | None, keyspace: str) -> object:
        """Recursively convert UDT dicts to namedtuples in all values (including nested in collections)."""
        if value is None:
            return None

        if isinstance(value, dict):
            # Could be a UDT or a map
            if target_type:
                target_lower = target_type.lower().strip()
                # Check if it's a map type (not a UDT)
                is_map = target_lower.startswith("map<") or (
                    target_lower.startswith("frozen<") and "map<" in target_lower
                )
                if is_map:
                    # It's a map - recursively convert values that might be UDTs
                    return {k: self._convert_nested_udt(v, keyspace) if isinstance(v, dict) else v
                            for k, v in value.items()}

                # Check if it's a UDT (frozen<udt_name> where udt_name is not a collection)
                udt_names = _extract_udt_names(target_type)
                logger.debug(f"Converting value with target_type={target_type}, udt_names={udt_names}")
                if udt_names:
                    # It's a UDT - convert to registered namedtuple
                    result = self._convert_udt_to_namedtuple(value, target_type, keyspace)
                    logger.debug(f"Converted UDT: {type(value).__name__} -> {type(result).__name__}")
                    return result

            # It's a map with no specific target type - recursively convert values
            return {k: self._convert_nested_udt(v, keyspace) if isinstance(v, dict) else v
                    for k, v in value.items()}

        if isinstance(value, list):
            # Recursively convert list elements
            return [self._convert_nested_udt(item, keyspace) if isinstance(item, dict) else item
                    for item in value]

        if isinstance(value, set):
            # Recursively convert set elements
            return {self._convert_nested_udt(item, keyspace) if isinstance(item, dict) else item
                    for item in value}

        return value

    def _convert_udt_to_namedtuple(self, value: object, target_type: str, keyspace: str) -> object:
        """Convert a UDT dict to a registered namedtuple instance.

        The cassandra-driver expects UDT values to be instances of the registered
        namedtuple class, not plain tuples or dicts.
        """
        if value is None:
            return None

        if not isinstance(value, dict):
            return value

        # Extract UDT name from type string like "frozen<address>" or "address"
        udt_names = _extract_udt_names(target_type)
        if not udt_names:
            # Not a UDT type - could be a regular map
            return value

        udt_name = udt_names[0]
        key = (keyspace, udt_name)
        field_order = self._udt_field_orders.get(key)
        udt_class = self._udt_classes.get(key)

        if not field_order or not udt_class:
            logger.warning(f"No UDT class for {keyspace}.{udt_name}, returning dict as-is")
            return value

        # Convert dict to namedtuple, recursively converting nested UDTs
        result = []
        for field_name in field_order:
            field_value = value.get(field_name)
            # Recursively convert nested dicts (which might be nested UDTs)
            if isinstance(field_value, dict):
                # Look up the field type to determine if it's a nested UDT
                # For now, recursively convert all nested dicts
                field_value = self._convert_nested_udt(field_value, keyspace)
            elif isinstance(field_value, list):
                # Convert list elements that might be UDTs
                field_value = [
                    self._convert_nested_udt(item, keyspace) if isinstance(item, dict) else item
                    for item in field_value
                ]
            result.append(field_value)

        return udt_class(*result)

    def _convert_nested_udt(self, value: dict, keyspace: str) -> object:
        """Convert a nested UDT dict to namedtuple by trying known UDT classes."""
        if not isinstance(value, dict):
            return value

        # Try to find a matching UDT by checking field names
        value_fields = set(value.keys())
        for (ks, udt_name), field_order in self._udt_field_orders.items():
            if ks == keyspace and set(field_order) == value_fields:
                # Found a matching UDT
                udt_class = self._udt_classes.get((ks, udt_name))
                if not udt_class:
                    # Fall back to returning dict if no class registered
                    return value

                result = []
                for field_name in field_order:
                    field_value = value.get(field_name)
                    if isinstance(field_value, dict):
                        field_value = self._convert_nested_udt(field_value, keyspace)
                    elif isinstance(field_value, list):
                        field_value = [
                            self._convert_nested_udt(item, keyspace) if isinstance(item, dict) else item
                            for item in field_value
                        ]
                    result.append(field_value)
                return udt_class(*result)

        # No matching UDT found, return as-is
        return value

    def execute(
        self, stream: int, statement_key: str, consistency: Consistency, typed_values: list[TypedValue]
    ) -> tuple[Frame, int]:
        """Execute a prepared statement.

        Returns:
            Tuple of (response frame, driver latency in nanoseconds).
            Latency measures only the driver execution time, excluding value decoding and frame building.
        """
        # dict.get() is atomic in CPython, no lock needed for reads
        cached = self._prepared_cache.get(statement_key)
        if cached is None:
            return error_frame(stream, ErrorCode.UNPREPARED, f"Statement '{statement_key}' not prepared"), 0

        # Get keyspace from query or session (only if we have UDT columns)
        keyspace = None
        if cached.udt_column_indices:
            keyspace = self._session.keyspace
            if cached.query:
                ks, _, _ = _parse_query_columns(cached.query)
                keyspace = ks or keyspace

        try:
            # Decode values with type coercion
            values = []
            for i, tv in enumerate(typed_values):
                target_type = cached.bind_types[i] if i < len(cached.bind_types) else None
                decoded = decode_typed_value(tv.type_code, tv.data, target_type)
                # Only convert UDT dicts for columns that need it (pre-computed during PREPARE)
                if i in cached.udt_column_indices:
                    decoded = self._convert_value_udts(decoded, target_type, keyspace)
                values.append(decoded)

            # Expand tuples for the driver
            expanded_values = _expand_values_for_tuples(values, cached.bind_types, cached.tuple_element_counts)
            logger.debug(f"After expansion: {len(values)} -> {len(expanded_values)} values")

            # Execute
            bound = cached.prepared.bind(expanded_values)
            bound.consistency_level = _map_consistency(consistency)

            # Measure only the driver execution time
            start_time = time.perf_counter_ns()
            result = self._session.execute(bound)
            latency_ns = time.perf_counter_ns() - start_time

            return self._build_rows_frame(stream, result), latency_ns
        except Exception as e:
            error_code, error_msg = map_exception_to_error_code(e)
            logger.warning(f"Execute failed for '{statement_key}' [{ErrorCode(error_code).name}]: {error_msg}")
            # Log full exception and values for debugging
            logger.warning(f"Number of values: {len(values)}, expanded: {len(expanded_values)}, bind_types: {len(cached.bind_types)}")
            logger.warning(f"Bind types: {cached.bind_types}")
            logger.warning(f"Tuple element counts: {cached.tuple_element_counts}")
            # Log each value with its type
            for idx, (val, bt) in enumerate(zip(expanded_values, cached.bind_types + [None] * (len(expanded_values) - len(cached.bind_types)))):
                logger.warning(f"  [{idx}] bind_type={bt}, value_type={type(val).__name__}, value={repr(val)[:100]}")
            logger.warning(f"Full exception:\n{traceback.format_exc()}")
            return error_frame(stream, error_code, error_msg), 0

    def execute_batch(
        self,
        stream: int,
        batch_type: BatchType,
        statements: list[BatchStatement],
        consistency: Consistency,
    ) -> tuple[Frame, int]:
        """Execute a batch of statements.

        Returns:
            Tuple of (response frame, driver latency in nanoseconds).
            Latency measures only the driver execution time, excluding batch building and frame building.
        """
        try:
            # Map batch type
            cass_batch_type = {
                BatchType.LOGGED: CassBatchType.LOGGED,
                BatchType.UNLOGGED: CassBatchType.UNLOGGED,
                BatchType.COUNTER: CassBatchType.COUNTER,
            }.get(batch_type, CassBatchType.LOGGED)

            batch = CassBatchStatement(batch_type=cass_batch_type)
            batch.consistency_level = _map_consistency(consistency)

            for stmt in statements:
                # dict.get() is atomic in CPython, no lock needed for reads
                cached = self._prepared_cache.get(stmt.statement_key)
                if cached is None:
                    return error_frame(
                        stream, ErrorCode.UNPREPARED, f"Statement '{stmt.statement_key}' not prepared"
                    ), 0

                # Decode values
                values = []
                for i, tv in enumerate(stmt.typed_values):
                    target_type = cached.bind_types[i] if i < len(cached.bind_types) else None
                    values.append(decode_typed_value(tv.type_code, tv.data, target_type))

                # Expand tuples
                expanded_values = _expand_values_for_tuples(values, cached.bind_types, cached.tuple_element_counts)

                batch.add(cached.prepared, expanded_values)

            # Measure only the driver execution time
            start_time = time.perf_counter_ns()
            self._session.execute(batch)
            latency_ns = time.perf_counter_ns() - start_time

            return void_result_frame(stream), latency_ns
        except Exception as e:
            error_code, error_msg = map_exception_to_error_code(e)
            logger.warning(f"Batch failed [{ErrorCode(error_code).name}]: {error_msg}")
            return error_frame(stream, error_code, error_msg), 0

    def _build_rows_frame(self, stream: int, result) -> Frame:
        """Build a ROWS result frame from a query result."""
        if result is None or result.column_names is None:
            return void_result_frame(stream)

        # Try to extract keyspace/table from the query, but default to empty strings
        keyspace = ""
        table = ""
        try:
            if hasattr(result, "response_future") and result.response_future:
                query = getattr(result.response_future, "query", None)
                if query:
                    keyspace = getattr(query, "keyspace", None) or ""
                    table = getattr(query, "table", None) or ""
        except Exception:
            pass

        columns = []
        for i, name in enumerate(result.column_names):
            col_type = result.column_types[i] if result.column_types else None
            type_code = _cassandra_type_to_code(col_type)
            columns.append(
                ColumnMeta(
                    keyspace=keyspace,
                    table=table,
                    name=name,
                    type_code=type_code,
                )
            )

        rows = []
        for row in result:
            row_values = []
            for i in range(len(result.column_names)):
                # Must check if column is null first - accessing the value directly
                # may return empty collections/tuples instead of None for null columns
                # (similar to Java driver's row.isNull(i) check)
                try:
                    value = row[i]
                    col_type = result.column_types[i] if result.column_types else None
                    # Check if this value represents a null column
                    if _is_null_value(value, col_type):
                        value = None
                except (IndexError, TypeError):
                    value = None
                row_values.append(value)
            rows.append(row_values)

        return rows_result_frame(stream, columns, rows)

    def _get_bind_types_from_schema(self, query: str) -> tuple[list[str], list[int]]:
        """Extract bind variable types from schema with caching."""
        keyspace, table, columns = _parse_query_columns(query)
        if not keyspace or not table or not columns:
            # Try without keyspace
            if table and columns:
                keyspace = self._session.keyspace or ""
            else:
                return [], []

        try:
            # Check cache first
            cache_key = (keyspace, table)
            column_type_map = self._schema_cache.get(cache_key)

            if column_type_map is None:
                # Cache miss - query schema
                schema_query = """
                    SELECT column_name, type
                    FROM system_schema.columns
                    WHERE keyspace_name = %s AND table_name = %s
                """
                rows = self._session.execute(schema_query, [keyspace, table])
                column_type_map = {row.column_name: row.type for row in rows}
                # Cache the result
                self._schema_cache[cache_key] = column_type_map
                logger.debug(f"Cached schema for {keyspace}.{table}: {len(column_type_map)} columns")

            bind_types = []
            tuple_element_counts = []
            for col in columns:
                cql_type = column_type_map.get(col, "")
                bind_types.append(cql_type)
                tuple_element_counts.append(_count_tuple_elements(cql_type))

            return bind_types, tuple_element_counts
        except Exception as e:
            logger.debug(f"Failed to get bind types from schema: {e}")
            return [], []


class SessionRegistry:
    """Registry of database sessions."""

    def __init__(self, config: Config):
        self._config = config
        self._sessions: dict[int, SessionWrapper] = {}
        self._next_id = 1
        self._lock = threading.Lock()

    def create_session(self, params: dict[str, str]) -> tuple[int, SessionWrapper | None, str | None]:
        """Create a new session.

        Returns (session_id, session, error_message).
        """
        try:
            session = self._connect_with_params(params)
            with self._lock:
                session_id = self._next_id
                self._next_id += 1
                self._sessions[session_id] = session
            return session_id, session, None
        except Exception as e:
            return 0, None, str(e)

    def get(self, session_id: int) -> SessionWrapper | None:
        """Get a session by ID."""
        with self._lock:
            return self._sessions.get(session_id)

    def close_session(self, session_id: int) -> bool:
        """Close and remove a session by ID.

        Returns True if the session was found and closed, False otherwise.
        """
        with self._lock:
            session = self._sessions.pop(session_id, None)

        if session is not None:
            try:
                session.close()
                logger.info(f"Closed session {session_id}")
                return True
            except Exception as e:
                logger.warning(f"Error closing session {session_id}: {e}")
                return True  # Session was still removed
        return False

    def close_all_sessions(self) -> int:
        """Close and remove all sessions.

        Returns the number of sessions that were closed.
        """
        with self._lock:
            sessions = list(self._sessions.items())
            self._sessions.clear()

        count = 0
        for session_id, session in sessions:
            try:
                session.close()
                logger.info(f"Closed session {session_id}")
                count += 1
            except Exception as e:
                logger.warning(f"Error closing session {session_id}: {e}")
                count += 1  # Still count as closed

        return count

    def session_count(self) -> int:
        """Return the number of active sessions."""
        with self._lock:
            return len(self._sessions)

    def _connect_with_params(self, params: dict[str, str]) -> SessionWrapper:
        """Create a connection with parameters."""
        # Contact points
        contact_points_raw = params.get("contact_points") or ",".join(self._config.contact_points)
        contact_points = [p.strip() for p in contact_points_raw.split(",") if p.strip()]

        # Extract host and port
        hosts = []
        port = 9042
        for cp in contact_points:
            if ":" in cp:
                host, port_str = cp.rsplit(":", 1)
                hosts.append(host)
                port = int(port_str)
            else:
                hosts.append(cp)

        # Load balancing policy
        datacenter = params.get("datacenter")
        if datacenter:
            lb_policy = TokenAwarePolicy(DCAwareRoundRobinPolicy(local_dc=datacenter))
        else:
            lb_policy = TokenAwarePolicy(RoundRobinPolicy())

        # Authentication
        auth_provider = None
        username = params.get("username")
        password = params.get("password")
        if username and password:
            auth_provider = PlainTextAuthProvider(username=username, password=password)

        # SSL/TLS
        ssl_context = None
        if params.get("ssl_enabled") in ("true", "1"):
            ssl_context = ssl.create_default_context()
            if params.get("ssl_verify_peer") not in ("true", "1"):
                ssl_context.check_hostname = False
                ssl_context.verify_mode = ssl.CERT_NONE
            if params.get("ssl_ca_cert"):
                ssl_context.load_verify_locations(params["ssl_ca_cert"])
            if params.get("ssl_cert") and params.get("ssl_key"):
                ssl_context.load_cert_chain(params["ssl_cert"], params["ssl_key"])

        # Create cluster
        # Disable shard-aware routing (requires access to shard ports 19042+)
        # Use protocol_version=4 to avoid shard-aware negotiation issues
        cluster = Cluster(
            contact_points=hosts,
            port=port,
            load_balancing_policy=lb_policy,
            auth_provider=auth_provider,
            ssl_context=ssl_context,
            shard_aware_options=None,
            protocol_version=4,
        )

        # Set timeouts
        if params.get("connect_timeout_ms"):
            cluster.connect_timeout = int(params["connect_timeout_ms"]) / 1000.0
        if params.get("request_timeout_ms"):
            cluster.default_timeout = int(params["request_timeout_ms"]) / 1000.0

        # Connect
        keyspace = params.get("keyspace") or self._config.keyspace
        session = cluster.connect(keyspace) if keyspace else cluster.connect()

        # Set default consistency level
        consistency = params.get("consistency")
        if consistency:
            cl = _parse_consistency_level(consistency)
            if cl is not None:
                session.default_consistency_level = cl
            else:
                logger.warning(f"Unknown consistency level: {consistency}, using driver default")

        # Set serial consistency level for LWT
        serial_consistency = params.get("serial_consistency")
        if serial_consistency:
            scl = _parse_serial_consistency_level(serial_consistency)
            if scl is not None:
                session.default_serial_consistency_level = scl
            else:
                logger.warning(f"Unknown serial consistency level: {serial_consistency}, using driver default")

        # Set default page size
        default_page_size = params.get("default_page_size")
        if default_page_size:
            try:
                session.default_fetch_size = int(default_page_size)
            except ValueError:
                logger.warning(f"Invalid default_page_size: {default_page_size}, using driver default")

        # Note: rack and connections_per_shard are not directly supported by the Python driver
        rack = params.get("rack")
        if rack:
            if not datacenter:
                logger.warning("rack parameter requires datacenter to be specified, ignoring rack")
            else:
                logger.warning(f"Rack-aware routing not supported by Python driver, ignoring rack={rack}")

        connections_per_shard = params.get("connections_per_shard")
        if connections_per_shard:
            logger.warning("connections_per_shard not supported by Python driver, ignoring")

        logger.info(f"Connected to cluster: {hosts}, keyspace={keyspace}")

        return SessionWrapper(cluster, session)


# Frame parsing functions


def parse_create_session_frame(body: bytes) -> dict[str, str]:
    """Parse a CREATE_SESSION request frame body."""
    reader = BytesIO(body)
    return read_string_map(reader)


def parse_query_frame(body: bytes) -> tuple[int, str, Consistency]:
    """Parse a QUERY request frame body."""
    reader = BytesIO(body)

    session_id = read_long(reader)
    query = read_long_string(reader)
    consistency = Consistency(read_short(reader))
    _ = read_byte(reader)  # flags

    return session_id, query, consistency


def parse_prepare_frame(body: bytes) -> tuple[int, str, str]:
    """Parse a PREPARE request frame body."""
    reader = BytesIO(body)

    session_id = read_long(reader)
    query = read_long_string(reader)
    statement_key = read_string(reader)

    return session_id, query, statement_key


def parse_execute_frame(body: bytes) -> tuple[int, str, Consistency, list[TypedValue]]:
    """Parse an EXECUTE request frame body."""
    reader = BytesIO(body)

    session_id = read_long(reader)
    statement_key = read_string(reader)
    consistency = Consistency(read_short(reader))
    flags = read_byte(reader)

    typed_values = []
    if flags & 0x01:  # Values present
        count = read_short(reader)
        for _ in range(count):
            type_code = read_short(reader)
            data = read_bytes(reader)
            typed_values.append(TypedValue(type_code=type_code, data=data))

    return session_id, statement_key, consistency, typed_values


def parse_batch_frame(body: bytes) -> tuple[int, BatchType, list[BatchStatement], Consistency]:
    """Parse a BATCH request frame body."""
    reader = BytesIO(body)

    session_id = read_long(reader)
    batch_type = BatchType(read_byte(reader))
    stmt_count = read_short(reader)

    statements = []
    for _ in range(stmt_count):
        kind = read_byte(reader)
        if kind != 1:
            raise ValueError(f"Only prepared statements supported in batch, got kind={kind}")

        statement_key = read_string(reader)
        value_count = read_short(reader)

        typed_values = []
        for _ in range(value_count):
            type_code = read_short(reader)
            data = read_bytes(reader)
            typed_values.append(TypedValue(type_code=type_code, data=data))

        statements.append(BatchStatement(statement_key=statement_key, typed_values=typed_values))

    consistency = Consistency(read_short(reader))
    # Ignore remaining flags

    return session_id, batch_type, statements, consistency


# Helper functions


def _convert_named_to_positional(query: str) -> str:
    """Convert named parameters (:name) to positional (?) placeholders.

    Latte workloads use named parameters like :col_name for clarity, but the
    Python Cassandra driver works best with positional ? placeholders. This
    function performs the conversion while respecting string literals.

    Example:
        Input:  "INSERT INTO t (a, b) VALUES (:a, :b)"
        Output: "INSERT INTO t (a, b) VALUES (?, ?)"

    The conversion:
    1. Tracks whether we're inside a string literal (single quotes)
    2. Outside strings, replaces :identifier with ?
    3. Identifiers can contain alphanumeric chars and underscores
    4. Handles escaped quotes (\\') correctly

    Args:
        query: CQL query with named parameters

    Returns:
        Query with positional placeholders
    """
    result = []
    in_string = False
    i = 0

    while i < len(query):
        # Toggle string literal state on unescaped single quotes
        if query[i] == "'" and (i == 0 or query[i - 1] != "\\"):
            in_string = not in_string
            result.append(query[i])
            i += 1
            continue

        # Outside strings, look for :identifier pattern
        if not in_string and query[i] == ":" and i + 1 < len(query):
            next_char = query[i + 1]
            # Named parameter starts with letter or underscore
            if next_char.isalpha() or next_char == "_":
                result.append("?")
                i += 1
                # Skip the entire identifier
                while i < len(query) and (query[i].isalnum() or query[i] == "_"):
                    i += 1
                continue

        result.append(query[i])
        i += 1

    return "".join(result)


def _expand_tuples_in_query(query: str, bind_types: list[str]) -> str:
    """Expand single ? placeholders for tuple columns into (?, ?, ...).

    The Python Cassandra driver doesn't support binding a tuple as a single
    value - instead, each tuple element must be bound separately. This function
    rewrites the query to expand tuple placeholders.

    Example with a tuple<int, text, float> column:
        Input:  "INSERT INTO t (id, data) VALUES (?, ?)"
                where data is tuple<int, text, float>
        Output: "INSERT INTO t (id, data) VALUES (?, (?, ?, ?))"

    The expansion process:
    1. Look up column types from bind_types (from schema metadata)
    2. Identify which positions are tuple types (including frozen<tuple<...>>)
    3. Count elements in each tuple type
    4. Replace the single ? with (?, ?, ...) for tuple positions

    Args:
        query: CQL query with positional placeholders
        bind_types: List of CQL type strings for each bind position

    Returns:
        Query with tuple placeholders expanded
    """
    # Find which positions are tuples and how many elements they have
    tuple_positions = {}
    for i, bt in enumerate(bind_types):
        if bt:
            bt_lower = bt.lower().strip()
            # Handle frozen<tuple<...>> or tuple<...>
            if bt_lower.startswith("tuple<") or (bt_lower.startswith("frozen<") and "tuple<" in bt_lower):
                n_elements = _count_tuple_elements(bt)
                if n_elements > 0:
                    tuple_positions[i] = n_elements

    if not tuple_positions:
        return query

    # Replace ? placeholders for tuples with (?, ?, ...)
    result = []
    in_string = False
    placeholder_idx = 0

    for char in query:
        if char == "'" and (not result or result[-1] != "\\"):
            in_string = not in_string
            result.append(char)
            continue

        if not in_string and char == "?":
            if placeholder_idx in tuple_positions:
                n_elements = tuple_positions[placeholder_idx]
                result.append("(")
                result.append(", ".join(["?"] * n_elements))
                result.append(")")
            else:
                result.append(char)
            placeholder_idx += 1
            continue

        result.append(char)

    return "".join(result)


def _is_tuple_type(cql_type: str) -> bool:
    """Check if a CQL type string is a tuple type (including frozen<tuple<...>>)."""
    if not cql_type:
        return False
    cql_lower = cql_type.lower().strip()
    return cql_lower.startswith("tuple<") or (cql_lower.startswith("frozen<") and "tuple<" in cql_lower)


def _expand_values_for_tuples(
    values: list, bind_types: list[str], tuple_element_counts: list[int]
) -> list:
    """Expand tuple values into individual elements for binding.

    Companion to _expand_tuples_in_query - while that function expands the
    query's ? placeholders, this function expands the corresponding values.

    Example with tuple<int, text, float>:
        Input values:  [1, (10, "hello", 3.14)]
        Output values: [1, 10, "hello", 3.14]

    Handling edge cases:
    - Short tuples: Padded with None to match expected element count
    - NULL tuples: Expanded to N None values
    - Non-tuple values: Passed through unchanged

    Args:
        values: List of decoded values from the wire
        bind_types: List of CQL type strings for each bind position
        tuple_element_counts: Pre-computed element counts for each position

    Returns:
        Flat list of values with tuples expanded to individual elements
    """
    result = []

    for i, value in enumerate(values):
        is_tuple = i < len(bind_types) and _is_tuple_type(bind_types[i])
        n_elements = tuple_element_counts[i] if i < len(tuple_element_counts) else 0

        if is_tuple and n_elements > 0:
            if isinstance(value, (list, tuple)):
                # Expand tuple elements
                result.extend(value)
                # Pad with None if tuple has fewer elements than schema expects
                for _ in range(n_elements - len(value)):
                    result.append(None)
            else:
                # NULL tuple - expand to n_elements NULLs (one for each ?)
                result.extend([None] * n_elements)
        else:
            # Non-tuple value - pass through unchanged
            result.append(value)

    return result


def _count_tuple_elements(cql_type: str) -> int:
    """Count the number of elements in a CQL tuple type string.

    Parses type strings like:
    - "tuple<int, text, float>" -> 3
    - "tuple<int>" -> 1
    - "frozen<tuple<int, text>>" -> 2
    - "tuple<list<int>, map<text, int>>" -> 2 (nested generics handled)

    The algorithm counts top-level commas while tracking nesting depth:
    - '<' increases depth, '>' decreases depth
    - Commas only count at depth 0 (inside the main tuple, not nested types)

    Args:
        cql_type: CQL type string (e.g., "tuple<int, text>")

    Returns:
        Number of elements, or 0 if not a tuple type
    """
    cql_type = cql_type.lower().strip()

    # Unwrap frozen<...> wrapper if present
    if cql_type.startswith("frozen<"):
        inner = cql_type[7:-1]  # Strip "frozen<" and ">"
        return _count_tuple_elements(inner)

    if not cql_type.startswith("tuple<"):
        return 0

    # Extract content between tuple< and >
    inner = cql_type[6:-1]  # Strip "tuple<" and ">"
    if not inner:
        return 0

    # Count top-level commas (depth 0 means we're directly inside the tuple)
    count = 1  # At least one element if inner is non-empty
    depth = 0
    for c in inner:
        if c == "<":
            depth += 1  # Entering nested type
        elif c == ">":
            depth -= 1  # Exiting nested type
        elif c == "," and depth == 0:
            count += 1  # Another element at top level

    return count


def _extract_udt_names(cql_type: str) -> list[str]:
    """Extract UDT type names from a CQL type string.

    UDTs are identified as type names that:
    1. Appear after 'frozen<' without being a built-in type
    2. Are not standard collection or primitive types

    Examples:
        "frozen<address>" -> ["address"]
        "map<text, frozen<person>>" -> ["person"]
        "frozen<list<frozen<address>>>" -> ["address"]
        "frozen<person>" where person contains address -> ["person"]
    """
    known_types = {
        "text", "ascii", "varchar", "int", "bigint", "smallint", "tinyint",
        "float", "double", "boolean", "blob", "uuid", "timeuuid", "timestamp",
        "date", "time", "duration", "inet", "varint", "decimal", "counter",
        "list", "set", "map", "tuple", "frozen", "vector"
    }

    udt_names = []
    cql_lower = cql_type.lower()

    # Find all frozen<...> occurrences
    # We advance by 1 after each match to find nested frozen<> inside collections
    # e.g., "frozen<list<frozen<address>>>" should find "address"
    i = 0
    while i < len(cql_lower):
        frozen_pos = cql_lower.find("frozen<", i)
        if frozen_pos == -1:
            break

        # Extract the type name inside frozen<...>
        start = frozen_pos + 7  # len("frozen<")
        # Find matching >
        depth = 1
        end = start
        while end < len(cql_lower) and depth > 0:
            if cql_lower[end] == "<":
                depth += 1
            elif cql_lower[end] == ">":
                depth -= 1
            end += 1

        inner_type = cql_lower[start:end - 1].strip()

        # Check if inner type is a UDT (not a known type or collection)
        # UDT names start with a letter/underscore and don't contain < (unlike list<>, etc.)
        if inner_type and "<" not in inner_type.split()[0]:
            first_word = inner_type.split()[0] if inner_type else ""
            if first_word and first_word not in known_types:
                # This is likely a UDT
                udt_names.append(first_word)

        # Advance past "frozen<" to find nested frozen<> patterns
        # Don't advance to 'end' which would skip nested patterns
        i = frozen_pos + 7

    return udt_names


# Query parsing patterns
_INSERT_PATTERN = re.compile(
    r"INSERT\s+INTO\s+(?:(\w+)\.)?(\w+)\s*\(\s*([^)]+)\s*\)\s*VALUES",
    re.IGNORECASE,
)
_UPDATE_PATTERN = re.compile(
    r"UPDATE\s+(?:(\w+)\.)?(\w+)\s+SET\s+(.+?)\s+WHERE\s+(.+)",
    re.IGNORECASE | re.DOTALL,
)
_SELECT_PATTERN = re.compile(
    r"SELECT\s+.+?\s+FROM\s+(?:(\w+)\.)?(\w+)(?:\s+WHERE\s+(.+))?",
    re.IGNORECASE | re.DOTALL,
)
_DELETE_PATTERN = re.compile(
    r"DELETE\s+(?:.+?\s+)?FROM\s+(?:(\w+)\.)?(\w+)(?:\s+WHERE\s+(.+))?",
    re.IGNORECASE | re.DOTALL,
)
_AND_PATTERN = re.compile(r"\s+AND\s+", re.IGNORECASE)


def _parse_query_columns(query: str) -> tuple[str, str, list[str]]:
    """Parse query to extract keyspace, table, and bind columns."""
    query = query.strip()

    # Try INSERT
    match = _INSERT_PATTERN.search(query)
    if match:
        keyspace = match.group(1) or ""
        table = match.group(2)
        cols_str = match.group(3)
        columns = [c.strip() for c in cols_str.split(",") if c.strip()]
        return keyspace, table, columns

    # Try UPDATE
    match = _UPDATE_PATTERN.search(query)
    if match:
        keyspace = match.group(1) or ""
        table = match.group(2)
        set_clause = match.group(3)
        where_clause = match.group(4)
        columns = _parse_set_columns(set_clause) + _parse_where_columns(where_clause)
        return keyspace, table, columns

    # Try SELECT
    match = _SELECT_PATTERN.search(query)
    if match:
        keyspace = match.group(1) or ""
        table = match.group(2)
        where_clause = match.group(3) or ""
        columns = _parse_where_columns(where_clause)
        return keyspace, table, columns

    # Try DELETE
    match = _DELETE_PATTERN.search(query)
    if match:
        keyspace = match.group(1) or ""
        table = match.group(2)
        where_clause = match.group(3) or ""
        columns = _parse_where_columns(where_clause)
        return keyspace, table, columns

    return "", "", []


def _parse_set_columns(set_clause: str) -> list[str]:
    """Parse SET clause columns."""
    columns = []
    for part in set_clause.split(","):
        if "=" in part:
            col = part.split("=")[0].strip()
            if col:
                columns.append(col)
    return columns


def _parse_where_columns(where_clause: str) -> list[str]:
    """Parse WHERE clause columns."""
    columns = []
    parts = _AND_PATTERN.split(where_clause)
    for part in parts:
        part = part.strip()
        if "=" in part:
            col = part.split("=")[0].strip()
            if col:
                columns.append(col)
        elif " IN " in part.upper():
            idx = part.upper().find(" IN ")
            col = part[:idx].strip()
            if col:
                columns.append(col)
    return columns


def _parse_consistency_level(s: str) -> int | None:
    """Parse a consistency level string.

    Returns None for unrecognized values (forward compatibility).
    """
    s_upper = s.upper().replace("_", "")
    mapping = {
        "ANY": ConsistencyLevel.ANY,
        "ONE": ConsistencyLevel.ONE,
        "1": ConsistencyLevel.ONE,
        "TWO": ConsistencyLevel.TWO,
        "2": ConsistencyLevel.TWO,
        "THREE": ConsistencyLevel.THREE,
        "3": ConsistencyLevel.THREE,
        "QUORUM": ConsistencyLevel.QUORUM,
        "ALL": ConsistencyLevel.ALL,
        "LOCALONE": ConsistencyLevel.LOCAL_ONE,
        "LOCALQUORUM": ConsistencyLevel.LOCAL_QUORUM,
        "EACHQUORUM": ConsistencyLevel.EACH_QUORUM,
        "SERIAL": ConsistencyLevel.SERIAL,
        "LOCALSERIAL": ConsistencyLevel.LOCAL_SERIAL,
    }
    return mapping.get(s_upper)


def _parse_serial_consistency_level(s: str) -> int | None:
    """Parse a serial consistency level string.

    Returns None for unrecognized values (forward compatibility).
    """
    s_upper = s.upper().replace("_", "")
    mapping = {
        "SERIAL": ConsistencyLevel.SERIAL,
        "LOCALSERIAL": ConsistencyLevel.LOCAL_SERIAL,
    }
    return mapping.get(s_upper)


def _cassandra_type_to_code(col_type) -> int:
    """Convert Cassandra column type to type code."""
    if col_type is None:
        return TypeCode.BLOB

    # Get the typename from the cassandra type class
    # The Python driver returns type classes like cassandra.cqltypes.LongType
    # which have a 'typename' attribute like 'bigint'
    type_name = col_type.typename.lower() if hasattr(col_type, "typename") else str(col_type).lower()

    if "int" in type_name and "bigint" not in type_name and "varint" not in type_name:
        if "smallint" in type_name:
            return TypeCode.SMALLINT
        if "tinyint" in type_name:
            return TypeCode.TINYINT
        return TypeCode.INT
    if "bigint" in type_name:
        return TypeCode.BIGINT
    if "text" in type_name or "varchar" in type_name:
        return TypeCode.TEXT
    if "ascii" in type_name:
        return TypeCode.ASCII
    if "boolean" in type_name:
        return TypeCode.BOOLEAN
    if "float" in type_name:
        return TypeCode.FLOAT
    if "double" in type_name:
        return TypeCode.DOUBLE
    if "timestamp" in type_name:
        return TypeCode.TIMESTAMP
    if "timeuuid" in type_name:
        return TypeCode.TIMEUUID
    if "uuid" in type_name:
        return TypeCode.UUID
    if "blob" in type_name:
        return TypeCode.BLOB
    if "inet" in type_name:
        return TypeCode.INET
    if "date" in type_name:
        return TypeCode.DATE
    if "time" in type_name and "timestamp" not in type_name and "timeuuid" not in type_name:
        return TypeCode.TIME
    if "varint" in type_name:
        return TypeCode.VARINT
    if "decimal" in type_name:
        return TypeCode.DECIMAL
    if "counter" in type_name:
        return TypeCode.COUNTER
    if "duration" in type_name:
        return TypeCode.DURATION
    if "list" in type_name:
        return TypeCode.LIST
    if "set" in type_name:
        return TypeCode.SET
    if "map" in type_name:
        return TypeCode.MAP
    if "vector" in type_name:
        return TypeCode.VECTOR
    if "tuple" in type_name:
        return TypeCode.TUPLE

    return TypeCode.BLOB
