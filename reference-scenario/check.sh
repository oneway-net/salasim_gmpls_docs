#!/usr/bin/env bash
# Acceptance steps of the reference scenario against a running stack (run.sh up). Exit 0 = every step passed.
# UNVERIFIED end to end: written against the code, never run (the authoring sandbox cannot run Docker).
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; EXP="$HERE/build/expected.json"
COMPOSE=(docker compose -f "$HERE/build/docker-compose.yml" -p salasim-ref)
J() { python3 -c "import json,sys;d=json.load(open('$EXP'));print($1)"; }
PARENT=$(J "d['hostPorts']['parentApi']"); D1=$(J "d['hostPorts']['pceD1Api']"); D2=$(J "d['hostPorts']['pceD2Api']")
NODEBASE=$(J "d['hostPorts']['nodeApiBase']"); MDSC=$(J "d['hostPorts']['mdsc']")
SYMBOL=$(J "d['service']['symbolicPathName']")
FAIL=0
step() { echo; echo "## $*"; }
ok()   { echo "  PASS: $*"; }
bad()  { echo "  FAIL: $*"; FAIL=1; }
until_() { # timeout description cmd...   poll cmd until it succeeds
  local t=$1 d=$2; shift 2; local i
  for ((i = 0; i < t; i++)); do "$@" >/dev/null 2>&1 && { ok "$d"; return 0; }; sleep 1; done
  bad "$d (after ${t}s)"; return 1
}
get() { curl -fs --max-time 10 "$1"; }
diag() { echo "  -- diagnostics: ${COMPOSE[*]} logs --tail 60 $*"; "${COMPOSE[@]}" logs --tail 60 "$@" 2>&1 | sed 's/^/     /'; }

step "1. health of PCEs and emulators"
for p in $PARENT $D1 $D2; do get "http://127.0.0.1:$p/api/v1/health" >/dev/null && ok "PCE :$p healthy" || bad "PCE :$p unhealthy"; done
for i in 1 2 3 4 5; do get "http://127.0.0.1:$((NODEBASE + i))/api/v1/health" >/dev/null && ok "n$i healthy" || bad "n$i unhealthy"; done

step "2. PCEP sessions (each domain PCE sees its PCCs; parent sees both children)"
sessions() { get "http://127.0.0.1:$1/api/v1/pce/sessions" | python3 -c "import json,sys;d=json.load(sys.stdin);print(len(d if isinstance(d,list) else d.get('sessions',d)))"; }
n1=$(sessions $D1) ; [[ "${n1:-0}" -ge 2 ]] && ok "pce-d1 sessions=$n1 (>=2)" || bad "pce-d1 sessions=${n1:-?} (want >=2)"
n2=$(sessions $D2) ; [[ "${n2:-0}" -ge 3 ]] && ok "pce-d2 sessions=$n2 (>=3)" || bad "pce-d2 sessions=${n2:-?} (want >=3)"
get "http://127.0.0.1:$PARENT/api/v1/parent-pce/peers" | python3 -c "import json,sys;d=json.load(sys.stdin);print(d)" | sed 's/^/     peers: /'

step "3. MDSC composed the topology (shared file + RESTCONF mount state)"
"${COMPOSE[@]}" exec -T mdsc test -s /var/salasim/shared/composed-topology.json && ok "composed-topology.json present" || bad "composed topology missing"
get "http://127.0.0.1:$MDSC/restconf/data/ietf-network:networks" >/dev/null && ok "MDSC RESTCONF answers" || bad "MDSC RESTCONF silent"
get "http://127.0.0.1:$PARENT/api/v1/pce/inter-domain-links" | head -c 400 | sed 's/^/     inter-domain-links: /'; echo

step "4. provision the service across both domains (parent PCE)"
BODY=$(J "json.dumps({k: d['service'][k] for k in ('symbolicPathName','serviceId','canonicalTunnelId','sourceRouterId','destinationRouterId','bandwidthBps','pathPlanning')})")
RESP=$(curl -s --max-time 60 -w '\n%{http_code}' -X POST -H 'Content-Type: application/json' -d "$BODY" "http://127.0.0.1:$PARENT/api/v1/pce/md-lsps")
CODE=${RESP##*$'\n'}; echo "     HTTP $CODE: ${RESP%$'\n'*}" | head -c 600; echo
[[ "$CODE" == 2* ]] && ok "md-lsp accepted" || { bad "md-lsp rejected"; diag parent-pce pce-d1 pce-d2; }

ero() { # prints the router ids of the md-lsp ERO as reported by the parent
  get "http://127.0.0.1:$PARENT/api/v1/pce/lsps" | python3 -c "
import json,sys,re
d=json.load(sys.stdin); text=json.dumps(d)
ip=re.compile(r'10\.77\.\d+\.\d+')
want='$SYMBOL'
items=d if isinstance(d,list) else d.get('lsps',d.get('items',[]))
for it in items:
    if want in json.dumps(it):
        print(' '.join(dict.fromkeys(ip.findall(json.dumps(it.get('ero',it.get('path',it)))))))
        break
"; }
want_path() { # file-key  -> compares set+order of router ids contained in the ERO
  local expected; expected=$(J "' '.join(d['service']['$1'])"); local got; got=$(ero)
  echo "     ero: ${got:-<none>}   expected: $expected"; [[ "$got" == *"$expected"* ]]
}

step "5. LSP up on the expected path (n1 n2 n3 n4 n5)"
until_ 60 "ERO equals expectedPathRouterIds" want_path expectedPathRouterIds || diag parent-pce pce-d2

step "6. inject the fault (carrier down on n3 port 2 and n4 port 1)"
"$HERE/fault.sh" down && ok "medium accepted both carrier-down writes" || bad "fault injection failed"
until_ 60 "n3 logged [LINK-DOWN]" bash -c "${COMPOSE[*]} logs n3 2>&1 | grep -q 'LINK-DOWN'"
until_ 60 "n4 logged [LINK-DOWN]" bash -c "${COMPOSE[*]} logs n4 2>&1 | grep -q 'LINK-DOWN'"

step "7. recovery: transit failure -> signalling to ingress -> PCRpt DOWN -> parent reroute"
until_ 60 "domain PCE d2 received the failure report" bash -c "${COMPOSE[*]} logs pce-d2 2>&1 | grep -qiE 'PCRpt|report.*down|LSP.*down'"
until_ 60 "parent PCE started a reroute" bash -c "${COMPOSE[*]} logs parent-pce 2>&1 | grep -qiE 'reportBrokenLsp|reroute'"
until_ 120 "ERO avoids n3-n4 (n1 n2 n3 n5)" want_path expectedPathAfterFaultRouterIds || diag parent-pce pce-d2 n3 n4

step "8. restore the link; LSP stays up on the new path"
"$HERE/restore.sh" >/dev/null && ok "carrier up written" || bad "restore failed"
sleep 5; want_path expectedPathAfterFaultRouterIds && ok "path stable after restore (no flap)" || echo "  NOTE: path changed after restore (acceptable if the PCE re-optimised); inspect manually"

echo; [[ $FAIL -eq 0 ]] && echo "RESULT: PASS" || echo "RESULT: FAIL"; exit $FAIL
