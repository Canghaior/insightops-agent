package com.jundaodsj.insightops.knowledge.application;

public class DocumentCollectionException extends RuntimeException {
    private final Code code;

    public DocumentCollectionException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public DocumentCollectionException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() { return code; }

    public enum Code {
        VALIDATION_ERROR,
        TIMEOUT,
        HTTP_ERROR,
        CONTENT_TOO_LARGE,
        UNSUPPORTED_CONTENT,
        TRANSIENT_REMOTE,
        INTERNAL_ERROR
    }
}
