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
if sys.argv[3] in ('create', 'create-protected'):
    svc['bandwidth'] = str(d['bandwidthBps'])
    svc['path-planning'] = {'optimization-metric': [{'metric-type': ('salasim-service-types:' if m == 'delay-variation'
        else 'ietf-te-types:') + 'path-metric-' + m} for m in d['pathMetrics']],
        'candidate-path-count': d['pathPlanning']['candidatePathCount']}
    if sys.argv[3] == 'create-protected':
        p = d['protection']  # no tunnels: the controller chooses them
        svc['protection'] = {'mode': p['mode'], 'diversity': p['diversity'], 'revertive': p['revertive'],
                             'hold-off-time': p['holdOffMillis'], 'wait-to-revert': p['waitToRevertSeconds']}
    else:
        svc['tunnel'] = [{'tunnel-id': d['canonicalTunnelId'], 'role': 'primary'}]
elif sys.argv[2] == 'protectedService':
    pass  # deleting a protected service names no tunnels
else:
    svc['tunnel'] = [{'tunnel-id': d['canonicalTunnelId']}]
print(json.dumps({'salasim-service-fleet:input': {'service': [svc]}}))
PY
}
# A target is mounted by the controller a few seconds after it starts. Until then the controller refuses with
# controller-pce-unavailable / controller-pnc-unavailable and retry-safe=true ("nothing was sent"): a client asks again.
provision() { # key [mode] -> LAST holds the answer; succeeds when the service was admitted
  local body attempt resp code
  body=$(mkbody "$1" "${2:-create}"); LAST=""
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


step "11. a protected service inside one domain: the controller chooses primary and standby"
PSYM=$(J "d['protectedService']['primarySymbolicPathName']"); SSYM=$(J "d['protectedService']['standbySymbolicPathName']")
mdsc_log_count() { "${COMPOSE[@]}" logs mdsc 2>&1 | grep -ac "$1"; }
queried() { # service-id leaf value: the controller's query-services answer has this leaf value for the service
  restconf query-services "{\"salasim-service-fleet:input\":{\"service\":[{\"service-id\":\"$1\"}]}}" \
    | python3 -c "import json,sys;b=sys.stdin.read().rsplit('\n',1)[0];s=json.loads(b)['salasim-service-fleet:output']['service'][0];print(s.get('$2'));sys.exit(0 if str(s.get('$2'))=='$3' else 1)"
}
carried() { # leg [minimum count]: the controller logged that the service moved to this leg
  [[ $(mdsc_log_count "protection: svc-3 is carried by the $1") -ge ${2:-1} ]]
}
if provision protectedService create-protected; then ok "protected service admitted (no tunnels in the request)"; else bad "protected service not admitted"; diag mdsc pnc-d2 pce-d2; fi
echo "     $LAST" | head -c 500; echo
routedTo d2 ref-pnc-d2 && ok "routed through the PNC of its domain" || bad "not routed through ref-pnc-d2"
until_ 90 "the primary is on n3 n4 n5" want_path expectedPrimaryPathRouterIds protectedService "$D2" "$PSYM" || diag mdsc pce-d2
until_ 90 "the MDSC was told that the standby is active (it is asked for only after the primary is)" heard svc-3 standby active
until_ 30 "the standby is on n3 n5, diverse from the primary" want_path expectedStandbyPathRouterIds protectedService "$D2" "$SSYM" || diag mdsc pce-d2
"${COMPOSE[@]}" exec -T mdsc sh -c 'cat /var/salasim/shared/protection/svc-3.properties' | grep -E '^(phase|selected)=' | sed 's/^/     stored: /'
until_ 30 "query-services through the controller: svc-3 is up" queried svc-3 oper-status up
queried svc-3 selected-tunnel primary >/dev/null && ok "query-services: svc-3 is carried by the primary" || bad "query-services: wrong selected tunnel"

step "12. the primary link fails: the controller moves the service to the standby"
"$HERE/fault.sh" down && ok "carrier down on the primary's link" || bad "fault injection failed"
until_ 60 "the MDSC was told that the primary of svc-3 is down" heard svc-3 primary down
until_ 30 "the service is carried by the standby" carried standby || diag mdsc pce-d2
until_ 30 "query-services: svc-3 is carried by the standby and still up" bash -c "$(declare -f restconf queried); EXP='$EXP'; RESTCONF='$RESTCONF'; queried svc-3 selected-tunnel standby && queried svc-3 oper-status up"

replays() { restconf provision-services "$(mkbody protectedService create-protected)" | grep -q 'idempotent-replay'; }
stored() { # leaf value: what the controller stored for svc-3
  "${COMPOSE[@]}" exec -T mdsc cat /var/salasim/shared/protection/svc-3.properties | grep -E "^$1=" | cut -d= -f2
}

step "13. the controller restarts while the standby carries the service: it continues from what it stored"
echo "     stored before the restart: selected=$(stored selected) phase=$(stored phase)"
[[ "$(stored selected)" == STANDBY ]] && ok "the selection of the standby is stored" || bad "the selection was not stored"
"${COMPOSE[@]}" restart mdsc >/dev/null 2>&1 && ok "mdsc restarted" || bad "mdsc restart failed"
until_ 90 "the restarted controller loaded the protected service" bash -c "${COMPOSE[*]} logs mdsc 2>&1 | grep -a 'protected service(s) loaded' | tail -1 | grep -qv ' 0 protected'"
until_ 60 "the MDSC is mounted to its PCEs again (a repeated provision answers as a replay)" replays
[[ "$(stored selected)" == STANDBY ]] && ok "still carried by the standby after the restart" || bad "the selection was lost by the restart"

step "14. the link comes back: the lost primary is asked for again, and after wait-to-revert the service returns to it"
"$HERE/restore.sh" >/dev/null && ok "carrier up written" || bad "restore failed"
until_ 120 "the service is carried by the primary again (wait-to-revert 10 s, revertive), decided by the restarted controller" carried primary || diag mdsc pce-d2
echo "     primary path now: $(ero "$D2" "$PSYM")   (n3 n5 would be the link the standby uses)"

step "15. remove the protected service: no tunnels listed, both legs go"
if remove protectedService; then ok "delete admitted"; else bad "delete not admitted"; diag mdsc pce-d2; fi
until_ 90 "the primary is gone from the domain PCE" gone "$D2" "$PSYM"
until_ 90 "the standby is gone from the domain PCE" gone "$D2" "$SSYM"
until_ 60 "the controller forgot the service (state file removed)" bash -c "! ${COMPOSE[*]} exec -T mdsc test -e /var/salasim/shared/protection/svc-3.properties"

echo
if [[ $FAIL -eq 0 ]]; then echo "RESULT: PASS"; else echo "RESULT: FAIL"; exit 1; fi
