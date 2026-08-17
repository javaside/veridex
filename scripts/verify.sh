#!/usr/bin/env bash
set -euo pipefail

# One-command foundation verification gate.
# Runs, in order: root Maven clean verify, frontend tests, frontend production
# build, and a whitespace/conflict-marker check of the working tree.
# Any failure aborts immediately with a non-zero exit code.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "==> [1/5] Maven clean verify"
"${ROOT_DIR}/mvnw" -f "${ROOT_DIR}/pom.xml" clean verify

echo "==> [2/5] Frontend tests (Vitest)"
npm --prefix "${ROOT_DIR}/web" test

echo "==> [3/5] Frontend production build"
npm --prefix "${ROOT_DIR}/web" run build

echo "==> [4/5] Observability stack checks"
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  "${ROOT_DIR}/scripts/verify-observability.sh"
else
  echo "Docker unavailable; observability container checks skipped."
fi

echo "==> [5/5] git diff --check"
git -C "${ROOT_DIR}" diff --check

echo "==> verify.sh: all checks passed"
