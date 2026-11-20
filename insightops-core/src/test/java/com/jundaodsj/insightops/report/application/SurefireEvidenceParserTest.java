package com.jundaodsj.insightops.report.application;

import com.jundaodsj.insightops.report.application.BuildEvidence.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SurefireEvidenceParserTest {
    private final UUID file = UUID.randomUUID();
    private final SurefireEvidenceParser parser = new SurefireEvidenceParser();

    private SurefireEvidenceParser.Suite parse(String xml) {
        return parser.parse(file, "TEST-Demo.xml", xml.getBytes(StandardCharsets.UTF_8));
    }

    private static String suite(String body, int n, int failures, int errors, int skipped) {
        return "<testsuite name='pkg.Demo' tests='%d' failures='%d' errors='%d' skipped='%d'>%s</testsuite>"
                .formatted(n, failures, errors, skipped, body);
    }

    @Test void preservesRepeatedNamesOriginalOrdinalsAndDiagnosticText() {
        var source = """
                <properties><property name="basedir" value="C:/example"/></properties>
                <testcase classname="pkg.Demo" name="check(1)"><system-out>ordinary output</system-out></testcase>
                <testcase classname="pkg.Demo" name="check(1)"><failure type="AssertionError" message="expected 2">at Demo.java:19</failure></testcase>
                <testcase classname="pkg.Demo" name="check(2)"><error type="QueryError" message="bad"><![CDATA[original <line>
                at Demo.java:23]]></error><system-err>more console output</system-err></testcase>
                <testcase classname="pkg.Demo" name="depends"><skipped message="unavailable"/></testcase>
                """;
        var parsed = parse(suite(source, 4, 1, 1, 1));
        assertEquals("C:/example", parsed.basedir());
        assertEquals(new Counts(1, 1, 1, 1), Counts.from(parsed.cases()));
        assertEquals(List.of("check(1)", "check(1)", "check(2)", "depends"), parsed.cases().stream().map(TestCase::name).toList());
        assertEquals(List.of(1,2,3,4), parsed.cases().stream().map(TestCase::ordinal).toList());
        assertTrue(parsed.cases().stream().allMatch(x -> x.fileId().equals(file)));
        TestCase error = parsed.cases().get(2);
        assertEquals(Status.ERROR, error.status());
        assertEquals("QueryError", error.type());
        assertEquals("bad", error.message());
        assertEquals("original <line>\nat Demo.java:23", error.stack());
        assertEquals("unavailable", parsed.cases().get(3).message());
        assertNull(parsed.cases().getFirst().stack());
    }

    @Test void rejectsDamagedAmbiguousAndUnsupportedReportsWithoutSynthesizingPasses() {
        for (String invalid : List.of("<testsuite", "<testsuites/>",
                "<!DOCTYPE testsuite>" + suite("", 0, 0, 0, 0),
                suite("<testcase name='missing class'/>", 1, 0, 0, 0),
                suite("<testcase classname='D' name='x'>failed</testcase>", 1, 0, 0, 0),
                suite("<testcase classname='D' name='x'/>", 2, 0, 0, 0),
                suite("<testcase classname='D' name='x'><error/><skipped/></testcase>", 1, 0, 1, 0),
                suite("<testcase classname='D' name='x'><flakyFailure/></testcase>", 1, 0, 0, 0),
                suite("<testcase classname='D' name='x'><rerunError/></testcase>", 1, 0, 1, 0),
                suite("<testsuite/>", 0, 0, 0, 0),
                suite("", 4001, 0, 0, 0))) {
            assertTrue(assertThrows(IllegalArgumentException.class, () -> parse(invalid)).getMessage().contains("TEST-Demo.xml"));
        }
    }
}
