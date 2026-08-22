package com.jundaodsj.insightops.infrastructure.persistence;

import com.jundaodsj.insightops.conversation.application.DurableChatRunStore;
import com.jundaodsj.insightops.identity.application.ActorContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcDurableChatRunStore implements DurableChatRunStore {

    private final JdbcClient jdbc;

    public JdbcDurableChatRunStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void enqueue(WorkDraft draft, String startedEventJson) {
        jdbc.sql("""
                        insert into agent_run_work (
                            run_id, workspace_id, owner_user_id, session_id, trace_id, status,
                            system_admin, access_level, user_prompt, contextual_prompt,
                            resume_checkpoint_id, max_attempts, created_at, updated_at
                        ) values (
                            :runId, :workspaceId, :ownerUserId, :sessionId, :traceId, 'QUEUED',
                            :systemAdmin, :accessLevel, :userPrompt, :contextualPrompt,
                            :resumeCheckpointId, :maxAttempts, :createdAt, :createdAt
                        )
                        """)
                .param("runId", draft.runId()).param("workspaceId", draft.workspaceId())
                .param("ownerUserId", draft.ownerUserId()).param("sessionId", draft.sessionId())
                .param("traceId", draft.traceId()).param("systemAdmin", draft.systemAdmin())
                .param("accessLevel", draft.accessLevel()).param("userPrompt", draft.userPrompt())
                .param("contextualPrompt", draft.contextualPrompt())
                .param("resumeCheckpointId", draft.resumeCheckpointId())
                .param("maxAttempts", Math.max(1, draft.maxAttempts()))
                .param("createdAt", Timestamp.from(draft.createdAt())).update();
        insertEvent(draft.runId(), 1L, "started", startedEventJson, draft.createdAt());
    }

    @Override
    @Transactional
    public List<WorkLease> claim(
            String workerId, int limit, int maxAttempts, Duration leaseDuration, Instant now) {
        List<Claimable> rows = jdbc.sql("""
                        select run_id, workspace_id, owner_user_id, session_id, trace_id,
                               system_admin, access_level, user_prompt, contextual_prompt,
                               resume_checkpoint_id, recovery_checkpoint_id, status,
                               attempt_count, max_attempts
                        from agent_run_work
                        where status = 'QUEUED'
                           or (status = 'RUNNING' and lease_expires_at <= :now)
                        order by case when cancel_requested_at is null then 1 else 0 end,
                                 created_at, run_id
                        for update skip locked
                        limit :limit
                        """)
                .param("now", Timestamp.from(now)).param("limit", Math.max(1, limit))
                .query((rs, row) -> new Claimable(
                        rs.getObject("run_id", UUID.class),
                        rs.getObject("workspace_id", UUID.class),
                        rs.getObject("owner_user_id", UUID.class),
                        rs.getObject("session_id", UUID.class), rs.getString("trace_id"),
                        rs.getBoolean("system_admin"), rs.getString("access_level"),
                        rs.getString("user_prompt"), rs.getString("contextual_prompt"),
                        rs.getObject("resume_checkpoint_id", UUID.class),
                        rs.getObject("recovery_checkpoint_id", UUID.class),
                        rs.getString("status"), rs.getInt("attempt_count"),
                        rs.getInt("max_attempts"))).list();
        List<WorkLease> leases = new ArrayList<>();
        for (Claimable row : rows) {
            UUID token = UUID.randomUUID();
            int configuredMax = Math.max(1, Math.min(row.maxAttempts(), maxAttempts));
            int attempt = row.attemptCount() + 1;
            Instant expiresAt = now.plus(leaseDuration);
            int updated = jdbc.sql("""
                            update agent_run_work
                            set status = 'RUNNING', attempt_count = :attempt,
                                max_attempts = :maxAttempts, claimed_by = :workerId,
                                lease_token = :leaseToken, heartbeat_at = :now,
                                lease_expires_at = :expiresAt, updated_at = :now
                            where run_id = :runId
                              and (status = 'QUEUED'
                                   or (status = 'RUNNING' and lease_expires_at <= :now))
                            """)
                    .param("attempt", attempt).param("maxAttempts", configuredMax)
                    .param("workerId", workerId).param("leaseToken", token)
                    .param("now", Timestamp.from(now)).param("expiresAt", Timestamp.from(expiresAt))
                    .param("runId", row.runId()).update();
            if (updated == 1) {
                leases.add(new WorkLease(
                        row.runId(), row.workspaceId(), row.ownerUserId(), row.sessionId(),
                        row.traceId(), row.systemAdmin(), row.accessLevel(), row.userPrompt(),
                        row.contextualPrompt(), row.resumeCheckpointId(),
                        row.recoveryCheckpointId(), token, workerId, attempt, configuredMax,
                        "RUNNING".equals(row.status()), expiresAt));
            }
        }
        return List.copyOf(leases);
    }

    @Override
    @Transactional
    public LeaseControl renewLease(
            UUID runId, UUID leaseToken, Duration leaseDuration, Instant now) {
        int updated = jdbc.sql("""
                        update agent_run_work
                        set heartbeat_at = :now, lease_expires_at = :expiresAt, updated_at = :now
                        where run_id = :runId and status = 'RUNNING'
                          and lease_token = :leaseToken and lease_expires_at > :now
                          and cancel_requested_at is null
                        """)
                .param("runId", runId).param("leaseToken", leaseToken)
                .param("now", Timestamp.from(now))
                .param("expiresAt", Timestamp.from(now.plus(leaseDuration))).update();
        if (updated == 1) return LeaseControl.ACTIVE;
        return jdbc.sql("""
                        select cancel_requested_at is not null
                        from agent_run_work
                        where run_id = :runId and status = 'RUNNING'
                          and lease_token = :leaseToken
                        """)
                .param("runId", runId).param("leaseToken", leaseToken)
                .query(Boolean.class).optional()
                .map(cancelled -> cancelled
                        ? LeaseControl.CANCEL_REQUESTED : LeaseControl.LOST)
                .orElse(LeaseControl.LOST);
    }

    @Override
    @Transactional
    public AttemptPreparation prepareAttempt(UUID runId, UUID leaseToken, Instant now) {
        Attempt attempt = jdbc.sql("""
                        select attempt_count
                        from agent_run_work
                        where run_id = :runId and status = 'RUNNING'
                          and lease_token = :leaseToken and lease_expires_at > :now
                        for update
                        """)
                .param("runId", runId).param("leaseToken", leaseToken)
                .param("now", Timestamp.from(now))
                .query((rs, row) -> new Attempt(rs.getInt("attempt_count")))
                .optional().orElseThrow(() -> new IllegalStateException("AGENT_RUN_LEASE_LOST"));
        if (attempt.count() <= 1) return new AttemptPreparation(null, false);

        jdbc.sql("""
                        update agent_plan set status = 'SUPERSEDED', finished_at = :now
                        where run_id = :runId and status in ('ACTIVE', 'PAUSE_REQUESTED')
                        """).param("runId", runId).param("now", Timestamp.from(now)).update();
        jdbc.sql("""
                        update agent_plan_node
                        set status = 'CANCELLED', error_code = 'AGENT_RUN_WORKER_LOST',
                            finished_at = :now, updated_at = :now
                        where run_id = :runId
                          and status in ('PENDING', 'BLOCKED', 'RUNNING', 'WAITING_APPROVAL')
                        """).param("runId", runId).param("now", Timestamp.from(now)).update();
        jdbc.sql("""
                        update tool_call
                        set status = 'FAILED', error_message = 'AGENT_RUN_WORKER_LOST',
                            finished_at = :now
                        where run_id = :runId and status in ('REQUESTED', 'RUNNING')
                        """).param("runId", runId).param("now", Timestamp.from(now)).update();
        jdbc.sql("""
                        update agent_step
                        set status = 'FAILED', finished_at = :now,
                            output_payload = coalesce(output_payload, '{}'::jsonb)
                                || '{\"errorCode\":\"AGENT_RUN_WORKER_LOST\"}'::jsonb
                        where run_id = :runId and status = 'RUNNING'
                        """).param("runId", runId).param("now", Timestamp.from(now)).update();

        UUID checkpointId = jdbc.sql("""
                        select id from agent_plan_checkpoint
                        where run_id = :runId and status = 'AVAILABLE'
                        order by created_at desc, sequence desc limit 1
                        """).param("runId", runId).query(UUID.class).optional().orElse(null);
        jdbc.sql("""
                        update agent_run_work
                        set recovery_checkpoint_id = :checkpointId, updated_at = :now
                        where run_id = :runId and lease_token = :leaseToken
                        """).param("checkpointId", checkpointId).param("now", Timestamp.from(now))
                .param("runId", runId).param("leaseToken", leaseToken).update();
        return new AttemptPreparation(checkpointId, true);
    }

    @Override
    @Transactional
    public Optional<Long> appendEvent(
            UUID runId, UUID leaseToken, String eventType, String payloadJson, Instant now) {
        boolean active = jdbc.sql("""
                        select run_id from agent_run_work
                        where run_id = :runId and status = 'RUNNING'
                          and lease_token = :leaseToken and lease_expires_at > :now
                          and cancel_requested_at is null
                        for update
                        """).param("runId", runId).param("leaseToken", leaseToken)
                .param("now", Timestamp.from(now)).query(UUID.class).optional().isPresent();
        if (!active) return Optional.empty();
        long sequence = nextEventSequence(runId);
        insertEvent(runId, sequence, eventType, payloadJson, now);
        return Optional.of(sequence);
    }

    @Override
    public boolean requestCancel(ActorContext actor, UUID runId, Instant requestedAt) {
        return jdbc.sql("""
                        update agent_run_work
                        set cancel_requested_at = coalesce(cancel_requested_at, :requestedAt),
                            updated_at = :requestedAt
                        where run_id = :runId and workspace_id = :workspaceId
                          and owner_user_id = :userId and status in ('QUEUED', 'RUNNING')
                        """).param("requestedAt", Timestamp.from(requestedAt))
                .param("runId", runId).param("workspaceId", actor.workspaceId())
                .param("userId", actor.userId()).update() == 1;
    }

    @Override
    public boolean ownsWork(ActorContext actor, UUID runId) {
        return jdbc.sql("""
                        select count(*) = 1 from agent_run_work
                        where run_id = :runId and workspace_id = :workspaceId
                          and owner_user_id = :userId
                        """).param("runId", runId).param("workspaceId", actor.workspaceId())
                .param("userId", actor.userId()).query(Boolean.class).single();
    }

    @Override
    public List<StoredEvent> events(
            ActorContext actor, UUID runId, long afterSequence, int limit) {
        return jdbc.sql("""
                        select event.sequence, event.event_type, event.payload::text, event.created_at
                        from agent_run_event event
                        join agent_run_work work on work.run_id = event.run_id
                        where event.run_id = :runId and event.sequence > :afterSequence
                          and work.workspace_id = :workspaceId and work.owner_user_id = :userId
                        order by event.sequence
                        limit :limit
                        """).param("runId", runId).param("afterSequence", Math.max(0, afterSequence))
                .param("workspaceId", actor.workspaceId()).param("userId", actor.userId())
                .param("limit", Math.max(1, Math.min(500, limit)))
                .query((rs, row) -> new StoredEvent(
                        rs.getLong("sequence"), rs.getString("event_type"),
                        rs.getString("payload"), rs.getTimestamp("created_at").toInstant())).list();
    }

    @Override
    public Optional<WorkView> findOwned(ActorContext actor, UUID runId) {
        return jdbc.sql("""
                        select run_id, status, attempt_count, max_attempts, claimed_by,
                               heartbeat_at, lease_expires_at, cancel_requested_at,
                               recovery_checkpoint_id, failure_code, updated_at
                        from agent_run_work
                        where run_id = :runId and workspace_id = :workspaceId
                          and owner_user_id = :userId
                        """).param("runId", runId).param("workspaceId", actor.workspaceId())
                .param("userId", actor.userId()).query((rs, row) -> new WorkView(
                        rs.getObject("run_id", UUID.class), rs.getString("status"),
                        rs.getInt("attempt_count"), rs.getInt("max_attempts"),
                        rs.getString("claimed_by"), instant(rs.getTimestamp("heartbeat_at")),
                        instant(rs.getTimestamp("lease_expires_at")),
                        instant(rs.getTimestamp("cancel_requested_at")),
                        rs.getObject("recovery_checkpoint_id", UUID.class),
                        rs.getString("failure_code"), rs.getTimestamp("updated_at").toInstant()))
                .optional();
    }

    @Override
    @Transactional
    public boolean markTerminal(
            UUID runId, UUID leaseToken, String status, String failureCode,
            String terminalEventType, String terminalEventJson, Instant finishedAt) {
        int updated = jdbc.sql("""
                        update agent_run_work
                        set status = :status, failure_code = :failureCode,
                            heartbeat_at = :finishedAt, lease_token = null,
                            lease_expires_at = null, finished_at = :finishedAt,
                            updated_at = :finishedAt
                        where run_id = :runId and status = 'RUNNING'
                          and lease_token = :leaseToken and lease_expires_at > :finishedAt
                        """).param("status", status).param("failureCode", failureCode)
                .param("finishedAt", Timestamp.from(finishedAt)).param("runId", runId)
                .param("leaseToken", leaseToken).update();
        if (updated != 1) return false;
        insertEvent(runId, nextEventSequence(runId), terminalEventType,
                terminalEventJson, finishedAt);
        return true;
    }

    private long nextEventSequence(UUID runId) {
        return jdbc.sql("""
                        select coalesce(max(sequence), 0) + 1
                        from agent_run_event where run_id = :runId
                        """).param("runId", runId).query(Long.class).single();
    }

    private void insertEvent(
            UUID runId, long sequence, String type, String payloadJson, Instant createdAt) {
        jdbc.sql("""
                        insert into agent_run_event
                            (run_id, sequence, event_type, payload, created_at)
                        values (:runId, :sequence, :type, cast(:payload as jsonb), :createdAt)
                        """).param("runId", runId).param("sequence", sequence).param("type", type)
                .param("payload", payloadJson).param("createdAt", Timestamp.from(createdAt)).update();
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private record Claimable(
            UUID runId, UUID workspaceId, UUID ownerUserId, UUID sessionId, String traceId,
            boolean systemAdmin, String accessLevel, String userPrompt, String contextualPrompt,
            UUID resumeCheckpointId, UUID recoveryCheckpointId, String status,
            int attemptCount, int maxAttempts) {
    }

    private record Attempt(int count) {
    }
}
