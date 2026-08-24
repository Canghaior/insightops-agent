package com.jundaodsj.insightops.server.auth;

import com.jundaodsj.insightops.infrastructure.identity.WorkspaceRepository;
import com.jundaodsj.insightops.server.api.ApiResponse;
import com.jundaodsj.insightops.server.api.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceController {
    private final WorkspaceManagementService service;

    public WorkspaceController(WorkspaceManagementService service) { this.service = service; }

    @GetMapping
    public ApiResponse<List<WorkspaceRepository.WorkspaceRecord>> list(HttpServletRequest request) {
        return response(request, service.list(CurrentAccount.account(request)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WorkspaceRepository.WorkspaceRecord> create(
            @Valid @RequestBody WorkspaceRequest body, HttpServletRequest request) {
        return response(request, service.create(CurrentAccount.account(request),
                body.name(), body.slug(), body.description()));
    }

    @PatchMapping("/{workspaceId}")
    public ApiResponse<WorkspaceRepository.WorkspaceRecord> update(
            @PathVariable UUID workspaceId, @Valid @RequestBody WorkspaceUpdateRequest body,
            HttpServletRequest request) {
        return response(request, service.update(CurrentAccount.account(request), workspaceId,
                body.name(), body.description()));
    }

    @PostMapping("/{workspaceId}/switch")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void switchWorkspace(@PathVariable UUID workspaceId, HttpServletRequest request) {
        service.switchWorkspace(CurrentAccount.account(request),
                AuthenticationFilter.cookie(request), workspaceId);
    }

    @PostMapping("/{workspaceId}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable UUID workspaceId, HttpServletRequest request) {
        service.archive(CurrentAccount.account(request), workspaceId);
    }

    @GetMapping("/{workspaceId}/members")
    public ApiResponse<List<WorkspaceRepository.MemberRecord>> members(
            @PathVariable UUID workspaceId, HttpServletRequest request) {
        return response(request, service.members(CurrentAccount.account(request), workspaceId));
    }

    @GetMapping("/{workspaceId}/invitations")
    public ApiResponse<List<WorkspaceRepository.InvitationRecord>> invitations(
            @PathVariable UUID workspaceId, HttpServletRequest request) {
        return response(request, service.invitations(CurrentAccount.account(request), workspaceId));
    }

    @PostMapping("/{workspaceId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WorkspaceManagementService.InvitationCreated> invite(
            @PathVariable UUID workspaceId, @Valid @RequestBody InvitationRequest body,
            HttpServletRequest request) {
        return response(request, service.invite(CurrentAccount.account(request), workspaceId,
                body.email(), body.role()));
    }

    @DeleteMapping("/{workspaceId}/invitations/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeInvitation(@PathVariable UUID workspaceId, @PathVariable UUID invitationId,
                                 HttpServletRequest request) {
        service.revokeInvitation(CurrentAccount.account(request), workspaceId, invitationId);
    }

    @PostMapping("/invitations/accept")
    public ApiResponse<AcceptedView> accept(@Valid @RequestBody TokenRequest body,
                                            HttpServletRequest request) {
        return response(request, new AcceptedView(service.acceptExisting(
                CurrentAccount.account(request), body.token())));
    }

    @PatchMapping("/{workspaceId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void role(@PathVariable UUID workspaceId, @PathVariable UUID userId,
                     @Valid @RequestBody RoleRequest body, HttpServletRequest request) {
        service.updateRole(CurrentAccount.account(request), workspaceId, userId, body.role());
    }

    @PostMapping("/{workspaceId}/ownership/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void transfer(@PathVariable UUID workspaceId, @PathVariable UUID userId,
                         HttpServletRequest request) {
        service.transferOwnership(CurrentAccount.account(request), workspaceId, userId);
    }

    @DeleteMapping("/{workspaceId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID workspaceId, @PathVariable UUID userId,
                       HttpServletRequest request) {
        service.removeMember(CurrentAccount.account(request), workspaceId, userId);
    }

    @DeleteMapping("/{workspaceId}/membership")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@PathVariable UUID workspaceId, HttpServletRequest request) {
        service.leave(CurrentAccount.account(request), workspaceId);
    }

    private static <T> ApiResponse<T> response(HttpServletRequest request, T value) {
        return new ApiResponse<>((String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE), value);
    }

    public record WorkspaceRequest(@NotBlank @Size(max = 128) String name,
                                   @NotBlank @Size(max = 64) String slug,
                                   @Size(max = 500) String description) { }
    public record WorkspaceUpdateRequest(@NotBlank @Size(max = 128) String name,
                                         @Size(max = 500) String description) { }
    public record InvitationRequest(@NotBlank @Email @Size(max = 320) String email,
                                    @NotBlank @Size(max = 16) String role) { }
    public record RoleRequest(@NotBlank @Size(max = 16) String role) { }
    public record TokenRequest(@NotBlank @Size(min = 32, max = 200) String token) { }
    public record AcceptedView(UUID workspaceId) { }
}
