package com.jundaodsj.insightops.server;

import com.jundaodsj.insightops.project.application.P0TrackedProjectCatalog;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OfficialSourceRegistryTest {

    private static final Set<String> EXPECTED_PROJECTS = Set.of("spring-ai", "langchain4j", "dify");
    private static final Set<String> REQUIRED_SOURCE_TYPES = Set.of(
            "homepage", "github_repository", "github_release", "official_documentation");
    private static final Set<String> TRUST_TIERS = Set.of("T1_PROJECT_DOMAIN", "T1_OFFICIAL_REPOSITORY");
    private static final Map<String, Set<String>> OFFICIAL_HOSTS = Map.of(
            "spring-ai", Set.of("spring.io", "docs.spring.io", "github.com", "api.github.com"),
            "langchain4j", Set.of("langchain4j.dev", "docs.langchain4j.dev", "github.com", "api.github.com"),
            "dify", Set.of("dify.ai", "docs.dify.ai", "github.com", "api.github.com"));

    @Test
    void registryContainsOnlyVerifiedOfficialSourcesAndEnablesReleaseAndDocumentation() throws IOException {
        Map<String, Object> registry = loadRegistry();

        assertThat(registry.get("schemaVersion")).isEqualTo(2);
        assertThat(registry.get("scope")).isEqualTo("alpha-p1.4-a");

        List<Map<String, Object>> projects = list(registry, "projects");
        assertThat(projects).extracting(project -> string(project, "id"))
                .containsExactlyInAnyOrderElementsOf(EXPECTED_PROJECTS);
        assertThat(P0TrackedProjectCatalog.all())
                .extracting(P0TrackedProjectCatalog.ProjectDefinition::id)
                .containsExactlyInAnyOrderElementsOf(EXPECTED_PROJECTS);

        Set<String> sourceIds = new HashSet<>();
        for (Map<String, Object> project : projects) {
            String projectId = string(project, "id");
            var runtimeProject = P0TrackedProjectCatalog.find(projectId).orElseThrow();
            Map<String, Object> repository = map(project, "repository");
            assertThat(string(repository, "owner")).isEqualTo(runtimeProject.repositoryOwner());
            assertThat(string(repository, "name")).isEqualTo(runtimeProject.repositoryName());
            assertThat(project.get("enabled")).isEqualTo(true);
            List<Map<String, Object>> sources = list(project, "sources");
            assertThat(sources).isNotEmpty();
            assertThat(sources).extracting(source -> string(source, "type"))
                    .containsAll(REQUIRED_SOURCE_TYPES);

            for (Map<String, Object> source : sources) {
                String sourceId = string(source, "id");
                assertThat(sourceIds.add(sourceId)).as("source id must be globally unique: %s", sourceId).isTrue();
                assertThat(string(source, "name")).isNotBlank();
                assertOfficialUrl(projectId, string(source, "url"));
                assertThat(string(source, "trustTier")).isIn(TRUST_TIERS);
                assertThat(string(source, "updateFrequency")).isNotBlank();

                boolean enabled = Boolean.TRUE.equals(source.get("collectionEnabled"));
                assertThat(enabled).as("only release and official docs may be enabled in P1.4-A: %s", sourceId)
                        .isEqualTo(Set.of("github_release", "official_documentation")
                                .contains(string(source, "type")));

                Map<String, Object> verification = map(source, "verification");
                assertThat(string(verification, "status")).isEqualTo("VERIFIED");
                assertThat(LocalDate.parse(string(verification, "checkedAt")))
                        .isBeforeOrEqualTo(LocalDate.parse(string(registry, "updatedAt")));

                if (source.containsKey("apiUrl")) {
                    assertOfficialUrl(projectId, string(source, "apiUrl"));
                }
            }

            validateSourceGaps(project, sources);
        }
        assertThat(sourceIds).hasSize(19);
    }

    private void validateSourceGaps(Map<String, Object> project, List<Map<String, Object>> sources) {
        Set<String> localSourceIds = new HashSet<>();
        sources.forEach(source -> localSourceIds.add(string(source, "id")));

        for (Map<String, Object> gap : optionalList(project, "sourceGaps")) {
            assertThat(string(gap, "type")).isNotBlank();
            assertThat(string(gap, "status")).isEqualTo("NOT_FOUND");
            LocalDate.parse(string(gap, "checkedAt"));
            assertThat(string(gap, "note")).isNotBlank();
            assertThat(stringList(gap, "fallbackSourceIds"))
                    .isNotEmpty()
                    .allMatch(localSourceIds::contains);
        }
    }

    private Map<String, Object> loadRegistry() throws IOException {
        Path registryPath = findProjectRoot().resolve("docs/product/tracked-projects.yaml");
        try (Reader reader = Files.newBufferedReader(registryPath)) {
            return new Yaml().load(reader);
        }
    }

    private Path findProjectRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isRegularFile(current.resolve("docs/product/tracked-projects.yaml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate project root");
    }

    private void assertOfficialUrl(String projectId, String value) {
        URI uri = URI.create(value);
        assertThat(uri.getScheme()).isEqualTo("https");
        assertThat(uri.getHost()).isIn(OFFICIAL_HOSTS.get(projectId));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Map<String, Object> source, String key) {
        return (Map<String, Object>) source.get(key);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(Map<String, Object> source, String key) {
        return (List<Map<String, Object>>) source.get(key);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> optionalList(Map<String, Object> source, String key) {
        return (List<Map<String, Object>>) source.getOrDefault(key, List.of());
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Map<String, Object> source, String key) {
        return (List<String>) source.get(key);
    }

    private String string(Map<String, Object> source, String key) {
        Object value = source.get(key);
        assertThat(value).as("%s must be a string", key).isInstanceOf(String.class);
        return (String) value;
    }
}
