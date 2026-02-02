package com.scylladb.latte.alternator.protocol;

public final class Opcodes {
    private Opcodes() {}

    // Protocol versions
    public static final byte VERSION_REQUEST = 0x01;
    public static final byte VERSION_RESPONSE = (byte) 0x81;

    // Header size in bytes
    public static final int HEADER_SIZE = 12;

    // Maximum body size (16MB)
    public static final int MAX_BODY_SIZE = 16 * 1024 * 1024;

    // Request opcodes
    public static final byte CREATE_SESSION = 0x01;
    public static final byte CLOSE_SESSION = 0x02;
    public static final byte GET_ITEM = 0x10;
    public static final byte PUT_ITEM = 0x11;
    public static final byte DELETE_ITEM = 0x12;
    public static final byte UPDATE_ITEM = 0x13;
    public static final byte QUERY = 0x14;
    public static final byte SCAN = 0x15;
    public static final byte BATCH_GET_ITEM = 0x20;
    public static final byte BATCH_WRITE_ITEM = 0x21;
    public static final byte TRANSACT_GET = 0x22;
    public static final byte TRANSACT_WRITE = 0x23;
    public static final byte CREATE_TABLE = 0x30;
    public static final byte DELETE_TABLE = 0x31;
    public static final byte DESCRIBE_TABLE = 0x32;
    public static final byte LIST_TABLES = 0x33;
    public static final byte SHUTDOWN = (byte) 0xFE;

    // Response opcodes
    public static final byte RESP_ERROR = 0x00;
    public static final byte RESP_SESSION_CREATED = 0x01;
    public static final byte RESP_SESSION_CLOSED = 0x02;
    public static final byte RESP_ITEM_RESULT = 0x10;
    public static final byte RESP_QUERY_RESULT = 0x14;
    public static final byte RESP_BATCH_RESULT = 0x20;
    public static final byte RESP_TRANSACT_RESULT = 0x22;
    public static final byte RESP_TABLE_RESULT = 0x30;
    public static final byte RESP_LIST_RESULT = 0x33;
    public static final byte RESP_SHUTDOWN_ACK = (byte) 0xFE;

    // Error codes
    public static final int ERROR_UNKNOWN = 0x0000;
    public static final int ERROR_PROTOCOL = 0x0001;
    public static final int ERROR_SESSION_NOT_FOUND = 0x0002;
    public static final int ERROR_CONNECTION = 0x0003;
    public static final int ERROR_TIMEOUT = 0x0004;
    public static final int ERROR_OVERLOADED = 0x0005;
    public static final int ERROR_RESOURCE_NOT_FOUND = 0x1001;
    public static final int ERROR_RESOURCE_IN_USE = 0x1002;
    public static final int ERROR_VALIDATION = 0x1003;
    public static final int ERROR_CONDITIONAL_CHECK_FAILED = 0x1004;
    public static final int ERROR_TRANSACTION_CANCELED = 0x1005;
    public static final int ERROR_PROVISIONED_THROUGHPUT = 0x1006;
    public static final int ERROR_ITEM_COLLECTION_SIZE = 0x1007;
    public static final int ERROR_LIMIT_EXCEEDED = 0x1008;
    public static final int ERROR_REQUEST_LIMIT_EXCEEDED = 0x1009;
    public static final int ERROR_INTERNAL_SERVER = 0x100A;
    public static final int ERROR_SERVICE_UNAVAILABLE = 0x100B;
}
