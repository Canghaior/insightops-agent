package com.jundaodsj.insightops.report.application;

public interface ReportDeliveryGateway {

    DeliveryResult deliver(ReportDeliveryStore.DeliveryTask task);

    record DeliveryResult(int responseCode, long durationMs) { }

    final class DeliveryException extends RuntimeException {
        private final String code;
        private final Integer responseCode;
        private final long durationMs;
        private final boolean retryable;

        public DeliveryException(String code, String message, Integer responseCode,
                                 long durationMs, boolean retryable, Throwable cause) {
            super(message, cause);
            this.code = code;
            this.responseCode = responseCode;
            this.durationMs = durationMs;
            this.retryable = retryable;
        }

        public String code() { return code; }
        public Integer responseCode() { return responseCode; }
        public long durationMs() { return durationMs; }
        public boolean retryable() { return retryable; }
    }
}
