#!/usr/bin/env bash
# Smoke test of a deployed system, from outside.
#   BASE_URL=https://<fqdn> ADMIN_PASSWORD=... ./deploy/verify.sh
set -euo pipefail
: "${BASE_URL:?}" "${ADMIN_PASSWORD:?}"
ADMIN_USER=${ADMIN_USER:-admin}
BASE_URL=${BASE_URL%/}
WAIT_SECONDS=${WAIT_SECONDS:-420}   # first start: images, 3-member replica sets, certificate
MIN_NODES=${MIN_NODES:-1}

esc() { local s=${1//\\/\\\\}; printf '%s' "${s//\"/\\\"}"; }
# credentials go to curl through stdin, so they do not show up in the process list
authed() { printf 'user = "%s:%s"\n' "$ADMIN_USER" "$(esc "$ADMIN_PASSWORD")" | curl -sS --max-time 15 -K - "$@"; }
code() { curl -s -o /dev/null --max-time 15 -w '%{http_code}' "$@" || true; }

expect() {   # name, expected status, command printing a status
  local name=$1 want=$2 got end=$((SECONDS + WAIT_SECONDS)); shift 2
  while :; do
    got=$("$@" 2>/dev/null || true)
    if [ "$got" = "$want" ]; then echo "  PASS $name"; return; fi
    if [ "$SECONDS" -ge "$end" ]; then echo "  FAIL $name (expected $want, got ${got:-nothing})"; exit 1; fi
    sleep 5
  done
}
status() { authed -o /dev/null -w '%{http_code}' "$BASE_URL$1"; }
enough_nodes() {
  local up
  up=$(authed "$BASE_URL/api/registry/instances" | grep -o '"status":"UP"' | wc -l)
  if [ "$up" -ge "$MIN_NODES" ]; then echo yes; else echo "only $((up))"; fi
}

echo "checking $BASE_URL"
expect "health endpoint"                      200 code "$BASE_URL/healthz"
expect "UI refuses anonymous visitors"        401 code "$BASE_URL/"
expect "API refuses anonymous requests"       401 code "$BASE_URL/api/employees"
expect "wrong password refused"               401 code -u "$ADMIN_USER:wrong-password" "$BASE_URL/api/employees"
expect "UI with password"                     200 status /
expect "at least $MIN_NODES warehouse node(s) UP"      yes enough_nodes
expect "data readable through the gateway"    200 status '/api/employees?limit=1'
echo "all checks passed"
