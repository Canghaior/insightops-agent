package com.jundaodsj.insightops.agent.application;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Validates plan graphs before nodes are scheduled. */
public final class AgentTaskGraphValidator {

    private AgentTaskGraphValidator() {
    }

    public static void validate(Collection<Node> nodes, int maxNodes) {
        if (nodes == null) throw new IllegalArgumentException("nodes must not be null");
        if (maxNodes < 1) throw new IllegalArgumentException("maxNodes must be positive");
        if (nodes.size() > maxNodes) {
            throw new GraphValidationException("PLAN_NODE_LIMIT_EXCEEDED");
        }
        Map<UUID, Node> byId = new HashMap<>();
        for (Node node : nodes) {
            if (node == null || node.id() == null) {
                throw new GraphValidationException("PLAN_NODE_INVALID");
            }
            if (byId.putIfAbsent(node.id(), node) != null) {
                throw new GraphValidationException("PLAN_NODE_DUPLICATE");
            }
        }

        Map<UUID, Integer> inDegree = new HashMap<>();
        Map<UUID, Set<UUID>> outgoing = new HashMap<>();
        for (Node node : nodes) {
            inDegree.put(node.id(), 0);
            outgoing.put(node.id(), new HashSet<>());
        }
        for (Node node : nodes) {
            Set<UUID> uniqueDependencies = new HashSet<>();
            for (UUID dependencyId : node.dependencyIds()) {
                if (dependencyId == null || !byId.containsKey(dependencyId)) {
                    throw new GraphValidationException("PLAN_DEPENDENCY_MISSING");
                }
                if (node.id().equals(dependencyId)) {
                    throw new GraphValidationException("PLAN_SELF_DEPENDENCY");
                }
                if (uniqueDependencies.add(dependencyId)
                        && outgoing.get(dependencyId).add(node.id())) {
                    inDegree.compute(node.id(), (ignored, value) -> value + 1);
                }
            }
        }

        ArrayDeque<UUID> ready = new ArrayDeque<>();
        inDegree.forEach((id, degree) -> {
            if (degree == 0) ready.add(id);
        });
        int visited = 0;
        while (!ready.isEmpty()) {
            UUID id = ready.removeFirst();
            visited++;
            for (UUID dependent : outgoing.get(id)) {
                int degree = inDegree.compute(dependent, (ignored, value) -> value - 1);
                if (degree == 0) ready.addLast(dependent);
            }
        }
        if (visited != nodes.size()) {
            throw new GraphValidationException("PLAN_DEPENDENCY_CYCLE");
        }
    }

    public record Node(UUID id, List<UUID> dependencyIds) {
        public Node {
            dependencyIds = dependencyIds == null ? List.of() : List.copyOf(dependencyIds);
        }
    }

    public static final class GraphValidationException extends IllegalArgumentException {
        private final String errorCode;

        public GraphValidationException(String errorCode) {
            super(errorCode);
            this.errorCode = errorCode;
        }

        public String errorCode() {
            return errorCode;
        }
    }
}
