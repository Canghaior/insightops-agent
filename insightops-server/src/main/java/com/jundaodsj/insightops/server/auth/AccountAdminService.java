package com.jundaodsj.insightops.server.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import com.jundaodsj.insightops.identity.application.AdminAccountStore;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AccountAdminService {

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{3,64}");
    private static final String SYSTEM_ADMIN = "SYSTEM_ADMIN";
    private static final String OWNER = "OWNER";
    private static final String MEMBER = "MEMBER";

    private final AdminAccountStore store;
    private final AuthService authService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public AccountAdminService(AdminAccountStore store, AuthService authService, ObjectMapper objectMapper) {
        this(store, authService, objectMapper, Clock.systemUTC());
    }

    AccountAdminService(
            AdminAccountStore store, AuthService authService, ObjectMapper objectMapper, Clock clock) {
        this.store = store;
        this.authService = authService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public List<AdminAccountStore.ManagedUser> listUsers(AccountWorkspaceStore.AccountRecord actor) {
        requireManager(actor);
        return store.listUsers(actor.workspaceId());
    }

    @Transactional
    public AdminAccountStore.ManagedUser createUser(
            AccountWorkspaceStore.AccountRecord actor,
            String username,
            String displayName,
            String temporaryPassword,
            String systemRole,
            String workspaceRole) {
        requireManager(actor);
        String normalizedUsername = username == null ? "" : username.trim();
        String normalizedDisplayName = displayName == null ? "" : displayName.trim();
        if (!USERNAME.matcher(normalizedUsername).matches()) {
            throw badRequest("Username must be 3-64 characters using letters, digits, '.', '_' or '-'");
        }
        if (normalizedDisplayName.isEmpty() || normalizedDisplayName.length() > 128) {
            throw badRequest("Display name must be 1-128 characters");
        }
        String requestedSystemRole = normalizeSystemRole(systemRole);
        String requestedWorkspaceRole = normalizeWorkspaceRole(workspaceRole);
        if (!isSystemAdmin(actor)
                && (!"USER".equals(requestedSystemRole) || !MEMBER.equals(requestedWorkspaceRole))) {
            throw forbidden("Workspace owners may only create ordinary members");
        }
        UUID userId = UUID.randomUUID();
        Instant now = clock.instant();
        try {
            AdminAccountStore.ManagedUser user = store.createUser(
                    userId, actor.workspaceId(), normalizedUsername, normalizedDisplayName,
                    authService.encodePassword(temporaryPassword), requestedSystemRole,
                    requestedWorkspaceRole, now);
            audit(actor, userId, "USER_CREATED", Map.of(
                    "systemRole", requestedSystemRole,
                    "workspaceRole", requestedWorkspaceRole));
            return user;
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        } catch (IllegalArgumentException exception) {
            throw badRequest(exception.getMessage());
        }
    }

    @Transactional
    public AdminAccountStore.ManagedUser updateStatus(
            AccountWorkspaceStore.AccountRecord actor, UUID targetUserId, String status) {
        requireManager(actor);
        AdminAccountStore.ManagedUser target = target(actor, targetUserId);
        requireCanManage(actor, target);
        String normalized = status == null ? "" : status.trim().toUpperCase();
        if (!normalized.equals("ACTIVE") && !normalized.equals("DISABLED")) {
            throw badRequest("Status must be ACTIVE or DISABLED");
        }
        if (actor.userId().equals(targetUserId) && normalized.equals("DISABLED")) {
            throw badRequest("You cannot disable your own account");
        }
        AdminAccountStore.ManagedUser updated = store.updateStatus(
                actor.workspaceId(), targetUserId, normalized, clock.instant()).orElseThrow(this::notFound);
        audit(actor, targetUserId, normalized.equals("ACTIVE") ? "USER_ENABLED" : "USER_DISABLED", Map.of());
        return updated;
    }

    @Transactional
    public AdminAccountStore.ManagedUser updateWorkspaceRole(
            AccountWorkspaceStore.AccountRecord actor, UUID targetUserId, String workspaceRole) {
        requireManager(actor);
        AdminAccountStore.ManagedUser target = target(actor, targetUserId);
        requireCanManage(actor, target);
        if (!isSystemAdmin(actor)) {
            throw forbidden("Only a system administrator may change workspace roles");
        }
        String normalized = normalizeWorkspaceRole(workspaceRole);
        if (actor.userId().equals(targetUserId) && MEMBER.equals(normalized)) {
            throw badRequest("You cannot remove your own owner role");
        }
        AdminAccountStore.ManagedUser updated = store.updateWorkspaceRole(
                actor.workspaceId(), targetUserId, normalized, clock.instant()).orElseThrow(this::notFound);
        audit(actor, targetUserId, "WORKSPACE_ROLE_CHANGED", Map.of("workspaceRole", normalized));
        return updated;
    }

    @Transactional
    public void resetPassword(
            AccountWorkspaceStore.AccountRecord actor, UUID targetUserId, String temporaryPassword) {
        requireManager(actor);
        AdminAccountStore.ManagedUser target = target(actor, targetUserId);
        requireCanManage(actor, target);
        String encoded;
        try {
            encoded = authService.encodePassword(temporaryPassword);
        } catch (IllegalArgumentException exception) {
            throw badRequest(exception.getMessage());
        }
        if (!store.resetPassword(actor.workspaceId(), targetUserId, encoded, clock.instant())) {
            throw notFound();
        }
        audit(actor, targetUserId, "PASSWORD_RESET", Map.of("mustChangePassword", true));
    }

    public List<AdminAccountStore.AccountAudit> listAudit(
            AccountWorkspaceStore.AccountRecord actor, int limit) {
        requireManager(actor);
        return store.listAudit(actor.workspaceId(), Math.max(1, Math.min(limit, 200)));
    }

    public void auditSelf(AccountWorkspaceStore.AccountRecord actor, String action) {
        audit(actor, actor.userId(), action, Map.of());
    }

    private AdminAccountStore.ManagedUser target(
            AccountWorkspaceStore.AccountRecord actor, UUID targetUserId) {
        return store.findUser(actor.workspaceId(), targetUserId).orElseThrow(this::notFound);
    }

    private static void requireManager(AccountWorkspaceStore.AccountRecord actor) {
        if (!isSystemAdmin(actor) && !OWNER.equals(actor.role())) {
            throw forbidden("Account administration requires an owner or system administrator");
        }
    }

    private static void requireCanManage(
            AccountWorkspaceStore.AccountRecord actor, AdminAccountStore.ManagedUser target) {
        if (isSystemAdmin(actor)) return;
        if (SYSTEM_ADMIN.equals(target.systemRole()) || OWNER.equals(target.workspaceRole())) {
            throw forbidden("Workspace owners may only manage ordinary members");
        }
    }

    private static boolean isSystemAdmin(AccountWorkspaceStore.AccountRecord actor) {
        return SYSTEM_ADMIN.equals(actor.systemRole());
    }

    private static String normalizeSystemRole(String role) {
        String normalized = role == null || role.isBlank() ? "USER" : role.trim().toUpperCase();
        if (!normalized.equals("USER") && !normalized.equals(SYSTEM_ADMIN)) {
            throw badRequest("System role must be USER or SYSTEM_ADMIN");
        }
        return normalized;
    }

    private static String normalizeWorkspaceRole(String role) {
        String normalized = role == null || role.isBlank() ? MEMBER : role.trim().toUpperCase();
        if (!normalized.equals(OWNER) && !normalized.equals(MEMBER)) {
            throw badRequest("Workspace role must be OWNER or MEMBER");
        }
        return normalized;
    }

    private void audit(
            AccountWorkspaceStore.AccountRecord actor, UUID targetUserId, String action, Map<String, ?> details) {
        try {
            store.appendAudit(UUID.randomUUID(), actor.workspaceId(), actor.userId(), targetUserId,
                    action, objectMapper.writeValueAsString(details), clock.instant());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize account audit details", exception);
        }
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "User was not found in this workspace");
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException forbidden(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
}
