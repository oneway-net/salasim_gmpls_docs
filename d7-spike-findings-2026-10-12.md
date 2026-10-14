# D7 spike: findings (2026-10-12)

Data: `docs/evidence/d7-2026-10-12/{report.md,results.json,run.log}`. One machine (Dell T440, 6 cores, 15 GiB, k3s v1.36.5 single
node, local-path storage), one run per size, 10, 20 and 30 devices, 3 domains, images built from the pushed heads of the repos on the
day. Nothing here is a repeat or a stress test; the numbers say what one idle machine does.

## What was measured

| devices | pods | provision (testbed layer) | load | reset + check | reload (another network) | clean |
|---|---|---|---|---|---|---|
| 10 | 19 | 30.2 s (first: image start) | 16.1 s | 38.3 s | 15.9 s | 16.5 s |
| 20 | 29 | 10.4 s | 27.1 s | 42.4 s | 27.7 s | 16.8 s |
| 30 | 39 | 11.2 s | 39.6 s | 46.7 s | 39.9 s | 17.1 s |

- Load is linear in devices: **4.0 s + 1.18 s per device** (r2 = 1.00 over three points: take the shape, not the third digit).
- Every reset passed all five checks on the first attempt; no pod restarted; nothing was left behind after cleaning a testbed.
- Reloading a *different* network into a reset testbed took the same time as the first load: nothing stays in the testbed that slows the next run.
- Memory per pod after 30 s settling (MiB): emulator 147, PNC 211, PCE 175, MDSC + parent PCE (one pod) 164, core database 67, link plane 36.
  30 devices in 3 domains come to about 5.5 GiB. **My earlier guess (over 300 MiB per device, so 66 would not fit in 15 GiB) was wrong.**
  66 devices should be about 11 GiB: possible on this machine only with nothing else running; 120 and 200 are not.

## What the numbers mean (and how sure I am)

1. **The slope of load time is mostly the platform's own waiting, not the cluster.** Devices start in parallel: the last device was ready at
   10.9, 18.6 and 26.8 s, but the load took 16.1, 27.1 and 39.6 s, 5 to 13 s longer. `runlayer.load` waits for deployments one at a time
   (`kubectl rollout status` per deployment). Waiting for them together should bring load to about the readiness of the slowest, roughly
   11, 19 and 27 s here. *Inferred from the timestamps; not yet tried.* This is the cheapest optimisation and changes no interface.
2. **A reset costs 38 to 47 s and barely grows with size: that looks like a fixed wait of about 30 s.** The likely cause is Kubernetes's default 30 s
   grace period for a pod whose process does not stop on SIGTERM (the emulator and PCE entry scripts run the JVM under `exec sh -c "java ..."`).
   *A hypothesis; not confirmed.* Check by timing the deletion of one pod and looking at what its main process is. If it holds, a shorter grace
   period (or the JVM as PID 1) takes the reset to a few seconds. Between two runs on one pooled testbed the cost today is reset + reload, about
   1 to 1.5 minutes for 10 to 30 devices; it is not charged to the user, but it is time a testbed is not available.
3. **The order of the optimisations in the design (section 9) follows the data:** wait for deployments together (devices are most of the pods
   and all of the slope), then the termination wait of reset; pre-pulled images and "standby processes" matter less (the image start of the first
   provision is 30 s once, then 10 s).

## What to change

- **Price book** (`SALASIM_PRICING`, `loadLowSeconds`/`loadHighSeconds`): the defaults, 120 and 600 s, are far above what was measured (16 to 40 s up to 30
  devices). For class S, 30 and 90 s cover what was seen with a margin. Class M cannot be measured here; the line puts 120 devices at about 145 s,
  but that is an extrapolation of three points. The sealing allowance (waiting for the event log) was not measured: the spike ran no event
  pipeline.
- **Quotas** (`PUT /api/v2/pools/<id>`): by pods, S (30 devices) needs 51 and M (120) 168 with a margin of 1.3. **Memory quotas cannot be used yet:
  the testbed's pods state no resource requests, and a quota on `requests.memory` would refuse them.** Add requests to the manifests; the memory
  column above is what to set them from (about 1.3 times the measured use: emulator 190 MiB, PNC 275, PCE 230, MDSC pod 215).
- **The spike's own report** flags the core database's volume as "left behind" after a reset. That is by design (the testbed's layer stays);
  only a volume left after a clean is a finding, and there was none. Fix the report so it does not cry wolf.

## Not measured

- Sealing and the event pipeline (no Debezium, Redpanda or ClickHouse here), and so the replication-slot item of the reset checklist.
- Anything above 30 devices, repeat runs, runs with other load on the machine, and the cost of the first run on a cold node (images were already imported).
- CPU: memory was read; CPU use during load was not.

## On the way: what the machine and the repos taught

- `docker build` of the PCE and controller images **did not work at the pushed heads**: both depend on `net.salasim:salasim-model`, which neither Dockerfile built.
  Fixed in both (pushed). **The `salasim_gmpls_model` directory was in no git repository at all**; it is now one, with a single commit, held only on the test machine
  and in the local directory. It needs a home (a GitHub repository), or the shared value objects exist in one working copy and one machine.
- Docker Hub, PyPI and GitHub are slow or blocked from the machine: images build from `docker.m.daocloud.io`, the platform image now installs from a
  pip mirror, and a `git pull` of a few commits takes minutes.
- k3s installed from the Rancher mirror without a hitch; `cluster.sh` worked as written. The reference scenario's 11 containers (running since 10-09) were stopped to free memory.

## After the first two changes (same day, same machine, same sizes)

Evidence: `docs/evidence/d7-2026-10-12-concurrent-waits/` (change 1 only) and `docs/evidence/d7-2026-10-12-after/` (both changes).
One run each; the second full run differs from the first by about 2 s in places, so read differences under that as noise.

| devices | load before | load, waits together | load, both changes | reset before | reset after |
|---|---|---|---|---|---|
| 10 | 16.1 s | 11.8 s | 13.3 s | 38.3 s | 14.9 s |
| 20 | 27.1 s | 21.1 s | 20.9 s | 42.4 s | 25.6 s |
| 30 | 39.6 s | 33.0 s | 33.6 s | 46.7 s | 33.7 s |

- **Waiting for the deployments together** saved about 4 to 7 s of every load (`Kubectl.rollout_all`, the tests count waits in flight). **It did not
  remove the slope: about 1.0 s per device remains.** I had read the slope as the platform's own sequential waiting; it was only partly that. With
  the waits together, loading takes as long as the last device takes to be ready (32.4 s against 33.0 s at 30 devices), and devices become ready
  about a second apart: 30 JVMs starting on 6 cores (the machine's load average was 30 to 40 during a load). The rest of the slope is CPU, not waiting.
- **The reset's fixed wait was the pod grace period, as suspected, and is gone.** Confirmed on the machine: in the image's `dash`, `exec sh -c "sleep 300"`
  leaves `sh -c` as the container's main process and `docker stop -t 30` takes 30.5 s, because the stop signal does not reach the child. The
  emulator and PCE entry scripts were written that way. With `java` as the main process (`exec java ...`) a 10-device reset takes 15 s instead of 38.
  **What is left of the reset grows by about 1 s per device (15, 26, 34 s): deleting that many pods and waiting for them.** The controller's entry script
  already used `exec java`.
- **Resource requests are on every container** (`manifests.REQUESTS`) and a class's quota is sized from them (`manifests.quota_for`: for 30 devices in 3
  domains about 8.8 GiB of requests, 51 pods, a margin of 10 %). Not yet run on the cluster with a quota applied: the spike applies none.
  The requests of the parent PCE are a guess (the spike read only the first container of the core pod).
- **The cluster itself fell over once.** During the first run after the change, at 30 devices, k3s exited: its SQLite datastore (kine) logged a slow
  compaction and then `Transaction commit failed`, a fatal error; systemd restarted it 5 s later and the spike's reset failed with connection refused.
  The rerun was clean. This is a property of a single-node k3s on SQLite under a start storm with a load average above 30, not of the platform;
  a cluster for pools should use etcd or an external database, and the platform's load and reset should tolerate an API outage of seconds
  (today they raise, and the run fails).

### What the cycle between two runs costs now

Reset + reload of a pooled testbed: about 26 s for 10 devices, 47 s for 20 and 70 s for 30, from 54, 70 and 87 s. It is the platform's cost, not the user's.

### Done with them

- The price book now has load allowances per class (`DEFAULT_LOAD_SECONDS`, `SALASIM_PRICING` `loadSeconds`): S 20 to 90 s (measured, with a margin), M 60 to 300 s
  and L 120 to 600 s (extrapolated, not measured). A quote for a 30-minute scenario at speed-up 10 on class S is now 4 to 9 credits (it was 6 to 17).
