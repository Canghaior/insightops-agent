import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import maven_case_extract as extractor


class MavenCaseTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.work = Path(temp.name)
        self.root = self.work / "project"
        self.root.mkdir()
        self.scope = {"baseline": "example", "dependency": "org.example:parser",
                      "poms": ["pom.xml", "child/pom.xml"]}

    def write(self, name, body):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding="utf-8")
        return path

    def pom(self, name, body):
        return self.write(name, '<project xmlns="http://maven.apache.org/POM/4.0.0">\n' + body + "\n</project>")

    def test_sources_modules_inheritance_bom_and_declared_not_effective(self):
        self.pom("pom.xml", """<groupId>org.example</groupId><artifactId>root</artifactId><version>1</version>
<modules><module>child</module><module>not-in-scope</module></modules>
<properties><lib.version>3.0.5</lib.version><bom.version>2</bom.version></properties>
<dependencyManagement><dependencies><dependency><groupId>org.example</groupId><artifactId>bom</artifactId>
<version>{bom}</version><type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement>
<profiles><profile><id>unknown</id></profile></profiles>""".replace("{bom}", "$" + "{bom.version}"))
        self.pom("child/pom.xml", """<parent><groupId>org.example</groupId><artifactId>root</artifactId><version>1</version></parent>
<dependencies><dependency><groupId>org.example</groupId><artifactId>parser</artifactId>
<version>VERSION</version></dependency></dependencies>""".replace("VERSION", "$" + "{lib.version}"))
        result = extractor.extract(self.root, self.scope)
        dep = next(x for x in result["facts"] if x["kind"] == "direct-dependency")
        self.assertEqual((dep["raw"], dep["local"]), ("$" + "{lib.version}", "3.0.5"))
        self.assertEqual(dep["source"], {"file": "F02", "path": "child/pom.xml", "line": 4})
        self.assertEqual(dep["via"], [{"file": "F01", "path": "pom.xml", "line": 4}])
        self.assertEqual([x["local"] for x in result["facts"] if x["kind"] == "module"], ["child/pom.xml", None])
        self.assertEqual(next(x["local"] for x in result["facts"] if x["kind"] == "BOM-import"), "2")
        self.assertTrue(any("Profile" in g for g in result["gaps"]))
        self.assertEqual(result["mavenResolution"], "未执行")

    def test_inherited_nested_expression_is_unknown_in_consuming_module(self):
        self.pom("pom.xml", """<groupId>org.example</groupId><artifactId>root</artifactId><version>1</version>
<properties><engine.version>3.0.5</engine.version>
<alias.version>${engine.version}</alias.version>
<owner.version>${project.version}</owner.version></properties>""")
        self.pom("middle/pom.xml", """<parent><groupId>org.example</groupId><artifactId>root</artifactId><version>1</version></parent>
<groupId>org.example</groupId><artifactId>middle</artifactId><version>2</version>""")
        self.pom("middle/child/pom.xml", """<parent><groupId>org.example</groupId><artifactId>middle</artifactId><version>2</version></parent>
<version>9</version>
<properties><engine.version>3.0.8</engine.version><local.version>${engine.version}</local.version></properties>
<dependencies><dependency><groupId>org.example</groupId><artifactId>parser</artifactId>
<version>${alias.version}-${owner.version}</version></dependency></dependencies>
<build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId>
<configuration><release>${owner.version}</release></configuration></plugin></plugins></build>""")
        scope = dict(self.scope, poms=["pom.xml", "middle/pom.xml", "middle/child/pom.xml"])
        result = extractor.extract(self.root, scope)
        child = [f for f in result["facts"] if f["source"]["path"] == "middle/child/pom.xml"]
        for kind, expr, line in (("direct-dependency", "${alias.version}-${owner.version}", 4),
                                 ("compiler-setting", "${owner.version}", 5)):
            with self.subTest(kind=kind):
                fact = next(f for f in child if f["kind"] == kind)
                self.assertEqual(fact["raw"], expr)
                self.assertIsNone(fact["local"])
                self.assertIn({"file": "F01", "path": "pom.xml", "line": line}, fact["via"])
                self.assertIn(expr + "；本地未解析", extractor.markdown(result))
        self.assertEqual(next(f["local"] for f in child if f["name"] == "local.version"), "3.0.8")
        parent_alias = next(f for f in result["facts"] if f["source"]["path"] == "pom.xml" and f["name"] == "alias.version")
        self.assertEqual(parent_alias["local"], "3.0.5")
        self.assertIn("middle/child/pom.xml", "\n".join(result["gaps"]))
        self.assertEqual(result["mavenResolution"], "未执行")

    def test_missing_version_unknown_parent_and_cycles(self):
        self.pom("pom.xml", "<properties><a.version>$" + "{b.version}</a.version><b.version>$" + "{a.version}</b.version></properties>")
        self.pom("child/pom.xml", """<parent><groupId>x</groupId><artifactId>missing</artifactId><version>1</version><relativePath/></parent>
<dependencies><dependency><groupId>org.example</groupId><artifactId>parser</artifactId></dependency></dependencies>""")
        result = extractor.extract(self.root, self.scope)
        self.assertTrue(result["gaps"])
        facts = result["facts"]
        self.assertTrue(all(f["local"] is None for f in facts if f["kind"] in ("direct-dependency", "property", "parent")))
        self.assertIsNone(next(f["raw"] for f in facts if f["kind"] == "direct-dependency"))

    def test_missing_unreadable_and_unsupported_are_reported(self):
        self.pom("pom.xml", "")
        data = extractor.extract(self.root, self.scope)
        self.assertEqual([x["status"] for x in data["files"]], ["read", "missing-or-not-regular"])
        original = Path.open

        def restricted(path, *args, **kwargs):
            if path.name == "pom.xml":
                raise PermissionError()
            return original(path, *args, **kwargs)
        with patch.object(Path, "open", restricted):
            self.assertEqual(extractor.extract(self.root, self.scope)["files"][0]["reason"], "PermissionError")
        self.write("pom.xml", '<!DOCTYPE project><project/>')
        self.assertEqual(extractor.extract(self.root, self.scope)["files"][0]["status"], "unreadable-or-unsupported")

    def test_checks_allowlist_without_reading_other_files_and_repeats(self):
        self.pom("pom.xml", "<modules><module>../outside</module></modules>")
        key = self.write(".env", "PRIVATE_TEST_SENTINEL")
        src = self.write("child/src/test/java/demo/Smoke.java", "class Smoke { void seesPages() {} }")
        self.scope["java"] = [{"path": src.relative_to(self.root).as_posix(), "symbols": ["seesPages"]}]
        sf = self.work / "scope.json"
        sf.write_text(json.dumps(self.scope), encoding="utf-8")
        def hashes():
            return {p.relative_to(self.root): hashlib.sha256(p.read_bytes()).digest() for p in self.root.rglob("*") if p.is_file()}
        initial = hashes()
        for name in ("one", "two"):
            extractor.run(self.root, sf, self.work / name)
        self.assertEqual(initial, hashes())
        for name in ("manifest.json", "工程事实.md"):
            data = (self.work/"one"/name).read_bytes()
            self.assertEqual(data, (self.work/"two"/name).read_bytes())
            self.assertNotIn(key.read_bytes(), data)
        for invalid in (self.root/"out", self.work/"one", self.work):
            with self.assertRaises(ValueError):
                extractor.run(self.root, sf, invalid)
        for bad in ("../outside/pom.xml", ".env", "c:/outside/pom.xml", "child/../../pom.xml", "outside\\pom.xml"):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                extractor.extract(self.root, dict(self.scope, poms=["pom.xml", bad]))

    def test_symlink_target_is_not_read(self):
        self.pom("pom.xml", "")
        outside = self.work / "private"
        outside.mkdir()
        (outside / "pom.xml").write_text("never read", encoding="utf-8")
        link = self.root / "child"
        try:
            link.symlink_to(outside, target_is_directory=True)
        except OSError:
            if sys.platform != "win32":
                self.skipTest("OS does not permit symlink creation")
            import _winapi
            _winapi.CreateJunction(str(outside), str(link))
        row = extractor.extract(self.root, self.scope)["files"][1]
        self.assertEqual(row["status"], "redirected-path-not-read")
        self.assertNotIn("sha256", row)

    def test_case_parent_and_config_remain_static_and_keep_credentials_out(self):
        self.pom("pom.xml", """<parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId><version>3.4.2</version><relativePath/></parent>
<dependencies><dependency><groupId>com.h2database</groupId><artifactId>h2</artifactId></dependency></dependencies>""")
        self.write("src/main/resources/application.properties", "database=h2\nspring.datasource.password=PRIVATE_SENTINEL\nspring.jpa.open-in-view=PRIVATE_SENTINEL")
        path = "src/test/java/demo/OwnerTests.java"
        self.write(path, "class OwnerTests { void findsOwners() {} }")
        result = extractor.extract(self.root, dict(self.scope, springSettings=True, relatedDependencies=["com.h2database:h2"],
                                                   java=[{"path": path, "symbols": []}]))
        self.assertEqual(next(f["name"] for f in result["facts"] if f["kind"] == "test-method-candidate"), "findsOwners")
        self.assertEqual(next(f["local"] for f in result["facts"] if f["kind"] == "parent-version"), "3.4.2")
        self.assertIsNone(next(f["local"] for f in result["facts"] if f["name"] == "com.h2database:h2"))
        self.assertEqual({f["name"]: f["raw"] for f in result["facts"] if f["kind"] == "configuration"},
                         {"database": "h2", "spring.jpa.open-in-view": None})
        self.assertNotIn("PRIVATE_SENTINEL", json.dumps(result))


if __name__ == "__main__":
    unittest.main()
