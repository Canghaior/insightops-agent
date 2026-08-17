package com.jundaodsj.insightops.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.knowledge.application.RagEvaluationStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcRagEvaluationStore implements RagEvaluationStore {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcRagEvaluationStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void start(UUID runId, UUID workspaceId, String datasetName, int caseCount,
                      int generationSampleSize, Instant startedAt) {
        jdbc.sql("""
                insert into rag_evaluation_run
                    (id, workspace_id, dataset_name, status, case_count,
                     generation_sample_size, started_at)
                values (:id, :workspaceId, :datasetName, 'RUNNING', :caseCount,
                        :generationSampleSize, :startedAt)
                """)
                .param("id", runId)
                .param("workspaceId", workspaceId)
                .param("datasetName", datasetName)
                .param("caseCount", caseCount)
                .param("generationSampleSize", generationSampleSize)
                .param("startedAt", timestamp(startedAt))
                .update();
    }

    @Override
    public void saveCase(UUID runId, CaseResult result) {
        jdbc.sql("""
                insert into rag_evaluation_case
                    (id, run_id, case_key, question, expected_answerable, expected_project,
                     predicted_answerable, answerability_correct, project_hit, reciprocal_rank,
                     term_coverage, retrieval_mode, top_projects, source_urls,
                     citation_precision, citation_coverage, faithfulness, judge_reason,
                     generated_answer)
                values
                    (:id, :runId, :caseKey, :question, :expectedAnswerable, :expectedProject,
                     :predictedAnswerable, :answerabilityCorrect, :projectHit, :reciprocalRank,
                     :termCoverage, :retrievalMode, cast(:topProjects as jsonb),
                     cast(:sourceUrls as jsonb), :citationPrecision, :citationCoverage,
                     :faithfulness, :judgeReason, :generatedAnswer)
                """)
                .param("id", UUID.randomUUID())
                .param("runId", runId)
                .param("caseKey", result.caseKey())
                .param("question", result.question())
                .param("expectedAnswerable", result.expectedAnswerable())
                .param("expectedProject", result.expectedProject())
                .param("predictedAnswerable", result.predictedAnswerable())
                .param("answerabilityCorrect", result.answerabilityCorrect())
                .param("projectHit", result.projectHit())
                .param("reciprocalRank", result.reciprocalRank())
                .param("termCoverage", result.termCoverage())
                .param("retrievalMode", result.retrievalMode())
                .param("topProjects", write(result.topProjects()))
                .param("sourceUrls", write(result.sourceUrls()))
                .param("citationPrecision", result.citationPrecision())
                .param("citationCoverage", result.citationCoverage())
                .param("faithfulness", result.faithfulness())
                .param("judgeReason", result.judgeReason())
                .param("generatedAnswer", result.generatedAnswer())
                .update();
    }

    @Override
    public void complete(UUID runId, Summary summary, Instant finishedAt) {
        jdbc.sql("""
                update rag_evaluation_run
                set status=:status, recall_at_k=:recallAtK,
                    mean_reciprocal_rank=:mrr, project_hit_rate=:projectHitRate,
                    term_coverage=:termCoverage, no_answer_accuracy=:noAnswerAccuracy,
                    citation_precision=:citationPrecision, citation_coverage=:citationCoverage,
                    faithfulness=:faithfulness, model_name=:modelName, finished_at=:finishedAt
                where id=:id and status='RUNNING'
                """)
                .param("status", summary.passed() ? "PASSED" : "FAILED")
                .param("recallAtK", summary.recallAtK())
                .param("mrr", summary.meanReciprocalRank())
                .param("projectHitRate", summary.projectHitRate())
                .param("termCoverage", summary.termCoverage())
                .param("noAnswerAccuracy", summary.noAnswerAccuracy())
                .param("citationPrecision", summary.citationPrecision())
                .param("citationCoverage", summary.citationCoverage())
                .param("faithfulness", summary.faithfulness())
                .param("modelName", summary.modelName())
                .param("finishedAt", timestamp(finishedAt))
                .param("id", runId)
                .update();
    }

    @Override
    public void fail(UUID runId, String errorMessage, Instant finishedAt) {
        jdbc.sql("""
                update rag_evaluation_run
                set status='ERROR', error_message=:errorMessage, finished_at=:finishedAt
                where id=:id and status='RUNNING'
                """)
                .param("errorMessage", truncate(errorMessage, 1000))
                .param("finishedAt", timestamp(finishedAt))
                .param("id", runId)
                .update();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Report> latest(UUID workspaceId) {
        Optional<RunRow> run = jdbc.sql("""
                select * from rag_evaluation_run
                where workspace_id=:workspaceId
                order by started_at desc
                limit 1
                """)
                .param("workspaceId", workspaceId)
                .query((rs, rowNum) -> new RunRow(
                        rs.getObject("id", UUID.class), rs.getString("dataset_name"),
                        rs.getString("status"), rs.getInt("case_count"),
                        rs.getInt("generation_sample_size"),
                        number(rs.getObject("recall_at_k")),
                        number(rs.getObject("mean_reciprocal_rank")),
                        number(rs.getObject("project_hit_rate")),
                        number(rs.getObject("term_coverage")),
                        number(rs.getObject("no_answer_accuracy")),
                        number(rs.getObject("citation_precision")),
                        number(rs.getObject("citation_coverage")),
                        number(rs.getObject("faithfulness")),
                        rs.getString("model_name"), rs.getString("error_message"),
                        instant(rs.getObject("started_at", OffsetDateTime.class)),
                        instant(rs.getObject("finished_at", OffsetDateTime.class))))
                .optional();
        if (run.isEmpty()) return Optional.empty();
        RunRow row = run.orElseThrow();
        List<CaseResult> cases = jdbc.sql("""
                select * from rag_evaluation_case where run_id=:runId order by case_key
                """)
                .param("runId", row.id())
                .query((rs, rowNum) -> new CaseResult(
                        rs.getString("case_key"), rs.getString("question"),
                        rs.getBoolean("expected_answerable"), rs.getString("expected_project"),
                        rs.getBoolean("predicted_answerable"),
                        rs.getBoolean("answerability_correct"), rs.getBoolean("project_hit"),
                        rs.getDouble("reciprocal_rank"), rs.getDouble("term_coverage"),
                        rs.getString("retrieval_mode"), readList(rs.getString("top_projects")),
                        readList(rs.getString("source_urls")),
                        number(rs.getObject("citation_precision")),
                        number(rs.getObject("citation_coverage")),
                        number(rs.getObject("faithfulness")), rs.getString("judge_reason"),
                        rs.getString("generated_answer")))
                .list();
        Summary summary = row.recallAtK() == null ? null : new Summary(
                row.recallAtK(), row.mrr(), row.projectHitRate(), row.termCoverage(),
                row.noAnswerAccuracy(), row.citationPrecision(), row.citationCoverage(),
                row.faithfulness(), "PASSED".equals(row.status()), row.modelName());
        return Optional.of(new Report(row.id(), row.datasetName(), row.status(), row.caseCount(),
                row.generationSampleSize(), summary, row.errorMessage(), row.startedAt(),
                row.finishedAt(), cases));
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value == null ? List.of() : value); }
        catch (Exception exception) { throw new IllegalStateException("Unable to write RAG evaluation JSON", exception); }
    }

    private List<String> readList(String value) {
        try { return value == null ? List.of() : json.readValue(value, new TypeReference<List<String>>() { }); }
        catch (Exception exception) { throw new IllegalStateException("Unable to read RAG evaluation JSON", exception); }
    }

    private static OffsetDateTime timestamp(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static Double number(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }

    private static String truncate(String value, int limit) {
        if (value == null) return null;
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private record RunRow(UUID id, String datasetName, String status, int caseCount,
                          int generationSampleSize, Double recallAtK, Double mrr,
                          Double projectHitRate, Double termCoverage, Double noAnswerAccuracy,
                          Double citationPrecision, Double citationCoverage, Double faithfulness,
                          String modelName, String errorMessage, Instant startedAt,
                          Instant finishedAt) { }
}
