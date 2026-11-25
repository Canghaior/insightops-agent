package com.jundaodsj.insightops.server.localexecution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.model.application.AgentPlanningModelGateway.PlannedToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalRepositoryFilesTest {
    @TempDir Path temporary;

    @Test
    void keepsWritesInTheSelectedWorkspaceAndChecksThePreviousFile() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace")).toRealPath();
        Path main = Files.createDirectories(workspace.resolve("src/main/java")).resolve("Rule.java");
        Path test = Files.createDirectories(workspace.resolve("src/test/java")).resolve("RuleTest.java");
        Files.writeString(main, "class Rule {}\n");
        Files.writeString(test, "class RuleTest {}\n");
        var files = new LocalRepositoryFiles(workspace, LocalRepositoryFiles.writeScopes(List.of("src/main/", "src/test/")));
        var initial = files.read("src/main/java/Rule.java", 1, 10);

        assertThatThrownBy(() -> files.write("src/main/java/../Rule.java", "", "broken"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> files.write("pom.xml", "", "broken"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("授权");
        assertThatThrownBy(() -> files.write("src/main/java/Rule.java", "", "broken"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SHA-256");
        assertThatThrownBy(() -> files.write("src/test/java/RuleTest.java", LocalWorkspaces.sha(Files.readAllBytes(test)), "broken"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("保留已有测试");

        assertThat(files.write("src/main/java/Rule.java", initial.sha256(), "class Rule { int size; }\n").created()).isFalse();
        var newTest = files.write("src/test/java/RuleRegressionTest.java", "", "class RuleRegressionTest {}\n");
        assertThat(newTest.created()).isTrue();
        assertThat(files.search("src", "RuleRegressionTest", 1).matches()).hasSize(1);
        assertThat(Files.readString(main)).contains("int size");
        assertThat(Files.readString(test)).isEqualTo("class RuleTest {}\n");
    }

    @Test
    void pagesFileAndContentSearchWithoutLosingMatches() throws Exception {
        var root = temporary.toRealPath();
        var source = Files.createDirectories(root.resolve("src/main/java"));
        for (int i = 0; i < 165; i++) Files.writeString(source.resolve("Path%03d.java".formatted(i)), "needle\n");
        var files = new LocalRepositoryFiles(root, List.of());
        assertThat(files.list("", "", 1).nextPage()).isEqualTo(2);
        assertThat(files.list("", "", 2).paths()).hasSize(5).startsWith("src/main/java/Path160.java");
        assertThat(files.list("", "path164", 1).paths()).containsExactly("src/main/java/Path164.java");
        assertThat(files.search("", "needle", 2).matches()).hasSize(24);
        assertThat(files.search("", "needle", 2).matches().getFirst().path()).endsWith("/Path024.java");
        assertThat(files.search("", "needle", 7).nextPage()).isNull();
    }

    @Test
    void rejectsEntireGroupOnAStaleHashAndCanRefineANewTest() throws Exception {
        var root = temporary.toRealPath();
        var source = Files.createDirectories(root.resolve("src/main")).resolve("Rule.java");
        Files.writeString(source, "old");
        var files = new LocalRepositoryFiles(root, List.of("src/main/", "src/test/"));
        var added = new LocalRepositoryFiles.Edit("src/test/NewTest.java", "", "test");
        var stale = new LocalRepositoryFiles.Edit("src/main/Rule.java", "0".repeat(64), "new");
        assertThatThrownBy(() -> files.writeAll(List.of(added, stale))).hasMessageContaining("SHA-256");
        assertThat(root.resolve(added.path())).doesNotExist();
        var known = files.read("src/main/Rule.java", 1, 1);
        var changes = files.writeAll(List.of(added, new LocalRepositoryFiles.Edit(known.path(), known.sha256(), "new")));
        assertThat(changes).hasSize(2);
        assertThat(Files.readString(source)).isEqualTo("new");
        files.write(added.path(), changes.getFirst().sha256(), "refined");
        assertThat(Files.readString(root.resolve(added.path()))).isEqualTo("refined");
    }

    @Test
    void toolsCannotRequestWritesInTheReadOnlyPhaseOrSupplyRawMavenCommands() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace")).toRealPath();
        var json = new ObjectMapper();
        var tools = new LocalAgentTools(json, new LocalRepositoryFiles(workspace, List.of("src/main/")),
                () -> Map.of("called", true));
        var write = new PlannedToolCall("call-1", "write_file", """
                {"path":"src/main/java/Rule.java","expectedSha256":"","content":"class Rule {}"}
                """);
        assertThat(tools.call(write, false).ok()).isFalse();
        assertThat(Files.exists(workspace.resolve("src"))).isFalse();
        var extra = new PlannedToolCall("call-2", "run_maven_tests", "{\"command\":[\"package\"]}");
        assertThat(tools.call(extra, true).ok()).isFalse();
        assertThat(tools.call(new PlannedToolCall("call-3", "run_maven_tests", "{}"), true).result())
                .isEqualTo(Map.of("called", true));
    }
}
