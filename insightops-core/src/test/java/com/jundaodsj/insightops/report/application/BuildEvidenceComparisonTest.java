package com.jundaodsj.insightops.report.application;

import com.jundaodsj.insightops.report.application.BuildEvidence.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.jundaodsj.insightops.report.application.BuildEvidenceComparison.Change.*;
import static com.jundaodsj.insightops.report.application.BuildEvidenceComparison.Scope.*;
import static org.assertj.core.api.Assertions.*;

class BuildEvidenceComparisonTest {
    private final UUID system = UUID.randomUUID(), report = UUID.randomUUID();
    private final UUID oldFile = UUID.randomUUID(), newFile = UUID.randomUUID();

    @Test void equalTotalsCanCoverDifferentTestsAndParametersAreSignificant() {
        var old = batch(system, Role.BASELINE, List.of(
                item(oldFile, "Lookup", "find[Franklin]", 1, Status.PASSED), item(oldFile, "Owner", "save", 2, Status.SKIPPED)));
        var next = batch(system, Role.CANDIDATE, List.of(
                item(newFile, "Lookup", "find[Davis]", 1, Status.PASSED), item(newFile, "Owner", "save", 2, Status.ERROR)));
        var comparison = BuildEvidenceComparison.compare(old, next);
        assertThat(comparison.scope()).isEqualTo(DIFFERENT_OBSERVED);
        assertThat(comparison.groups()).extracting(BuildEvidenceComparison.Group::change)
                .containsExactly(CANDIDATE_ONLY, BASELINE_ONLY, CHANGED);
        var broken = comparison.groups().getLast().candidate().getFirst();
        assertThat(broken.fileId()).isEqualTo(newFile);
        assertThat(broken.ordinal()).isEqualTo(2);
        assertThat(broken.stack()).isEqualTo("at Owner.save(Owner.java:9)");
        assertThat(comparison.objectiveSummary()).contains("无法按同一范围比较通过率");
        assertThat(comparison.candidateCounts().skipped()).isZero();
    }

    @Test void recordsMultiplicityAndStatusDistributionsWithoutPairingDuplicateDisplayNames() {
        var before = batch(system, Role.BASELINE, List.of(
                item(oldFile, "A", "same", 5, Status.PASSED), item(oldFile, "A", "same", 3, Status.FAILURE),
                item(oldFile, "B", "same", 1, Status.PASSED)));
        var after = batch(system, Role.CANDIDATE, List.of(
                item(newFile, "B", "same", 2, Status.PASSED), item(newFile, "A", "same", 1, Status.PASSED),
                item(newFile, "A", "same", 4, Status.ERROR)));
        var comparison = BuildEvidenceComparison.compare(before, after);
        assertThat(comparison.scope()).isEqualTo(SAME_OBSERVED);
        var repeat = comparison.groups().getFirst();
        assertThat(repeat.change()).isEqualTo(AMBIGUOUS);
        assertThat(repeat.baseline()).extracting(BuildEvidenceComparison.Occurrence::ordinal).containsExactly(3, 5);
        assertThat(repeat.candidate()).hasSize(2);
        assertThat(repeat.baselineCounts().failure()).isOne();
        assertThat(repeat.candidateCounts().error()).isOne();
        assertThat(comparison.groups().getLast().change()).isEqualTo(UNCHANGED);
        assertThat(comparison.objectiveSummary()).contains("重复身份", "仅能比较各侧次数");
    }

    @Test void extraRepeatChangesTheObservedScopeAndAbsentInvocationsAreNeverSkipped() {
        var only = item(oldFile, "A", "list", 1, Status.PASSED);
        var comparison = BuildEvidenceComparison.compare(batch(system, Role.BASELINE, List.of(only)),
                batch(system, Role.CANDIDATE, List.of(item(newFile, "A", "list", 1, Status.PASSED),
                        item(newFile, "A", "list", 2, Status.PASSED))));
        assertThat(comparison.scope()).isEqualTo(DIFFERENT_OBSERVED);
        assertThat(comparison.groups()).singleElement().satisfies(g -> {
            assertThat(g.change()).isEqualTo(AMBIGUOUS);
            assertThat(g.baselineCounts().skipped()).isZero();
            assertThat(g.baselineCounts().total()).isOne();
            assertThat(g.candidateCounts().total()).isEqualTo(2);
        });
        assertThat(comparison.limitations()).anySatisfy(s -> assertThat(s).contains("未出现的方法执行情况未知"));
    }

    @Test void sameClassAndMethodInDistinctSuitesAreSeparateObservations() {
        var old = new TestCase(oldFile, "Web", "OwnerTest", "find", 1, Status.PASSED, null, null, null);
        var next = new TestCase(newFile, "Service", "OwnerTest", "find", 1, Status.PASSED, null, null, null);
        var comparison = BuildEvidenceComparison.compare(batch(system, Role.BASELINE, List.of(old)), batch(system, Role.CANDIDATE, List.of(next)));
        assertThat(comparison.scope()).isEqualTo(DIFFERENT_OBSERVED);
        assertThat(comparison.groups()).extracting(BuildEvidenceComparison.Group::change).containsExactly(CANDIDATE_ONLY, BASELINE_ONLY);
    }

    @Test void rejectsSameBuildSwappedRolesAndWrongSystem() {
        var old = batch(system, Role.BASELINE, List.of());
        assertThatIllegalArgumentException().isThrownBy(() -> BuildEvidenceComparison.compare(old,old));
        assertThatIllegalArgumentException().isThrownBy(() -> BuildEvidenceComparison.compare(old,batch(system,Role.BASELINE,List.of())));
        assertThatIllegalArgumentException().isThrownBy(() -> BuildEvidenceComparison.compare(old,batch(UUID.randomUUID(),Role.CANDIDATE,List.of())));
    }

    private TestCase item(UUID file, String suite, String method, int ordinal, Status status) {
        return new TestCase(file, suite, suite + "Test", method, ordinal, status,
                status == Status.ERROR ? "ExampleException" : null, null,
                status == Status.ERROR ? "at Owner.save(Owner.java:9)" : null);
    }

    private Batch batch(UUID systemId, Role role, List<TestCase> cases) {
        var counts = new Counts(count(cases,Status.PASSED),count(cases,Status.FAILURE),count(cases,Status.ERROR),count(cases,Status.SKIPPED));
        return new Batch(new Summary(UUID.randomUUID(),systemId,report,role,"test",Instant.EPOCH,counts),
                new Metadata("test",null,null,null,null,null), new BuildRecord(List.of(),null,null,null,null,List.of()),
                new LogFacts(null,null),List.of(),List.of(),cases,List.of("未提供 Maven 日志。"));
    }
    private static int count(List<TestCase> cases, Status match) {
        return (int) cases.stream().filter(c -> c.status() == match).count();
    }
}
