"""Extract cited, local Maven declarations from an explicitly scoped snapshot."""
import argparse
import hashlib
import json
import posixpath
import re
from pathlib import Path
from xml.parsers import expat


WRAPPER = ".mvn/wrapper/maven-wrapper.properties"
NAMESPACE = "http://maven.apache.org/POM/4.0.0"
COORD = r"[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*"
PROPERTY = re.compile(r"\$\{([A-Za-z0-9_.-]+)}")
SAFE_VALUE = re.compile(r"[A-Za-z0-9_.$}{()[\],+-]{1,160}")
PRIVATE = re.compile(r"secret|token|password|credential|private.key|api.key", re.I)
MAX_BYTES = 1024 * 1024
PUBLIC_SETTINGS = {
    "database": r"h2|mysql|postgres",
    "spring.jpa.open-in-view": r"true|false",
    "spring.jpa.hibernate.ddl-auto": r"none|validate|update|create|create-drop",
    "spring.sql.init.schema-locations": r"classpath\*?:db/\$\{database\}/schema\.sql",
    "spring.sql.init.data-locations": r"classpath\*?:db/\$\{database\}/data\.sql",
}


class Node:
    def __init__(self, name, line):
        self.name, self.line = name, line
        self.text = ""
        self.children = []

    def all(self, name):
        return [x for x in self.children if x.name == name]

    def at(self, path):
        node = self
        for name in path.split("/"):
            node = next(iter(node.all(name)), None)
            if node is None:
                return None
        return node

    def value(self, path):
        node = self.at(path)
        return node.text.strip() if node is not None else ""


def parse_pom(raw):
    parser = expat.ParserCreate(namespace_separator=" ")
    stack, roots = [], []

    def start(name, _attrs):
        if " " in name:
            namespace, tag = name.rsplit(" ", 1)
            name = tag if namespace == NAMESPACE else name
        node = Node(name, parser.CurrentLineNumber)
        (stack[-1].children if stack else roots).append(node)
        stack.append(node)
        if len(stack) > 80:
            raise ValueError("XML nesting exceeds limit")

    def reject(*_args):
        raise ValueError("DTD and external entities are unsupported")

    def chars(s):
        if stack:
            stack[-1].text += s

    parser.StartElementHandler = start
    parser.EndElementHandler = lambda _: stack.pop()
    parser.CharacterDataHandler = chars
    parser.StartDoctypeDeclHandler = reject
    parser.ExternalEntityRefHandler = reject
    parser.Parse(raw, True)
    if roots[0].name != "project":
        raise ValueError("unsupported POM root or namespace")
    return roots[0]


def safe_name(name, kind):
    if not isinstance(name, str) or not name or "\\" in name or ":" in name:
        raise ValueError("invalid scoped path")
    parts = name.split("/")
    if kind == "wrapper" and name == WRAPPER:
        return
    if kind == "settings" and name == "src/main/resources/application.properties":
        return
    if any(not re.fullmatch(r"[A-Za-z0-9_-][A-Za-z0-9_.-]*", p) for p in parts):
        raise ValueError("unsupported scoped path")
    if kind == "pom" and parts[-1] == "pom.xml":
        return
    if kind == "java" and re.fullmatch(r"(?:[\w.-]+/)*src/(?:main|test)/java/(?:[\w.-]+/)*\w+\.java", name):
        return
    raise ValueError("file type is outside the prototype scope")


def allowlist(scope):
    entries = [(p, "pom", []) for p in scope["poms"]]
    if not entries or entries[0][0] != "pom.xml":
        raise ValueError("scope must start with root pom.xml")
    for entry in scope.get("java", []):
        symbols = entry["symbols"]
        if any(not re.fullmatch(COORD, s) or PRIVATE.search(s) for s in symbols):
            raise ValueError("unsupported Java symbol")
        entries.append((entry["path"], "java", symbols))
    if scope.get("wrapper"):
        entries.append((WRAPPER, "wrapper", []))
    if scope.get("springSettings"):
        entries.append(("src/main/resources/application.properties", "settings", []))
    if len(entries) > 64 or len({e[0] for e in entries}) != len(entries):
        raise ValueError("too many or duplicate inputs")
    for name, kind, _ in entries:
        safe_name(name, kind)
    if any(not re.fullmatch(COORD + ":" + COORD, c)
           for c in [scope["dependency"], *scope.get("relatedDependencies", [])]):
        raise ValueError("expected groupId:artifactId")
    if not re.fullmatch(r"[A-Za-z0-9_.-]{1,100}", scope["baseline"]):
        raise ValueError("invalid snapshot label")
    return entries


def safe_version(value):
    return value if SAFE_VALUE.fullmatch(value) and not PRIVATE.search(value) else None


def extract(root, scope):
    files, poms, facts, gaps, by_path = [], {}, [], [], {}
    root = root.resolve(strict=True)

    def cite(path, line):
        return {"file": by_path[path]["id"], "path": path, "line": line}

    def fact(path, line, kind, name, raw, local=None, via=()):
        facts.append(dict(source=cite(path, line), kind=kind, name=name, raw=raw,
                          local=local, via=list(via)))

    for idx, (name, kind, symbols) in enumerate(allowlist(scope), 1):
        record = {"id": f"F{idx:02}", "path": name}
        by_path[name] = record
        files.append(record)
        path = root / name
        try:
            if path.resolve() != path:
                record["status"] = "redirected-path-not-read"
                continue
            if not path.is_file():
                record["status"] = "missing-or-not-regular"
                continue
            with path.open("rb") as stream:
                data = stream.read(MAX_BYTES + 1)
            if len(data) > MAX_BYTES:
                record["status"] = "size-limit"
                continue
            record.update(status="read", sha256=hashlib.sha256(data).hexdigest(), bytes=len(data))
            if kind == "pom":
                poms[name] = parse_pom(data)
                continue
            for line, s in enumerate(data.decode("utf-8-sig").splitlines(), 1):
                if kind == "settings":
                    key, separator, value = s.partition("=")
                    key, value = key.strip(), value.strip()
                    if separator and key in PUBLIC_SETTINGS:
                        allowed = re.fullmatch(PUBLIC_SETTINGS[key], value)
                        fact(name, line, "configuration", key, value if allowed else None)
                if kind == "wrapper":
                    match = re.fullmatch(r"\s*distributionUrl\s*=.*?/apache-maven-([0-9.]+)-bin\.zip\s*", s)
                    if match:
                        fact(name, line, "wrapper", "Apache Maven distribution", match[1], match[1])
                for symbol in symbols:
                    if re.search(r"(?<![\w$])" + re.escape(symbol) + r"(?![\w$])", s):
                        literal = re.search(r"\b" + re.escape(symbol) + r"\s*=\s*([0-9_]+)\s*;", s)
                        call = re.search(r"\b" + re.escape(symbol) + r"\((?:[\w]+)?\)", s)
                        value = literal[1] if literal else (call[0] if call else symbol)
                        fact(name, line, "java-symbol", symbol, value)
                test = re.search(r"\bvoid\s+(\w+)\s*\(", s)
                if kind == "java" and "/src/test/java/" in "/" + name and test:
                    fact(name, line, "test-method-candidate", test[1], test[1])
        except (OSError, ValueError, expat.ExpatError, RuntimeError) as exc:
            record.update(status="unreadable-or-unsupported", reason=type(exc).__name__)

    def relative(source, target):
        if not target or "\\" in target or ":" in target or target.startswith("/"):
            return None
        return posixpath.normpath(posixpath.join(posixpath.dirname(source), target))

    parents = {}
    for path, pom in poms.items():
        par = pom.at("parent")
        if par is None:
            continue
        gav = [safe_version(par.value(x)) for x in ("groupId", "artifactId", "version")]
        candidate = relative(path, par.value("relativePath") if par.at("relativePath") is not None else "../pom.xml")
        if candidate not in poms and candidate is not None:
            candidate = posixpath.join(candidate, "pom.xml")
        local = poms.get(candidate)
        if local and all(gav) and [local.value(x) for x in ("groupId", "artifactId", "version")] == gav:
            parents[path] = candidate
        else:
            gaps.append(f"{path}:{par.line} 父 POM 未在授权文件中按坐标确认。")
        fact(path, par.line, "parent", "groupId:artifactId:version",
             ":".join(v or "?" for v in gav), parents.get(path))
        if par.at("version") and all(gav[:2]):
            fact(path, par.at("version").line, "parent-version", ":".join(gav[:2]), gav[2], gav[2]
                 if gav[2] and "$" not in gav[2] else None)

    def lookup(path, key, seen, consumer):
        if (path, key) in seen or PRIVATE.search(key) or path not in poms:
            return None, []
        seen = seen | {(path, key)}
        pom = poms[path]
        node = pom.at(key[8:]) if key in ("project.version", "project.groupId", "project.artifactId") else pom.at("properties/" + key)
        if node is None:
            return lookup(parents.get(path), key, seen, consumer)
        raw = node.text.strip()
        if path != consumer and "$" in raw:
            # The child can override names used inside an inherited expression.
            gap = f"{consumer} 引用 {path}:{node.line} 的继承属性 {key}，其嵌套表达式在子模块中的值未知。"
            if gap not in gaps:
                gaps.append(gap)
            return None, [cite(path, node.line)]
        val, via = resolve(path, raw, seen)
        return val, [cite(path, node.line), *via]

    def resolve(path, raw, seen=frozenset()):
        if not safe_version(raw):
            return None, []
        refs = []
        result = raw
        for match in PROPERTY.finditer(raw):
            value, chain = lookup(path, match[1], seen, path)
            refs.extend(chain)
            if value is None:
                return None, refs
            result = result.replace(match[0], value)
        return (result if "$" not in result else None), refs

    def declared(path, node, kind, label):
        raw = node.text.strip()
        local, chain = resolve(path, raw)
        fact(path, node.line, kind, label, safe_version(raw), local, chain)

    for path, pom in poms.items():
        modules = pom.at("modules")
        fact(path, modules.line if modules else pom.line, "structure", "top-level modules",
             str(len(modules.all("module")) if modules else 0))
        management = pom.at("dependencyManagement/dependencies")
        imports = [d for d in management.all("dependency") if d.value("type") == "pom" and d.value("scope") == "import"] if management else []
        fact(path, management.line if management else pom.line, "structure", "explicit local BOM imports", str(len(imports)))
        for module in modules.all("module") if modules else []:
            val = module.text.strip()
            target = relative(path, posixpath.join(val, "pom.xml"))
            known = bool(target in poms)
            fact(path, module.line, "module", "module POM", val if re.fullmatch(r"[\w./-]+", val) else None,
                 target if known else None)
            if not known:
                gaps.append(f"{path}:{module.line} 模块 POM 未读或未列入范围。")
        for key in ("groupId", "artifactId", "version", "packaging"):
            if pom.at(key):
                declared(path, pom.at(key), "project", key)
        props = pom.at("properties")
        for node in props.children if props else []:
            if not PRIVATE.search(node.name) and (node.name.endswith(".version") or node.name.startswith("maven.compiler.")):
                declared(path, node, "property", node.name)
        for section in ("dependencies", "dependencyManagement/dependencies"):
            deps = pom.at(section)
            for dep in deps.all("dependency") if deps else []:
                group, artifact = dep.value("groupId"), dep.value("artifactId")
                name = f"{group}:{artifact}"
                bom = dep.value("type") == "pom" and dep.value("scope") == "import"
                if not re.fullmatch(COORD + ":" + COORD, name):
                    gaps.append(f"{path}:{dep.line} 动态或不支持的依赖坐标未解析。")
                    continue
                if name in [scope["dependency"], *scope.get("relatedDependencies", [])] or bom:
                    kind = "BOM-import" if bom else ("direct-dependency" if section == "dependencies" else "managed-declaration")
                    version = dep.at("version")
                    if version is not None:
                        declared(path, version, kind, name)
                    else:
                        fact(path, dep.line, kind, name, None)
        plugins = pom.at("build/plugins")
        for plugin in plugins.all("plugin") if plugins else []:
            artifact = plugin.value("artifactId")
            if artifact == "maven-compiler-plugin":
                for field in ("source", "target", "release"):
                    node = plugin.at("configuration/" + field)
                    if node:
                        declared(path, node, "compiler-setting", field)
            if artifact == "maven-enforcer-plugin" and plugin.at("executions"):
                for ex in plugin.at("executions").all("execution"):
                    for rule in ("requireJavaVersion", "requireMavenVersion"):
                        node = ex.at("configuration/rules/" + rule + "/version")
                        if node:
                            declared(path, node, "build-rule", rule)
        if pom.at("profiles"):
            gaps.append(f"{path}:{pom.at('profiles').line} Profile 声明存在，未激活或解析。")
    inventory = json.dumps([(r["path"], r["status"], r.get("sha256")) for r in files], separators=(",", ":"))
    return dict(baselineLabel=scope["baseline"], inventorySha256=hashlib.sha256(inventory.encode()).hexdigest(),
                dependency=scope["dependency"], files=files, facts=facts, gaps=gaps,
                mavenResolution="未执行", runtimeObservation="未测量",
                limits=["只读授权清单；外部父 POM、导入 BOM 内容、活动 Profile、传递依赖及最终有效版本未解析。",
                        "Java 命中为静态行号；未执行测试，源码中的默认值不代表部署配置。"])


def markdown(result):
    lines = ["# Maven 工程文件提取", "", "来源为工具读取的授权文件。",
             f"案例坐标 {result['dependency']}，调用方基线 {result['baselineLabel']}。",
             f"独立内容清单 SHA-256 {result['inventorySha256']}。", "", "## 文件索引", ""]
    for f in result["files"]:
        lines.append(f"- {f['id']} {f['path']}（{f['status']}）")
    lines += ["", "## 文件中的声明及静态线索", "", "“本地”只指清单内字面值或已核对属性的展开。"]
    for f in result["facts"]:
        src = f["source"]
        raw = f["raw"] if f["raw"] is not None else "未声明或不支持"
        known = f"；本地 {f['local']}" if f["local"] is not None else ""
        unresolved_property = f["via"] and "$" in raw
        if not known and (unresolved_property or f["kind"] in ("BOM-import", "direct-dependency", "managed-declaration", "parent", "build-rule")):
            known = "；本地未解析"
        via = "，属性来自 " + ", ".join(f"{v['file']}:L{v['line']}" for v in f["via"]) if f["via"] else ""
        lines.append(f"- {src['file']}:L{src['line']} {f['kind']} {f['name']} = {raw}{known}{via}")
    lines += ["", "## 取得范围与未知项", "",
              "- Maven 实际解析结果：未执行。实际 Java/Maven 运行环境：未测量。"]
    lines.extend("- " + gap for gap in result["gaps"] + result["limits"])
    return "\n".join(lines) + "\n"


def run(project, scope_file, output):
    project = project.resolve(strict=True)
    output = output.resolve()
    if output.is_relative_to(project) or project.is_relative_to(output) or output.exists():
        raise ValueError("choose a new output directory outside the project")
    result = extract(project, json.loads(scope_file.read_text(encoding="utf-8-sig")))
    output.mkdir(parents=True, exist_ok=False)
    (output / "manifest.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "工程事实.md").write_text(markdown(result), encoding="utf-8")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ("project", "scope", "output"):
        parser.add_argument("--" + arg, required=True, type=Path)
    args = parser.parse_args()
    run(args.project, args.scope, args.output)
