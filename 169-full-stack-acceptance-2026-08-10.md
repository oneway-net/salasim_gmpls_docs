# 169 full-stack acceptance checkpoint — 2026-08-10

Scope: `walker-10x20-50-gs-1000-1-1-concurrency` on `10.112.140.169`.

## Deployed baseline

- Topology: `ddb42d0`.
- Emulator: `92dda8f`.
- Dedicated PCE and emulator image tag: `goal169-20260810`.
- Target PCE and emulator workloads were rolled by deleting only the target
  emulator Pods; all 262 target Pods subsequently became Ready.
- Backend listener used for this acceptance: `http://10.112.140.169:30081`.
- Parent PCE reroute fix deployed as `4c47c44` in image
  `127.0.0.1:32000/salasim/pce:goal169-20260810-r1`
  (`sha256:73304ee1e23e07ac242ffed53d33fb955b87b082778911d7bf0438bee37fe889`).
- Backend delete-path fix deployed as `511a536` on the host-run backend at
  port `30081`; focused regression suite: `116 passed`.
- Backend configuration-timeout fix `76a8757` is deployed on port `30081`.
  Its focused compiler/configuration suites: `55 passed`.

## Smoke findings

The first 20-service protected MPLS smoke run created 40 parent-owned LSPs,
but the backend's runtime-LSP view was empty. The deployment metadata still
pointed `backendCallbackBaseUrl` at historical port `30010`, while the
acceptance backend is on `30081`. The target deployment metadata was updated
to `http://10.112.140.169:30081` after stopping the diagnostic run.

A second 20-service run then demonstrated the repaired statistics path:

- 20 services and 40 protected tunnels were created.
- PCE webhook facts reached the runtime-LSP projection and were joined to
  service tunnel instances (including ERO and snapshot index).
- Parent PCE and backend active-LSP views agreed while the first snapshot was
  stable.

The second run is not an acceptance pass. At the first topology transition,
several fresh end-to-end paths crossed a different set of child domains. The
parent attempted PCUpd, which cannot add or remove child-domain segments, and
the affected tunnels subsequently reported `child-update-failed`. The run was
stopped to prevent further recovery-queue load.

## Historical gate (resolved)

Before the subsequent r2 rollouts, the 100- and 500-service stages were held
until the PCE acknowledgement hierarchy was corrected and the 1x smoke had
been repeated. The later sections record that resolution and the completed
load stages.

## Latest 1x smoke: `simrun-a3f09736dcc1` (failed gate)

Configuration: 20 services, 10 Mbps, MPLS, bidirectional, dedicated link-diverse
1+1 protection, seed `16920`, 20 × 300-second slices, speedup `1`.

- At snapshot 0, all 20 services created their 40 parent-owned MD-LSPs.
- At snapshot 1, `4c47c44` took the intended ownership-change branch for
  `svc-bf572ee6…/tun-2d53…`: both `rebuilding child-domain ownership` and
  `rebuilt child-domain ownership` were logged; no old
  `PCUpd cannot change segment ownership` message was observed for it.
- A manual, frozen-snapshot lifecycle probe succeeded.  Deleting UP service
  `svc-e1f847…` completed two parent-owned teardown operations, removed both
  symbolic MD-LSPs, and changed the parent active-MD-LSP count from `19` to
  `17`.  Recreating the same endpoints and protection settings as
  `svc-294c213…` completed both create operations, produced two active,
  link-diverse tunnels, and restored the count to `19`.
- The dynamic smoke nevertheless failed its gate: after the snapshot-1
  transition, the original 20 services were `7 UP / 5 DEGRADED / 8 DOWN`
  (the explicit deletion and recreated probe are excluded from that original
  workload accounting).  Parent queue work drained, but 13 failed paths were
  parked for recovery.
- This is not explained solely by global physical disconnection: snapshot 1
  contains one 250-node connected component.  Several DOWN/DEGRADED
  satellite-to-satellite services have two directed edge-disjoint paths in
  both directions in the physical scene, yet their child PCE requests log
  `topoReachable=false` with adequate bandwidth.  Other ground/satellite
  pairs genuinely lack a reverse or a second edge-disjoint path and are
  separately classified as topology constraints.
- The remaining reproducible symptom is now narrowed to a PCUpd acknowledgement
  timeout inversion, rather than parent/domain path selection or the already
  fixed ownership rebuild.  For parent MD-LSP `18`, the parent successfully
  computed and split the new ERO into all three child segments.  The source
  Emulator (`emulator-sat-6-16`, router `10.64.1.71`) received the PCUpd at
  `18:48:04`, but its make-before-break task is allowed to wait `85s` for RSVP
  establishment.  The child PCE expired the PCC acknowledgement at `65s` and
  the child-to-parent relay at about `70s`, before the Emulator reported its
  eventual MBB outcome at `18:49:29`.  That early expiry forced an erroneous
  child failure upstream and discarded the later correlated PCRpt.
- `76a8757` makes the tier explicit and validates it during deployment
  compilation: Emulator RSVP setup `85s` < domain PCC ack `95s` < domain relay
  `100s` < parent child-update wait `110s`.  It preserves the existing target
  precomputed-route worker setting (`13`) when the target manifest is
  regenerated.  Do not start the 100- or 500-service stages until that
  configuration is active and the 1x smoke is repeated.
- The failed run was then stopped with `op-c5c22fe62327`: parent PCE active
  MD-LSPs, recovery queued/active/pendingDistinct, and the target active run
  identifier all reached zero/null before further diagnosis.

## Second 1x smoke: `simrun-de97e0db5975` (failed gate; stopped)

The timeout hierarchy from `76a8757` was active: the domain logs resolved
PCC acknowledgement at `95s`, relay at `100s`, and parent child-update wait
at `110s`. Snapshot 0 established all 20 services, 40 protected tunnels, and
40 runtime LSPs successfully. At snapshot 1, the ownership-change rebuild
again completed for the affected MD-LSPs, so that branch remains verified.

The reroute gate still failed (`7 UP / 13 DEGRADED`). The remaining concrete
failure was different from the timeout ordering: `pce-ground-walker` sent two
distinct PCUpds for the same stable child PLSP while its first make-before-break
replacement was in flight. The Emulator's prior dedupe path silently dropped
the second request, so its SRP correlation had no PCRpt and expired after about
100 seconds. The run was stopped with `op-930af1befb70`; simulation state is
`stopped`.

## Emulator duplicate-PCUpd repair and rollout

- Emulator commit `836a7b6` (`Acknowledge duplicate in-flight PCUpd`) replaces
  the silent duplicate drop with an immediate keep-current PCRpt on the same
  SRP. This preserves the live old LSP and tells the child PCE that the second
  update was not applied instead of leaving it pending until timeout.
- Focused Emulator verification: `mvn test -Dtest=LspIdentityTest`, **6/6
  passed**. The commit is pushed to `origin/k8s-deploy`.
- Target image: `127.0.0.1:32000/salasim/emulator:goal169-20260810-r2`, digest
  `sha256:c816c91ecc2f4dd10fa2dd3fd0960977152ce96d36430ec809de0f39d045fd05`.
- Only the 11 target Emulator StatefulSets were changed. After exact image and
  namespace checks, old target Emulator Pods were deleted and recreated in
  parallel (250 replicas total); no PCE or non-target workload was deleted.
  All 250 are Ready on the new tag and the StatefulSet strategy was restored
  to `RollingUpdate`.
- Post-roll PCEP health is stable: parent `11` sessions; ground domain
  `51/50` (parent plus 50 PCCs); each of the ten satellite domains `21/20`
  (parent plus 20 PCCs).

Historical gate (resolved): repeat the fixed 20-service, 1x smoke before
starting 100 or 500 services. Its primary observation was whether duplicate
in-flight PCUpds receive immediate correlated rejection rather than
pending-ack expiry.

## Third 1x smoke: `simrun-1810344da046` (failed gate; stopped)

Snapshot 0 passed completely: **20/20 UP** services, **40/40** protected
tunnels, and **40/40** runtime LSPs. Snapshot 1 exposed a second, independent
ownership condition: a child domain stayed in the MD-LSP domain set but the
computed segment moved from one ingress PCC to another. The existing test
only compared domain sets, so it sent PCUpd to the old PCC. That PCC correctly
returned an immediate ERO mismatch (`PCUpd NOT APPLIED`) rather than the old
long pending-ack timeout. The run was stopped with `op-28999f24e697` at
snapshot 1 after the gate reached `17 UP / 3 DOWN`.

## Child-segment endpoint ownership repair

- PCE commit `58bf535` (`Rebuild MD-LSP when child segment endpoint changes`)
  expands `requiresOwnershipRebuild`: a changed child-domain ingress or egress
  now requires controlled rollback and PCInitiate rebuild even when the set of
  domains is unchanged. This preserves the rule that PCUpd only updates the
  LSP delegated to its current PCC.
- Focused PCE verification: `mvn test -Dtest=InterDomainLspUpdateHelperTest`,
  **4/4 passed**, including the new same-domain/different-ingress regression.
- Target PCE image is now
  `127.0.0.1:32000/salasim/pce:goal169-20260810-r2`, digest
  `sha256:d8983ea7b06fae0577e5366555d4783bbe9a4fedb87d85fa64bb9284dc004cbc`.
  All 11 target parent/domain PCE Pods are Ready on r2.
- During the PCE rollout, some sat-6 and sat-8 Emulator first connections
  raced the new PCE listener and did not retry. Only those two target domains'
  40 r2 Emulator Pods were restarted; both are now 20/20 Ready and each PCE
  reports 21 sessions (parent plus 20 PCCs).

Historical gate (resolved): restart the fixed 20-service, 1x smoke and require
both the snapshot-0 20/20/40 baseline and snapshot-1 ownership rebuild to
succeed before the 100-service stage.

## Fourth 1x smoke: `simrun-74aeb194f9b4` (passed; cleanly stopped)

This repeat used the fixed 20-service, bidirectional MPLS 1+1 protected
workload (`seed=16920`, speed `1x`). It is the first full smoke after both the
duplicate-PCUpd and child-segment-endpoint repairs:

- Snapshot 0 established **20/20 UP** services, **40/40** protected tunnels,
  and **40/40** runtime LSPs.
- Snapshot 1 remained **20/20 UP** with all 40 tunnels active and all 40 LSPs
  present.
- Snapshot 2 remained **20/20 UP**, 40 active protected tunnels, and 40
  runtime LSPs. Parent logs explicitly record multiple cases of a child-domain
  segment endpoint changing, followed by `rebuilding child-domain ownership`
  and `rebuilt child-domain ownership`; no `child-update-failed` was observed
  in the inspected reroute interval.
- The run was stopped with `op-a158b49b34c4`, which completed all 3/3 steps.
  After stop, PCE recovery was clean (`queued=0`, `active=0`,
  `pendingDistinct=0`, `oldestWaitMs=0`). The recorded run remains available
  for inspection with 20 UP services, 40 tunnels, and 40 LSP records.

## Stage 1 checkpoint — PASS

The 1x gate is satisfied: baseline setup, bidirectional 1+1 protection, and
actual child-domain endpoint migration all completed without service loss. The
earlier targeted lifecycle probe additionally exercised controlled teardown
and recreation. The subsequent 100-service bidirectional 1+1 run at 3x used
new, explicitly recorded profiles and passed the same zero-pending-residual
check before the 500-service stage.

## 100-service, 3x stage: `simrun-99d01d488ef3` (passed; cleanly stopped)

- Dedicated profiles: simulation `profile-0333632ddbf0426a`
  (`goal169-100-3x`) and workload `profile-4caba96fd11b410b`
  (`goal169-100-protected-10m`), with deterministic seed `169100`.
  They specify 100 bidirectional MPLS 1+1-protected 10 Mbps services and a
  3x, 20-slice simulation.
- Start operation `op-e035cd99cb0d` completed 3/3 steps. At the initial
  convergence checkpoint: **100/100 UP**, **200/200 protected tunnels**, and
  **200 LSPs**; no failed operation and PCE recovery was all zero.
- At active snapshot index **2**, all 100 services remain UP and all 200
  tunnels/LSPs remain present. The PCE recovery summary is still all zero.
  Parent logs show the repaired endpoint-change detection and controlled
  ownership rebuilds during the transition, with no `child-update-failed` in
  the inspected interval.
- Frontend comparison, performed against the same deployment/run, agrees with
  the API: the deployment panel shows **262/262 Ready** and **262 Running**
  Pods; the Services page shows 100 total, 0 degraded, 0 rerouting, 0 failed,
  100 working tunnels plus 100 protection tunnels, and 0 pending. It also
  displays nonzero per-service reroute counters while their states remain UP.

- The stage was stopped with `op-c371915fe85a` (3/3 steps). The active run
  identifier cleared and the backend recovery summary returned all zeros
  before the 500-service stage was started.

## 500-service, 3x stage: `simrun-04dd0531d8b2` (passed; cleanly stopped)

- Dedicated workload profile `profile-2bb38f34196a43db`
  (`goal169-500-protected-10m`) uses 500 bidirectional MPLS 1+1-protected
  10 Mbps services with deterministic seed `169500`; it reuses the recorded
  3x simulation profile. Start operation `op-f03542db156e` completed without
  error.
- The initial batch briefly exercised recovery pressure (peak observed 233
  queued and 64 active), then converged at snapshot 2 to **500/500 UP**,
  **1,000/1,000 protected tunnels**, and **1,000 LSPs**, with no failed
  operation.
- Snapshot 3 triggered a second observed burst (243 queued/64 active), which
  again drained to zero. At the settled snapshot-3 checkpoint all 500 services
  stayed UP, all 1,000 tunnels/LSPs remained present, and recovery plus
  pending-distinct counts were zero.
- Frontend comparison on the exact same run shows 500 normal, 0 degraded,
  0 rerouting, 0 failed, 500 working plus 500 protection tunnels, and 0
  pending. The UI's service rows also show completed reroute counts while
  retaining `UP` state.
- Stop operation `op-f8b2f3a5b202` completed all 3/3 steps. Final checks show
  `activeRunId=null`; backend recovery (`queued`, `active`, `pendingDistinct`,
  `queuedGroups`) is all zero; and the parent PCE directly reports zero total,
  retry, reroute, and recovery queues.

## Interim result — passed observed snapshots 0–3 (superseded by final run below)

The target workload passed the staged interim scope: 20-service 1x smoke,
100-service 3x, and 500-service 3x all established bidirectional 1+1
protection, survived observed topology-driven reroutes, and were stopped with
no residual work. Frontend and API comparisons agree for the 100- and
500-service stages. These intentionally bounded runs do **not** satisfy the
final criterion of running all 21 snapshots and observing terminal convergence;
the complete final run and the remaining six-page/four-layer evidence are
still required before a final PASS can be declared.

## Complete 500-service timeline: `simrun-41c9f391ab33` (terminal evidence)

The final run reuses simulation profile `profile-0333632ddbf0426a` and
workload profile `profile-2bb38f34196a43db`: 20 × 300-second slices at 3x,
deterministic seed `169500`, and 500 bidirectional MPLS 1+1-protected 10 Mbps
services. Start operation `op-8066d3379307` completed successfully.

- The live clock advanced through snapshots 0–20 and froze at snapshot **20**.
  Throughout sampled snapshots 0–19, all 500 services remained UP. Recovery
  bursts at snapshot transitions drained to zero before the next checkpoint.
- At the frozen terminal watermark: backend service projection reports
  **500/500 UP**, **1,000** protected tunnels, and **1,000** LSPs; parent PCE
  raw `/lsps` also reports **1,000**, while both backend recovery and parent
  queue report zero queued, active, pending-distinct, retry, and recovery work.
- The final complete statistics slice is index 19 (the frozen index 20 is the
  terminal timeline marker rather than an additional statistics slice): it
  reports 500 UP/0 DOWN, 1,000 ACTIVE/0 DOWN tunnels, zero overbooked links,
  0.2167% network utilization and 51.0% maximum link utilization.
- Target workload Pod audit: **262/262 Ready**, zero non-Running Pods, zero
  CrashLoopBackOff/OOMKilled cases, and zero accumulated container restarts.
  Parent PCE health reports 11 parent/domain sessions and all domain PCE health
  endpoints report `up`.

## Terminal frontend regressions found and repaired

Two real consistency defects were found during the required browser pass:

- Frontend `e724071` (`Preserve terminal run scope in Mission Control`) fixes
  Mission Control treating a URL-selected frozen run as an unscoped idle scene.
  Before the fix it showed no selectable services/0 paths; after the 30080-only
  redeploy, the same URL shows the selected `simrun-41c9f391ab33`, 500 services
  and 500 active paths with no browser console warnings/errors.
- Backend `96a02bf` (`Scope operation alarms to their simulator run`) fixes
  `/ops/alarms` attaching historical runless lifecycle failures to every
  later selected run. Focused regression test: `2 passed`. After the 30081-only
  redeploy, direct API and the Alarms page both show 0 critical and 0 warning
  alarms for the terminal run.

At the common frozen watermark, the Services page, the frontend `/api` proxy,
the backend API, and parent PCE raw facts all agree on 500 UP services and
1,000 LSP/tunnel instances. Analytics loads the 20 complete slices without an
empty chart (last complete slice 19 as described above); Operations displays
the selected run's completed LSP-create history; the Alarms page loads in under
8 seconds with zero events. A captured Services screenshot shows the frozen
run scope, 500 normal, zero degraded/rerouting/failed/pending, and 500 working
plus 500 protection tunnels.

## Final read-only recheck and ownership-change semantics

The final recheck at the frozen run watermark recorded `paused` at active
snapshot 20, 500 services with **0 non-UP**, 1,000 tunnels with **0
non-ACTIVE**, and zero incomplete tunnel paths or child-domain segments. The
backend recovery summary and the parent PCE raw queue independently report
zero queued/active/pending work; the target-only Pod audit remains 262 Ready,
zero non-Running, and zero restarts. The selected-run alarm API returns zero
active alarms.

The deployed PCE commit is `58bf535`, which is descended from `4c47c44`
(`Rebuild MD-LSPs when child domain ownership changes`). The latter's
`InterDomainLspUpdateHelper.requiresOwnershipRebuild()` compares the new
ERO-derived domain set to the established `domainLSPIDMap` set. A mismatch
prevents a PCUpd from being issued against the old ownership set; reroute is
instead routed to the serialized rollback-and-reinitiate path. This is the
expected protocol behaviour: a PCUpd updates a delegated LSP but must not
silently create a child segment in a newly entered domain or leave one in a
departed domain. The terminal API recheck confirms every resulting child
segment is complete.

Deployed component revisions: topology `ddb42d0`, emulator `836a7b6`, PCE
`58bf535`, protocols `5be487f`, backend `96a02bf`, frontend `e724071`.
The English Services view was also checked against the Chinese view at the
same URL-selected frozen run; both show the same 500 UP services, 500 working
tunnels, 500 protection tunnels, and zero degraded/rerouting/failed/pending
counts, with no browser console warning or error.

## Four-layer terminal reconciliation

All values below use deployment
`walker-10x20-50-gs-1000-1-1-concurrency`, run
`simrun-41c9f391ab33`, terminal watermark snapshot 20 (and the final complete
statistics slice 19).

| Metric | Frontend display | Frontend API proxy | Backend/database | PCE/Emulator | Consistent |
| --- | --- | --- | --- | --- | --- |
| Service state | 500 UP; no degraded/rerouting/failed/pending | 500 services, 0 non-UP | `service_current`: 500 UP | Parent has the corresponding active MD-LSP population | Yes |
| Protection tunnels | 500 working + 500 protect | 1,000, 0 non-ACTIVE | `tunnel_current`: 1,000 ACTIVE, 1,000 complete paths | Parent `/lsps`: 1,000 | Yes |
| Terminal work | page has no pending condition | selected-run alarms: 0 | recovery queued/active/pending/groups: all 0 | parent queue, pending reroutes, recovery, and PCUpd awaiting acknowledgement: all 0 | Yes |
| Timeline/statistics | Analytics selects final complete slice 19 | response is scoped to the frozen run | `simulation_slice_results`: 20 COMPLETE | 12 PCE snapshot batches at slice 19 | Yes |
| Bandwidth/accounting | Analytics max utilization 51% | same scoped runtime data | 12 PCE metrics batches; 1,108 reported links | 0 overbooked, 0 invalid sampled reservations, max utilization 51%; Emulator sample carries 10 Mbps, bidirectional, delegated symbolic LSP | Yes |

The frontend proxy returned the terminal service payload with the same
**5,744,735-byte** body as the backend (`200` from both endpoints; 0.107 s
through port 30080 and 0.057 s directly from port 30081). The Mission Control
registry uses one shared 20-second refresh loop rather than a loop per
consumer; tunnel-history requests remain lazy, only after a user opens a
specific tunnel's lineage panel. This rules out an automatic 500-service
history N+1 request pattern in the tested pages. Browser console checks on all
six core pages found no warning or error.

The SQLite operation aggregate for the same run records **1,000/1,000**
`lsp-create` operations completed, 500/500 `service-batch-job` operations
completed, and one completed simulation-control operation, with no other
failed operation status for this run. This supplies the denominator for the
initial protected-tunnel establishment result.

## First-run verdict — superseded by second-round audit

All staged gates passed after the documented, minimal PCE, Emulator, backend,
and frontend fixes. The terminal 500-service run completed the full timeline,
remained converged well beyond the required ten-minute window, and its UI,
frontend proxy, API, SQLite projections, parent/domain PCE facts, and
Emulator facts agreed at the aggregate watermark. The second-round audit below
adds a per-link feasibility check that was absent from this first verdict and
therefore supersedes the unconditional PASS.

## Second-round audit: `simrun-7771bd447196` — strict acceptance FAIL

The repeat used the same recorded 500-service workload and routing/simulation
profiles. It reached snapshot 20 at `2026-08-10T00:52:34Z`; more than ten
minutes later it still reported 500/500 services UP, 1,000/1,000 tunnels
ACTIVE, 1,000 backend LSPs, zero failed operations, zero backend/parent queues,
zero PCUpd acknowledgements pending, and 262/262 target Pods Ready with no
restart. Backend and parent PCE also have identical 1,000-entry symbolic-name
sets and identical `(symbolicName, tunnelId, extendedTunnelId)` identity sets.

The stricter terminal path-to-topology reconciliation nevertheless found a
real false-UP condition:

- 11 ACTIVE tunnels belonging to 11 services still reference 14 directed
  links that do not exist in the authoritative snapshot-19 topology; six of
  the affected tunnels are primary and five are standby.
- All 11 generations were established at snapshot 1 (`pathRevision=1`) and
  never rerouted. Parent PCE raw `/lsps` retains the same obsolete EROs with
  `pathState=1`, so this is not merely a frontend rendering problem.
- Recomputing reservations from all backend tunnel paths gives 229.8 Gbps of
  directed hop-bandwidth. A bidirectional ledger would therefore account for
  459.6 Gbps, while the 12 PCE snapshot-19 ledgers report 459.3 Gbps. The exact
  0.3 Gbps delta is twice the 0.15 Gbps carried by the 14 obsolete directed
  path references. PCE reports no reservation for those removed links.
- The run contains no `link_failure_events`, so the disappearance never
  entered the backend failure/recovery projection.

The code path explains the race. Commit `e198f6e` added
`rerouteInfeasibleAgainstCurrentTed()` specifically to catch MD-LSP
generations published after a transition-local gone-link scan. Commit
`3498215` later changed `ParentAutonomousClockThread` to the scoped
`rerouteAffectedByInterDomainDiff()` worker and removed the call to the
current-TED validation, while leaving the validation method unused. An LSP
that becomes visible after its link's transition work has already been
consumed is consequently never revisited.

Final result for the second round: terminal queue convergence **PASS**;
strict four-layer path and bandwidth consistency **FAIL**. No code fix was
applied during this audit.

## Current-TED validation repair and r3 smoke

PCE commit `537834b` (`Restore current TED reroute validation`) restores the
authoritative current-TED sweep after the transition-local removed-link fast
path in every asynchronous parent frame. Existing in-flight reroute
deduplication prevents the two scans from issuing duplicate work. A regression
test fixes the phase order as removed-link diff, current-TED validation,
transit update, precomputed route application, and backoff retry.

Local verification covered 13 focused reroute, domain-ownership, run-boundary,
and resweep tests with no failures; both domain and parent shaded jars also
built successfully. Only the two repair files were committed. Pre-existing
local PCE deletions and unrelated algorithm edits were not staged or changed.

The repair was built from a clean archive of `537834b`, without using or
overwriting the dirty PCE checkout already present on server 169. Target image
`127.0.0.1:32000/salasim/pce:goal169-20260810-r3` has digest
`sha256:5aedae3501bf45a679c67446efe361ebe5370ca742a190659ee406f6783cfce8`.
Only the target deployment's parent PCE and 11 domain PCE Deployments were
updated. The resulting target inventory was 262/262 Ready with zero restart.

Run `simrun-e049921ee056` reused the deterministic 20-service smoke workload
(`seed=16920`) at 1x. At snapshot 4 it reported 20/20 services UP, 40/40
tunnels ACTIVE, no failed operations, and zero backend/parent recovery,
reroute, pending-distinct, or PCUpd acknowledgement queues. Parent logs prove
that every frame executed both phases: for frames 1 through 4 the exact diff
matched 11, 27, 2, and 18 MD-LSPs respectively; the immediately following
`MD_REROUTE_CURRENT_TED` scan observed the same in-flight generations and
submitted zero duplicates.

The same-watermark strict audit compared all 40 current tunnel paths with the
1,108 directed links derived from authoritative parent scene snapshot 4. It
found **zero affected tunnels, zero affected services, and zero missing
directed path links**. The run was then stopped cleanly by completed operation
`op-c0f3cfaf26c6`.

This smoke validates the repaired call path, deduplication behavior, queue
convergence, and strict ERO-to-topology consistency. Because all 40 smoke LSPs
were initially published in snapshot 0, it did not independently reproduce a
late-publication-only submission; closing the former 500-service failure at
full acceptance scale still requires a new 500-service terminal run.
