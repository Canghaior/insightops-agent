package com.jundaodsj.insightops.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.conversation.application.ChatCitation;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.infrastructure.delivery.DeliverySecretCipher;
import com.jundaodsj.insightops.infrastructure.persistence.*;
import com.jundaodsj.insightops.knowledge.application.*;
import com.jundaodsj.insightops.report.application.ReportDeliveryStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "INSIGHTOPS_CHAIN_GATE", matches = "true")
class ResearchReportsDatabaseGateTest {
    private static final String SCHEMA = "research_gate_" + UUID.randomUUID().toString().replace("-", "");
    private static final UUID WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final ActorContext ACTOR = new ActorContext(USER, WORKSPACE);
    private static JdbcClient jdbc;
    private static DriverManagerDataSource root;
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    @BeforeAll static void migrate() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:55432/insightops");
        String username = System.getenv().getOrDefault("DB_USERNAME", "insightops");
        String password = System.getenv().getOrDefault("DB_PASSWORD", "insightops_dev");
        root = new DriverManagerDataSource(url, username, password);
        var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA, username, password);
        Flyway.configure().dataSource(source).schemas(SCHEMA).defaultSchema(SCHEMA).createSchemas(true)
                .locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(source);
    }
    @AfterAll static void cleanup() {
        if (root != null) JdbcClient.create(root).sql("drop schema if exists " + SCHEMA + " cascade").update();
    }
    @Test void reportsAreIdempotentImmutableAndOwnerWorkspaceScoped() {
        var store = new JdbcReportDeliveryStore(jdbc, JSON, new DeliverySecretCipher("research-test-key-only"));
        UUID run = UUID.randomUUID(); Instant now = Instant.now();
        jdbc.sql("""
                insert into agent_run(id,workspace_id,owner_user_id,trace_id,status,question,answer,started_at,finished_at,created_at)
                values(:id,:workspace,:owner,'research-gate','SUCCEEDED','question','answer',:now,:now,:now)
                """).param("id", run).param("workspace", WORKSPACE).param("owner", USER).param("now", Timestamp.from(now)).update();
        var snapshot = new ReportDeliveryStore.ResearchSnapshot(run, "question", "answer [S1]", Map.of("currentVersion", "1.0.0"),
                List.of(new ChatCitation("S1", "source", "https://docs.spring.io/spring-ai/reference/", "spring-ai", "API", "OFFICIAL_DOCUMENT", 1.0)),
                List.of(), List.of("兼容性未验证"), List.of(), now, now);
        var first = store.createResearchReport(ACTOR, UUID.randomUUID(), "research", snapshot, "answer [S1]", now);
        var duplicate = store.createResearchReport(ACTOR, UUID.randomUUID(), "changed", snapshot, "changed", now);
        assertThat(duplicate.id()).isEqualTo(first.id());
        assertThat(duplicate.markdown()).isEqualTo("answer [S1]");
        assertThat(duplicate.research().uncertainties()).containsExactly("兼容性未验证");
        assertThat(store.findReport(new ActorContext(UUID.randomUUID(), WORKSPACE), first.id())).isEmpty();
        assertThat(store.findReport(new ActorContext(USER, UUID.randomUUID()), first.id())).isEmpty();
        assertThatThrownBy(() -> store.createResearchReport(new ActorContext(UUID.randomUUID(), WORKSPACE), UUID.randomUUID(), "forbidden", snapshot, "body", now))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void scopedSearchFiltersBeforeRankingAndStillChecksPrivateFileOwner() {
        var uploads = new JdbcKnowledgeUploadStore(jdbc);
        UUID file = UUID.randomUUID(), source = UUID.randomUUID(), document = UUID.randomUUID(), revision = UUID.randomUUID(), chunk = UUID.randomUUID();
        uploads.create(new KnowledgeUploadStore.CreateUpload(file, source, WORKSPACE, PROJECT, USER, "selected.txt", file + ".txt",
                "text/plain", 100, "a".repeat(64), "PRIVATE", 1_000_000), Instant.now());
        jdbc.sql("insert into knowledge_document(id,source_id,canonical_url,title) values(:id,:source,:url,'team compatibility')")
                .param("id", document).param("source", source).param("url", "upload://" + file + "/content").update();
        jdbc.sql("insert into knowledge_revision(id,document_id,content_sha256,content_text,character_count) values(:id,:doc,:hash,'compatibility selected',22)")
                .param("id", revision).param("doc", document).param("hash", "b".repeat(64)).update();
        jdbc.sql("update knowledge_document set current_revision_id=:revision where id=:id").param("revision", revision).param("id", document).update();
        jdbc.sql("insert into knowledge_chunk(id,revision_id,chunk_index,content,content_sha256,character_count,estimated_tokens) values(:id,:revision,0,'compatibility selected',:hash,22,6)")
                .param("id", chunk).param("revision", revision).param("hash", "c".repeat(64)).update();
        var embeddings = new JdbcKnowledgeEmbeddingStore(jdbc, JSON);
        var selected = new KnowledgeEmbeddingStore.SearchScope(PROJECT, List.of(file));
        assertThat(embeddings.searchKeywordScoped(WORKSPACE, USER, "compatibility", 10, selected)).extracting(KnowledgeEmbeddingStore.SearchResult::chunkId).containsExactly(chunk);
        assertThat(embeddings.searchKeywordScoped(WORKSPACE, UUID.randomUUID(), "compatibility", 10, selected)).isEmpty();
        assertThat(embeddings.searchKeywordScoped(WORKSPACE, USER, "compatibility", 10, new KnowledgeEmbeddingStore.SearchScope(PROJECT, List.of()))).isEmpty();
        assertThat(embeddings.searchKeywordScoped(UUID.randomUUID(), USER, "compatibility", 10, selected)).isEmpty();
        float[] vector = new float[1024]; vector[0] = 1;
        embeddings.prepareCurrentChunks("test", "scope-test", 1024, Instant.now());
        embeddings.claimPending("scope-test", Instant.now(), java.time.Duration.ofMinutes(1), 10);
        embeddings.complete(chunk, "scope-test", vector, Instant.now());
        assertThat(embeddings.searchScoped(WORKSPACE, USER, "scope-test", vector, 10, 0.1, selected)).extracting(KnowledgeEmbeddingStore.SearchResult::chunkId).containsExactly(chunk);
        assertThat(embeddings.searchScoped(WORKSPACE, UUID.randomUUID(), "scope-test", vector, 10, 0.1, selected)).isEmpty();
    }
}
