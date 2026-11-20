import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import surefire_evidence as evidence


class SurefireEvidenceTest(unittest.TestCase):
    def setUp(self):
        work = tempfile.TemporaryDirectory()
        self.addCleanup(work.cleanup)
        self.root = Path(work.name)
        self.reports = self.root / "surefire-reports"
        self.reports.mkdir()
        self.build = self.root / "build.json"
        self.command("!PostgresTests,!MySqlTests")

    def command(self, selector):
        self.build.write_text(json.dumps({"command": ["mvnw", "-Dtest=" + selector, "package"], "exit": 1}))

    def suite(self, body, counters='tests="1" failures="0" errors="0" skipped="0"', name="ServiceTests"):
        path = self.reports / ("TEST-" + name + ".xml")
        path.write_text(f'<testsuite name="{name}" {counters}>{body}</testsuite>', encoding="utf-8")
        return path

    def test_statuses_provenance_and_command_scope(self):
        path = self.suite("""
          <properties><property name="private" value="PRIVATE_SENTINEL"/></properties>
          <testcase classname="ServiceTests" name="ok"><system-out>PRIVATE_SENTINEL</system-out></testcase>
          <testcase classname="ServiceTests" name="bad"><failure type="AssertionError">stack:9</failure></testcase>
          <testcase classname="ServiceTests" name="broken"><error type="SQLException">stack:18</error></testcase>
          <testcase classname="ServiceTests" name="ignored"><skipped message="requires resource"/></testcase>
        """, 'tests="4" failures="1" errors="1" skipped="1"')
        raw = path.read_bytes()
        rendered = evidence.convert(self.reports, self.build)
        self.assertIn("通过 1，断言失败 1，执行报错 1，跳过 1", rendered)
        self.assertIn("明确排除类 PostgresTests, MySqlTests", rendered)
        for item in ("ServiceTests#bad — 断言失败", "ServiceTests#broken — 执行报错", "stack:9",
                     "stack:18", "requires resource", "testcase 3，元素 error", hashlib.sha256(raw).hexdigest()):
            self.assertIn(item, rendered)
        self.assertNotIn("PRIVATE_SENTINEL", rendered)
        self.assertEqual(raw, path.read_bytes())

    def test_does_not_invent_exclusions_or_skips(self):
        self.suite('<testcase classname="ServiceTests" name="ok"/>')
        self.command("*ServiceTests,!*External")
        text = evidence.convert(self.reports, self.build)
        self.assertIn("未出现的测试状态未知", text)
        self.assertIn("跳过 0", text)
        self.command("!ServiceTests")
        with self.assertRaisesRegex(ValueError, "conflicts"):
            evidence.convert(self.reports, self.build)

    def test_inconsistent_input_is_not_reported_as_passing(self):
        self.suite('<testcase classname="ServiceTests" name="broken"><error/></testcase>')
        with self.assertRaisesRegex(ValueError, "disagree"):
            evidence.convert(self.reports, self.build)
        self.suite('<testcase classname="ServiceTests" name="rerun"><flakyFailure/></testcase>')
        with self.assertRaisesRegex(ValueError, "unsupported outcome"):
            evidence.convert(self.reports, self.build)
        self.suite('<testcase classname="ServiceTests" name="ambiguous"><failure/><skipped/></testcase>')
        with self.assertRaisesRegex(ValueError, "ambiguous"):
            evidence.convert(self.reports, self.build)

    def test_originals_and_existing_outputs_are_protected(self):
        output = self.root / "evidence.md"
        with self.assertRaisesRegex(ValueError, "no original"):
            evidence.write_report(self.reports, self.build, output)
        self.assertFalse(output.exists())
        path = self.suite('<testcase classname="ServiceTests" name="ok"/>')
        with self.assertRaises(ValueError):
            evidence.write_report(self.reports, self.build, path)
        evidence.write_report(self.reports, self.build, output)
        with self.assertRaises(FileExistsError):
            evidence.write_report(self.reports, self.build, output)
        path.write_text('<!DOCTYPE testsuite><testsuite name="X"/>')
        with self.assertRaisesRegex(ValueError, "DTD"):
            evidence.convert(self.reports, self.build)


if __name__ == "__main__":
    unittest.main()
