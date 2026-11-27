package com.jundaodsj.insightops.server.localexecution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.report.application.BuildEvidence;
import com.jundaodsj.insightops.report.application.SurefireEvidenceParser;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** Explicit IO-11 integration check using the pinned amount-rule case and its offline cache. */
class LocalBuildProfilesIT {
    @Test
    void usesIdenticalClassSelectorOnBaselineCandidateAndTestsOnlyReplay() throws Exception {
        Path catalog = Path.of(System.getenv("IO11_MAVEN_IT_CATALOG"));
        Path cache = Path.of(System.getenv("IO11_MAVEN_IT_CACHE"));
        Path data = Files.createTempDirectory(Path.of(System.getenv("IO11_MAVEN_IT_OUTPUT")), "selected-classes-").toRealPath();
        UUID workspace = UUID.randomUUID(), run = UUID.randomUUID();
        var config = new LocalExecutionProperties(catalog, data, cache, null, null, workspace);
        var workspaces = new LocalWorkspaces(config);
        var entry = workspaces.check(workspace, "amount-rule", LocalProcess.ACTIVE);
        assertThat(entry.revision()).isEqualTo("0932821fbb11e430329346713e53854979004340");
        var directory = Files.createDirectory(data.resolve("run"));
        Files.createDirectory(directory.resolve("artifacts"));
        var root = workspaces.prepare(workspace, entry, LocalWorkspaces.sha(Files.readAllBytes(catalog.resolve("amount-rule/pom.xml"))),
                directory, LocalProcess.ACTIVE);
        var files = new LocalRepositoryFiles(root, List.of("src/main/", "src/test/"));
        var maven = new LocalMavenBuild(config, workspaces, new ObjectMapper());
        var plan = new LocalBuildPlan(LocalBuildPlan.Mode.SELECTED_CLASSES, List.of("AmountRuleTest", "NewRuleTest"));
        var baseline = maven.run(run, "baseline", "", files, directory, plan, LocalProcess.ACTIVE);
        var source = "src/main/java/example/io09/AmountRule.java";
        var original = files.read(source, 1, 200);
        files.writeAll(List.of(new LocalRepositoryFiles.Edit(source, original.sha256(),
                        Files.readString(root.resolve(source)).replace("signum() < 0", "signum() <= 0")),
                new LocalRepositoryFiles.Edit("src/test/java/example/io09/NewRuleTest.java", "", """
                        package example.io09;
                        import java.math.BigDecimal;
                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.assertThrows;
                        class NewRuleTest {
                            @Test void rejectsZero() {
                                assertThrows(IllegalArgumentException.class, () -> AmountRule.roundForOrder(BigDecimal.ZERO));
                            }
                        }
                        """),
                new LocalRepositoryFiles.Edit("src/test/java/example/io09/OutsideSelectionTest.java", "", """
                        package example.io09;
                        import org.junit.jupiter.api.Test;
                        class OutsideSelectionTest { @Test void outside() {} }
                        """)));
        byte[] patch = workspaces.diff(root, LocalProcess.ACTIVE);
        var candidate = maven.run(run, "candidate-1", LocalWorkspaces.sha(patch), files, directory, plan, LocalProcess.ACTIVE);
        Files.write(candidate.artifacts().resolve("change.patch"), patch);
        var replay = workspaces.withNewTests(root, entry.revision(), directory.resolve("replay-source"),
                workspaces.newTestFiles(root, LocalProcess.ACTIVE), LocalProcess.ACTIVE);
        byte[] tests = workspaces.diff(replay.root(), LocalProcess.ACTIVE);
        var old = maven.run(run, "baseline-with-new-tests", LocalWorkspaces.sha(tests), replay, directory, plan, LocalProcess.ACTIVE);
        Files.write(old.artifacts().resolve("tests-only.patch"), tests);
        assertThat(old.command()).isEqualTo(candidate.command()).isEqualTo(baseline.command());
        assertThat(baseline.execution().ok()).isTrue();
        assertThat(candidate.execution().ok()).isTrue();
        assertThat(old.execution().exitCode()).isNotZero();
        assertThat(counts(baseline)).isEqualTo(new BuildEvidence.Counts(2, 0, 0, 0));
        assertThat(counts(old)).isEqualTo(new BuildEvidence.Counts(2, 1, 0, 0));
        assertThat(counts(candidate)).isEqualTo(new BuildEvidence.Counts(3, 0, 0, 0));
    }

    private BuildEvidence.Counts counts(LocalMavenBuild.Build run) throws Exception {
        var cases = new ArrayList<BuildEvidence.TestCase>();
        for (Path path : run.xml()) {
            cases.addAll(new SurefireEvidenceParser().parse(UUID.randomUUID(), path.getFileName().toString(),
                    Files.readAllBytes(path)).cases());
        }
        assertThat(cases).noneMatch(row -> row.className().contains("OutsideSelectionTest"));
        return BuildEvidence.Counts.from(cases);
    }
}
