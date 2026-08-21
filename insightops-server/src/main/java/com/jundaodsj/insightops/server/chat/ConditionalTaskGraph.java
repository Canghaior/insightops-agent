package com.jundaodsj.insightops.server.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.agent.application.AgentTaskGraphValidator;
import com.jundaodsj.insightops.model.application.AgentPlanningModelGateway.PlannedToolCall;
import com.jundaodsj.insightops.tool.application.registry.AgentToolDefinition;
import com.jundaodsj.insightops.tool.application.registry.AgentToolRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Parses the Planner-only graph function into a validated, non-executable task graph. */
public final class ConditionalTaskGraph {

    public static final String FUNCTION_NAME = "submit_task_graph";

    private ConditionalTaskGraph() { }

    public static boolean isSubmission(PlannedToolCall call) {
        return call != null && FUNCTION_NAME.equals(call.name());
    }

    public static String schema(ObjectMapper objectMapper) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("type", "object");
        node.put("additionalProperties", false);
        node.put("required", List.of("id", "toolName", "arguments"));
        node.put("properties", Map.of(
                "id", Map.of("type", "string", "minLength", 1, "maxLength", 64),
                "toolName", Map.of("type", "string", "minLength", 1, "maxLength", 64),
                "arguments", Map.of("type", "object"),
                "dependsOn", Map.of("type", "array", "maxItems", 32,
                        "items", Map.of("type", "string")),
                "condition", Map.of("type", "string", "enum", List.of(
                        "ALL_SUCCESS", "ANY_SUCCESS", "ANY_FAILED",
                        "ERROR_CODE_MATCH", "ALL_TERMINAL", "ALWAYS")),
                "expectedErrorCodes", Map.of("type", "array", "maxItems", 16,
                        "items", Map.of("type", "string", "maxLength", 64)),
                "required", Map.of("type", "boolean")));
        Map<String, Object> schema = Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("nodes"),
                "properties", Map.of(
                        "reason", Map.of("type", "string", "maxLength", 500),
                        "nodes", Map.of("type", "array", "minItems", 1,
                                "maxItems", 64, "items", node)));
        try { return objectMapper.writeValueAsString(schema); }
        catch (JsonProcessingException exception) {
            throw new GraphException("GRAPH_SCHEMA_SERIALIZATION_FAILED", exception);
        }
    }

    public static Submission parse(
            PlannedToolCall call,
            ObjectMapper objectMapper,
            AgentToolRegistry registry,
            AgentToolDefinition.AccessLevel accessLevel,
            int maxNodes) {
        if (!isSubmission(call)) throw new GraphException("GRAPH_SUBMISSION_REQUIRED");
        final JsonNode root;
        try { root = objectMapper.readTree(call.argumentsJson()); }
        catch (JsonProcessingException exception) {
            throw new GraphException("GRAPH_ARGUMENTS_INVALID", exception);
        }
        JsonNode nodesJson = root == null ? null : root.get("nodes");
        if (nodesJson == null || !nodesJson.isArray() || nodesJson.isEmpty()) {
            throw new GraphException("GRAPH_NODES_REQUIRED");
        }
        if (nodesJson.size() > maxNodes) throw new GraphException("GRAPH_NODE_LIMIT_EXCEEDED");

        Set<String> allowedTools = registry.availableTo(accessLevel).stream()
                .map(AgentToolDefinition::name).collect(java.util.stream.Collectors.toSet());
        Map<String, UUID> ids = new LinkedHashMap<>();
        for (JsonNode json : nodesJson) {
            String logicalId = text(json, "id");
            if (logicalId == null || !logicalId.matches("[A-Za-z0-9_-]{1,64}")) {
                throw new GraphException("GRAPH_NODE_ID_INVALID");
            }
            if (ids.putIfAbsent(logicalId, UUID.randomUUID()) != null) {
                throw new GraphException("GRAPH_NODE_ID_DUPLICATE");
            }
        }

        List<Node> nodes = new ArrayList<>();
        for (JsonNode json : nodesJson) {
            String logicalId = text(json, "id");
            String toolName = text(json, "toolName");
            if (toolName == null || FUNCTION_NAME.equals(toolName) || !allowedTools.contains(toolName)) {
                throw new GraphException("GRAPH_TOOL_NOT_ALLOWED");
            }
            JsonNode arguments = json.get("arguments");
            if (arguments == null || !arguments.isObject()) {
                throw new GraphException("GRAPH_TOOL_ARGUMENTS_INVALID");
            }
            List<String> dependencyNames = strings(json.get("dependsOn"));
            List<UUID> dependencyIds = dependencyNames.stream().map(name -> {
                UUID id = ids.get(name);
                if (id == null) throw new GraphException("GRAPH_DEPENDENCY_MISSING");
                return id;
            }).toList();
            Condition condition = condition(text(json, "condition"), dependencyIds.isEmpty());
            List<String> expectedErrors = strings(json.get("expectedErrorCodes"));
            if (condition == Condition.ERROR_CODE_MATCH && expectedErrors.isEmpty()) {
                throw new GraphException("GRAPH_EXPECTED_ERROR_CODES_REQUIRED");
            }
            boolean required = !json.has("required") || json.get("required").asBoolean(true);
            nodes.add(new Node(ids.get(logicalId), logicalId, toolName, arguments.toString(),
                    dependencyIds, condition, expectedErrors, required,
                    registry.find(toolName).map(AgentToolDefinition::riskLevel)
                            .map(Enum::name).orElse("UNKNOWN")));
        }
        AgentTaskGraphValidator.validate(nodes.stream()
                .map(node -> new AgentTaskGraphValidator.Node(node.id(), node.dependencyIds())).toList(), maxNodes);
        validateMutationExclusivity(nodes);
        return new Submission(call, limited(text(root, "reason"), 500), List.copyOf(nodes));
    }

    public static boolean conditionMatches(Node node, Map<UUID, NodeResult> results) {
        if (node.dependencyIds().isEmpty()) return true;
        List<NodeResult> dependencies = node.dependencyIds().stream().map(results::get).toList();
        if (dependencies.stream().anyMatch(java.util.Objects::isNull)) return false;
        return switch (node.condition()) {
            case ALL_SUCCESS -> dependencies.stream().allMatch(NodeResult::succeeded);
            case ANY_SUCCESS -> dependencies.stream().anyMatch(NodeResult::succeeded);
            case ANY_FAILED -> dependencies.stream().anyMatch(NodeResult::failed);
            case ERROR_CODE_MATCH -> dependencies.stream().map(NodeResult::errorCode)
                    .filter(java.util.Objects::nonNull).anyMatch(node.expectedErrorCodes()::contains);
            case ALL_TERMINAL, ALWAYS -> true;
        };
    }

    public static List<List<Node>> waves(List<Node> nodes) {
        List<List<Node>> waves = new ArrayList<>();
        Set<UUID> emitted = new HashSet<>();
        while (emitted.size() < nodes.size()) {
            List<Node> wave = nodes.stream().filter(node -> !emitted.contains(node.id()))
                    .filter(node -> emitted.containsAll(node.dependencyIds())).toList();
            if (wave.isEmpty()) throw new GraphException("GRAPH_CYCLE_DETECTED");
            waves.add(wave);
            wave.forEach(node -> emitted.add(node.id()));
        }
        return List.copyOf(waves);
    }

    private static void validateMutationExclusivity(List<Node> nodes) {
        for (List<Node> wave : waves(nodes)) {
            long mutating = wave.stream().filter(node -> "MUTATING".equals(node.riskLevel())).count();
            if (mutating > 0 && wave.size() > 1) {
                throw new GraphException("GRAPH_MUTATING_NODE_NOT_EXCLUSIVE");
            }
        }
    }

    private static Condition condition(String value, boolean root) {
        if (root) return Condition.ALWAYS;
        if (value == null || value.isBlank()) return Condition.ALL_SUCCESS;
        try { return Condition.valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) { throw new GraphException("GRAPH_CONDITION_INVALID"); }
    }

    private static String text(JsonNode node, String name) {
        if (node == null || !node.has(name) || node.get(name).isNull()) return null;
        return node.get(name).asText();
    }

    private static List<String> strings(JsonNode value) {
        if (value == null || value.isNull()) return List.of();
        if (!value.isArray()) throw new GraphException("GRAPH_ARRAY_INVALID");
        List<String> values = new ArrayList<>();
        value.forEach(item -> {
            String text = item.asText(null);
            if (text == null || text.isBlank() || text.length() > 64) {
                throw new GraphException("GRAPH_ARRAY_VALUE_INVALID");
            }
            values.add(text);
        });
        return List.copyOf(values);
    }

    private static String limited(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    public enum Condition {
        ALL_SUCCESS, ANY_SUCCESS, ANY_FAILED, ERROR_CODE_MATCH, ALL_TERMINAL, ALWAYS
    }

    public record Submission(PlannedToolCall providerCall, String reason, List<Node> nodes) { }

    public record Node(
            UUID id,
            String logicalId,
            String toolName,
            String argumentsJson,
            List<UUID> dependencyIds,
            Condition condition,
            List<String> expectedErrorCodes,
            boolean required,
            String riskLevel) { }

    public record NodeResult(String status, String errorCode) {
        public boolean succeeded() { return "SUCCEEDED".equals(status); }
        public boolean failed() { return "FAILED".equals(status) || "CANCELLED".equals(status); }
    }

    public static final class GraphException extends IllegalArgumentException {
        private final String errorCode;
        public GraphException(String errorCode) { super(errorCode); this.errorCode = errorCode; }
        public GraphException(String errorCode, Throwable cause) {
            super(errorCode, cause); this.errorCode = errorCode;
        }
        public String errorCode() { return errorCode; }
    }
}
