#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env.prod}"
confirmation="${1:-}"
requested_tag="${2:-$(git -C "$ROOT_DIR" rev-parse HEAD)}"
previous_env=""
deployment_started=false

if [[ "$confirmation" != "--confirm-public-beta-infrastructure" ]]; then
  echo "Refusing to activate Public Beta infrastructure without explicit confirmation" >&2
  exit 1
fi
if ! [[ "$requested_tag" =~ ^[A-Za-z0-9._-]{1,128}$ ]]; then
  echo "Invalid image tag: $requested_tag" >&2
  exit 1
fi
if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing production environment file: $ENV_FILE" >&2
  exit 1
fi

env_value() {
  local name="$1"
  sed -n "s/^${name}=//p" "$ENV_FILE" | tail -n 1
}

registration_switch() {
  local postgres_user
  local postgres_database
  postgres_user="$(env_value POSTGRES_USER)"
  postgres_database="$(env_value POSTGRES_DB)"
  [[ -n "$postgres_user" && -n "$postgres_database" ]] || {
    echo "POSTGRES_USER and POSTGRES_DB must be configured" >&2
    return 1
  }
  docker compose --env-file "$ENV_FILE" -f "$ROOT_DIR/infra/compose.prod.yml" \
    exec -T postgres psql --no-psqlrc --username "$postgres_user" \
    --dbname "$postgres_database" --tuples-only --no-align \
    --command 'select registration_enabled from public_beta_control where singleton_id = 1' \
    | tr -d '[:space:]'
}

rollback() {
  local exit_code=$?
  trap - EXIT
  if [[ -n "$previous_env" && -f "$previous_env" ]]; then
    echo "Activation failed; restoring the previous production environment" >&2
    mv -- "$previous_env" "$ENV_FILE"
    chmod 600 "$ENV_FILE"
    if [[ "$deployment_started" == "true" ]]; then
      bash "$ROOT_DIR/scripts/deploy-prod.sh" "$(env_value IMAGE_TAG)" || true
    fi
  fi
  exit "$exit_code"
}
trap rollback EXIT

switch_before="$(registration_switch)"
if [[ "$switch_before" != "f" ]]; then
  echo "Database registration switch must be off before infrastructure activation" >&2
  exit 1
fi

temporary="$(mktemp "${ENV_FILE}.activation.XXXXXX")"
previous_env="$(mktemp "${ENV_FILE}.rollback.XXXXXX")"
cp -- "$ENV_FILE" "$previous_env"

declare -A replacements=(
  [PUBLIC_BETA_ENABLED]="true"
  [IDENTITY_MAIL_ENABLED]="true"
  [TENCENT_SES_ENABLED]="true"
  [TURNSTILE_ENABLED]="true"
)
declare -A written=()
ordered_names=(
  PUBLIC_BETA_ENABLED IDENTITY_MAIL_ENABLED TENCENT_SES_ENABLED TURNSTILE_ENABLED
)

while IFS= read -r line || [[ -n "$line" ]]; do
  name="${line%%=*}"
  if [[ "$line" == *=* && -v "replacements[$name]" ]]; then
    if [[ ! -v "written[$name]" ]]; then
      printf '%s=%s\n' "$name" "${replacements[$name]}" >> "$temporary"
      written[$name]=true
    fi
  else
    printf '%s\n' "$line" >> "$temporary"
  fi
done < "$ENV_FILE"

for name in "${ordered_names[@]}"; do
  if [[ ! -v "written[$name]" ]]; then
    printf '%s=%s\n' "$name" "${replacements[$name]}" >> "$temporary"
  fi
done

chmod 600 "$temporary"
bash "$ROOT_DIR/scripts/preflight-prod.sh" "$temporary" >/dev/null
bash "$ROOT_DIR/scripts/preflight-public-beta.sh" "$temporary" >/dev/null
mv -- "$temporary" "$ENV_FILE"
chmod 600 "$ENV_FILE"

deployment_started=true
bash "$ROOT_DIR/scripts/deploy-prod.sh" "$requested_tag"

switch_after="$(registration_switch)"
if [[ "$switch_after" != "f" ]]; then
  echo "Database registration switch changed during activation" >&2
  exit 1
fi

app_address="$(env_value APP_ADDRESS)"
status="$(curl --fail --silent --show-error --max-time 20 \
  "https://${app_address}/api/v1/public/identity/registration/status")"
if [[ ! "$status" =~ \"registrationEnabled\"[[:space:]]*:[[:space:]]*false ]] \
  || [[ ! "$status" =~ \"reason\"[[:space:]]*:[[:space:]]*\"REGISTRATION_SWITCH_OFF\" ]] \
  || [[ "$status" =~ \"turnstileSiteKey\"[[:space:]]*:[[:space:]]*\"\" ]]; then
  echo "Public Beta readiness check failed while registration remained closed" >&2
  exit 1
fi

rm -f -- "$previous_env"
previous_env=""
trap - EXIT
echo "Public Beta infrastructure is ready; database registration switch remains off"
