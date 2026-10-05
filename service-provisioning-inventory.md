# Service provisioning (C2b): inventory, YANG design, migration plan

Date: 2026-10-05. Scope: inventory and design only. No backend, PCE, emulator, controller or frontend code was changed. The YANG draft is committed in `salasim_gmpls_yang` (commit b3965b7): `salasim-service-types` (397 lines), `salasim-service` (171), `salasim-service-fleet` (222); all pass `validate.sh` and the full set loads together. Context: `docs/controller-hub-plan.md` sections 0.1 (rule 5: service provisioning belongs to the controller, the Backend gives intent only), 0.2 and C2b; style follows `docs/pce-run-start-inventory.md`.

Method and confidence. Every row below was read from source on 2026-10-05, not inferred from names: Python in `salasim_gmpls_backend/src/salasim_backend/` (`agent_clients.py`, `api_helpers.py`, `routers/services.py`, `tunnel_store.py`, `tunnel_identity.py`, `config.py`, `v3_statistics.py`), Java in `salasim_gmpls_pce/src/main/java/es/tid/pce/` (`http/PceApiServer`, `http/IntraDomainLspInitiateHandler`, `http/SrlgDisjointPairComputeHandler`, `http/LegacyPceControlPlane`, `http/HttpControlExecutor`, `parentPCE/ParentMdLspInitiateService`, `parentPCE/ProtectionGroupRegistry`, `parentPCE/TunnelUpdateEmitter`, `sim/RoutePlanningPolicy`). "Dead on the PCE" claims were checked by searching the whole PCE main tree for the key. Line numbers drift; function names are the stable handle. Nothing was executed against a live system. What could not be verified is listed in section 12.

Abbreviations: **IDH** = `IntraDomainLspInitiateHandler`, **PMS** = `ParentMdLspInitiateService`, **PGR** = `ProtectionGroupRegistry`, **SRLG** = `SrlgDisjointPairComputeHandler`, **API** = `api_helpers.py`, **SVC** = `routers/services.py`, **AC** = `agent_clients.py`, **TUE** = `TunnelUpdateEmitter`.

## 1. Numbers

| Item | Count |
|---|---|
| PCE HTTP routes the Backend calls for provisioning | 7 (6 POST: `lsp/initiate`, `lsp/delete`, `lsp/srlg-disjoint-pair`, `md-lsps`, `md-lsps/delete`, `protection-groups`; 1 GET: `pce/lsps[?symbolicPathName=]`) |
| Direct emulator calls in the provisioning path (rule 1 violations to migrate too) | 4 (`GET /api/v1/node`, `GET /api/v1/lsps`, `POST /api/v1/lsps`, `DELETE /api/v1/lsps/{id}`); the last two are a legacy third path, see 2.2 |
| Leaf values in one create request, domain / parent | 32 / 31 (the parent never receives `explicitEro`), counting the three `pathPlanning` children, arrays as one leaf |
| ... read by the PCE, domain / parent | 18 / 19 |
| ... sent but read by nothing in that PCE | 14 / 12 |
| ... of the 32, kept in the YANG intent / transitional / derived by the PCE / dropped as dead, derived or constant / dropped as path hints | 16 / 1 / 1 / 12 / 2 |
| Leaves in the delete request, domain / parent | 2 / 1 (service delete) or 2 (operation-recovery path) |
| Leaves in one protection-group registration | 9 + 5 per tunnel (plus `pceRunId`, accepted but never sent); 5 of them derived or constant |
| Leaves in the SRLG pair request | 5 read + `pathPlanning` (3 leaves, ignored) + `mode` (accepted, never sent) |
| Backend `POST /api/v1/services` (the intent producer): canonical fields / alias spellings accepted | about 24 / about 30 (section 7) |
| YANG `create-services` input, leaf definitions per service | 23 (6 service level, 2 path planning, 5 protection, 10 per tunnel incl. its attempt context); a concrete one-leg request sets about 14 |
| YANG delete input, leaf definitions per service | 8 (fleet: 10, `source` and `destination` are routing keys) |

## 2. The calls today

### 2.1 Which PCE, and who decides

`SVC._create_service_now` decides, for every service, from the Backend's own inventory:

```
is_intra_domain = src.domain_id is not None and dst.domain_id is not None
                  and src.domain_id == dst.domain_id and src.domain_id != "core"
intra  -> domain PCE of src.domain_id : POST /api/v1/pce/lsp/initiate
cross  -> parent PCE ("core")          : POST /api/v1/pce/md-lsps
```

The destination may be a node id or a bare IPv4 (`_resolve_destination`); a node without `router_id` makes the Backend call the emulator (`EmulatorAgentClient.get_node`, `_canonical_router_id`) to learn it. A service has one primary tunnel plus protection tunnels; each tunnel is one HTTP call (one operation of kind `lsp-create` in the Backend's `operation_store`). `bidirectional` is a flag on the tunnel, not a second tunnel.

### 2.2 Endpoint inventory

| # | Call | PCE | Executor / client timeout | Backend retry |
|---|---|---|---|---|
| 1 | `POST /api/v1/pce/lsp/initiate` (`PceApiServer` route `domain-lsp-initiate` -> `LegacyPceControlPlane.initiateDomainLsp` -> `IDH.execute`) | domain | PCE slow executor; business clamp `min(pccTunnelSignaling.timeoutMs 95000 + 5000, slow.commandTimeoutMs 245000 - 5000)` = 100 s. Client `max(30, domain_pce_lsp_http_timeout_seconds 100)` s | 2 transport retries, 2 s apart (safe: the handler joins an in-flight create by symbolic name) |
| 2 | `POST /api/v1/pce/lsp/delete` (`domain-lsp-teardown` -> `IDH.teardown`) | domain | same pool and clamp; client 100 s | none (`retry_on_503=False`, no transport retry) |
| 3 | `POST /api/v1/pce/lsp/srlg-disjoint-pair` (`srlg-disjoint-pair-compute` -> `SRLG.execute`) | domain | PCE fast executor, in-process graph work; client 15 s | 503 x2 (1 s or `Retry-After`), transport x3 |
| 4 | `POST /api/v1/pce/md-lsps` (`parent-md-lsp-initiate` -> `PMS.initiateAsync`, `CompletionStage`) | parent | PCE slow executor, 245 s; client `max(10, parent_md_lsp_http_timeout_seconds 260)` s | **none** (comment: hidden retries once reused a terminal attempt id) |
| 5 | `POST /api/v1/pce/md-lsps/delete` (`parent-md-lsp-teardown` -> `PMS.teardown`) | parent | slow executor; client 260 s | none |
| 6 | `POST /api/v1/pce/protection-groups` (`protection-group-replace` -> `PGR.replace`) | the PCE that owns the creates (domain for intra, parent for cross: `target.requestEndpoint`) | fast executor; client 15 s | 503 x2, transport x3 (a replace is idempotent) |
| 7 | `GET /api/v1/pce/lsps[?symbolicPathName=]` (`PceApiRuntime.describeLsps`) | parent (only caller: `list_lsps` for reconcile and protection-reference wait) | n/a | n/a |
| 8-11 | emulator `GET /api/v1/node`, `GET /api/v1/lsps`, `POST /api/v1/lsps`, `DELETE /api/v1/lsps/{id}` | emulator | `POST` timeout from the deployment profile `apiCommandMs` (`_emulator_command_timeout_seconds`) | n/a |

Notes. (a) Calls 1 to 5 are also issued from the Backend's recovery loop (`_run_recoverable_lsp_operation`, durable `operation_store` leases of `lifecycle_operation_lease_seconds` 120 s), so a Backend restart replays them. (b) Calls 10 and 11 are a legacy path: every one of the four operation-creating sites in `SVC` sets a parent or domain `provisioningChannel`, so `_execute_lsp_create_operation` (emulator `POST /api/v1/lsps`) and `teardown_lsp` are reachable only by an operation persisted with another channel. They should be deleted, not migrated. (c) Call 9 is **live** for every domain create: see 3.3. (d) The domain handler's own `handle()` (400 / 413 / 503 mapping) is not on the registered route; the route is a `RawJsonCommandHandler`, see the error table. (e) `HttpBodyReader` accepts 64 MiB by default, so size is not a constraint.

## 3. `POST lsp/initiate` and `POST md-lsps` (create)

### 3.1 Request body

The Backend builds one body per tunnel in `SVC._create_service_now._request_payload_for_tunnel`, then adds six leaves at dispatch (`API._with_provisioning_attempt_policy`, `API._request_with_upstream_wait`). "D" = domain PCE reads it (`IDH.execute`), "P" = parent reads it (`PMS`, in `prepare` / `resumeExisting`). "Py" = Backend validation (all in `SVC` unless noted). Types are as sent on the wire.

| # | Field | Type | Sent | D | P | Required / default at the PCE | Bounds and who validates | Disposition |
|---|---|---|---|---|---|---|---|---|
| 1 | `serviceId` | string | always | yes | yes | absent -> null (the fact registration then has no service) | none; Backend generates `svc-<24 hex>` from a batch job/request id (`_service_id_for_batch_job`, sha256) or `svc-<32 hex>` | keep: `service-id` |
| 2 | `simulatorRunId` | string | always | **no** | yes | P: stale-run check only if present: `parent-create-stale-run` 409 | Py: `require_active_simulator_run`. D: not read, so a domain create from a stale run is not fenced | keep: `simulator-run-id`; add the domain check |
| 3 | `sourceNodeId` | string | always | no | no | none | none | drop: dead |
| 4 | `destinationNodeId` | string | always | no | no | none | none | drop: dead |
| 5 | `sourceRouterId` | IPv4 string | always | yes | yes | D: `parseIp` -> `IllegalArgumentException "sourceRouterId and destinationRouterId are required IPv4 addresses"`; P: `required()` -> `IllegalArgumentException "<key> is required"` | Py: `_canonical_router_id` (422 if none) | keep: `source` |
| 6 | `destinationRouterId` | IPv4 string | always | yes | yes | same | Py: `_resolve_destination` (422 if neither node nor IPv4) | keep: `destination` |
| 7 | `bandwidthBps` | int | always | yes | yes | D: absent -> 0 -> `MplsOnlyMode.normalizeObjectiveFunction` throws "bandwidthBps/bandwidth is required for MPLS services"; P: `longValue` (`Long.parseLong`), 0 -> same later failure. Converted `Float.parseFloat(bps) / 1e6` (Mbit/s as a 24-bit float) | Py: `int()`; `>0` required for MPLS (400) | keep: `bandwidth`, mandatory, `uint64 1..max` |
| 8 | `symbolicPathName` | string | always | yes | yes | **the idempotency key**: D: LSP-DB `findBySymbolicPathName`; P: `CreateKey(db, symbol)`. D: no null check (`RoutePlanningRegistry.put(null, ...)`) | Py: `make_symbolic_name` = `service/{service_id}/tunnel/{tunnel_id}` (`tunnel_identity.py`) | derive (PCE builds it from service-id and tunnel-id) |
| 9 | `canonicalTunnelId` | string | always | yes | yes | read for `TunnelUpdateEmitter.register(...)`; if the registration is missing every later fact is rejected ("no explicit backend Tunnel identity ... fact rejected") | Py: `tun-<32 hex>` from `tunnel_store.create_tunnel` | keep: `tunnel-id` |
| 10 | `tunnelId` | int | always | yes | yes | D: `getIntValue`, absent 0; the `IPv4LSPIdentifiersTLV` is attached **only if non-zero**. P: `intValue` | Py: `MAX(tunnel_id) WHERE sender_address=? + 1` in `tunnel_store.create_tunnel` (unbounded across runs; RFC 3209 tunnel ids are 16 bits, not checked anywhere I found) | transitional: `wire-tunnel-id` (D5) |
| 11 | `extendedTunnelId` | int | always | yes | yes | D: absent -> equals `tunnelId` | Py: source router id as a 32-bit int | drop: derived from `source` |
| 12 | `bidirectional` | bool | always | yes | yes | D: `getBooleanValue` (absent false); P: `Boolean.parseBoolean(String.valueOf(x))` so `"yes"` is false | Py: `_resolve_bidirectional`: `bool(payload["bidirectional"])` (so the string `"false"` is **true**), else `direction` text with aliases | keep |
| 13 | `protectionRole` | string | always | no | no | none (the group registration carries roles, not this) | Py: `primary`, `standby`, `shared_protect` | keep as `tunnel/role` |
| 14 | `protectionMode` | string | always | no | no | none | Py | drop: dead per tunnel (service-level `protection/mode`) |
| 15 | `protectionIndex` | int | always | no | no | none | none | drop: dead |
| 16 | `protectionGroup` | string | always | no | no | none | Py: `pg-<service>` or `sharedGroupId` | drop: dead; the PCE derives `pg-<service-id>` |
| 17 | `diversity` | string | always | yes (reuse check only) | yes | **no validation**: D uses it only in `reuseSatisfiesDiversity`; P: `"srlg"`, `"node"`, `"none"`, anything else behaves as `link`. P: `diversityAgainst...` set and `diversity` empty -> 400 "diversity is required for a protected business tunnel"; D: no such check | Py: `none/link/node/srlg`, default `link` (also when protection is off) | keep: `protection/diversity` |
| 18 | `pathPlanning.candidatePathCount` | int | always | yes | yes | required: absent -> `IllegalArgumentException "pathPlanning.candidatePathCount required"`; bound `1..runConfig.maxCandidatePathCount` | Py: `_resolve_path_planning`, bound from the deployment's `parentPce.routingSearch.maxCandidatePathCount` (409 if the limits are missing) | keep, optional in the YANG (D4) |
| 19 | `pathPlanning.qualityObjectiveOrder` | 3 strings | always | yes | yes | must be a permutation of `AVG_HOP, AVG_DELAY, DELAY_JITTER`; the first one selects the OF (`ofCodeForPrimaryObjective`: `AVG_DELAY`=1004, `DELAY_JITTER`=1005, else 1003); the whole order ranks candidates (`RouteCandidatePlanner`) | Py: uppercase array, permutation, 400 | keep: ordered `optimization-metric` list |
| 20 | `pathPlanning.stabilityGated` | bool | always (constant true) | no | no | never read | none | drop: constant |
| 21 | `controlTriggeredAt` | RFC 3339 | if known | no | no | none (only the Backend uses it) | Py: `parse_sim_iso` 400 | drop: replaced by `triggered-at` |
| 22 | `attemptTriggeredAt` | RFC 3339 | if known | no | no | none | Py | drop |
| 23 | `diversityAgainstTunnelId` | string | standby, diversity != none | no | no | none | none | drop: dead |
| 24 | `diversityAgainstSymbolicPathName` | string | standby, diversity != none | yes (reuse) | yes | P overrides it from `PGR.diversityReferenceSymbolicPath` when the group knows the tunnel | none | drop: a **path hint** (reference path) |
| 25 | `explicitEro` | array of IPv4 | intra-domain protected only | yes | no | D attaches it as the ERO, skipping local computation; reuse only matches a connection on exactly that path | Py: result of call 3 | drop: **a path** |
| 26 | `technology` | string | only if present in the caller's payload | no | no | none | none | drop: dead |
| 27 | `provisioningAttempt` | int | added | yes | yes | `max(1, x)`; part of the attempt id | Py: constant 1 | keep: `attempt` |
| 28 | `finalAttempt` | bool | added | no | yes | P: default true; false suppresses the terminal failure fact | Py: `attempt >= max_attempts` (always true today: 1 of 1) | keep: `final-attempt` |
| 29 | `controlOperationId` | string | added | yes | yes | attempt id = `<controlOperationId>:<provisioningAttempt>`; absent -> random UUID | Py: the Backend operation id | keep: `operation-id` |
| 30 | `upstreamWaitingDelayMs` | long | added if a trigger time exists | yes | yes | `max(0, x)`; added to the PCE's own `queueWaitMs` fact attribute | Py: dispatch time minus `attemptTriggeredAt` or `controlTriggeredAt` | keep: `upstream-waiting-delay` |
| 31 | `triggeredAtEpochMs` | long | added if a trigger time exists | yes | yes | default: PCE now | Py | keep: `triggered-at` |
| 32 | `startedAtEpochMs` | long | added | no | no | the PCE records its own start time (`TUE` reads an attribute map, not the body) | Py | drop: dead |

Counts: read by the domain PCE 18 (rows 1, 5-10, 11, 12, 17-19, 24-25, 27, 29-31), by the parent 19 (the same minus 25, plus 2 and 28); sent and unread 14 (domain) / 12 (parent).

**Which fields the intent must NOT carry (the plan: "intents carry no path")**: `explicitEro` (25) is a path; `diversityAgainstSymbolicPathName` and `diversityAgainstTunnelId` (23, 24) name a reference path; `tunnelId`/`extendedTunnelId` (10, 11) are wire identifiers (kept for now only as the optional `wire-tunnel-id`, D5); `candidatePathCount` (18) is a search-width knob (an algorithm hint, kept optional, D4); `stabilityGated` (20) and `technology` (26) are constants or dead. Objective-function **codes are not on the wire any more**: both PCE handlers reject `routingObjective` and `objectiveFunctionCode` ("retired; use pathPlanning"), and the code is derived from the first quality objective. The plan's wording "OF 1003/1004/1005" therefore describes an internal derived value; the intent carries the ordered metrics (D9).

**What must stay**: objective ordering (19), protection type and diversity (17, 13 and the group policy of section 5), bandwidth (7), bidirectional (12), endpoints (5, 6), service and tunnel identities and the run id (1, 2, 9), the attempt correlation (27-31). Priority and hold-off are protection-group policy (section 5). `switchingType` is not on the wire to the PCE (the Backend checks it equals the deployment's and computes `of_code = _SWITCHING_OF[...]` which is **never used**: dead local in `SVC`).

### 3.2 Response

Domain, HTTP **202 for every outcome** (`RawJsonCommandHandler` writes 202 for `domain-lsp-initiate`, 200 otherwise), body one of:

| Case | Body |
|---|---|
| reuse hit | `status completed`, `scope`, `lspId`, `symbolicPathName`, `reuseResult HIT`, `connectionName`, `signalingBypassed`, `pathComputationDelayMs 0`, `signalingDelayMs 0` |
| already exists / joined in-flight | `status completed`, `idempotentReplay true`, `lspId`, `symbolicPathName` |
| PCRpt with lspId > 0 | `status completed`, `scope`, `symbolicPathName`, `bidirectional`, `lspId` (+ `reuseResult MISS`, `idempotentReplay` when it joined) |
| PCRpt without lspId | `status failed`, `failureType pcc-initiate-no-report`, `message` |
| wait bound reached | `status accepted`, `inProgress true`, `idempotentReplay`, `message` |

There is **no `pathState` and no `ero`** in a domain reply. The Backend reads `status` (success = one of `queued, accepted, completed, ok, success`), and via `_emulator_lsp_record` the keys `lspId, source, destination, pathState, ero, bandwidth`. Because `pathState` is absent, `_is_emulator_lsp_established` is false and **every domain create goes on to poll the emulator** (`_await_lsp_visibility`: `GET <emulator>/api/v1/lsps` for up to 150 s, plus an event watcher), a direct device read the plan forbids (rule 1) and the reason the new interface must return the operational state (D14).

Parent, HTTP 200 on success (or the error table). Body: `status completed`, `mdLspId`, `symbolicPathName`, `bidirectional`, `domainLspIds` (map), `ero` (router ids), any `protection*` attribute (`protectionDegraded`, `protectionDegradationReason`, `protectionSharedLinks`), `reuseResult MISS` when reuse is on, `idempotentReplay true` on a replay (`existingResult`: no `protection*`). The Backend reads `status` (success = `completed, ok, success`), `mdLspId`, `ero`, `pathState` (defaults to 1 = UP on success, not invented as 2), `protectionDegraded`, `protectionDegradationReason`, `protectionSharedLinks`, and on failure `failureType`, `details` (whole object stored as `parentPceFailure`; `noPathClass`, `retrySafe`, `outcomeUncertain`, `retryReason` extracted), `status recovery-in-progress` with `reason parent-recovery-owns-tunnel` and `recoveryOperationId`, `outcomeUncertain` (top level, from the 504 body). Never read: `domainLspIds`, `reuseResult`, `bidirectional`, `controlOperationId`, `idempotentReplay`. The error body is recovered by cutting the substring between the first `{` and the last `}` of the exception text (`API._extract_upstream_error_body`); the HTTP status code itself is not kept.

### 3.3 Errors and what the Backend does

The Backend wraps any HTTP >= 400 in `AgentRequestError("Agent request failed: <status> <body>")`.

| Condition | HTTP | Body marker | Backend behaviour |
|---|---|---|---|
| **domain**: bad IP, missing or invalid `pathPlanning`, retired field, no bandwidth (all `IllegalArgumentException`) | **500** through the registered route (`initiateDomainLsp` re-wraps every exception as `HttpControlException(500, ...)`; the 400 mapping exists only in the unused `handle()`) | `message` | `failureType domain-pce-request-failed`; a caller error is reported as a PCE failure |
| domain: no PCC session / dispatcher | 503 | `message` | same, `domain-pce-request-failed` |
| domain: intake full (`HttpControlExecutor`) | 503 | `failureType control-command-queue-full`, `details.retrySafe true`, `outcomeUncertain false` | `_propagate_parent_failure_classification` keeps the type for the parent path; for the domain path it ends as `domain-pce-request-failed` |
| any: command exceeded the executor bound | 504 | `details.outcomeUncertain` (true if it had started) | parent: `outcomeUncertain` -> reconcile by `GET lsps?symbolicPathName` (4 tries, 1 s); domain: only transport errors trigger the reconcile (30 s emulator poll) |
| parent: invalid body / `IllegalArgument` / `IOException` | 400 | `status failed`, `message` | `parent-pce-request-failed` |
| parent: stale run | 409 | `failureType parent-create-stale-run` | failed, final |
| parent: same symbolic name, different body while in flight | 409 | `parent-create-request-conflict` | failed |
| parent: create for a tunnel whose teardown receipt exists in this run | 409 | `parent-owner-already-removed` | failed |
| parent: no path | **422** | `failureType pce-no-path*` or `pce-overload`/`pce-child-timeout`, `details.noPathClass`, diagnostics (`mdGraph`, `childDomains`, ...) | `failureType` stored; types in `_SAFE_UNESTABLISHED_CREATE_FAILURES` mean "nothing was signaled" (teardown may skip, retry next slice) |
| parent: child initiate failed | 502, or 503 + `Retry-After 1` if `retrySafe` | `child-initiate-failed`, `child-bandwidth-changed-before-initiate`, `details.retrySafe` | stored; the slice scheduler retries |
| parent: busy or session not ready | 503 + `Retry-After` 1-3 | `parent-md-lsp-busy`, `parent-owner-operation-in-progress`, `domain-session-not-ready`, `protection-reference-not-ready`, `parent-create-executor-unavailable`, `status recovery-in-progress` | `create_md_lsp` has `retry_on_503=False`: the failure is recorded and the next slice reissues the same canonical tunnel; recovery-in-progress is kept as `parent-recovery-owns-tunnel` |
| parent: cleanup failed | 500 | `parent-create-cleanup-failed` | failed |
| transport error (timeout, reset) | none | none | parent: uncertain -> reconcile; a later PCE success fact for `<operationId>:1` repairs it (`reconcile_terminal_parent_lsp_operations_once`, every 30 s) |

### 3.4 Idempotency and duplicate symbolic name

* **Domain**: `symbolicPathName` is the key. An LSP already in the LSP-DB answers `completed` + `idempotentReplay`, with no comparison of the new body to the old (a different bandwidth for the same name is silently ignored). A concurrent request joins the in-flight future (`IN_FLIGHT`). One of 256 striped locks per name. Reuse (end-to-end reuse pool) can bind a pooled connection instead of signaling.
* **Parent**: `creates[CreateKey(db, symbolicPathName)]`. The same name in flight with an equal "identity" replays the shared result (`idempotentReplay true`); different identity -> 409 `parent-create-request-conflict`. The identity is the whole body minus five fields (`attemptId`, `provisioningAttempt`, `finalAttempt`, `upstreamWaitingDelayMs`, `triggeredAtEpochMs`). **`controlOperationId`, `startedAtEpochMs`, `controlTriggeredAt`, `attemptTriggeredAt` are not excluded**, and the Backend sets a new value of the first two on every dispatch, so only a byte-identical retransmission replays; a Backend recovery re-dispatch while the first is still in flight gets 409. After completion the entry leaves the map; a later create for an existing confirmed owner answers from the DB (`existingResult`). A recovery owning the tunnel answers 503 `recovery-in-progress`.
* Backend side: service identity is idempotent per batch job or request id (`_service_id_for_batch_job`), a reissue reuses the same canonical tunnels (`_retryFailedBatch`), never rotating identities, "because a previous non-idempotent request may still confirm late".

### 3.5 Async behaviour

Domain: the handler blocks on `iniDispatcher.dispathInitiateWithCompletion(...)` up to 100 s (value above), then answers `accepted/inProgress`; it **equals the Backend's HTTP timeout of 100 s**, so on the timeout path the client most often sees a transport timeout, not the `inProgress` body (a race; the repeat is safe). Parent: `initiateAsync` -> `CompletionStage<Result>` over a bounded executor; admission `MD_LSP_ADMISSION` semaphore of 64 (`salasim.pce.parent.multiDomainEstablish.workerCount`), phase continuations enqueued, protection reference wait `DIVERSITY_REFERENCE_WAIT_MS`, hard bound 245 s. Both wait for the child PCRpt before answering. The Backend never sends more than `lsp_create_dispatch_concurrency` 64 (`_lsp_create_dispatch_sem`) at once, and batch creation has `service_batch_create_concurrency` 32, `service_batch_active_operation_limit` 64.

## 4. Teardown (`lsp/delete`, `md-lsps/delete`)

| | Domain `lsp/delete` | Parent `md-lsps/delete` |
|---|---|---|
| Body sent (service delete, `SVC.delete_service`) | `lspId` (wire id from the service projection; the Backend requires it: without it `_create_lsp_teardown_operation` returns None and the delete fails 409 "Could not create teardown for active tunnel", although the PCE resolves by symbolic name) and `symbolicPathName` | `symbolicPathName` only (a parent MD-LSP is addressed by name; the recovery path `_run_recoverable_lsp_operation` sends `mdLspId` and `symbolicPathName`) |
| PCE reads | `symbolicPathName` (preferred), else `lspId` | `mdLspId`, `symbolicPathName`; neither -> 400 "mdLspId or symbolicPathName is required" |
| Success body | `status completed`, `physicalTeardown`, `lspId`, or `reuseResult RELEASED_TO_POOL` / `ALREADY_REMOVED` | `status completed`, `mdLspId`, `symbolicPathName`, `confirmedDomains`; or `ALREADY_RELEASED`; `idempotentReplay true` from a receipt |
| Already gone | **200 `ALREADY_REMOVED`** | **404** `Unknown parent MD-LSP`, unless a teardown receipt of this run exists (in memory, current run only) |
| Errors | 502 "Domain PCC did not confirm LSP delete", 503 no dispatcher, 500 | 409 `parent-owner-operation-in-progress` (+ `Retry-After 1`, a create is in flight), 409 name/id mismatch, 502 `parent-md-lsp-teardown-incomplete`, 400, 503, 500 |
| What the Backend reads | **HTTP success only** (`_execute_upstream_operation` marks the operation completed on any 2xx; the `status` field is not looked at) | same |

The Backend marks the service `STOPPED` only after every owner teardown is confirmed (`service-teardown-incomplete` 502 otherwise) and refuses to start a teardown while a create is pending or uncertain (`_teardown_create_barrier`, `service-lifecycle-not-converged`). A 404 from the parent is a failure to the Backend, not "already removed" (inconsistent with the domain reply). Shared protection tunnels are only torn down by the last service using them; that arithmetic (`claim_service_teardown`) is Backend-side. Teardown retry: none at the HTTP layer; the durable operation is re-dispatched by the recovery loop.

## 5. `POST protection-groups` and `POST lsp/srlg-disjoint-pair`

### 5.1 Protection group registration (`PGR.replace`)

Sent by `API._register_lsp_protection` before the first create of the service, once per service, to the PCE that will own the creates (a durable worker prerequisite; failure -> operation failed `pce-protection-group-sync-failed`).

| Field | Sent value | PCE behaviour | Disposition |
|---|---|---|---|
| `serviceId` | service id | required (400) | derived |
| `protectionGroupId` | always `pg-<serviceId>` | required (400); **not** the `sharedGroupId` the tunnels carry | derived |
| `simulatorRunId` | run id | stored, no check | covered by `simulator-run-id` |
| `pceRunId` | never sent | accepted, default empty | dropped |
| `selectedTunnelId` | the primary's canonical id | not validated against `tunnels` | derived: the `primary` tunnel |
| `protection.mode` | `dedicated/shared/mixed` | **not validated** | keep |
| `protection.diversity` | `none/link/node/srlg` | default `link`, not validated | keep |
| `protection.revertive` | bool (Backend: `bool()` of anything, `"false"` -> true) | `bool(..., false)` | keep |
| `protection.holdoffMs` | int >= 0 (Backend clamps negatives to 0) | `number(..., 0)` | keep: `hold-off-time` |
| `protection.wtrSeconds` | int >= 0 (same clamp) | `number(..., 0)` | keep: `wait-to-revert` |
| `tunnels[].tunnelId`, `.symbolicPathName`, `.role`, `.priority`, `.state` | canonical id, symbolic name, `primary/standby/shared_protect`, int (default 100), constant `SIGNALING` | an entry without id or symbolic name is skipped silently; default priority 100, state `SIGNALING`; an empty list -> 400 | `tunnel/*` (state is constant, symbolic name derived) |

Response: the group description (not read by the Backend). Idempotency: a repeat refreshes policy, role and priority and keeps pending DOWN timers; a tunnel dropped from the list is unmapped. Behaviour worth knowing: the group id is per service even for shared protection, so a shared tunnel is registered in each service's group under its own symbolic name; the Backend's `sharedGroupId` never reaches the PCE.

### 5.2 SRLG disjoint pair (`SRLG.execute`)

Request leaves: `sourceRouterId`, `destinationRouterId`, `bandwidthBps`, `bidirectional`, `diversity` (default `srlg`; `none/link/node/srlg`, else 400), `pathPlanning` (sent, **ignored**), `mode` (`sequential` default, `joint` only for SRLG; never sent by the Backend). Response `primaryEro`, `backupEro` (router-id lists). Errors: 400 invalid, 409 "No primary path found" / "No backup path found for X diversity" / "No SRLG-disjoint pair found", 503 TEDB or graph unavailable. The Backend turns **any** failure, including the 409 "no pair exists", into `503 pce-diversity-pair-compute-failed` and retries the same canonical pair in the next slice (so a topologically impossible pair is retried as if transient). It calls this for **intra-domain** protected services only (`diversity != none`, at least one standby) and sends the two EROs as `explicitEro` of the primary and of the first standby; further standbys get none and no pair. The pair is computed on the current graph only (the class comment states it deliberately does not use the cross-snapshot stability windows), so a protected intra-domain service gets weaker route stability than an unprotected one. Cross-domain protected services have no such call; the parent derives diversity itself (XRO from the reference leg, `PGR.diversityReferenceSymbolicPath`).

## 6. How the Backend learns the outcome later (the evidence the controller path must keep)

1. **The HTTP reply** (sections 3.2, 4): immediate outcome, written to the durable `operation_store` and, via `_service_notify_lsp_outcome`, to `tunnel_store` (ACTIVE on success, DOWN otherwise). Domain success additionally needs the emulator read (3.2).
2. **Telemetry facts**, not through the controller (rule 4): the PCE publishes per-tunnel facts (`PceWebhookSender.offerV3`, JetStream or HTTP) to the Backend table `pce_tunnel_updates` (`v3_statistics.ingest_tunnels`). Facts are produced by `TunnelUpdateEmitter.emit(symbolicPathName, ..., fromState, toState, reason, wireLspId, ero, snapshotIndex, ..., attributes)`; operation types include `TUNNEL_ESTABLISH`, `SERVICE_TEARDOWN`, `REALTIME_REROUTE`, `PRECOMPUTED_REPLACE`; the `attributes` carry `attemptId`, `triggeredAtEpochMs`, `startedAtEpochMs`, `queueWaitMs`, `routingLatencyMs`, `signalingSetupLatencyMs`, `metricScope`, `result`, `failureType`, protection and reuse attributes. Metric facts (`pce_metric_facts`: latency, histogram, count, ingested by `ingest_metric_facts`) are presumably produced by `SignalingOperationTelemetry` (`begin("TUNNEL_ESTABLISH", attemptId)` and `complete`); the link from that class to the metric table was inferred from names, not traced.
   * A fact is accepted only for a tunnel whose identity was registered at create time (`TunnelUpdateEmitter.register(symbolicPathName, serviceId, canonicalTunnelId, bandwidth, scope, bidirectional)`); otherwise "fact rejected" is logged. **So the create call must carry `serviceId` and `canonicalTunnelId` unchanged.**
   * The attempt id is `<controlOperationId>:<provisioningAttempt>`; `API._parent_create_success_fact` looks the success fact up as `<operation id>:1` (the `1` is hard-coded). **The controller must pass `operation-id` and `attempt` through untouched.**
   * Time attributes are computed from `triggeredAtEpochMs` and `upstreamWaitingDelayMs`; a controller hop adds real delay (the intent carries `upstream-waiting-delay`, the controller adds its own hop time, recorded honestly, invariant I3).
3. **Reroute and recovery outcomes**: only facts. The PCE keeps no per-tunnel reroute counter (`describeLsps` keys: `administrative, bandwidth, bidirectional, connectionName, currentCanonicalTunnelId, currentServiceId, currentTunnelSymbolicPathName, delegated, destination, domainLspIds, ero, extendedTunnelId, lifecycleState, lspId, mdLspId, objectiveFunctionCode, operational, pathState, pendingSetupCleanup, removed, reuseState, rsvpLspId, source, symbolicPathName, sync, tunnelId, tunnelSender`; no `reroute`, no `generation`). Backend aggregations (`service_store.aggregate_reroute_outcomes_by_snapshot`, the reroute-outcomes API) read `pce_tunnel_updates.operation.type IN (REALTIME_REROUTE, PRECOMPUTED_REPLACE)` and `operation.result`. Nothing in the YANG model duplicates this; the evidence stays facts.
4. **Reads**: `GET pce/lsps?symbolicPathName=` (parent) for the reconcile after an uncertain create and for the standby's wait for an established primary (`_await_parent_protection_reference_ready`: polls until `ero` is non-empty and `pathState` is 1 or 2). In the new model this is `query-services`.
5. **Protection state**: `protectionDegraded` arrives in the create reply and as a PCE fact attribute; the Backend also records degradation independently when it dispatches a fallback standby (`_prepare_protection_fallback`), because the primary's `failed` can be late-reconciled.

## 7. What the Backend decides itself today (the producer side)

`POST /api/v1/services` (`SVC.create_service`, also called by the batch scheduler `batch/create`, `MAX_CREATE_ITEMS` 20000 per batch, `MAX_PREVIEW_ITEMS` 20000) accepts, with alias spellings accepted silently: `deployment_id|deploymentId`, `src_node|srcNode`, `dst_node|dstNode`, `name`, `requestedSimTime|startSimTime|startTime|requested_start_time`, `controlTriggeredAt`, `attemptTriggeredAt`, `bandwidth_bps|bandwidthBps`, `simulator_run_id|simulatorRunId`, `requestId`, `_batchJobId`, `_retryFailedBatch`, `lifetimeSeconds`, `protection|protectionConfig|protection_config` (with `enabled, mode, diversity, standbyCount|standby_count, sharedCount|shared_count, sharedGroupId|shared_group_id, revertive, holdoffMs|holdoff_ms|holdoffSeconds|holdoff_seconds, wtrSeconds|wtr_seconds|waitToRestoreSeconds|wait_to_restore_seconds`), `switchingType|switching_type`, `pathPlanning|path_planning` (`qualityObjectiveOrder|quality_objective_order`, `candidatePathCount|candidate_path_count`), `bidirectional` or `direction|serviceDirection|service_direction` (aliases `bi, two_way, duplex`, `uni, one_way, simplex`), `technology`, and tracking attributes (`batchId`, `batchOrdinal`, `manifestSha256`, `trafficProfileSha256`, `trafficSourceFileSha256`, `trafficWeight|samplingWeight|businessWeight`, `experimentKind`, `benchmarkId`, `benchmarkSampleOrdinal`).

Backend-only (stay in the simulation driver): arrival process and rates, service lifetime (`lifetimeSeconds` -> `expiresSimTime`, expiry teardown on simulation time), admission and saturation shedding (`BatchAdmissionShed`, 429), the horizon check (409 `simulation-result-horizon-reached`), service/tunnel stores, tracking attributes, the durable operation queue and leases, per-slice retry scheduling.

Decided by the Backend today and in question for the intent model (decision D1, D2): the number and identity of tunnels (`tunnel_store.create_tunnel`, wire tunnel id allocation), shared-protect tunnel find-or-create across services under one lock (`find_or_create_shared_protect_tunnel`), the order primary-then-standby (`_await_lsp_create_dependency` waits outside the dispatch semaphore, and for the parent also until `_await_parent_protection_reference_ready` sees the primary established), the fallback when the primary failed (`_prepare_protection_fallback`: standby without a diversity reference, service becomes degraded), the registration call, and for intra-domain protection the SRLG pair computation with explicit EROs.

Silent normalisations in the producer, all of which the YANG replaces by typed leaves: negative `standbyCount`, `sharedCount`, `holdoffMs`, `wtrSeconds` are clamped to 0; `protection.diversity` defaults to `link` even when protection is off; a non-dict truthy `protection` means enabled with defaults; `bool("false")` is true for `revertive` and `bidirectional`; `enabled` false zeroes the counts silently; `candidatePathCount` bound comes from the deployment profile, not from the PCE.

## 8. Inconsistencies, dead fields, silent defaults (consolidated)

1. 14 / 12 dead leaves per create request (section 3.1); `of_code` computed and unused; `stabilityGated` constant; `technology` pass-through unread.
2. Domain create validation errors are **HTTP 500** (`initiateDomainLsp` re-wraps the `IllegalArgumentException` message), parent create's are 400; SRLG and protection groups 400; the domain 202 is returned for failed and in-progress outcomes too.
3. Domain create never checks `simulatorRunId` (parent does: 409).
4. Domain replay ignores content (silent ignore of a changed bandwidth); parent replay compares content including wall-clock fields (`startedAtEpochMs`) so it almost never replays.
5. Teardown of an unknown LSP: domain 200 `ALREADY_REMOVED`, parent 404 (a failure to the Backend). The Backend ignores the teardown body, so the domain `RELEASED_TO_POOL` vs real removal is invisible to it.
6. `diversity` is unvalidated at the PCE (any typo is link diversity); `protection.mode` unvalidated; group registration silently skips tunnel entries without id.
7. HTTP timeout equals the domain business bound (100 s): a race on the `inProgress` path.
8. The domain reply has no `pathState`/`ero`, so the Backend polls the emulator for every domain create.
9. The group id is `pg-<service>` even for shared tunnels; the Backend's `sharedGroupId` never reaches the PCE.
10. SRLG pair compute failure of any kind (including "no pair exists", 409) becomes a retryable 503.
11. Backend-allocated RSVP tunnel number grows with `MAX+1` per sender across runs; 16-bit field on the wire (unverified).
12. The Backend recovers `AgentRequestError` details by parsing the exception text; the status code is not retained.
13. `PMS` replay identity excludes five fields but not the other four per-dispatch fields (4 above).
14. `bandwidthBps` goes through `Float.parseFloat / 1e6`: above 2^24 Mbit/s precision is lost (not reachable at today's 400 Gbit/s links, silent beyond).
15. Plan text says OF 1003/1004/1005 are the intent; on the wire they are retired and derived (D9).
16. Legacy emulator create/teardown path is dead code but reachable by old persisted operations.
17. Domain teardown needs a Backend-known wire LSP id although the PCE resolves by symbolic name; the parent needs none.

## 9. YANG design

Files (`salasim_gmpls_yang/salasim/`, nothing synced anywhere):

| Module | Lines | Role |
|---|---|---|
| `salasim-service-types` | 397 | typedefs, the identity `path-metric-delay-variation`, groupings `service-intent`, `tunnels-intent`, `tunnel-intent`, `attempt-context`, `tunnel-state`, `failure-info`, `admission-result`; no data nodes |
| `salasim-service` | 171 | implemented by every PCE: operational list `services`, RPCs `create-services` and `delete-services`, notification `service-state-changed` |
| `salasim-service-fleet` | 222 | served by the controller: `provision-services`, `delete-services`, `query-services`, read-only `routing` table |

Mapping of the old calls: `lsp/initiate` + `md-lsps` + `protection-groups` + `srlg-disjoint-pair` -> `create-services`; `lsp/delete` + `md-lsps/delete` -> `delete-services`; `GET pce/lsps` -> `services` state / `query-services`.

### 9.1 Intent (what is in, what is out)

One entry per service: `service-id` (key), `simulator-run-id`, `source`, `destination` (`te-types:te-node-id`, the router ids, as in ietf-te), `bandwidth` (uint64 bit/s, mandatory, D8), `bidirectional`, `path-planning` (ordered `optimization-metric` list of exactly three identities: `path-metric-hop`, `path-metric-delay-average`, own `path-metric-delay-variation`; optional `candidate-path-count`, D4), optional presence container `protection` (`mode`, `diversity`, `revertive`, `hold-off-time` ms, `wait-to-revert` s), and the `tunnel` list (D1): `tunnel-id`, `role`, `priority`, `signal`, optional `wire-tunnel-id` (D5), and the `attempt` correlation (`operation-id`, `attempt`, `final-attempt`, `triggered-at`, `upstream-waiting-delay`). `must` rules: exactly one primary; an unprotected service has one tunnel (not enforced by the server for RPC input, so explicit PCE checks, as in section 10.3 of the run-start inventory). No path, no explicit route, no reference path, no wire LSP id other than the transitional leaf, no OF code, no switching type, no symbolic name.

Naming against ietf-te: `source`, `destination`, `bidirectional`, `optimization-metric` / `metric-type` (te-types identities), `protection`, `hold-off-time`, `wait-to-revert`, `oper-status` (`te-types:te-oper-status`). I did not verify the exact ietf-te data tree (it is not vendored, README Decisions); the names are chosen from the te-types identities and typedefs that are vendored and from memory of the draft, so treat the "rename, not redesign" claim as a goal, not a checked fact. Deliberate deviations: bandwidth is uint64 bit/s, not `te-types:te-bandwidth` (a string, bytes per second, D8); `diversity` is the old four-value enum, equivalent to `te-path-disjointness` bits `{} {link} {node} {srlg}`.

### 9.2 RPCs instead of edit-config (D3)

Considered: a config list `service` written with edit-config, candidate and commit, per-entry operational state. Chosen: explicit batch RPCs. Reasons.

1. **Side effects.** Creating a service sends PCEP and RSVP signaling. `discard-changes` or a failed commit cannot unsend it; rollback of a create is a delete, which is an operation, not a datastore state. The only real "rollback" in this domain is the Backend's decision to tear down.
2. **Volume and churn.** Thousands of services per run (batch limit 20000), created in bursts and deleted on simulated lifetime. A config list of that size in the controller datastore is persistent state with high churn; the plan wants the controller to keep nothing durable and the Backend (with its `service_store`) is the source of truth for what should exist. A PCE restart already loses its in-memory state per run.
3. **Idempotency** is per `service-id` in both models; the RPC form states it explicitly (replay, conflict on different content, higher `attempt` to retry a failed tunnel) and fixes today's two inconsistent behaviours.
4. **Per-entry results** in one round trip are natural for an RPC output; with config they need a second read.
5. **Fit with the existing repo**: `salasim-fleet`, `salasim-pce-fleet`, `salasim-pce-run` already use controller RPCs with per-device results.

What is lost: declarative reconciliation after a controller restart. The Backend does that through `query-services` and idempotent re-issue.

### 9.3 Admission, not outcome, on the device RPC (D7)

`create-services` and `delete-services` return when each entry is admitted, replayed or refused, **never when signaling ends**. Reasons: a domain create blocks up to 100 s and a multi-domain one up to 245 s today; a NETCONF session carries one request at a time per mounted device as far as I know (unverified), and the ODL default request timeout is, as I remember, 60 s (unverified; check `default-request-timeout-millis` of the pinned netconf-topology). A blocking RPC would serialise the PCE's whole management channel behind the slowest create. So the PCE must admit quickly and report through (a) the operational `services/service/tunnel/lifecycle-state`, (b) the advisory notification, (c) the unchanged telemetry facts. The controller front end offers `wait=outcome` (default `admission`, `timeout` 1..300 s, default 120) by polling the PCE state (event-driven where the notification subscription exists, poll fallback), so the Backend can keep its present "call returns the outcome" shape in the first phase.

New PCE state this requires (honest cost): a per-run service registry holding every admitted tunnel, its state, last attempt and failure, and removed tunnels as tombstones until the run is reset (needed for replay and for `failed`/`removed` to be visible; today a failed parent create leaves only a fact and domain removal leaves nothing). Size: services per run times tunnels, tens of thousands of small entries, cleared by `prepare-run` / `reset-run`.

### 9.4 Batch and scale limits

* `max-elements 256` services per RPC at both levels (`create-services`, `provision-services`, `delete-services`, `query-services` by id). Justification: the Backend's concurrency is 32 batch creates and 64 dispatches; 256 is four dispatch windows; a one-leg create entry is about 1 KB of XML, so a full batch is about 256 KB per RPC (PCE NETCONF message size limit unverified). The controller splits a `provision-services` call per owner PCE into chunks of at most 64 and sends PCEs in parallel (`max-parallel` 1..64, default 8), the chunks to one PCE sequentially.
* Admission is O(1) per entry on the PCE (validate, registry insert, enqueue), so 5000 services in a slice cost on the order of 80 chunks and seconds, not the 100-260 s a blocking call would hold a session for. Not measured.
* `query-services`: at most 1000 per page (`limit`, default 500, `marker`); a full `get` of the `services` list at ten thousand entries is megabytes and must not be polled.
* Entries are independent: no all-or-nothing across a batch (a failed neighbour must not undo an established LSP; matches today's per-tunnel operations).

### 9.5 Operational state, notification, polling

`services` (config false) shows per service the intent as accepted, `oper-status`, `selected-tunnel`, and per tunnel `symbolic-path-name`, `lifecycle-state` (`requested, establishing, active, down, failed, removing, removed`), `path-state` (RFC 8231 O-field, worst across domains, absent until a domain reported), `ero` (the assigned path as a result), `lsp-id`, `domain-lsp`, `reuse-result`, `protection-degraded` (+ reason, shared links), `updated-at`, and `failure` (`type` as an open string because the Backend classifies by the PCE's `failureType`, `retry-safe`, `outcome-uncertain`, `retry-after`, `no-path-class`, `retry-reason`, `anydata details` for the multi-domain diagnostics). Reroute counters are **not** modelled: the PCE has none (section 6.3); reroute evidence is facts.

`service-state-changed` is defined but advisory. Notifications over NETCONF are not durable, and facts already carry richer evidence (attempt id, timing). The Backend must not depend on it; the controller uses it to end a `wait=outcome` early, with polling as the fallback. Whether the controller can subscribe to a mounted PCE's notifications is exactly the unverified C1-0 spike of the hub plan.

### 9.6 Routing a service to the PCE (D6) and what the controller keeps

Rule (the Backend's today, moved): both endpoints in the same domain and that domain is not `core` -> that domain's PCE; otherwise the parent PCE. Unknown router id -> entry refused `controller-unknown-endpoint` (result-certain, retry-safe). PCE not mounted or not connected -> `controller-pce-unavailable`, retry-safe (nothing was sent). Failure after the request was sent (timeout, session loss) -> `outcome-uncertain true`.

Source of the mapping: the deployment manifest the controller already needs to mount devices (hub plan C0 items 3-4) must list, per emulator, its `router-id` and `domain-id`, and per domain its PCE device. The controller builds the table at start in memory and exposes it read-only as `routing`. Nothing is persisted; a restarted controller rebuilds it. Alternatives in D6.

The controller keeps: the routing table (derived, rebuilt), in-flight `wait=outcome` calls (lost on restart, the Backend repeats; every operation is idempotent), and optionally a soft `service-id -> owner` cache (never authoritative; absent means ask every PCE or use the `owner` in the request). Not kept: services, tunnels, paths, results. For delete the Backend passes `source` and `destination` again as routing keys.

### 9.7 Failure and rollback semantics

No candidate/commit and no controller-side rollback of provisioning: a failed tunnel is reported (`failed`, with `retry-safe`) and the Backend decides (retry in the next slice with a higher `attempt`, or give up), exactly as now. A protected service whose primary failed gets its standby established without a diversity reference and `protection-degraded` set (decision D2: this rule moves into the PCE; it is `_prepare_protection_fallback` today).

### 9.8 Example (hand-written, names checked against the pyang tree, not machine-validated: the DSDL plugin supports YANG 1.0 only)

```xml
<provision-services xmlns="urn:salasim:service-fleet">
  <service>
    <service-id>svc-3f2a</service-id>
    <simulator-run-id>simrun-0123456789ab</simulator-run-id>
    <source>10.1.0.4</source>
    <destination>10.2.0.9</destination>
    <bandwidth>100000000</bandwidth>
    <path-planning>
      <optimization-metric><metric-type xmlns:te-types="urn:ietf:params:xml:ns:yang:ietf-te-types">te-types:path-metric-delay-average</metric-type></optimization-metric>
      <optimization-metric><metric-type xmlns:te-types="urn:ietf:params:xml:ns:yang:ietf-te-types">te-types:path-metric-hop</metric-type></optimization-metric>
      <optimization-metric><metric-type xmlns:sst="urn:salasim:service-types">sst:path-metric-delay-variation</metric-type></optimization-metric>
      <candidate-path-count>3</candidate-path-count>
    </path-planning>
    <protection><mode>dedicated</mode><diversity>srlg</diversity><revertive>true</revertive><hold-off-time>200</hold-off-time></protection>
    <tunnel><tunnel-id>tun-a1</tunnel-id><role>primary</role><attempt><operation-id>op-77</operation-id></attempt></tunnel>
    <tunnel><tunnel-id>tun-b2</tunnel-id><role>standby</role><priority>10</priority><attempt><operation-id>op-78</operation-id></attempt></tunnel>
  </service>
  <wait>outcome</wait><timeout>120</timeout>
</provision-services>
```

## 10. Decisions for the user (recommendations)

| # | Decision | Options | Recommendation and why |
|---|---|---|---|
| **D1** | Who expands a protected service into tunnels, allocates their ids and decides sharing | (a) Backend keeps it; the intent lists the tunnels with Backend-allocated ids. (b) PCE/controller expand from the protection spec and report ids | **(a) for v1.** The evidence chain (facts, `tunnel_store`, symbolic names, `_parent_create_success_fact`) is keyed by Backend ids, and shared-protect tunnels are shared across services under a Backend lock; moving that is the largest and riskiest part. The YANG supports (b) later (tunnel list becomes output). Plan item "intent without legs" is therefore met for path and algorithm, not for leg count. |
| **D2** | Who orders legs and applies the fallback (primary first, standby after the primary is established and diverse, fallback to non-diverse and mark degraded, group registration) | Backend (as now), PCE, controller | **PCE.** It already owns the group registry, the reference path, the degraded marking and the facts; the controller would have to wait minutes and hold state; keeping it in the Backend would force the Backend to send per-leg path hints (explicit EROs, reference names), which the plan forbids. Cost: new PCE orchestrator, about 8 of the 30 days. |
| **D3** | RPC vs edit-config | see 9.2 | **RPC** (9.2). |
| **D4** | `candidate-path-count` | keep as is (required); optional with a run default; drop | **Optional leaf now, move the default into the PCE run config** (a `prepare-run` field, owned by the other modules) later; the Backend keeps sending it in phase 1. It is an algorithm knob, not a business need. |
| **D5** | Wire tunnel number | Backend allocates (now); PCE/PCC allocates | **PCE allocates**, reported in the state; keep `wire-tunnel-id` as a transitional optional input. Today's `MAX+1` per sender is unbounded against a 16-bit field (unverified). Changing it alters the PCInitiate (no `IPv4LSPIdentifiersTLV` if absent), so test it on its own. |
| **D6** | Routing source | manifest table (router id to domain) in the controller; Backend sends the domain; ask the parent PCE's reachability | **Manifest table.** Static per deployment, no PCE dependency, rebuilt at start. Needs the manifest to carry `router-id` and `domain-id` per emulator and the domain of ground stations (unverified how ground stations are assigned). Parent reachability is dynamic but adds a read per service. |
| **D7** | Device RPC returns at admission, controller `wait=outcome` | blocking RPC | **Admission** (9.3), because of session blocking and the ODL timeout (both unverified, to be measured in a spike before step S6). |
| **D8** | Bandwidth type | uint64 bit/s; `te-types:te-bandwidth` string | **uint64 bit/s now**, convert at the ietf-te migration. te-bandwidth is a bytes-per-second float string; no gain today. |
| **D9** | How the objective is expressed | ordered metrics (as the wire is today); numeric OF 1003/1004/1005 | **Ordered metrics.** The OF code is retired on the wire and derived; please confirm the hub plan's wording of C2b item 1 may change to "ordered quality metrics (OF is derived)". |
| **D10** | Teardown of something already gone | domain behaviour (200 removed); parent behaviour (404) | **200 `removed` for both**, with tombstones per run; removes the 404 failure mode. |
| **D11** | Domain create run fencing | none (now); stale-run check like the parent | **Add the check** (409 `stale-run`); one line, closes a gap. |
| **D12** | Protected intra-domain pair: stability semantics | keep today's current-graph pair (no cross-snapshot window); use the windowed search | **Keep today's computation** inside the PCE for equivalence (the plan says "不改算法"); a windowed joint pair is a separate decision with its own design (the class comment defers it). |
| **D13** | Evidence of outcome | facts only; plus notifications | **Facts (JetStream) stay the evidence**; the notification is advisory (9.5). |
| **D14** | Domain create confirmation | emulator `GET /lsps` poll (now); PCE-reported state | **PCE-reported state:** a domain create returns when PCRpt arrives and its `path-state` is in the reply, which removes the direct emulator read (rule 1). It changes "emulator-visible within 150 s" into "PCRpt-confirmed"; the two can differ, so measure on 169. |

## 11. Migration plan

Principle (as in the run-start plan): the typed intent object is the contract between input and provisioning. Both entry points build it and call one provisioner; the HTTP routes stay until the last step, every step is revertible by switching the Backend back (`SALASIM_SERVICE_PROVISION_VIA=pce`, the default per hub plan C2b item 4).

```
HTTP lsp/initiate, md-lsps, protection-groups, srlg-disjoint-pair --JSON adapter--> ServiceIntent --\
NETCONF create-services --DOM mapper--------------------------------------------> ServiceIntent ---+--> ServiceProvisioner (existing IDH / PMS / PGR behind it)
```

| Step | Owner repo | Content | Tests proving equivalence | Revert | Days |
|---|---|---|---|---|---|
| S0 | yang | Review and freeze `salasim-service*`; run `pydantify` on the data trees; decide D1-D14 | `validate.sh`; generated models compile | drop the three files | 0.5 |
| S1 | backend | Golden capture: record, through a recording stub of `PceAgentClient`, every request the Backend builds for these cases: domain/parent, unprotected, protected link/node/srlg, bidirectional, dedicated/shared/mixed, retry of a failed batch, teardown, registration. Store under `docs/service-provisioning-golden/` with a capture script like `capture_sim_start.py`; adapt `test_bidirectional_services`, `test_protection_*`, `test_parent_async_timeout`, `test_lsp_dispatch_isolation` fixtures | the captured set is the oracle; a mapping legacy-request -> intent -> legacy-request is the identity except the documented dropped fields | none (new files) | 2 |
| S2 | pce | `ServiceIntent` immutable object, JSON adapter for the legacy bodies (domain, parent, registration, pair) and DOM mapper for the RPC; `ServiceRegistry` (admitted tunnels, states, tombstones, content hash for replay, attempt rules); explicit checks for every mandatory/must (section 9.1); typed errors (400 for caller errors, not 500) | for every golden body: JSON adapter and DOM mapper give `equals()` intents; table-driven rejection tests for each bad input assert the error and that no state changed | the new entry points are additive | 4 |
| S3 | pce | `ServiceProvisioner`: primary first, standby after the primary is established (reuse `awaitReferenceEro` and the domain LSP-DB), fallback and degraded marking, group registration derived from the intent, domain run fencing (D11), D10 teardown semantics; call the existing handlers unchanged | PCE harness (style of `PceApiRuntimeSingleDomainLspTest`, `LegacyPceControlPlaneResetTest`): the same golden service through the legacy sequence and through the provisioner gives equal LSP-DB, `ProtectionGroupRegistry`, and **equal emitted facts incl. `attemptId`** (capture `TunnelUpdateEmitter`) | provisioner unused if the Backend uses HTTP | 5 |
| S4 | pce | Intra-domain diverse pair inside the PCE: factor the `SRLG.execute` logic into a callable, used for the standby of a protected intra-domain service; the HTTP route stays | `SrlgDisjointPairComputeHandlerTest` plus: provisioner pair equals the pair the old endpoint returns for the same TED, for `link/node/srlg` | route untouched | 3 |
| S5 | pce (netconf) | RPCs `create-services` / `delete-services`, operational `services` (paged by key filter), `service-state-changed`; admission semantics; expose in `salasim_gmpls_netconf` like `salasim-pce-run` | NETCONF in-process test as in `schema-validation-probe` (XML to intent equals); batch of 256; replay and conflict cases | default off like `-Dnode.netconf.enabled` | 3 |
| S6 | controller | `provision-services`, `delete-services`, `query-services`, `routing` from the manifest; chunking, parallel PCEs, `wait=outcome`, error mapping (result-certain vs uncertain) against `device-stub` | stub PCEs: routing table cases (intra, cross, `core`, unknown), chunking, uncertain after a dropped session, no state kept across a controller restart; **spike first**: ODL request timeout, session serialisation, notification subscription | controller not used | 4 |
| S7 | backend | Adapter behind `SALASIM_SERVICE_PROVISION_VIA`: build the intent from the tunnel store, map results to the existing operation records and `failureType` names (so `_SAFE_UNESTABLISHED_CREATE_FAILURES`, `_parent_create_outcome_is_uncertain` keep working), stop sending the 14 dead / 3 path-hint fields, replace the emulator `GET lsps` poll by the returned `path-state` (D14), reconcile through `query-services`; keep `controlOperationId:1` attempt naming | golden: intent built from the same inputs equals the S1 oracle mapped; replay of S1 failure cases yields the same operation status and `failureType`; no emulator read in the provisioning path (grep test) | switch back to `pce` | 4 |
| S8 | all, on 169 | Same-seed A/B: provisioning success ratio, setup latency (PCInitiate to PCRpt and Backend trigger to confirmation), protection switch behaviour, `windowLagEvents == 0`, no request in the clock path (hub plan C2b acceptance) | the acceptance in the hub plan; plus fact-set comparison (same `TUNNEL_ESTABLISH` facts per tunnel) | switch back | 3 + 1 buffer |
| S9 | backend, pce | After acceptance only: delete the Backend's direct calls and the legacy emulator create path, the PCE routes and `IDH.handle` | grep guard: no `/api/v1/pce/lsp`, `/md-lsps`, `/protection-groups` in the Backend | revert the commit | 1.5 |

Estimate: about **30 agent-days** (range 24-40), dominated by S3 (leg orchestration) and S6/S7. If D1 chose (b), add roughly 10. Everything except S8 can be done and tested locally, as the run-start work was. Changes of the switch happen at run start only, never mid-run, so one run never has two owners of a service.

## 12. Not verified

* ODL netconf-topology request timeout and whether one mounted session processes RPCs one at a time (D7 reasoning). To be measured in the S6 spike.
* Maximum NETCONF message size accepted by the PCE server (batch of 256).
* Whether the controller can subscribe to a mounted PCE's notifications (the open C1-0 spike).
* The exact ietf-te data tree (not vendored); the naming claim is a goal.
* How ground stations map to domains in the Backend inventory (D6), and that the manifest can carry router id and domain per emulator.
* Whether `wire-tunnel-id` above 65535 is ever sent and what the PCC does with it.
* `anydata` handling in the controller binding for the `failure/details` leaf (as in the run-start inventory).
* The emulator SSE watcher in `_await_lsp_visibility` (read only partly); the claim "every domain create polls the emulator" rests on `_is_emulator_lsp_established` requiring `pathState`, which a domain reply lacks (read in code, not observed live).
* The Backend's behaviour when the PCE restarts mid-run (registry loss) was not traced; today a PCE restart also loses its LSP-DB.
