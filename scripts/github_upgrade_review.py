#!/usr/bin/env python3
"""Prepare review evidence for one exact Git commit and optionally open an authorized GitHub draft."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode
from urllib.request import HTTPRedirectHandler, Request, build_opener

REPO = re.compile(r"[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+")
SHA = re.compile(r"[0-9a-f]{40}")
API = "https://api.github.com"


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        return None


class GitHub:
    def __init__(self, repository: str, token: str | None):
        if not REPO.fullmatch(repository):
            raise ValueError("GitHub 仓库应为 owner/name")
        self.repository = repository
        self.token = token

    def request(self, path: str, body=None):
        headers = {"Accept": "application/vnd.github+json", "User-Agent": "InsightOps-upgrade-review",
                   "X-GitHub-Api-Version": "2026-03-10"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        data = None
        if body is not None:
            if not self.token:
                raise ValueError("创建草稿 PR 需要已授权的 GitHub 凭据")
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = Request(f"{API}/repos/{self.repository}{path}", data=data, headers=headers)
        with build_opener(NoRedirect()).open(request, timeout=15) as response:
            content = response.read(4 * 1024 * 1024 + 1)
        if len(content) > 4 * 1024 * 1024:
            raise ValueError("GitHub 响应超过核对范围")
        return json.loads(content)


def git(repo: Path, *args: str) -> bytes:
    return subprocess.run(["git", "--no-pager", "-C", str(repo), *args], check=True,
                          capture_output=True, timeout=35).stdout


def pinned(repo: Path, ref: str) -> str:
    commit = git(repo, "rev-parse", "--verify", "--end-of-options", ref + "^{commit}").decode("ascii").strip()
    if not SHA.fullmatch(commit):
        raise ValueError("需要 40 位 GitHub 提交 SHA")
    return commit


def read_ci(github: GitHub, candidate: str) -> dict:
    records = []
    for page in range(1, 11):
        response = github.request("/actions/runs?" + urlencode({"head_sha": candidate, "per_page": 100, "page": page}))
        for run in response["workflow_runs"]:
            if run.get("head_sha") != candidate:
                continue
            records.append({field: run.get(field) for field in
                            ("id", "name", "head_sha", "event", "status", "conclusion", "run_attempt", "updated_at")})
        if page * 100 >= response["total_count"] or not response["workflow_runs"]:
            return {"status": "OBSERVED", "commit": candidate, "runs": records, "truncated": False}
    return {"status": "OBSERVED", "commit": candidate, "runs": records, "truncated": True}


def testing_evidence(path: Path, candidate: str) -> dict:
    data = path.read_bytes()
    record = json.loads(data)
    if record.get("commit") != candidate:
        raise ValueError("Maven 实跑清单不属于当前候选提交")
    entries = record.get("attempts")
    if not isinstance(entries, list) or not 1 <= len(entries) <= 3:
        raise ValueError("Maven 实跑清单的尝试条目无效")
    manifests = []
    for index, attempt in enumerate(entries, start=1):
        expected = f"attempt-{index}/manifest.json"
        if attempt.get("manifest") != expected:
            raise ValueError("Maven 尝试的清单路径与执行序号不符")
        root = path.parent.resolve()
        manifest = (root / expected).resolve(strict=True)
        if not manifest.is_relative_to(root):
            raise ValueError("Maven 清单路径超出交付目录")
        wire = manifest.read_bytes()
        run = json.loads(wire)
        if run.get("commit") != candidate or run.get("attempt") != index:
            raise ValueError("Maven 分轮证据与候选或尝试序号不符")
        manifests.append({"path": expected, "sha256": hashlib.sha256(wire).hexdigest(),
                          "exitCode": run.get("outcome", {}).get("exitCode"), "issues": run.get("collectionIssues", [])})
    return {"sha256": hashlib.sha256(data).hexdigest(), "attempts": manifests}


def open_draft(github: GitHub, payload: dict, base: str, candidate: str) -> dict:
    if not github.token:
        raise ValueError("创建草稿 PR 需要已授权的 GitHub 凭据")
    for branch, commit in ((payload["base"], base), (payload["head"], candidate)):
        ref = github.request("/git/ref/heads/" + quote(branch, safe="/"))
        if ref.get("object", {}).get("sha") != commit:
            raise ValueError("远端分支与交接中的提交已不匹配")

    owner = github.repository.split("/", 1)[0]
    query = "/pulls?" + urlencode({"state": "open", "base": payload["base"], "head": owner + ":" + payload["head"]})

    def existing():
        pulls = github.request(query)
        if len(pulls) > 1:
            raise ValueError("同一分支组合有多个开放 PR，需核对实际目标")
        return pulls[0] if pulls else None

    pr = existing()
    reused = pr is not None
    if pr is None:
        try:
            pr = github.request("/pulls", payload)
        except HTTPError as error:
            if error.code != 422:
                raise
            pr = existing()
            if pr is None:
                raise
            reused = True
    if pr.get("head", {}).get("sha") != candidate or pr.get("base", {}).get("sha") != base:
        raise ValueError("GitHub 返回的 PR 提交与交接不匹配，检查远端实际结果")
    return {"number": pr["number"], "draft": pr["draft"], "url": pr["html_url"], "reused": reused}


def write_json(target: Path, data):
    target.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--base-ref", required=True)
    parser.add_argument("--candidate", required=True)
    parser.add_argument("--base-branch", required=True)
    parser.add_argument("--head-branch", required=True)
    parser.add_argument("--attempts", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--local-only", action="store_true")
    parser.add_argument("--open-draft", action="store_true")
    options = parser.parse_args()
    github = GitHub(options.repository, os.getenv("INSIGHTOPS_GITHUB_TOKEN") or os.getenv("GITHUB_TOKEN"))
    origin = git(options.repo, "remote", "get-url", "origin").decode("utf-8").strip().removesuffix(".git")
    if origin.lower() not in {f"https://github.com/{github.repository.lower()}", f"git@github.com:{github.repository.lower()}",
                             f"ssh://git@github.com/{github.repository.lower()}"}:
        parser.error("指定仓库与本地 origin 不一致")
    for branch in (options.base_branch, options.head_branch):
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.\/-]{0,150}", branch):
            parser.error("无效的分支名")
        git(options.repo, "check-ref-format", "refs/heads/" + branch)
    baseline, candidate = pinned(options.repo, options.base_ref), pinned(options.repo, options.candidate)
    if baseline == candidate or options.base_branch == options.head_branch:
        parser.error("基线与候选需要不同的提交和分支")
    if options.local_only and options.open_draft:
        parser.error("本地生成模式不能创建 GitHub PR")
    proof = testing_evidence(options.attempts.resolve(strict=True), candidate) if options.attempts else None
    merge_bases = git(options.repo, "merge-base", "--all", baseline, candidate).decode("ascii").splitlines()
    if len(merge_bases) != 1 or not SHA.fullmatch(merge_bases[0]):
        parser.error("无法唯一确定 PR 比较使用的共同祖先")
    comparison_base = merge_bases[0]
    patch = git(options.repo, "diff", "--no-ext-diff", "--no-textconv", "--binary", "--no-color", comparison_base, candidate)
    if not patch or len(patch) > 24 * 1024 * 1024:
        parser.error("补丁为空或超过交接大小")
    changed = git(options.repo, "-c", "core.quotePath=false", "diff", "--no-ext-diff", "--no-color", "--name-only", "-z", comparison_base, candidate)
    paths = [path.decode("utf-8") for path in changed.split(b"\0") if path]
    sha = hashlib.sha256(patch).hexdigest()
    ci = {"status": "NOT_REQUESTED", "commit": candidate, "runs": []}
    if not options.local_only:
        try:
            ci = read_ci(github, candidate)
        except (HTTPError, URLError, TimeoutError, ValueError) as error:
            ci = {"status": "UNAVAILABLE", "commit": candidate, "runs": [],
                  "reason": f"HTTP {error.code}" if isinstance(error, HTTPError) else type(error).__name__}
    now = datetime.now(timezone.utc).isoformat()
    facts = {"repository": github.repository, "baseCommit": baseline, "candidateCommit": candidate,
             "comparisonBase": comparison_base, "patchSha256": sha, "affectedPaths": paths, "collectedAt": now, "mavenEvidence": proof}
    body = (
        "## 升级候选审阅\n\n"
        f"- 基线分支提交 {baseline}\n- 比较共同祖先 {comparison_base}\n- 候选提交 {candidate}\n- 变更文件 {len(paths)} 个\n"
        f"- 本地差异 SHA-256 {sha}\n- CI 证据 {ci['status']}，获取到 {len(ci['runs'])} 条匹配记录\n"
        + "".join(f"- [CI 记录 {run['id']}](https://github.com/{github.repository}/actions/runs/{run['id']})"
                  f" · {run['conclusion'] or run['status']}\n" for run in ci["runs"] if isinstance(run["id"], int))
        + (f"- Maven 实跑清单 {proof['sha256']}，{len(proof['attempts'])} 次尝试\n" if proof else "")
        + "\n维护者核对影响范围、测试与回退条件后记录决定。\n"
    )
    payload = {"title": f"升级候选 {candidate[:12]}", "body": body,
               "base": options.base_branch, "head": options.head_branch, "draft": True}
    path = options.output.resolve()
    path.mkdir(parents=True, exist_ok=False)
    (path / "candidate.patch").write_bytes(patch)
    write_json(path / "review.json", facts)
    write_json(path / "ci.json", ci)
    write_json(path / "draft-pr.json", payload)
    (path / "review.md").write_text(body, encoding="utf-8")
    if options.open_draft:
        write_json(path / "draft-pr-receipt.json", open_draft(github, payload, baseline, candidate))
    print(candidate)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
