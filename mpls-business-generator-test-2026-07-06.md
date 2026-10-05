# MPLS Business Generator Test Issues - 2026-07-06

## Test Scope

- Deployment: `walker-50x20-e2e-0704`
- Simulator run: `simrun-91378edcc26a`
- Simulation profile: `profile-8b80274b1ddd`
- Simulation config: 300s step, 3x speed, 9000s duration
- Batch generator: 500 MPLS services, 100 Mbps each, unidirectional, seed `11`
- Batch id: `svc-batch-aae160c14c61`
- Browser target: `http://10.112.140.169:30080`

## Observed Runtime State

- Simulation start operation completed and run entered `running`.
- Batch preview generated 500 service requests.
- Batch create response scheduled 435 requests and skipped 65 as expired because of the default safety lead/tail window.
- During observation, services were being created over simulated time. At one checkpoint, 59 services were present and UP, each with an ACTIVE selected tunnel in the API payload.
- Backend log tail showed batch service creation events and did not show `No path`, `ERROR`, or exception entries in the sampled window.
- Reroute counters were increasing for some services, and analytics showed reroute success data.

## Issues Found

### Update - fixes applied locally on 2026-07-06

- Operations page now uses a longer feed timeout and preserves/labels the last good feed when refresh fails instead of presenting failed totals as zero.
- Run-scoped operations feed no longer mixes deployment-wide inventory discovery events into the live run feed.
- Alarms page now distinguishes source-unavailable from a true zero-alarm state.
- Service list/detail/tunnel components now understand the new `primary` tunnel role as the working tunnel and read selected tunnel/LSP summary fields.
- Relative age formatting now avoids confusing negative ages for recent timestamps.
- Service activity analytics now prefers service requested simulation time over lsp-create operation wall-clock time when bucketing create counts.
- Batch service generator UI now exposes an explicit `Target services` control and shows generated/scheduled/skipped counts.
- Operations detail no longer treats `runtime:` operation ids as snapshot-backed monitor targets.
- Added regression coverage for service activity bucketing by requested simulation time.

### Update - additional finding from local frontend regression

- URL tested: `/services?deployment=walker-50x20-e2e-0704&simulator=simrun-5f701e023435`
- Server-side check from `10.112.140.169`:
  - `/api/v1/runtime/services?deployment_id=walker-50x20-e2e-0704&run_id=simrun-5f701e023435` returned `count=0`.
  - `/api/v1/runtime/services?deployment_id=walker-50x20-e2e-0704` returned services whose `simulatorRunId` is `simrun-91378edcc26a`.
- Interpretation:
  - For this specific URL, the services page showing zero is caused by selecting a simulator run that does not own the created services, not by the tunnel rendering bug.
  - Follow-up deployment tests should verify that batch-created services, analytics pages, and service pages all use the same `simulatorRunId`.
- Suggested fix:
  - Add a visible scoped-run mismatch diagnostic when a deployment has services in other runs but the selected run has none.
  - Consider surfacing "latest run with services" in the simulation selector to avoid comparing analytics and services across different run ids.

### P0/P1 - Operations and alarms data APIs time out in the UI

- Page: `/operations?deployment=walker-50x20-e2e-0704&simulator=simrun-91378edcc26a`
- Page: `/alarms?deployment=walker-50x20-e2e-0704&simulator=simrun-91378edcc26a`
- Symptoms:
  - Operations feed request timed out:
    `/api/v1/ops/operations/feed?deployment_id=walker-50x20-e2e-0704&run_id=simrun-91378edcc26a&limit=500`
  - Alarms request timed out:
    `/api/v1/ops/alarms?deployment_id=walker-50x20-e2e-0704&run_id=simrun-91378edcc26a&status=active&limit=500`
  - Header still shows `API 正常`.
  - Operations page shows `0` total and "尚无操作记录".
  - Alarms page shows `0` critical/warnings and "No active alarms returned".
- Risk:
  - The UI can falsely imply the system is healthy or idle while the key data request has failed.
- Suggested fix:
  - Separate global API health from page data-source health.
  - Show an explicit error state for the affected table/cards and avoid reporting `0` when the source failed.
  - Optimize or paginate backend ops/alarms queries for large operation volumes.

### P1 - Services page does not render tunnel data from the new tunnel schema

- Page: `/services?deployment=walker-50x20-e2e-0704&simulator=simrun-91378edcc26a`
- Symptoms:
  - UI shows `工作隧道 0`.
  - Row columns `WORK TUNNEL` and `Tunnel-ID` show `-`.
  - API services include `tunnels[]`, selected primary tunnel, canonical `state=ACTIVE`, and `tunnelId=1`.
- Risk:
  - The new service/tunnel binding model is not visible in the service list, so users cannot validate primary/protection tunnel state.
- Suggested fix:
  - Update frontend mapping from legacy `currentLspId/currentScopedLspId/wireLspId` fields to the new `tunnels[]` model.
  - Derive "working tunnel" from selected active primary tunnel.
  - Show role, binding state, tunnel state, symbolic name, and tunnel id.

### P1 - Analytics summary uses inconsistent metric sources

- Page: `/analytics?deployment=walker-50x20-e2e-0704&simulator=simrun-91378edcc26a`
- Symptoms:
  - Top service summary, LSP fleet, and network block show different active LSP/service counts during the same visual refresh.
  - Example observed mismatch: service summary showed 21 services, LSP result view showed 21 fleet active, while the network block showed 25 active LSP and 2.5 Gbps.
  - Earlier API checks also showed mismatches between `serviceHealth`, `lspFleet`, and KPI values.
- Risk:
  - Users cannot tell whether business creation, LSP projection, or network sampling is the source of truth.
- Suggested fix:
  - Align all analytics widgets to a single snapshot/run timestamp.
  - Include `source`, `collectedAt`, and `snapshotIndex` per metric group.
  - Avoid mixing latest service projection with older/newer network frame metrics in one "current" view.

### P1 - Service activity chart totals and per-slice values disagree

- Page: analytics service activity chart.
- Symptoms:
  - Summary reported service creates, but per-slice `serviceCreateCount` stayed zero in observed slices.
  - Reroute counts were present in slices, so the chart partially works.
- Risk:
  - The new chart for "每时间片业务发放数量 / 重路由数量" can underreport creation events.
- Suggested fix:
  - Verify the aggregation key for service create events.
  - Ensure batch-created services emit or project create activity into the same per-slice series used by the chart.
  - Reconcile wall-clock `submittedAt`, requested simulation time, and snapshot effective time before bucketing.

### P1 - Network/topology sampling remains incomplete

- Page: analytics network/topology sections.
- Symptoms:
  - Network charts had missing frame counts during the run.
  - Link count rendered as `-` even though the deployment has topology links.
  - TE delay, utilization, and topology dynamics rendered as `暂无数据` or `-`.
- Risk:
  - Large-run analytics still loses important sampling data, especially network-side metrics.
- Suggested fix:
  - Audit the sampling pipeline after removing `simulation_result_snapshots`.
  - Confirm frames are persisted or queryable for every simulation slice from the runtime/projection source.
  - Add explicit missing-data diagnostics per metric family rather than silent gaps.

### P2 - Batch generator "500" does not mean 500 scheduled services

- Batch response:
  - Generated: 500
  - Scheduled: 435
  - Skipped expired: 65
  - Safety lead: 600s
  - Safety tail: 150s
- Symptoms:
  - User-facing intent was "发放 500 个业务", but only 435 were actually scheduled.
- Risk:
  - Test scale and expected statistics become ambiguous.
- Suggested fix:
  - Make preview apply the same safety filtering as creation.
  - Label generated vs scheduled clearly.
  - Add an option like `targetScheduledCount` if the user expects exactly N effective services.

### P2 - Simulation startup state is transiently inconsistent

- Symptoms:
  - Run list showed active run `simrun-91378edcc26a` as starting/running.
  - Clock endpoint temporarily returned stopped/null or stale state before correcting to running.
- Risk:
  - Pages can show stopped or no active run immediately after a successful start.
- Suggested fix:
  - Make start operation completion depend on runtime-store clock visibility, or mark clock status as `starting` until synchronized.
  - Avoid returning `stopped` for a known active run during propagation.

### P2 - Relative times can become negative on service rows

- Page: services list.
- Symptoms:
  - Recent activity labels showed negative values such as `-39s` or `-1m`.
- Risk:
  - Looks like time moved backwards; confusing during live simulation.
- Suggested fix:
  - Clamp small future deltas to `LIVE` or `0s`.
  - Review whether event timestamps use server wall time, browser time, or simulated time.

### P2 - Analytics API/debug routes are hard to inspect directly

- Symptoms:
  - Browser page renders analytics data.
  - Direct requests to `/api/v1/analytics/overview` and `/api/v1/analytics/charts` returned `404`.
- Risk:
  - Debugging route behavior is confusing because UI data paths and direct backend paths are not obvious.
- Suggested fix:
  - Document actual frontend proxy/API paths.
  - Prefer consistent `/api/v1/...` direct routes where possible, or expose debug links in development builds.

### P3 - Operations detail can reference stale runtime operation snapshots

- Page: operations.
- Symptom observed before the run:
  - Detail pane showed `Backend request failed for /api/v1/ops/operations/runtime%3Awalker-50x20-e2e-0704%3Asat-9/snapshot?log_tail_chars=120000: 404`.
- Risk:
  - Stale operation IDs produce noisy detail errors.
- Suggested fix:
  - Guard operation-detail fetches when an operation is not snapshot-backed.
  - Clear selected operation when feed scope changes.

## Follow-up Test Ideas

- Let the batch finish to its scheduled 435 services, then verify final service count, failed count, no-path count, reroute count, and analytics totals.
- Add a targeted batch that forces ground-ground, satellite-satellite, satellite-ground, and ground-satellite traffic proportions, then compare API service types with UI filters.
- After fixing tunnel rendering, verify service rows for primary/protection/shared-protection tunnel roles.
- Re-run analytics after every fix with a stable snapshot index and compare all summary cards against raw services/tunnels API counts.
