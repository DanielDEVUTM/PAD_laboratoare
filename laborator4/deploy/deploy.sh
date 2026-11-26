#!/usr/bin/env bash
# Installs one release on both VMs over SSH. Used by the CI/CD pipeline and by hand.
#
#   EDGE_HOST=<fqdn or IP> IMAGE_PREFIX=ghcr.io/<owner>/pad-lab4 TAG=<git sha> ADMIN_PASSWORD=... ./deploy/deploy.sh
#
# Optional: ADMIN_USER (admin), SSH_USER (ubuntu), SSH_KEY (private key file),
#           SSH_KNOWN_HOSTS_FILE (strict host key check), DATA_PRIVATE_IP (10.10.2.10),
#           INITIAL_WAREHOUSES (1, used only when no warehouse exists yet), GHCR_USER + GHCR_TOKEN (needed if the images are private)
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)

: "${EDGE_HOST:?set EDGE_HOST (terraform output -raw edge_public_ip)}"
: "${IMAGE_PREFIX:?set IMAGE_PREFIX, e.g. ghcr.io/owner/pad-lab4}"
: "${TAG:?set TAG (the git commit of the images)}"
: "${ADMIN_PASSWORD:?set ADMIN_PASSWORD (password of the web interface and API)}"
ADMIN_USER=${ADMIN_USER:-admin}
SSH_USER=${SSH_USER:-ubuntu}
DATA_PRIVATE_IP=${DATA_PRIVATE_IP:-10.10.2.10}
INITIAL_WAREHOUSES=${INITIAL_WAREHOUSES:-1}

die() { echo "deploy: $*" >&2; exit 1; }
[ "${#ADMIN_PASSWORD}" -ge 12 ] || die "ADMIN_PASSWORD must have at least 12 characters"
[[ $ADMIN_USER =~ ^[A-Za-z0-9_.-]+$ ]] || die "ADMIN_USER may contain only letters, digits, . _ -"
[[ $IMAGE_PREFIX =~ ^[a-z0-9._/-]+$ ]] || die "IMAGE_PREFIX must be lowercase"
[[ $TAG =~ ^[A-Za-z0-9._-]+$ ]] || die "invalid TAG"
[[ $INITIAL_WAREHOUSES =~ ^[0-9]+$ ]] || die "INITIAL_WAREHOUSES must be a number"

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
{
  printf 'Host pad-edge\n  HostName %s\n' "$EDGE_HOST"
  # the data VM accepts SSH only from the private network: jump through the edge VM
  printf 'Host pad-data\n  HostName %s\n  ProxyJump pad-edge\n' "$DATA_PRIVATE_IP"
  printf 'Host pad-*\n  User %s\n  ServerAliveInterval 30\n  ConnectTimeout 20\n' "$SSH_USER"
  if [ -n "${SSH_KEY:-}" ]; then printf '  IdentityFile "%s"\n  IdentitiesOnly yes\n' "$SSH_KEY"; fi
  if [ -n "${SSH_KNOWN_HOSTS_FILE:-}" ]; then
    printf '  UserKnownHostsFile "%s"\n  StrictHostKeyChecking yes\n' "$SSH_KNOWN_HOSTS_FILE"
  else
    printf '  StrictHostKeyChecking accept-new\n'
  fi
} > "$tmp/ssh_config"

remote() { ssh -F "$tmp/ssh_config" "$@"; }
upload() { scp -q -F "$tmp/ssh_config" "$@"; }
compose='cd /opt/pad && docker compose --env-file /etc/pad/infra.env --env-file release.env'

wait_ready() {   # cloud-init installs Docker at the first boot
  echo "== $1: waiting for cloud-init"
  remote "$1" 'cloud-init status --wait >/dev/null 2>&1 || true; test -f /etc/pad/ready' \
    || die "$1 is not ready (see /var/log/cloud-init-output.log on the VM)"
}

start_release() {
  if [ -n "${GHCR_TOKEN:-}" ]; then
    printf '%s' "$GHCR_TOKEN" | remote "$1" "docker login ghcr.io -u '${GHCR_USER:?set GHCR_USER}' --password-stdin" >/dev/null
  fi
  local status=0
  # never --remove-orphans: the warehouses created by the orchestrator would be deleted
  remote "$1" "$compose pull --quiet && docker compose --env-file /etc/pad/infra.env --env-file release.env up -d ${2:-}" || status=$?
  if [ -n "${GHCR_TOKEN:-}" ]; then remote "$1" 'docker logout ghcr.io' >/dev/null; fi
  [ "$status" -eq 0 ] || die "$1: docker compose failed"
  remote "$1" 'docker image prune -af --filter until=168h' >/dev/null
}

wait_ready pad-edge
wait_ready pad-data

echo "== data VM: orchestrator, sync, warehouses"
upload "$here/data/compose.yml" pad-data:/opt/pad/compose.yml
printf 'IMAGE_PREFIX=%s\nTAG=%s\nINITIAL_WAREHOUSES=%s\n' "$IMAGE_PREFIX" "$TAG" "$INITIAL_WAREHOUSES" \
  | remote pad-data 'umask 077; cat > /opt/pad/release.env'
start_release pad-data

echo "== edge VM: reverse proxy, UI, gateway, Redis"
upload "$here/edge/compose.yml" "$here/edge/Caddyfile" pad-edge:/opt/pad/
# the password is sent on stdin and only its bcrypt hash is stored on the VM
hash=$(printf '%s\n' "$ADMIN_PASSWORD" | remote pad-edge 'docker run --rm -i caddy:2-alpine caddy hash-password')
[[ $hash == \$2* ]] || die "could not hash the password"
printf "IMAGE_PREFIX=%s\nTAG=%s\nADMIN_USER=%s\nADMIN_PASSWORD_HASH='%s'\n" "$IMAGE_PREFIX" "$TAG" "$ADMIN_USER" "$hash" \
  | remote pad-edge 'umask 077; cat > /opt/pad/release.env'
start_release pad-edge
remote pad-edge "$compose up -d --force-recreate proxy"   # re-reads the Caddyfile

echo "== release $TAG installed"
