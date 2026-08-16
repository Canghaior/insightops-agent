package com.jundaodsj.insightops.server.api;

public record ApiResponse<T>(String traceId, T data) {
}
