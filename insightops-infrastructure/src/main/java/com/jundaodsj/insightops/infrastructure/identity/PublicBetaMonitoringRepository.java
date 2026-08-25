package com.jundaodsj.insightops.infrastructure.identity;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PublicBetaMonitoringRepository {
    private final JdbcClient jdbc;
    public PublicBetaMonitoringRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    public int occupiedSlots() {
        return jdbc.sql("select count(registration_slot) from public_registration")
                .query(Integer.class).single();
    }
    public int failedMail() {
        return jdbc.sql("select count(*) from identity_mail_outbox where status='FAILED'")
                .query(Integer.class).single();
    }
    public double oldestPendingMailSeconds() {
        return jdbc.sql("""
                select coalesce(extract(epoch from (now()-min(created_at))),0)
                from identity_mail_outbox where status in ('PENDING','RETRY_WAIT','SENDING')
                """).query(Double.class).single();
    }
}
