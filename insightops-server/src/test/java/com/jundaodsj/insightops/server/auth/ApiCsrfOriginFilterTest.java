package com.jundaodsj.insightops.server.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ApiCsrfOriginFilterTest {

    @Test
    void rejectsUnsafeRequestWithoutHeader() throws Exception {
        ApiCsrfOriginFilter filter = filter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("CSRF_HEADER_REQUIRED");
    }

    @Test
    void rejectsForeignOriginEvenWithHeader() throws Exception {
        ApiCsrfOriginFilter filter = filter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.addHeader(ApiCsrfOriginFilter.HEADER, "1");
        request.addHeader("Origin", "https://attacker.example");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("ORIGIN_NOT_ALLOWED");
    }

    @Test
    void acceptsExpectedOriginAndSkipsSafeMethods() throws Exception {
        ApiCsrfOriginFilter filter = filter();
        MockHttpServletRequest post = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        post.addHeader(ApiCsrfOriginFilter.HEADER, "1");
        post.addHeader("Origin", "https://insightops.example");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(post, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/v1/workspaces"))).isTrue();
    }

    private static ApiCsrfOriginFilter filter() {
        IdentityProperties properties = new IdentityProperties();
        properties.setPublicBaseUrl("https://insightops.example/path");
        return new ApiCsrfOriginFilter(properties, new ObjectMapper());
    }
}
