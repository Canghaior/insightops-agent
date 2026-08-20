package com.jundaodsj.insightops.server.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import com.jundaodsj.insightops.identity.application.AdminAccountStore;
import com.jundaodsj.insightops.project.application.AdminProjectStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AdminProjectServiceTest {

    private static final UUID WORKSPACE = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-19T08:00:00Z");
    private final RecordingStore store = new RecordingStore();
    private final AdminAccountStore auditStore = mock(AdminAccountStore.class);
    private AdminProjectService service;

    @BeforeEach
    void setUp() {
        service = new AdminProjectService(store, auditStore, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void ownerCanCreateARepositoryAndCoordinatesAreNormalized() {
        var created = service.create(actor("USER", "OWNER"), " OpenAI ", " OpenAI-Java ", 2,
                10, List.of(" OpenAI SDK ", "openai sdk"));

        assertThat(created.repositoryOwner()).isEqualTo("openai");
        assertThat(created.repositoryName()).isEqualTo("openai-java");
        assertThat(created.canonicalUrl()).isEqualTo("https://github.com/openai/openai-java");
        assertThat(created.syncIntervalHours()).isEqualTo(10);
        assertThat(created.chatAliases()).containsExactly("openai sdk");
        assertThat(created.enabled()).isTrue();
        verify(auditStore).appendAudit(
                org.mockito.ArgumentMatchers.any(), eq(WORKSPACE),
                org.mockito.ArgumentMatchers.any(), eq(null), eq("PROJECT_CREATED"),
                org.mockito.ArgumentMatchers.contains("openai/openai-java"), eq(NOW));
    }

    @Test
    void memberCannotManageProjects() {
        assertStatus(HttpStatus.FORBIDDEN,
                () -> service.list(actor("USER", "MEMBER")));
    }

    @Test
    void invalidRepositoryAndPriorityAreRejected() {
        assertStatus(HttpStatus.BAD_REQUEST,
                () -> service.create(actor("SYSTEM_ADMIN", "MEMBER"), "bad owner", "repo", 3));
        assertStatus(HttpStatus.BAD_REQUEST,
                () -> service.create(actor("SYSTEM_ADMIN", "MEMBER"), "owner", "repo", 6));
    }

    @Test
    void repositoryCoordinatesCannotChangeAfterDataWasCollected() {
        var project = store.add("spring-projects", "spring-ai", 1, 4, 0, 0, 0);

        assertStatus(HttpStatus.CONFLICT,
                () -> service.update(actor("SYSTEM_ADMIN", "OWNER"), project.projectId(),
                        "other", "repository", 3));
    }

    @Test
    void priorityCanChangeAfterDataWasCollected() {
        var project = store.add("spring-projects", "spring-ai", 1, 4, 0, 0, 0);

        var updated = service.update(actor("SYSTEM_ADMIN", "OWNER"), project.projectId(),
                "spring-projects", "spring-ai", 5);

        assertThat(updated.priority()).isEqualTo(5);
    }

    @Test
    void dependentProjectMustBeDisabledInsteadOfDeleted() {
        var project = store.add("owner", "repo", 3, 0, 0, 1, 0);

        assertStatus(HttpStatus.CONFLICT,
                () -> service.delete(actor("USER", "OWNER"), project.projectId()));
    }

    @Test
    void emptyProjectCanBeDeleted() {
        var project = store.add("owner", "empty", 3, 0, 0, 0, 0);

        service.delete(actor("USER", "OWNER"), project.projectId());

        assertThat(store.find(WORKSPACE, project.projectId())).isEmpty();
    }

    private static AccountWorkspaceStore.AccountRecord actor(String systemRole, String workspaceRole) {
        return new AccountWorkspaceStore.AccountRecord(
                UUID.randomUUID(), "actor", "Actor", WORKSPACE, "Workspace",
                systemRole, workspaceRole, "hash", false);
    }

    private static void assertStatus(HttpStatus status, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(status));
    }

    private static final class RecordingStore implements AdminProjectStore {
        private final Map<UUID, ManagedProject> projects = new LinkedHashMap<>();

        ManagedProject add(
                String owner, String repository, int priority,
                long releases, long sources, long watchers, long jobs) {
            UUID id = UUID.randomUUID();
            ManagedProject project = managed(id, owner, repository, priority, true,
                    releases, sources, watchers, jobs, NOW);
            projects.put(id, project);
            return project;
        }

        @Override public List<ManagedProject> list(UUID workspaceId) {
            return List.copyOf(projects.values());
        }

        @Override public Optional<ManagedProject> find(UUID workspaceId, UUID projectId) {
            return Optional.ofNullable(projects.get(projectId));
        }

        @Override
        public ManagedProject create(
                UUID projectId, UUID workspaceId, String owner, String repository,
                String canonicalUrl, int priority, int syncIntervalHours,
                List<String> chatAliases, Instant now) {
            ManagedProject project = managed(projectId, owner, repository, priority,
                    syncIntervalHours, chatAliases, true, 0, 0, 0, 0, now);
            projects.put(projectId, project);
            return project;
        }

        @Override
        public Optional<ManagedProject> update(
                UUID workspaceId, UUID projectId, String owner, String repository,
                String canonicalUrl, int priority, int syncIntervalHours,
                List<String> chatAliases, Instant now) {
            return find(workspaceId, projectId).map(project -> {
                ManagedProject updated = managed(projectId, owner, repository, priority,
                        syncIntervalHours, chatAliases, project.enabled(), project.releaseCount(),
                        project.knowledgeSourceCount(), project.watcherCount(),
                        project.activeJobCount(), now);
                projects.put(projectId, updated);
                return updated;
            });
        }

        @Override
        public Optional<ManagedProject> setEnabled(
                UUID workspaceId, UUID projectId, boolean enabled, Instant now) {
            return find(workspaceId, projectId).map(project -> {
                ManagedProject updated = managed(projectId, project.repositoryOwner(),
                        project.repositoryName(), project.priority(), project.syncIntervalHours(),
                        project.chatAliases(), enabled,
                        project.releaseCount(), project.knowledgeSourceCount(),
                        project.watcherCount(), project.activeJobCount(), now);
                projects.put(projectId, updated);
                return updated;
            });
        }

        @Override public DeleteResult deleteEmpty(UUID workspaceId, UUID projectId) {
            ManagedProject project = projects.get(projectId);
            if (project == null) return DeleteResult.NOT_FOUND;
            if (project.hasDependencies()) return DeleteResult.HAS_DEPENDENCIES;
            projects.remove(projectId);
            return DeleteResult.DELETED;
        }

        private static ManagedProject managed(
                UUID id, String owner, String repository, int priority, boolean enabled,
                long releases, long sources, long watchers, long jobs, Instant now) {
            return managed(id, owner, repository, priority, 6, List.of(), enabled,
                    releases, sources, watchers, jobs, now);
        }

        private static ManagedProject managed(
                UUID id, String owner, String repository, int priority,
                int syncIntervalHours, List<String> chatAliases, boolean enabled,
                long releases, long sources, long watchers, long jobs, Instant now) {
            return new ManagedProject(id, "github", owner, repository,
                    "https://github.com/" + owner + "/" + repository,
                    priority, syncIntervalHours, chatAliases, enabled, "NEVER", null, now, 0, null,
                    releases, sources, watchers, jobs, now, now);
        }
    }
}
