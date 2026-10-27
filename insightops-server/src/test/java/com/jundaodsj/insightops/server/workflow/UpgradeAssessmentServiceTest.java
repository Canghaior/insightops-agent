package com.jundaodsj.insightops.server.workflow;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.agent.application.AgentWorkflowTemplateStore;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.knowledge.application.KnowledgeUploadStore;
import com.jundaodsj.insightops.project.application.UserProjectWatchStore;
import com.jundaodsj.insightops.server.tool.AgentToolRegistryConfiguration;
import com.jundaodsj.insightops.tool.application.github.GitHubReleaseGateway;
import com.jundaodsj.insightops.tool.application.registry.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UpgradeAssessmentServiceTest {
    private final ActorContext actor = new ActorContext(UUID.randomUUID(), UUID.randomUUID());
    private final UUID projectId = UUID.randomUUID();
    private final AgentWorkflowTemplateStore templates = mock(AgentWorkflowTemplateStore.class);
    private final AgentWorkflowRunService runs = mock(AgentWorkflowRunService.class);
    private final UserProjectWatchStore projects = mock(UserProjectWatchStore.class);
    private final KnowledgeUploadStore uploads = mock(KnowledgeUploadStore.class);
    private final ObjectMapper json = new ObjectMapper();
    private final com.jundaodsj.insightops.agent.application.AgentWorkflowProductStore products = mock(com.jundaodsj.insightops.agent.application.AgentWorkflowProductStore.class);
    private final AgentToolRegistry registry = new AgentToolRegistry(AgentToolRegistryConfiguration.definitions(true, true));
    private final UpgradeAssessmentService service = new UpgradeAssessmentService(templates, runs, projects, uploads,
            mock(GitHubReleaseGateway.class), registry, json, products, mock(AgentWorkflowProductService.class),
            mock(com.jundaodsj.insightops.knowledge.application.KnowledgeStore.class));

    @Test void preparesRealReleaseDocsAndIssueToolsWithValidatedArguments() {
        watched();
        var template = new AgentWorkflowTemplateStore.WorkflowTemplate(UUID.randomUUID(), actor.workspaceId(),
                UpgradeAssessmentService.NAME, "", "UPGRADE_ASSESSMENT", "ACTIVE", UUID.randomUUID(), actor.userId(), Instant.now(), Instant.now(), List.of());
        when(templates.overview(actor.workspaceId())).thenReturn(List.of(template));
        service.launch(actor, false, AgentToolDefinition.AccessLevel.WORKSPACE_MEMBER,
                command(List.of(), List.of("https://github.com/spring-projects/spring-ai/issues/6620")), "trace");
        var graph = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> inputs = ArgumentCaptor.forClass(Map.class);
        verify(runs).launchPrepared(eq(actor), eq(false), any(), eq(template), graph.capture(), anyString(), inputs.capture(), any(), eq("trace"));
        var expressions = new AgentWorkflowExpressionService(json, registry);
        var parsed = expressions.validateGraph(graph.getValue());
        assertThat(parsed.nodes()).extracting(AgentWorkflowExpressionService.NodeDefinition::toolName)
                .containsExactly("github_release_list", "knowledge_hybrid_search", "knowledge_hybrid_search", "github_issue_read");
        for (var node : parsed.nodes()) {
            var resolved = expressions.resolveArguments(node, inputs.getValue(), Map.of());
            assertThatCode(() -> registry.find(node.toolName()).orElseThrow().validateInput(resolved)).doesNotThrowAnyException();
        }
    }
    @Test void deniesUnwatchedProjectsAndOtherUsersPrivateFilesBeforeLaunching() {
        assertThatThrownBy(() -> service.launch(actor, false, AgentToolDefinition.AccessLevel.WORKSPACE_MEMBER, command(List.of(), List.of()), "trace"))
                .isInstanceOf(IllegalArgumentException.class);
        watched();
        assertThatThrownBy(() -> service.launch(actor, true, AgentToolDefinition.AccessLevel.SYSTEM_ADMIN, command(List.of(UUID.randomUUID()), List.of()), "trace"))
                .hasMessageContaining("无权访问");
        verifyNoInteractions(runs);
    }
    @Test void issueLinksMustMatchSelectedRepository() {
        assertThat(UpgradeAssessmentService.issueNumber("https://github.com/spring-projects/spring-ai/issues/6620", "spring-projects", "spring-ai")).isEqualTo(6620);
        for (String link : List.of("https://github.com/other/repo/issues/6620", "https://github.com/spring-projects/spring-ai/pull/6620", "https://evil.example/issues/6620"))
            assertThatThrownBy(() -> UpgradeAssessmentService.issueNumber(link, "spring-projects", "spring-ai")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void frontendUsesItsOwnContextAndQueriesAndRejectsForeignProfileFields() {
        when(projects.list(actor)).thenReturn(List.of(new UserProjectWatchStore.ProjectWatch(projectId, "vuejs", "core", "https://github.com/vuejs/core", 1, true, Instant.now())));
        var template = new AgentWorkflowTemplateStore.WorkflowTemplate(UUID.randomUUID(), actor.workspaceId(), UpgradeAssessmentService.NAME,
                "", "UPGRADE_ASSESSMENT", "ACTIVE", UUID.randomUUID(), actor.userId(), Instant.now(), Instant.now(), List.of());
        when(templates.overview(actor.workspaceId())).thenReturn(List.of(template));
        var command = new UpgradeAssessmentService.Command(projectId, "3.4.38", "3.5.0", "", "", "运营后台",
                List.of("构建与 TypeScript"), List.of(), List.of(), UUID.randomUUID(), "web_frontend", Map.of("nodeVersion", "22", "renderMode", "SPA"));
        service.launch(actor, false, AgentToolDefinition.AccessLevel.WORKSPACE_MEMBER, command, "front");
        var graph = ArgumentCaptor.forClass(String.class);
        var question = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> input = ArgumentCaptor.forClass(Map.class);
        verify(runs).launchPrepared(eq(actor), eq(false), any(), eq(template), graph.capture(), question.capture(), input.capture(), any(), eq("front"));
        var expressions = new AgentWorkflowExpressionService(json, registry);
        var parsed = expressions.validateGraph(graph.getValue());
        expressions.validateInputs(parsed, input.getValue());
        assertThat(input.getValue()).containsEntry("projectName", "Vue").containsEntry("javaVersion", "");
        assertThat(input.getValue().get("context")).isEqualTo(Map.of("nodeVersion", "22", "renderMode", "SPA"));
        assertThat(question.getValue()).contains("浏览器", "未提供 Issue").doesNotContain("Java/Spring Boot 基线");
        for (var node : parsed.nodes()) {
            var resolved = expressions.resolveArguments(node, input.getValue(), Map.of());
            if (resolved.containsKey("query")) assertThat(resolved.get("query").toString()).contains("Vue").doesNotContain("Java", "Spring Boot");
        }
        assertThatThrownBy(() -> ResearchProfiles.normalize("web_frontend", Map.of("javaVersion", "21"), "", "")).hasMessageContaining("不属于");
        assertThatThrownBy(() -> ResearchProfiles.require("unknown")).hasMessageContaining("研究方向无效");
    }

    @Test void oldPresetsRemainAvailableAcrossTemplateVersionsWithoutBeingRewritten() {
        UUID oldVersion = UUID.randomUUID(), activeVersion = UUID.randomUUID(), templateId = UUID.randomUUID();
        Instant now = Instant.now();
        var versions = List.of(new AgentWorkflowTemplateStore.WorkflowVersion(activeVersion, templateId, 2, "ACTIVE", "", "", "{\"profileId\":{}}", actor.userId(), now, now),
                new AgentWorkflowTemplateStore.WorkflowVersion(oldVersion, templateId, 1, "ARCHIVED", "", "", "{}", actor.userId(), now, now));
        var template = new AgentWorkflowTemplateStore.WorkflowTemplate(templateId, actor.workspaceId(), UpgradeAssessmentService.NAME, "", "UPGRADE_ASSESSMENT", "ACTIVE", activeVersion, actor.userId(), now, now, versions);
        when(templates.overview(actor.workspaceId())).thenReturn(List.of(template));
        when(products.presets(actor.workspaceId(), actor.userId(), templateId, oldVersion)).thenReturn(List.of(
                new com.jundaodsj.insightops.agent.application.AgentWorkflowProductStore.ParameterPreset(UUID.randomUUID(), actor.workspaceId(), actor.userId(), templateId, oldVersion,
                        "订单服务", "{\"javaVersion\":\"21\",\"springBootVersion\":\"3.4.5\"}", now, now)));
        var saved = service.presets(actor);
        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().values()).containsEntry("profileId", "java_backend")
                .containsEntry("context", Map.of("javaVersion", "21", "springBootVersion", "3.4.5"));
        verify(products, never()).savePreset(any());
    }
    private void watched() { when(projects.list(actor)).thenReturn(List.of(new UserProjectWatchStore.ProjectWatch(projectId, "spring-projects", "spring-ai", "https://github.com/spring-projects/spring-ai", 1, true, Instant.now()))); }
    private UpgradeAssessmentService.Command command(List<UUID> files, List<String> issues) {
        return new UpgradeAssessmentService.Command(projectId, "1.0.0", "1.1.0", "21", "3.4.5", "订单服务", List.of("兼容性"), files, issues, UUID.randomUUID());
    }
}
