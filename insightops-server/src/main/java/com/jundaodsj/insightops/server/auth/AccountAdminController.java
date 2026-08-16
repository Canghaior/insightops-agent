package com.jundaodsj.insightops.server.auth;

import com.jundaodsj.insightops.identity.application.AdminAccountStore;
import com.jundaodsj.insightops.server.api.ApiResponse;
import com.jundaodsj.insightops.server.api.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
public class AccountAdminController {

    private final AccountAdminService service;

    public AccountAdminController(AccountAdminService service) {
        this.service = service;
    }

    @GetMapping("/users")
    public ApiResponse<List<AdminAccountStore.ManagedUser>> users(HttpServletRequest request) {
        return response(request, service.listUsers(CurrentAccount.account(request)));
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdminAccountStore.ManagedUser> create(
            @Valid @RequestBody CreateUserRequest body, HttpServletRequest request) {
        return response(request, service.createUser(CurrentAccount.account(request), body.username(),
                body.displayName(), body.temporaryPassword(), body.systemRole(), body.workspaceRole()));
    }

    @PatchMapping("/users/{userId}/status")
    public ApiResponse<AdminAccountStore.ManagedUser> status(
            @PathVariable UUID userId,
            @Valid @RequestBody StatusRequest body,
            HttpServletRequest request) {
        return response(request, service.updateStatus(CurrentAccount.account(request), userId, body.status()));
    }

    @PatchMapping("/users/{userId}/role")
    public ApiResponse<AdminAccountStore.ManagedUser> role(
            @PathVariable UUID userId,
            @Valid @RequestBody RoleRequest body,
            HttpServletRequest request) {
        return response(request,
                service.updateWorkspaceRole(CurrentAccount.account(request), userId, body.workspaceRole()));
    }

    @PostMapping("/users/{userId}/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(
            @PathVariable UUID userId,
            @Valid @RequestBody ResetPasswordRequest body,
            HttpServletRequest request) {
        service.resetPassword(CurrentAccount.account(request), userId, body.temporaryPassword());
    }

    @GetMapping("/audit")
    public ApiResponse<List<AdminAccountStore.AccountAudit>> audit(
            @RequestParam(defaultValue = "100") int limit, HttpServletRequest request) {
        return response(request, service.listAudit(CurrentAccount.account(request), limit));
    }

    private static <T> ApiResponse<T> response(HttpServletRequest request, T data) {
        return new ApiResponse<>((String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE), data);
    }

    public record CreateUserRequest(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Size(max = 128) String displayName,
            @NotBlank @Size(min = 10, max = 72) String temporaryPassword,
            String systemRole,
            String workspaceRole) {
    }

    public record StatusRequest(@NotBlank String status) {
    }

    public record RoleRequest(@NotBlank String workspaceRole) {
    }

    public record ResetPasswordRequest(
            @NotBlank @Size(min = 10, max = 72) String temporaryPassword) {
    }
}
