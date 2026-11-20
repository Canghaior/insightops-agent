package com.jundaodsj.insightops.server.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.report.application.BuildEvidence.*;
import com.jundaodsj.insightops.report.application.BuildEvidenceStore;
import com.jundaodsj.insightops.report.application.ReportReviewStore;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import static com.jundaodsj.insightops.server.workflow.ResearchProfiles.text;

@Service
public class BuildEvidenceService {
    private static final int MAX_XML = 40, MAX_FILE = 2 * 1024 * 1024, MAX_TOTAL = 12 * 1024 * 1024;
    private static final Map<String, FileKind> PARTS = Map.of("xmlFiles", FileKind.SUREFIRE_XML,
            "mavenLog", FileKind.MAVEN_LOG, "commandRecord", FileKind.COMMAND_RECORD,
            "exclusionManifest", FileKind.EXCLUSION_MANIFEST);

    public record ImportCommand(UUID requestId, Role role, String label, String environment, String executedAt,
                                String executedBy, Integer exitCode, String sourceNote) {}

    private final ReportReviewStore reports;
    private final BuildEvidenceStore store;
    private final ObjectMapper json;
    private final BuildEvidenceDocuments documents;

    public BuildEvidenceService(ReportReviewStore reports, BuildEvidenceStore store, ObjectMapper objectMapper) {
        this.reports = reports;
        this.store = store;
        this.json = objectMapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
        this.json.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxDocumentLength(MAX_FILE).maxNestingDepth(32).maxTokenCount(50_000)
                .maxNameLength(256).maxStringLength(128 * 1024).build());
        this.documents = new BuildEvidenceDocuments(json);
    }

    @Transactional(readOnly = true)
    public List<Summary> list(ActorContext actor, UUID reportId) {
        return store.list(actor, system(actor, reportId));
    }

    @Transactional(readOnly = true)
    public Batch require(ActorContext actor, UUID reportId, UUID batchId) {
        return find(actor, system(actor, reportId), batchId);
    }

    @Transactional(readOnly = true)
    public Original download(ActorContext actor, UUID reportId, UUID batchId, UUID fileId) {
        return store.original(actor, system(actor, reportId), batchId, fileId)
                .orElseThrow(() -> notFound("未找到当前系统可下载的工程原件。"));
    }

    @Transactional
    public Batch importBatch(ActorContext actor, UUID reportId, String declaration,
                             Map<String, List<MultipartFile>> parts) {
        UUID system = system(actor, reportId);
        ImportCommand command = command(declaration);
        Metadata meta = new Metadata(optional(command.label(), 180), optional(command.environment(), 1000),
                BuildEvidenceDocuments.timestamp(optional(command.executedAt(), 100), "执行时间"),
                optional(command.executedBy(), 180), command.exitCode(), optional(command.sourceNote(), 2000));
        List<Original> originals = originals(parts);
        List<List<String>> identities = originals.stream().map(Original::file)
                .map(f -> List.of(f.kind().name(), f.name(), f.sha256())).toList();
        String fingerprint;
        try { fingerprint = sha(json.writeValueAsBytes(List.of(reportId, command.role(), meta, identities))); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("无法计算导入标识。", ex); }
        var previous = store.request(actor, system, command.requestId());
        if (previous.isPresent()) return replay(actor, system, previous.get(), fingerprint);

        var header = new Summary(UUID.randomUUID(), system, reportId, command.role(), meta.label(),
                Instant.now().truncatedTo(ChronoUnit.MICROS), null);
        Batch batch = documents.analyze(header, meta, originals);
        if (store.insert(actor, command.requestId(), fingerprint, batch, originals)) return batch;
        // ON CONFLICT waits for the concurrent import transaction. Its receipt and originals are now committed.
        return replay(actor, system, store.request(actor, system, command.requestId()).orElseThrow(), fingerprint);
    }

    private UUID system(ActorContext actor, UUID reportId) {
        return reports.context(actor, reportId, false).orElseThrow(() -> notFound("未找到可导入工程证据的系统报告。")).systemId();
    }

    private Batch find(ActorContext actor, UUID system, UUID batchId) {
        return store.find(actor, system, batchId).orElseThrow(() -> notFound("未找到当前系统的构建证据。"));
    }

    private Batch replay(ActorContext actor, UUID system, BuildEvidenceStore.Receipt receipt, String hash) {
        if (!receipt.fingerprint().equals(hash)) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "此提交标识已用于不同的构建材料，请核对已保存的批次。");
        return find(actor, system, receipt.id());
    }

    private ImportCommand command(String source) {
        if (source == null || source.length() > 10_000) throw new IllegalArgumentException("导入字段缺失或过长。");
        try {
            var input = json.readTree(source);
            if (input == null || !input.isObject()) throw new IllegalArgumentException("导入声明应为 JSON 对象。");
            for (String field : List.of("requestId","role","label","environment","executedAt","executedBy","sourceNote")) {
                if (input.hasNonNull(field) && !input.get(field).isTextual())
                    throw new IllegalArgumentException(field + " 需要文本值。");
            }
            if (input.hasNonNull("exitCode") && (!input.get("exitCode").isIntegralNumber() || !input.get("exitCode").canConvertToInt()))
                throw new IllegalArgumentException("退出码应为整数。");
            ImportCommand cmd = json.treeToValue(input, ImportCommand.class);
            if (cmd == null || cmd.role() == null || cmd.requestId() == null)
                throw new IllegalArgumentException("请选择基线或候选构建，并提供提交标识。");
            return cmd;
        } catch (JsonProcessingException ex) { throw new IllegalArgumentException("导入字段或数据类型无效。"); }
    }

    private static List<Original> originals(Map<String, List<MultipartFile>> parts) {
        int xmlCount = parts.getOrDefault("xmlFiles", List.of()).size();
        if (xmlCount < 1 || xmlCount > MAX_XML)
            throw new IllegalArgumentException("请选择 1 至 " + MAX_XML + " 份普通 Surefire TEST-*.xml。");
        List<Original> items = new ArrayList<>();
        HashSet<String> names = new HashSet<>();
        long total = 0;
        for (var entry : parts.entrySet()) {
            FileKind kind = PARTS.get(entry.getKey());
            if (kind == null) throw new IllegalArgumentException("存在不支持的原件字段。");
            if (kind != FileKind.SUREFIRE_XML && entry.getValue().size() != 1)
                throw new IllegalArgumentException("每批只能选择一份日志、命令记录和排除方法清单。");
            for (MultipartFile upload : entry.getValue()) {
                String name = fileName(upload.getOriginalFilename(), kind);
                if (!names.add(name.toLowerCase(Locale.ROOT)))
                    throw new IllegalArgumentException(name + "：同一批含重复文件名，请按模块、按次导入。");
                long size = upload.getSize();
                if (size < 1 || size > MAX_FILE) throw new IllegalArgumentException(name + "：单个原件须为 1 字节至 2 MiB。");
                if (total + size > MAX_TOTAL) throw new IllegalArgumentException("每批原件合计最多 12 MiB。");
                total += size;
                try (var stream = upload.getInputStream()) {
                    byte[] data = stream.readNBytes((int) size + 1);
                    if (data.length != size) throw new IllegalArgumentException(name + "：读取长度与提交长度不符。");
                    var file = new EvidenceFile(UUID.randomUUID(), name, kind, data.length, sha(data));
                    items.add(new Original(file, data));
                } catch (IOException ex) { throw new IllegalArgumentException(name + "：原件读取失败。"); }
            }
        }
        // File order sent by the browser is immaterial to a retried request.
        items.sort(Comparator.comparing((Original x) -> x.file().kind())
                .thenComparing(x -> x.file().name(), String.CASE_INSENSITIVE_ORDER).thenComparing(x -> x.file().name()));
        return items;
    }

    private static String fileName(String name, FileKind kind) {
        if (name == null || name.isBlank() || name.length() > 240 || !name.equals(name.strip())
                || name.chars().anyMatch(c -> Character.isISOControl(c) || "\\/:*?\"<>|".indexOf(c) >= 0)
                || name.codePoints().anyMatch(c -> Character.getType(c) == Character.FORMAT))
            throw new IllegalArgumentException("原件需要普通文件名，请直接选择文件，不要提交目录路径。");
        String lower = name.toLowerCase(Locale.ROOT);
        boolean valid = switch (kind) {
            case SUREFIRE_XML -> name.startsWith("TEST-") && lower.endsWith(".xml") && name.length() > 9;
            case MAVEN_LOG -> lower.endsWith(".txt") || lower.endsWith(".log");
            case COMMAND_RECORD, EXCLUSION_MANIFEST -> lower.endsWith(".json");
        };
        if (!valid) throw new IllegalArgumentException(name + "：该字段不支持此文件名或格式。");
        return name;
    }

    private static String optional(String value, int max) {
        String normalized = text(value, max, false);
        return normalized.isEmpty() ? null : normalized;
    }

    private static String sha(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
