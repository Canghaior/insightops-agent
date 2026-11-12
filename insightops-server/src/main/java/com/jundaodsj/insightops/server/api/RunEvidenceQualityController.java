package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.agent.application.AgentRunQuery;
import com.jundaodsj.insightops.agent.application.AgentWorkflowRunStore;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.report.application.ResearchEvidenceAssessment;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import com.jundaodsj.insightops.server.report.ResearchEvidenceQuality;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping("/api/v1/runs")
public class RunEvidenceQualityController {
    private final AgentRunQuery runs;
    private final AgentWorkflowRunStore workflows;
    private final ResearchEvidenceQuality quality;
    public RunEvidenceQualityController(AgentRunQuery runs, AgentWorkflowRunStore workflows, ResearchEvidenceQuality quality) {
        this.runs = runs; this.workflows = workflows; this.quality = quality;
    }
    @GetMapping("/{runId}/evidence-quality")
    public ApiResponse<ResearchEvidenceAssessment> detail(@PathVariable UUID runId, HttpServletRequest request) {
        return new ApiResponse<>(trace(request), assess(CurrentAccount.actor(request), runId));
    }
    @GetMapping("/evidence-quality")
    public ApiResponse<Map<UUID, ResearchEvidenceAssessment>> batch(@RequestParam List<UUID> runIds, HttpServletRequest request) {
        if (runIds.isEmpty() || runIds.size() > 50) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request 1 to 50 run IDs");
        var actor = CurrentAccount.actor(request);
        Map<UUID, ResearchEvidenceAssessment> result = new LinkedHashMap<>();
        for (UUID runId : new LinkedHashSet<>(runIds)) result.put(runId, assess(actor, runId));
        return new ApiResponse<>(trace(request), result);
    }
    private ResearchEvidenceAssessment assess(ActorContext actor, UUID runId) {
        var run = runs.findRun(actor, runId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent run not found"));
        var workflow = workflows.find(runId).filter(item -> actor.workspaceId().equals(item.workspaceId())
                && actor.userId().equals(item.ownerUserId())).orElse(null);
        return quality.assess(run, workflow);
    }
    private static String trace(HttpServletRequest request) {
        return (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
    }
}
