package com.jundaodsj.insightops.agent.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Durable plan graph and budget ledger for a single Agent run. */
public interface AgentOrchestrationStore {

    PlanHandle startRun(UUID runId, RunLimits limits, Instant startedAt);

    List<PlanNode> appendLayer(
            UUID planId,
            UUID runId,
            int round,
            List<NodeDraft> nodes,
            List<UUID> dependencyIds,
            Instant createdAt);

    void updateNode(
            UUID nodeId,
            String status,
            UUID toolCallId,
            String errorCode,
            Instant updatedAt);

    void updateBudget(UUID runId, BudgetSnapshot budget, Instant updatedAt);

    void finishPlan(UUID planId, String status, Instant finishedAt);

    record RunLimits(
            int maxNodes,
            int maxParallelism,
            int maxToolAttempts,
            long maxModelTokens,
            BigDecimal maxEstimatedCostCny) {
    }

    record BudgetSnapshot(
            int usedNodes,
            int usedToolAttempts,
            long usedModelTokens,
            BigDecimal estimatedCostCny,
            String status,
            String exhaustionReason) {
    }

    record PlanHandle(UUID planId, int version) {
    }

    record NodeDraft(
            UUID id,
            String providerToolCallId,
            int position,
            String toolName,
            String riskLevel,
            boolean required,
            String inputPayload) {
    }

    record PlanNode(
            UUID id,
            int round,
            int position,
            String toolName,
            String status,
            List<UUID> dependencyIds) {
    }
}
