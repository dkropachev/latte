# Known Issues

Known driver and adapter issues affecting workload compatibility.

See the [Adapter Compatibility Matrix](README.md#adapter-compatibility-matrix) for a quick overview.

## collections workload

The collections workload tests nested collection types (`frozen<list<frozen<list<int>>>>`, UDTs, tuples with embedded collections) which are challenging for many drivers.

### cpp-driver:collections

- **Error**: Adapter crashes on nested frozen collection types (UDTs, tuples, list of lists)
- **Root cause**: **Driver limitation.** The C++ DataStax driver has limited type introspection for complex nested types. The `cass_data_type_sub_data_type()` API cannot expose the type metadata needed to re-serialize frozen lists of lists, frozen maps of UDTs, etc.
- **Adapter fixable**: No. The adapter already does everything it can within the driver's constraints.

## vectors workload

The vectors workload tests `vector<float, N>` columns with various dimensions (3, 128, 512) and `frozen<list<vector<float, 3>>>`.

### cpp-rs-driver:vectors

- **Error**: `col_vector_small: expected vector, got None` (vector columns silently stored as NULL)
- **Root cause**: **Driver limitation.** The cpp-rs-driver (C wrapper around the Rust driver) maps `vector<float, N>` columns to `CASS_VALUE_TYPE_CUSTOM`. The `cass_statement_bind_custom()` API is unimplemented, and `cass_statement_bind_bytes()` fails type checking for CUSTOM columns (`is_type_compatible()` only allows Blob for BLOB/VARINT types). No C API path exists to bind data to vector columns.
- **Adapter fixable**: No. Requires `cass_statement_bind_custom()` to be implemented in the cpp-rs-driver. See [scylladb/cpp-rust-driver#415](https://github.com/scylladb/cpp-rust-driver/issues/415).

## Summary

| Adapter:Workload | Source | Fixable on adapter side |
|------------------|--------|:-----------------------:|
| cpp-driver:collections | Driver limitation | No |
| cpp-rs-driver:vectors | Driver limitation (no `cass_statement_bind_custom`). See [cpp-rust-driver#415](https://github.com/scylladb/cpp-rust-driver/issues/415) | No |
