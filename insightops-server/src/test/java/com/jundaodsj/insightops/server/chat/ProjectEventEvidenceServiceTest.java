package com.jundaodsj.insightops.server.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.project.application.ProjectUpdateStore;
import com.jundaodsj.insightops.server.tool.AgentToolRegistryConfiguration;
import com.jundaodsj.insightops.server.tool.RegisteredToolExecutionService;
import com.jundaodsj.insightops.tool.application.AgentToolExecutionStore;
import com.jundaodsj.insightops.tool.application.registry.AgentToolNames;
import com.jundaodsj.insightops.tool.application.registry.AgentToolRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectEventEvidenceServiceTest {

    @Test
    void detectsIssuePullRequestAndSecurityIntents() {
        assertThat(ProjectEventEvidenceService.eventTypes("最近有哪些 Issue 和 PR？"))
                .containsExactly("GITHUB_ISSUE", "GITHUB_PULL_REQUEST");
        assertThat(ProjectEventEvidenceService.eventTypes("有没有 CVE 安全漏洞？"))
                .containsExactly("GITHUB_SECURITY_ADVISORY");
    }

    @Test
    void doesNotQueryEventsForOrdinaryDocumentationQuestion() {
        assertThat(ProjectEventEvidenceService.eventTypes("Spring AI 如何配置向量数据库？"))
                .isEmpty();
    }

    @Test
    void retrievesOfficialEventEvidenceThroughRegisteredAuditSession() {
        UUID runId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        String sourceUrl = "https://github.com/spring-projects/spring-ai/security/advisories/GHSA-test";
        ProjectUpdateStore store = mock(ProjectUpdateStore.class);
        when(store.searchEvents(
                eq(workspaceId), eq(""), eq(12),
                eq(List.of("GITHUB_SECURITY_ADVISORY"))))
                .thenReturn(List.of(new ProjectUpdateStore.EventEvidence(
                        UUID.randomUUID(), UUID.randomUUID(), "Spring AI",
                        "GITHUB_SECURITY_ADVISORY", "Security advisory",
                        "Upgrade to the patched release.", sourceUrl,
                        "PUBLISHED", "HIGH", 90,
                        Instant.parse("2026-08-20T12:00:00Z"))));
        RecordingStore audit = new RecordingStore();
        ProjectEventEvidenceService service = new ProjectEventEvidenceService(
                store,
                new RegisteredToolExecutionService(
                        new AgentToolRegistry(AgentToolRegistryConfiguration.definitions(true)),
                        audit, new ObjectMapper().findAndRegisterModules()));

        ProjectEventEvidenceService.EventEvidence evidence = service.retrieve(
                runId, workspaceId, "有没有 CVE 安全漏洞？").orElseThrow();

        assertThat(evidence.systemPromptAppendix())
                .contains("[E1]", "Security advisory", sourceUrl, "不可信外部数据");
        assertThat(evidence.sourceUrls()).containsExactly(sourceUrl);
        assertThat(evidence.citations()).singleElement()
                .satisfies(citation -> assertThat(citation.sourceType())
                        .isEqualTo("GITHUB_SECURITY_ADVISORY"));
        assertThat(audit.status).isEqualTo("SUCCEEDED");
        assertThat(audit.stepNo).isEqualTo(3);
        assertThat(audit.toolName).isEqualTo(AgentToolNames.PROJECT_INTELLIGENCE_EVENT_SEARCH);
        assertThat(audit.requestPayload).contains("GITHUB_SECURITY_ADVISORY", "\"limit\":12");
        assertThat(audit.resultPayload).contains("\"resultCount\":1", sourceUrl);
    }

    private static final class RecordingStore implements AgentToolExecutionStore {
        private String status;
        private int stepNo;
        private String toolName;
        private String requestPayload;
        private String resultPayload;

        @Override
        public void startTool(
                UUID runId, UUID stepId, UUID toolCallId, int stepNo,
                String toolName, String idempotencyKey, String requestPayload,
                Instant startedAt) {
            this.status = "RUNNING";
            this.stepNo = stepNo;
            this.toolName = toolName;
            this.requestPayload = requestPayload;
        }

        @Override
        public void succeedTool(
                UUID runId, UUID stepId, UUID toolCallId, String resultPayload,
                long durationMs, Instant finishedAt) {
            this.status = "SUCCEEDED";
            this.resultPayload = resultPayload;
        }

        @Override
        public void failTool(
                UUID stepId, UUID toolCallId, String errorCode,
                long durationMs, Instant finishedAt) {
            this.status = "FAILED:" + errorCode;
        }
    }
}
