"""Package an explicitly scoped, single-module Maven snapshot for InsightOps v1. No project code is executed."""
import argparse
import hashlib
import io
import json
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

from maven_case_extract import MAX_BYTES, allowlist, extract, parse_pom

FORMAT = "insightops.maven-baseline/v1"
MAX_FILES = 32
MAX_TOTAL = 6 * MAX_BYTES
RECORDS = {"dependency_tree": "dependency-tree.txt", "maven_version": "maven-version.txt",
           "application_log": "application.log"}


def read_file(path):
    if path.resolve(strict=True) != path.absolute() or not path.is_file():
        raise ValueError(f"expected a regular file at the specified path: {path.name}")
    with path.open("rb") as stream:
        data = stream.read(MAX_BYTES + 1)
    if not 0 < len(data) <= MAX_BYTES:
        raise ValueError(f"empty or oversized file: {path.name}")
    return data


def pack(project, scope_file, output, legacy_manifest=None, **records):
    project = project.resolve(strict=True)
    output = output.absolute()
    if output.resolve().is_relative_to(project) or output.exists():
        raise ValueError("choose a new output file outside the scoped project")
    scope = json.loads(read_file(scope_file).decode("utf-8-sig"))
    if scope["poms"] != ["pom.xml"]:
        raise ValueError("v1 supports one Maven module with a root pom.xml")
    allowlist(scope)
    extracted = extract(project, scope)
    root = parse_pom(read_file(project / "pom.xml"))
    if root.all("modules") or any(profile.all("modules") for profiles in root.all("profiles") for profile in profiles.all("profile")):
        raise ValueError("aggregated Maven modules are not supported")
    files = {}
    for file in extracted["files"]:
        if file["status"] != "read":
            raise ValueError(f"scoped original unavailable: {file['path']} ({file['status']})")
        data = read_file(project / file["path"])
        if hashlib.sha256(data).hexdigest() != file["sha256"]:
            raise ValueError(f"source changed during extraction: {file['path']}")
        files["sources/" + file["path"]] = data
    if legacy_manifest is not None:
        manifest = read_file(legacy_manifest)
        if json.loads(manifest.decode("utf-8-sig")) != extracted:
            raise ValueError("legacy manifest differs from these original files or extraction scope")
    else:
        manifest = (json.dumps(extracted, ensure_ascii=False, indent=2) + "\n").encode()
    files["evidence/extractor.json"] = manifest
    for key, name in RECORDS.items():
        if records.get(key) is not None:
            files["evidence/" + name] = read_file(records[key])
    descriptor = {"format": FORMAT, "label": scope["baseline"],
                  "focus": list(dict.fromkeys([scope["dependency"], *scope.get("relatedDependencies", [])])),
                  "files": [{"path": path, "sha256": hashlib.sha256(data).hexdigest()} for path, data in sorted(files.items())]}
    files["baseline.json"] = (json.dumps(descriptor, ensure_ascii=False, indent=2) + "\n").encode()
    if len(files) > MAX_FILES or sum(map(len, files.values())) > MAX_TOTAL or any(len(b) > MAX_BYTES for b in files.values()):
        raise ValueError("package exceeds the v1 size or entry limit")
    buffer = io.BytesIO()
    with ZipFile(buffer, "w") as archive:
        for path, data in sorted(files.items()):
            info = ZipInfo(path)
            info.compress_type = ZIP_DEFLATED
            archive.writestr(info, data)
    with output.open("xb") as stream:
        stream.write(buffer.getvalue())
    return descriptor


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    for key in ("project", "scope", "output", "legacy_manifest", *RECORDS.keys()):
        cli.add_argument("--" + key.replace("_", "-"), type=Path, required=key in ("project", "scope", "output"))
    args = vars(cli.parse_args())
    args["scope_file"] = args.pop("scope")
    pack(**args)

