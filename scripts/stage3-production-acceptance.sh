#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SECRET_DIR="$ROOT_DIR/.secrets"
action="${1:-}"
marker="${2:-}"
confirmation="${4:-}"

if [[ "$confirmation" != "--confirm-stage3-production-reliability" ]]; then
  echo "Explicit Stage 3 production reliability confirmation is required" >&2
  exit 1
fi
if ! [[ "$marker" =~ ^STAGE3-[0-9]+-[0-9]+$ ]]; then
  echo "Stage 3 marker is invalid" >&2
  exit 1
fi

send_alertmanager_canary() {
  local webhook_url topic_url started_at ends_at resolved_at start_epoch delivered response
  [[ -s "$SECRET_DIR/alertmanager-webhook-url" ]] || {
    echo "Alertmanager webhook secret is missing" >&2
    exit 1
  }
  webhook_url="$(tr -d '\r\n' < "$SECRET_DIR/alertmanager-webhook-url")"
  if [[ ! "$webhook_url" =~ ^https://ntfy\.sh/insightops-[a-f0-9]{48}\?template=alertmanager\&firebase=no$ ]]; then
    echo "Alertmanager webhook destination is not the approved private ntfy form" >&2
    exit 1
  fi
  topic_url="${webhook_url%%\?*}"
  start_epoch="$(date -u +%s)"
  started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  ends_at="$(date -u -d '+10 minutes' +%Y-%m-%dT%H:%M:%SZ)"

  curl --fail --silent --show-error \
    --request POST \
    --header 'Content-Type: application/json' \
    --data-binary @- \
    http://127.0.0.1:9093/api/v2/alerts >/dev/null <<EOF
[{"labels":{"alertname":"InsightOpsStage3Canary","severity":"info","source":"production-acceptance","marker":"$marker"},"annotations":{"summary":"InsightOps reliability canary $marker","description":"Synthetic operational canary without business or user content"},"startsAt":"$started_at","endsAt":"$ends_at"}]
EOF

  delivered=false
  for _ in $(seq 1 30); do
    response="$(curl --fail --silent --show-error \
      "${topic_url}/json?poll=1&since=${start_epoch}" || true)"
    if grep -Fq "$marker" <<< "$response"; then
      delivered=true
      break
    fi
    sleep 2
  done
  [[ "$delivered" == "true" ]] || {
    echo "Alertmanager Canary did not arrive at the approved ntfy topic" >&2
    exit 1
  }

  resolved_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  curl --fail --silent --show-error \
    --request POST \
    --header 'Content-Type: application/json' \
    --data-binary @- \
    http://127.0.0.1:9093/api/v2/alerts >/dev/null <<EOF
[{"labels":{"alertname":"InsightOpsStage3Canary","severity":"info","source":"production-acceptance","marker":"$marker"},"annotations":{"summary":"InsightOps reliability canary $marker","description":"Synthetic operational canary without business or user content"},"startsAt":"$started_at","endsAt":"$resolved_at"}]
EOF
  echo "ALERT_CANARY=PASS marker=$marker"
}

case "$action" in
  prepare)
    if [[ "${3:-}" != "--create-encrypted-offsite-package" ]]; then
      echo "Encrypted offsite package confirmation is required" >&2
      exit 1
    fi
    bash "$ROOT_DIR/scripts/configure-prod-reliability.sh"
    bash "$ROOT_DIR/scripts/production-stability-report.sh" 72 95
    send_alertmanager_canary
    backup_output="$(bash "$ROOT_DIR/scripts/create-offsite-backup.sh" "$marker")"
    package_path="$(sed -n 's/^OFFSITE_PACKAGE=//p' <<< "$backup_output" | tail -n 1)"
    package_name="$(basename "$package_path")"
    if ! [[ "$package_name" =~ ^insightops-offsite-[0-9]{8}T[0-9]{6}Z-STAGE3-[0-9]+-[0-9]+\.tar\.gz\.enc$ ]]; then
      echo "Encrypted offsite package name is invalid" >&2
      exit 1
    fi
    package_root="$(realpath "$ROOT_DIR/backups/offsite")"
    resolved_package="$(realpath "$package_path")"
    [[ "$resolved_package" == "$package_root/$package_name" ]] || {
      echo "Encrypted offsite package escaped the approved directory" >&2
      exit 1
    }
    base="${resolved_package%.tar.gz.enc}"
    [[ -s "$resolved_package" && -s "$base.sha256" && -s "$base.metadata" ]] || {
      echo "Encrypted offsite package triplet is incomplete" >&2
      exit 1
    }
    echo "OFFSITE_PACKAGE_BASENAME=$package_name"
    ;;
  restore)
    package_path="${3:-}"
    if [[ -z "$package_path" ]]; then
      echo "Round-tripped recovery package path is required" >&2
      exit 1
    fi
    bash "$ROOT_DIR/scripts/restore-offsite-drill.sh" \
      "$package_path" --confirm-isolated-recovery "$marker"
    echo "STAGE3_PRODUCTION_RELIABILITY=PASS marker=$marker"
    ;;
  *)
    echo "Usage: $0 prepare <marker> --create-encrypted-offsite-package --confirm-stage3-production-reliability" >&2
    echo "   or: $0 restore <marker> <recovery-package> --confirm-stage3-production-reliability" >&2
    exit 1
    ;;
esac
