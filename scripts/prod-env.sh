#!/usr/bin/env bash

# Read a single value from a Docker-style env file without executing its content.
# Values may be unquoted or wrapped in matching single/double quotes.
prod_env_get() {
  local name="$1"
  local file="$2"
  local line=""
  local value=""

  line="$(grep -m 1 -E "^[[:space:]]*${name}=" "$file" 2>/dev/null || true)"
  [[ -n "$line" ]] || return 0
  value="${line#*=}"
  value="${value%$'\r'}"

  if [[ ${#value} -ge 2 ]]; then
    if [[ "${value:0:1}" == '"' && "${value: -1}" == '"' ]] || \
       [[ "${value:0:1}" == "'" && "${value: -1}" == "'" ]]; then
      value="${value:1:${#value}-2}"
    fi
  fi

  printf '%s' "$value"
}
