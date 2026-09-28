#!/usr/bin/env python3
"""Disable first-owner bootstrap after the owner has changed their password."""

import os
from pathlib import Path
import re
import tempfile


if os.geteuid() == 0:
    raise SystemExit("Run this helper as the ordinary VPS user, not root.")

env_path = Path(__file__).resolve().parent.parent / ".env.tiny"
body = env_path.read_text(encoding="utf-8")
for name, value in (
    ("AUTH_BOOTSTRAP_ENABLED", "false"),
    ("AUTH_BOOTSTRAP_PASSWORD", ""),
):
    pattern = re.compile(rf"^{name}=.*$", re.MULTILINE)
    if not pattern.search(body):
        raise SystemExit(f"Missing {name}; no configuration was changed.")
    body = pattern.sub(lambda _: f"{name}={value}", body)

fd, temp_name = tempfile.mkstemp(prefix=".env.tiny.", dir=env_path.parent, text=True)
try:
    os.fchmod(fd, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as output:
        output.write(body)
    os.replace(temp_name, env_path)
except BaseException:
    Path(temp_name).unlink(missing_ok=True)
    raise

print("Owner bootstrap disabled and temporary password removed from .env.tiny.")
