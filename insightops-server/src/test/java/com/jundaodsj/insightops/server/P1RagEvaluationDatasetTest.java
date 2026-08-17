package com.jundaodsj.insightops.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class P1RagEvaluationDatasetTest {
    private static final Set<String> PROJECTS = Set.of("spring-ai", "langchain4j", "dify");
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void ragDatasetHasBalancedVerifiedQualityContracts() throws Exception {
        Path dataset = projectRoot().resolve("docs/evals/p1-rag-questions.jsonl");
        List<JsonNode> records = Files.readAllLines(dataset).stream()
                .filter(line -> !line.isBlank())
                .map(line -> {
                    try { return json.readTree(line); }
                    catch (Exception exception) { throw new IllegalArgumentException(exception); }
                })
                .toList();

        assertThat(records).hasSize(15);
        Set<String> ids = new HashSet<>();
        for (JsonNode item : records) {
            assertThat(ids.add(text(item, "id"))).isTrue();
            assertThat(text(item, "question")).isNotBlank();
            boolean answerable = item.path("answerable").asBoolean();
            if (answerable) {
                assertThat(text(item, "expectedProject")).isIn(PROJECTS);
                assertThat(text(item, "sourceDomain")).startsWith("docs.");
                assertTextArray(item, "mustHitTerms", true);
                assertTextArray(item, "answerMustInclude", true);
            }
            else {
                assertThat(item.path("expectedProject").isNull()).isTrue();
                assertThat(item.path("sourceDomain").isNull()).isTrue();
                assertTextArray(item, "mustHitTerms", false);
                assertTextArray(item, "answerMustInclude", false);
            }
            assertThat(text(item, "category")).isNotBlank();
            assertThat(text(item, "status")).isEqualTo("verified");
        }
        Map<String, Long> coverage = records.stream().filter(item -> item.path("answerable").asBoolean())
                .collect(Collectors.groupingBy(
                item -> item.path("expectedProject").asText(), Collectors.counting()));
        assertThat(coverage).containsEntry("spring-ai", 4L)
                .containsEntry("langchain4j", 4L).containsEntry("dify", 4L);
        assertThat(records.stream().filter(item -> !item.path("answerable").asBoolean())).hasSize(3);
    }

    private static String text(JsonNode item, String field) {
        assertThat(item.hasNonNull(field)).as("missing %s", field).isTrue();
        assertThat(item.path(field).isTextual()).as("%s must be text", field).isTrue();
        return item.path(field).asText();
    }

    private static void assertTextArray(JsonNode item, String field, boolean nonEmpty) {
        assertThat(item.path(field).isArray()).as("%s must be an array", field).isTrue();
        if (nonEmpty) assertThat(item.path(field).size()).isGreaterThan(0);
        assertThat(item.path(field).valueStream().allMatch(JsonNode::isTextual)).isTrue();
    }

    private static Path projectRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("docs/evals"))) return current;
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate project root");
    }
}
