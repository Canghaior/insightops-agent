package com.jundaodsj.insightops.server.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jundaodsj.insightops.tool.application.registry.AgentToolDefinition;
import com.jundaodsj.insightops.tool.application.registry.AgentToolRegistry;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AgentWorkflowExpressionService {

    private static final Pattern EXPRESSION = Pattern.compile("\\$\\{([A-Za-z0-9_-]{1,64})\\.([A-Za-z0-9_.-]{1,256})}");
    private final ObjectMapper json;
    private final AgentToolRegistry registry;

    public AgentWorkflowExpressionService(ObjectMapper json, AgentToolRegistry registry) {
        this.json = json;
        this.registry = registry;
    }

    public Graph validateGraph(String graphSpecJson) {
        JsonNode root = readObject(graphSpecJson, "WORKFLOW_GRAPH_INVALID");
        Map<String, InputDefinition> inputs = inputs(root.path("inputs"));
        JsonNode nodesJson = root.path("nodes");
        if (!nodesJson.isArray() || nodesJson.isEmpty()) {
            throw new WorkflowExpressionException("WORKFLOW_NODES_REQUIRED");
        }
        Map<String, NodeDefinition> nodes = new LinkedHashMap<>();
        for (JsonNode item : nodesJson) {
            String id = requiredText(item, "id", "WORKFLOW_NODE_ID_INVALID");
            String toolName = requiredText(item, "toolName", "WORKFLOW_TOOL_REQUIRED");
            AgentToolDefinition tool = registry.requireEnabled(toolName);
            List<String> dependsOn = strings(item.path("dependsOn"));
            List<String> expose = strings(item.path("exposeOutputs"));
            Set<String> outputNames = tool.outputParameters().stream()
                    .map(AgentToolDefinition.Parameter::name)
                    .collect(java.util.stream.Collectors.toSet());
            if (!outputNames.containsAll(expose)) {
                throw new WorkflowExpressionException("WORKFLOW_EXPOSE_OUTPUT_INVALID:" + id);
            }
            JsonNode arguments = item.path("arguments");
            if (!arguments.isObject()) {
                throw new WorkflowExpressionException("WORKFLOW_TOOL_ARGUMENTS_INVALID:" + id);
            }
            NodeDefinition previous = nodes.putIfAbsent(id, new NodeDefinition(
                    id, toolName, arguments.deepCopy(), dependsOn, expose,
                    item.path("condition").asText(dependsOn.isEmpty() ? "ALWAYS" : "ALL_SUCCESS"),
                    !item.has("required") || item.path("required").asBoolean(true),
                    tool.version(), tool.riskLevel().name()));
            if (previous != null) throw new WorkflowExpressionException("WORKFLOW_NODE_ID_DUPLICATE");
        }
        for (NodeDefinition node : nodes.values()) {
            for (String dependency : node.dependsOn()) {
                if (!nodes.containsKey(dependency) || dependency.equals(node.id())) {
                    throw new WorkflowExpressionException("WORKFLOW_DEPENDENCY_INVALID:" + node.id());
                }
            }
            validateExpressions(node.arguments(), node, nodes, inputs);
        }
        return new Graph(root.deepCopy(), Map.copyOf(inputs), List.copyOf(nodes.values()));
    }

    public Map<String, Object> validateInputs(Graph graph, Map<String, Object> supplied) {
        Map<String, Object> values = supplied == null ? Map.of() : supplied;
        Set<String> unknown = new LinkedHashSet<>(values.keySet());
        unknown.removeAll(graph.inputs().keySet());
        if (!unknown.isEmpty()) throw new WorkflowExpressionException("WORKFLOW_INPUT_UNKNOWN:" + unknown);
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (InputDefinition definition : graph.inputs().values()) {
            Object value = values.get(definition.name());
            if (value == null && definition.defaultValue() != null) {
                value = json.convertValue(definition.defaultValue(), Object.class);
            }
            if (value == null) {
                if (definition.required()) {
                    throw new WorkflowExpressionException("WORKFLOW_INPUT_MISSING:" + definition.name());
                }
                continue;
            }
            validateValue(definition, value);
            normalized.put(definition.name(), immutable(value));
        }
        return Map.copyOf(normalized);
    }

    public String resolveText(String template, Map<String, Object> inputs) {
        if (template == null || template.isBlank()) return "";
        return interpolate(template, reference -> value(reference, inputs, Map.of()));
    }

    public void validateArgumentContracts(Graph graph) {
        Map<String, Object> inputSamples = new LinkedHashMap<>();
        graph.inputs().values().forEach(definition ->
                inputSamples.put(definition.name(), sample(definition)));
        Map<String, Map<String, Object>> outputSamples = new LinkedHashMap<>();
        for (NodeDefinition node : graph.nodes()) {
            AgentToolDefinition tool = registry.requireEnabled(node.toolName());
            Map<String, Object> exposed = new LinkedHashMap<>();
            for (String name : node.exposeOutputs()) {
                AgentToolDefinition.Parameter parameter = tool.outputParameters().stream()
                        .filter(item -> item.name().equals(name)).findFirst().orElseThrow();
                exposed.put(name, sample(parameter));
            }
            outputSamples.put(node.id(), Map.copyOf(exposed));
        }
        for (NodeDefinition node : graph.nodes()) {
            try { resolveArguments(node, inputSamples, outputSamples); }
            catch (WorkflowExpressionException exception) {
                throw new WorkflowExpressionException(
                        "WORKFLOW_TOOL_ARGUMENTS_INVALID:" + node.id(), exception);
            }
        }
    }

    public Map<String, Object> resolveArguments(
            NodeDefinition node, Map<String, Object> inputs,
            Map<String, Map<String, Object>> exposedOutputs) {
        JsonNode resolved = resolveNode(node.arguments(), inputs, exposedOutputs);
        @SuppressWarnings("unchecked")
        Map<String, Object> values = json.convertValue(resolved, LinkedHashMap.class);
        try {
            return registry.validateInput(node.toolName(),
                    AgentToolDefinition.AccessLevel.SYSTEM_ADMIN, values);
        }
        catch (AgentToolRegistry.ToolRegistryException exception) {
            throw new WorkflowExpressionException("WORKFLOW_INPUT_TYPE_MISMATCH:" + node.id(), exception);
        }
    }

    public Map<String, Object> expose(NodeDefinition node, Map<String, Object> output) {
        Map<String, Object> exposed = new LinkedHashMap<>();
        for (String name : node.exposeOutputs()) {
            if (!output.containsKey(name)) {
                throw new WorkflowExpressionException("WORKFLOW_OUTPUT_MISSING:" + node.id() + "." + name);
            }
            exposed.put(name, immutable(output.get(name)));
        }
        return Map.copyOf(exposed);
    }

    public String contractFingerprint(Graph graph) {
        List<Map<String, Object>> contracts = graph.nodes().stream().map(node -> {
            AgentToolDefinition tool = registry.requireEnabled(node.toolName());
            return Map.<String, Object>of(
                    "name", tool.name(), "version", tool.version(),
                    "input", tool.inputSchema(), "output", tool.outputSchema(),
                    "risk", tool.riskLevel().name(), "approval", tool.approvalPolicy().name());
        }).toList();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsBytes(contracts)));
        }
        catch (NoSuchAlgorithmException | JsonProcessingException exception) {
            throw new IllegalStateException("WORKFLOW_CONTRACT_FINGERPRINT_FAILED", exception);
        }
    }

    private void validateExpressions(
            JsonNode value, NodeDefinition node, Map<String, NodeDefinition> nodes,
            Map<String, InputDefinition> inputs) {
        if (value.isContainerNode()) {
            value.forEach(child -> validateExpressions(child, node, nodes, inputs));
            return;
        }
        if (!value.isTextual()) return;
        Matcher matcher = EXPRESSION.matcher(value.textValue());
        while (matcher.find()) {
            String root = matcher.group(1);
            String path = matcher.group(2);
            if ("inputs".equals(root)) {
                String inputName = path.split("\\.")[0];
                if (!inputs.containsKey(inputName)) {
                    throw new WorkflowExpressionException("WORKFLOW_INPUT_REFERENCE_INVALID:" + inputName);
                }
                continue;
            }
            NodeDefinition source = nodes.get(root);
            if (source == null || !node.dependsOn().contains(root) || !path.startsWith("output.")) {
                throw new WorkflowExpressionException("WORKFLOW_OUTPUT_REFERENCE_INVALID:" + node.id());
            }
            String field = path.substring("output.".length()).split("\\.")[0];
            if (!source.exposeOutputs().contains(field)) {
                throw new WorkflowExpressionException("WORKFLOW_OUTPUT_NOT_EXPOSED:" + root + "." + field);
            }
        }
        if (value.textValue().contains("${") && !matcher(value.textValue()).find()) {
            throw new WorkflowExpressionException("WORKFLOW_EXPRESSION_INVALID:" + node.id());
        }
    }

    private JsonNode resolveNode(
            JsonNode node, Map<String, Object> inputs,
            Map<String, Map<String, Object>> outputs) {
        if (node.isObject()) {
            ObjectNode result = json.createObjectNode();
            node.fields().forEachRemaining(entry ->
                    result.set(entry.getKey(), resolveNode(entry.getValue(), inputs, outputs)));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = json.createArrayNode();
            node.forEach(item -> result.add(resolveNode(item, inputs, outputs)));
            return result;
        }
        if (!node.isTextual()) return node.deepCopy();
        String text = node.textValue();
        Matcher exact = matcher(text);
        if (exact.matches()) return json.valueToTree(value(exact, inputs, outputs));
        return json.getNodeFactory().textNode(interpolate(text, ref -> value(ref, inputs, outputs)));
    }

    private Object value(Matcher matcher, Map<String, Object> inputs,
                         Map<String, Map<String, Object>> outputs) {
        String root = matcher.group(1);
        String path = matcher.group(2);
        if ("inputs".equals(root)) return nested(inputs, path);
        if (!path.startsWith("output.")) {
            throw new WorkflowExpressionException("WORKFLOW_EXPRESSION_INVALID");
        }
        Map<String, Object> output = outputs.get(root);
        if (output == null) throw new WorkflowExpressionException("WORKFLOW_INPUT_MISSING:" + root);
        return nested(output, path.substring("output.".length()));
    }

    private Object value(String reference, Map<String, Object> inputs,
                         Map<String, Map<String, Object>> outputs) {
        Matcher matcher = matcher(reference);
        if (!matcher.matches()) throw new WorkflowExpressionException("WORKFLOW_EXPRESSION_INVALID");
        return value(matcher, inputs, outputs);
    }

    private static Object nested(Map<String, Object> source, String path) {
        Object current = source;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(part)) {
                throw new WorkflowExpressionException("WORKFLOW_INPUT_MISSING:" + path);
            }
            current = map.get(part);
        }
        return current;
    }

    private static String interpolate(String template, java.util.function.Function<String, Object> resolver) {
        Matcher matcher = EXPRESSION.matcher(template);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            Object value = resolver.apply(matcher.group());
            if (value instanceof Map<?, ?> || value instanceof List<?>) {
                throw new WorkflowExpressionException("WORKFLOW_INPUT_TYPE_MISMATCH");
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(String.valueOf(value)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private Map<String, InputDefinition> inputs(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) return Map.of();
        if (!value.isObject()) throw new WorkflowExpressionException("WORKFLOW_INPUT_SCHEMA_INVALID");
        Map<String, InputDefinition> inputs = new LinkedHashMap<>();
        value.fields().forEachRemaining(entry -> {
            JsonNode definition = entry.getValue();
            String type = definition.path("type").asText("string");
            if (!List.of("string", "integer", "boolean", "string_array", "json", "json_array").contains(type)) {
                throw new WorkflowExpressionException("WORKFLOW_INPUT_TYPE_INVALID:" + entry.getKey());
            }
            inputs.put(entry.getKey(), new InputDefinition(
                    entry.getKey(), type, definition.path("required").asBoolean(false),
                    definition.has("maxLength") ? definition.path("maxLength").asInt() : null,
                    definition.has("minimum") ? definition.path("minimum").asLong() : null,
                    definition.has("maximum") ? definition.path("maximum").asLong() : null,
                    definition.get("default")));
        });
        return Map.copyOf(inputs);
    }

    private static void validateValue(InputDefinition definition, Object value) {
        boolean type = switch (definition.type()) {
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "string_array" -> value instanceof List<?> list && list.stream().allMatch(String.class::isInstance);
            case "json" -> value instanceof Map<?, ?>;
            case "json_array" -> value instanceof List<?>;
            default -> false;
        };
        if (!type) throw new WorkflowExpressionException("WORKFLOW_INPUT_TYPE_MISMATCH:" + definition.name());
        if (value instanceof String text && definition.maxLength() != null
                && text.length() > definition.maxLength()) {
            throw new WorkflowExpressionException("WORKFLOW_INPUT_TOO_LONG:" + definition.name());
        }
        if (value instanceof Number number) {
            long candidate = number.longValue();
            if (definition.minimum() != null && candidate < definition.minimum()
                    || definition.maximum() != null && candidate > definition.maximum()) {
                throw new WorkflowExpressionException("WORKFLOW_INPUT_OUT_OF_RANGE:" + definition.name());
            }
        }
    }

    private static Object sample(InputDefinition definition) {
        return switch (definition.type()) {
            case "string" -> "sample";
            case "integer" -> definition.minimum() == null ? 0L : definition.minimum();
            case "boolean" -> false;
            case "string_array" -> List.of("sample");
            case "json" -> Map.of();
            case "json_array" -> List.of(Map.of());
            default -> throw new WorkflowExpressionException("WORKFLOW_INPUT_TYPE_INVALID");
        };
    }

    private static Object sample(AgentToolDefinition.Parameter parameter) {
        return switch (parameter.type()) {
            case STRING -> "sample";
            case INTEGER -> parameter.minimum() == null ? 0 : parameter.minimum();
            case BOOLEAN -> false;
            case STRING_ARRAY -> List.of("sample");
            case JSON -> Map.of();
            case JSON_ARRAY -> List.of(Map.of());
        };
    }

    private JsonNode readObject(String value, String errorCode) {
        try {
            JsonNode parsed = json.readTree(value);
            if (parsed == null || !parsed.isObject()) throw new WorkflowExpressionException(errorCode);
            return parsed;
        }
        catch (JsonProcessingException exception) {
            throw new WorkflowExpressionException(errorCode, exception);
        }
    }

    private static String requiredText(JsonNode node, String field, String errorCode) {
        String value = node.path(field).asText("").strip();
        if (value.isEmpty()) throw new WorkflowExpressionException(errorCode);
        return value;
    }

    private static List<String> strings(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return List.of();
        if (!node.isArray()) throw new WorkflowExpressionException("WORKFLOW_ARRAY_INVALID");
        List<String> result = new ArrayList<>();
        node.forEach(item -> result.add(item.asText()));
        return List.copyOf(result);
    }

    private static Matcher matcher(String value) { return EXPRESSION.matcher(value); }
    private static Object immutable(Object value) {
        if (value instanceof Map<?, ?> map) return Map.copyOf(map);
        if (value instanceof List<?> list) return List.copyOf(list);
        return value;
    }

    public record Graph(JsonNode root, Map<String, InputDefinition> inputs, List<NodeDefinition> nodes) { }
    public record InputDefinition(
            String name, String type, boolean required, Integer maxLength,
            Long minimum, Long maximum, JsonNode defaultValue) { }
    public record NodeDefinition(
            String id, String toolName, JsonNode arguments, List<String> dependsOn,
            List<String> exposeOutputs, String condition, boolean required,
            int toolVersion, String riskLevel) { }

    public static final class WorkflowExpressionException extends IllegalArgumentException {
        public WorkflowExpressionException(String message) { super(message); }
        public WorkflowExpressionException(String message, Throwable cause) { super(message, cause); }
    }
}
