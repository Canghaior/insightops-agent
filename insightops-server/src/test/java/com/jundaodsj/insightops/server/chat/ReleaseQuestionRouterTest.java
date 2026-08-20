package com.jundaodsj.insightops.server.chat;

import com.jundaodsj.insightops.project.application.AdminProjectStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReleaseQuestionRouterTest {

    private final ReleaseQuestionRouter router = new ReleaseQuestionRouter();

    @Test
    void shouldRouteReleaseQuestionWithProjectsAndTimeWindow() {
        var query = router.route(
                "Spring AI 和 LangChain4j 最近90天在 Tool Calling 方面分别有什么变化？");

        assertThat(query).isPresent();
        assertThat(query.orElseThrow().projectIds()).containsExactly("spring-ai", "langchain4j");
        assertThat(query.orElseThrow().timeWindowDays()).isEqualTo(90);
    }

    @Test
    void shouldNotCallReleaseToolForGeneralArchitectureQuestion() {
        assertThat(router.route("比较 Spring AI 和 LangChain4j 的核心设计取向。"))
                .isEmpty();
    }

    @Test
    void shouldNotAllowUnknownProjects() {
        assertThat(router.route("UnknownAgent 最新版本是什么？"))
                .isEmpty();
    }

    @Test
    void shouldUseNinetyDaysForNaturalRecentQuestions() {
        var query = router.route("Dify 最近在工作流、Agent 或知识库方面有哪些变化？");

        assertThat(query).isPresent();
        assertThat(query.orElseThrow().timeWindowDays()).isEqualTo(90);
        assertThat(query.orElseThrow().maxReleasesPerProject()).isEqualTo(30);
    }

    @Test
    void shouldNotInferProjectFromPronounOnlyFollowUp() {
        assertThat(router.route("这个版本相比上一个版本有什么变化？"))
                .isEmpty();
    }

    @Test
    void shouldResolvePronounOnlyFollowUpFromPreviousProjectContext() {
        var query = router.routeWithProjectContext(
                "这个版本相比上一个版本有什么变化？",
                "Spring AI 最新正式版本是什么？");

        assertThat(query).isPresent();
        assertThat(query.orElseThrow().projectIds()).containsExactly("spring-ai");
        assertThat(query.orElseThrow().maxReleasesPerProject()).isEqualTo(2);
    }

    @Test
    void shouldInheritTimeWindowForEvidenceBasedFollowUp() {
        var query = router.routeWithProjectContext(
                "根据刚才证据，哪个项目发布更活跃？",
                "Spring AI 和 LangChain4j 最近 90 天的正式 Release 情况如何？");

        assertThat(query).isPresent();
        assertThat(query.orElseThrow().projectIds())
                .containsExactly("spring-ai", "langchain4j");
        assertThat(query.orElseThrow().timeWindowDays()).isEqualTo(90);
        assertThat(query.orElseThrow().maxReleasesPerProject()).isEqualTo(30);
    }

    @Test
    void shouldResolveEnabledWorkspaceProjectByCustomAlias() {
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        AdminProjectStore store = mock(AdminProjectStore.class);
        when(store.list(workspaceId)).thenReturn(List.of(new AdminProjectStore.ManagedProject(
                projectId, "github", "acme", "agent-platform",
                "https://github.com/acme/agent-platform", 2, 4, List.of("nebula"),
                true, "SUCCEEDED", Instant.EPOCH, Instant.EPOCH, 0, null,
                1, 0, 0, 0, Instant.EPOCH, Instant.EPOCH)));
        ReleaseQuestionRouter dynamicRouter = new ReleaseQuestionRouter(store);

        var routed = dynamicRouter.routeWithProjectContext(
                workspaceId, "Nebula 最近版本有什么变化？", "");

        assertThat(routed).isPresent();
        assertThat(routed.orElseThrow().evidenceQuery().projectIds())
                .containsExactly(projectId.toString());
        assertThat(routed.orElseThrow().repositories()).singleElement().satisfies(repository -> {
            assertThat(repository.repositoryOwner()).isEqualTo("acme");
            assertThat(repository.repositoryName()).isEqualTo("agent-platform");
        });
    }
}
