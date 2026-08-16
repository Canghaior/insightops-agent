package com.jundaodsj.insightops.server.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.model.application.ChatModelGateway;
import com.jundaodsj.insightops.model.application.ChatModelRequest;
import com.jundaodsj.insightops.model.application.ChatModelResponse;
import com.jundaodsj.insightops.model.application.ModelCallErrorCode;
import com.jundaodsj.insightops.model.application.ModelCallException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
@ConditionalOnProperty(prefix = "insightops.model.deepseek", name = "enabled", havingValue = "true")
public class StructuredReleaseIntentService {

    private static final Set<String> PROJECTS = Set.of("spring-ai", "langchain4j", "dify");
    private static final String SYSTEM_PROMPT = """
            Return one JSON object only. Do not use Markdown or add explanations.
            Required schema: project is spring-ai, langchain4j, or dify; intent must be release_list;
            timeWindowDays must be an integer from 1 through 365.
            """;
    private static final String USER_PROMPT = """
            Extract this request: list Spring AI releases from the last 30 days.
            Return exactly these semantic values: project=spring-ai, intent=release_list,
            timeWindowDays=30.
            """;

    private final ChatModelGateway gateway;
    private final ObjectMapper objectMapper;

    public StructuredReleaseIntentService(ChatModelGateway gateway, ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.objectMapper = objectMapper;
    }

    public StructuredResult generate() {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                ChatModelResponse response = gateway.generate(
                        new ChatModelRequest(SYSTEM_PROMPT, USER_PROMPT, 0.0, 128));
                return new StructuredResult(
                        parse(response.content()),
                        response.provider(),
                        response.model(),
                        response.usage(),
                        response.duration().toMillis(),
                        attempt);
            }
            catch (ModelCallException exception) {
                if (exception.code() != ModelCallErrorCode.EMPTY_RESPONSE || attempt == 2) {
                    throw exception;
                }
                lastFailure = exception;
            }
            catch (StructuredOutputException exception) {
                lastFailure = exception;
                if (attempt == 2) {
                    throw exception;
                }
            }
        }
        throw new StructuredOutputException("MODEL_JSON_INVALID", lastFailure);
    }

    ReleaseIntent parse(String content) {
        try {
            if (content == null || content.isBlank() || content.contains("```")) {
                throw new StructuredOutputException("MODEL_JSON_INVALID");
            }
            JsonNode root = objectMapper.readTree(content);
            if (!root.isObject() || root.size() != 3
                    || !root.path("project").isTextual()
                    || !root.path("intent").isTextual()
                    || !root.path("timeWindowDays").isIntegralNumber()) {
                throw new StructuredOutputException("MODEL_JSON_SCHEMA_MISMATCH");
            }
            String project = root.path("project").textValue();
            String intent = root.path("intent").textValue();
            int days = root.path("timeWindowDays").intValue();
            if (!PROJECTS.contains(project) || !"release_list".equals(intent) || days < 1 || days > 365) {
                throw new StructuredOutputException("MODEL_JSON_SCHEMA_MISMATCH");
            }
            return new ReleaseIntent(project, intent, days);
        }
        catch (StructuredOutputException exception) {
            throw exception;
        }
        catch (Exception exception) {
            throw new StructuredOutputException("MODEL_JSON_INVALID", exception);
        }
    }

    public record ReleaseIntent(String project, String intent, int timeWindowDays) {
    }

    public record StructuredResult(
            ReleaseIntent intent,
            String provider,
            String model,
            com.jundaodsj.insightops.model.application.ModelUsage usage,
            long durationMs,
            int attempts) {
    }

    public static final class StructuredOutputException extends RuntimeException {
        public StructuredOutputException(String code) {
            super(code);
        }

        public StructuredOutputException(String code, Throwable cause) {
            super(code, cause);
        }
    }
}
