#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env.prod}"
COMPOSE_FILE="$ROOT_DIR/infra/compose.prod.yml"
STATE_DIR="$ROOT_DIR/.deploy"
requested_tag="${1:-latest}"

if ! [[ "$requested_tag" =~ ^[A-Za-z0-9._-]{1,128}$ ]]; then
  echo "Invalid image tag: $requested_tag" >&2
  exit 1
fi

bash "$ROOT_DIR/scripts/preflight-prod.sh" "$ENV_FILE"
mkdir -p "$STATE_DIR"
previous_tag=""
if [[ -f "$STATE_DIR/last-successful-tag" ]]; then
  previous_tag="$(<"$STATE_DIR/last-successful-tag")"
fi

wait_for_health() {
  local service="$1"
  local container_id
  local status
  container_id="$(IMAGE_TAG="$requested_tag" docker compose --env-file "$ENV_FILE" \
    -f "$COMPOSE_FILE" ps -q "$service")"
  if [[ -z "$container_id" ]]; then
    return 1
  fi
  for _ in $(seq 1 60); do
    status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container_id" 2>/dev/null || true)"
    [[ "$status" == "healthy" ]] && return 0
    [[ "$status" == "unhealthy" || "$status" == "exited" ]] && return 1
    sleep 5
  done
  return 1
}

export IMAGE_TAG="$requested_tag"
if docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" ps --status running -q postgres | grep -q .; then
  bash "$ROOT_DIR/scripts/backup-prod.sh"
fi
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" pull server worker web
deployment_ok=true
if ! docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d --remove-orphans; then
  echo "Compose startup failed" >&2
  deployment_ok=false
fi

if [[ "$deployment_ok" == "true" ]]; then
  for service in postgres ollama server worker web caddy; do
    if ! wait_for_health "$service"; then
      echo "Health check failed for $service" >&2
      deployment_ok=false
      break
    fi
  done
fi

if [[ "$deployment_ok" != "true" ]]; then
  echo "Deployment diagnostics for $requested_tag" >&2
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" ps >&2 || true
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" \
    logs --tail 200 --no-color server worker web >&2 || true
  if [[ -n "$previous_tag" && "$previous_tag" != "$requested_tag" ]]; then
    echo "Rolling back from $requested_tag to $previous_tag" >&2
    export IMAGE_TAG="$previous_tag"
    docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d --remove-orphans
  fi
  exit 1
fi

printf '%s\n' "$requested_tag" > "$STATE_DIR/last-successful-tag"
echo "Deployment $requested_tag is healthy"
