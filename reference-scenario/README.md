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
| `check.sh` | acceptance steps 1–15 (provisioning and removal go through the controller; step 10 is a service inside one domain, MDSC → PNC → domain PCE; steps 11–15 are a 1:1 protected service `svc-3` n3 → n5: the controller chooses primary n3 n4 n5 and standby n3 n5, moves the service to the standby when the n3–n4 link fails (hold-off 0), back after wait-to-revert 10 s, is restarted while the standby carries the service and continues from its stored state, takes the service back to the primary after the link returns and wait-to-revert, and removes both legs without being given tunnel ids) |
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
medium → nodes → domain PCEs → PNCs + MDSC → **wait for `composed-topology.json`** → parent PCE. `run.sh` still stages
it this way, but the parent no longer needs to: it loads the composed topology file on the first request that finds it
(`ParentActualTopologyBootstrap.Source`), and until then answers 503 "no composed topology yet", which the MDSC retries.
(Before 2026-10-14 a parent started ahead of the file stayed without a topology and refused every report.)
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
- Steps 11–15: first run 2026-10-09 found four defects, all fixed (PNC had no `query-services`; PCRpt removal never reported the tunnel down; nothing re-established an LSP the PCC had removed; a late callback re-wrote the state file of a removed service). The restart step used to fault the link again after the restart, which cannot work: the revived primary can come back on the direct n3–n5 link, so it no longer rides n3–n4. Known race: after the fault the PCE also reroutes the primary, onto the direct link the standby uses, so the primary comes back `active` on a path that shares the standby's link; the controller keeps the service on the standby until the revert timer fires (step 13 then moves it back to the primary regardless). This is what the policy does; whether it is what a protected service should do is open.

- **2026-10-10 (system redesign v3, R2b/R3b; not run on a machine):** the controller now owns every service. The scenario's requests no longer list tunnels, deletes name only the service id, and the MDSC's state is read from the core database (`core-db`, intent schema) instead of the state files. Steps that changed: 4/10/11 (bodies), 13 (the stored selection and the load log line come from the database / `service(s) loaded`), 15 (the service is gone from `intent.service`, and the stream carried the service-lifecycle facts requested, admitted, deleting, deleted). The MDSC logs `lsp state: <device> <service>/<tunnel> is <state>` (it follows `lsp-state-changed` of `salasim-pce-lsp`), which is what `heard` greps. Unverified: the JSON shape of the service-lifecycle facts on the stream (the grep assumes `"service-id":..,"revision":..,"event":..` in that order), that the controller image builds with the PostgreSQL JDBC driver on the test machine, and the whole run.


- **2026-10-10 (P5d, not run on a machine):** the controller's northbound is `ietf-te` (RESTCONF on the MDSC), no longer `salasim-service-fleet`. `check.sh` writes a service as `PUT /restconf/data/ietf-te:te/tunnels/tunnel=<id>` (primary path, optionally a secondary path; bandwidth in bytes per second; metric order by `weight`), deletes it with `DELETE`, and reads the state from the same tree (`operational-state`, the path `active` leaf, the failure on the primary path, `removals` for a teardown). A refusal is no longer the answer of the write: it is the failed state of the primary path, which `provision()` polls for (and, for `controller-p*-unavailable`, writes the tunnel again). The steps that checked where a service was routed (`owner`/`pce` in the answer) are gone: `ietf-te` has no such leaf.

- **2026-10-10 (P7 S2, not run on a machine):** a service across domains that is not protected (svc-1, n1 to n5) is no longer an LSP of the parent PCE: the MDSC asks the PNCs of the source and destination domain for their segment costs, asks the parent PCE for the route (`compute-cross-domain-paths`), and writes one `ietf-te` tunnel per domain to the PNCs, named `svc-1.seg.d1` and `svc-1.seg.d2`. `check.sh` therefore reads the path as the EROs of the two segment LSPs one after the other, each at its domain PCE (`pce-d1`, `pce-d2`), and checks that both are gone after the delete. Protected services across domains are not covered (the protected scenario is inside one domain).
