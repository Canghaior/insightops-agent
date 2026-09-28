#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
env_file="${1:-$root_dir/.env.tiny}"
# shellcheck source=prod-env.sh
source "$root_dir/scripts/prod-env.sh"

[[ -f "$env_file" ]] || { echo "Missing .env.tiny" >&2; exit 1; }

for key in APP_ADDRESS POSTGRES_PASSWORD DB_PASSWORD; do
  value="$(prod_env_get "$key" "$env_file")"
  if [[ -z "$value" || "$value" == *CHANGE_ME* ]]; then
    echo "$key must be configured in .env.tiny" >&2
    exit 1
  fi
done

database_password="$(prod_env_get POSTGRES_PASSWORD "$env_file")"
application_password="$(prod_env_get DB_PASSWORD "$env_file")"
[[ "$database_password" == "$application_password" ]] || {
  echo "POSTGRES_PASSWORD and DB_PASSWORD must match" >&2
  exit 1
}
(( ${#database_password} >= 16 )) || {
  echo "Database password must contain at least 16 characters" >&2
  exit 1
}

address="$(prod_env_get APP_ADDRESS "$env_file")"
if [[ "$address" == *://* || "$address" == *localhost* || "$address" != *.* ]]; then
  echo "APP_ADDRESS must be the public domain without http:// or https://" >&2
  exit 1
fi
[[ "$(prod_env_get APP_PUBLIC_URL "$env_file")" == "https://$address" ]] || {
  echo "APP_PUBLIC_URL must be https:// followed by APP_ADDRESS" >&2
  exit 1
}
[[ "$(prod_env_get AUTH_SECURE_COOKIE "$env_file")" == true ]] || {
  echo "AUTH_SECURE_COOKIE must be true for public deployment" >&2
  exit 1
}
[[ "$(prod_env_get PUBLIC_BETA_ENABLED "$env_file")" == false ]] || {
  echo "Keep PUBLIC_BETA_ENABLED=false for the initial tiny VPS trial" >&2
  exit 1
}

if [[ "$(prod_env_get AUTH_BOOTSTRAP_ENABLED "$env_file")" == true ]]; then
  owner_password="$(prod_env_get AUTH_BOOTSTRAP_PASSWORD "$env_file")"
  if (( ${#owner_password} < 10 || ${#owner_password} > 72 )) \
      || [[ "$owner_password" == *CHANGE_ME* ]] \
      || [[ ! "$owner_password" =~ [A-Z] ]] \
      || [[ ! "$owner_password" =~ [a-z] ]] \
      || [[ ! "$owner_password" =~ [0-9] ]]; then
    echo "AUTH_BOOTSTRAP_PASSWORD must be 10-72 characters with upper/lower-case letters and digits" >&2
    exit 1
  fi
fi

if [[ "$(prod_env_get DEEPSEEK_ENABLED "$env_file")" == true ]]; then
  [[ -n "$(prod_env_get DEEPSEEK_API_KEY "$env_file")" ]] || {
    echo "DEEPSEEK_API_KEY is required when DEEPSEEK_ENABLED=true" >&2
    exit 1
  }
  [[ "$(prod_env_get SPRING_AI_MODEL_CHAT "$env_file")" == openai ]] || {
    echo "SPRING_AI_MODEL_CHAT must be openai when DEEPSEEK_ENABLED=true" >&2
    exit 1
  }
fi

docker compose --env-file "$env_file" -f "$root_dir/infra/compose.tiny.yml" config --quiet
echo "Tiny VPS preflight passed for $address"
