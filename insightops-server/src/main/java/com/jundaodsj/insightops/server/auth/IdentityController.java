package com.jundaodsj.insightops.server.auth;

import com.jundaodsj.insightops.infrastructure.identity.IdentityRepository;
import com.jundaodsj.insightops.server.api.ApiResponse;
import com.jundaodsj.insightops.server.api.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/identity")
public class IdentityController {
    private final IdentityLifecycleService service;

    public IdentityController(IdentityLifecycleService service) {
        this.service = service;
    }

    @GetMapping("/security")
    public ApiResponse<IdentityLifecycleService.SecuritySummary> security(HttpServletRequest request) {
        return response(request, service.summary(CurrentAccount.account(request).userId()));
    }

    @PostMapping("/email")
    public ApiResponse<IdentityLifecycleService.EmailRequest> email(
            @Valid @RequestBody EmailRequest body, HttpServletRequest request) {
        return response(request, service.requestEmailChange(
                CurrentAccount.account(request), body.password(), body.email()));
    }

    @GetMapping("/sessions")
    public ApiResponse<List<SessionView>> sessions(HttpServletRequest request) {
        return response(request, service.sessions(CurrentAccount.account(request).userId(),
                AuthenticationFilter.cookie(request)).stream().map(SessionView::from).toList());
    }

    @DeleteMapping("/sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID sessionId, HttpServletRequest request,
                       HttpServletResponse response) {
        boolean current = service.sessions(CurrentAccount.account(request).userId(),
                        AuthenticationFilter.cookie(request)).stream()
                .anyMatch(value -> value.id().equals(sessionId) && value.current());
        service.revokeSession(CurrentAccount.account(request).userId(), sessionId);
        if (current) clearCookie(response);
    }

    @PostMapping("/sessions/revoke-others")
    public ApiResponse<CountView> revokeOthers(HttpServletRequest request) {
        return response(request, new CountView(service.revokeOtherSessions(
                CurrentAccount.account(request).userId(), AuthenticationFilter.cookie(request))));
    }

    @PostMapping("/mfa/setup")
    public ApiResponse<TotpService.Setup> setupMfa(@Valid @RequestBody PasswordRequest body,
                                                   HttpServletRequest request) {
        return response(request, service.beginMfa(CurrentAccount.account(request), body.password()));
    }

    @PostMapping("/mfa/confirm")
    public ApiResponse<RecoveryCodesView> confirmMfa(@Valid @RequestBody CodeRequest body,
                                                     HttpServletRequest request) {
        return response(request, new RecoveryCodesView(
                service.confirmMfa(CurrentAccount.account(request), body.code())));
    }

    @PostMapping("/mfa/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disableMfa(@Valid @RequestBody DisableMfaRequest body, HttpServletRequest request) {
        service.disableMfa(CurrentAccount.account(request), body.password(), body.code(),
                AuthenticationFilter.cookie(request));
    }

    @PostMapping("/deletion")
    public ApiResponse<DeletionView> requestDeletion(@Valid @RequestBody DeletionRequest body,
                                                     HttpServletRequest request,
                                                     HttpServletResponse response) {
        Instant scheduled = service.requestDeletion(CurrentAccount.account(request),
                body.password(), body.mfaCode());
        clearCookie(response);
        return response(request, new DeletionView(scheduled));
    }

    @DeleteMapping("/deletion")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelDeletion(@Valid @RequestBody PasswordRequest body, HttpServletRequest request) {
        service.cancelDeletion(CurrentAccount.account(request), body.password());
    }

    private static void clearCookie(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE,
                AuthenticationFilter.COOKIE_NAME + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
    }

    private static <T> ApiResponse<T> response(HttpServletRequest request, T value) {
        return new ApiResponse<>((String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE), value);
    }

    public record EmailRequest(@NotBlank @Size(max = 72) String password,
                               @NotBlank @Email @Size(max = 320) String email) { }
    public record PasswordRequest(@NotBlank @Size(max = 72) String password) { }
    public record CodeRequest(@NotBlank @Size(max = 32) String code) { }
    public record DisableMfaRequest(@NotBlank @Size(max = 72) String password,
                                    @NotBlank @Size(max = 32) String code) { }
    public record DeletionRequest(@NotBlank @Size(max = 72) String password,
                                  @Size(max = 32) String mfaCode) { }
    public record CountView(int count) { }
    public record RecoveryCodesView(List<String> recoveryCodes) { }
    public record DeletionView(Instant scheduledAt) { }
    public record SessionView(UUID id, Instant createdAt, Instant lastSeenAt, Instant expiresAt,
                              String userAgent, String addressFingerprint, UUID workspaceId,
                              String workspaceName, boolean current) {
        static SessionView from(IdentityRepository.SessionRecord value) {
            String fingerprint = value.ipHash() == null ? null
                    : value.ipHash().substring(0, Math.min(12, value.ipHash().length()));
            return new SessionView(value.id(), value.createdAt(), value.lastSeenAt(), value.expiresAt(),
                    value.userAgent(), fingerprint, value.workspaceId(), value.workspaceName(), value.current());
        }
    }
}
