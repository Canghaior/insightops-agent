package com.jundaodsj.insightops.server.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import com.jundaodsj.insightops.identity.application.AdminAccountStore;
import com.jundaodsj.insightops.infrastructure.identity.IdentityRepository;
import com.jundaodsj.insightops.infrastructure.identity.IdentitySecretCipher;
import com.jundaodsj.insightops.infrastructure.identity.WorkspaceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class WorkspaceManagementService {
    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{2,62}[a-z0-9]");
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{3,64}");
    private final WorkspaceRepository repository;
    private final IdentityRepository identities;
    private final IdentitySecretCipher cipher;
    private final IdentityProperties properties;
    private final AuthService authService;
    private final AdminAccountStore auditStore;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    @Autowired
    public WorkspaceManagementService(WorkspaceRepository repository, IdentityRepository identities,
                                      IdentitySecretCipher cipher, IdentityProperties properties,
                                      AuthService authService, AdminAccountStore auditStore,
                                      ObjectMapper json) {
        this(repository, identities, cipher, properties, authService, auditStore, json, Clock.systemUTC());
    }

    WorkspaceManagementService(WorkspaceRepository repository, IdentityRepository identities,
                               IdentitySecretCipher cipher, IdentityProperties properties,
                               AuthService authService, AdminAccountStore auditStore,
                               ObjectMapper json, Clock clock) {
        this.repository = repository;
        this.identities = identities;
        this.cipher = cipher;
        this.properties = properties;
        this.authService = authService;
        this.auditStore = auditStore;
        this.json = json;
        this.clock = clock;
    }

    public List<WorkspaceRepository.WorkspaceRecord> list(AccountWorkspaceStore.AccountRecord actor) {
        return repository.listForUser(actor.userId());
    }

    @Transactional
    public WorkspaceRepository.WorkspaceRecord create(AccountWorkspaceStore.AccountRecord actor,
                                                       String name, String slug, String description) {
        String safeName = required(name, 128, "Workspace name");
        String safeSlug = slug == null ? "" : slug.trim().toLowerCase(Locale.ROOT);
        if (!SLUG.matcher(safeSlug).matches()) {
            throw badRequest("Workspace slug must be 4-64 lowercase letters, digits or hyphens");
        }
        String safeDescription = optional(description, 500);
        try {
            WorkspaceRepository.WorkspaceRecord created = repository.create(
                    UUID.randomUUID(), actor.userId(), safeName, safeSlug, safeDescription, clock.instant());
            audit(actor, created.id(), actor.userId(), "WORKSPACE_CREATED", Map.of("slug", safeSlug));
            return created;
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Workspace slug already exists");
        }
    }

    @Transactional
    public WorkspaceRepository.WorkspaceRecord update(AccountWorkspaceStore.AccountRecord actor,
                                                       UUID workspaceId, String name, String description) {
        requireOwner(actor, workspaceId);
        WorkspaceRepository.WorkspaceRecord updated = repository.update(actor.userId(), workspaceId,
                required(name, 128, "Workspace name"), optional(description, 500), clock.instant())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        audit(actor, workspaceId, actor.userId(), "WORKSPACE_UPDATED", Map.of());
        return updated;
    }

    @Transactional
    public void archive(AccountWorkspaceStore.AccountRecord actor, UUID workspaceId) {
        requireOwner(actor, workspaceId);
        List<WorkspaceRepository.MemberRecord> affectedMembers = repository.listMembers(workspaceId);
        if (repository.listForUser(actor.userId()).stream()
                .filter(value -> "ACTIVE".equals(value.status())).count() <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Create or join another active workspace before archiving the current one");
        }
        if (!repository.archive(actor.userId(), workspaceId, clock.instant())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        affectedMembers.forEach(member -> identities.rehomeSessionsAfterWorkspaceRemoval(member.userId(), workspaceId, clock.instant()));
        audit(actor, workspaceId, actor.userId(), "WORKSPACE_ARCHIVED", Map.of());
    }

    public void switchWorkspace(AccountWorkspaceStore.AccountRecord actor, String rawSessionToken,
                                UUID workspaceId) {
        if (!repository.switchSession(AuthService.hash(rawSessionToken), actor.userId(), workspaceId,
                clock.instant())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You are not an active member of this workspace");
        }
    }

    public List<WorkspaceRepository.MemberRecord> members(AccountWorkspaceStore.AccountRecord actor,
                                                           UUID workspaceId) {
        requireOwner(actor, workspaceId);
        return repository.listMembers(workspaceId);
    }

    public List<WorkspaceRepository.InvitationRecord> invitations(
            AccountWorkspaceStore.AccountRecord actor, UUID workspaceId) {
        requireOwner(actor, workspaceId);
        return repository.listInvitations(workspaceId, clock.instant());
    }

    @Transactional
    public InvitationCreated invite(AccountWorkspaceStore.AccountRecord actor, UUID workspaceId,
                                    String email, String role) {
        requireOwner(actor, workspaceId);
        String normalizedEmail = IdentityLifecycleService.normalizeEmail(email);
        String safeRole = normalizeRole(role);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        Instant expiresAt = now.plus(Math.max(1, properties.getInvitationHours()), ChronoUnit.HOURS);
        WorkspaceRepository.InvitationRecord invitation = repository.createInvitation(
                UUID.randomUUID(), workspaceId, email.trim(), normalizedEmail, safeRole,
                hash(rawToken), actor.userId(), expiresAt, now);
        String link = publicUrl("/invitation#token=" + rawToken);
        identities.enqueueMail(UUID.randomUUID(), email.trim(), "WORKSPACE_INVITATION",
                "You are invited to " + invitation.workspaceName() + " on InsightOps",
                cipher.encrypt("Accept the InsightOps workspace invitation:\n\n" + link
                        + "\n\nThe link expires soon and can be used once."), now);
        audit(actor, workspaceId, actor.userId(), "WORKSPACE_INVITATION_CREATED",
                Map.of("invitationId", invitation.id(), "role", safeRole));
        return new InvitationCreated(invitation, properties.getMail().isEnabled(),
                properties.getMail().isEnabled() ? null : link);
    }

    public InvitationPreview preview(String rawToken) {
        WorkspaceRepository.InvitationRecord invitation = invitation(rawToken);
        return new InvitationPreview(invitation.workspaceName(), maskEmail(invitation.email()),
                invitation.role(), invitation.expiresAt(), invitation.existingUser());
    }

    @Transactional
    public UUID acceptNew(String rawToken, String username, String displayName,
                          String password) {
        WorkspaceRepository.InvitationRecord invitation = invitation(rawToken);
        if (invitation.existingUser()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Sign in to the existing account before accepting this invitation");
        }
        String safeUsername = username == null ? "" : username.trim();
        if (!USERNAME.matcher(safeUsername).matches()) {
            throw badRequest("Username must be 3-64 characters using letters, digits, '.', '_' or '-'");
        }
        String safeDisplayName = required(displayName, 128, "Display name");
        String passwordHash;
        try {
            passwordHash = authService.encodePassword(password);
        } catch (IllegalArgumentException exception) {
            throw badRequest(exception.getMessage());
        }
        try {
            UUID userId = repository.acceptNewInvitation(invitation.id(), UUID.randomUUID(), safeUsername,
                    safeDisplayName, invitation.email(), invitation.normalizedEmail(),
                    passwordHash, clock.instant());
            audit(invitation.workspaceId(), userId, userId, "WORKSPACE_INVITATION_ACCEPTED",
                    Map.of("invitationId", invitation.id(), "newAccount", true));
            return userId;
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username or email already exists");
        }
    }

    @Transactional
    public UUID acceptExisting(AccountWorkspaceStore.AccountRecord actor, String rawToken) {
        WorkspaceRepository.InvitationRecord invitation = invitation(rawToken);
        IdentityRepository.UserIdentity identity = identities.findIdentity(actor.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!identity.emailVerified() || identity.email() == null
                || !IdentityLifecycleService.normalizeEmail(identity.email()).equals(invitation.normalizedEmail())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The invitation email does not match this verified account");
        }
        if (!repository.acceptExistingInvitation(invitation.id(), actor.userId(), clock.instant())) {
            throw invalidInvitation();
        }
        audit(actor, invitation.workspaceId(), actor.userId(), "WORKSPACE_INVITATION_ACCEPTED",
                Map.of("invitationId", invitation.id()));
        return invitation.workspaceId();
    }

    @Transactional
    public void revokeInvitation(AccountWorkspaceStore.AccountRecord actor, UUID workspaceId,
                                 UUID invitationId) {
        requireOwner(actor, workspaceId);
        if (!repository.revokeInvitation(workspaceId, invitationId, clock.instant())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        audit(actor, workspaceId, actor.userId(), "WORKSPACE_INVITATION_REVOKED",
                Map.of("invitationId", invitationId));
    }

    @Transactional
    public void updateRole(AccountWorkspaceStore.AccountRecord actor, UUID workspaceId,
                           UUID userId, String role) {
        requireOwner(actor, workspaceId);
        WorkspaceRepository.MemberRecord target = repository.findMember(workspaceId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String safeRole = normalizeRole(role);
        if ("OWNER".equals(target.role()) && "MEMBER".equals(safeRole)
                && repository.ownerCount(workspaceId) <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A workspace must keep at least one owner");
        }
        repository.updateMemberRole(workspaceId, userId, safeRole, clock.instant());
        audit(actor, workspaceId, userId, "WORKSPACE_MEMBER_ROLE_CHANGED", Map.of("role", safeRole));
    }

    @Transactional
    public void transferOwnership(AccountWorkspaceStore.AccountRecord actor, UUID workspaceId,
                                  UUID targetUserId) {
        requireOwner(actor, workspaceId);
        if (actor.userId().equals(targetUserId)
                || !repository.transferOwnership(workspaceId, actor.userId(), targetUserId, clock.instant())) {
            throw badRequest("Choose another current workspace member");
        }
        audit(actor, workspaceId, targetUserId, "WORKSPACE_OWNERSHIP_TRANSFERRED", Map.of());
    }

    @Transactional
    public void removeMember(AccountWorkspaceStore.AccountRecord actor, UUID workspaceId,
                             UUID userId) {
        requireOwner(actor, workspaceId);
        if (actor.userId().equals(userId)) throw badRequest("Use the leave workspace action for yourself");
        WorkspaceRepository.MemberRecord target = repository.findMember(workspaceId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if ("OWNER".equals(target.role()) && repository.ownerCount(workspaceId) <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The last owner cannot be removed");
        }
        repository.removeMember(workspaceId, userId);
        identities.rehomeSessionsAfterWorkspaceRemoval(userId, workspaceId, clock.instant());
        audit(actor, workspaceId, userId, "WORKSPACE_MEMBER_REMOVED", Map.of());
    }

    @Transactional
    public void leave(AccountWorkspaceStore.AccountRecord actor, UUID workspaceId) {
        WorkspaceRepository.MemberRecord membership = repository.findMember(workspaceId, actor.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if ("OWNER".equals(membership.role()) && repository.ownerCount(workspaceId) <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Transfer ownership or archive the workspace before leaving");
        }
        repository.removeMember(workspaceId, actor.userId());
        identities.rehomeSessionsAfterWorkspaceRemoval(actor.userId(), workspaceId, clock.instant());
        audit(actor, workspaceId, actor.userId(), "WORKSPACE_LEFT", Map.of());
    }

    private WorkspaceRepository.InvitationRecord invitation(String rawToken) {
        return repository.findInvitationByToken(hash(rawToken), clock.instant())
                .filter(value -> "PENDING".equals(value.status()) && value.expiresAt().isAfter(clock.instant()))
                .orElseThrow(WorkspaceManagementService::invalidInvitation);
    }

    private void requireOwner(AccountWorkspaceStore.AccountRecord actor, UUID workspaceId) {
        WorkspaceRepository.WorkspaceRecord workspace = repository.findForUser(actor.userId(), workspaceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!"OWNER".equals(workspace.role()) && !"SYSTEM_ADMIN".equals(actor.systemRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Workspace owner access is required");
        }
    }

    private void audit(AccountWorkspaceStore.AccountRecord actor, UUID workspaceId, UUID target,
                       String action, Map<String, ?> details) {
        audit(workspaceId, actor.userId(), target, action, details);
    }

    private void audit(UUID workspaceId, UUID actorId, UUID target,
                       String action, Map<String, ?> details) {
        try {
            auditStore.appendAudit(UUID.randomUUID(), workspaceId, actorId, target,
                    action, json.writeValueAsString(details), clock.instant());
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to append workspace audit", exception);
        }
    }

    private String publicUrl(String path) {
        return properties.getPublicBaseUrl().replaceAll("/+$", "") + path;
    }

    private static String required(String value, int maximum, String label) {
        String safe = value == null ? "" : value.trim();
        if (safe.isEmpty() || safe.length() > maximum) throw badRequest(label + " is invalid");
        return safe;
    }

    private static String optional(String value, int maximum) {
        String safe = value == null ? "" : value.trim();
        if (safe.length() > maximum) throw badRequest("Description is too long");
        return safe.isEmpty() ? null : safe;
    }

    private static String normalizeRole(String role) {
        String value = role == null ? "MEMBER" : role.trim().toUpperCase(Locale.ROOT);
        if (!value.equals("OWNER") && !value.equals("MEMBER")) throw badRequest("Role is invalid");
        return value;
    }

    private static String hash(String rawToken) {
        if (rawToken == null || rawToken.length() < 32 || rawToken.length() > 200) throw invalidInvitation();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) return "***" + email.substring(Math.max(0, at));
        return email.substring(0, 1) + "***" + email.substring(at);
    }

    private static ResponseStatusException invalidInvitation() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invitation is invalid or expired");
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record InvitationCreated(WorkspaceRepository.InvitationRecord invitation,
                                    boolean deliveryQueued, String manualInvitationLink) { }
    public record InvitationPreview(String workspaceName, String maskedEmail, String role,
                                    Instant expiresAt, boolean existingUser) { }
}
