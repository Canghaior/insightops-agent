package com.jundaodsj.insightops.tool.application.registry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class AgentToolRegistry {

    private final Map<String, AgentToolDefinition> definitions;

    public AgentToolRegistry(Collection<AgentToolDefinition> definitions) {
        if (definitions == null) throw new IllegalArgumentException("definitions must not be null");
        List<AgentToolDefinition> sorted = new ArrayList<>();
        for (AgentToolDefinition definition : definitions) {
            if (definition == null) {
                throw new IllegalArgumentException("definitions must not contain null");
            }
            sorted.add(definition);
        }
        sorted.sort(Comparator.comparing(AgentToolDefinition::name));
        LinkedHashMap<String, AgentToolDefinition> byName = new LinkedHashMap<>();
        for (AgentToolDefinition definition : sorted) {
            if (byName.putIfAbsent(definition.name(), definition) != null) {
                throw new ToolRegistryException(
                        ErrorCode.DUPLICATE_TOOL, "duplicate tool: " + definition.name());
            }
        }
        this.definitions = Collections.unmodifiableMap(byName);
    }

    public List<AgentToolDefinition> definitions() {
        return List.copyOf(definitions.values());
    }

    public Optional<AgentToolDefinition> find(String name) {
        return Optional.ofNullable(definitions.get(name));
    }

    public AgentToolDefinition requireEnabled(String name) {
        AgentToolDefinition definition = definitions.get(name);
        if (definition == null) {
            throw new ToolRegistryException(ErrorCode.UNKNOWN_TOOL, "unknown tool: " + name);
        }
        if (!definition.enabled()) {
            throw new ToolRegistryException(ErrorCode.TOOL_DISABLED, "tool is disabled: " + name);
        }
        return definition;
    }

    public Map<String, Object> validateInput(
            String name,
            AgentToolDefinition.AccessLevel grantedAccess,
            Map<String, ?> input) {
        AgentToolDefinition definition = requireEnabled(name);
        if (grantedAccess == null || !grantedAccess.permits(definition.accessLevel())) {
            throw new ToolRegistryException(ErrorCode.ACCESS_DENIED, "access denied for tool: " + name);
        }
        try {
            return definition.validateInput(input);
        } catch (IllegalArgumentException exception) {
            throw new ToolRegistryException(
                    ErrorCode.INPUT_INVALID, "invalid input for " + name + ": " + exception.getMessage(),
                    exception);
        }
    }

    public List<AgentToolDefinition> availableTo(AgentToolDefinition.AccessLevel grantedAccess) {
        if (grantedAccess == null) return List.of();
        return definitions.values().stream()
                .filter(AgentToolDefinition::enabled)
                .filter(definition -> grantedAccess.permits(definition.accessLevel()))
                .toList();
    }

    public List<Map<String, Object>> modelToolSchemas(
            AgentToolDefinition.AccessLevel grantedAccess) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentToolDefinition definition : availableTo(grantedAccess)) {
            LinkedHashMap<String, Object> function = new LinkedHashMap<>();
            function.put("name", definition.name());
            function.put("description", definition.description());
            function.put("parameters", definition.inputSchema());
            result.add(Map.of("type", "function", "function", function));
        }
        return List.copyOf(result);
    }

    public enum ErrorCode {
        DUPLICATE_TOOL,
        UNKNOWN_TOOL,
        TOOL_DISABLED,
        ACCESS_DENIED,
        INPUT_INVALID
    }

    public static final class ToolRegistryException extends IllegalArgumentException {
        private final ErrorCode code;

        public ToolRegistryException(ErrorCode code, String message) {
            super(message);
            this.code = code;
        }

        public ToolRegistryException(ErrorCode code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public ErrorCode code() {
            return code;
        }
    }
}
