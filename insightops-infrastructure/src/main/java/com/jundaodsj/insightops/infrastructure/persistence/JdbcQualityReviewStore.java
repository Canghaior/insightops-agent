package com.jundaodsj.insightops.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.knowledge.application.QualityReviewStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcQualityReviewStore implements QualityReviewStore {
    private static final String BASE_DATASET = "p1-rag-questions-v3-50";
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcQualityReviewStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional(readOnly = true)
    public FeedbackPage listFeedback(UUID workspaceId, int page, int size, String status, String type) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, size));
        String safeStatus = filter(status, List.of("PENDING", "REVIEWED", "ADDED_TO_EVAL", "DISMISSED"));
        String safeType = filter(type, List.of("ANSWER", "CITATION"));
        String base = feedbackUnion();
        String conditions = " where workspace_id=:workspaceId"
                + " and (:status='ALL' or review_status=:status)"
                + " and (:type='ALL' or feedback_type=:type)";
        long total = jdbc.sql("select count(*) from (" + base + ") feedback" + conditions)
                .param("workspaceId", workspaceId).param("status", safeStatus).param("type", safeType)
                .query(Long.class).single();
        List<FeedbackItem> items = jdbc.sql("select * from (" + base + ") feedback" + conditions
                        + " order by created_at desc, id limit :limit offset :offset")
                .param("workspaceId", workspaceId).param("status", safeStatus).param("type", safeType)
                .param("limit", safeSize).param("offset", safePage * safeSize)
                .query(this::feedback).list();
        return new FeedbackPage(items, total, safePage, safeSize);
    }

    @Override
    @Transactional
    public Optional<FeedbackItem> reviewFeedback(UUID workspaceId, UUID reviewerId, UUID feedbackId,
                                                  String type, ReviewCommand command, Instant now) {
        String safeType = required(type, List.of("ANSWER", "CITATION"), "Unsupported feedback type");
        String decision = required(command.decision(), List.of("REVIEWED", "DISMISSED", "ADD_TO_EVAL"),
                "Unsupported review decision");
        String table = "ANSWER".equals(safeType) ? "research_answer_feedback" : "research_citation_feedback";
        FeedbackSource source = jdbc.sql("""
                select feedback.id, run.question
                from %s feedback
                join agent_run run on run.id=feedback.run_id
                where feedback.id=:id and feedback.workspace_id=:workspaceId
                for update
                """.formatted(table))
                .param("id", feedbackId).param("workspaceId", workspaceId)
                .query((rs, rowNum) -> new FeedbackSource(
                        rs.getObject("id", UUID.class), rs.getString("question"))).optional()
                .orElse(null);
        if (source == null) return Optional.empty();
        if ("ADD_TO_EVAL".equals(decision)) {
            String existingStatus = jdbc.sql("""
                    select status from rag_evaluation_candidate
                    where source_feedback_type=:type and source_feedback_id=:feedbackId
                    """)
                    .param("type", safeType).param("feedbackId", feedbackId)
                    .query(String.class).optional().orElse(null);
            if (existingStatus != null && !"DRAFT".equals(existingStatus)) {
                throw new IllegalStateException(
                        "This feedback is already linked to a reviewed evaluation candidate");
            }
        }
        String storedStatus = "ADD_TO_EVAL".equals(decision) ? "ADDED_TO_EVAL" : decision;
        jdbc.sql("update " + table + " set review_status=:status, reviewer_user_id=:reviewerId,"
                        + " reviewer_note=:note, reviewed_at=:now, updated_at=:now where id=:id")
                .param("status", storedStatus).param("reviewerId", reviewerId)
                .param("note", clean(command.note(), 1000)).param("now", timestamp(now))
                .param("id", feedbackId).update();
        if ("ADD_TO_EVAL".equals(decision)) {
            CandidateCommand candidate = command.candidate();
            if (candidate == null) throw new IllegalArgumentException("Evaluation candidate is required");
            CandidateCommand valid = validateCandidate(candidate, source.question());
            jdbc.sql("""
                    insert into rag_evaluation_candidate
                        (id,workspace_id,source_feedback_type,source_feedback_id,status,question,
                         expected_answerable,expected_project,category,must_hit_terms,
                         answer_must_include,source_domain,reviewer_user_id,reviewer_note,created_at,updated_at)
                    values (:id,:workspaceId,:type,:feedbackId,'DRAFT',:question,:answerable,
                            :project,:category,:terms,:answerTerms,:domain,:reviewerId,:note,:now,:now)
                    on conflict (source_feedback_type,source_feedback_id) do update set
                        question=excluded.question, expected_answerable=excluded.expected_answerable,
                        expected_project=excluded.expected_project, category=excluded.category,
                        must_hit_terms=excluded.must_hit_terms,
                        answer_must_include=excluded.answer_must_include,
                        source_domain=excluded.source_domain, reviewer_user_id=excluded.reviewer_user_id,
                        reviewer_note=excluded.reviewer_note, updated_at=excluded.updated_at
                    where rag_evaluation_candidate.status='DRAFT'
                    """)
                    .param("id", UUID.randomUUID()).param("workspaceId", workspaceId)
                    .param("type", safeType).param("feedbackId", feedbackId)
                    .param("question", valid.question()).param("answerable", valid.expectedAnswerable())
                    .param("project", valid.expectedProject()).param("category", valid.category())
                    .param("terms", valid.mustHitTerms().toArray(String[]::new))
                    .param("answerTerms", valid.answerMustInclude().toArray(String[]::new))
                    .param("domain", valid.sourceDomain()).param("reviewerId", reviewerId)
                    .param("note", clean(command.note(), 1000)).param("now", timestamp(now)).update();
        }
        return feedbackById(workspaceId, feedbackId, safeType);
    }

    @Override
    @Transactional(readOnly = true)
    public CandidatePage listCandidates(UUID workspaceId, int page, int size, String status) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, size));
        String safeStatus = filter(status, List.of("DRAFT", "APPROVED", "REJECTED", "INCLUDED"));
        String where = " where candidate.workspace_id=:workspaceId"
                + " and (:status='ALL' or candidate.status=:status)";
        long total = jdbc.sql("select count(*) from rag_evaluation_candidate candidate" + where)
                .param("workspaceId", workspaceId).param("status", safeStatus)
                .query(Long.class).single();
        List<EvaluationCandidate> items = jdbc.sql(candidateSelect() + where
                        + " order by candidate.created_at desc limit :limit offset :offset")
                .param("workspaceId", workspaceId).param("status", safeStatus)
                .param("limit", safeSize).param("offset", safePage * safeSize)
                .query(this::candidate).list();
        return new CandidatePage(items, total, safePage, safeSize);
    }

    @Override
    @Transactional
    public Optional<EvaluationCandidate> updateCandidate(UUID workspaceId, UUID candidateId,
                                                          CandidateCommand command, Instant now) {
        CandidateCommand valid = validateCandidate(command, null);
        int changed = jdbc.sql("""
                update rag_evaluation_candidate set question=:question,
                    expected_answerable=:answerable, expected_project=:project,
                    category=:category, must_hit_terms=:terms,
                    answer_must_include=:answerTerms, source_domain=:domain, updated_at=:now
                where id=:id and workspace_id=:workspaceId and status='DRAFT'
                """)
                .param("question", valid.question()).param("answerable", valid.expectedAnswerable())
                .param("project", valid.expectedProject()).param("category", valid.category())
                .param("terms", valid.mustHitTerms().toArray(String[]::new))
                .param("answerTerms", valid.answerMustInclude().toArray(String[]::new))
                .param("domain", valid.sourceDomain()).param("now", timestamp(now))
                .param("id", candidateId).param("workspaceId", workspaceId).update();
        return changed == 0 ? Optional.empty() : candidateById(workspaceId, candidateId);
    }

    @Override
    @Transactional
    public Optional<EvaluationCandidate> decideCandidate(UUID workspaceId, UUID reviewerId,
                                                          UUID candidateId, String decision,
                                                          String note, Instant now) {
        String safeDecision = required(decision, List.of("APPROVED", "REJECTED"),
                "Unsupported candidate decision");
        int changed = jdbc.sql("""
                update rag_evaluation_candidate set status=:status, reviewer_user_id=:reviewerId,
                    reviewer_note=:note, updated_at=:now
                where id=:id and workspace_id=:workspaceId and status='DRAFT'
                """)
                .param("status", safeDecision).param("reviewerId", reviewerId)
                .param("note", clean(note, 1000)).param("now", timestamp(now))
                .param("id", candidateId).param("workspaceId", workspaceId).update();
        return changed == 0 ? Optional.empty() : candidateById(workspaceId, candidateId);
    }

    @Override
    @Transactional
    public DatasetVersion createVersion(UUID workspaceId, UUID creatorId, String name,
                                        List<UUID> candidateIds, Instant now) {
        String safeName = requiredText(name, 128, "Dataset version name is required");
        lockWorkspace(workspaceId);
        if (BASE_DATASET.equalsIgnoreCase(safeName)) {
            throw new IllegalArgumentException("The base dataset name is reserved");
        }
        boolean duplicateName = jdbc.sql("""
                select exists(select 1 from rag_dataset_version
                    where workspace_id=:workspaceId and lower(name)=lower(:name))
                """).param("workspaceId", workspaceId).param("name", safeName)
                .query(Boolean.class).single();
        if (duplicateName) throw new IllegalArgumentException("Dataset version name already exists");
        List<UUID> uniqueIds = candidateIds == null ? List.of()
                : List.copyOf(new LinkedHashSet<>(candidateIds));
        if (uniqueIds.isEmpty()) throw new IllegalArgumentException("Select at least one approved candidate");
        List<EvaluationCandidate> candidates = jdbc.sql(candidateSelect() + """
                where candidate.workspace_id=:workspaceId and candidate.status='APPROVED'
                  and candidate.id in (:ids)
                order by candidate.created_at
                """)
                .param("workspaceId", workspaceId).param("ids", uniqueIds)
                .query(this::candidate).list();
        if (candidates.size() != uniqueIds.size()) {
            throw new IllegalArgumentException("Every selected candidate must be approved and belong to this workspace");
        }
        int versionNumber = jdbc.sql("""
                select coalesce(max(version_number),0)+1 from rag_dataset_version
                where workspace_id=:workspaceId
                """).param("workspaceId", workspaceId).query(Integer.class).single();
        UUID versionId = UUID.randomUUID();
        jdbc.sql("""
                insert into rag_dataset_version
                    (id,workspace_id,version_number,name,status,base_dataset_name,
                     candidate_count,created_by,created_at)
                values (:id,:workspaceId,:versionNumber,:name,'DRAFT',:baseDataset,
                        :candidateCount,:creatorId,:now)
                """)
                .param("id", versionId).param("workspaceId", workspaceId)
                .param("versionNumber", versionNumber).param("name", safeName)
                .param("baseDataset", BASE_DATASET).param("candidateCount", candidates.size())
                .param("creatorId", creatorId).param("now", timestamp(now)).update();
        for (EvaluationCandidate candidate : candidates) {
            String caseKey = "feedback-" + candidate.id().toString().substring(0, 12);
            jdbc.sql("""
                    insert into rag_dataset_version_case
                        (version_id,candidate_id,case_key,question,expected_answerable,
                         expected_project,category,must_hit_terms,answer_must_include,source_domain)
                    values (:versionId,:candidateId,:caseKey,:question,:answerable,:project,
                            :category,:terms,:answerTerms,:domain)
                    """)
                    .param("versionId", versionId).param("candidateId", candidate.id())
                    .param("caseKey", caseKey).param("question", candidate.question())
                    .param("answerable", candidate.expectedAnswerable())
                    .param("project", candidate.expectedProject()).param("category", candidate.category())
                    .param("terms", candidate.mustHitTerms().toArray(String[]::new))
                    .param("answerTerms", candidate.answerMustInclude().toArray(String[]::new))
                    .param("domain", candidate.sourceDomain()).update();
        }
        jdbc.sql("""
                update rag_evaluation_candidate set status='INCLUDED', dataset_version_id=:versionId,
                    updated_at=:now where workspace_id=:workspaceId and id in (:ids)
                """).param("versionId", versionId).param("now", timestamp(now))
                .param("workspaceId", workspaceId).param("ids", uniqueIds).update();
        return versionById(workspaceId, versionId).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DatasetVersion> listVersions(UUID workspaceId) {
        return jdbc.sql(versionSelect() + " where version.workspace_id=:workspaceId order by version.version_number desc")
                .param("workspaceId", workspaceId).query(this::version).list();
    }

    @Override
    @Transactional
    public Optional<DatasetVersion> activateVersion(UUID workspaceId, UUID reviewerId,
                                                    UUID versionId, Instant now) {
        lockWorkspace(workspaceId);
        DatasetVersion version = versionByIdForUpdate(workspaceId, versionId).orElse(null);
        if (version == null) return Optional.empty();
        if (!"DRAFT".equals(version.status())) {
            throw new IllegalStateException("Only a draft dataset version can be activated");
        }
        GateRun latestRun = jdbc.sql("""
                select id,status from rag_evaluation_run
                where workspace_id=:workspaceId and dataset_name=:datasetName
                order by started_at desc,id desc limit 1
                """).param("workspaceId", workspaceId).param("datasetName", version.name())
                .query((rs, rowNum) -> new GateRun(
                        rs.getObject("id", UUID.class), rs.getString("status"))).optional()
                .orElseThrow(() -> new IllegalStateException(
                        "This dataset version must pass the RAG evaluation gate before activation"));
        if (!"PASSED".equals(latestRun.status())) {
            throw new IllegalStateException(
                    "The latest RAG evaluation run must pass before this dataset version can be activated");
        }
        jdbc.sql("""
                update rag_dataset_version set status='RETIRED'
                where workspace_id=:workspaceId and status='ACTIVE'
                """).param("workspaceId", workspaceId).update();
        jdbc.sql("""
                update rag_dataset_version set status='ACTIVE', gate_run_id=:runId,
                    activated_by=:reviewerId, activated_at=:now where id=:id
                """).param("runId", latestRun.id()).param("reviewerId", reviewerId)
                .param("now", timestamp(now)).param("id", versionId).update();
        return versionById(workspaceId, versionId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DatasetSelection> datasetSelection(UUID workspaceId, UUID versionId) {
        Optional<DatasetVersion> version = versionId == null
                ? jdbc.sql(versionSelect() + " where version.workspace_id=:workspaceId and version.status='ACTIVE'")
                    .param("workspaceId", workspaceId).query(this::version).optional()
                : versionById(workspaceId, versionId);
        if (version.isEmpty()) return Optional.empty();
        DatasetVersion selected = version.orElseThrow();
        List<DatasetCase> cases = jdbc.sql("""
                select case_key,question,expected_answerable,expected_project,category,
                       must_hit_terms,answer_must_include,source_domain
                from rag_dataset_version_case where version_id=:versionId order by case_key
                """).param("versionId", selected.id())
                .query((rs, rowNum) -> new DatasetCase(
                        rs.getString("case_key"), rs.getString("question"),
                        rs.getBoolean("expected_answerable"), rs.getString("expected_project"),
                        rs.getString("category"), strings(rs.getArray("must_hit_terms")),
                        strings(rs.getArray("answer_must_include")), rs.getString("source_domain"),
                        "verified")).list();
        return Optional.of(new DatasetSelection(selected.name(), selected.id(), cases));
    }

    private Optional<FeedbackItem> feedbackById(UUID workspaceId, UUID id, String type) {
        return jdbc.sql("select * from (" + feedbackUnion() + ") feedback"
                        + " where workspace_id=:workspaceId and id=:id and feedback_type=:type")
                .param("workspaceId", workspaceId).param("id", id).param("type", type)
                .query(this::feedback).optional();
    }

    private void lockWorkspace(UUID workspaceId) {
        jdbc.sql("select id from workspace where id=:workspaceId for update")
                .param("workspaceId", workspaceId).query(UUID.class).single();
    }

    private Optional<EvaluationCandidate> candidateById(UUID workspaceId, UUID id) {
        return jdbc.sql(candidateSelect() + " where candidate.workspace_id=:workspaceId and candidate.id=:id")
                .param("workspaceId", workspaceId).param("id", id).query(this::candidate).optional();
    }

    private Optional<DatasetVersion> versionById(UUID workspaceId, UUID id) {
        return jdbc.sql(versionSelect() + " where version.workspace_id=:workspaceId and version.id=:id")
                .param("workspaceId", workspaceId).param("id", id).query(this::version).optional();
    }

    private Optional<DatasetVersion> versionByIdForUpdate(UUID workspaceId, UUID id) {
        return jdbc.sql("""
                        select version.*,version.gate_run_id as effective_gate_run_id,
                               null::varchar as gate_status
                        from rag_dataset_version version
                        where version.workspace_id=:workspaceId and version.id=:id
                        for update
                        """)
                .param("workspaceId", workspaceId).param("id", id).query(this::version).optional();
    }

    private FeedbackItem feedback(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new FeedbackItem(
                rs.getObject("id", UUID.class), rs.getString("feedback_type"),
                rs.getString("review_status"), rs.getObject("user_id", UUID.class),
                rs.getString("username"), rs.getString("display_name"),
                rs.getObject("run_id", UUID.class), rs.getObject("session_id", UUID.class),
                rs.getString("trace_id"), rs.getString("question"), rs.getString("answer"),
                rs.getString("model_provider"), rs.getString("model_name"),
                readList(rs.getString("citations")), (Boolean) rs.getObject("helpful"),
                rs.getString("reason"), rs.getString("comment"), rs.getString("citation_url"),
                (Boolean) rs.getObject("citation_correct"), rs.getString("reviewer_note"),
                rs.getString("reviewer_display_name"), instant(rs.getObject("created_at", OffsetDateTime.class)),
                instant(rs.getObject("reviewed_at", OffsetDateTime.class)),
                rs.getObject("candidate_id", UUID.class));
    }

    private EvaluationCandidate candidate(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new EvaluationCandidate(
                rs.getObject("id", UUID.class), rs.getString("source_feedback_type"),
                rs.getObject("source_feedback_id", UUID.class), rs.getString("status"),
                rs.getString("question"), rs.getBoolean("expected_answerable"),
                rs.getString("expected_project"), rs.getString("category"),
                strings(rs.getArray("must_hit_terms")), strings(rs.getArray("answer_must_include")),
                rs.getString("source_domain"), rs.getString("reviewer_note"),
                rs.getString("reviewer_display_name"), rs.getObject("dataset_version_id", UUID.class),
                instant(rs.getObject("created_at", OffsetDateTime.class)),
                instant(rs.getObject("updated_at", OffsetDateTime.class)));
    }

    private DatasetVersion version(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new DatasetVersion(
                rs.getObject("id", UUID.class), rs.getInt("version_number"), rs.getString("name"),
                rs.getString("status"), rs.getString("base_dataset_name"),
                rs.getInt("candidate_count"), rs.getObject("effective_gate_run_id", UUID.class),
                rs.getString("gate_status"), instant(rs.getObject("created_at", OffsetDateTime.class)),
                instant(rs.getObject("activated_at", OffsetDateTime.class)));
    }

    private String feedbackUnion() {
        return """
                select feedback.id,'ANSWER'::varchar as feedback_type,feedback.review_status,
                       feedback.user_id,user_account.username,user_account.display_name,
                       feedback.run_id,run.session_id,run.trace_id,run.question,run.answer,
                       run.model_provider,run.model_name,run.citations::text as citations,
                       feedback.helpful,feedback.reason,feedback.comment,
                       null::varchar as citation_url,null::boolean as citation_correct,
                       feedback.reviewer_note,reviewer.display_name as reviewer_display_name,
                       feedback.created_at,feedback.reviewed_at,candidate.id as candidate_id,
                       feedback.workspace_id
                from research_answer_feedback feedback
                join app_user user_account on user_account.id=feedback.user_id
                join agent_run run on run.id=feedback.run_id
                left join app_user reviewer on reviewer.id=feedback.reviewer_user_id
                left join rag_evaluation_candidate candidate
                  on candidate.source_feedback_type='ANSWER' and candidate.source_feedback_id=feedback.id
                union all
                select feedback.id,'CITATION'::varchar as feedback_type,feedback.review_status,
                       feedback.user_id,user_account.username,user_account.display_name,
                       feedback.run_id,run.session_id,run.trace_id,run.question,run.answer,
                       run.model_provider,run.model_name,run.citations::text as citations,
                       null::boolean as helpful,null::varchar as reason,feedback.comment,
                       feedback.citation_url,feedback.correct as citation_correct,
                       feedback.reviewer_note,reviewer.display_name as reviewer_display_name,
                       feedback.created_at,feedback.reviewed_at,candidate.id as candidate_id,
                       feedback.workspace_id
                from research_citation_feedback feedback
                join app_user user_account on user_account.id=feedback.user_id
                join agent_run run on run.id=feedback.run_id
                left join app_user reviewer on reviewer.id=feedback.reviewer_user_id
                left join rag_evaluation_candidate candidate
                  on candidate.source_feedback_type='CITATION' and candidate.source_feedback_id=feedback.id
                """;
    }

    private static String candidateSelect() {
        return """
                select candidate.*,reviewer.display_name as reviewer_display_name
                from rag_evaluation_candidate candidate
                left join app_user reviewer on reviewer.id=candidate.reviewer_user_id
                """;
    }

    private static String versionSelect() {
        return """
                select version.*,coalesce(latest_gate.id,gate.id) as effective_gate_run_id,
                       coalesce(latest_gate.status,gate.status) as gate_status
                from rag_dataset_version version
                left join rag_evaluation_run gate on gate.id=version.gate_run_id
                left join lateral (
                    select run.id,run.status from rag_evaluation_run run
                    where run.workspace_id=version.workspace_id and run.dataset_name=version.name
                    order by run.started_at desc limit 1
                ) latest_gate on true
                """;
    }

    private CandidateCommand validateCandidate(CandidateCommand command, String fallbackQuestion) {
        if (command == null) throw new IllegalArgumentException("Evaluation candidate is required");
        String question = requiredText(command.question() == null ? fallbackQuestion : command.question(),
                4000, "Question is required");
        String project = clean(command.expectedProject(), 128);
        if (command.expectedAnswerable() && (project == null || project.isBlank())) {
            throw new IllegalArgumentException("Expected project is required for an answerable case");
        }
        if (!command.expectedAnswerable()) project = null;
        String category = requiredText(command.category(), 64, "Category is required");
        List<String> terms = cleanList(command.mustHitTerms(), 20, 120);
        List<String> answerTerms = cleanList(command.answerMustInclude(), 20, 120);
        if (command.expectedAnswerable() && terms.isEmpty()) {
            throw new IllegalArgumentException("At least one retrieval term is required");
        }
        if (command.expectedAnswerable() && answerTerms.isEmpty()) {
            throw new IllegalArgumentException("At least one expected answer term is required");
        }
        String domain = command.expectedAnswerable() ? clean(command.sourceDomain(), 255) : null;
        if (command.expectedAnswerable()
                && (domain == null || !domain.matches("(?i)^[a-z0-9.-]+$"))) {
            throw new IllegalArgumentException("A valid source domain is required for an answerable case");
        }
        return new CandidateCommand(question, command.expectedAnswerable(), project, category,
                terms, answerTerms, domain);
    }

    private List<String> readList(String value) {
        try { return value == null ? List.of() : json.readValue(value, new TypeReference<List<String>>() { }); }
        catch (Exception exception) { throw new IllegalStateException("Stored citations are invalid JSON", exception); }
    }

    private static List<String> strings(Array array) {
        if (array == null) return List.of();
        try {
            Object[] values = (Object[]) array.getArray();
            List<String> result = new ArrayList<>(values.length);
            for (Object value : values) if (value != null) result.add(value.toString());
            return List.copyOf(result);
        }
        catch (Exception exception) { throw new IllegalStateException("Unable to read text array", exception); }
    }

    private static String filter(String value, List<String> accepted) {
        if (value == null || value.isBlank()) return "ALL";
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return accepted.contains(normalized) ? normalized : "ALL";
    }

    private static String required(String value, List<String> accepted, String message) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!accepted.contains(normalized)) throw new IllegalArgumentException(message);
        return normalized;
    }

    private static String requiredText(String value, int max, String message) {
        String clean = clean(value, max);
        if (clean == null || clean.isBlank()) throw new IllegalArgumentException(message);
        return clean;
    }

    private static List<String> cleanList(List<String> values, int maxItems, int maxLength) {
        if (values == null) return List.of();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            String safe = clean(value, maxLength);
            if (safe != null && !safe.isBlank()) unique.add(safe);
            if (unique.size() >= maxItems) break;
        }
        return List.copyOf(unique);
    }

    private static String clean(String value, int max) {
        if (value == null) return null;
        String safe = value.replace("\u0000", "").trim();
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    private static OffsetDateTime timestamp(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private record FeedbackSource(UUID id, String question) { }

    private record GateRun(UUID id, String status) { }
}
