#!/usr/bin/env python3
"""Run an explicit list of offline InsightOps Maven reviews and retain per-project evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

from maven_reactor_evidence import DEFAULT_IMAGE, git, now, write_json

KEY = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{0,39}")
COMMIT = re.compile(r"(?:[0-9a-f]{40}|[0-9a-f]{64})")


def freeze(plan: Path) -> list[dict]:
    if plan.stat().st_size > 1024 * 1024:
        raise ValueError("批次计划超过 1 MiB")
    content = json.loads(plan.read_text(encoding="utf-8"))
    projects = content.get("projects") if isinstance(content, dict) else None
    if not isinstance(projects, list) or not 1 <= len(projects) <= 32:
        raise ValueError("批次需要 1 至 32 个项目")
    result = []
    ids = set()
    for source in projects:
        if not isinstance(source, dict):
            raise ValueError("项目需要结构化声明")
        key = source.get("id", "")
        revision = source.get("revision", "")
        repo = source.get("repo")
        approved = source.get("approved", False)
        attempts = source.get("attempts", 1)
        if not isinstance(key, str) or not KEY.fullmatch(key) or key.lower() in ids:
            raise ValueError("项目标识需要唯一安全的名称")
        if not isinstance(revision, str) or not COMMIT.fullmatch(revision):
            raise ValueError(f"{key} 缺少完整提交 SHA")
        if not isinstance(repo, str) or not repo.strip() or type(approved) is not bool:
            raise ValueError(f"{key} 需要仓库路径和明确的批准状态")
        if type(attempts) is not int or attempts not in (1, 2, 3):
            raise ValueError(f"{key} 的执行次数应为 1 至 3")
        ids.add(key.lower())
        root = (plan.parent / Path(repo)).resolve()
        entry = {"id": key, "repo": str(root), "revision": revision, "approved": approved,
                 "attempts": attempts, "status": "READY" if approved else "WAITING_FOR_APPROVAL"}
        try:
            resolved = git(root, "rev-parse", "--verify", revision + "^{commit}").decode("ascii").strip()
            if resolved != revision:
                raise ValueError("提交不匹配")
        except (OSError, subprocess.SubprocessError, UnicodeError, ValueError):
            entry["status"] = "BLOCKED_SOURCE"
        result.append(entry)
    return result


def persist(directory: Path, summary: dict) -> None:
    temporary = directory / "batch-status.json.tmp"
    write_json(temporary, summary)
    temporary.replace(directory / "batch-status.json")


def run_project(job: dict, directory: Path, cache: Path, image: str, timeout: int) -> None:
    target = directory / job["id"]
    log_name = job["id"] + ".execution.log"
    job["runnerLog"] = log_name
    command = [sys.executable, str(Path(__file__).with_name("maven_reactor_evidence.py").resolve()),
               "--repo", job["repo"], "--revision", job["revision"], "--offline-repository", str(cache),
               "--image", image, "--output", str(target), "--attempts", str(job["attempts"]),
               "--timeout", str(timeout)]
    try:
        with (directory / log_name).open("wb") as log:
            run = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=job["attempts"] * (timeout + 50) + 60)
    except (OSError, subprocess.SubprocessError) as error:
        job.update(status="RUNNER_INTERRUPTED", issue=type(error).__name__)
        return
    job["runnerExit"] = run.returncode
    manifest = target / "attempts.json"
    if not manifest.is_file():
        job["status"] = "RUNNER_FAILED"
        return
    raw = manifest.read_bytes()
    record = json.loads(raw)
    if record.get("commit") != job["revision"] or len(record.get("attempts", [])) != job["attempts"]:
        job["status"] = "EVIDENCE_MISMATCH"
        return
    runs = record["attempts"]
    job["evidence"] = {"path": manifest.relative_to(directory).as_posix(),
                       "sha256": hashlib.sha256(raw).hexdigest(), "attempts": runs}
    if any(attempt["timedOut"] for attempt in runs):
        job["status"] = "BUILD_TIMED_OUT"
    elif any(attempt["exitCode"] != 0 for attempt in runs):
        job["status"] = "BUILD_FAILED"
    elif run.returncode != 0 or any(attempt["collectionIssues"] for attempt in runs):
        job["status"] = "EVIDENCE_INCOMPLETE"
    else:
        job["status"] = "BUILD_SUCCEEDED"


def main() -> int:
    args = argparse.ArgumentParser(description=__doc__)
    args.add_argument("--plan", type=Path, required=True)
    args.add_argument("--output", type=Path, required=True)
    args.add_argument("--execute", action="store_true")
    args.add_argument("--offline-repository", type=Path)
    args.add_argument("--image", default=DEFAULT_IMAGE)
    args.add_argument("--timeout", type=int, default=180)
    args.add_argument("--stop-on-failure", action="store_true")
    options = args.parse_args()
    if options.execute and (options.offline_repository is None or not options.offline_repository.is_dir()):
        args.error("执行需要已准备的离线 Maven 缓存目录")
    if not 5 <= options.timeout <= 600:
        args.error("--timeout 应在 5 至 600 秒")
    jobs = freeze(options.plan.resolve(strict=True))
    output = options.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    summary = {"startedAt": now(), "executionRequested": options.execute, "projects": jobs}
    persist(output, summary)
    if options.execute:
        for job in jobs:
            if job["status"] != "READY":
                continue
            job["status"] = "RUNNING"
            persist(output, summary)
            run_project(job, output, options.offline_repository, options.image, options.timeout)
            persist(output, summary)
            if options.stop_on_failure and job["status"] != "BUILD_SUCCEEDED":
                break
    summary["endedAt"] = now()
    persist(output, summary)
    expected = {"READY", "WAITING_FOR_APPROVAL", "BUILD_SUCCEEDED"}
    return int(any(job["status"] not in expected for job in jobs))


if __name__ == "__main__":
    raise SystemExit(main())
