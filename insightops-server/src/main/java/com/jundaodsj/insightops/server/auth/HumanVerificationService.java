package com.jundaodsj.insightops.server.auth;

public interface HumanVerificationService {
    boolean ready();
    VerificationResult verify(String token, String remoteAddress);

    record VerificationResult(boolean valid, String failureCode) {
        public static VerificationResult accepted() { return new VerificationResult(true, null); }
        public static VerificationResult invalid(String code) { return new VerificationResult(false, code); }
    }
}
