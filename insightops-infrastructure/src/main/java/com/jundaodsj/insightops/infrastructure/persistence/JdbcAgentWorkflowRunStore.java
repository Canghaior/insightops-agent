package com.jundaodsj.insightops.infrastructure.persistence;

import com.jundaodsj.insightops.agent.application.AgentWorkflowRunStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcAgentWorkflowRunStore implements AgentWorkflowRunStore {

    private final JdbcClient jdbc;

    public JdbcAgentWorkflowRunStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<WorkflowRun> find(UUID runId) {
        List<WorkflowNode> nodes = nodes(runId);
        return jdbc.sql("select * from agent_workflow_run where run_id = :runId")
                .param("runId", runId).query((rs, row) -> workflow(rs, nodes)).optional();
    }

    @Override
    public Optional<WorkflowRun> findByRequest(
            UUID workspaceId, UUID ownerUserId, UUID requestId) {
        return jdbc.sql("""
                        select run_id from agent_workflow_run
                        where workspace_id = :workspaceId and owner_user_id = :ownerUserId
                          and request_id = :requestId
                        """)
                .param("workspaceId", workspaceId).param("ownerUserId", ownerUserId)
                .param("requestId", requestId).query(UUID.class).optional().flatMap(this::find);
    }

    @Override
    @Transactional
    public void create(WorkflowRunDraft draft, List<NodeDraft> nodes) {
        jdbc.sql("""
                        insert into agent_workflow_run (
                            run_id, workspace_id, owner_user_id, template_id,
                            template_version_id, template_name_snapshot,
                            template_version_snapshot, entry_question_snapshot,
                            graph_spec_snapshot, input_snapshot, tool_contract_fingerprint,
                            request_id, source_run_id, retry_root_run_id,
                            retry_from_node_id, created_at
                        ) values (
                            :runId, :workspaceId, :ownerUserId, :templateId,
                            :templateVersionId, :templateName, :templateVersion,
                            :entryQuestion, cast(:graphSpec as jsonb), cast(:inputs as jsonb),
                            :fingerprint, :requestId, :sourceRunId, :retryRootRunId,
                            :retryFromNodeId, :createdAt
                        )
                        """)
                .param("runId", draft.runId()).param("workspaceId", draft.workspaceId())
                .param("ownerUserId", draft.ownerUserId()).param("templateId", draft.templateId())
                .param("templateVersionId", draft.templateVersionId())
                .param("templateName", draft.templateName()).param("templateVersion", draft.templateVersion())
                .param("entryQuestion", draft.entryQuestion()).param("graphSpec", draft.graphSpecJson())
                .param("inputs", draft.inputJson()).param("fingerprint", draft.toolContractFingerprint())
                .param("requestId", draft.requestId()).param("sourceRunId", draft.sourceRunId())
                .param("retryRootRunId", draft.retryRootRunId()).param("retryFromNodeId", draft.retryFromNodeId())
                .param("createdAt", ts(draft.createdAt())).update();
        for (NodeDraft node : nodes) insertNode(draft.runId(), node);
    }

    @Override
    @Transactional
    public NodeAttempt beginNode(
            UUID runId, String logicalNodeId, UUID leaseToken, int runAttempt,
            String workerId, String resolvedInputJson, Instant now) {
        requireLease(runId, leaseToken, now);
        NodeIdentity node = jdbc.sql("""
                        select id, attempt_count from agent_workflow_run_node
                        where run_id = :runId and logical_node_id = :logicalNodeId for update
                        """)
                .param("runId", runId).param("logicalNodeId", logicalNodeId)
                .query((rs, row) -> new NodeIdentity(
                        rs.getObject("id", UUID.class), rs.getInt("attempt_count"))).single();
        jdbc.sql("""
                        update agent_workflow_node_attempt
                        set status = 'FAILED', error_code = 'RUN_RECOVERED', finished_at = :now
                        where workflow_node_id = :nodeId and status = 'RUNNING'
                          and (lease_token <> :leaseToken or run_attempt < :runAttempt)
                        """)
                .param("now", ts(now)).param("nodeId", node.id())
                .param("leaseToken", leaseToken).param("runAttempt", runAttempt).update();
        int attemptNo = node.attemptCount() + 1;
        UUID attemptId = UUID.randomUUID();
        jdbc.sql("""
                        update agent_workflow_run_node
                        set status = 'RUNNING', attempt_count = :attemptNo,
                            resolved_input = cast(:input as jsonb), error_code = null,
                            started_at = coalesce(started_at, :now), finished_at = null,
                            updated_at = :now where id = :id
                        """)
                .param("attemptNo", attemptNo).param("input", resolvedInputJson)
                .param("now", ts(now)).param("id", node.id()).update();
        jdbc.sql("""
                        insert into agent_workflow_node_attempt (
                            id, workflow_node_id, run_id, attempt_no, run_attempt,
                            worker_id, lease_token, status, resolved_input, started_at
                        ) values (
                            :id, :nodeId, :runId, :attemptNo, :runAttempt,
                            :workerId, :leaseToken, 'RUNNING', cast(:input as jsonb), :now
                        )
                        """)
                .param("id", attemptId).param("nodeId", node.id()).param("runId", runId)
                .param("attemptNo", attemptNo).param("runAttempt", runAttempt)
                .param("workerId", workerId).param("leaseToken", leaseToken)
                .param("input", resolvedInputJson).param("now", ts(now)).update();
        return new NodeAttempt(attemptId, node.id(), attemptNo, now);
    }

    @Override
    @Transactional
    public void finishNode(
            UUID runId, String logicalNodeId, UUID leaseToken, UUID attemptId,
            String status, UUID toolCallId, UUID planNodeId, String outputJson,
            String exposedOutputJson, String promptAppendix, String sourceUrlsJson,
            String citationsJson, long inputTokens, long outputTokens,
            BigDecimal estimatedCostCny, String errorCode, Instant now) {
        requireLease(runId, leaseToken, now);
        int nodeUpdated = jdbc.sql("""
                        update agent_workflow_run_node set
                            status = :status, tool_call_id = :toolCallId,
                            plan_node_id = :planNodeId, output_payload = cast(:output as jsonb),
                            exposed_output = cast(:exposed as jsonb), prompt_appendix = :appendix,
                            source_urls = cast(:sources as jsonb), citations = cast(:citations as jsonb),
                            input_tokens = :inputTokens, output_tokens = :outputTokens,
                            estimated_cost_cny = :cost, error_code = :errorCode,
                            finished_at = :now, updated_at = :now
                        where run_id = :runId and logical_node_id = :logicalNodeId
                        """)
                .param("status", status).param("toolCallId", toolCallId).param("planNodeId", planNodeId)
                .param("output", objectJson(outputJson)).param("exposed", objectJson(exposedOutputJson))
                .param("appendix", promptAppendix).param("sources", arrayJson(sourceUrlsJson))
                .param("citations", arrayJson(citationsJson)).param("inputTokens", Math.max(0, inputTokens))
                .param("outputTokens", Math.max(0, outputTokens)).param("cost", cost(estimatedCostCny))
                .param("errorCode", errorCode).param("now", ts(now)).param("runId", runId)
                .param("logicalNodeId", logicalNodeId).update();
        int attemptUpdated = jdbc.sql("""
                        update agent_workflow_node_attempt set
                            status = :status, tool_call_id = :toolCallId,
                            output_payload = cast(:output as jsonb), input_tokens = :inputTokens,
                            output_tokens = :outputTokens, estimated_cost_cny = :cost,
                            error_code = :errorCode, finished_at = :now
                        where id = :attemptId and run_id = :runId
                          and lease_token = :leaseToken and status = 'RUNNING'
                        """)
                .param("status", attemptStatus(status)).param("toolCallId", toolCallId)
                .param("output", objectJson(outputJson)).param("inputTokens", Math.max(0, inputTokens))
                .param("outputTokens", Math.max(0, outputTokens)).param("cost", cost(estimatedCostCny))
                .param("errorCode", errorCode).param("now", ts(now)).param("attemptId", attemptId)
                .param("runId", runId).param("leaseToken", leaseToken).update();
        if (nodeUpdated != 1 || attemptUpdated != 1) {
            throw new IllegalStateException("AGENT_RUN_LEASE_LOST");
        }
    }

    @Override
    public void markReused(UUID runId, String logicalNodeId, UUID planNodeId, Instant now) {
        jdbc.sql("""
                        update agent_workflow_run_node
                        set status = 'REUSED', plan_node_id = :planNodeId, updated_at = :now
                        where run_id = :runId and logical_node_id = :logicalNodeId
                          and status in ('SUCCEEDED', 'REUSED')
                        """)
                .param("planNodeId", planNodeId).param("now", ts(now))
                .param("runId", runId).param("logicalNodeId", logicalNodeId).update();
    }

    private void requireLease(UUID runId, UUID leaseToken, Instant now) {
        boolean active = jdbc.sql("""
                        select count(*) = 1 from agent_run_work
                        where run_id = :runId and status = 'RUNNING'
                          and lease_token = :leaseToken and lease_expires_at > :now
                          and cancel_requested_at is null
                        """)
                .param("runId", runId).param("leaseToken", leaseToken).param("now", ts(now))
                .query(Boolean.class).single();
        if (!active) throw new IllegalStateException("AGENT_RUN_LEASE_LOST");
    }

    private void insertNode(UUID runId, NodeDraft node) {
        jdbc.sql("""
                        insert into agent_workflow_run_node (
                            id, run_id, logical_node_id, tool_name, tool_version, risk_level,
                            required, condition_type, dependency_node_ids, argument_template,
                            expose_outputs, resolved_input, output_payload, exposed_output,
                            prompt_appendix, source_urls, citations, status, attempt_count,
                            reused_from_node_id, input_tokens, output_tokens, estimated_cost_cny,
                            error_code, started_at, finished_at, created_at, updated_at
                        ) values (
                            :id, :runId, :logicalNodeId, :toolName, :toolVersion, :riskLevel,
                            :required, :conditionType, cast(:dependencies as jsonb), cast(:arguments as jsonb),
                            cast(:expose as jsonb), cast(:resolved as jsonb), cast(:output as jsonb),
                            cast(:exposed as jsonb), :appendix, cast(:sources as jsonb),
                            cast(:citations as jsonb), :status, :attemptCount, :reusedFrom,
                            :inputTokens, :outputTokens, :cost, :errorCode,
                            :startedAt, :finishedAt, :createdAt, :createdAt
                        )
                        """)
                .param("id", node.id()).param("runId", runId).param("logicalNodeId", node.logicalNodeId())
                .param("toolName", node.toolName()).param("toolVersion", node.toolVersion())
                .param("riskLevel", node.riskLevel()).param("required", node.required())
                .param("conditionType", node.conditionType()).param("dependencies", arrayJson(node.dependencyNodeIdsJson()))
                .param("arguments", objectJson(node.argumentTemplateJson())).param("expose", arrayJson(node.exposeOutputsJson()))
                .param("resolved", nullableJson(node.resolvedInputJson())).param("output", nullableJson(node.outputJson()))
                .param("exposed", nullableJson(node.exposedOutputJson())).param("appendix", node.promptAppendix())
                .param("sources", arrayJson(node.sourceUrlsJson())).param("citations", arrayJson(node.citationsJson()))
                .param("status", node.status()).param("attemptCount", node.attemptCount())
                .param("reusedFrom", node.reusedFromNodeId()).param("inputTokens", node.inputTokens())
                .param("outputTokens", node.outputTokens()).param("cost", cost(node.estimatedCostCny()))
                .param("errorCode", node.errorCode()).param("startedAt", nullableTs(node.startedAt()))
                .param("finishedAt", nullableTs(node.finishedAt())).param("createdAt", ts(node.createdAt())).update();
    }

    private List<WorkflowNode> nodes(UUID runId) {
        List<AttemptRow> attempts = jdbc.sql("""
                        select * from agent_workflow_node_attempt
                        where run_id = :runId order by started_at, attempt_no
                        """)
                .param("runId", runId).query((rs, row) -> attempt(rs)).list();
        return jdbc.sql("""
                        select * from agent_workflow_run_node
                        where run_id = :runId order by created_at, logical_node_id
                        """)
                .param("runId", runId).query((rs, row) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return node(rs, attempts.stream().filter(item -> id.equals(item.nodeId()))
                            .map(AttemptRow::view).toList());
                }).list();
    }

    private static WorkflowRun workflow(ResultSet rs, List<WorkflowNode> nodes) throws SQLException {
        return new WorkflowRun(
                rs.getObject("run_id", UUID.class), rs.getObject("workspace_id", UUID.class),
                rs.getObject("owner_user_id", UUID.class), rs.getObject("template_id", UUID.class),
                rs.getObject("template_version_id", UUID.class), rs.getString("template_name_snapshot"),
                rs.getInt("template_version_snapshot"), rs.getString("entry_question_snapshot"),
                rs.getString("graph_spec_snapshot"), rs.getString("input_snapshot"),
                rs.getString("tool_contract_fingerprint"), rs.getObject("request_id", UUID.class),
                rs.getObject("source_run_id", UUID.class), rs.getObject("retry_root_run_id", UUID.class),
                rs.getString("retry_from_node_id"), instant(rs, "created_at"), List.copyOf(nodes));
    }

    private static WorkflowNode node(ResultSet rs, List<NodeAttemptView> attempts) throws SQLException {
        return new WorkflowNode(
                rs.getObject("id", UUID.class), rs.getString("logical_node_id"), rs.getString("tool_name"),
                rs.getInt("tool_version"), rs.getString("risk_level"), rs.getBoolean("required"),
                rs.getString("condition_type"), rs.getString("dependency_node_ids"),
                rs.getString("argument_template"), rs.getString("expose_outputs"),
                rs.getString("resolved_input"), rs.getString("output_payload"),
                rs.getString("exposed_output"), rs.getString("prompt_appendix"),
                rs.getString("source_urls"), rs.getString("citations"), rs.getString("status"),
                rs.getInt("attempt_count"), rs.getObject("tool_call_id", UUID.class),
                rs.getObject("plan_node_id", UUID.class), rs.getObject("reused_from_node_id", UUID.class),
                rs.getLong("input_tokens"), rs.getLong("output_tokens"), rs.getBigDecimal("estimated_cost_cny"),
                rs.getString("error_code"), instant(rs, "started_at"), instant(rs, "finished_at"),
                instant(rs, "created_at"), instant(rs, "updated_at"), List.copyOf(attempts));
    }

    private static AttemptRow attempt(ResultSet rs) throws SQLException {
        return new AttemptRow(rs.getObject("workflow_node_id", UUID.class), new NodeAttemptView(
                rs.getObject("id", UUID.class), rs.getInt("attempt_no"), rs.getInt("run_attempt"),
                rs.getString("worker_id"), rs.getObject("tool_call_id", UUID.class), rs.getString("status"),
                rs.getString("resolved_input"), rs.getString("output_payload"), rs.getLong("input_tokens"),
                rs.getLong("output_tokens"), rs.getBigDecimal("estimated_cost_cny"), rs.getString("error_code"),
                instant(rs, "started_at"), instant(rs, "finished_at")));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp ts(Instant value) { return Timestamp.from(value); }
    private static Timestamp nullableTs(Instant value) { return value == null ? null : ts(value); }
    private static String objectJson(String value) { return value == null || value.isBlank() ? "{}" : value; }
    private static String arrayJson(String value) { return value == null || value.isBlank() ? "[]" : value; }
    private static String nullableJson(String value) { return value == null || value.isBlank() ? null : value; }
    private static BigDecimal cost(BigDecimal value) {
        return value == null || value.signum() < 0 ? BigDecimal.ZERO : value;
    }
    private static String attemptStatus(String status) {
        return switch (status) {
            case "SUCCEEDED", "REUSED" -> "SUCCEEDED";
            case "WAITING_APPROVAL" -> "WAITING_APPROVAL";
            case "CANCELLED" -> "CANCELLED";
            default -> "FAILED";
        };
    }

    private record NodeIdentity(UUID id, int attemptCount) { }
    private record AttemptRow(UUID nodeId, NodeAttemptView view) { }
}
