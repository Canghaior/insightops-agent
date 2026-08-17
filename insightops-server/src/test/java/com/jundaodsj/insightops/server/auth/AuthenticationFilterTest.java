package com.jundaodsj.insightops.server.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthenticationFilterTest {

    @Test
    void temporaryPasswordCanOnlyAccessPasswordSetupEndpoints() throws Exception {
        AuthService authService = mock(AuthService.class);
        when(authService.authenticate("token")).thenReturn(Optional.of(account(true)));
        AuthenticationFilter filter = new AuthenticationFilter(authService, new ObjectMapper());

        MockHttpServletRequest request = request("/api/v1/chat/stream");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(428);
        assertThat(response.getContentAsString()).contains("PASSWORD_CHANGE_REQUIRED");
    }

    @Test
    void temporaryPasswordMayBeChanged() throws Exception {
        AuthService authService = mock(AuthService.class);
        when(authService.authenticate("token")).thenReturn(Optional.of(account(true)));
        AuthenticationFilter filter = new AuthenticationFilter(authService, new ObjectMapper());

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("/api/v1/auth/password"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void internalPrometheusScrapeDoesNotRequireAnApplicationSession() {
        AuthenticationFilter filter = new AuthenticationFilter(mock(AuthService.class), new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");

        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    private static MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setCookies(new Cookie(AuthenticationFilter.COOKIE_NAME, "token"));
        return request;
    }

    private static AccountWorkspaceStore.AccountRecord account(boolean mustChangePassword) {
        return new AccountWorkspaceStore.AccountRecord(UUID.randomUUID(), "member", "Member", UUID.randomUUID(),
                "Workspace", "USER", "MEMBER", "hash", mustChangePassword);
    }
}
