package com.jundaodsj.insightops.server.localexecution;

import com.jundaodsj.insightops.report.application.BuildEvidence.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class LocalRegressionComparisonTest {
    private final UUID system = UUID.randomUUID();
    private final UUID report = UUID.randomUUID();
    private final List<String> additions = List.of("src/test/java/sample/NewTest.java");

    @Test
    void pairsBySuiteClassAndNameAndRequiresUniqueAssertionFailure() {
        var base = batch(Role.BASELINE, test("OldTest", "existing", 2, Status.PASSED));
        var probe = batch(Role.BASELINE, test("OldTest", "existing", 9, Status.PASSED),
                test("NewTest", "zero", 4, Status.FAILURE), test("NewTest", "positive", 7, Status.PASSED),
                test("NewTest", "duplicate", 1, Status.FAILURE), test("NewTest", "duplicate", 2, Status.FAILURE),
                test("NewTest", "exception", 11, Status.ERROR), test("Unaffiliated", "surprise", 12, Status.FAILURE));
        var next = batch(Role.CANDIDATE, test("OldTest", "existing", 1, Status.PASSED),
                test("NewTest", "zero", 1, Status.PASSED), test("NewTest", "positive", 2, Status.PASSED),
                test("NewTest", "duplicate", 3, Status.PASSED), test("NewTest", "exception", 4, Status.PASSED),
                test("Unaffiliated", "surprise", 5, Status.PASSED));
        var result = LocalRegressionComparison.compare(base, probe, next, additions, "");
        assertThat(result.baselinePassingOnCandidate()).isOne();
        assertThat(result.failedThenPassed()).isOne();
        assertThat(result.undistinguished()).isEqualTo(4);
        assertThat(result.cases()).filteredOn(row -> row.outcome().equals("FAILURE_TO_PASS"))
                .extracting(LocalRegressionComparison.Case::name).containsExactly("zero");
        assertThat(LocalRegressionComparison.compare(base, null, next, additions, "无原件").failedThenPassed()).isZero();
    }

    @Test
    void distinguishesMissingAndSkippedFromPassingOriginals() {
        var base = batch(Role.BASELINE, test("OldTest", "missing", 1, Status.PASSED),
                test("OldTest", "skippedNow", 2, Status.PASSED));
        var next = batch(Role.CANDIDATE, test("OldTest", "skippedNow", 1, Status.SKIPPED));
        var result = LocalRegressionComparison.compare(base, null, next, List.of(), "候选未新增测试。");
        assertThat(result.baselineExecuted()).isEqualTo(2);
        assertThat(result.baselinePassingOnCandidate()).isZero();
        assertThat(result.failedThenPassed()).isZero();
        assertThat(result.limitation()).contains("未新增测试");
    }

    private Batch batch(Role role, TestCase... cases) {
        var tests = List.of(cases);
        return new Batch(new Summary(UUID.randomUUID(), system, report, role, "", null, Counts.from(tests)),
                null, new BuildRecord(List.of("mvn", "test"), 0, "", "", "", List.of()), null,
                List.of(), List.of(), tests, List.of());
    }

    private TestCase test(String className, String method, int ordinal, Status status) {
        return new TestCase(UUID.randomUUID(), "sample." + className, "sample." + className, method, ordinal, status, "", "", "");
    }
}
