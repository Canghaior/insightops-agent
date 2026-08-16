package com.jundaodsj.insightops.server.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.tool.application.AgentToolExecutionStore;
import com.jundaodsj.insightops.tool.application.github.GitHubReleaseGateway;
import com.jundaodsj.insightops.tool.application.github.GitHubReleaseQuery;
import com.jundaodsj.insightops.tool.application.github.GitHubReleaseResult;
import com.jundaodsj.insightops.tool.application.github.GitHubToolErrorCode;
import com.jundaodsj.insightops.tool.application.github.GitHubToolException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ReleaseToolService {

    public static final String TOOL_NAME = "github_release_list";

    private final ReleaseQuestionRouter router;
    private final GitHubReleaseGateway gateway;
    private final AgentToolExecutionStore executionStore;
    private final GitHubReleaseEvidenceFormatter evidenceFormatter;
    private final ObjectMapper objectMapper;

    public ReleaseToolService(
            ReleaseQuestionRouter router,
            GitHubReleaseGateway gateway,
            AgentToolExecutionStore executionStore,
            GitHubReleaseEvidenceFormatter evidenceFormatter,
            ObjectMapper objectMapper) {
        this.router = router;
        this.gateway = gateway;
        this.executionStore = executionStore;
        this.evidenceFormatter = evidenceFormatter;
        this.objectMapper = objectMapper;
    }

    public Optional<ToolEvidence> execute(
            UUID runId,
            String question,
            ToolProgressListener listener) {
        return execute(runId, question, "", listener);
    }

    public Optional<ToolEvidence> execute(
            UUID runId,
            String question,
            String previousUserQuestions,
            ToolProgressListener listener) {
        Optional<GitHubReleaseQuery> routed = router.routeWithProjectContext(
                question,
                previousUserQuestions == null ? "" : previousUserQuestions);
        if (routed.isEmpty()) {
            return Optional.empty();
        }

        GitHubReleaseQuery query = routed.get();
        UUID stepId = UUID.randomUUID();
        UUID toolCallId = UUID.randomUUID();
        Instant startedAt = Instant.now();
        String requestPayload = json(query);
        executionStore.startTool(
                runId,
                stepId,
                toolCallId,
                1,
                TOOL_NAME,
                runId + ":" + TOOL_NAME + ":1",
                requestPayload,
                startedAt);
        listener.onStarted(toolCallId, TOOL_NAME);

        try {
            GitHubReleaseResult result = gateway.listReleases(query);
            long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
            executionStore.succeedTool(
                    runId,
                    stepId,
                    toolCallId,
                    json(result),
                    durationMs,
                    Instant.now());
            listener.onCompleted(toolCallId, TOOL_NAME, result.releases().size());
            return Optional.of(new ToolEvidence(
                    evidenceFormatter.format(query, result),
                    result.sourceUrls(),
                    toolCallId,
                    result.releases().size()));
        }
        catch (GitHubToolException exception) {
            fail(stepId, toolCallId, exception.code().name(), startedAt);
            throw exception;
        }
        catch (RuntimeException exception) {
            fail(stepId, toolCallId, GitHubToolErrorCode.INTERNAL_ERROR.name(), startedAt);
            throw new GitHubToolException(GitHubToolErrorCode.INTERNAL_ERROR, exception);
        }
    }

    private void fail(UUID stepId, UUID toolCallId, String errorCode, Instant startedAt) {
        executionStore.failTool(
                stepId,
                toolCallId,
                errorCode,
                Duration.between(startedAt, Instant.now()).toMillis(),
                Instant.now());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException exception) {
            throw new GitHubToolException(GitHubToolErrorCode.INTERNAL_ERROR, exception);
        }
    }

    public record ToolEvidence(
            String systemPromptAppendix,
            List<String> sourceUrls,
            UUID toolCallId,
            int releaseCount) {
    }

    public interface ToolProgressListener {

        void onStarted(UUID toolCallId, String toolName);

        void onCompleted(UUID toolCallId, String toolName, int releaseCount);
    }
}
