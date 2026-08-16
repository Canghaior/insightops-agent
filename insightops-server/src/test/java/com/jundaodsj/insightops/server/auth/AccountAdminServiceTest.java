package com.jundaodsj.insightops.server.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import com.jundaodsj.insightops.identity.application.AdminAccountStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountAdminServiceTest {

    private static final UUID WORKSPACE = UUID.randomUUID();
    private final RecordingStore store = new RecordingStore();
    private AccountAdminService service;

    @BeforeEach
    void setUp() {
        AuthService authService = mock(AuthService.class);
        when(authService.encodePassword(anyString())).thenReturn("encoded");
        service = new AccountAdminService(store, authService, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-17T08:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void ownerCanCreateAnOrdinaryMember() {
        var created = service.createUser(actor("USER", "OWNER"), "java.dev", "Java Dev",
                "Temporary1", "USER", "MEMBER");

        assertThat(created.username()).isEqualTo("java.dev");
        assertThat(created.mustChangePassword()).isTrue();
        assertThat(store.audit).extracting(AdminAccountStore.AccountAudit::action)
                .containsExactly("USER_CREATED");
    }

    @Test
    void ownerCannotCreateAnOwnerOrSystemAdministrator() {
        assertForbidden(() -> service.createUser(actor("USER", "OWNER"), "other.owner", "Other",
                "Temporary1", "USER", "OWNER"));
        assertForbidden(() -> service.createUser(actor("USER", "OWNER"), "sys.admin", "Admin",
                "Temporary1", "SYSTEM_ADMIN", "MEMBER"));
    }

    @Test
    void memberCannotOpenAccountAdministration() {
        assertForbidden(() -> service.listUsers(actor("USER", "MEMBER")));
    }

    @Test
    void systemAdministratorCanResetMemberPasswordAndRevokeAccess() {
        var target = store.add("member", "USER", "MEMBER");
        service.resetPassword(actor("SYSTEM_ADMIN", "OWNER"), target.userId(), "Temporary1");

        assertThat(store.resetUser).isEqualTo(target.userId());
        assertThat(store.audit).extracting(AdminAccountStore.AccountAudit::action)
                .containsExactly("PASSWORD_RESET");
    }

    @Test
    void accountCannotDisableItself() {
        var actor = actor("SYSTEM_ADMIN", "OWNER");
        store.users.put(actor.userId(), managed(actor.userId(), actor.username(), "SYSTEM_ADMIN", "OWNER"));
        assertThatThrownBy(() -> service.updateStatus(actor, actor.userId(), "DISABLED"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private static AccountWorkspaceStore.AccountRecord actor(String systemRole, String workspaceRole) {
        return new AccountWorkspaceStore.AccountRecord(UUID.randomUUID(), "actor", "Actor", WORKSPACE,
                "Workspace", systemRole, workspaceRole, "hash", false);
    }

    private static AdminAccountStore.ManagedUser managed(
            UUID id, String username, String systemRole, String workspaceRole) {
        Instant now = Instant.parse("2026-08-17T08:00:00Z");
        return new AdminAccountStore.ManagedUser(id, username, username, "ACTIVE", systemRole,
                workspaceRole, true, now, now);
    }

    private static void assertForbidden(Runnable runnable) {
        assertThatThrownBy(runnable::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    private static final class RecordingStore implements AdminAccountStore {
        private final Map<UUID, ManagedUser> users = new LinkedHashMap<>();
        private final List<AccountAudit> audit = new ArrayList<>();
        private UUID resetUser;

        private ManagedUser add(String username, String systemRole, String role) {
            ManagedUser user = managed(UUID.randomUUID(), username, systemRole, role);
            users.put(user.userId(), user);
            return user;
        }

        @Override public List<ManagedUser> listUsers(UUID workspaceId) { return List.copyOf(users.values()); }
        @Override public Optional<ManagedUser> findUser(UUID workspaceId, UUID userId) { return Optional.ofNullable(users.get(userId)); }

        @Override
        public ManagedUser createUser(UUID userId, UUID workspaceId, String username, String displayName,
                                      String passwordHash, String systemRole, String workspaceRole, Instant now) {
            ManagedUser user = new ManagedUser(userId, username, displayName, "ACTIVE", systemRole,
                    workspaceRole, true, now, now);
            users.put(userId, user);
            return user;
        }

        @Override
        public Optional<ManagedUser> updateStatus(UUID workspaceId, UUID userId, String status, Instant now) {
            return findUser(workspaceId, userId).map(user -> {
                ManagedUser updated = new ManagedUser(user.userId(), user.username(), user.displayName(), status,
                        user.systemRole(), user.workspaceRole(), user.mustChangePassword(), user.createdAt(), now);
                users.put(userId, updated);
                return updated;
            });
        }

        @Override
        public Optional<ManagedUser> updateWorkspaceRole(UUID workspaceId, UUID userId, String role, Instant now) {
            return findUser(workspaceId, userId).map(user -> {
                ManagedUser updated = new ManagedUser(user.userId(), user.username(), user.displayName(), user.status(),
                        user.systemRole(), role, user.mustChangePassword(), user.createdAt(), now);
                users.put(userId, updated);
                return updated;
            });
        }

        @Override public boolean resetPassword(UUID workspaceId, UUID userId, String passwordHash, Instant now) {
            resetUser = userId;
            return users.containsKey(userId);
        }

        @Override
        public void appendAudit(UUID auditId, UUID workspaceId, UUID actorUserId, UUID targetUserId,
                                String action, String detailsJson, Instant now) {
            audit.add(new AccountAudit(auditId, actorUserId, "actor", targetUserId, "target", action,
                    detailsJson, now));
        }

        @Override public List<AccountAudit> listAudit(UUID workspaceId, int limit) { return List.copyOf(audit); }
    }
}
