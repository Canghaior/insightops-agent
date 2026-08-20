package com.jundaodsj.insightops.server.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import com.jundaodsj.insightops.identity.application.AdminAccountStore;
import com.jundaodsj.insightops.knowledge.application.KnowledgeStore;
import com.jundaodsj.insightops.project.application.AdminProjectStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminKnowledgeSourceServiceTest {
    private static final UUID WORKSPACE = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");
    private final KnowledgeStore store = mock(KnowledgeStore.class);
    private final AdminProjectStore projects = mock(AdminProjectStore.class);
    private final AdminAccountStore audits = mock(AdminAccountStore.class);
    private AdminKnowledgeSourceService service;

    @BeforeEach
    void setUp() {
        service = new AdminKnowledgeSourceService(store, projects, audits, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(projects.find(WORKSPACE, PROJECT)).thenReturn(Optional.of(project()));
        when(store.createSource(any(), eq(NOW))).thenAnswer(invocation ->
                status(invocation.getArgument(0)));
    }

    @Test
    void createsPublicHttpsSourceWithDerivedBoundaryHostAndFrequency() {
        KnowledgeStore.SourceStatus created = service.create(admin(), PROJECT, "Acme Docs",
                "official_documentation", "https://docs.acme.dev/guide/",
                "https://docs.acme.dev/sitemap.xml", "/guide", 8);

        assertThat(created.allowedHost()).isEqualTo("docs.acme.dev");
        assertThat(created.allowedPathPrefix()).isEqualTo("/guide/");
        assertThat(created.syncIntervalHours()).isEqualTo(8);
        verify(audits).appendAudit(any(), eq(WORKSPACE), any(), eq(null),
                eq("KNOWLEDGE_SOURCE_CREATED"), any(), eq(NOW));
    }

    @Test
    void rejectsPrivateOrInsecureSourceUrlsBeforePersistence() {
        assertBadRequest(() -> service.create(admin(), PROJECT, "Internal", "OFFICIAL_DOCUMENTATION",
                "http://docs.acme.dev/", "http://docs.acme.dev/", "/", 24));
        assertBadRequest(() -> service.create(admin(), PROJECT, "Internal", "OFFICIAL_DOCUMENTATION",
                "https://127.0.0.1/docs/", "https://127.0.0.1/docs/", "/docs/", 24));
    }

    @Test
    void rejectsDiscoveryOnAnotherHostAndRootOutsideBoundary() {
        assertBadRequest(() -> service.create(admin(), PROJECT, "Bad host", "OFFICIAL_DOCUMENTATION",
                "https://docs.acme.dev/guide/", "https://evil.example/sitemap.xml", "/guide/", 24));
        assertBadRequest(() -> service.create(admin(), PROJECT, "Bad path", "OFFICIAL_DOCUMENTATION",
                "https://docs.acme.dev/other/", "https://docs.acme.dev/sitemap.xml", "/guide/", 24));
    }

    @Test
    void requiresSystemAdministratorForKnowledgeSourceChanges() {
        assertThatThrownBy(() -> service.create(member(), PROJECT, "Acme Docs",
                "OFFICIAL_DOCUMENTATION", "https://docs.acme.dev/guide/",
                "https://docs.acme.dev/sitemap.xml", "/guide/", 24))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void keepsCollectionBoundaryImmutableAfterDocumentsWereCollected() {
        UUID sourceId = UUID.randomUUID();
        KnowledgeStore.SourceDefinition definition = new KnowledgeStore.SourceDefinition(
                sourceId, WORKSPACE, PROJECT, "acme-docs", "Acme Docs",
                "OFFICIAL_DOCUMENTATION", "https://docs.acme.dev/guide/",
                "https://docs.acme.dev/sitemap.xml", "docs.acme.dev", "/guide/",
                "T1_PROJECT_DOMAIN", 24);
        when(store.sourceStatus(WORKSPACE)).thenReturn(List.of(status(definition, 1)));

        assertThatThrownBy(() -> service.update(admin(), sourceId, PROJECT, "Acme Docs",
                "OFFICIAL_DOCUMENTATION", "https://docs.acme.dev/reference/",
                "https://docs.acme.dev/sitemap.xml", "/reference/", 24))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    private static void assertBadRequest(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private static AccountWorkspaceStore.AccountRecord admin() {
        return new AccountWorkspaceStore.AccountRecord(UUID.randomUUID(), "admin", "Admin",
                WORKSPACE, "Workspace", "SYSTEM_ADMIN", "OWNER", "hash", false);
    }

    private static AccountWorkspaceStore.AccountRecord member() {
        return new AccountWorkspaceStore.AccountRecord(UUID.randomUUID(), "member", "Member",
                WORKSPACE, "Workspace", "USER", "MEMBER", "hash", false);
    }

    private static AdminProjectStore.ManagedProject project() {
        return new AdminProjectStore.ManagedProject(PROJECT, "github", "acme", "agent",
                "https://github.com/acme/agent", 3, true, "NEVER", null, NOW,
                0, null, 0, 0, 0, 0, NOW, NOW);
    }

    private static KnowledgeStore.SourceStatus status(KnowledgeStore.SourceDefinition source) {
        return status(source, 0);
    }

    private static KnowledgeStore.SourceStatus status(KnowledgeStore.SourceDefinition source,
                                                       long documentCount) {
        return new KnowledgeStore.SourceStatus(source.sourceId(), source.projectId(), "agent",
                source.sourceKey(), source.name(), source.sourceType(), source.rootUrl(),
                source.discoveryUrl(), source.allowedHost(), source.allowedPathPrefix(),
                source.trustTier(), source.syncIntervalHours(), true, "NEVER", null, NOW,
                0, null, documentCount, 0, 0, null, null);
    }
}
