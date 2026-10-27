package com.jundaodsj.insightops.server.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.project.application.AdminProjectStore;
import com.jundaodsj.insightops.project.application.ProjectUpdateStore;
import com.jundaodsj.insightops.server.knowledge.KnowledgeAnswerabilityPolicy;
import com.jundaodsj.insightops.server.knowledge.KnowledgeSearchService;
import com.jundaodsj.insightops.server.tool.AgentToolApprovalService;
import com.jundaodsj.insightops.server.tool.AgentToolCircuitBreakerRegistry;
import com.jundaodsj.insightops.server.tool.AgentToolOperationalMetrics;
import com.jundaodsj.insightops.server.tool.AgentToolRegistryConfiguration;
import com.jundaodsj.insightops.server.tool.AgentToolResilienceProperties;
import com.jundaodsj.insightops.server.tool.GitHubIssueMcpService;
import com.jundaodsj.insightops.server.tool.McpReadToolService;
import com.jundaodsj.insightops.server.tool.RegisteredToolExecutionService;
import com.jundaodsj.insightops.tool.application.AgentToolExecutionStore;
import com.jundaodsj.insightops.tool.application.github.GitHubRelease;
import com.jundaodsj.insightops.tool.application.github.GitHubReleaseGateway;
import com.jundaodsj.insightops.tool.application.github.GitHubReleaseResult;
import com.jundaodsj.insightops.tool.application.registry.AgentToolDefinition;
import com.jundaodsj.insightops.tool.application.registry.AgentToolNames;
import com.jundaodsj.insightops.tool.application.registry.AgentToolRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentToolDispatcherIssueTest {

    private static final String ISSUE_URL = "https://github.com/spring-projects/spring-ai/issues/42";
    private static final Instant NOW = Instant.parse("2026-10-26T00:00:00Z");

    private final UUID workspaceId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final AgentToolExecutionStore executionStore = mock(AgentToolExecutionStore.class);
    private final AdminProjectStore projects = mock(AdminProjectStore.class);
    private final GitHubIssueMcpService issueService = mock(GitHubIssueMcpService.class);
    private final GitHubReleaseGateway releases = mock(GitHubReleaseGateway.class);
    private final AgentToolDispatcher.ProgressListener listener =
            mock(AgentToolDispatcher.ProgressListener.class);

    private ResilientAgentToolExecutor resilientExecutor;
    private SimpleMeterRegistry meters;
    private AgentToolDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        AgentToolRegistry registry = new AgentToolRegistry(
                AgentToolRegistryConfiguration.definitions(true, true));
        RegisteredToolExecutionService execution = new RegisteredToolExecutionService(
                registry, executionStore, new ObjectMapper().findAndRegisterModules());
        AgentToolResilienceProperties properties = new AgentToolResilienceProperties();
        properties.setMaxAttempts(1);
        meters = new SimpleMeterRegistry();
        AgentToolOperationalMetrics metrics = new AgentToolOperationalMetrics(meters);
        resilientExecutor = new ResilientAgentToolExecutor(properties,
                new AgentToolCircuitBreakerRegistry(properties, metrics), metrics);
        dispatcher = new AgentToolDispatcher(registry, execution,
                mock(AgentToolApprovalService.class), mock(McpReadToolService.class),
                issueService, projects, resilientExecutor, metrics, releases,
                new GitHubReleaseEvidenceFormatter(), mock(KnowledgeSearchService.class),
                mock(KnowledgeAnswerabilityPolicy.class), new KnowledgeRagProperties(),
                mock(ProjectUpdateStore.class));
    }

    @AfterEach
    void close() {
        resilientExecutor.close();
        meters.close();
    }

    @Test
    void readsEnabledWorkspaceProjectAndPersistsIssueEvidenceAndCitation() throws Exception {
        when(projects.list(workspaceId)).thenReturn(List.of(project(true)));
        when(issueService.readIssue("spring-projects", "spring-ai", 42))
                .thenReturn(new GitHubIssueMcpService.Issue(42, "Issue title", ISSUE_URL,
                        "open", "Issue body", "reporter", NOW.toString(), NOW.toString(),
                        false, NOW));
        AgentToolDispatcher.ExecutionContext context = context();

        AgentToolDispatcher.ExecutionResult result = dispatcher.execute(context,
                AgentToolNames.GITHUB_ISSUE_READ,
                Map.of("projectId", "spring-projects/spring-ai", "issueNumber", 42), listener);

        verify(projects).list(workspaceId);
        verify(issueService).readIssue("spring-projects", "spring-ai", 42);
        verifyNoInteractions(releases);
        assertThat(result.resultCount()).isEqualTo(1);
        assertThat(result.resultModel()).isEqualTo("github-mcp");
        assertThat(result.sourceUrls()).containsExactly(ISSUE_URL);
        assertThat(result.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.label()).isEqualTo("I1");
            assertThat(citation.sourceType()).isEqualTo("GITHUB_ISSUE");
            assertThat(citation.url()).isEqualTo(ISSUE_URL);
        });
        assertThat(result.systemPromptAppendix()).contains("[I1]", "Issue body", ISSUE_URL);
        assertThat(result.observation()).containsEntry("provider", "github-official-mcp");
        ArgumentCaptor<String> auditPayload = ArgumentCaptor.forClass(String.class);
        verify(executionStore).succeedTool(eq(context.runId()), any(), eq(result.toolCallId()),
                auditPayload.capture(), anyLong(), any());
        assertThat(new ObjectMapper().readTree(auditPayload.getValue()).path("issue").path("number").asInt())
                .isEqualTo(42);
        verify(listener).onCompleted(result.toolCallId(), AgentToolNames.GITHUB_ISSUE_READ,
                1, 1, "github-mcp");
        verify(executionStore, never()).finishTool(any(), any(), anyString(), anyString(), anyLong(), any());
    }

    @Test
    void rejectsDisabledProjectBeforeCallingMcp() {
        when(projects.list(workspaceId)).thenReturn(List.of(project(false)));

        assertUnavailable(projectId.toString());

        verifyNoInteractions(issueService);
    }

    @Test
    void rejectsUnknownRepositoryBeforeCallingMcp() {
        when(projects.list(workspaceId)).thenReturn(List.of(project(true)));

        assertUnavailable("unknown-owner/unknown-repository");

        verifyNoInteractions(issueService);
    }

    @Test
    void cannotResolveProjectFromAnotherWorkspace() {
        UUID otherWorkspace = UUID.randomUUID();
        when(projects.list(workspaceId)).thenReturn(List.of());
        when(projects.list(otherWorkspace)).thenReturn(List.of(project(true)));

        assertUnavailable(projectId.toString());

        verify(projects, never()).list(otherWorkspace);
        verifyNoInteractions(issueService);
    }

    @Test
    void mcpFailureIsAuditedAsFailureWithoutACompletionOrSuccessPayload() {
        when(projects.list(workspaceId)).thenReturn(List.of(project(true)));
        when(issueService.readIssue("spring-projects", "spring-ai", 42))
                .thenThrow(new GitHubIssueMcpService.GitHubMcpException("MCP_GITHUB_NOT_FOUND"));

        assertThatThrownBy(() -> dispatcher.execute(context(), AgentToolNames.GITHUB_ISSUE_READ,
                Map.of("projectId", projectId.toString(), "issueNumber", 42), listener))
                .isInstanceOfSatisfying(AgentToolDispatcher.DispatchException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo("MCP_GITHUB_NOT_FOUND"));

        verify(executionStore).finishTool(any(), any(), eq("FAILED"),
                eq("MCP_GITHUB_NOT_FOUND"), anyLong(), any());
        verify(executionStore, never()).succeedTool(any(), any(), any(), anyString(), anyLong(), any());
        verify(listener, never()).onCompleted(any(), anyString(), anyInt(), anyInt(), any());
        verify(listener).onFailed(any(), eq(AgentToolNames.GITHUB_ISSUE_READ), eq(1),
                eq("MCP_GITHUB_NOT_FOUND"));
    }

    @Test
    void existingReleaseToolStillUsesItsHttpGatewayAndRetainsReleaseCitations() {
        when(projects.list(workspaceId)).thenReturn(List.of(project(true)));
        String releaseUrl = "https://github.com/spring-projects/spring-ai/releases/tag/v1.0.0";
        when(releases.listRepositoryReleases(any())).thenReturn(new GitHubReleaseResult(List.of(
                new GitHubRelease(projectId.toString(), "spring-ai", "v1.0.0", "Release v1.0.0",
                        NOW, releaseUrl, false, "Release notes")), NOW, false));

        AgentToolDispatcher.ExecutionResult result = dispatcher.execute(context(),
                AgentToolNames.GITHUB_RELEASE_LIST, Map.of("projectIds", List.of(projectId.toString()),
                        "maxReleasesPerProject", 10, "includePrereleases", false), listener);

        verify(releases).listRepositoryReleases(any());
        verifyNoInteractions(issueService);
        assertThat(result.sourceUrls()).containsExactly(releaseUrl);
        assertThat(result.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.label()).isEqualTo("R1");
            assertThat(citation.sourceType()).isEqualTo("GITHUB_RELEASE");
        });
        verify(executionStore).succeedTool(any(), any(), eq(result.toolCallId()), anyString(), anyLong(), any());
    }

    private void assertUnavailable(String project) {
        assertThatThrownBy(() -> dispatcher.execute(context(), AgentToolNames.GITHUB_ISSUE_READ,
                Map.of("projectId", project, "issueNumber", 42), listener))
                .isInstanceOfSatisfying(AgentToolDispatcher.DispatchException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo("PROJECT_NOT_AVAILABLE"));
        verify(executionStore).finishTool(any(), any(), eq("FAILED"),
                eq("PROJECT_NOT_AVAILABLE"), anyLong(), any());
        verify(executionStore, never()).succeedTool(any(), any(), any(), anyString(), anyLong(), any());
    }

    private AgentToolDispatcher.ExecutionContext context() {
        return new AgentToolDispatcher.ExecutionContext(UUID.randomUUID(), workspaceId,
                UUID.randomUUID(), false, AgentToolDefinition.AccessLevel.WORKSPACE_MEMBER, 1, 1, 1);
    }

    private AdminProjectStore.ManagedProject project(boolean enabled) {
        return new AdminProjectStore.ManagedProject(projectId, "GITHUB", "spring-projects", "spring-ai",
                "https://github.com/spring-projects/spring-ai", 1, enabled, "SUCCEEDED", NOW, NOW,
                0, null, 0, 0, 0, 0, NOW, NOW);
    }
}
