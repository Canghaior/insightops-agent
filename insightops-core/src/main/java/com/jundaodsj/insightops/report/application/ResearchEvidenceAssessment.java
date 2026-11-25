package com.jundaodsj.insightops.report.application;

import java.util.List;

/** A deterministic completeness check, never a factual accuracy or compatibility certificate. */
public record ResearchEvidenceAssessment(
        String status, String label, String summary, List<Reason> reasons,
        boolean compatibilityVerified, String assessmentVersion, Coverage coverage) {
    public ResearchEvidenceAssessment {
        reasons = List.copyOf(reasons);
    }

    public record Coverage(Section engineering, Section external, Section validation) { }

    public record Section(String label, String summary, List<Reason> reasons, boolean missing) {
        public Section {
            reasons = List.copyOf(reasons);
        }
    }

    public record Reason(String code, String message) { }
}
