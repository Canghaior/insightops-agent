#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env.prod}"
COMPOSE_FILE="$ROOT_DIR/infra/compose.prod.yml"
confirmation="${1:-}"

if [[ "$confirmation" != "--from-stdin" ]]; then
  echo "Production GitHub token must be supplied through standard input" >&2
  exit 1
fi
if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing production environment file: $ENV_FILE" >&2
  exit 1
fi

IFS= read -r github_token || {
  echo "Production GitHub token is missing" >&2
  exit 1
}
if IFS= read -r unexpected_line; then
  echo "Production GitHub token input must contain exactly one line" >&2
  exit 1
fi
if (( ${#github_token} < 20 || ${#github_token} > 255 )) \
    || [[ ! "$github_token" =~ ^[A-Za-z0-9_]+$ ]]; then
  echo "Production GitHub token format is invalid" >&2
  exit 1
fi

temporary="$(mktemp "${ENV_FILE}.github-token.XXXXXX")"
cleanup() {
  if [[ "$temporary" == "${ENV_FILE}.github-token."* && -f "$temporary" ]]; then
    rm -f -- "$temporary"
  fi
}
trap cleanup EXIT
token_written=false
while IFS= read -r line || [[ -n "$line" ]]; do
  if [[ "$line" =~ ^[[:space:]]*GITHUB_TOKEN= ]]; then
    if [[ "$token_written" == "false" ]]; then
      printf 'GITHUB_TOKEN=%s\n' "$github_token" >> "$temporary"
      token_written=true
    fi
  else
    printf '%s\n' "$line" >> "$temporary"
  fi
done < "$ENV_FILE"
if [[ "$token_written" == "false" ]]; then
  printf 'GITHUB_TOKEN=%s\n' "$github_token" >> "$temporary"
fi
chmod 600 "$temporary"
mv -- "$temporary" "$ENV_FILE"
trap - EXIT
github_token=""

bash "$ROOT_DIR/scripts/preflight-prod.sh" "$ENV_FILE" >/dev/null
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" \
  up -d --force-recreate worker >/dev/null
container_id="$(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" ps -q worker)"
[[ -n "$container_id" ]] || { echo "Production Worker was not recreated" >&2; exit 1; }
worker_ready=false
for _ in $(seq 1 60); do
  status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
    "$container_id" 2>/dev/null || true)"
  if [[ "$status" == "healthy" ]]; then
    worker_ready=true
    break
  fi
  [[ "$status" != "unhealthy" && "$status" != "exited" ]] || break
  sleep 2
done
[[ "$worker_ready" == "true" ]] || {
  echo "Production Worker did not become healthy after GitHub token update" >&2
  exit 1
}

echo "Production GitHub collection credential is configured and Worker is healthy"
