package com.jundaodsj.insightops.server.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.agent.application.AgentWorkflowProductStore;
import com.jundaodsj.insightops.agent.application.AgentWorkflowTemplateStore;
import com.jundaodsj.insightops.identity.application.ActorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentWorkflowProductServiceTest {

    private static final UUID WORKSPACE = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID TEMPLATE = UUID.randomUUID();
    private static final UUID VERSION = UUID.randomUUID();

    @Mock private AgentWorkflowTemplateStore templates;
    @Mock private AgentWorkflowProductStore products;
    @Mock private AgentWorkflowService workflows;
    @Mock private AgentWorkflowExpressionService expressions;
    private ObjectMapper json;
    private AgentWorkflowProductService service;

    @BeforeEach
    void setUp() {
        json = new ObjectMapper();
        service = new AgentWorkflowProductService(
                templates, products, workflows, expressions, json);
        lenient().when(templates.find(WORKSPACE, TEMPLATE)).thenReturn(Optional.of(template()));
    }

    @Test
    void validatesAndPersistsUserOwnedParameterPreset() {
        AgentWorkflowExpressionService.Graph graph = new AgentWorkflowExpressionService.Graph(
                json.createObjectNode(), Map.of(), List.of());
        when(expressions.validateGraph("{}")).thenReturn(graph);
        when(expressions.validateInputs(graph, Map.of("topic", "Spring AI")))
                .thenReturn(Map.of("topic", "Spring AI"));
        when(products.savePreset(any())).thenAnswer(invocation -> {
            AgentWorkflowProductStore.PresetDraft draft = invocation.getArgument(0);
            return new AgentWorkflowProductStore.ParameterPreset(
                    draft.id(), draft.workspaceId(), draft.ownerUserId(), draft.templateId(),
                    draft.templateVersionId(), draft.name(), draft.valuesJson(),
                    draft.now(), draft.now());
        });

        AgentWorkflowProductService.ParameterPresetView result = service.savePreset(
                actor(), TEMPLATE, VERSION, " Spring AI ", Map.of("topic", "Spring AI"));

        assertThat(result.name()).isEqualTo("Spring AI");
        assertThat(result.values()).containsEntry("topic", "Spring AI");
        ArgumentCaptor<AgentWorkflowProductStore.PresetDraft> captor =
                ArgumentCaptor.forClass(AgentWorkflowProductStore.PresetDraft.class);
        verify(products).savePreset(captor.capture());
        assertThat(captor.getValue().ownerUserId()).isEqualTo(USER);
    }

    @Test
    void createsOneTimeRawShareTokenButStoresOnlyItsHash() {
        when(products.createShare(any())).thenAnswer(invocation -> {
            AgentWorkflowProductStore.ShareDraft draft = invocation.getArgument(0);
            return new AgentWorkflowProductStore.TemplateShare(
                    draft.id(), draft.sourceWorkspaceId(), draft.templateId(),
                    draft.templateVersionId(), "ACTIVE", draft.expiresAt(), draft.createdBy(),
                    draft.createdAt(), null, 0, null);
        });

        AgentWorkflowProductService.CreatedShare created = service.createShare(
                WORKSPACE, USER, TEMPLATE, VERSION, 30);

        ArgumentCaptor<AgentWorkflowProductStore.ShareDraft> captor =
                ArgumentCaptor.forClass(AgentWorkflowProductStore.ShareDraft.class);
        verify(products).createShare(captor.capture());
        assertThat(created.token()).hasSizeGreaterThan(40);
        assertThat(captor.getValue().tokenHash()).hasSize(64).doesNotContain(created.token());
        assertThat(created.share().status()).isEqualTo("ACTIVE");
    }

    @Test
    void aggregatesRealRunNodeFeedbackCitationTokenAndCostQuality() {
        Instant now = Instant.now();
        when(products.runMetrics(any(), any(), any(), anyInt())).thenReturn(List.of(
                metric(UUID.randomUUID(), 1, "SUCCEEDED", now.minusSeconds(100),
                        3, 2, 120, "0.020000", 2, 2, 3, 3),
                metric(UUID.randomUUID(), 2, "FAILED", now.minusSeconds(50),
                        1, 0, 80, "0.010000", 1, 0, 2, 1)));

        AgentWorkflowProductService.TemplateAnalytics result = service.analytics(
                WORKSPACE, TEMPLATE, 30);

        assertThat(result.summary().runCount()).isEqualTo(2);
        assertThat(result.summary().successRate()).isEqualTo(0.5);
        assertThat(result.summary().feedbackCount()).isEqualTo(4);
        assertThat(result.summary().helpfulRate()).isEqualTo(0.5);
        assertThat(result.summary().citationCorrectRate()).isEqualTo(0.6667);
        assertThat(result.summary().nodeSuccessRate()).isEqualTo(0.8);
        assertThat(result.summary().totalTokens()).isEqualTo(200);
        assertThat(result.summary().estimatedCostCny()).isEqualByComparingTo("0.030000");
        assertThat(result.versions()).extracting("bucket").containsExactly("v1", "v2");
    }

    @Test
    void importsBundleThroughExistingWorkflowValidationBoundary() {
        AgentWorkflowProductService.ExportBundle bundle = new AgentWorkflowProductService.ExportBundle(
                1, Instant.now(), new AgentWorkflowProductService.ExportTemplate(
                        "Shared", "description", "TECH_RESEARCH"),
                new AgentWorkflowProductService.ExportVersion(
                        4, "summary", "Research topic", Map.of("reason", "shared", "nodes", List.of())));
        when(workflows.create(any(), any(), any())).thenReturn(template());

        service.importBundle(WORKSPACE, USER, bundle, "Imported");

        ArgumentCaptor<AgentWorkflowTemplateStore.TemplateDraft> captor =
                ArgumentCaptor.forClass(AgentWorkflowTemplateStore.TemplateDraft.class);
        verify(workflows).create(any(), any(), captor.capture());
        assertThat(captor.getValue().name()).isEqualTo("Imported");
        assertThat(captor.getValue().version().graphSpecJson()).contains("shared");
    }

    private AgentWorkflowProductStore.WorkflowRunMetric metric(
            UUID runId, int version, String status, Instant createdAt,
            int feedbackCount, int helpfulCount, long tokens, String cost,
            int citations, int correct, int nodes, int successfulNodes) {
        Boolean helpful = feedbackCount == 0 ? null : helpfulCount * 2 >= feedbackCount;
        return new AgentWorkflowProductStore.WorkflowRunMetric(
                runId, version, status, createdAt, createdAt, createdAt.plusSeconds(5),
                tokens, new BigDecimal(cost), helpful, feedbackCount, helpfulCount,
                citations, correct, nodes, successfulNodes);
    }

    private AgentWorkflowTemplateStore.WorkflowTemplate template() {
        AgentWorkflowTemplateStore.WorkflowVersion version =
                new AgentWorkflowTemplateStore.WorkflowVersion(
                        VERSION, TEMPLATE, 1, "ACTIVE", "summary", "question", "{}",
                        USER, Instant.now(), Instant.now());
        return new AgentWorkflowTemplateStore.WorkflowTemplate(
                TEMPLATE, WORKSPACE, "Template", "description", "TECH_RESEARCH", "ACTIVE",
                VERSION, USER, Instant.now(), Instant.now(), List.of(version));
    }

    private ActorContext actor() {
        return new ActorContext(USER, WORKSPACE);
    }
}
