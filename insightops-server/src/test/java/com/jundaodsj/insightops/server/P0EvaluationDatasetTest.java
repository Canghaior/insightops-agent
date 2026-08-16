package com.jundaodsj.insightops.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.project.application.P0TrackedProjectCatalog;
import com.jundaodsj.insightops.server.chat.ReleaseQuestionRouter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class P0EvaluationDatasetTest {

    private static final Set<String> REQUIRED_FIELDS = Set.of(
            "id", "question", "projectIds", "category", "mode", "timeWindowDays",
            "expectedTools", "requiredSourceTypes", "expectedBehavior", "mustInclude",
            "mustNotDo", "allowInsufficientEvidence", "priority", "status");
    private static final Set<String> ALLOWED_STATUSES = Set.of("draft", "verified", "disabled");
    private static final Set<String> PROJECT_IDS = P0TrackedProjectCatalog.all().stream()
            .map(P0TrackedProjectCatalog.ProjectDefinition::id)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReleaseQuestionRouter releaseQuestionRouter = new ReleaseQuestionRouter();

    @Test
    void p0DatasetContainsTwentyValidReleaseQuestions() throws IOException {
        Path dataset = findProjectRoot().resolve("docs/evals/p0-research-questions.jsonl");
        List<String> records = Files.readAllLines(dataset).stream()
                .filter(line -> !line.isBlank())
                .toList();

        assertThat(records).hasSize(20);
        Set<String> ids = new HashSet<>();
        Set<String> questions = new HashSet<>();

        for (String record : records) {
            JsonNode item = objectMapper.readTree(record);
            assertThat(item.isObject()).isTrue();
            assertThat(item.propertyStream().map(entry -> entry.getKey()).toList())
                    .containsAll(REQUIRED_FIELDS);

            String id = requiredText(item, "id");
            String question = requiredText(item, "question");
            assertThat(ids.add(id)).as("evaluation id must be unique: %s", id).isTrue();
            assertThat(questions.add(question)).as("question must be unique: %s", id).isTrue();
            assertThat(requiredText(item, "category")).isNotBlank();
            assertThat(requiredText(item, "mode")).isEqualTo("live");
            assertThat(requiredText(item, "priority")).isEqualTo("P0");
            assertThat(requiredText(item, "status")).isIn(ALLOWED_STATUSES);
            assertThat(item.path("allowInsufficientEvidence").isBoolean()).isTrue();

            List<String> projectIds = requiredTextArray(item, "projectIds");
            assertThat(projectIds)
                    .isNotEmpty()
                    .allMatch(PROJECT_IDS::contains);
            assertThat(requiredTextArray(item, "expectedTools"))
                    .containsExactly("github_release_list");
            assertThat(requiredTextArray(item, "requiredSourceTypes"))
                    .containsExactly("github_release");
            assertThat(requiredTextArray(item, "mustInclude")).isNotEmpty();
            assertThat(requiredTextArray(item, "mustNotDo")).isNotEmpty();
            assertThat(requiredText(item, "expectedBehavior")).isNotBlank();

            JsonNode timeWindow = item.get("timeWindowDays");
            assertThat(timeWindow.isNull() || timeWindow.canConvertToInt()).isTrue();
            if (timeWindow.canConvertToInt()) {
                assertThat(timeWindow.intValue()).isBetween(1, 365);
            }

            var routed = releaseQuestionRouter.route(question);
            assertThat(routed).as("P0 question must route to the release tool: %s", id).isPresent();
            assertThat(routed.orElseThrow().projectIds())
                    .as("routed projects must match the evaluation contract: %s", id)
                    .containsExactlyElementsOf(projectIds);
            if (timeWindow.isNull()) {
                assertThat(routed.orElseThrow().timeWindowDays())
                        .as("time window must match the evaluation contract: %s", id)
                        .isNull();
            } else {
                assertThat(routed.orElseThrow().timeWindowDays())
                        .as("time window must match the evaluation contract: %s", id)
                        .isEqualTo(timeWindow.intValue());
            }
        }
    }

    private String requiredText(JsonNode item, String field) {
        JsonNode value = item.get(field);
        assertThat(value).as("%s must be a text value", field).isNotNull();
        assertThat(value.isTextual()).as("%s must be a text value", field).isTrue();
        assertThat(value.textValue()).as("%s must not be blank", field).isNotBlank();
        return value.textValue();
    }

    private List<String> requiredTextArray(JsonNode item, String field) {
        JsonNode value = item.get(field);
        assertThat(value).as("%s must be an array", field).isNotNull();
        assertThat(value.isArray()).as("%s must be an array", field).isTrue();
        List<String> values = value.valueStream()
                .map(element -> {
                    assertThat(element.isTextual()).as("%s values must be text", field).isTrue();
                    return element.textValue();
                })
                .toList();
        assertThat(values).allMatch(text -> text != null && !text.isBlank());
        return values;
    }

    private Path findProjectRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isRegularFile(current.resolve("docs/evals/p0-research-questions.jsonl"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate project root");
    }
}
