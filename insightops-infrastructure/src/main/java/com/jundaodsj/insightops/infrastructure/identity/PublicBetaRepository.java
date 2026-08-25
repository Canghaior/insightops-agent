package com.jundaodsj.insightops.infrastructure.identity;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PublicBetaRepository {
    private static final long REGISTRATION_LOCK = 724031L;
    private final JdbcClient jdbc;

    public PublicBetaRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Control control() {
        return jdbc.sql("""
                select registration_enabled,runs_enabled,status_message,updated_at
                from public_beta_control where singleton_id = 1
                """)
                .query((rs, row) -> new Control(rs.getBoolean("registration_enabled"),
                        rs.getBoolean("runs_enabled"), rs.getString("status_message"),
                        instant(rs, "updated_at"))).single();
    }

    public Counts counts() {
        return jdbc.sql("""
                select count(*) filter (where status = 'ACTIVE') as active_count,
                       count(*) filter (where status = 'PENDING') as pending_count,
                       count(registration_slot) as occupied_count
                from public_registration
                """)
                .query((rs, row) -> new Counts(rs.getInt("active_count"),
                        rs.getInt("pending_count"), rs.getInt("occupied_count"))).single();
    }

    @Transactional
    public Registration create(RegistrationCommand command, int maximumRegistrations) {
        jdbc.sql("""
                select :value from (select pg_advisory_xact_lock(:lockId)) held
                """).param("value", 1).param("lockId", REGISTRATION_LOCK)
                .query(Integer.class).single();
        expirePending(command.createdAt());
        int slot = jdbc.sql("""
                select candidate.slot
                from generate_series(1, :maximum) as candidate(slot)
                where not exists (
                    select 1 from public_registration registration
                    where registration.registration_slot = candidate.slot)
                order by candidate.slot limit 1
                """).param("maximum", Math.max(1, maximumRegistrations))
                .query(Integer.class)
                .optional()
                .orElseThrow(RegistrationCapacityException::new);

        jdbc.sql("""
                insert into app_user
                    (id,username,display_name,status,system_role,email,email_normalized,
                     created_at,updated_at)
                values (:userId,:username,:displayName,'PENDING_VERIFICATION','USER',
                        :email,:normalizedEmail,:now,:now)
                """).param("userId", command.userId()).param("username", command.username())
                .param("displayName", command.displayName()).param("email", command.email())
                .param("normalizedEmail", command.normalizedEmail())
                .param("now", timestamp(command.createdAt())).update();
        jdbc.sql("""
                insert into user_credential
                    (user_id,password_hash,must_change_password,password_changed_at,updated_at)
                values (:userId,:passwordHash,false,:now,:now)
                """).param("userId", command.userId()).param("passwordHash", command.passwordHash())
                .param("now", timestamp(command.createdAt())).update();
        jdbc.sql("""
                insert into workspace
                    (id,name,slug,status,description,created_by,created_at,updated_at)
                values (:workspaceId,:name,:slug,'ACTIVE',:description,:userId,:now,:now)
                """).param("workspaceId", command.workspaceId()).param("name", command.workspaceName())
                .param("slug", command.workspaceSlug()).param("description", "Personal public Beta workspace")
                .param("userId", command.userId()).param("now", timestamp(command.createdAt())).update();
        jdbc.sql("""
                insert into workspace_member (workspace_id,user_id,role,created_at)
                values (:workspaceId,:userId,'OWNER',:now)
                """).param("workspaceId", command.workspaceId()).param("userId", command.userId())
                .param("now", timestamp(command.createdAt())).update();
        jdbc.sql("""
                insert into public_registration
                    (user_id,workspace_id,registration_slot,status,verification_expires_at,
                     created_at,updated_at)
                values (:userId,:workspaceId,:slot,'PENDING',:expiresAt,:now,:now)
                """).param("userId", command.userId()).param("workspaceId", command.workspaceId())
                .param("slot", slot).param("expiresAt", timestamp(command.verificationExpiresAt()))
                .param("now", timestamp(command.createdAt())).update();
        for (Consent consent : command.consents()) {
            jdbc.sql("""
                    insert into legal_consent
                        (id,user_id,document_type,document_version,ip_hash,user_agent_hash,accepted_at)
                    values (:id,:userId,:type,:version,:ipHash,:userAgentHash,:now)
                    """).param("id", UUID.randomUUID()).param("userId", command.userId())
                    .param("type", consent.documentType()).param("version", consent.documentVersion())
                    .param("ipHash", command.ipHash()).param("userAgentHash", command.userAgentHash())
                    .param("now", timestamp(command.createdAt())).update();
        }
        jdbc.sql("""
                insert into agent_cost_policy
                    (workspace_id,enabled,daily_token_limit,daily_cost_limit_cny,
                     monthly_token_limit,monthly_cost_limit_cny,max_concurrent_runs,
                     warning_percent,hard_limit_enabled,version,updated_by,created_at,updated_at)
                values (:workspaceId,true,9223372036854775807,:cost,
                        9223372036854775807,:cost,1,80,true,1,:userId,:now,:now)
                """).param("workspaceId", command.workspaceId())
                .param("cost", new BigDecimal("999999999999.000000"))
                .param("userId", command.userId()).param("now", timestamp(command.createdAt())).update();
        return new Registration(command.userId(), command.workspaceId(), slot, "PENDING",
                command.verificationExpiresAt(), null);
    }

    @Transactional
    public int expirePending(Instant now) {
        List<Registration> expired = jdbc.sql("""
                select user_id,workspace_id,registration_slot,status,
                       verification_expires_at,activated_at
                from public_registration
                where status = 'PENDING' and verification_expires_at <= :now
                order by verification_expires_at
                for update
                """).param("now", timestamp(now))
                .query((rs, row) -> registration(rs, row)).list();
        expired.forEach(value -> {
            jdbc.sql("delete from workspace where id = :workspaceId")
                    .param("workspaceId", value.workspaceId()).update();
            jdbc.sql("delete from app_user where id = :userId and status = 'PENDING_VERIFICATION'")
                    .param("userId", value.userId()).update();
        });
        return expired.size();
    }

    public Optional<Registration> registration(UUID userId) {
        return jdbc.sql("""
                select user_id,workspace_id,registration_slot,status,
                       verification_expires_at,activated_at
                from public_registration where user_id = :userId
                """).param("userId", userId)
                .query((rs, row) -> registration(rs, row)).optional();
    }

    public boolean isPublicWorkspace(UUID workspaceId) {
        return jdbc.sql("""
                select exists(select 1 from public_registration where workspace_id = :workspaceId)
                """).param("workspaceId", workspaceId).query(Boolean.class).single();
    }

    public boolean publicRunsEnabled(UUID workspaceId) {
        return jdbc.sql("""
                select case when exists(
                    select 1 from public_registration where workspace_id = :workspaceId)
                then (select runs_enabled from public_beta_control where singleton_id = 1)
                else true end
                """).param("workspaceId", workspaceId).query(Boolean.class).single();
    }

    @Transactional
    public Control updateControl(boolean registrationEnabled, boolean runsEnabled,
                                 String message, UUID updatedBy, Instant now) {
        return jdbc.sql("""
                update public_beta_control
                set registration_enabled = :registrationEnabled, runs_enabled = :runsEnabled,
                    status_message = :message, updated_by = :updatedBy, updated_at = :now
                where singleton_id = 1
                returning registration_enabled,runs_enabled,status_message,updated_at
                """).param("registrationEnabled", registrationEnabled).param("runsEnabled", runsEnabled)
                .param("message", message).param("updatedBy", updatedBy)
                .param("now", timestamp(now))
                .query((rs, row) -> new Control(rs.getBoolean("registration_enabled"),
                        rs.getBoolean("runs_enabled"), rs.getString("status_message"),
                        instant(rs, "updated_at"))).single();
    }

    private Registration registration(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        Integer slot = (Integer) rs.getObject("registration_slot");
        return new Registration(rs.getObject("user_id", UUID.class),
                rs.getObject("workspace_id", UUID.class), slot, rs.getString("status"),
                instant(rs, "verification_expires_at"), instant(rs, "activated_at"));
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime timestamp(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    public record RegistrationCommand(UUID userId, UUID workspaceId, String username,
                                      String displayName, String email, String normalizedEmail,
                                      String passwordHash, String workspaceName, String workspaceSlug,
                                      String ipHash, String userAgentHash, List<Consent> consents,
                                      Instant verificationExpiresAt, Instant createdAt) { }
    public record Consent(String documentType, String documentVersion) { }
    public record Registration(UUID userId, UUID workspaceId, Integer slot, String status,
                               Instant verificationExpiresAt, Instant activatedAt) { }
    public record Control(boolean registrationEnabled, boolean runsEnabled,
                          String statusMessage, Instant updatedAt) { }
    public record Counts(int active, int pending, int occupied) { }

    public static final class RegistrationCapacityException extends RuntimeException { }
}
