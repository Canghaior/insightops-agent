package com.jundaodsj.insightops.server.knowledge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import com.jundaodsj.insightops.identity.application.AdminAccountStore;
import com.jundaodsj.insightops.knowledge.application.KnowledgeStore;
import com.jundaodsj.insightops.project.application.AdminProjectStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AdminKnowledgeSourceService {
    private static final Set<String> SOURCE_TYPES = Set.of(
            "OFFICIAL_DOCUMENTATION", "MIGRATION_GUIDE", "OFFICIAL_RELEASE_NOTES",
            "OFFICIAL_BLOG_RSS", "OFFICIAL_ROADMAP");
    private final KnowledgeStore store;
    private final AdminProjectStore projectStore;
    private final AdminAccountStore auditStore;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public AdminKnowledgeSourceService(KnowledgeStore store, AdminProjectStore projectStore,
                                       AdminAccountStore auditStore, ObjectMapper objectMapper) {
        this(store, projectStore, auditStore, objectMapper, Clock.systemUTC());
    }

    AdminKnowledgeSourceService(KnowledgeStore store, AdminProjectStore projectStore,
                                AdminAccountStore auditStore, ObjectMapper objectMapper, Clock clock) {
        this.store = store;
        this.projectStore = projectStore;
        this.auditStore = auditStore;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public KnowledgeStore.SourceStatus create(AccountWorkspaceStore.AccountRecord actor,
                                               UUID projectId, String name, String sourceType,
                                               String rootUrl, String discoveryUrl,
                                               String allowedPathPrefix, int syncIntervalHours) {
        requireAdmin(actor);
        projectStore.find(actor.workspaceId(), projectId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Project was not found in this workspace"));
        UUID sourceId = UUID.randomUUID();
        KnowledgeStore.SourceDefinition definition = definition(sourceId, actor.workspaceId(), projectId,
                "custom-" + sourceId.toString().substring(0, 12), name, sourceType,
                rootUrl, discoveryUrl, allowedPathPrefix, syncIntervalHours);
        try {
            KnowledgeStore.SourceStatus created = store.createSource(definition, clock.instant());
            audit(actor, "KNOWLEDGE_SOURCE_CREATED", created);
            return created;
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A knowledge source with this root URL already exists", exception);
        }
    }

    @Transactional
    public KnowledgeStore.SourceStatus update(AccountWorkspaceStore.AccountRecord actor, UUID sourceId,
                                               UUID projectId, String name, String sourceType,
                                               String rootUrl, String discoveryUrl,
                                               String allowedPathPrefix, int syncIntervalHours) {
        requireAdmin(actor);
        KnowledgeStore.SourceStatus existing = source(actor.workspaceId(), sourceId);
        projectStore.find(actor.workspaceId(), projectId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Project was not found in this workspace"));
        KnowledgeStore.SourceDefinition definition = definition(sourceId, actor.workspaceId(), projectId,
                existing.sourceKey(), name, sourceType, rootUrl, discoveryUrl,
                allowedPathPrefix, syncIntervalHours);
        boolean boundaryChanged = !existing.projectId().equals(projectId)
                || !existing.rootUrl().equals(definition.rootUrl())
                || !existing.allowedHost().equals(definition.allowedHost())
                || !existing.allowedPathPrefix().equals(definition.allowedPathPrefix());
        if (boundaryChanged && existing.documentCount() > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Project and crawl boundary cannot change after documents were collected");
        }
        try {
            KnowledgeStore.SourceStatus updated = store.updateSource(
                    actor.workspaceId(), sourceId, definition, clock.instant())
                    .orElseThrow(() -> conflictOrNotFound(existing));
            audit(actor, "KNOWLEDGE_SOURCE_UPDATED", updated);
            return updated;
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A knowledge source with this root URL already exists", exception);
        }
    }

    @Transactional
    public KnowledgeStore.SourceStatus setEnabled(AccountWorkspaceStore.AccountRecord actor,
                                                   UUID sourceId, boolean enabled) {
        requireAdmin(actor);
        KnowledgeStore.SourceStatus existing = source(actor.workspaceId(), sourceId);
        KnowledgeStore.SourceStatus updated = store.setSourceEnabled(
                actor.workspaceId(), sourceId, enabled, clock.instant())
                .orElseThrow(() -> conflictOrNotFound(existing));
        audit(actor, enabled ? "KNOWLEDGE_SOURCE_ENABLED" : "KNOWLEDGE_SOURCE_DISABLED", updated);
        return updated;
    }

    @Transactional
    public void delete(AccountWorkspaceStore.AccountRecord actor, UUID sourceId) {
        requireAdmin(actor);
        KnowledgeStore.SourceStatus existing = source(actor.workspaceId(), sourceId);
        KnowledgeStore.DeleteResult result = store.deleteEmptySource(actor.workspaceId(), sourceId);
        if (result == KnowledgeStore.DeleteResult.HAS_DEPENDENCIES) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Sources with documents or an active collection job cannot be deleted; disable the source instead");
        }
        if (result == KnowledgeStore.DeleteResult.NOT_FOUND) throw notFound();
        audit(actor, "KNOWLEDGE_SOURCE_DELETED", existing);
    }

    private KnowledgeStore.SourceDefinition definition(UUID sourceId, UUID workspaceId, UUID projectId,
                                                       String sourceKey, String name, String sourceType,
                                                       String rootUrl, String discoveryUrl,
                                                       String pathPrefix, int interval) {
        String normalizedName = name == null ? "" : name.trim();
        if (normalizedName.isBlank() || normalizedName.length() > 256) {
            throw badRequest("Source name must contain 1-256 characters");
        }
        String normalizedType = sourceType == null ? "" : sourceType.trim().toUpperCase(Locale.ROOT);
        if (!SOURCE_TYPES.contains(normalizedType)) throw badRequest("Unsupported knowledge source type");
        if (interval < 1 || interval > 720) {
            throw badRequest("Collection interval must be between 1 and 720 hours");
        }
        URI root = secureHttps(rootUrl);
        URI discovery = secureHttps(discoveryUrl);
        if (!root.getHost().equalsIgnoreCase(discovery.getHost())) {
            throw badRequest("Root URL and discovery URL must use the same host");
        }
        String prefix = normalizePrefix(pathPrefix);
        if (!normalizedPath(root).startsWith(prefix)) {
            throw badRequest("Root URL must be inside the allowed path prefix");
        }
        return new KnowledgeStore.SourceDefinition(sourceId, workspaceId, projectId, sourceKey,
                normalizedName, normalizedType, canonical(root), canonical(discovery),
                root.getHost().toLowerCase(Locale.ROOT), prefix, "T1_PROJECT_DOMAIN", interval);
    }

    private static URI secureHttps(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim()).normalize();
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host.isBlank()
                    || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                    || host.equals("localhost") || host.endsWith(".local") || privateLiteral(host)) {
                throw badRequest("Knowledge source URLs must use a public HTTPS host");
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw badRequest("Knowledge source URL is invalid");
        }
    }

    private static boolean privateLiteral(String host) {
        if (host.equals("::1") || host.startsWith("fc") || host.startsWith("fd")
                || host.startsWith("fe8") || host.startsWith("fe9")
                || host.startsWith("fea") || host.startsWith("feb")) return true;
        String[] parts = host.split("\\.");
        if (parts.length != 4) return false;
        try {
            int first = Integer.parseInt(parts[0]);
            int second = Integer.parseInt(parts[1]);
            return first == 0 || first == 10 || first == 127
                    || (first == 169 && second == 254)
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static String normalizePrefix(String value) {
        String prefix = value == null ? "" : value.trim();
        if (prefix.isBlank() || !prefix.startsWith("/") || prefix.contains("..")) {
            throw badRequest("Allowed path prefix must be an absolute path without '..'");
        }
        return prefix.endsWith("/") ? prefix : prefix + "/";
    }

    private static String normalizedPath(URI uri) {
        String path = uri.getPath();
        if (path == null || path.isBlank()) return "/";
        return path.endsWith("/") ? path : path + "/";
    }

    private static String canonical(URI uri) {
        String path = uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath();
        return URI.create(uri.getScheme().toLowerCase(Locale.ROOT) + "://"
                + uri.getHost().toLowerCase(Locale.ROOT) + path).toASCIIString();
    }

    private KnowledgeStore.SourceStatus source(UUID workspaceId, UUID sourceId) {
        return store.sourceStatus(workspaceId).stream()
                .filter(source -> source.sourceId().equals(sourceId))
                .findFirst().orElseThrow(AdminKnowledgeSourceService::notFound);
    }

    private void audit(AccountWorkspaceStore.AccountRecord actor, String action,
                       KnowledgeStore.SourceStatus source) {
        try {
            String details = objectMapper.writeValueAsString(Map.of(
                    "sourceId", source.sourceId(), "sourceKey", source.sourceKey(),
                    "rootUrl", source.rootUrl(), "syncIntervalHours", source.syncIntervalHours(),
                    "enabled", source.enabled()));
            auditStore.appendAudit(UUID.randomUUID(), actor.workspaceId(), actor.userId(), null,
                    action, details, clock.instant());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize knowledge source audit", exception);
        }
    }

    private static void requireAdmin(AccountWorkspaceStore.AccountRecord actor) {
        if (!"SYSTEM_ADMIN".equals(actor.systemRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Knowledge administration requires a system administrator");
        }
    }

    private static ResponseStatusException conflictOrNotFound(KnowledgeStore.SourceStatus source) {
        return "RUNNING".equals(source.status())
                ? new ResponseStatusException(HttpStatus.CONFLICT, "A running knowledge source cannot be changed")
                : notFound();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Knowledge source was not found in this workspace");
    }
}
