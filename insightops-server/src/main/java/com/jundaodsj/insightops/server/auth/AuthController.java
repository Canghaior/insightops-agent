package com.jundaodsj.insightops.server.auth;

import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import com.jundaodsj.insightops.server.api.ApiResponse;
import com.jundaodsj.insightops.server.api.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final AccountAdminService accountAdminService;
    private final LoginRateLimiter loginRateLimiter;

    public AuthController(AuthService authService, AccountAdminService accountAdminService,
                          LoginRateLimiter loginRateLimiter) {
        this.authService = authService;
        this.accountAdminService = accountAdminService;
        this.loginRateLimiter = loginRateLimiter;
    }

    @PostMapping("/login")
    public ApiResponse<AccountView> login(
            @Valid @RequestBody LoginRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        String remoteAddress = request.getRemoteAddr();
        try {
            loginRateLimiter.check(body.username(), remoteAddress);
            AuthService.LoginResult result = authService.login(body.username(), body.password());
            loginRateLimiter.succeeded(body.username(), remoteAddress);
            response.addHeader(HttpHeaders.SET_COOKIE, cookie(result.token(), authService.cookieMaxAgeSeconds()));
            accountAdminService.auditSelf(result.account(), "LOGIN_SUCCEEDED");
            return new ApiResponse<>(traceId(request), view(result.account()));
        } catch (AuthService.InvalidCredentialsException exception) {
            loginRateLimiter.failed(body.username(), remoteAddress);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, exception.getMessage());
        } catch (LoginRateLimiter.LoginRateLimitedException exception) {
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()));
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, exception.getMessage());
        }
    }

    @GetMapping("/me")
    public ApiResponse<AccountView> me(HttpServletRequest request) {
        return new ApiResponse<>(traceId(request), view(CurrentAccount.account(request)));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        accountAdminService.auditSelf(CurrentAccount.account(request), "LOGOUT");
        authService.logout(AuthenticationFilter.cookie(request));
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", 0));
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void password(
            @Valid @RequestBody ChangePasswordRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        try {
            authService.changePassword(CurrentAccount.account(request), body.currentPassword(), body.newPassword());
            accountAdminService.auditSelf(CurrentAccount.account(request), "PASSWORD_CHANGED");
            response.addHeader(HttpHeaders.SET_COOKIE, cookie("", 0));
        } catch (AuthService.InvalidCredentialsException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    private String cookie(String value, int maxAge) {
        StringBuilder cookie = new StringBuilder(AuthenticationFilter.COOKIE_NAME)
                .append('=').append(value)
                .append("; Path=/; Max-Age=").append(maxAge)
                .append("; HttpOnly; SameSite=Lax");
        if (authService.secureCookie()) {
            cookie.append("; Secure");
        }
        return cookie.toString();
    }

    private static AccountView view(AccountWorkspaceStore.AccountRecord account) {
        return new AccountView(
                account.userId(), account.username(), account.displayName(), account.workspaceId(),
                account.workspaceName(), account.systemRole(), account.role(), account.mustChangePassword());
    }

    private static String traceId(HttpServletRequest request) {
        return (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
    }

    public record LoginRequest(@NotBlank String username, @NotBlank @Size(max = 72) String password) {
    }

    public record ChangePasswordRequest(
            @NotBlank @Size(max = 72) String currentPassword,
            @NotBlank @Size(min = 10, max = 72) String newPassword) {
    }

    public record AccountView(
            java.util.UUID userId,
            String username,
            String displayName,
            java.util.UUID workspaceId,
            String workspaceName,
            String systemRole,
            String role,
            boolean mustChangePassword) {
    }
}
