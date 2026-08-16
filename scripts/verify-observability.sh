#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE=(docker compose --env-file "${ROOT_DIR}/deploy/compose/.env.example" -f "${ROOT_DIR}/deploy/compose/compose.yml")
OBS="${ROOT_DIR}/deploy/compose/observability"

require_file() {
  test -f "$1" || { echo "missing required file: $1" >&2; exit 1; }
}

for file in \
  "${OBS}/prometheus/prometheus.yml" \
  "${OBS}/prometheus/rules/veridex-alerts.yml" \
  "${OBS}/otel-collector/config.yaml" \
  "${OBS}/tempo/tempo.yaml" \
  "${OBS}/grafana/dashboards/veridex-overview.json" \
  "${OBS}/grafana/provisioning/datasources/datasources.yml"; do
  require_file "$file"
done

python3 -m json.tool "${OBS}/grafana/dashboards/veridex-overview.json" >/dev/null
python3 - <<'PY' "${OBS}/prometheus/prometheus.yml" "${OBS}/prometheus/rules/veridex-alerts.yml" "${OBS}/otel-collector/config.yaml" "${OBS}/tempo/tempo.yaml"
import sys
try:
    import yaml
except ImportError:
    yaml = None
if yaml:
    for path in sys.argv[1:]:
        with open(path, encoding="utf-8") as handle:
            yaml.safe_load(handle)
PY

"${COMPOSE[@]}" config --quiet

grep -Fq 'prom/prometheus:v3.13.2' "${ROOT_DIR}/deploy/compose/compose.yml"
grep -Fq 'grafana/grafana:13.1.3' "${ROOT_DIR}/deploy/compose/compose.yml"
grep -Fq 'otel/opentelemetry-collector-contrib:0.158.0' "${ROOT_DIR}/deploy/compose/compose.yml"
grep -Fq 'grafana/tempo:3.0.3' "${ROOT_DIR}/deploy/compose/compose.yml"
grep -Fq 'queue="ingestion.document"' "${OBS}/prometheus/rules/veridex-alerts.yml"
grep -Fq 'queue="ingestion.document.dlq"' "${OBS}/prometheus/rules/veridex-alerts.yml"

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker unavailable; host-side observability syntax checks passed."
  exit 0
fi

if ! docker info >/dev/null 2>&1; then
  echo "Docker daemon unavailable; host-side observability syntax checks passed."
  exit 0
fi

docker run --rm --entrypoint /bin/promtool \
  -v "${OBS}/prometheus:/etc/prometheus:ro" \
  prom/prometheus:v3.13.2 check config /etc/prometheus/prometheus.yml

docker run --rm --entrypoint /bin/promtool \
  -v "${OBS}/prometheus/rules:/rules:ro" \
  prom/prometheus:v3.13.2 check rules /rules/veridex-alerts.yml

docker run --rm --entrypoint /otelcol-contrib \
  -v "${OBS}/otel-collector/config.yaml:/etc/otelcol-contrib/config.yaml:ro" \
  otel/opentelemetry-collector-contrib:0.158.0 validate --config=/etc/otelcol-contrib/config.yaml

"${COMPOSE[@]}" up -d tempo
cleanup() { "${COMPOSE[@]}" stop tempo >/dev/null || true; }
trap cleanup EXIT
for attempt in $(seq 1 30); do
  if curl --fail --silent http://localhost:3200/ready >/dev/null; then
    echo "Tempo readiness check passed."
    exit 0
  fi
  sleep 2
done
"${COMPOSE[@]}" logs tempo
exit 1
