package com.jundaodsj.insightops.report.application;

import java.util.List;

/** A deterministic completeness check, never a factual accuracy or compatibility certificate. */
public record ResearchEvidenceAssessment(
        String status, String label, String summary, List<Reason> reasons,
        boolean compatibilityVerified, String assessmentVersion) {
    public ResearchEvidenceAssessment {
        reasons = List.copyOf(reasons);
    }
    public record Reason(String code, String message) { }
}
