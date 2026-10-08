#!/usr/bin/env bash
# Reference scenario: build images, render runtime files, start the stack in dependency order.
# UNVERIFIED end to end (the authoring sandbox could not run Docker); see README.md.
#   run.sh up [--no-build]   build + render + start
#   run.sh down              stop and remove containers and volumes
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"          # workspace root holding the salasim_gmpls_* repos
BUILD="$HERE/build"
COMPOSE=(docker compose -f "$BUILD/docker-compose.yml" -p salasim-ref)
wait_http() { # url label timeout
  local i; for ((i = 0; i < $3; i++)); do curl -fs -o /dev/null "$1" && { echo "  ok: $2"; return 0; }; sleep 1; done
  echo "  TIMEOUT: $2 ($1)" >&2; return 1
}
case "${1:-up}" in
  up)
    if [[ "${2:-}" != "--no-build" ]]; then
      for x in emulator pce controller; do
        echo "== build salasim/$x:ref"
        docker build -f "$ROOT/salasim_gmpls_$x/Dockerfile" -t "salasim/$x:ref" "$ROOT"
      done
    fi
    python3 "$HERE/render.py" --out "$BUILD"
    P=$(python3 -c "import json;print(*[json.load(open('$BUILD/expected.json'))['hostPorts'][k] for k in ('parentApi','pceD1Api','pceD2Api','medium','mdsc')])")
    read -r PARENT D1 D2 MEDIUM MDSC <<<"$P"
    echo "== medium";       "${COMPOSE[@]}" up -d medium;                         wait_http "http://127.0.0.1:$MEDIUM/health" medium 90
    echo "== nodes";        "${COMPOSE[@]}" up -d n1 n2 n3 n4 n5
    echo "== domain PCEs";  "${COMPOSE[@]}" up -d pce-d1 pce-d2
    wait_http "http://127.0.0.1:$D1/api/v1/health" pce-d1 90; wait_http "http://127.0.0.1:$D2/api/v1/health" pce-d2 90
    echo "== PNCs + MDSC";  "${COMPOSE[@]}" up -d pnc-d1 pnc-d2 mdsc
    echo "== waiting for the MDSC to write composed-topology.json (parent PCE needs it at start)"
    for ((i = 0; i < 180; i++)); do
      "${COMPOSE[@]}" exec -T mdsc test -s /var/salasim/shared/composed-topology.json 2>/dev/null && break; sleep 1
    done || true
    "${COMPOSE[@]}" exec -T mdsc test -s /var/salasim/shared/composed-topology.json || { echo "composed topology never appeared; see: ${COMPOSE[*]} logs mdsc pnc-d1 pnc-d2" >&2; exit 1; }
    echo "== parent PCE"; "${COMPOSE[@]}" up -d parent-pce
    wait_http "http://127.0.0.1:$PARENT/api/v1/health" parent-pce 90
    echo "stack is up; run ./check.sh"
    ;;
  down) "${COMPOSE[@]}" down -v ;;
  *) echo "usage: $0 up [--no-build] | down" >&2; exit 2 ;;
esac
