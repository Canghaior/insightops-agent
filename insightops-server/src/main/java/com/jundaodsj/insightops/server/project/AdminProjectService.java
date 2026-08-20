package com.jundaodsj.insightops.server.project;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import com.jundaodsj.insightops.identity.application.AdminAccountStore;
import com.jundaodsj.insightops.project.application.AdminProjectStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
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
public class AdminProjectService {

    private static final String SYSTEM_ADMIN = "SYSTEM_ADMIN";
    private static final String OWNER = "OWNER";
    private static final Pattern REPOSITORY_OWNER =
            Pattern.compile("(?=.{1,39}$)[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*");
    private static final Pattern REPOSITORY_NAME = Pattern.compile("[A-Za-z0-9._-]{1,100}");

    private final AdminProjectStore store;
    private final AdminAccountStore auditStore;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public AdminProjectService(
            AdminProjectStore store,
            AdminAccountStore auditStore,
            ObjectMapper objectMapper) {
        this(store, auditStore, objectMapper, Clock.systemUTC());
    }

    AdminProjectService(
            AdminProjectStore store,
            AdminAccountStore auditStore,
            ObjectMapper objectMapper,
            Clock clock) {
        this.store = store;
        this.auditStore = auditStore;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public List<AdminProjectStore.ManagedProject> list(
            AccountWorkspaceStore.AccountRecord actor) {
        requireManager(actor);
        return store.list(actor.workspaceId());
    }

    @Transactional
    public AdminProjectStore.ManagedProject create(
            AccountWorkspaceStore.AccountRecord actor,
            String repositoryOwner,
            String repositoryName,
            int priority,
            int syncIntervalHours,
            List<String> chatAliases) {
        requireManager(actor);
        RepositoryCoordinates coordinates = validate(repositoryOwner, repositoryName, priority);
        List<String> aliases = validateAliases(chatAliases);
        validateInterval(syncIntervalHours);
        UUID projectId = UUID.randomUUID();
        Instant now = clock.instant();
        try {
            AdminProjectStore.ManagedProject created = store.create(
                    projectId,
                    actor.workspaceId(),
                    coordinates.owner(),
                    coordinates.repository(),
                    coordinates.canonicalUrl(),
                    priority,
                    syncIntervalHours,
                    aliases,
                    now);
            audit(actor, "PROJECT_CREATED", created);
            return created;
        } catch (DataIntegrityViolationException exception) {
            throw conflict("This GitHub repository is already tracked");
        }
    }

    public AdminProjectStore.ManagedProject create(
            AccountWorkspaceStore.AccountRecord actor, String repositoryOwner,
            String repositoryName, int priority) {
        return create(actor, repositoryOwner, repositoryName, priority, 6, List.of());
    }

    @Transactional
    public AdminProjectStore.ManagedProject update(
            AccountWorkspaceStore.AccountRecord actor,
            UUID projectId,
            String repositoryOwner,
            String repositoryName,
            int priority,
            int syncIntervalHours,
            List<String> chatAliases) {
        requireManager(actor);
        RepositoryCoordinates coordinates = validate(repositoryOwner, repositoryName, priority);
        List<String> aliases = validateAliases(chatAliases);
        validateInterval(syncIntervalHours);
        AdminProjectStore.ManagedProject existing = project(actor, projectId);
        boolean coordinatesChanged = !existing.repositoryOwner().equalsIgnoreCase(coordinates.owner())
                || !existing.repositoryName().equalsIgnoreCase(coordinates.repository());
        if (coordinatesChanged && existing.hasCollectedData()) {
            throw conflict("Repository coordinates cannot change after collection has produced data");
        }
        try {
            AdminProjectStore.ManagedProject updated = store.update(
                    actor.workspaceId(),
                    projectId,
                    coordinates.owner(),
                    coordinates.repository(),
                    coordinates.canonicalUrl(),
                    priority,
                    syncIntervalHours,
                    aliases,
                    clock.instant()).orElseThrow(AdminProjectService::notFound);
            audit(actor, "PROJECT_UPDATED", updated);
            return updated;
        } catch (DataIntegrityViolationException exception) {
            throw conflict("This GitHub repository is already tracked");
        }
    }

    public AdminProjectStore.ManagedProject update(
            AccountWorkspaceStore.AccountRecord actor, UUID projectId,
            String repositoryOwner, String repositoryName, int priority) {
        AdminProjectStore.ManagedProject existing = project(actor, projectId);
        return update(actor, projectId, repositoryOwner, repositoryName, priority,
                existing.syncIntervalHours(), existing.chatAliases());
    }

    @Transactional
    public AdminProjectStore.ManagedProject setEnabled(
            AccountWorkspaceStore.AccountRecord actor, UUID projectId, boolean enabled) {
        requireManager(actor);
        project(actor, projectId);
        AdminProjectStore.ManagedProject updated = store.setEnabled(
                actor.workspaceId(), projectId, enabled, clock.instant())
                .orElseThrow(AdminProjectService::notFound);
        audit(actor, enabled ? "PROJECT_ENABLED" : "PROJECT_DISABLED", updated);
        return updated;
    }

    @Transactional
    public void delete(AccountWorkspaceStore.AccountRecord actor, UUID projectId) {
        requireManager(actor);
        AdminProjectStore.ManagedProject existing = project(actor, projectId);
        AdminProjectStore.DeleteResult result = store.deleteEmpty(actor.workspaceId(), projectId);
        if (result == AdminProjectStore.DeleteResult.NOT_FOUND) throw notFound();
        if (result == AdminProjectStore.DeleteResult.HAS_DEPENDENCIES) {
            throw conflict("Projects with collected data, watchers, or jobs cannot be deleted; disable the project instead");
        }
        audit(actor, "PROJECT_DELETED", existing);
    }

    private AdminProjectStore.ManagedProject project(
            AccountWorkspaceStore.AccountRecord actor, UUID projectId) {
        return store.find(actor.workspaceId(), projectId).orElseThrow(AdminProjectService::notFound);
    }

    private static RepositoryCoordinates validate(String owner, String repository, int priority) {
        String normalizedOwner = owner == null ? "" : owner.trim();
        String normalizedRepository = repository == null ? "" : repository.trim();
        if (!REPOSITORY_OWNER.matcher(normalizedOwner).matches()) {
            throw badRequest("GitHub owner must be 1-39 letters, digits, or hyphens");
        }
        if (!REPOSITORY_NAME.matcher(normalizedRepository).matches()
                || normalizedRepository.equals(".") || normalizedRepository.equals("..")) {
            throw badRequest("GitHub repository must be 1-100 letters, digits, '.', '_' or '-'");
        }
        if (priority < 1 || priority > 5) {
            throw badRequest("Priority must be between 1 and 5");
        }
        normalizedOwner = normalizedOwner.toLowerCase(java.util.Locale.ROOT);
        normalizedRepository = normalizedRepository.toLowerCase(java.util.Locale.ROOT);
        return new RepositoryCoordinates(
                normalizedOwner,
                normalizedRepository,
                "https://github.com/" + normalizedOwner + "/" + normalizedRepository);
    }

    private void audit(
            AccountWorkspaceStore.AccountRecord actor,
            String action,
            AdminProjectStore.ManagedProject project) {
        try {
            String details = objectMapper.writeValueAsString(Map.of(
                    "projectId", project.projectId(),
                    "repository", project.repositoryOwner() + "/" + project.repositoryName(),
                    "priority", project.priority(),
                    "syncIntervalHours", project.syncIntervalHours(),
                    "chatAliases", project.chatAliases(),
                    "enabled", project.enabled()));
            auditStore.appendAudit(UUID.randomUUID(), actor.workspaceId(), actor.userId(), null,
                    action, details, clock.instant());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize project audit details", exception);
        }
    }

    private static void requireManager(AccountWorkspaceStore.AccountRecord actor) {
        if (!SYSTEM_ADMIN.equals(actor.systemRole()) && !OWNER.equals(actor.role())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Project administration requires an owner or system administrator");
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static void validateInterval(int interval) {
        if (interval < 1 || interval > 720) {
            throw badRequest("Collection interval must be between 1 and 720 hours");
        }
    }

    private static List<String> validateAliases(List<String> input) {
        if (input == null) return List.of();
        if (input.size() > 20) throw badRequest("At most 20 chat aliases are allowed");
        List<String> aliases = input.stream()
                .map(value -> value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT))
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        if (aliases.stream().anyMatch(value -> value.length() < 2 || value.length() > 80)) {
            throw badRequest("Chat aliases must contain 2-80 characters");
        }
        return aliases;
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Project was not found in this workspace");
    }

    private record RepositoryCoordinates(String owner, String repository, String canonicalUrl) {
    }
}
