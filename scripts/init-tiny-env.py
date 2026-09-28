#!/usr/bin/env python3
"""Create a fresh, private .env.tiny without printing generated credentials."""

from pathlib import Path
import os
import secrets


root = Path(__file__).resolve().parent.parent
template = root / ".env.tiny.example"
destination = root / ".env.tiny"

database_password = "Aa1" + secrets.token_urlsafe(32)
owner_password = "Aa1" + secrets.token_urlsafe(24)
body = template.read_text(encoding="utf-8")
body = body.replace("CHANGE_ME_RANDOM_DATABASE_PASSWORD", database_password)
body = body.replace("CHANGE_ME_STRONG_OWNER_PASSWORD", owner_password)

fd = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
try:
    with os.fdopen(fd, "w", encoding="utf-8") as file:
        file.write(body)
except BaseException:
    destination.unlink(missing_ok=True)
    raise

print(f"Created {destination} with mode 600; credentials were not printed.")
