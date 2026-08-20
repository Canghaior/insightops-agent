package com.jundaodsj.insightops.tool.application.registry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public record AgentToolDefinition(
        String name,
        int version,
        String description,
        boolean enabled,
        AccessLevel accessLevel,
        RiskLevel riskLevel,
        ApprovalPolicy approvalPolicy,
        Duration timeout,
        int maxResultCharacters,
        List<Parameter> inputParameters,
        List<Parameter> outputParameters) {

    private static final Pattern NAME_PATTERN = Pattern.compile("[a-z][a-z0-9_]{2,63}");

    public AgentToolDefinition {
        name = requireText(name, "name");
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "tool name must match " + NAME_PATTERN.pattern());
        }
        if (version < 1) throw new IllegalArgumentException("version must be positive");
        description = requireText(description, "description");
        accessLevel = Objects.requireNonNull(accessLevel, "accessLevel");
        riskLevel = Objects.requireNonNull(riskLevel, "riskLevel");
        approvalPolicy = Objects.requireNonNull(approvalPolicy, "approvalPolicy");
        timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.compareTo(Duration.ofMillis(100)) < 0
                || timeout.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("timeout must be between 100 ms and 2 minutes");
        }
        if (maxResultCharacters < 1_000 || maxResultCharacters > 1_000_000) {
            throw new IllegalArgumentException(
                    "maxResultCharacters must be between 1000 and 1000000");
        }
        if (riskLevel == RiskLevel.MUTATING
                && approvalPolicy != ApprovalPolicy.REQUIRED) {
            throw new IllegalArgumentException("mutating tools must require approval");
        }
        inputParameters = validatedParameters(inputParameters, "inputParameters");
        outputParameters = validatedParameters(outputParameters, "outputParameters");
    }

    public Map<String, Object> inputSchema() {
        return schema(inputParameters);
    }

    public Map<String, Object> outputSchema() {
        return schema(outputParameters);
    }

    public Map<String, Object> validateInput(Map<String, ?> input) {
        return validate(inputParameters, input, "input");
    }

    public Map<String, Object> validateOutput(Map<String, ?> output) {
        return validate(outputParameters, output, "output");
    }

    private static Map<String, Object> validate(
            List<Parameter> parameters, Map<String, ?> values, String label) {
        if (values == null) throw new IllegalArgumentException(label + " must not be null");
        Map<String, Parameter> expected = new LinkedHashMap<>();
        for (Parameter parameter : parameters) expected.put(parameter.name(), parameter);
        Set<String> unknown = new LinkedHashSet<>(values.keySet());
        unknown.removeAll(expected.keySet());
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(label + " contains unknown fields: " + unknown);
        }
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        for (Parameter parameter : parameters) {
            Object value = values.get(parameter.name());
            if (value == null) {
                if (parameter.required()) {
                    throw new IllegalArgumentException(
                            label + " is missing required field: " + parameter.name());
                }
                continue;
            }
            parameter.validate(value, label);
            copy.put(parameter.name(), immutableValue(value));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof List<?> list) return List.copyOf(list);
        if (value instanceof Map<?, ?> map) return Collections.unmodifiableMap(new LinkedHashMap<>(map));
        return value;
    }

    private static Map<String, Object> schema(List<Parameter> parameters) {
        LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Parameter parameter : parameters) {
            properties.put(parameter.name(), parameter.schema());
            if (parameter.required()) required.add(parameter.name());
        }
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Collections.unmodifiableMap(properties));
        schema.put("required", List.copyOf(required));
        schema.put("additionalProperties", false);
        return Collections.unmodifiableMap(schema);
    }

    private static List<Parameter> validatedParameters(
            Collection<Parameter> parameters, String label) {
        if (parameters == null) throw new IllegalArgumentException(label + " must not be null");
        List<Parameter> copy = List.copyOf(parameters);
        Set<String> names = new LinkedHashSet<>();
        for (Parameter parameter : copy) {
            Objects.requireNonNull(parameter, label + " must not contain null");
            if (!names.add(parameter.name())) {
                throw new IllegalArgumentException(label + " contains duplicate: " + parameter.name());
            }
        }
        return copy;
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.strip();
    }

    public enum AccessLevel {
        WORKSPACE_MEMBER,
        WORKSPACE_OWNER,
        SYSTEM_ADMIN;

        public boolean permits(AccessLevel required) {
            return ordinal() >= Objects.requireNonNull(required, "required").ordinal();
        }
    }

    public enum RiskLevel {
        READ_ONLY,
        MUTATING
    }

    public enum ApprovalPolicy {
        NOT_REQUIRED,
        REQUIRED
    }

    public enum ValueType {
        STRING("string"),
        INTEGER("integer"),
        BOOLEAN("boolean"),
        STRING_ARRAY("array"),
        JSON("object"),
        JSON_ARRAY("array");

        private final String jsonType;

        ValueType(String jsonType) {
            this.jsonType = jsonType;
        }
    }

    public record Parameter(
            String name,
            ValueType type,
            String description,
            boolean required,
            Integer minimum,
            Integer maximum,
            Integer maxLength,
            Integer maxItems) {

        private static final Pattern PARAMETER_PATTERN =
                Pattern.compile("[a-z][a-zA-Z0-9]{0,63}");

        public Parameter {
            name = requireText(name, "parameter name");
            if (!PARAMETER_PATTERN.matcher(name).matches()) {
                throw new IllegalArgumentException("invalid parameter name: " + name);
            }
            type = Objects.requireNonNull(type, "type");
            description = requireText(description, "parameter description");
            positiveOrNull(maxLength, "maxLength");
            positiveOrNull(maxItems, "maxItems");
            if (minimum != null && maximum != null && minimum > maximum) {
                throw new IllegalArgumentException("minimum must not exceed maximum");
            }
            if (type != ValueType.INTEGER && (minimum != null || maximum != null)) {
                throw new IllegalArgumentException("minimum/maximum require INTEGER");
            }
            if (type != ValueType.STRING && maxLength != null) {
                throw new IllegalArgumentException("maxLength requires STRING");
            }
            if (type != ValueType.STRING_ARRAY && maxItems != null) {
                throw new IllegalArgumentException("maxItems requires STRING_ARRAY");
            }
        }

        public static Parameter string(
                String name, String description, boolean required, int maxLength) {
            return new Parameter(name, ValueType.STRING, description, required,
                    null, null, maxLength, null);
        }

        public static Parameter integer(
                String name, String description, boolean required, int minimum, int maximum) {
            return new Parameter(name, ValueType.INTEGER, description, required,
                    minimum, maximum, null, null);
        }

        public static Parameter bool(String name, String description, boolean required) {
            return new Parameter(name, ValueType.BOOLEAN, description, required,
                    null, null, null, null);
        }

        public static Parameter stringArray(
                String name, String description, boolean required, int maxItems) {
            return new Parameter(name, ValueType.STRING_ARRAY, description, required,
                    null, null, null, maxItems);
        }

        public static Parameter json(String name, String description, boolean required) {
            return new Parameter(name, ValueType.JSON, description, required,
                    null, null, null, null);
        }

        public static Parameter jsonArray(String name, String description, boolean required) {
            return new Parameter(name, ValueType.JSON_ARRAY, description, required,
                    null, null, null, null);
        }


        private Map<String, Object> schema() {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.put("type", type.jsonType);
            result.put("description", description);
            if (type == ValueType.STRING_ARRAY) {
                result.put("items", Map.of("type", "string"));
            }
            if (minimum != null) result.put("minimum", minimum);
            if (maximum != null) result.put("maximum", maximum);
            if (maxLength != null) result.put("maxLength", maxLength);
            if (maxItems != null) result.put("maxItems", maxItems);
            return Collections.unmodifiableMap(result);
        }

        private void validate(Object value, String label) {
            boolean valid = switch (type) {
                case STRING -> value instanceof String;
                case INTEGER -> value instanceof Byte || value instanceof Short
                        || value instanceof Integer || value instanceof Long;
                case BOOLEAN -> value instanceof Boolean;
                case STRING_ARRAY -> value instanceof List<?> list
                        && list.stream().allMatch(String.class::isInstance);
                case JSON -> value instanceof Map<?, ?>;
                case JSON_ARRAY -> value instanceof List<?>;
            };
            if (!valid) {
                throw new IllegalArgumentException(
                        label + " field " + name + " must be " + type);
            }
            if (value instanceof String text && maxLength != null && text.length() > maxLength) {
                throw new IllegalArgumentException(label + " field " + name + " exceeds maxLength");
            }
            if (value instanceof Number number && type == ValueType.INTEGER) {
                long candidate = number.longValue();
                if ((minimum != null && candidate < minimum)
                        || (maximum != null && candidate > maximum)) {
                    throw new IllegalArgumentException(label + " field " + name + " is out of range");
                }
            }
            if (value instanceof List<?> list && maxItems != null && list.size() > maxItems) {
                throw new IllegalArgumentException(label + " field " + name + " exceeds maxItems");
            }
        }

        private static void positiveOrNull(Integer value, String label) {
            if (value != null && value < 1) {
                throw new IllegalArgumentException(label + " must be positive");
            }
        }
    }
}
