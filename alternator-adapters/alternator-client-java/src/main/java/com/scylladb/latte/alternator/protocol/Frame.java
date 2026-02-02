package com.scylladb.latte.alternator.protocol;

public record Frame(
    byte version,
    byte flags,
    short streamId,
    byte opcode,
    int bodyLength,
    byte[] body
) {
}
