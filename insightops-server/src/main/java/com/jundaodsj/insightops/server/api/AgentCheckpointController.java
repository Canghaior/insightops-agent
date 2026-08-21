package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.agent.application.AgentCheckpointQuery;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import com.jundaodsj.insightops.server.chat.AgentCheckpointService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/runs")
public class AgentCheckpointController {

    private final AgentCheckpointService service;
    private final AgentCheckpointQuery query;

    public AgentCheckpointController(AgentCheckpointService service, AgentCheckpointQuery query) {
        this.service = service;
        this.query = query;
    }

    @GetMapping("/{runId}/checkpoint")
    public ApiResponse<AgentCheckpointQuery.CheckpointSummary> checkpoint(
            @PathVariable UUID runId, HttpServletRequest request) {
        return new ApiResponse<>((String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE),
                query.latest(CurrentAccount.actor(request), runId).orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                "Agent checkpoint not found")));
    }

    @PostMapping("/{runId}/pause")
    public ApiResponse<PauseResult> pause(@PathVariable UUID runId, HttpServletRequest request) {
        var actor = CurrentAccount.actor(request);
        if (!service.requestPause(actor.workspaceId(), actor.userId(), runId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only an active owned Agent run can be paused");
        }
        return new ApiResponse<>((String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE),
                new PauseResult(runId, "PAUSE_REQUESTED"));
    }

    public record PauseResult(UUID runId, String status) { }
}
