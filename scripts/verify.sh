#!/usr/bin/env bash
set -euo pipefail

# One-command foundation verification gate.
# Runs, in order: root Maven clean verify, frontend tests, frontend production
# build, and a whitespace/conflict-marker check of the working tree.
# Any failure aborts immediately with a non-zero exit code.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "==> [1/4] Maven clean verify"
"${ROOT_DIR}/mvnw" -f "${ROOT_DIR}/pom.xml" clean verify

echo "==> [2/4] Frontend tests (Vitest)"
npm --prefix "${ROOT_DIR}/web" test

echo "==> [3/4] Frontend production build"
npm --prefix "${ROOT_DIR}/web" run build

echo "==> [4/4] git diff --check"
git -C "${ROOT_DIR}" diff --check

echo "==> verify.sh: all checks passed"
