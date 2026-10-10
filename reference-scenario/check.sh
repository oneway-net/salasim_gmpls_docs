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

step "4. provision the service across both domains (through the controller: RESTCONF ietf-te on the MDSC)"
DATA="http://127.0.0.1:$MDSC/restconf/data/ietf-te:te/tunnels"
CT='Content-Type: application/yang-data+json'
http() { # method path [body] -> prints the body, then the HTTP status on the last line
  curl -s --max-time 90 -w '\n%{http_code}' -X "$1" -H "$CT" -H 'Accept: application/yang-data+json' ${3:+-d "$3"} "$DATA$2"
}
# the tunnel of a service of expected.json (key: service | intraService | protectedService)
mkbody() {
  python3 - "$EXP" "$1" "$2" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))[sys.argv[2]]
w = len(d['pathMetrics'])
def metric(m, weight):
    return {'metric-type': ('salasim-te-ext:' if m == 'delay-variation' else 'ietf-te-types:') + 'path-metric-' + m,
            'weight': weight}
t = {'name': d['serviceId'], 'source': {'te-node-id': d['sourceRouterId']}, 'destination': {'te-node-id': d['destinationRouterId']},
     'bidirectional': False,
     # the model's unit is bytes per second
     'te-bandwidth': {'generic': str((int(d['bandwidthBps']) + 7) // 8)},
     'primary-paths': {'primary-path': [{'name': 'primary', 'k-requested-paths': d['pathPlanning']['candidatePathCount'],
         'optimizations': {'optimization-metric': [metric(m, w - i) for i, m in enumerate(d['pathMetrics'])]}}]}}
if sys.argv[3] == 'create-protected':
    p = d['protection']
    t['protection'] = {'protection-type': 'ietf-te-types:lsp-protection-1-for-1', 'protection-reversion-disable': not p['revertive'],
                       'hold-off-time': p['holdOffMillis'], 'wait-to-revert': p['waitToRevertSeconds']}
    t['secondary-paths'] = {'secondary-path': [{'name': 'standby', 'disjointness': p['diversity'] if p['diversity'] != 'srlg' else 'srlg link',
        'optimizations': {'optimization-metric': [metric(m, w - i) for i, m in enumerate(d['pathMetrics'])]}}]}
print(json.dumps({'ietf-te:tunnel': [t]}))
PY
}
sid() { J "d['$1']['serviceId']"; }
tunnel_state() { # key -> the tunnel as the MDSC reports it (configuration and state), JSON
  http GET "/tunnel=$(sid "$1")" | head -n -1
}
primary_failure() { # key -> the failure type on the primary path, if any
  tunnel_state "$1" | python3 -c "
import json,sys
try: t=json.load(sys.stdin)['ietf-te:tunnel'][0]
except Exception: sys.exit(0)
for p in t.get('primary-paths',{}).get('primary-path',[]): print(p.get('salasim-te-ext:failure',{}).get('type',''))"
}
# A target is mounted by the controller a few seconds after it starts. Until then the controller marks the tunnel's primary
# path failed with controller-pce-unavailable / controller-pnc-unavailable ("nothing was sent"): a client writes it again.
provision() { # key [mode] -> succeeds when the tunnel was accepted and not refused
  local body attempt resp code f; body=$(mkbody "$1" "${2:-create}"); LAST=""
  for attempt in $(seq 1 30); do
    resp=$(http PUT "/tunnel=$(sid "$1")" "$body"); code=${resp##*$'\n'}; LAST=${resp%$'\n'*}
    [[ "$code" == 2* ]] || return 1
    sleep 2; f=$(primary_failure "$1")
    case "$f" in
      "") return 0 ;;
      controller-p*-unavailable) echo "     attempt $attempt: the controller has not mounted the target yet ($f), writing it again"
        http DELETE "/tunnel=$(sid "$1")" >/dev/null; sleep 3 ;;
      *) LAST="refused: $f"; return 1 ;;
    esac
  done
  return 1
}
remove() { # key -> succeeds when the delete was taken
  local resp code; resp=$(http DELETE "/tunnel=$(sid "$1")"); code=${resp##*$'\n'}; LAST=${resp%$'\n'*}
  [[ "$code" == 2* ]]
}
removal_state() { # key leg -> lifecycle of that leg's teardown in removals
  curl -s --max-time 10 -H 'Accept: application/yang-data+json' \
    "http://127.0.0.1:$MDSC/restconf/data/salasim-te-ext:removals/removal=$(sid "$1")%2F$2" \
    | python3 -c "import json,sys;print(json.load(sys.stdin)['salasim-te-ext:removal'][0]['lifecycle-state'])" 2>/dev/null
}
if provision service; then ok "service accepted by the controller (and not refused)"; else bad "service refused or not accepted"; diag mdsc parent-pce pce-d1 pce-d2; fi
echo "     $LAST" | head -c 700; echo

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
# svc-1 crosses domains and is not protected: the MDSC lays it itself, one segment tunnel per domain at the PNCs
# (<service>.seg.<domain>), so the path is the two segments' EROs one after the other, each read at its domain PCE
SEG1="svc-1.seg.d1/primary"; SEG2="svc-1.seg.d2/primary"
cross_ero() { echo "$(ero "$D1" "$SEG1") $(ero "$D2" "$SEG2")" | xargs; }
want_cross_path() { # key: the two segments' EROs together hold the expected router ids in order
  local expected got; expected=$(J "' '.join(d['service']['$1'])"); got=$(cross_ero)
  echo "     ero (segments d1 + d2): ${got:-<none>}   expected: $expected"; [[ "$got" == *"$expected"* ]]
}
want_path() { # key [root port symbol]: the ERO holds the expected router ids in order
  local expected got; expected=$(J "' '.join(d['${2:-service}']['$1'])"); got=$(ero "${3:-$PARENT}" "${4:-$SYMBOL}")
  echo "     ero: ${got:-<none>}   expected: $expected"; [[ "$got" == *"$expected"* ]]
}

heard() { # service tunnel state: the MDSC read that state of the tunnel back from the PNC / PCE (YANG-push hint, then a read of the ietf-te state)
  "${COMPOSE[@]}" logs mdsc 2>&1 | grep -aq "tunnel state: $1/$2 is $3"
}
until_ 60 "the MDSC read that svc-1 (laid by itself across the domains) is active" heard svc-1 primary active

step "5. LSP up on the expected path (n1 n2 n3 n4 n5)"
until_ 60 "the segments together are on n1 n2 n3 n4 n5" want_cross_path expectedPathRouterIds || diag mdsc pnc-d1 pnc-d2 pce-d1 pce-d2

step "6. inject the fault (carrier down on n3 port 2 and n4 port 1)"
"$HERE/fault.sh" down && ok "medium accepted both carrier-down writes" || bad "fault injection failed"
until_ 60 "n3 logged [LINK-DOWN]" bash -c "${COMPOSE[*]} logs n3 2>&1 | grep -q 'LINK-DOWN'"
until_ 60 "n4 logged [LINK-DOWN]" bash -c "${COMPOSE[*]} logs n4 2>&1 | grep -q 'LINK-DOWN'"

step "7. recovery: transit failure -> signalling to ingress -> PCRpt DOWN -> the domain PCE reroutes its segment"
until_ 60 "domain PCE d2 received the failure report" bash -c "${COMPOSE[*]} logs pce-d2 2>&1 | grep -qiE 'PCRpt|report.*down|LSP.*down'"
until_ 60 "domain PCE d2 started a reroute of the segment inside its domain" bash -c "${COMPOSE[*]} logs pce-d2 2>&1 | grep -qiE 'reroute|recompute'"
until_ 120 "the segments avoid n3-n4 (n1 n2 n3 n5)" want_cross_path expectedPathAfterFaultRouterIds || diag mdsc pnc-d2 pce-d2 n3 n4

step "8. restore the link; LSP stays up on the new path"
"$HERE/restore.sh" >/dev/null && ok "carrier up written" || bad "restore failed"
sleep 5; want_cross_path expectedPathAfterFaultRouterIds && ok "path stable after restore (no flap)" || echo "  NOTE: path changed after restore (acceptable if the PCE re-optimised); inspect manually"

step "9. remove the service through the controller; removing it again answers removed"
if remove service; then ok "delete admitted"; else bad "delete not admitted"; diag mdsc parent-pce; fi
echo "     $LAST" | head -c 500; echo
gone() { ! get "http://127.0.0.1:$1/api/v1/pce/lsps" | grep -q "$2"; }
until_ 90 "the segments are gone from their domain PCEs" bash -c "$(declare -f get gone); ! (get http://127.0.0.1:$D1/api/v1/pce/lsps | grep -q '$SEG1') && ! (get http://127.0.0.1:$D2/api/v1/pce/lsps | grep -q '$SEG2')"
until_ 30 "the teardown of svc-1 is recorded as removed" bash -c "[[ \$($(declare -f http sid removal_state J); EXP='$EXP'; MDSC=$MDSC; CT='$CT'; DATA='$DATA'; removal_state service primary) == removed ]]"

step "10. a service inside one domain: MDSC -> the domain's PNC -> the domain PCE"
ISYMBOL=$(J "d['intraService']['symbolicPathName']")
if provision intraService; then ok "intra-domain service accepted"; else bad "intra-domain service refused or not accepted"; diag mdsc pnc-d2 pce-d2; fi
echo "     $LAST" | head -c 600; echo
until_ 60 "the MDSC was told through the PNC that svc-2 is active" heard svc-2 primary active
until_ 90 "the domain PCE holds the LSP on n3 n4 n5" want_path expectedPathRouterIds intraService "$D2" "$ISYMBOL" || diag pnc-d2 pce-d2
if remove intraService; then ok "intra-domain delete admitted"; else bad "intra-domain delete not admitted"; diag mdsc pnc-d2 pce-d2; fi
until_ 90 "the LSP is gone from the domain PCE" gone "$D2" "$ISYMBOL"
until_ 30 "the teardown of svc-2 is recorded as removed" bash -c "[[ \$($(declare -f http sid removal_state J); EXP='$EXP'; MDSC=$MDSC; CT='$CT'; DATA='$DATA'; removal_state intraService primary) == removed ]]"


# the MDSC's northbound facts (salasim-fact network-fact), as a client receives them: RFC 8040 notification stream, SSE
FACTS=/tmp/salasim-ref-facts.sse
stream_open() { # file seconds: subscribe to network-fact on the MDSC and record the stream into file in the background
  local s; s=$(restconf create-notification-stream '{"input":{"notifications":["(urn:salasim:fact?revision=2026-10-10)network-fact"]}}' \
    | head -n -1 | python3 -c "import json,sys;print(json.load(sys.stdin)['sal-remote:output']['notification-stream-identifier'])") || return 1
  rm -f "$1"; (timeout "$2" curl -s -N -H 'Accept: text/event-stream' "http://127.0.0.1:$MDSC/restconf/streams/json/$s" > "$1" 2>&1 &)
}
stream_has() { grep -q "$2" "$1"; } # file pattern

step "11. a protected service inside one domain: the controller chooses primary and standby"
restconf() { # rpc body -> the answer, then the HTTP status on the last line (RPCs of RESTCONF itself: the notification stream)
  curl -s --max-time 90 -w '\n%{http_code}' -X POST -H "$CT" -H 'Accept: application/yang-data+json' -d "$2" \
    "http://127.0.0.1:$MDSC/restconf/operations/sal-remote:$1"
}
stream_open "$FACTS" 600 && ok "subscribed to the MDSC's network-fact stream" || bad "could not subscribe to the MDSC's network-fact stream"
sleep 1
PSYM=$(J "d['protectedService']['primarySymbolicPathName']"); SSYM=$(J "d['protectedService']['standbySymbolicPathName']")
stored() { # column: what the controller stored for svc-3, in the core database's intent schema (lower case)
  "${COMPOSE[@]}" exec -T core-db psql -U postgres -d salasim_core -tAc "select $1 from intent.protection where service_id = 'svc-3'" | tr -d '\r'
}
mdsc_log_count() { "${COMPOSE[@]}" logs mdsc 2>&1 | grep -ac "$1"; }
queried() { # service-id leaf value: leaf is oper-status (up|down) or selected-tunnel (primary|standby) of the tunnel's state
  http GET "/tunnel=$1" | head -n -1 | python3 -c "
import json,sys
t=json.load(sys.stdin)['ietf-te:tunnel'][0]
leaf='$2'
if leaf=='oper-status':
    v=t.get('operational-state','').split('-')[-1]
else:
    v=next((p['name'] for k in ('primary-paths','secondary-paths') for p in t.get(k,{}).get(k[:-1],[]) if p.get('active')), '')
print(v); sys.exit(0 if v=='$3' else 1)"
}
carried() { # leg [minimum count]: the controller logged that the service moved to this leg
  [[ $(mdsc_log_count "protection: svc-3 is carried by the $1") -ge ${2:-1} ]]
}
if provision protectedService create-protected; then ok "protected service admitted (no tunnels in the request)"; else bad "protected service not admitted"; diag mdsc pnc-d2 pce-d2; fi
echo "     $LAST" | head -c 500; echo
until_ 90 "the primary is on n3 n4 n5" want_path expectedPrimaryPathRouterIds protectedService "$D2" "$PSYM" || diag mdsc pce-d2
until_ 90 "the MDSC was told that the standby is active (it is asked for only after the primary is)" heard svc-3 standby active
until_ 30 "the standby is on n3 n5, diverse from the primary" want_path expectedStandbyPathRouterIds protectedService "$D2" "$SSYM" || diag mdsc pce-d2
echo "     stored: phase=$(stored phase) selected=$(stored selected_tunnel)"
until_ 30 "the tunnel state on the MDSC: svc-3 is up" queried svc-3 oper-status up
queried svc-3 selected-tunnel primary >/dev/null && ok "tunnel state: svc-3 is carried by the primary" || bad "tunnel state: wrong selected tunnel"

step "12. the primary link fails: the controller moves the service to the standby"
"$HERE/fault.sh" down && ok "carrier down on the primary's link" || bad "fault injection failed"
until_ 60 "the MDSC was told that the primary of svc-3 is down" heard svc-3 primary down
until_ 30 "the service is carried by the standby" carried standby || diag mdsc pce-d2
until_ 30 "the MDSC's stream carried the standby's LSP facts (domain PCE -> PNC -> MDSC -> client)" stream_has "$FACTS" '"lsp-name":"svc-3/standby"'
until_ 30 "the MDSC's stream carried the controller's protection-selection fact for svc-3" stream_has "$FACTS" '"protection-selection":{[^}]*"service-id":"svc-3"'
until_ 30 "tunnel state: svc-3 is carried by the standby and still up" bash -c "$(declare -f http queried); EXP='$EXP'; MDSC='$MDSC'; CT='$CT'; DATA='$DATA'; queried svc-3 selected-tunnel standby && queried svc-3 oper-status up"

replays() { # the same tunnel written again is a replay: accepted and the primary path is not failed
  local resp; resp=$(http PUT "/tunnel=$(sid protectedService)" "$(mkbody protectedService create-protected)"); [[ "${resp##*$'\n'}" == 2* ]] && [[ -z "$(primary_failure protectedService)" ]]
}

step "13. the controller restarts while the standby carries the service: it continues from what it stored"
echo "     stored before the restart: selected=$(stored selected_tunnel) phase=$(stored phase)"
[[ "$(stored selected_tunnel)" == standby ]] && ok "the selection of the standby is stored" || bad "the selection was not stored"
"${COMPOSE[@]}" restart mdsc >/dev/null 2>&1 && ok "mdsc restarted" || bad "mdsc restart failed"
until_ 90 "the restarted controller loaded the service" bash -c "${COMPOSE[*]} logs mdsc 2>&1 | grep -a 'service(s) loaded' | tail -1 | grep -qv ' 0 service'"
until_ 60 "the MDSC is mounted to its PCEs again (a repeated provision answers as a replay)" replays
[[ "$(stored selected_tunnel)" == standby ]] && ok "still carried by the standby after the restart" || bad "the selection was lost by the restart"

step "14. the link comes back: the lost primary is asked for again, and after wait-to-revert the service returns to it"
"$HERE/restore.sh" >/dev/null && ok "carrier up written" || bad "restore failed"
until_ 120 "the service is carried by the primary again (wait-to-revert 10 s, revertive), decided by the restarted controller" carried primary || diag mdsc pce-d2
echo "     primary path now: $(ero "$D2" "$PSYM")   (n3 n5 would be the link the standby uses)"

step "15. remove the protected service: no tunnels listed, both legs go"
if remove protectedService; then ok "delete admitted"; else bad "delete not admitted"; diag mdsc pce-d2; fi
until_ 90 "the primary is gone from the domain PCE" gone "$D2" "$PSYM"
until_ 90 "the standby is gone from the domain PCE" gone "$D2" "$SSYM"
until_ 60 "the controller forgot the service (nothing of it left in the core database)" bash -c "[[ \$(${COMPOSE[*]} exec -T core-db psql -U postgres -d salasim_core -tAc \"select count(*) from intent.service where service_id = 'svc-3'\" | tr -d '\\r') == 0 ]]"
lifecycle_fact() { stream_has "$FACTS" "\"service-id\":\"svc-3\",\"revision\":\"[0-9]*\",\"event\":\"$1\""; }
for e in requested admitted deleting deleted; do
  until_ 30 "the MDSC's stream carried the service-lifecycle fact $e of svc-3" lifecycle_fact $e
done

echo
if [[ $FAIL -eq 0 ]]; then echo "RESULT: PASS"; else echo "RESULT: FAIL"; exit 1; fi
