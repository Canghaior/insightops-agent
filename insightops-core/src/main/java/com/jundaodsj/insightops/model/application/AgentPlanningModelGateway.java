package com.jundaodsj.insightops.model.application;

import java.time.Duration;
import java.util.List;

/** Provider-independent gateway for one Function Calling planning turn. */
public interface AgentPlanningModelGateway {

    AgentPlanResponse plan(AgentPlanRequest request);

    record AgentPlanRequest(
            String systemPrompt,
            String userPrompt,
            List<ToolExchange> exchanges,
            List<FunctionDefinition> tools,
            double temperature,
            int maxOutputTokens,
            String modelOverride) {

        public AgentPlanRequest(
                String systemPrompt, String userPrompt, List<ToolExchange> exchanges,
                List<FunctionDefinition> tools, double temperature, int maxOutputTokens) {
            this(systemPrompt, userPrompt, exchanges, tools, temperature, maxOutputTokens, null);
        }

        public AgentPlanRequest {
            if (systemPrompt == null || systemPrompt.isBlank()) {
                throw new IllegalArgumentException("systemPrompt must not be blank");
            }
            systemPrompt = systemPrompt.strip();
            if (userPrompt == null || userPrompt.isBlank()) {
                throw new IllegalArgumentException("userPrompt must not be blank");
            }
            userPrompt = userPrompt.strip();
            exchanges = exchanges == null ? List.of() : List.copyOf(exchanges);
            tools = tools == null ? List.of() : List.copyOf(tools);
            if (tools.isEmpty()) throw new IllegalArgumentException("tools must not be empty");
            if (temperature < 0.0 || temperature > 2.0) {
                throw new IllegalArgumentException("temperature must be between 0 and 2");
            }
            if (maxOutputTokens < 1 || maxOutputTokens > 8192) {
                throw new IllegalArgumentException("maxOutputTokens must be between 1 and 8192");
            }
            modelOverride = modelOverride == null || modelOverride.isBlank()
                    ? null : modelOverride.strip();
        }
    }

    record FunctionDefinition(String name, String description, String inputSchemaJson) {
        public FunctionDefinition {
            name = requireText(name, "name");
            description = requireText(description, "description");
            inputSchemaJson = requireText(inputSchemaJson, "inputSchemaJson");
        }
    }

    record PlannedToolCall(String id, String name, String argumentsJson) {
        public PlannedToolCall {
            id = requireText(id, "id");
            name = requireText(name, "name");
            argumentsJson = requireText(argumentsJson, "argumentsJson");
        }
    }

    record ToolExchange(PlannedToolCall toolCall, String responseJson) {
        public ToolExchange {
            if (toolCall == null) throw new IllegalArgumentException("toolCall must not be null");
            responseJson = requireText(responseJson, "responseJson");
        }
    }

    record AgentPlanResponse(
            String content,
            List<PlannedToolCall> toolCalls,
            String provider,
            String model,
            ModelUsage usage,
            Duration duration) {

        public AgentPlanResponse {
            content = content == null ? "" : content.strip();
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            if (content.isBlank() && toolCalls.isEmpty()) {
                throw new IllegalArgumentException("plan response must contain content or tool calls");
            }
            provider = requireText(provider, "provider");
            model = requireText(model, "model");
            usage = usage == null ? ModelUsage.unknown() : usage;
            if (duration == null || duration.isNegative()) {
                throw new IllegalArgumentException("duration must not be null or negative");
            }
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
