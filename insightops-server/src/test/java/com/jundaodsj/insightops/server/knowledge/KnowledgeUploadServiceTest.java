package com.jundaodsj.insightops.server.knowledge;

import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import com.jundaodsj.insightops.infrastructure.knowledge.KnowledgeUploadProperties;
import com.jundaodsj.insightops.knowledge.application.KnowledgeFileStorage;
import com.jundaodsj.insightops.knowledge.application.KnowledgeUploadQuotaExceededException;
import com.jundaodsj.insightops.knowledge.application.KnowledgeStore;
import com.jundaodsj.insightops.knowledge.application.KnowledgeUploadStore;
import com.jundaodsj.insightops.project.application.AdminProjectStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeUploadServiceTest {
    private static final UUID WORKSPACE = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private final KnowledgeUploadStore store = mock(KnowledgeUploadStore.class);
    private final KnowledgeStore knowledgeStore = mock(KnowledgeStore.class);
    private final KnowledgeFileStorage storage = mock(KnowledgeFileStorage.class);
    private final AdminProjectStore projects = mock(AdminProjectStore.class);
    private KnowledgeUploadService service;

    @BeforeEach
    void setUp() {
        KnowledgeUploadProperties properties = new KnowledgeUploadProperties();
        properties.setMaxFileBytes(1_000);
        properties.setWorkspaceQuotaBytes(2_000);
        service = new KnowledgeUploadService(store, knowledgeStore, storage, properties, projects);
        when(projects.find(WORKSPACE, PROJECT)).thenReturn(Optional.of(project()));
        when(store.workspaceBytes(WORKSPACE)).thenReturn(0L);
    }

    @Test
    void storesMarkdownAndPersistsWorkspaceVisibility() throws Exception {
        byte[] content = "# Team guide\n\nUse the approved agent workflow.".getBytes(StandardCharsets.UTF_8);
        when(storage.store(any(), any(), eq(1_000L)))
                .thenReturn(new KnowledgeFileStorage.StoredFile("upload.bin", content.length, "abc123"));
        when(store.create(any(), any())).thenAnswer(invocation -> record(invocation.getArgument(0)));

        var result = service.upload(actor(), PROJECT, "workspace",
                new MockMultipartFile("file", "../team-guide.md", "text/plain", content));

        ArgumentCaptor<KnowledgeUploadStore.CreateUpload> command =
                ArgumentCaptor.forClass(KnowledgeUploadStore.CreateUpload.class);
        verify(store).create(command.capture(), any(Instant.class));
        assertThat(command.getValue().originalName()).isEqualTo("team-guide.md");
        assertThat(command.getValue().mediaType()).isEqualTo("text/markdown");
        assertThat(command.getValue().visibility()).isEqualTo("WORKSPACE");
        assertThat(result.status()).isEqualTo("PENDING");
    }

    @Test
    void rejectsPdfWhoseSignatureDoesNotMatchExtension() throws Exception {
        var file = new MockMultipartFile("file", "guide.pdf", "application/pdf",
                "this is not a pdf".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.upload(actor(), PROJECT, "PRIVATE", file))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(storage, never()).store(any(), any(), any(Long.class));
    }

    @Test
    void rejectsUploadThatWouldExceedWorkspaceQuota() throws Exception {
        when(store.workspaceBytes(WORKSPACE)).thenReturn(1_999L);
        var file = new MockMultipartFile("file", "guide.txt", "text/plain", "two".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.upload(actor(), PROJECT, "PRIVATE", file))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(507));
        verify(storage, never()).store(any(), any(), any(Long.class));
    }

    @Test
    void removesStoredFileWhenDatabaseCreateFails() throws Exception {
        byte[] content = "valid text".getBytes(StandardCharsets.UTF_8);
        when(storage.store(any(), any(), eq(1_000L)))
                .thenReturn(new KnowledgeFileStorage.StoredFile("orphan.bin", content.length, "abc123"));
        when(store.create(any(), any())).thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service.upload(actor(), PROJECT, "PRIVATE",
                new MockMultipartFile("file", "guide.txt", "text/plain", content)))
                .isInstanceOf(IllegalStateException.class);
        verify(storage).delete("orphan.bin");
    }

    @Test
    void cleansStoredFileWhenTransactionalQuotaCheckLosesRace() throws Exception {
        byte[] content = "valid concurrent upload".getBytes(StandardCharsets.UTF_8);
        when(storage.store(any(), any(), eq(1_000L)))
                .thenReturn(new KnowledgeFileStorage.StoredFile("raced.bin", content.length, "abc123"));
        when(store.create(any(), any())).thenThrow(new KnowledgeUploadQuotaExceededException());

        assertThatThrownBy(() -> service.upload(actor(), PROJECT, "PRIVATE",
                new MockMultipartFile("file", "guide.txt", "text/plain", content)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> {
                            assertThat(error.getStatusCode().value()).isEqualTo(507);
                            assertThat(error.getReason()).isEqualTo("Workspace upload quota is exhausted");
                        });
        verify(storage).delete("raced.bin");
    }

    private static AccountWorkspaceStore.AccountRecord actor() {
        return new AccountWorkspaceStore.AccountRecord(USER, "member", "Member", WORKSPACE,
                "Workspace", "USER", "MEMBER", "hash", false);
    }

    private static AdminProjectStore.ManagedProject project() {
        Instant now = Instant.parse("2026-08-20T00:00:00Z");
        return new AdminProjectStore.ManagedProject(PROJECT, "github", "acme", "agent",
                "https://github.com/acme/agent", 3, true, "NEVER", null, now,
                0, null, 0, 0, 0, 0, now, now);
    }

    private static KnowledgeUploadStore.UploadRecord record(KnowledgeUploadStore.CreateUpload command) {
        Instant now = Instant.parse("2026-08-20T00:00:00Z");
        return new KnowledgeUploadStore.UploadRecord(command.uploadId(), command.sourceId(), command.projectId(),
                "agent", command.uploadedBy(), "Member", command.originalName(), command.mediaType(),
                command.byteSize(), command.sha256(), command.visibility(), "PENDING", 0,
                null, null, null, null, now, now);
    }
}
