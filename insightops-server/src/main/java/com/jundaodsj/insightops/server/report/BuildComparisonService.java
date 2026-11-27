package com.jundaodsj.insightops.server.report;

import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.report.application.BuildEvidenceComparison;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class BuildComparisonService {
    private final BuildEvidenceService builds;

    public BuildComparisonService(BuildEvidenceService builds) {
        this.builds = builds;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BuildEvidenceComparison compare(ActorContext actor, UUID reportId, UUID baselineId, UUID candidateId) {
        if (baselineId == null || candidateId == null || baselineId.equals(candidateId)) {
            throw new IllegalArgumentException("请选择两份不同的构建批次。");
        }
        var baseline = builds.require(actor, reportId, baselineId);
        var candidate = builds.require(actor, reportId, candidateId);
        return BuildEvidenceComparison.compare(baseline, candidate);
    }
}
