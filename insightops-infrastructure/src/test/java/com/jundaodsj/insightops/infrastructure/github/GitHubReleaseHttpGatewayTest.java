package com.jundaodsj.insightops.infrastructure.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.infrastructure.config.GitHubToolProperties;
import com.jundaodsj.insightops.tool.application.github.GitHubReleaseQuery;
import com.jundaodsj.insightops.tool.application.github.GitHubToolErrorCode;
import com.jundaodsj.insightops.tool.application.github.GitHubToolException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitHubReleaseHttpGatewayTest {

    @Test
    void shouldFetchPublishedReleaseWithRequiredHeaders() throws Exception {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                [{
                  "tag_name":"v2.0.0",
                  "name":"Spring AI 2.0",
                  "html_url":"https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0",
                  "draft":false,
                  "prerelease":false,
                  "published_at":"2026-08-15T00:00:00Z",
                  "body":"Tool Calling improvements"
                }]
                """);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        GitHubReleaseHttpGateway gateway = new GitHubReleaseHttpGateway(
                properties(), new ObjectMapper(), client);

        var result = gateway.listReleases(new GitHubReleaseQuery(
                List.of("spring-ai"), null, 5, false));

        assertThat(result.releases()).hasSize(1);
        assertThat(result.releases().getFirst().tagName()).isEqualTo("v2.0.0");
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().headers().firstValue("User-Agent"))
                .contains("InsightOps-Agent/0.1");
        assertThat(request.getValue().headers().firstValue("X-GitHub-Api-Version"))
                .contains("2026-03-10");
    }

    @Test
    void shouldRejectProjectOutsideAllowlist() {
        GitHubReleaseHttpGateway gateway = new GitHubReleaseHttpGateway(
                properties(), new ObjectMapper(), mock(HttpClient.class));

        assertThatThrownBy(() -> gateway.listReleases(new GitHubReleaseQuery(
                List.of("unknown"), null, 5, false)))
                .isInstanceOfSatisfying(GitHubToolException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(GitHubToolErrorCode.VALIDATION_ERROR));
    }

    private static GitHubToolProperties properties() {
        return new GitHubToolProperties(
                "https://api.github.com", "2026-03-10", 10, 20, 1600);
    }
}
