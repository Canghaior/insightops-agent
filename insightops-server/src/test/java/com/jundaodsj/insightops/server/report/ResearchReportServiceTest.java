package com.jundaodsj.insightops.server.report;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.agent.application.*;
import com.jundaodsj.insightops.conversation.application.ChatCitation;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.report.application.ReportDeliveryStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResearchReportServiceTest {
    private final AgentRunQuery runs = mock(AgentRunQuery.class);
    private final AgentWorkflowRunStore workflows = mock(AgentWorkflowRunStore.class);
    private final ReportDeliveryStore reports = mock(ReportDeliveryStore.class);
    private final ResearchReportService service = new ResearchReportService(runs, workflows, reports,
            new ObjectMapper().findAndRegisterModules(), "http://127.0.0.1:15173");
    private final ActorContext actor = new ActorContext(UUID.randomUUID(), UUID.randomUUID());
    private final UUID runId = UUID.randomUUID();
    @Test void missingOrIncompleteRunsCannotCreateReports() {
        assertThatThrownBy(() -> service.create(actor, runId)).isInstanceOf(ReportService.ReportNotFoundException.class);
        var run = mock(AgentRunQuery.RunDetail.class);
        when(run.status()).thenReturn("FAILED");
        when(runs.findRun(actor, runId)).thenReturn(Optional.of(run));
        assertThatThrownBy(() -> service.create(actor, runId)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(reports);
    }
    @Test void preservesAnswerCitationsAndUncertaintyWithAuthenticatedFileLinks() {
        var run = mock(AgentRunQuery.RunDetail.class);
        when(run.status()).thenReturn("SUCCEEDED"); when(run.question()).thenReturn("评估升级");
        when(run.answer()).thenReturn("## 主要变化\n结论 [S1]\n## 尚未确认事项\n迁移步骤尚未核实");
        when(run.createdAt()).thenReturn(Instant.now());
        String url = "/api/v1/knowledge/uploads/" + UUID.randomUUID() + "/content#page=2";
        when(run.citationDetails()).thenReturn(List.of(new ChatCitation("S1", "团队方案", url, "Spring AI", "第 2 页", "USER_UPLOAD", 1.0)));
        when(run.sources()).thenReturn(List.of(url));
        when(run.toolCalls()).thenReturn(List.of(new AgentRunQuery.RunToolCall(UUID.randomUUID(), UUID.randomUUID(),
                "knowledge_hybrid_search", "SUCCEEDED", Map.of(), Map.of("vectorAvailable", false,
                "sources", List.of(Map.of("version", "未标注"))), null, 1L, Instant.now(), Instant.now(), List.of())));
        when(runs.findRun(actor, runId)).thenReturn(Optional.of(run));
        service.create(actor, runId);
        var snapshot = ArgumentCaptor.forClass(ReportDeliveryStore.ResearchSnapshot.class);
        var markdown = ArgumentCaptor.forClass(String.class);
        verify(reports).createResearchReport(eq(actor), any(), anyString(), snapshot.capture(), markdown.capture(), any());
        assertThat(snapshot.getValue().answer()).isEqualTo(run.answer());
        assertThat(snapshot.getValue().uncertainties()).anyMatch(item -> item.contains("迁移步骤尚未核实"));
        assertThat(snapshot.getValue().uncertainties()).anyMatch(item -> item.contains("关键词检索"))
                .anyMatch(item -> item.contains("未标注版本"));
        assertThat(markdown.getValue()).contains("[S1]", "http://127.0.0.1:15173" + url, "仍需登录原工作区");
    }
    @Test void frontendContextIsSnapshottedAndReadableWithoutExposingTheInternalPrompt() {
        var run = mock(AgentRunQuery.RunDetail.class);
        when(run.status()).thenReturn("SUCCEEDED");
        when(run.question()).thenReturn("internal instructions <research_inputs>...</research_inputs>");
        when(run.answer()).thenReturn("## 尚未确认事项\n尚缺插件兼容性资料");
        when(run.createdAt()).thenReturn(Instant.now());
        when(runs.findRun(actor, runId)).thenReturn(Optional.of(run));
        var workflow = mock(AgentWorkflowRunStore.WorkflowRun.class);
        when(workflow.workspaceId()).thenReturn(actor.workspaceId());
        when(workflow.ownerUserId()).thenReturn(actor.userId());
        when(workflow.inputJson()).thenReturn("""
                {"profileId":"web_frontend","profileName":"Web 前端","projectName":"Vue",
                 "currentVersion":"3.4.38","targetVersion":"3.5.0","javaVersion":"",
                 "context":{"nodeVersion":"22","renderMode":"SPA","notes":"业务样例"}}
                """);
        when(workflows.find(runId)).thenReturn(Optional.of(workflow));
        service.create(actor, runId);
        var snapshot = ArgumentCaptor.forClass(ReportDeliveryStore.ResearchSnapshot.class);
        var markdown = ArgumentCaptor.forClass(String.class);
        verify(reports).createResearchReport(eq(actor), any(), anyString(), snapshot.capture(), markdown.capture(), any());
        assertThat(snapshot.getValue().inputs().get("context")).isEqualTo(Map.of("nodeVersion", "22", "renderMode", "SPA", "notes", "业务样例"));
        assertThat(snapshot.getValue().question()).isEqualTo(run.question());
        assertThat(markdown.getValue()).contains("Node.js 版本: 22", "渲染与部署方式: SPA", "尚缺插件兼容性资料", "3.4.38", "3.5.0")
                .doesNotContain("internal instructions", "Java 版本:");
    }
}
