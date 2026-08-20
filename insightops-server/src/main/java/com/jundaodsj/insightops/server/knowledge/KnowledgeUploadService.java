package com.jundaodsj.insightops.server.knowledge;

import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import com.jundaodsj.insightops.infrastructure.knowledge.KnowledgeUploadProperties;
import com.jundaodsj.insightops.knowledge.application.KnowledgeFileStorage;
import com.jundaodsj.insightops.knowledge.application.KnowledgeStore;
import com.jundaodsj.insightops.knowledge.application.KnowledgeUploadQuotaExceededException;
import com.jundaodsj.insightops.knowledge.application.KnowledgeUploadStore;
import com.jundaodsj.insightops.project.application.AdminProjectStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class KnowledgeUploadService {
    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeUploadService.class);
    private static final Map<String, String> MEDIA_TYPES = Map.of(
            "pdf", "application/pdf", "md", "text/markdown", "markdown", "text/markdown", "txt", "text/plain");
    private final KnowledgeUploadStore store;
    private final KnowledgeStore knowledgeStore;
    private final KnowledgeFileStorage storage;
    private final KnowledgeUploadProperties properties;
    private final AdminProjectStore projectStore;

    public KnowledgeUploadService(KnowledgeUploadStore store, KnowledgeStore knowledgeStore,
                                  KnowledgeFileStorage storage, KnowledgeUploadProperties properties,
                                  AdminProjectStore projectStore) {
        this.store = store; this.knowledgeStore = knowledgeStore; this.storage = storage;
        this.properties = properties; this.projectStore = projectStore;
    }

    public List<KnowledgeUploadStore.UploadRecord> list(AccountWorkspaceStore.AccountRecord actor) {
        return store.listVisible(actor.workspaceId(), actor.userId(), isAdmin(actor));
    }

    public KnowledgeUploadStore.UploadRecord upload(AccountWorkspaceStore.AccountRecord actor,
                                                     UUID projectId, String visibility,
                                                     MultipartFile file) {
        projectStore.find(actor.workspaceId(), projectId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Project was not found in this workspace"));
        String name = safeName(file.getOriginalFilename());
        String mediaType = mediaType(name);
        String normalizedVisibility = visibility == null ? "PRIVATE" : visibility.strip().toUpperCase(Locale.ROOT);
        if (!normalizedVisibility.equals("PRIVATE") && !normalizedVisibility.equals("WORKSPACE")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Visibility must be PRIVATE or WORKSPACE");
        }
        long declared = file.getSize();
        if (declared < 1 || declared > properties.getMaxFileBytes()) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "File must contain 1-20 MB of data");
        }
        long used = store.workspaceBytes(actor.workspaceId());
        long quota = properties.getWorkspaceQuotaBytes();
        if (quota < 1 || used > quota || declared > quota - used) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(507), "Workspace upload quota is exhausted");
        }
        validateSignature(file, mediaType);
        UUID uploadId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        KnowledgeFileStorage.StoredFile stored;
        try {
            stored = storage.store(uploadId, file.getInputStream(), properties.getMaxFileBytes());
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to store uploaded file", exception);
        }
        try {
            return store.create(new KnowledgeUploadStore.CreateUpload(uploadId, sourceId,
                    actor.workspaceId(), projectId, actor.userId(), name, stored.storageKey(),
                    mediaType, stored.byteSize(), stored.sha256(), normalizedVisibility,
                    quota), Instant.now());
        } catch (RuntimeException exception) {
            try { storage.delete(stored.storageKey()); }
            catch (IOException cleanupFailure) { exception.addSuppressed(cleanupFailure); }
            if (exception instanceof KnowledgeUploadQuotaExceededException) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(507), exception.getMessage(), exception);
            }
            throw exception;
        }
    }

    public void retry(AccountWorkspaceStore.AccountRecord actor, UUID uploadId) {
        var upload = visible(actor, uploadId);
        if (!upload.uploadedBy().equals(actor.userId()) && !isAdmin(actor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the uploader can retry this file");
        }
        if (!knowledgeStore.requestSync(actor.workspaceId(), upload.sourceId(), Instant.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Upload is disabled or already processing");
        }
    }

    public void delete(AccountWorkspaceStore.AccountRecord actor, UUID uploadId) {
        var target = store.delete(actor.workspaceId(), actor.userId(), isAdmin(actor), uploadId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Upload was not found"));
        try { storage.delete(target.storageKey()); }
        catch (IOException exception) { LOGGER.warn("Unable to remove orphaned upload {}", target.storageKey(), exception); }
    }

    public Download download(AccountWorkspaceStore.AccountRecord actor, UUID uploadId) {
        var upload = visible(actor, uploadId);
        try {
            return new Download(upload.originalName(), upload.mediaType(), upload.byteSize(),
                    storage.open(uploadId + ".bin"));
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Stored upload is unavailable", exception);
        }
    }

    private KnowledgeUploadStore.UploadRecord visible(AccountWorkspaceStore.AccountRecord actor, UUID uploadId) {
        return store.findVisible(actor.workspaceId(), actor.userId(), isAdmin(actor), uploadId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Upload was not found"));
    }

    private static void validateSignature(MultipartFile file, String mediaType) {
        if (!mediaType.equals("application/pdf")) return;
        try (InputStream input = file.getInputStream()) {
            byte[] signature = input.readNBytes(5);
            if (signature.length != 5 || !new String(signature, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PDF signature does not match the file extension");
            }
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to inspect uploaded file", exception);
        }
    }

    private static String safeName(String value) {
        String name = value == null ? "" : value.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        if (name.isBlank() || name.length() > 255 || name.indexOf('\u0000') >= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File name must contain 1-255 safe characters");
        }
        return name;
    }

    private static String mediaType(String name) {
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String value = MEDIA_TYPES.get(extension);
        if (value == null) throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Only PDF, Markdown and TXT files are supported");
        return value;
    }

    private static boolean isAdmin(AccountWorkspaceStore.AccountRecord actor) {
        return "SYSTEM_ADMIN".equals(actor.systemRole());
    }

    public record Download(String fileName, String mediaType, long byteSize, InputStream input) { }
}
