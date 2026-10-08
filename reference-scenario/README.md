# Reference scenario

Two domains, five nodes, one cross-domain service, one link fault. It exercises the whole control path on real
protocols and no simulation vocabulary: link plane → device → PNC → MDSC → parent/domain PCE → PCEP/RSVP.

> **Status: written, not run.** The authoring sandbox cannot start Docker or bind ports. What *was* verified offline:
> every rendered file parses with the real loaders (`ReferenceScenarioFixturesTest` in pce, emulator, controller) and
> the link-plane medium answers in-process. Compose wiring, start-up timing, the log patterns `check.sh` greps and the
> JSON shapes it reads from the PCE API are unverified. Expect to adjust them on the first real run.

```
n1 -- n2 ==(inter-domain)== n3 -- n4 -- n5          d1 = {n1,n2}   d2 = {n3,n4,n5}
                             \_____________/          n3-n5 is the 5 ms detour
```

Service `ref/svc-1/tunnel/primary`, n1 → n5, 10 Mbit/s, AVG_DELAY. Expected path n1 n2 n3 n4 n5; after the fault on
n3–n4, n1 n2 n3 n5.

## Files
| File | Role |
|---|---|
| `scenario.json` | the only input: addresses, links, delays, service, fault |
| `render.py` | writes `build/`: per-domain topology (JSON, XML, RFC 9195 native), PNC/MDSC inventories, PCE and emulator configs, `docker-compose.yml`, `expected.json` |
| `medium_server.py` | link-plane medium (one `salasim_sim.linkplane` app per node) |
| `run.sh` | build images, render, start in order, wait for the composed topology, stop with `down` |
| `check.sh` | acceptance steps 1–9 (provisioning and removal go through the controller) |
| `fault.sh` / `restore.sh` | carrier down/up on both ends of n3–n4 through the medium |

## Run
```bash
./run.sh up          # needs docker and python3 (stdlib only); builds salasim/{emulator,pce,controller}:ref
./check.sh
./run.sh down
```
Faster re-runs: `./run.sh up --no-build`. Host ports (127.0.0.1): parent PCE 18080, d1 18081, d2 18082, medium 18099,
MDSC RESTCONF 18181, node APIs 18101–18105.

## Start order (why `run.sh` is staged)
medium → nodes → domain PCEs → PNCs + MDSC → **wait for `composed-topology.json`** → parent PCE. The parent reads the
composed topology file at start and has no way to receive it later; starting it earlier fails. That is a robustness gap
(the parent should accept the MDSC's `report-topology-change` against an empty start), recorded in the architecture doc.
Each PCE's actual-topology network id is `salasim:<domain>`.

## What `check.sh` proves
1. PCEs and emulators healthy. 2. PCEP sessions up (d1 ≥2, d2 ≥3). 3. MDSC composed the topology.
4. Parent accepts the cross-domain LSP. 5. ERO is n1 n2 n3 n4 n5. 6. Fault injected; n3 and n4 log `[LINK-DOWN]`.
7. Transit failure is signalled (PathErr) to the ingress, ingress reports PCRpt DOWN, parent reroutes.
8. ERO becomes n1 n2 n3 n5; restore does not flap the path.
On failure it prints the tail of the relevant container logs.

## Known soft spots
- Steps 5/7/8 find the ERO by scanning `GET /api/v1/pce/lsps` for the symbolic path name and `10.77.*` addresses; adjust
  `ero()` in `check.sh` if the payload nests differently.
- Log greps in step 7 are loose on purpose; tighten once real output is seen.
- Images build from the workspace root (`docker build -f <repo>/Dockerfile .`); clear stale `target/` dirs first.
