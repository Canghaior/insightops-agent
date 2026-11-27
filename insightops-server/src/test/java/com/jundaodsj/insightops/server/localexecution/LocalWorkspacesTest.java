package com.jundaodsj.insightops.server.localexecution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalWorkspacesTest {
    @TempDir Path temporary;

    @Test
    void snapshotsTheSelectedCommitAndCapturesNewFilesWithoutChangingTheOriginal() throws Exception {
        var catalog = Files.createDirectory(temporary.resolve("catalog"));
        var repository = Files.createDirectory(catalog.resolve("amount-rule"));
        var cache = Files.createDirectory(temporary.resolve("maven-cache"));
        var data = Files.createDirectory(temporary.resolve("runs"));
        git(repository, "git", "init", "--quiet");
        var pomBytes = """
                <project><modelVersion>4.0.0</modelVersion><groupId>test</groupId>
                  <artifactId>amount-rule</artifactId><version>1</version>
                </project>
                """;
        var pom = Files.writeString(repository.resolve("pom.xml"), pomBytes);
        var source = Files.createDirectories(repository.resolve("src/main/java")).resolve("Rule.java");
        Files.writeString(source, "class Rule {}\n");
        git(repository, "git", "-c", "core.autocrlf=false", "add", "--", "pom.xml", "src");
        git(repository, "git", "-c", "user.name=Local Test", "-c", "user.email=test@example.invalid", "-c",
                "commit.gpgsign=false", "commit", "--quiet", "-m", "Test fixture baseline");
        Files.writeString(source, "// unsaved by user\nclass Rule {}\n");
        var tenant = UUID.randomUUID();
        var other = UUID.randomUUID();
        var workspaces = new LocalWorkspaces(new LocalExecutionProperties(catalog, data, cache, null, null, tenant));
        var entry = workspaces.list(tenant).getFirst();
        assertThat(workspaces.list(other)).isEmpty();
        assertThatThrownBy(() -> workspaces.check(other, entry.id(), LocalProcess.ACTIVE)).isInstanceOf(IllegalArgumentException.class);
        var run = Files.createDirectory(data.resolve("a-run"));
        var checkout = workspaces.prepare(tenant, entry, LocalWorkspaces.sha(Files.readAllBytes(pom)), run, LocalProcess.ACTIVE);
        assertThat(Files.readString(checkout.resolve("src/main/java/Rule.java"))).isEqualTo("class Rule {}\n");
        assertThat(workspaces.diff(checkout, LocalProcess.ACTIVE)).isEmpty();
        var files = new LocalRepositoryFiles(checkout, List.of("src/test/"));
        files.write("src/test/java/NewTest.java", "", "class NewTest {}\n");
        Files.writeString(checkout.resolve("src/main/java/Rule.java"), "class Rule { int candidate; }\n");
        assertThat(new String(workspaces.diff(checkout, LocalProcess.ACTIVE), java.nio.charset.StandardCharsets.UTF_8))
                .contains("new file mode", "src/test/java/NewTest.java", "+class NewTest {}");
        var tests = workspaces.newTestFiles(checkout, LocalProcess.ACTIVE);
        assertThat(tests).containsExactly("src/test/java/NewTest.java");
        var replay = workspaces.withNewTests(checkout, entry.revision(), run.resolve("replay"), tests, LocalProcess.ACTIVE);
        assertThat(Files.readString(replay.root().resolve("src/main/java/Rule.java"))).isEqualTo("class Rule {}\n");
        assertThat(Files.readString(replay.root().resolve(tests.getFirst()))).isEqualTo("class NewTest {}\n");
        assertThat(Files.readString(source)).startsWith("// unsaved by user");
        assertThat(Files.exists(repository.resolve("src/test/java/NewTest.java"))).isFalse();

        var mismatch = Files.createDirectory(data.resolve("wrong-pom"));
        assertThatThrownBy(() -> workspaces.prepare(tenant, entry, "0".repeat(64), mismatch, LocalProcess.ACTIVE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("字节不一致");

        var secondCatalog = Files.createDirectory(catalog.resolve(other.toString()));
        git(secondCatalog, "git", "clone", "--quiet", "--no-local", "--", repository.toString(), "second");
        assertThat(workspaces.list(other)).extracting(LocalWorkspaces.Workspace::id).containsExactly("second");
        assertThat(workspaces.list(tenant)).extracting(LocalWorkspaces.Workspace::id).containsExactly("amount-rule");
        assertThatThrownBy(() -> workspaces.check(tenant, "second", LocalProcess.ACTIVE)).isInstanceOf(IllegalArgumentException.class);
        var scoped = new LocalWorkspaces(new LocalExecutionProperties(catalog, data, cache, null, null, null));
        assertThat(scoped.list(tenant)).isEmpty();
        assertThatThrownBy(() -> scoped.check(tenant, "amount-rule", LocalProcess.ACTIVE)).isInstanceOf(IllegalArgumentException.class);
    }

    private static void git(Path directory, String... argv) throws Exception {
        var result = LocalProcess.run(List.of(argv), directory, Duration.ofSeconds(20), 64 * 1024,
                Map.of("GIT_TERMINAL_PROMPT", "0"), LocalProcess.ACTIVE, null);
        assertThat(result.ok()).as(result.text()).isTrue();
    }
}
