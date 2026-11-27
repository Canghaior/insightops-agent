#!/usr/bin/env python3
"""Run a committed Maven Reactor in the same offline Docker boundary as local engineering."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tarfile
from threading import Thread
from uuid import uuid4
import xml.etree.ElementTree as ET

MAX_ARTIFACT = 2 * 1024 * 1024
MAX_TOTAL = 12 * 1024 * 1024
MAX_SOURCE = 24 * 1024 * 1024
MAX_MODULES = 32
MAX_XML = 160
MAX_CASES = 4000
XML_NAME = re.compile(r"TEST-[\w.$-]+\.xml")
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
DEFAULT_IMAGE = "maven:3.9.11-eclipse-temurin-21"


def git(repo: Path, *args: str) -> bytes:
    return subprocess.run(["git", "-C", str(repo), *args], check=True, capture_output=True, timeout=35).stdout


def export_source(repo: Path, commit: str, directory: Path) -> Path:
    rows = git(repo, "ls-tree", "-rlz", commit).split(b"\0")
    total = 0
    if len(rows) > 3001:
        raise ValueError("提交目录超过 3000 个文件")
    for row in filter(None, rows):
        properties, _ = row.split(b"\t", 1)
        mode, kind, _, size = properties.split()
        if mode not in (b"100644", b"100755") or kind != b"blob":
            raise ValueError("工程验证只接收普通 Git 文件")
        total += int(size)
        if total > MAX_SOURCE:
            raise ValueError("提交的源码超过 24 MiB")
    archive = directory / "source.tar"
    git(repo, "archive", "--format=tar", f"--output={archive}", commit)
    snapshot = directory / "source"
    snapshot.mkdir()
    with tarfile.open(archive) as payload:
        for member in payload.getmembers():
            if not (member.isfile() or member.isdir()) or not safe_relative(member.name):
                raise ValueError("源码归档包含不支持的路径或条目")
        payload.extractall(snapshot, filter="data")
    return snapshot


def safe_relative(raw: str) -> bool:
    path = PurePosixPath(raw)
    return bool(path.parts) and not path.is_absolute() and ".." not in path.parts and all(
        part and part == part.strip() and not any(c in part for c in '\\:,\0')
        for part in path.parts
    )


def reactor_modules(root: Path) -> list[dict]:
    found = {}

    def visit(path: Path):
        relative = path.relative_to(root).as_posix()
        if relative in found or len(found) >= MAX_MODULES:
            raise ValueError("Reactor 模块重复或超过 32 个")
        pom = path / "pom.xml"
        if not pom.is_file() or pom.stat().st_size > MAX_ARTIFACT:
            raise ValueError(f"模块 {relative} 缺少支持的 pom.xml")
        content = pom.read_bytes()
        if b"<!DOCTYPE" in content.upper():
            raise ValueError(f"模块 {relative} 的 POM 使用了不支持的 DTD")
        model = ET.fromstring(content)
        prefix = "m:" if model.tag.startswith("{") else ""
        name = model.findtext(prefix + "name", namespaces=NS) or model.findtext(prefix + "artifactId", namespaces=NS)
        if not name or not name.strip():
            raise ValueError(f"模块 {relative} 缺少项目名")
        found[relative] = {"path": relative, "projectName": name.strip(), "selected": None, "files": []}
        for child in model.findall(f"{prefix}modules/{prefix}module", NS):
            text = (child.text or "").strip()
            if not safe_relative(text) or "${" in text:
                raise ValueError(f"模块 {relative} 有不能静态定位的子模块")
            visit(path / PurePosixPath(text))

    visit(root)
    if len(found) < 2:
        raise ValueError("工程至少需要一个静态声明的子模块")
    return list(found.values())


def docker_local() -> list[str]:
    docker = shutil.which("docker")
    if not docker:
        raise ValueError("未找到本机 Docker")
    endpoint = "npipe:////./pipe/dockerDesktopLinuxEngine" if os.name == "nt" else "unix:///var/run/docker.sock"
    return [docker, "--host", endpoint]


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


def write_json(path: Path, content):
    path.write_text(json.dumps(content, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def run_maven(docker: list[str], snapshot: Path, cache: Path, image_id: str, log: Path, timeout: int) -> dict:
    if "," in str(snapshot) or "," in str(cache):
        raise ValueError("Docker 挂载的路径不能含逗号")
    name = "insightops-reactor-evidence-" + uuid4().hex
    maven = ["mvn", "--batch-mode", "--offline", "-Dstyle.color=never", "-Dmaven.repo.local=/offline-repo", "test"]
    command = docker + [
        "run", "--rm", "--pull=never", "--name", name, "--network=none", "--cap-drop=ALL",
        "--security-opt=no-new-privileges", "--pids-limit=256", "--memory=2g", "--cpus=2", "--read-only",
        "--tmpfs", "/tmp:rw,nosuid,nodev", "--mount", f"type=bind,source={snapshot},target=/project",
        "--mount", f"type=bind,source={cache},target=/offline-repo,readonly",
        "--env", "HOME=/tmp", "--workdir", "/project", "--entrypoint", "mvn", image_id,
    ] + maven[1:]
    started = now()
    partial = False
    timed_out = False
    with log.open("wb") as out:
        with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT) as process:
            def drain():
                nonlocal partial
                left = MAX_ARTIFACT
                while chunk := process.stdout.read(65536):
                    out.write(chunk[:left])
                    if len(chunk) > left:
                        partial = True
                    left = max(0, left - len(chunk))

            reader = Thread(target=drain)
            reader.start()
            try:
                process.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                timed_out = True
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait(timeout=10)
                    subprocess.run(docker + ["rm", "--force", name], capture_output=True, timeout=12)
                reader.join()
    return {"command": maven, "startedAt": started, "endedAt": now(),
            "exitCode": None if timed_out else process.returncode, "timedOut": timed_out, "truncatedLog": partial}


def collect(snapshot: Path, directory: Path, modules: list[dict], remaining: int) -> list[str]:
    issues = []
    saved = 0
    for index, module in enumerate(modules):
        reports = snapshot / module["path"] / "target/surefire-reports"
        if not reports.exists():
            continue
        if reports.resolve() != reports or not reports.is_dir():
            issues.append(f"{module['path']} 的 Surefire 目录未能安全采集")
            continue
        for report in sorted(reports.glob("TEST-*.xml")):
            valid = report.resolve() == report and report.is_file() and XML_NAME.fullmatch(report.name)
            size = report.stat().st_size if valid else 0
            if not valid or size < 1 or size > MAX_ARTIFACT or size > remaining or saved >= MAX_XML:
                issues.append(f"{module['path']} / {report.name} 超出原件归档范围")
                continue
            relative = Path("xml") / str(index) / report.name
            destination = directory / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(report, destination)
            module["files"].append({"path": relative.as_posix(), "bytes": size,
                                    "sha256": hashlib.sha256(destination.read_bytes()).hexdigest()})
            remaining -= size
            saved += 1
    return issues


def attempt_observations(directory: Path, modules: list[dict]) -> dict:
    states = {}
    total = 0
    for module in modules:
        for file in module["files"]:
            content = (directory / file["path"]).read_bytes()
            if b"<!DOCTYPE" in content.upper():
                continue
            try:
                suite = ET.fromstring(content)
            except ET.ParseError:
                continue
            if suite.tag != "testsuite":
                continue
            for case in suite.findall("testcase"):
                total += 1
                if total > MAX_CASES:
                    raise ValueError("XML 测试项超过 4000 个")
                identity = (module["path"], suite.get("name"), case.get("classname"), case.get("name"))
                state = next((kind for kind in ("failure", "error", "skipped") if case.find(kind) is not None), "passed")
                states.setdefault(identity, []).append(state)
    return states


def main() -> int:
    arguments = argparse.ArgumentParser(description=__doc__)
    arguments.add_argument("--repo", type=Path, required=True)
    arguments.add_argument("--revision", default="HEAD")
    arguments.add_argument("--offline-repository", type=Path, required=True)
    arguments.add_argument("--output", type=Path, required=True)
    arguments.add_argument("--image", default=DEFAULT_IMAGE)
    arguments.add_argument("--timeout", type=int, default=180)
    arguments.add_argument("--attempts", type=int, choices=(1, 2, 3), default=1)
    opts = arguments.parse_args()
    if not 5 <= opts.timeout <= 600:
        arguments.error("--timeout 应在 5 至 600 秒")
    cache = opts.offline_repository.resolve(strict=True)
    if not cache.is_dir():
        arguments.error("--offline-repository 需要实际的依赖缓存目录")
    commit = git(opts.repo.resolve(strict=True), "rev-parse", "--verify", "--end-of-options", opts.revision + "^{commit}").decode("ascii").strip()
    if not re.fullmatch(r"[0-9a-f]{40,64}", commit):
        arguments.error("无法确定 Git 提交")
    docker = docker_local()
    image = subprocess.run(docker + ["image", "inspect", "--format", "{{.Id}}", "--", opts.image],
                           check=True, capture_output=True, timeout=15, text=True).stdout.strip()
    if not re.fullmatch(r"sha256:[a-f0-9]{64}", image):
        arguments.error("无法读取本机 Maven 镜像摘要")

    output = opts.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    records = []
    facts = []
    for index in range(1, opts.attempts + 1):
        directory = output / f"attempt-{index}"
        directory.mkdir()
        snapshot = export_source(opts.repo, commit, directory)
        modules = reactor_modules(snapshot)
        log = directory / "maven.log"
        result = run_maven(docker, snapshot, cache, image, log, opts.timeout)
        write_json(directory / "command.json", result)
        budget = MAX_TOTAL - log.stat().st_size - (directory / "command.json").stat().st_size
        issues = collect(snapshot, directory, modules, budget)
        try:
            facts.append(attempt_observations(directory, modules))
        except ValueError as error:
            issues.append(str(error))
            facts.append({})
        record = {"attempt": index, "commit": commit, "imageId": image, "outcome": result, "modules": modules,
                  "files": {name: hashlib.sha256((directory / name).read_bytes()).hexdigest()
                            for name in ("command.json", "maven.log")}, "collectionIssues": issues}
        write_json(directory / "manifest.json", record)
        records.append({"manifest": f"attempt-{index}/manifest.json", "exitCode": result["exitCode"],
                        "timedOut": result["timedOut"], "collectionIssues": len(issues)})
    changed = []
    for identity in sorted(set.intersection(*(set(fact) for fact in facts)), key=str):
        outcomes = [fact[identity] for fact in facts]
        if all(len(values) == 1 for values in outcomes) and len({values[0] for values in outcomes}) > 1:
            changed.append({"module": identity[0], "suite": identity[1], "className": identity[2], "name": identity[3],
                            "observedStatuses": [values[0] for values in outcomes]})
    write_json(output / "attempts.json", {"commit": commit, "imageId": image, "attempts": records,
                                         "matchedTestsWithDifferentObservations": changed})
    return int(any(record["exitCode"] != 0 or record["collectionIssues"] for record in records))


if __name__ == "__main__":
    raise SystemExit(main())
