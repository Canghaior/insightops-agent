package com.jundaodsj.insightops.server.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
}
