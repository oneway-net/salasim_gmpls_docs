"""Capture REAL sim/start payloads from the backend builder (read-only use of the repo)."""
import json, sys, os, tempfile
from pathlib import Path
os.environ["SALASIM_PCE_FRAME_SOURCE"] = "schedule"
from salasim_backend.configuration_defaults import resolve_composed_configuration, resolve_configuration
from salasim_backend.runtime_store import RuntimeStore
from salasim_backend.topology_clock_service import TopologyClockService
dep = resolve_composed_configuration("deployment")
sim = resolve_configuration("simulation")["resolvedConfig"]
run = resolve_composed_configuration("simulation-run")
tmp = Path(tempfile.mkdtemp())
d = "dep-golden"
k8s = tmp / d / "k8s"
core = k8s / "core" / "runtime"; dom = k8s / "sat-1" / "runtime"
for p in (core/"schedules", dom/"schedules", core/"parent-topology", dom/"topology"): p.mkdir(parents=True)
(k8s/"namespaces.json").write_text('{"core":"dep-golden-core","domains":{"sat-1":"dep-golden-sat-1"}}')
(core/"schedules"/"parent-schedule.json").write_text('[{"index":0,"timestamp":"2026-07-04T05:43:00Z","parent_topology_json_file":"parent-0.json"}]')
(dom/"schedules"/"topology-schedule.json").write_text('[{"index":0,"timestamp":"2026-07-04T05:43:00Z","topology_json_file":"domain-0.json"}]')
(core/"parent-topology"/"parent-0.json").write_text('{"nodes":[],"links":[]}')
(dom/"topology"/"domain-0.json").write_text('{"nodes":[],"links":[]}')
svc = TopologyClockService(RuntimeStore(tmp/"runtime.db"), tmp)
rr = run["resolvedConfig"]
targets = svc._build_start_targets(
    d, run_id="dep-golden-run-1783143780000", simulator_run_id="simrun-0123456789ab",
    wall_anchor_ms=1783143780000, sim_anchor_ms=1783143780000, speedup=0.0,
    push_config={"initialFrames": 4, "capacity": 8, "stabilityWindowFrames": 3, "resultSliceCount": 20,
                 "topologyFrameCount": 24, "horizonSimTimeMs": 1783143780000 + 6_000_000},
    statistics_backend_url="http://backend.salasim:30081",
    statistics_nats_url="nats://nats.dep-golden-core.svc.cluster.local:4222",
    network=sim["networkDefaults"],
    routing_config=rr["routing"], end_to_end_reuse=sim["endToEndReuse"],
    deployment_config=dep["resolvedConfig"], deployment_config_digest=dep["configDigest"])
out = [{"url": u, "body": b} for u, b in targets]
json.dump(out, sys.stdout, indent=1)
