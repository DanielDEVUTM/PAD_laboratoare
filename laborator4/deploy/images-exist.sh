#!/usr/bin/env bash
# Are all the images of a release in the registry?   images-exist.sh <image prefix> <tag>
# exit 0 = all of them exist; exit 1 = at least one is missing or cannot be read (names on stderr)
set -u
: "${1:?image prefix}" "${2:?tag}"
missing=0
for service in dw-node gateway sync orchestrator ui; do
  image="$1-$service:$2"
  docker buildx imagetools inspect "$image" >/dev/null 2>&1 || { echo "not in the registry: $image" >&2; missing=1; }
done
exit "$missing"
