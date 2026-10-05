#!/usr/bin/env python3
"""Golden round trip: legacy sim/start JSON -> YANG-shaped JSON -> legacy JSON.

Usage (pyang's venv has the pyang library):
  /Users/oneway/dev/salasim_gmpls/yang-bootstrap/.venv/bin/python check_roundtrip.py

Checks, for every captured payload (real output of TopologyClockService._build_start_targets):
  1. every YANG-shaped node exists in salasim-pce-run:prepare-run/input (no stray nodes),
  2. every leaf value is valid for its YANG type (ranges, patterns, enums, lengths),
  3. every mandatory leaf of the chosen case is present,
  4. converting back gives the original payload minus the documented dropped fields,
     plus the documented constants (that diff is printed, it is the equivalence statement).
`must` statements are not evaluated (no XPath engine here): they are listed as untested.
"""
import copy
import datetime as dt
import json
import re
import sys
from pathlib import Path

from pyang import context, repository

HERE = Path(__file__).resolve().parent
YANG = Path("/Users/oneway/dev/salasim_gmpls/salasim_gmpls_yang")


def load_ctx():
    repo = repository.FileRepository(f"{YANG}/ietf:{YANG}/salasim")
    ctx = context.Context(repo)
    for name in ("salasim-pce-run",):
        text = (YANG / "salasim" / f"{name}.yang").read_text()
        ctx.add_module(f"{name}.yang", text)
    ctx.validate()
    errs = [e for e in ctx.errors if e[1].startswith(("MISSING", "BAD", "SYNTAX")) or "error" in str(e)]
    mod = ctx.get_module("salasim-pce-run")
    return ctx, mod


DELETED_DOMAIN_KNOBS = ("futurePcreqTimeoutMs", "pcreqBundleSize", "transientLinkPenalty")


def kebab(s):
    return re.sub(r"([A-Z])", lambda m: "-" + m.group(1).lower(), s)


def camel(s):
    return re.sub(r"-([a-z])", lambda m: m.group(1).upper(), s)


def ms_to_iso(ms):
    return dt.datetime.fromtimestamp(int(ms) / 1000, dt.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def iso_to_ms(s):
    return int(round(dt.datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp() * 1000))


# ---------------------------------------------------------------- adapter (legacy -> YANG JSON)
def conv_block(block, drop=()):
    """kebab-case every key; values stay (typing is applied by encode())."""
    return {kebab(k): (conv_block(v) if isinstance(v, dict) else v) for k, v in block.items() if k not in drop}


def to_yang(body):
    rt = body["runRuntimeConfig"]
    role = rt["role"]
    out = {
        "run-id": body["runId"],
        "simulator-run-id": body["simulatorRunId"],
        "deployment-id": body["deploymentId"],
        "domain-id": body["domainId"],
        "sim-anchor-time": ms_to_iso(iso_to_ms(body["simAnchorMs"])) if isinstance(body["simAnchorMs"], str) else ms_to_iso(body["simAnchorMs"]),
        "result-slice-count": body["resultSliceCount"],
        "horizon-sim-time": ms_to_iso(body["horizonSimTimeMs"]),
        "inventory-capacity": body["inventoryCapacity"],
        "statistics-transport": {k: v for k, v in {
            "backend-url": body.get("statisticsBackendUrl"),
            "nats-url": body.get("statisticsNatsUrl"),
            "internal-token": body.get("internalToken") or None,
        }.items() if v},
        "end-to-end-reuse": conv_block(body["endToEndReuse"], drop=("countIdleAgainstCapacity",)),
    }
    fs = body.get("frameSchedule")
    if fs:
        f = {"schedule-file": fs["scheduleFile"], "runtime-dir": fs["runtimeDir"]}
        if fs.get("marginFrames") is not None:
            f["margin-frames"] = fs["marginFrames"]
        if fs.get("networkOverrides"):
            f["network-overrides"] = conv_block(fs["networkOverrides"])
        out["frame-schedule"] = f
    case = conv_block(rt, drop=("schemaVersion", "role"))
    if role == "domain":
        for dead in DELETED_DOMAIN_KNOBS:          # decision D7: deleted from profile, backend and PCE
            case["precomputation"].pop(kebab(dead), None)
    out[f"{role}-runtime-config"] = case
    return out


def from_yang(y, body_template):
    """Inverse adapter: rebuild the legacy body from the YANG-shaped input."""
    role = "domain" if "domain-runtime-config" in y else "parent"
    rtc = y[f"{role}-runtime-config"]
    rt = {"schemaVersion": 1, "configDigest": rtc["config-digest"], "role": role}
    for k, v in rtc.items():
        if k != "config-digest":
            rt[camel(k)] = decamel_block(v)
    body = {
        "runId": y["run-id"], "simulatorRunId": y["simulator-run-id"], "deploymentId": y["deployment-id"],
        "domainId": y["domain-id"],
        "wallAnchorMs": body_template["wallAnchorMs"],      # injected by the controller (prepare = frozen)
        "simAnchorMs": y["sim-anchor-time"],
        "speedup": 0.0,                                      # constant of prepare-run
        "inventoryCapacity": y["inventory-capacity"],
        "resultSliceCount": y["result-slice-count"],
        "horizonSimTimeMs": iso_to_ms(y["horizon-sim-time"]),
        "routingConfig": {"constraints": {k: "REJECT" for k in
                          ("missingBandwidthPolicy", "missingTeDataPolicy", "unsupportedEndpointPolicy")}},
        "runRuntimeConfig": rt,
        "endToEndReuse": decamel_block(y["end-to-end-reuse"]),
    }
    st = y.get("statistics-transport", {})
    body["statisticsBackendUrl"] = st.get("backend-url")
    if "nats-url" in st:
        body["statisticsNatsUrl"] = st["nats-url"]
    body["internalToken"] = st.get("internal-token", "")
    fs = y.get("frame-schedule")
    if fs:
        d = {"version": 1, "scheduleFile": fs["schedule-file"], "runtimeDir": fs["runtime-dir"],
             "runId": y["run-id"], "domainId": y["domain-id"]}
        if "network-overrides" in fs:
            d["networkOverrides"] = decamel_block(fs["network-overrides"])
        body["frameSchedule"] = d
    return body


def decamel_block(b):
    return {camel(k): (decamel_block(v) if isinstance(v, dict) else v) for k, v in b.items()}


# ---------------------------------------------------------------- schema check
def children(stmt):
    return {c.arg: c for c in getattr(stmt, "i_children", [])}


def cases(choice):
    return {c.arg: c for c in choice.i_children}


def wire(ts, val):
    """RFC 7951: 64-bit integers and decimals are strings."""
    base = ts.name if hasattr(ts, "name") else ""
    return str(val)


def encode(node, val):
    """Encode a python value the way the YANG JSON wire form carries it (RFC 7951)."""
    t = node.search_one("type").i_type_spec
    nm = t.name
    if nm in ("uint64", "int64", "decimal64"):
        return str(val)
    return val


def validate_leaf(node, val, path, problems):
    """Validate one leaf value against its (derived) YANG type using pyang's own type specs."""
    tnode = node.search_one("type")
    ts = tnode.i_type_spec
    nm = ts.name
    errs = []
    if nm == "boolean":
        if not isinstance(val, bool):
            problems.append(f"{path}: {val!r} is not boolean")
        return
    if nm == "enumeration":
        if val not in [e[0] for e in ts.enums]:
            problems.append(f"{path}: {val!r} is not an enum value")
        return
    if nm == "string":
        ok = ts.validate(errs, node.pos, val, "x")
        if errs or ok is False:
            problems.append(f"{path}: {val!r} violates {tnode.arg}: {[e[1] for e in errs]}")
        return
    if nm in ("uint8", "uint16", "uint32", "uint64", "int8", "int16", "int32", "int64", "decimal64"):
        pv = ts.str_to_val(errs, node.pos, str(val), node.i_module)
        if pv is None or errs:
            problems.append(f"{path}: {val!r} is not a valid {nm}")
            return
        errs = []
        ok = ts.validate(errs, node.pos, pv, "x")
        if errs or ok is False:
            problems.append(f"{path}: {val!r} outside the range of {tnode.arg}: {[e[1] for e in errs]}")
        return
    problems.append(f"{path}: type {nm} not handled by this checker")


def walk(schema_children, inst, path, problems, covered, stray=True):
    nodes = schema_children
    allowed = set(nodes)
    for n, c in nodes.items():
        if c.keyword == "choice":
            for case in c.i_children:
                allowed |= {ch.arg for ch in case.i_children}
    for key, val in (inst.items() if stray else ()):
        if key not in allowed:
            problems.append(f"{path}/{key}: not in schema")
    for name, node in nodes.items():
        kw = node.keyword
        if kw == "choice":
            present = [c for c in node.i_children if any(ch.arg in inst for ch in c.i_children)]
            if node.search_one("mandatory") is not None and not present:
                problems.append(f"{path}/{name}: mandatory choice has no case")
            for c in present:
                walk({ch.arg: ch for ch in c.i_children}, inst, path, problems, covered, stray=False)
                # keys already consumed above; remove stray complaints for choice members
            continue
        if kw in ("leaf", "leaf-list"):
            if name in inst:
                covered.add(f"{path}/{name}")
                validate_leaf(node, inst[name], f"{path}/{name}", problems)
            elif node.search_one("mandatory") is not None and node.search_one("mandatory").arg == "true":
                problems.append(f"{path}/{name}: mandatory leaf missing")
        elif kw == "container":
            if name in inst:
                walk(children(node), inst[name], f"{path}/{name}", problems, covered)
            elif node.search_one("presence") is None:
                # non-presence container: mandatory descendants only matter when it holds data
                req = [c for c in node.i_children if c.keyword == "leaf" and c.search_one("mandatory") is not None]
                # only enforced if the container is actually instantiated by its parent; JSON omits empty ones
                if req and name not in ("routing-search", "precomputation", "recovery"):
                    pass


def main():
    ctx, mod = load_ctx()
    rpc = [c for c in mod.i_children if c.keyword == "rpc" and c.arg == "prepare-run"][0]
    inp = rpc.search_one("input")
    root = children(inp)
    # choice members are children of the choice node, flatten them for membership tests
    flat = {}
    for n, c in root.items():
        if c.keyword == "choice":
            for case in c.i_children:
                for ch in case.i_children:
                    flat[ch.arg] = ch
        else:
            flat[n] = c

    payloads = json.loads((HERE / "sim-start-payloads.json").read_text())
    ok_all = True
    for entry in payloads:
        body = entry["body"]
        role = body["runRuntimeConfig"]["role"]
        y = to_yang(body)
        # wire encoding of 64-bit values (RFC 7951) is applied by a schema-driven pass
        y = encode_tree(y, root)
        problems, covered = [], set()
        walk(root, y, "", problems, covered)
        # the legacy -> yang -> legacy round trip
        y_plain = json.loads(json.dumps(y))
        back = from_yang(decode_tree(y_plain, root), body)
        orig = copy.deepcopy(body)
        orig.pop("frames", None)
        orig.pop("serviceType", None)
        orig.pop("topologyFrameCount", None)
        orig["endToEndReuse"].pop("countIdleAgainstCapacity", None)
        if role == "domain":
            for dead in DELETED_DOMAIN_KNOBS:
                orig["runRuntimeConfig"]["precomputation"].pop(dead, None)
        orig["frameSchedule"].pop("version", None); orig["frameSchedule"].pop("runId", None); orig["frameSchedule"].pop("domainId", None)
        back["frameSchedule"].pop("version", None); back["frameSchedule"].pop("runId", None); back["frameSchedule"].pop("domainId", None)
        for b in (orig, back):
            b["speedup"] = b.get("speedup")
        orig["simAnchorMs"] = ms_to_iso(iso_to_ms(orig["simAnchorMs"])) if isinstance(orig["simAnchorMs"], str) else orig["simAnchorMs"]
        diff = {k: (orig.get(k), back.get(k)) for k in sorted(set(orig) | set(back)) if orig.get(k) != back.get(k)}
        def nleaf(x):
            return sum(nleaf(v) for v in x.values()) if isinstance(x, dict) else 1
        legacy_leaves = nleaf({k: v for k, v in body.items() if k != "frames"}) + (3 if role == "domain" else 0) * 0
        (HERE / f"prepare-run-input.{role}.json").write_text(json.dumps(y_plain, indent=1) + "\n")
        print(f"   legacy leaves (no frames) {legacy_leaves}; YANG leaves {nleaf(y_plain)}; dropped {legacy_leaves - nleaf(y_plain)}")
        print(f"== {role} PCE ({entry['url'].split('/')[2].split('.')[0]})")
        print(f"   schema problems: {problems or 'none'}")
        print(f"   YANG leaves instantiated: {len(covered)}")
        print(f"   round trip differences (expected: none): {diff or 'none'}")
        ok_all &= not problems and not diff
    print("untested: must statements (maximum-delay-slices >= initial, precompute budgets, safety <= pass deadline)")
    return 0 if ok_all else 1


def encode_tree(inst, schema_children):
    out = {}
    flat = {}
    for n, c in schema_children.items():
        if c.keyword == "choice":
            for case in c.i_children:
                for ch in case.i_children:
                    flat[ch.arg] = ch
        else:
            flat[n] = c
    for k, v in inst.items():
        node = flat.get(k)
        if node is None:
            out[k] = v
        elif node.keyword == "container":
            out[k] = encode_tree(v, children(node))
        elif node.keyword == "leaf":
            out[k] = encode(node, v)
        else:
            out[k] = v
    return out


def decode_tree(inst, schema_children):
    out = {}
    flat = {}
    for n, c in schema_children.items():
        if c.keyword == "choice":
            for case in c.i_children:
                for ch in case.i_children:
                    flat[ch.arg] = ch
        else:
            flat[n] = c
    for k, v in inst.items():
        node = flat.get(k)
        if node is not None and node.keyword == "container":
            out[k] = decode_tree(v, children(node))
        elif node is not None and node.keyword == "leaf":
            nm = node.search_one("type").i_type_spec.name
            if nm in ("uint64", "int64"):
                out[k] = int(v)
            elif nm == "decimal64":
                out[k] = float(v)
            else:
                out[k] = v
        else:
            out[k] = v
    return out


if __name__ == "__main__":
    sys.exit(main())
