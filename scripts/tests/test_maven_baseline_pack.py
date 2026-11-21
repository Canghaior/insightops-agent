import hashlib
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from maven_baseline_pack import pack
from maven_case_extract import extract


class MavenBaselinePackTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.area = Path(tmp.name)
        self.project = self.area / "project"
        self.project.mkdir()
        self.pom = self.project / "pom.xml"
        self.pom.write_text("<project><artifactId>sample</artifactId></project>", encoding="utf8")
        self.scope = {"baseline": "example", "dependency": "org.example:core", "poms": ["pom.xml"]}
        self.spec = self.area / "scope.json"
        self.spec.write_text(json.dumps(self.scope), encoding="utf8")
        self.result = self.area / "baseline.zip"

    def test_legacy_manifest_and_raw_bytes_match_and_two_outputs_are_identical(self):
        legacy = self.area / "old.json"
        original = json.dumps(extract(self.project, self.scope), indent=1).encode()
        legacy.write_bytes(original)
        pack(self.project, self.spec, self.result, legacy)
        second = self.area / "second.zip"
        pack(self.project, self.spec, second, legacy)
        self.assertEqual(second.read_bytes(), self.result.read_bytes())
        with zipfile.ZipFile(second) as archive:
            self.assertEqual(archive.read("evidence/extractor.json"), original)
            desc = json.loads(archive.read("baseline.json"))
            for f in desc["files"]:
                self.assertEqual(hashlib.sha256(archive.read(f["path"])).hexdigest(), f["sha256"])
        self.assertEqual(legacy.read_bytes(), original)
        with self.assertRaises(ValueError):
            pack(self.project, self.spec, second, legacy)

    def test_changed_same_named_file_rejects_old_inventory(self):
        old = self.area / "legacy.json"
        old.write_text(json.dumps(extract(self.project,self.scope)), encoding="utf8")
        self.pom.write_text("<project><artifactId>other</artifactId></project>", encoding="utf8")
        with self.assertRaisesRegex(ValueError, "differs"):
            pack(self.project, self.spec, self.result, old)
        self.assertFalse(self.result.exists())

    def test_modules_and_unreadable_scope_fail_without_saving(self):
        self.pom.write_text("<project><profiles><profile><modules><module>a</module></modules></profile></profiles></project>", encoding="utf8")
        with self.assertRaisesRegex(ValueError, "modules"):
            pack(self.project, self.spec, self.result)
        self.pom.write_text("<project/>", encoding="utf8")
        self.spec.write_text(json.dumps(dict(self.scope,wrapper=True)), encoding="utf8")
        with self.assertRaisesRegex(ValueError, "unavailable"):
            pack(self.project, self.spec, self.result)

