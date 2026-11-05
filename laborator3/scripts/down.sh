#!/bin/sh
# Stops everything, including the warehouses created dynamically by the orchestrator.
cd "$(dirname "$0")/.."
docker compose down --remove-orphans -v
# safety net for dynamic containers that compose did not know about
ids=$(docker ps -aq --filter label=lab3.managed=true)
[ -n "$ids" ] && docker rm -f -v $ids
echo "all stopped (data removed: next start begins from the seed data)"
