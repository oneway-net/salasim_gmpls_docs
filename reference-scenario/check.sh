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
sessions() { get "http://127.0.0.1:$1/api/v1/pce/sessions" | python3 -c "import re,sys;print(len(set(re.findall(r'10\\.77\\.\\d+\\.\\d+', sys.stdin.read()))))"; }
n1=$(sessions $D1) ; [[ "${n1:-0}" -ge 2 ]] && ok "pce-d1 sessions=$n1 (>=2)" || bad "pce-d1 sessions=${n1:-?} (want >=2)"
n2=$(sessions $D2) ; [[ "${n2:-0}" -ge 3 ]] && ok "pce-d2 sessions=$n2 (>=3)" || bad "pce-d2 sessions=${n2:-?} (want >=3)"
both_children() { get "http://127.0.0.1:$PARENT/api/v1/parent-pce/peers" | python3 -c "import json,sys;sys.exit(0 if json.load(sys.stdin).get('count',0)>=2 else 1)"; }
# the children retry the parent every 5 s: provisioning before both are connected fails with UNKNOWN_SOURCE
until_ 120 "parent PCE sees both child PCEs" both_children

step "3. MDSC composed the topology (shared file + RESTCONF mount state)"
"${COMPOSE[@]}" exec -T mdsc test -s /var/salasim/shared/composed-topology.json && ok "composed-topology.json present" || bad "composed topology missing"
get "http://127.0.0.1:$MDSC/restconf/data" >/dev/null && ok "MDSC RESTCONF answers" || bad "MDSC RESTCONF silent"
get "http://127.0.0.1:$PARENT/api/v1/pce/inter-domain-links" | head -c 400 | sed 's/^/     inter-domain-links: /'; echo

step "4. provision the service across both domains (through the controller: RESTCONF on the MDSC)"
RESTCONF="http://127.0.0.1:$MDSC/restconf/operations/salasim-service-fleet"
restconf() { # rpc body  -> prints the HTTP status on the last line
  curl -s --max-time 90 -w '\n%{http_code}' -X POST -H 'Content-Type: application/yang-data+json' \
    -H 'Accept: application/yang-data+json' -d "$2" "$RESTCONF:$1"
}
allAdmitted() { python3 -c "import json,sys;o=json.load(sys.stdin)['salasim-service-fleet:output'];sys.exit(0 if o.get('all-admitted') else 1)"; }
# the request body of a service of expected.json (key: service | intraService; mode: create | delete)
mkbody() {
  python3 - "$EXP" "$1" "$2" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))[sys.argv[2]]
svc = {'service-id': d['serviceId'], 'source': d['sourceRouterId'], 'destination': d['destinationRouterId']}
if sys.argv[3] == 'create':
    svc['bandwidth'] = str(d['bandwidthBps'])
    svc['path-planning'] = {'optimization-metric': [{'metric-type': ('salasim-service-types:' if m == 'delay-variation'
        else 'ietf-te-types:') + 'path-metric-' + m} for m in d['pathMetrics']],
        'candidate-path-count': d['pathPlanning']['candidatePathCount']}
    svc['tunnel'] = [{'tunnel-id': d['canonicalTunnelId'], 'role': 'primary'}]
else:
    svc['tunnel'] = [{'tunnel-id': d['canonicalTunnelId']}]
print(json.dumps({'salasim-service-fleet:input': {'service': [svc]}}))
PY
}
# A target is mounted by the controller a few seconds after it starts. Until then the controller refuses with
# controller-pce-unavailable / controller-pnc-unavailable and retry-safe=true ("nothing was sent"): a client asks again.
provision() { # key -> LAST holds the answer; succeeds when the service was admitted
  local body attempt resp code
  body=$(mkbody "$1" create); LAST=""
  for attempt in $(seq 1 30); do
    resp=$(restconf provision-services "$body"); code=${resp##*$'\n'}; LAST=${resp%$'\n'*}
    if [[ "$code" == 2* ]] && echo "$LAST" | allAdmitted; then return 0; fi
    echo "$LAST" | grep -q '"controller-p[a-z]*-unavailable"' || return 1
    echo "     attempt $attempt: the controller has not mounted the target yet (retry-safe), asking again"; sleep 3
  done
  return 1
}
remove() { # key -> LAST holds the answer; succeeds when the delete was admitted
  local resp code; resp=$(restconf delete-services "$(mkbody "$1" delete)"); code=${resp##*$'\n'}; LAST=${resp%$'\n'*}
  [[ "$code" == 2* ]] && echo "$LAST" | allAdmitted
}
routedTo() { # owner device: the first result says where the service went
  echo "$LAST" | python3 -c "import json,sys;r=json.load(sys.stdin)['salasim-service-fleet:output']['result'][0];sys.exit(0 if (r.get('owner'),r.get('pce'))==(sys.argv[1],sys.argv[2]) else 1)" "$1" "$2"
}
if provision service; then ok "service admitted by the PCE through the controller"; else bad "service not admitted"; diag mdsc parent-pce pce-d1 pce-d2; fi
echo "     $LAST" | head -c 700; echo
routedTo core ref-pce-core && ok "routed to the parent PCE (owner core)" || bad "not routed to the parent PCE"

ero() { # [port] [symbol]: the router ids of that LSP's ERO, as the PCE on that port reports it
  get "http://127.0.0.1:${1:-$PARENT}/api/v1/pce/lsps" | python3 -c "
import json,sys,re
d=json.load(sys.stdin)
ip=re.compile(r'10\.77\.\d+\.\d+')
want=sys.argv[1]
items=d if isinstance(d,list) else d.get('lsps',d.get('items',[]))
for it in items:
    if want in json.dumps(it):
        print(' '.join(dict.fromkeys(ip.findall(json.dumps(it.get('ero',it.get('path',it)))))))
        break
" "${2:-$SYMBOL}"; }
want_path() { # key [root port symbol]: the ERO holds the expected router ids in order
  local expected got; expected=$(J "' '.join(d['${2:-service}']['$1'])"); got=$(ero "${3:-$PARENT}" "${4:-$SYMBOL}")
  echo "     ero: ${got:-<none>}   expected: $expected"; [[ "$got" == *"$expected"* ]]
}

heard() { # service tunnel state: the MDSC logged that notification (service-state-changed, RFC 5277)
  "${COMPOSE[@]}" logs mdsc 2>&1 | grep -aq "service state: .* $1/$2 is $3"
}
until_ 60 "the MDSC was told by the parent PCE that svc-1 is active" heard svc-1 primary active

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

step "9. remove the service through the controller; removing it again answers removed"
if remove service; then ok "delete admitted"; else bad "delete not admitted"; diag mdsc parent-pce; fi
echo "     $LAST" | head -c 500; echo
gone() { ! get "http://127.0.0.1:$1/api/v1/pce/lsps" | grep -q "$2"; }
until_ 90 "the LSP is gone from the parent PCE" gone "$PARENT" "$SYMBOL"
remove service; echo "$LAST" | grep -q '"removed"' && ok "a second delete answers removed" || bad "second delete did not answer removed"

step "10. a service inside one domain: MDSC -> the domain's PNC -> the domain PCE"
ISYMBOL=$(J "d['intraService']['symbolicPathName']")
if provision intraService; then ok "intra-domain service admitted"; else bad "intra-domain service not admitted"; diag mdsc pnc-d2 pce-d2; fi
echo "     $LAST" | head -c 600; echo
routedTo d2 ref-pnc-d2 && ok "routed through the PNC of its domain (owner d2)" || bad "not routed through ref-pnc-d2"
until_ 60 "the MDSC was told through the PNC that svc-2 is active" heard svc-2 primary active
until_ 90 "the domain PCE holds the LSP on n3 n4 n5" want_path expectedPathRouterIds intraService "$D2" "$ISYMBOL" || diag pnc-d2 pce-d2
if remove intraService; then ok "intra-domain delete admitted"; else bad "intra-domain delete not admitted"; diag mdsc pnc-d2 pce-d2; fi
until_ 90 "the LSP is gone from the domain PCE" gone "$D2" "$ISYMBOL"
remove intraService; echo "$LAST" | grep -q '"removed"' && ok "a second intra-domain delete answers removed" || bad "second intra-domain delete did not answer removed"

echo; [[ $FAIL -eq 0 ]] && echo "RESULT: PASS" || echo "RESULT: FAIL"; exit $FAIL
