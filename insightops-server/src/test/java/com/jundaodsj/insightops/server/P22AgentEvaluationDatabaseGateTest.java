package com.jundaodsj.insightops.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.agent.application.AgentEvaluationStore;
import com.jundaodsj.insightops.agent.application.AgentRunQuery;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcAgentEvaluationStore;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcAgentRunQuery;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "INSIGHTOPS_CHAIN_GATE", matches = "true")
class P22AgentEvaluationDatabaseGateTest {

    private static final UUID WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final ActorContext ACTOR = new ActorContext(USER, WORKSPACE);
    private static final String SCHEMA = "p22_gate_" + UUID.randomUUID().toString().replace("-", "");

    private static String databaseUrl;
    private static String username;
    private static String password;
    private static DataSource dataSource;

    @BeforeAll
    static void migrate() throws Exception {
        databaseUrl = environment("DB_URL", "jdbc:postgresql://localhost:55432/insightops");
        username = environment("DB_USERNAME", "insightops");
        password = environment("DB_PASSWORD", "insightops_dev");
        DriverManagerDataSource root = new DriverManagerDataSource(databaseUrl, username, password);
        try (Connection connection = root.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("create extension if not exists vector with schema public");
        }
        String schemaUrl = databaseUrl + (databaseUrl.contains("?") ? "&" : "?")
                + "currentSchema=" + SCHEMA;
        dataSource = new DriverManagerDataSource(schemaUrl, username, password);
        var result = Flyway.configure().dataSource(dataSource).schemas(SCHEMA)
                .defaultSchema(SCHEMA).createSchemas(true)
                .locations("classpath:db/migration").load().migrate();
        assertThat(result.migrationsExecuted).isEqualTo(33);
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (databaseUrl == null) return;
        DriverManagerDataSource root = new DriverManagerDataSource(databaseUrl, username, password);
        try (Connection connection = root.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + SCHEMA + " cascade");
        }
    }

    @Test
    void persistsEvaluationBaselineAndGatesProductionActivation() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        JdbcAgentEvaluationStore store = new JdbcAgentEvaluationStore(jdbc, json);
        Instant now = Instant.parse("2026-08-22T08:00:00Z");
        AgentEvaluationStore.Dataset dataset = store.createDataset(
                WORKSPACE, USER, new AgentEvaluationStore.DatasetDraft(
                        "agent-core", "P2.2 database gate",
                        new AgentEvaluationStore.Gate(
                                1, 1, 1, 1, 90_000, 16_000, BigDecimal.ONE),
                        List.of(new AgentEvaluationStore.CaseDraft(
                                "spring-ai-search", "查询 Spring AI ChatClient",
                                List.of("knowledge_hybrid_search"),
                                List.of("user_memory_upsert"), List.of("spring.io"),
                                false, 4, 90_000, 16_000, BigDecimal.ONE, true, null))), now);
        assertThat(dataset.version()).isEqualTo(1);
        assertThat(dataset.cases()).hasSize(1);

        String toolHash = "a".repeat(64);
        AgentEvaluationStore.Candidate candidate = store.createCandidate(
                WORKSPACE, USER, new AgentEvaluationStore.CandidateDraft(
                        "candidate", "Prefer official sources", "deepseek-v4-flash",
                        0, 1024, toolHash, null), now.plusSeconds(1));
        AgentEvaluationStore.Candidate untested = store.createCandidate(
                WORKSPACE, USER, new AgentEvaluationStore.CandidateDraft(
                        "untested", "", "deepseek-v4-flash",
                        0, 1024, toolHash, candidate.id()), now.plusSeconds(2));
        assertThatThrownBy(() -> store.activateCandidate(
                WORKSPACE, USER, untested.id(), toolHash, "must fail", now.plusSeconds(3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("latest evaluation");

        AgentEvaluationStore.Candidate regressed = store.createCandidate(
                WORKSPACE, USER, new AgentEvaluationStore.CandidateDraft(
                        "regressed", "", "deepseek-v4-flash",
                        0, 1024, toolHash, candidate.id()), now.plusSeconds(3));
        AgentEvaluationStore.EvaluationRun earlierPass = store.queueEvaluation(
                WORKSPACE, USER, dataset.id(), regressed.id(), now.plusSeconds(4));
        AgentEvaluationStore.EvaluationLease earlierLease = claim(
                store, earlierPass.id(), "worker-earlier", now.plusSeconds(5));
        store.completeEvaluation(earlierPass.id(), earlierLease.leaseToken(), new AgentEvaluationStore.Summary(
                1, 1, 1, 1, 1, 1, 1_000, 150,
                new BigDecimal("0.010000"), true), now.plusSeconds(6));
        AgentEvaluationStore.EvaluationRun latestFailure = store.queueEvaluation(
                WORKSPACE, USER, dataset.id(), regressed.id(), now.plusSeconds(7));
        AgentEvaluationStore.EvaluationLease failureLease = claim(
                store, latestFailure.id(), "worker-failure", now.plusSeconds(8));
        store.completeEvaluation(latestFailure.id(), failureLease.leaseToken(), new AgentEvaluationStore.Summary(
                1, 0, 0, 0, 1, 0, 1_000, 150,
                new BigDecimal("0.010000"), false), now.plusSeconds(9));
        assertThatThrownBy(() -> store.activateCandidate(
                WORKSPACE, USER, regressed.id(), toolHash, "latest run failed",
                now.plusSeconds(10)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("latest evaluation");

        AgentEvaluationStore.EvaluationRun evaluation = store.queueEvaluation(
                WORKSPACE, USER, dataset.id(), candidate.id(), now.plusSeconds(4));
        AgentEvaluationStore.EvaluationLease evaluationLease = claim(
                store, evaluation.id(), "worker-evaluation", now.plusSeconds(5));
        UUID agentRunId = UUID.randomUUID();
        store.startAgentRun(
                ACTOR, evaluation.id(), dataset.cases().getFirst().id(),
                evaluationLease.leaseToken(), agentRunId, "p22-db-" + agentRunId,
                dataset.cases().getFirst().question(), now.plusSeconds(5));
        store.completeAgentRun(evaluation.id(), evaluationLease.leaseToken(), agentRunId,
                "deepseek-v4-flash", 100, 50,
                new BigDecimal("0.010000"), List.of("https://spring.io/projects/spring-ai"),
                now.plusSeconds(6));
        store.saveCaseResult(evaluation.id(), evaluationLease.leaseToken(),
                new AgentEvaluationStore.CaseResultDraft(
                UUID.randomUUID(), dataset.cases().getFirst().id(), agentRunId, "PASSED",
                List.of("knowledge_hybrid_search"), List.of(), List.of(),
                List.of("https://spring.io/projects/spring-ai"), true, true,
                false, true, 1_000, 150, new BigDecimal("0.010000"), null,
                "{\"withinLimits\":true}"), now.plusSeconds(6));
        AgentEvaluationStore.Summary summary = new AgentEvaluationStore.Summary(
                1, 1, 1, 1, 1, 1, 1_000, 150,
                new BigDecimal("0.010000"), true);
        assertThat(store.completeEvaluation(
                evaluation.id(), evaluationLease.leaseToken(), summary, now.plusSeconds(7))).isTrue();
        AgentEvaluationStore.EvaluationRun completed = store.findEvaluation(
                WORKSPACE, evaluation.id()).orElseThrow();
        assertThat(completed.status()).isEqualTo("PASSED");
        assertThat(completed.results()).singleElement()
                .extracting("toolSelectionCorrect", "planCompleted")
                .containsExactly(true, true);

        AgentEvaluationStore.Candidate active = store.activateCandidate(
                WORKSPACE, USER, candidate.id(), toolHash, "database gate passed",
                now.plusSeconds(8));
        assertThat(active.status()).isEqualTo("ACTIVE");
        assertThat(store.activeProfile(WORKSPACE)).get()
                .extracting("candidateId", "modelName")
                .containsExactly(candidate.id(), "deepseek-v4-flash");

        AgentEvaluationStore.Candidate promoted = store.createCandidate(
                WORKSPACE, USER, new AgentEvaluationStore.CandidateDraft(
                        "promoted", "", "deepseek-v4-flash",
                        0, 1024, toolHash, candidate.id()), now.plusSeconds(9));
        AgentEvaluationStore.EvaluationRun promotion = store.queueEvaluation(
                WORKSPACE, USER, dataset.id(), promoted.id(), now.plusSeconds(10));
        assertThat(promotion.baselineRunId()).isEqualTo(evaluation.id());
        AgentEvaluationStore.EvaluationLease promotionLease = claim(
                store, promotion.id(), "worker-promotion", now.plusSeconds(11));
        store.completeEvaluation(promotion.id(), promotionLease.leaseToken(), summary, now.plusSeconds(12));
        store.activateCandidate(WORKSPACE, USER, promoted.id(), toolHash,
                "new candidate passed", now.plusSeconds(13));
        assertThat(store.activeProfile(WORKSPACE)).get()
                .extracting("candidateId", "version")
                .containsExactly(promoted.id(), promoted.version());

        assertThat(jdbc.sql("select run_kind from agent_run where id = :id")
                .param("id", agentRunId).query(String.class).single()).isEqualTo("EVALUATION");

        AgentRunQuery runQuery = new JdbcAgentRunQuery(jdbc, json);
        assertThat(runQuery.listRuns(ACTOR, 0, 100, null).items())
                .noneMatch(item -> item.id().equals(agentRunId));
    }

    @Test
    void reclaimsExpiredLeaseAndRejectsStaleWorkerWrites() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        JdbcAgentEvaluationStore store = new JdbcAgentEvaluationStore(
                jdbc, new ObjectMapper().findAndRegisterModules());
        Instant now = Instant.parse("2026-08-22T10:00:00Z");
        AgentEvaluationStore.Dataset dataset = store.createDataset(
                WORKSPACE, USER, new AgentEvaluationStore.DatasetDraft(
                        "lease-recovery", "P2.3 durable queue",
                        new AgentEvaluationStore.Gate(
                                1, 1, 1, 1, 90_000, 16_000, BigDecimal.ONE),
                        List.of(new AgentEvaluationStore.CaseDraft(
                                "lease-case", "验证租约接管", List.of(), List.of(), List.of(),
                                false, 2, 30_000, 2_000, BigDecimal.ONE, true, null))), now);
        AgentEvaluationStore.Candidate candidate = store.createCandidate(
                WORKSPACE, USER, new AgentEvaluationStore.CandidateDraft(
                        "lease-candidate", "", "deepseek-v4-flash", 0, 512,
                        "b".repeat(64), null), now.plusSeconds(1));
        AgentEvaluationStore.EvaluationRun queued = store.queueEvaluation(
                WORKSPACE, USER, dataset.id(), candidate.id(), now.plusSeconds(2));
        AgentEvaluationStore.EvaluationLease first = claim(
                store, queued.id(), "worker-one", now.plusSeconds(3), Duration.ofSeconds(10));
        UUID orphanRun = UUID.randomUUID();
        assertThat(store.startAgentRun(
                ACTOR, queued.id(), dataset.cases().getFirst().id(), first.leaseToken(),
                orphanRun, "lease-orphan", "验证租约接管", now.plusSeconds(4))).isTrue();

        AgentEvaluationStore.EvaluationLease second = claim(
                store, queued.id(), "worker-two", now.plusSeconds(14), Duration.ofSeconds(10));
        assertThat(second.attemptCount()).isEqualTo(2);
        assertThat(second.leaseToken()).isNotEqualTo(first.leaseToken());
        assertThat(store.prepareEvaluationAttempt(
                queued.id(), second.leaseToken(), now.plusSeconds(15))).containsExactly(orphanRun);
        assertThat(jdbc.sql("select failure_code from agent_run where id = :id")
                .param("id", orphanRun).query(String.class).single())
                .isEqualTo("EVALUATION_WORKER_LOST");

        AgentEvaluationStore.Summary summary = new AgentEvaluationStore.Summary(
                1, 1, 1, 1, 1, 1, 100, 10, new BigDecimal("0.001000"), true);
        assertThat(store.completeEvaluation(
                queued.id(), first.leaseToken(), summary, now.plusSeconds(16))).isFalse();
        assertThat(store.renewEvaluationLease(
                queued.id(), first.leaseToken(), Duration.ofSeconds(10), now.plusSeconds(16)))
                .isFalse();
        assertThat(store.completeEvaluation(
                queued.id(), second.leaseToken(), summary, now.plusSeconds(16))).isTrue();
    }

    private static AgentEvaluationStore.EvaluationLease claim(
            JdbcAgentEvaluationStore store, UUID expectedId, String workerId, Instant now) {
        return claim(store, expectedId, workerId, now, Duration.ofMinutes(3));
    }

    private static AgentEvaluationStore.EvaluationLease claim(
            JdbcAgentEvaluationStore store, UUID expectedId, String workerId,
            Instant now, Duration leaseDuration) {
        List<AgentEvaluationStore.EvaluationLease> leases = store.claimEvaluations(
                workerId, 1, 3, leaseDuration, now);
        assertThat(leases).singleElement().extracting("evaluationRunId").isEqualTo(expectedId);
        return leases.getFirst();
    }
    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
