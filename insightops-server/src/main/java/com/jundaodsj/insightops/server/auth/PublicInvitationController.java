package com.jundaodsj.insightops.server.auth;

import com.jundaodsj.insightops.server.api.ApiResponse;
import com.jundaodsj.insightops.server.api.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/public/invitations")
public class PublicInvitationController {
    private final WorkspaceManagementService service;
    private final IdentityActionRateLimiter rateLimiter;

    public PublicInvitationController(WorkspaceManagementService service,
                                      IdentityActionRateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/preview")
    public ApiResponse<WorkspaceManagementService.InvitationPreview> preview(
            @Valid @RequestBody TokenRequest body, HttpServletRequest request) {
        rateLimiter.consume("INVITATION_PREVIEW", request.getRemoteAddr());
        return response(request, service.preview(body.token()));
    }

    @PostMapping("/accept")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AcceptedView> accept(@Valid @RequestBody AcceptRequest body,
                                            HttpServletRequest request) {
        rateLimiter.consume("INVITATION_ACCEPT", request.getRemoteAddr());
        return response(request, new AcceptedView(service.acceptNew(body.token(), body.username(),
                body.displayName(), body.password())));
    }

    private static <T> ApiResponse<T> response(HttpServletRequest request, T value) {
        return new ApiResponse<>((String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE), value);
    }

    public record AcceptRequest(@NotBlank @Size(min = 32, max = 200) String token,
                                @NotBlank @Size(max = 64) String username,
                                @NotBlank @Size(max = 128) String displayName,
                                @NotBlank @Size(min = 10, max = 72) String password) { }
    public record TokenRequest(@NotBlank @Size(min = 32, max = 200) String token) { }
    public record AcceptedView(UUID userId) { }
}
