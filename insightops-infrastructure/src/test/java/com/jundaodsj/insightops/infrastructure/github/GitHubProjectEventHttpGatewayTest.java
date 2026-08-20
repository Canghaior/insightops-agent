package com.jundaodsj.insightops.infrastructure.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.infrastructure.config.GitHubToolProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitHubProjectEventHttpGatewayTest {

    @Test
    void mapsIssuesPullRequestsAndSecurityAdvisoriesAndUsesToken() throws Exception {
        HttpClient client=mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> issues=mock(HttpResponse.class);
        @SuppressWarnings("unchecked") HttpResponse<String> advisories=mock(HttpResponse.class);
        when(issues.statusCode()).thenReturn(200);when(advisories.statusCode()).thenReturn(200);
        when(issues.body()).thenReturn("""
                [{"number":41,"title":"Vector bug","body":"Security regression","state":"open",
                  "html_url":"https://github.com/acme/agent/issues/41","created_at":"2026-08-19T00:00:00Z",
                  "updated_at":"2026-08-20T00:00:00Z","user":{"login":"alice"},"labels":[{"name":"security"}]},
                 {"number":42,"title":"Improve RAG","body":"Better ranking","state":"closed",
                  "html_url":"https://github.com/acme/agent/pull/42","created_at":"2026-08-18T00:00:00Z",
                  "updated_at":"2026-08-20T00:00:00Z","pull_request":{},"user":{"login":"bob"},"labels":[]}]
                """);
        when(advisories.body()).thenReturn("""
                [{"ghsa_id":"GHSA-1234","summary":"Remote execution","description":"Upgrade now",
                  "severity":"high","state":"published","html_url":"https://github.com/acme/agent/security/advisories/GHSA-1234",
                  "published_at":"2026-08-20T01:00:00Z","updated_at":"2026-08-20T02:00:00Z"}]
                """);
        when(client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class)))
                .thenReturn(issues,advisories);
        var gateway=new GitHubProjectEventHttpGateway(properties(),new ObjectMapper(),client,"secret-token");

        var result=gateway.fetch("acme","agent",50);

        assertThat(result.events()).extracting(event->event.eventType())
                .containsExactly("GITHUB_ISSUE","GITHUB_PULL_REQUEST","GITHUB_SECURITY_ADVISORY");
        assertThat(result.events().getLast().riskLevel()).isEqualTo("HIGH");
        ArgumentCaptor<HttpRequest> request=ArgumentCaptor.forClass(HttpRequest.class);
        verify(client,org.mockito.Mockito.times(2)).send(request.capture(),any(HttpResponse.BodyHandler.class));
        assertThat(request.getAllValues()).allSatisfy(value ->
                assertThat(value.headers().firstValue("Authorization")).contains("Bearer secret-token"));
    }

    @Test
    void keepsIssueCollectionAvailableWhenAdvisoryEndpointIsForbidden() throws Exception {
        HttpClient client=mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> issues=mock(HttpResponse.class);
        @SuppressWarnings("unchecked") HttpResponse<String> advisories=mock(HttpResponse.class);
        when(issues.statusCode()).thenReturn(200);when(issues.body()).thenReturn("[]");
        when(advisories.statusCode()).thenReturn(403);
        when(client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(issues,advisories);

        var result=new GitHubProjectEventHttpGateway(properties(),new ObjectMapper(),client,"")
                .fetch("acme","agent",50);

        assertThat(result.events()).isEmpty();
        assertThat(result.unavailableSources()).containsExactly("GITHUB_SECURITY_ADVISORY");
    }

    private static GitHubToolProperties properties(){return new GitHubToolProperties("https://api.github.com","2026-03-10",10,20,1600);}
}
