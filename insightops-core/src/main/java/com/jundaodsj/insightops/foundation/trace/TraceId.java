package com.jundaodsj.insightops.foundation.trace;

import java.util.Objects;
import java.util.UUID;

public record TraceId(String value) {

    public TraceId {
        Objects.requireNonNull(value, "traceId 不能为空");
        if (value.isBlank() || value.length() > 64) {
            throw new IllegalArgumentException("traceId 长度必须在 1 到 64 之间");
        }
    }

    public static TraceId create() {
        return new TraceId(UUID.randomUUID().toString());
    }
}
