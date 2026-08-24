package com.jundaodsj.insightops.server.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.agent.application.AgentWorkflowProductStore;
import com.jundaodsj.insightops.agent.application.AgentWorkflowTemplateStore;
import com.jundaodsj.insightops.identity.application.ActorContext;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Service
public class AgentWorkflowProductService {

    private static final int EXPORT_SCHEMA_VERSION = 1;
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() { };

    private final AgentWorkflowTemplateStore templates;
    private final AgentWorkflowProductStore products;
    private final AgentWorkflowService workflows;
    private final AgentWorkflowExpressionService expressions;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();

    public AgentWorkflowProductService(
            AgentWorkflowTemplateStore templates,
            AgentWorkflowProductStore products,
            AgentWorkflowService workflows,
            AgentWorkflowExpressionService expressions,
            ObjectMapper json) {
        this.templates = templates;
        this.products = products;
        this.workflows = workflows;
        this.expressions = expressions;
        this.json = json;
    }

    public List<ParameterPresetView> presets(
            ActorContext actor, UUID templateId, UUID versionId) {
        requireVersion(actor.workspaceId(), templateId, versionId, true);
        return products.presets(actor.workspaceId(), actor.userId(), templateId, versionId)
                .stream().map(this::preset).toList();
    }

    public ParameterPresetView savePreset(
            ActorContext actor, UUID templateId, UUID versionId,
            String name, Map<String, Object> values) {
        AgentWorkflowTemplateStore.WorkflowVersion version = requireVersion(
                actor.workspaceId(), templateId, versionId, true);
        AgentWorkflowExpressionService.Graph graph = expressions.validateGraph(version.graphSpecJson());
        Map<String, Object> validated = expressions.validateInputs(
                graph, values == null ? Map.of() : values);
        Instant now = Instant.now();
        return preset(products.savePreset(new AgentWorkflowProductStore.PresetDraft(
                UUID.randomUUID(), actor.workspaceId(), actor.userId(), templateId, versionId,
                text(name, "Preset name", 80), write(validated), now)));
    }

    public void deletePreset(ActorContext actor, UUID presetId) {
        if (!products.deletePreset(actor.workspaceId(), actor.userId(), presetId)) {
            throw new WorkflowProductException("WORKFLOW_PRESET_NOT_FOUND");
        }
    }

    public ExportBundle exportBundle(UUID workspaceId, UUID templateId, UUID versionId) {
        AgentWorkflowTemplateStore.WorkflowTemplate template = requireTemplate(workspaceId, templateId);
        AgentWorkflowTemplateStore.WorkflowVersion version = template.versions().stream()
                .filter(item -> item.id().equals(versionId)).findFirst()
                .orElseThrow(() -> new WorkflowProductException("WORKFLOW_VERSION_NOT_FOUND"));
        return new ExportBundle(
                EXPORT_SCHEMA_VERSION, Instant.now(),
                new ExportTemplate(template.name(), template.description(), template.category()),
                new ExportVersion(version.version(), version.summary(), version.entryQuestion(),
                        readObject(version.graphSpecJson())));
    }

    public AgentWorkflowTemplateStore.WorkflowTemplate importBundle(
            UUID workspaceId, UUID userId, ExportBundle bundle, String importedName) {
        validateBundle(bundle);
        String name = importedName == null || importedName.isBlank()
                ? bundle.template().name() : importedName;
        return workflows.create(workspaceId, userId,
                new AgentWorkflowTemplateStore.TemplateDraft(
                        text(name, "Template name", 128),
                        optional(bundle.template().description(), 1_000),
                        text(bundle.template().category(), "Template category", 48),
                        new AgentWorkflowTemplateStore.VersionDraft(
                                optional(bundle.version().summary(), 500),
                                text(bundle.version().entryQuestion(), "Entry question", 4_000),
                                write(bundle.version().graph()))));
    }

    public CreatedShare createShare(
            UUID workspaceId, UUID userId, UUID templateId, UUID versionId, int expiresInDays) {
        requireVersion(workspaceId, templateId, versionId, false);
        int days = Math.max(1, Math.min(expiresInDays, 90));
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = Instant.now();
        AgentWorkflowProductStore.TemplateShare share = products.createShare(
                new AgentWorkflowProductStore.ShareDraft(
                        UUID.randomUUID(), workspaceId, templateId, versionId, hash(token),
                        now.plus(Duration.ofDays(days)), userId, now));
        return new CreatedShare(view(share), token);
    }

    public List<ShareView> shares(UUID workspaceId, UUID templateId) {
        requireTemplate(workspaceId, templateId);
        return products.shares(workspaceId, templateId).stream().map(this::view).toList();
    }

    public SharedPreview sharedPreview(String token) {
        AgentWorkflowProductStore.TemplateShare share = activeShare(token);
        return new SharedPreview(view(share), exportBundle(
                share.sourceWorkspaceId(), share.templateId(), share.templateVersionId()));
    }

    public AgentWorkflowTemplateStore.WorkflowTemplate importShare(
            UUID workspaceId, UUID userId, String token, String importedName) {
        AgentWorkflowProductStore.TemplateShare share = activeShare(token);
        ExportBundle bundle = exportBundle(
                share.sourceWorkspaceId(), share.templateId(), share.templateVersionId());
        AgentWorkflowTemplateStore.WorkflowTemplate imported = importBundle(
                workspaceId, userId, bundle, importedName);
        products.recordImport(share.id(), Instant.now());
        return imported;
    }

    public void revokeShare(UUID workspaceId, UUID shareId) {
        if (!products.revokeShare(workspaceId, shareId, Instant.now())) {
            throw new WorkflowProductException("WORKFLOW_SHARE_NOT_FOUND");
        }
    }

    public TemplateAnalytics analytics(UUID workspaceId, UUID templateId, int days) {
        requireTemplate(workspaceId, templateId);
        int window = Math.max(7, Math.min(days, 365));
        List<AgentWorkflowProductStore.WorkflowRunMetric> metrics = products.runMetrics(
                workspaceId, templateId, Instant.now().minus(Duration.ofDays(window)), 5_000);
        MetricAccumulator summary = new MetricAccumulator("all");
        Map<String, MetricAccumulator> daily = new TreeMap<>();
        Map<Integer, MetricAccumulator> versions = new TreeMap<>();
        for (AgentWorkflowProductStore.WorkflowRunMetric metric : metrics) {
            summary.add(metric);
            String day = metric.createdAt().atZone(ZoneOffset.UTC).toLocalDate().toString();
            daily.computeIfAbsent(day, MetricAccumulator::new).add(metric);
            versions.computeIfAbsent(metric.templateVersion(), value -> new MetricAccumulator("v" + value))
                    .add(metric);
        }
        List<RecentRun> recent = metrics.stream().limit(20).map(item -> new RecentRun(
                item.runId(), item.templateVersion(), item.status(), duration(item),
                item.totalTokens(), item.estimatedCostCny(), item.helpful(), item.createdAt())).toList();
        return new TemplateAnalytics(window, summary.view(),
                daily.values().stream().map(MetricAccumulator::view).toList(),
                versions.values().stream().map(MetricAccumulator::view).toList(), recent);
    }

    private AgentWorkflowTemplateStore.WorkflowTemplate requireTemplate(
            UUID workspaceId, UUID templateId) {
        return templates.find(workspaceId, templateId)
                .orElseThrow(() -> new WorkflowProductException("WORKFLOW_TEMPLATE_NOT_FOUND"));
    }

    private AgentWorkflowTemplateStore.WorkflowVersion requireVersion(
            UUID workspaceId, UUID templateId, UUID versionId, boolean activeOnly) {
        AgentWorkflowTemplateStore.WorkflowTemplate template = requireTemplate(workspaceId, templateId);
        if (activeOnly && !versionId.equals(template.activeVersionId())) {
            throw new WorkflowProductException("WORKFLOW_ACTIVE_VERSION_CHANGED");
        }
        return template.versions().stream().filter(item -> item.id().equals(versionId)).findFirst()
                .orElseThrow(() -> new WorkflowProductException("WORKFLOW_VERSION_NOT_FOUND"));
    }

    private AgentWorkflowProductStore.TemplateShare activeShare(String token) {
        String normalized = text(token, "Share token", 128);
        return products.findActiveShare(hash(normalized), Instant.now())
                .orElseThrow(() -> new WorkflowProductException("WORKFLOW_SHARE_INVALID"));
    }

    private void validateBundle(ExportBundle bundle) {
        if (bundle == null || bundle.schemaVersion() != EXPORT_SCHEMA_VERSION
                || bundle.template() == null || bundle.version() == null
                || bundle.version().graph() == null) {
            throw new WorkflowProductException("WORKFLOW_IMPORT_SCHEMA_INVALID");
        }
    }

    private ParameterPresetView preset(AgentWorkflowProductStore.ParameterPreset item) {
        return new ParameterPresetView(
                item.id(), item.templateId(), item.templateVersionId(), item.name(),
                readMap(item.valuesJson()), item.createdAt(), item.updatedAt());
    }

    private ShareView view(AgentWorkflowProductStore.TemplateShare item) {
        return new ShareView(item.id(), item.templateId(), item.templateVersionId(), item.status(),
                item.expiresAt(), item.createdAt(), item.revokedAt(), item.importCount(),
                item.lastImportedAt());
    }

    private Map<String, Object> readMap(String value) {
        try {
            return json.readValue(value, JSON_MAP);
        }
        catch (JsonProcessingException exception) {
            throw new WorkflowProductException("WORKFLOW_JSON_INVALID", exception);
        }
    }

    private Object readObject(String value) {
        try {
            return json.readValue(value, Object.class);
        }
        catch (JsonProcessingException exception) {
            throw new WorkflowProductException("WORKFLOW_JSON_INVALID", exception);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException exception) {
            throw new WorkflowProductException("WORKFLOW_SERIALIZATION_FAILED", exception);
        }
    }

    private static String hash(String token) {
        try {
            byte[] value = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(value);
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static long duration(AgentWorkflowProductStore.WorkflowRunMetric item) {
        if (item.startedAt() == null || item.finishedAt() == null) return 0;
        return Math.max(0, Duration.between(item.startedAt(), item.finishedAt()).toMillis());
    }

    private static String text(String value, String label, int max) {
        if (value == null || value.isBlank()) {
            throw new WorkflowProductException(label.toUpperCase().replace(' ', '_') + "_REQUIRED");
        }
        String normalized = value.strip();
        if (normalized.length() > max) throw new WorkflowProductException(label + " is too long");
        return normalized;
    }

    private static String optional(String value, int max) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.strip();
        if (normalized.length() > max) throw new WorkflowProductException("Text is too long");
        return normalized;
    }

    public record ParameterPresetView(
            UUID id, UUID templateId, UUID templateVersionId, String name,
            Map<String, Object> values, Instant createdAt, Instant updatedAt) {
    }

    public record ExportTemplate(String name, String description, String category) { }

    public record ExportVersion(
            int sourceVersion, String summary, String entryQuestion, Object graph) { }

    public record ExportBundle(
            int schemaVersion, Instant exportedAt, ExportTemplate template, ExportVersion version) { }

    public record ShareView(
            UUID id, UUID templateId, UUID templateVersionId, String status,
            Instant expiresAt, Instant createdAt, Instant revokedAt, int importCount,
            Instant lastImportedAt) {
    }

    public record CreatedShare(ShareView share, String token) { }

    public record SharedPreview(ShareView share, ExportBundle bundle) { }

    public record QualityMetric(
            String bucket, int runCount, int succeededCount, int failedCount,
            int cancelledCount, double successRate, long averageDurationMs,
            long totalTokens, BigDecimal estimatedCostCny, int feedbackCount,
            double helpfulRate, int citationCount, double citationCorrectRate,
            int nodeCount, double nodeSuccessRate) {
    }

    public record RecentRun(
            UUID runId, int templateVersion, String status, long durationMs,
            long totalTokens, BigDecimal estimatedCostCny, Boolean helpful, Instant createdAt) {
    }

    public record TemplateAnalytics(
            int windowDays, QualityMetric summary, List<QualityMetric> daily,
            List<QualityMetric> versions, List<RecentRun> recentRuns) {
    }

    private static final class MetricAccumulator {
        private final String bucket;
        private int runCount;
        private int succeeded;
        private int failed;
        private int cancelled;
        private long durationTotal;
        private int durationCount;
        private long tokens;
        private BigDecimal cost = BigDecimal.ZERO;
        private int feedbackCount;
        private int helpfulCount;
        private int citations;
        private int correctCitations;
        private int nodes;
        private int successfulNodes;

        private MetricAccumulator(String bucket) {
            this.bucket = bucket;
        }

        private void add(AgentWorkflowProductStore.WorkflowRunMetric item) {
            runCount++;
            if ("SUCCEEDED".equals(item.status())) succeeded++;
            else if ("FAILED".equals(item.status())) failed++;
            else if ("CANCELLED".equals(item.status())) cancelled++;
            long duration = duration(item);
            if (duration > 0) {
                durationTotal += duration;
                durationCount++;
            }
            tokens += item.totalTokens();
            cost = cost.add(item.estimatedCostCny() == null
                    ? BigDecimal.ZERO : item.estimatedCostCny());
            feedbackCount += item.feedbackCount();
            helpfulCount += item.helpfulCount();
            citations += item.citationCount();
            correctCitations += item.correctCitationCount();
            nodes += item.nodeCount();
            successfulNodes += item.successfulNodeCount();
        }

        private QualityMetric view() {
            return new QualityMetric(bucket, runCount, succeeded, failed, cancelled,
                    rate(succeeded, runCount), durationCount == 0 ? 0 : durationTotal / durationCount,
                    tokens, cost.setScale(6, RoundingMode.HALF_UP), feedbackCount,
                    rate(helpfulCount, feedbackCount), citations, rate(correctCitations, citations),
                    nodes, rate(successfulNodes, nodes));
        }

        private static double rate(int numerator, int denominator) {
            if (denominator == 0) return 0;
            return BigDecimal.valueOf((double) numerator / denominator)
                    .setScale(4, RoundingMode.HALF_UP).doubleValue();
        }
    }

    public static final class WorkflowProductException extends IllegalArgumentException {
        public WorkflowProductException(String message) { super(message); }
        public WorkflowProductException(String message, Throwable cause) { super(message, cause); }
    }
}
