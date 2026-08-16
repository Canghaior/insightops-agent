package com.jundaodsj.insightops.model.application;

public final class ModelCallException extends RuntimeException {

    private final ModelCallErrorCode code;
    private final String provider;

    public ModelCallException(ModelCallErrorCode code, String provider, Throwable cause) {
        super("Model call failed: " + code, cause);
        this.code = code;
        this.provider = provider;
    }

    public ModelCallErrorCode code() {
        return code;
    }

    public String provider() {
        return provider;
    }
}
