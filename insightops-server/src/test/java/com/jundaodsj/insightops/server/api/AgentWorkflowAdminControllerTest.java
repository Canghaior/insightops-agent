package com.jundaodsj.insightops.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.agent.application.AgentWorkflowTemplateStore;
import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import com.jundaodsj.insightops.server.tool.AgentToolRegistryConfiguration;
import com.jundaodsj.insightops.server.workflow.AgentWorkflowService;
import com.jundaodsj.insightops.tool.application.registry.AgentToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentWorkflowAdminControllerTest {

    private static final String ROOT = "/api/v1/admin/agent-workflows";
    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000035");
    private static final UUID WORKSPACE_ID = UUID.fromString("20000000-0000-0000-0000-000000000035");
    private static final UUID TEMPLATE_ID = UUID.fromString("30000000-0000-0000-0000-000000000035");
    private static final String GRAPH = """
            {
              "inputs":{"topic":{"type":"string","required":true,"maxLength":500}},
              "nodes":[{
                "id":"research_1","toolName":"knowledge_hybrid_search",
                "arguments":{"query":"${inputs.topic} · 官方核心能力","candidateLimit":12},
                "dependsOn":[],"condition":"ALWAYS","required":true,
                "exposeOutputs":["resultCount","sources"]
              }]
            }
            """;

    private final AgentWorkflowService service = mock(AgentWorkflowService.class);
    private final ObjectMapper serviceJson = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AgentWorkflowAdminController(service))
                .setMessageConverters(new JacksonJsonHttpMessageConverter())
                .build();
    }

    @Test
    void workspaceOwnerCanReadWorkflowGovernance() {
        AgentWorkflowService service = mock(AgentWorkflowService.class);
        UUID workspaceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AgentWorkflowService.Overview overview = new AgentWorkflowService.Overview(
                List.of(), List.of(), 32);
        when(service.overview(workspaceId, userId)).thenReturn(overview);
        AgentWorkflowAdminController controller = new AgentWorkflowAdminController(service);

        ApiResponse<AgentWorkflowService.Overview> response = controller.overview(
                request(userId, workspaceId, "USER", "OWNER"));

        assertThat(response.traceId()).isEqualTo("trace-workflow");
        assertThat(response.data()).isSameAs(overview);
    }

    @Test
    void ordinaryMemberCannotManageWorkflowTemplates() {
        AgentWorkflowAdminController controller = new AgentWorkflowAdminController(
                mock(AgentWorkflowService.class));

        assertThatThrownBy(() -> controller.overview(
                request(UUID.randomUUID(), UUID.randomUUID(), "USER", "MEMBER")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void previewsParameterizedGraphThroughTheJackson3HttpConverter() throws Exception {
        when(service.preview(anyString())).thenReturn(new AgentWorkflowService.Preview(
                GRAPH, "research", List.of(), List.of(), 1, 1, 0, List.of()));

        mvc.perform(ownerPost(ROOT + "/preview", "{\"graph\":" + GRAPH + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodeCount").value(1));

        ArgumentCaptor<String> graph = ArgumentCaptor.forClass(String.class);
        verify(service).preview(graph.capture());
        assertThat(serviceJson.readTree(graph.getValue())).isEqualTo(serviceJson.readTree(GRAPH));
    }

    @Test
    void preservesNestedGraphsWhenCreatingTemplatesAndVersions() throws Exception {
        String version = "{\"summary\":\"本地验收\",\"entryQuestion\":\"研究 ${inputs.topic}\",\"graph\":"
                + GRAPH + "}";
        String template = "{\"name\":\"技术专题研究\",\"description\":\"并行检索\","
                + "\"category\":\"TECH_RESEARCH\",\"version\":" + version + "}";

        mvc.perform(ownerPost(ROOT + "/templates", template)).andExpect(status().isOk());
        mvc.perform(ownerPost(ROOT + "/templates/" + TEMPLATE_ID + "/versions", version))
                .andExpect(status().isOk());

        ArgumentCaptor<AgentWorkflowTemplateStore.TemplateDraft> templateDraft =
                ArgumentCaptor.forClass(AgentWorkflowTemplateStore.TemplateDraft.class);
        verify(service).create(eq(WORKSPACE_ID), eq(USER_ID), templateDraft.capture());
        ArgumentCaptor<AgentWorkflowTemplateStore.VersionDraft> versionDraft =
                ArgumentCaptor.forClass(AgentWorkflowTemplateStore.VersionDraft.class);
        verify(service).createVersion(eq(WORKSPACE_ID), eq(TEMPLATE_ID), eq(USER_ID), versionDraft.capture());
        assertThat(serviceJson.readTree(templateDraft.getValue().version().graphSpecJson()))
                .isEqualTo(serviceJson.readTree(GRAPH));
        assertThat(serviceJson.readTree(versionDraft.getValue().graphSpecJson()))
                .isEqualTo(serviceJson.readTree(GRAPH));
        assertThat(versionDraft.getValue().entryQuestion()).isEqualTo("研究 ${inputs.topic}");
    }

    @Test
    void rejectsMissingMalformedAndInvalidGraphs() throws Exception {
        AgentWorkflowService validatingService = new AgentWorkflowService(
                mock(AgentWorkflowTemplateStore.class),
                new AgentToolRegistry(AgentToolRegistryConfiguration.definitions(true)), serviceJson);
        MockMvc validatingMvc = MockMvcBuilders.standaloneSetup(
                        new AgentWorkflowAdminController(validatingService))
                .setMessageConverters(new JacksonJsonHttpMessageConverter()).build();
        validatingMvc.perform(ownerPost(ROOT + "/preview", "{}")).andExpect(status().isBadRequest());
        validatingMvc.perform(ownerPost(ROOT + "/preview", "{\"graph\":null}"))
                .andExpect(status().isBadRequest());
        validatingMvc.perform(ownerPost(ROOT + "/preview", "{\"graph\":"))
                .andExpect(status().isBadRequest());
        validatingMvc.perform(ownerPost(ROOT + "/preview", "{\"graph\":{\"nodes\":[]}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsOrdinaryMembersBeforePreflight() throws Exception {
        mvc.perform(post(ROOT + "/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"graph\":" + GRAPH + "}")
                        .requestAttr(CurrentAccount.ATTRIBUTE, account("USER", "MEMBER")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    private static MockHttpServletRequestBuilder ownerPost(String path, String body) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(body)
                .requestAttr(CurrentAccount.ATTRIBUTE, account("SYSTEM_ADMIN", "OWNER"))
                .requestAttr(TraceIdFilter.TRACE_ID_ATTRIBUTE, "trace-workflow-http");
    }

    private static AccountWorkspaceStore.AccountRecord account(String systemRole, String workspaceRole) {
        return new AccountWorkspaceStore.AccountRecord(USER_ID, "test-owner", "Test Owner", WORKSPACE_ID,
                "Test Workspace", systemRole, workspaceRole, "unused", false);
    }

    private static MockHttpServletRequest request(
            UUID userId, UUID workspaceId, String systemRole, String workspaceRole) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE, "trace-workflow");
        request.setAttribute(CurrentAccount.ATTRIBUTE, new AccountWorkspaceStore.AccountRecord(
                userId, "operator", "Operator", workspaceId, "Workspace",
                systemRole, workspaceRole, "hash", false));
        return request;
    }
}
