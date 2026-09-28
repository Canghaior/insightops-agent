#!/usr/bin/env python3
"""Validate a DeepSeek key, then update the private tiny-VPS env file."""

import json
import os
from pathlib import Path
import re
import sys
import tempfile
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


root = Path(__file__).resolve().parent.parent
env_path = root / ".env.tiny"
key = sys.stdin.read().strip()

if os.geteuid() == 0:
    raise SystemExit("Run this helper as the ordinary VPS user, not root.")
if not re.fullmatch(r"[A-Za-z0-9_-]{16,}", key):
    raise SystemExit("Key format was not accepted; no configuration was changed.")
if not env_path.is_file():
    raise SystemExit("Missing .env.tiny; no configuration was changed.")

request = Request(
    "https://api.deepseek.com/models",
    headers={"Authorization": f"Bearer {key}"},
)
try:
    with urlopen(request, timeout=15) as response:
        models = json.load(response).get("data", [])
except HTTPError as error:
    raise SystemExit(f"DeepSeek rejected the key (HTTP {error.code}); no configuration was changed.") from None
except (URLError, TimeoutError, ValueError) as error:
    raise SystemExit(f"Unable to verify DeepSeek access ({type(error).__name__}); no configuration was changed.") from None

if not any(model.get("id") == "deepseek-flash" for model in models):
    raise SystemExit("deepseek-flash was not listed for this key; no configuration was changed.")

changes = {
    "DEEPSEEK_API_KEY": key,
    "DEEPSEEK_ENABLED": "true",
    "SPRING_AI_MODEL_CHAT": "openai",
    "DEEPSEEK_CHAT_MODEL": "deepseek-flash",
    # Conservative peak prices in USD per million tokens, as of 2026-09-10.
    "DEEPSEEK_PRICING_EFFECTIVE_DATE": "2026-09-10",
    "DEEPSEEK_INPUT_CACHE_HIT_USD_PER_MILLION": "0.006",
    "DEEPSEEK_INPUT_CACHE_MISS_USD_PER_MILLION": "0.30",
    "DEEPSEEK_OUTPUT_USD_PER_MILLION": "1.20",
}

body = env_path.read_text(encoding="utf-8")
for name, value in changes.items():
    pattern = re.compile(rf"^{re.escape(name)}=.*$", re.MULTILINE)
    replacement = f"{name}={value}"
    if pattern.search(body):
        body = pattern.sub(lambda _: replacement, body)
    else:
        body += f"\n{replacement}\n"

fd, temp_name = tempfile.mkstemp(prefix=".env.tiny.", dir=root, text=True)
try:
    os.fchmod(fd, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as output:
        output.write(body)
    os.replace(temp_name, env_path)
except BaseException:
    Path(temp_name).unlink(missing_ok=True)
    raise

print("DeepSeek key validated and private configuration updated. The key was not printed.")
