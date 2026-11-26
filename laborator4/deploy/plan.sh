#!/usr/bin/env bash
# Decides what a pipeline run has to do ("build once, deploy many"). Prints key=value lines.
#   needs IMAGE_PREFIX, EVENT (push | workflow_dispatch), REF; BEFORE = previous head of the pushed branch
#
#   tag        = tree hash of HEAD: the same content always gets the same tag, on any branch or commit
#   skip_build = true  : push to main and the images of this tree are already published (they were tested)
#   deploy     = false : the push changed only files under .github/ and/or README files
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
: "${IMAGE_PREFIX:?}" "${EVENT:?}" "${REF:?}"

tag=$(git rev-parse 'HEAD^{tree}')

skip_build=false
if [ "$EVENT" = push ] && [ "$REF" = refs/heads/main ] && "$here/images-exist.sh" "$IMAGE_PREFIX" "$tag"; then
  skip_build=true
fi

# when the previous head is unknown (new branch, forced push) the safe answer is "deploy"
deploy=true
if [ "$EVENT" = push ] && git cat-file -e "${BEFORE:-unknown}^{commit}" 2>/dev/null; then
  changed=$(git diff --name-only --no-renames "$BEFORE" HEAD)
  # is there any changed path that is NOT under .github/ and NOT a README?
  if ! grep -qvE '^(\.github/.*|(.*/)?README(\.[^/]*)?)?$' <<<"$changed"; then
    deploy=false
  fi
fi

printf 'tag=%s\nskip_build=%s\ndeploy=%s\n' "$tag" "$skip_build" "$deploy"
