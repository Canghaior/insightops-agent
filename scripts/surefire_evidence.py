"""Convert one existing Maven Surefire run to readable evidence. Never invokes Maven."""
import argparse
import hashlib
import json
import re
from collections import Counter
from pathlib import Path
from xml.etree import ElementTree as ET


LABELS = {"passed": "通过", "failure": "断言失败", "error": "执行报错", "skipped": "跳过"}
OUTPUT_TAGS = {"system-out", "system-err"}


class SuiteParser(ET.TreeBuilder):
    def doctype(self, name, pubid, system):
        raise ValueError("XML DTD is outside the supported Surefire format")


def read_suite(path):
    data = path.read_bytes()
    suite = ET.fromstring(data, parser=ET.XMLParser(target=SuiteParser()))
    if suite.tag != "testsuite" or not suite.get("name") or suite.find("testsuite") is not None:
        raise ValueError(f"{path.name}: expected a single named Surefire testsuite")
    counts = Counter()
    cases = []
    for ordinal, case in enumerate(suite.findall("testcase"), 1):
        markers = [node for node in case if node.tag not in OUTPUT_TAGS]
        if not case.get("name") or not case.get("classname") or len(markers) > 1:
            raise ValueError(f"{path.name} testcase {ordinal}: ambiguous identity or outcome")
        status = markers[0].tag if markers else "passed"
        if markers and status not in ("failure", "error", "skipped"):
            raise ValueError(f"{path.name} testcase {ordinal}: unsupported outcome {status}")
        counts[status] += 1
        detail = ""
        if status in ("error", "failure", "skipped"):
            node = markers[0]
            detail = "\n".join(x for x in (node.get("type"), node.get("message"), (node.text or "").strip()) if x)
        cases.append((case.get("classname"), case.get("name"), status, detail))
    actual = (len(cases), counts["failure"], counts["error"], counts["skipped"])
    counters = ("tests", "failures", "errors", "skipped")
    if tuple(suite.get(key) for key in counters) != tuple(map(str, actual)):
        raise ValueError(f"{path.name}: suite totals disagree with testcase outcomes")
    return dict(file=path.name, name=suite.get("name"), sha256=hashlib.sha256(data).hexdigest(),
                counts=counts, cases=cases)


def convert(reports, build_record):
    reports = reports.resolve(strict=True)
    suites = []
    names = set()
    xmls = (p for p in reports.iterdir() if p.name.lower().startswith("test-") and p.name.lower().endswith(".xml"))
    for path in sorted(xmls, key=lambda p: (p.name.lower(), p.name)):
        if not path.is_file() or path.resolve() != path or path.stat().st_size > 16 * 1024 * 1024:
            raise ValueError(f"unsupported report file: {path.name}")
        suite = read_suite(path)
        if suite["name"] in names:
            raise ValueError(f"multiple XML reports for suite {suite['name']}")
        names.add(suite["name"])
        suites.append(suite)
    if not suites:
        raise ValueError("no original TEST-*.xml found")

    build = json.loads(build_record.read_text(encoding="utf-8-sig"))
    command = build["command"]
    if not isinstance(command, list) or not all(isinstance(arg, str) for arg in command) or type(build["exit"]) is not int:
        raise ValueError("expected a recorded argument list and integer exit status")
    selectors = [arg.removeprefix("-Dtest=") for arg in command if arg.startswith("-Dtest=")]
    exclusions = []
    if len(selectors) == 1 and re.fullmatch(r"![A-Za-z_]\w*(?:,![A-Za-z_]\w*)*", selectors[0]):
        exclusions = [item[1:] for item in selectors[0].split(",")]
        reported = {s.rsplit(".", 1)[-1] for s in names}
        reported.update(case[0].rsplit(".", 1)[-1] for s in suites for case in s["cases"])
        if reported.intersection(exclusions):
            raise ValueError("recorded exclusion conflicts with observed XML; check the build and report directory")

    total = Counter()
    for suite in suites:
        total.update(suite["counts"])

    def summary(counts):
        return "，".join(f"{LABELS[key]} {counts[key]}" for key in LABELS)

    lines = ["# Maven Surefire 回归证据", "",
             f"来源为同一次构建的 {len(suites)} 个原始 TEST-*.xml。每一行对应一个 testcase，序号为其在该 XML 中的位置。",
             f"XML 共记录 {sum(total.values())} 项。{summary(total)}。构建记录中的进程退出码为 {build['exit']}。",
             "", "## 命令限定的范围", ""]
    lines += [f"- 命令记录中的 -Dtest 参数为 {', '.join(selectors) if selectors else '未指定'}。"]
    if exclusions:
        lines += [f"- 命令明确排除类 {', '.join(exclusions)}。排除项单列，计数仅来自 XML 内的 testcase。"]
    else:
        lines += ["- 未从该命令取得受支持的显式排除类清单。未出现的测试状态未知。"]
    lines += ["", "## 套件与方法", ""]
    details = []
    for suite in suites:
        lines += [f"### {suite['name']}", "", f"原件 {suite['file']}", f"SHA-256 {suite['sha256']}",
                  f"共 {len(suite['cases'])} 项，{summary(suite['counts'])}。", ""]
        for ordinal, (classname, method, status, detail) in enumerate(suite["cases"], 1):
            lines.append(f"- {ordinal}. {classname}#{method} — {LABELS[status]}")
            if detail:
                details.extend([f"### {classname}#{method}", "",
                                f"原件 {suite['file']}，testcase {ordinal}，元素 {status}。", "",
                                *["    " + line for line in detail.splitlines()], ""])
        lines.append("")
    if details:
        lines.extend(["## XML 中的异常或跳过原因", "", *details])
    return "\n".join(lines) + "\n"


def write_report(reports, build_record, output):
    if output.resolve().is_relative_to(reports.resolve()) or output.resolve() == build_record.resolve():
        raise ValueError("choose a new output file outside the source reports")
    content = convert(reports, build_record)
    with output.open("x", encoding="utf-8", newline="\n") as stream:
        stream.write(content)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ("reports", "build-record", "output"):
        parser.add_argument("--" + arg, required=True, type=Path)
    args = parser.parse_args()
    write_report(args.reports, args.build_record, args.output)
