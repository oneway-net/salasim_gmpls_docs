#!/usr/bin/env bash
# One entry point for "is the platform consistent?": architecture fitness ratchets, YANG validation and the unit tests
# of every repository.
# Exit code 0 = everything run passed; it is the command a CI job runs.
#
#   tools/check-all.sh                 run every check
#   tools/check-all.sh yang pce        run only the named checks
#   SMOKES=1 tools/check-all.sh        also run the controller smoke scripts (need loopback sockets)
#
# Checks: fitness (F1-F10 ratchets, tools/fitness), yang, netconf, emulator, pce, controller, backend, frontend.
# Environment: WORKSPACE (default: the parent of this repo), PYANG (default: the yang-bootstrap venv's pyang, then PATH),
#              JAVA_HOME (default: JDK 25 from Homebrew if present), MVN_FLAGS (default "-o -q"), PYTEST (default "python3 -m pytest").
set -u
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKSPACE="${WORKSPACE:-$(cd "$here/.." && pwd)}"
MVN_FLAGS="${MVN_FLAGS:--o -q}"
PYTEST="${PYTEST:-python3 -m pytest}"
if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@25 ]; then export JAVA_HOME=/opt/homebrew/opt/openjdk@25; fi
[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"
unset JAVA_TOOL_OPTIONS
if [ -z "${PYANG:-}" ]; then
  if [ -x "$WORKSPACE/yang-bootstrap/.venv/bin/pyang" ]; then PYANG="$WORKSPACE/yang-bootstrap/.venv/bin/pyang"; else PYANG=pyang; fi
fi
export PYANG

all="fitness yang netconf emulator pce controller backend frontend"
selected="${*:-$all}"
failed=""
passed=""

run() {
  # run <name> <dir> <command...>
  local name="$1" dir="$2"; shift 2
  printf '\n=== %s (%s)\n' "$name" "$dir"
  if [ ! -d "$dir" ]; then
    printf 'CHECK %s: MISSING directory\n' "$name"; failed="$failed $name"; return
  fi
  if (cd "$dir" && "$@"); then
    printf 'CHECK %s: PASS\n' "$name"; passed="$passed $name"
  else
    printf 'CHECK %s: FAIL\n' "$name"; failed="$failed $name"
  fi
}

for c in $selected; do
  case "$c" in
    fitness)    run fitness "$here/tools/fitness" sh -c "python3 -m unittest -q test_fitness && WORKSPACE=\"$WORKSPACE\" python3 fitness.py" ;;
    yang)       run yang "$WORKSPACE/salasim_gmpls_yang" ./validate.sh ;;
    netconf)    run netconf "$WORKSPACE/salasim_gmpls_netconf" mvn $MVN_FLAGS test ;;
    emulator)   run emulator "$WORKSPACE/salasim_gmpls_emulator" mvn $MVN_FLAGS test ;;
    pce)        run pce "$WORKSPACE/salasim_gmpls_pce" mvn $MVN_FLAGS test ;;
    controller)
      run controller "$WORKSPACE/salasim_gmpls_controller" mvn $MVN_FLAGS test
      if [ -n "${SMOKES:-}" ]; then
        run controller-integration-smoke "$WORKSPACE/salasim_gmpls_controller" scripts/integration-smoke.sh
        run controller-notification-smoke "$WORKSPACE/salasim_gmpls_controller" scripts/notification-smoke.sh
      fi ;;
    backend)    run backend "$WORKSPACE/salasim_gmpls_backend" $PYTEST -q ;;
    frontend)   run frontend "$WORKSPACE/salasim_gmpls_frontend" npm run --silent test:unit ;;
    *) printf 'unknown check: %s (known: %s)\n' "$c" "$all"; failed="$failed $c" ;;
  esac
done

printf '\n=== summary\npassed:%s\nfailed:%s\n' "${passed:- none}" "${failed:- none}"
[ -z "$failed" ]
