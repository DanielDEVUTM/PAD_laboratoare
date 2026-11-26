#!/usr/bin/env bash
# Runs the two cloud compose files on ONE machine, talking to each other through a host IP exactly as the
# two VMs do through their private IPs. Needs the images <IMAGE_PREFIX>-<service>:<TAG>.
#   Linux:          HOST_IP=$(hostname -I | awk '{print $1}') ./deploy/test-topology.sh
#   Docker Desktop: HOST_IP=host.docker.internal BIND_IP=0.0.0.0 ./deploy/test-topology.sh
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
: "${HOST_IP:?}"
export IMAGE_PREFIX=${IMAGE_PREFIX:-lab4} TAG=${TAG:-latest}
export EDGE_PRIVATE_IP=$HOST_IP DATA_PRIVATE_IP=$HOST_IP BIND_IP=${BIND_IP:-$HOST_IP}
export SITE_ADDRESS=:80 HTTP_PORT=${HTTP_PORT:-18080} HTTPS_PORT=${HTTPS_PORT:-18443}
export INITIAL_WAREHOUSES=2 MIN_NODES=2
export ADMIN_USER=admin
ADMIN_PASSWORD="test-$(od -An -N12 -tx1 /dev/urandom | tr -d ' \n')"; export ADMIN_PASSWORD

if [ -n "$(docker ps -aq --filter label=lab4.managed=true)" ]; then
  echo "warehouse containers already exist (a local cluster is running). Stop it first: ./scripts/down.sh" >&2
  exit 1
fi
ADMIN_PASSWORD_HASH=$(printf '%s\n' "$ADMIN_PASSWORD" | docker run --rm -i caddy:2-alpine caddy hash-password)
export ADMIN_PASSWORD_HASH

data() { docker compose -f "$here/data/compose.yml" "$@"; }
edge() { docker compose -f "$here/edge/compose.yml" "$@"; }
cleanup() {
  status=$?
  if [ "$status" -ne 0 ]; then edge logs --tail 40 || true; data logs --tail 60 || true; fi
  ids=$(docker ps -aq --filter label=lab4.managed=true --filter label=com.docker.compose.project=pad-lab4-data)
  # shellcheck disable=SC2086
  if [ -n "$ids" ]; then docker rm -f -v $ids >/dev/null; fi
  edge down -v >/dev/null 2>&1 || true
  data down -v >/dev/null 2>&1 || true
}
trap cleanup EXIT

data up -d
edge up -d
BASE_URL=http://localhost:$HTTP_PORT "$here/verify.sh"

api() { curl -sS --max-time 15 -u "$ADMIN_USER:$ADMIN_PASSWORD" "$@"; }
base=http://localhost:$HTTP_PORT/api
fail() { echo "  FAIL $1"; exit 1; }
api "$base/registry/instances" | grep -q "\"url\":\"http://$HOST_IP:81" || fail "nodes advertise the host address"
echo "  PASS nodes advertise $HOST_IP and a published port"
api "$base/warehouses" | grep -q '"url"' && fail "no localhost links in the cloud"
echo "  PASS no unreachable direct links are offered"
[ -z "$(docker ps --filter label=lab4.role=db --format '{{.Ports}}' | grep -- '->' || true)" ] || fail "databases must not be published"
echo "  PASS databases are not published"
code=$(api -o /dev/null -w '%{http_code}' -X PUT "$base/employees/topology" -H 'Content-Type: application/json' \
  -d '{"firstName":"T","lastName":"P","email":"t@p.test"}')
[ "$code" = 201 ] || fail "write through the proxy (HTTP $code)"
sleep 5
for wh in wh-1 wh-2; do
  api "$base/admin/instances/$wh/employees?limit=500" | grep -q '"topology"' || fail "write reached $wh"
done
echo "  PASS write through the proxy reached both warehouses"

# a new release: the API containers created earlier must be replaced, the data must stay
docker build -q -t "$IMAGE_PREFIX-dw-node:next" - >/dev/null <<DOCKERFILE
FROM $IMAGE_PREFIX-dw-node:$TAG
LABEL release=next
DOCKERFILE
docker tag "$IMAGE_PREFIX-orchestrator:$TAG" "$IMAGE_PREFIX-orchestrator:next"
docker tag "$IMAGE_PREFIX-sync:$TAG" "$IMAGE_PREFIX-sync:next"
TAG=next data up -d
want=$(docker image inspect -f '{{.Id}}' "$IMAGE_PREFIX-dw-node:next")
for _ in $(seq 1 60); do
  [ "$(docker inspect -f '{{.Image}}' wh-1-api wh-2-api 2>/dev/null | grep -c "$want")" = 2 ] && break
  sleep 3
done
[ "$(docker inspect -f '{{.Image}}' wh-1-api wh-2-api | grep -c "$want")" = 2 ] || fail "API containers updated"
BASE_URL=http://localhost:$HTTP_PORT "$here/verify.sh" >/dev/null || fail "system healthy after the update"
api "$base/employees/topology" -H 'Cache-Control: no-cache' | grep -q '"topology"' || fail "data kept after the update"
echo "  PASS new release replaced the API containers and kept the data"
docker rmi "$IMAGE_PREFIX-orchestrator:next" "$IMAGE_PREFIX-sync:next" >/dev/null
echo "topology test passed"
