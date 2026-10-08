#!/usr/bin/env bash
# Carrier down (or up) on both ends of the fault link, through the link-plane medium. Usage: fault.sh [down|up]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; EXP="$HERE/build/expected.json"
STATE="${1:-down}"
MEDIUM=$(python3 -c "import json;print(json.load(open('$EXP'))['hostPorts']['medium'])")
python3 - "$EXP" "$MEDIUM" "$STATE" <<'PY'
import json, sys, urllib.request
exp, medium, state = json.load(open(sys.argv[1])), sys.argv[2], sys.argv[3]
assert state in ("down", "up")
for node, port in exp["faultPorts"].items():
    req = urllib.request.Request(f"http://127.0.0.1:{medium}/{node}/ports/{port}", method="PUT",
                                 data=json.dumps({"carrier": state}).encode(), headers={"Content-Type": "application/json"})
    print(node, port, urllib.request.urlopen(req, timeout=10).read().decode())
PY
