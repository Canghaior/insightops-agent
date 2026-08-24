package com.jundaodsj.insightops.server.knowledge;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeOperationalMetrics {
    private final JdbcClient jdbc;

    public KnowledgeOperationalMetrics(JdbcClient jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        Gauge.builder("insightops.knowledge.collection.running", this, value -> value.count("""
                        select count(*) from knowledge_source where status='RUNNING'
                        """))
                .description("Knowledge collection sources currently running").register(registry);
        Gauge.builder("insightops.knowledge.collection.expired_leases", this, value -> value.count("""
                        select count(*) from knowledge_source
                        where status='RUNNING' and locked_until < now()
                        """))
                .description("Knowledge sources whose collection lease has expired").register(registry);
        Gauge.builder("insightops.project.collection.failed", this, value -> value.count("""
                        select count(*) from tracked_project
                        where enabled=true and last_sync_status in ('RETRY_WAIT','FAILED')
                        """))
                .description("Enabled tracked projects in a failed collection state").register(registry);
        Gauge.builder("insightops.project.collection.stale", this, value -> value.count("""
                        select count(*) from tracked_project
                        where enabled=true
                          and (last_sync_at is null
                               or last_sync_at < now() - (sync_interval_hours + 6) * interval '1 hour')
                        """))
                .description("Enabled tracked projects beyond their collection interval and grace period")
                .register(registry);
        Gauge.builder("insightops.knowledge.embedding.backlog", this, value -> value.count("""
                        select count(*) from knowledge_embedding
                        where status in ('PENDING','RUNNING','RETRY_WAIT')
                        """))
                .description("Embedding tasks not yet complete").register(registry);
        Gauge.builder("insightops.knowledge.upload.bytes", this, value -> value.count("""
                        select coalesce(sum(byte_size),0) from knowledge_upload
                        """))
                .baseUnit("bytes").description("Stored user knowledge upload bytes").register(registry);
        Gauge.builder("insightops.knowledge.upload.failed", this, value -> value.count("""
                        select count(*) from knowledge_upload where status='FAILED'
                        """))
                .description("User knowledge uploads in failed state").register(registry);
    }

    private double count(String sql) {
        try { return jdbc.sql(sql).query(Long.class).single().doubleValue(); }
        catch (RuntimeException exception) { return Double.NaN; }
    }
}
