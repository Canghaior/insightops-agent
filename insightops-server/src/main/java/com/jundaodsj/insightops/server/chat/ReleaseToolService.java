package com.jundaodsj.insightops.server.chat;

import com.jundaodsj.insightops.server.tool.RegisteredToolExecutionService;
import com.jundaodsj.insightops.tool.application.registry.AgentToolNames;
import com.jundaodsj.insightops.tool.application.github.GitHubReleaseGateway;
import com.jundaodsj.insightops.tool.application.github.GitHubReleaseQuery;
import com.jundaodsj.insightops.tool.application.github.GitHubReleaseResult;
import com.jundaodsj.insightops.tool.application.github.GitHubToolErrorCode;
import com.jundaodsj.insightops.tool.application.github.GitHubToolException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ReleaseToolService {

    public static final String TOOL_NAME = AgentToolNames.GITHUB_RELEASE_LIST;

    private final ReleaseQuestionRouter router;
    private final GitHubReleaseGateway gateway;
    private final RegisteredToolExecutionService toolExecution;
    private final GitHubReleaseEvidenceFormatter evidenceFormatter;

    public ReleaseToolService(
            ReleaseQuestionRouter router,
            GitHubReleaseGateway gateway,
            RegisteredToolExecutionService toolExecution,
            GitHubReleaseEvidenceFormatter evidenceFormatter) {
        this.router = router;
        this.gateway = gateway;
        this.toolExecution = toolExecution;
        this.evidenceFormatter = evidenceFormatter;
    }

    public Optional<ToolEvidence> execute(
            UUID runId,
            String question,
            ToolProgressListener listener) {
        return execute(runId, question, "", listener);
    }

    public Optional<ToolEvidence> execute(
            UUID workspaceId,
            UUID runId,
            String question,
            String previousUserQuestions,
            ToolProgressListener listener) {
        Optional<ReleaseQuestionRouter.ResolvedReleaseQuery> routed =
                router.routeWithProjectContext(workspaceId, question,
                        previousUserQuestions == null ? "" : previousUserQuestions);
        if (routed.isEmpty()) return Optional.empty();
        var resolved = routed.orElseThrow();
        return executeResolved(runId, resolved, listener);
    }

    private Optional<ToolEvidence> executeResolved(
            UUID runId, ReleaseQuestionRouter.ResolvedReleaseQuery resolved,
            ToolProgressListener listener) {
        GitHubReleaseQuery query = resolved.evidenceQuery();
        RegisteredToolExecutionService.Session session = start(runId, 1, query);
        Instant startedAt = Instant.now();
        listener.onStarted(session.toolCallId(), session.toolName());
        try {
            java.util.ArrayList<com.jundaodsj.insightops.tool.application.github.GitHubRelease> releases =
                    new java.util.ArrayList<>();
            Instant fetchedAt = startedAt;
            boolean truncated = false;
            for (var repository : resolved.repositories()) {
                GitHubReleaseResult current = gateway.listRepositoryReleases(repository);
                releases.addAll(current.releases());
                if (current.fetchedAt().isAfter(fetchedAt)) fetchedAt = current.fetchedAt();
                truncated = truncated || current.truncated();
            }
            GitHubReleaseResult result = new GitHubReleaseResult(releases, fetchedAt, truncated);
            session.succeed(resultPayload(result));
            listener.onCompleted(session.toolCallId(), session.toolName(), result.releases().size());
            return Optional.of(new ToolEvidence(evidenceFormatter.format(query, result),
                    result.sourceUrls(), session.toolCallId(), result.releases().size()));
        } catch (GitHubToolException exception) {
            session.failIfRunning(exception.code().name());
            throw exception;
        } catch (RuntimeException exception) {
            session.failIfRunning(GitHubToolErrorCode.INTERNAL_ERROR.name());
            throw new GitHubToolException(GitHubToolErrorCode.INTERNAL_ERROR, exception);
        }
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
        RegisteredToolExecutionService.Session session = start(runId, 1, query);
        listener.onStarted(session.toolCallId(), session.toolName());

        try {
            GitHubReleaseResult result = gateway.listReleases(query);
            session.succeed(resultPayload(result));
            listener.onCompleted(
                    session.toolCallId(),
                    session.toolName(),
                    result.releases().size());
            return Optional.of(new ToolEvidence(
                    evidenceFormatter.format(query, result),
                    result.sourceUrls(),
                    session.toolCallId(),
                    result.releases().size()));
        }
        catch (GitHubToolException exception) {
            session.failIfRunning(exception.code().name());
            throw exception;
        }
        catch (RuntimeException exception) {
            session.failIfRunning(GitHubToolErrorCode.INTERNAL_ERROR.name());
            throw new GitHubToolException(GitHubToolErrorCode.INTERNAL_ERROR, exception);
        }
    }

    private static Map<String, Object> resultPayload(GitHubReleaseResult result) {
        return Map.of(
                "releases", result.releases(),
                "fetchedAt", result.fetchedAt().toString(),
                "truncated", result.truncated());
    }

    private RegisteredToolExecutionService.Session start(
            UUID runId, int stepNo, GitHubReleaseQuery query) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("projectIds", query.projectIds());
        if (query.timeWindowDays() != null) {
            input.put("timeWindowDays", query.timeWindowDays());
        }
        input.put("maxReleasesPerProject", query.maxReleasesPerProject());
        input.put("includePrereleases", query.includePrereleases());
        return toolExecution.start(runId, stepNo, 1, 1, TOOL_NAME, input);
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
