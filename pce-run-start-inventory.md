# PCE run start: field inventory, YANG design, migration plan

Date: 2026-10-05 (decision 2026-10-06: the PCE run-start parameters are fully modelled in YANG; the controller starts a PCE run through a typed NETCONF RPC with rollback; YANG is the single source of the data model).
**Status 2026-10-05 (second pass):** the user decided D1 (frame-schedule required), D2 (typed parser directly, not adapter-first), D5 (all tightenings), D7 (delete the three dead knobs and `countIdleAgainstCapacity`), and accepted the coordinator's defaults for D3, D4, D6, D8. Sections 1 to 8 describe the code as it is today (rows for deleted fields are marked DELETED); sections 9 to 12 are the decided design, the typed-parser-first migration plan and the final decision list. The ODL NETCONF server behaviour that the plan depends on was verified by experiment (section 10.2).

Scope of this document: inventory and design only. No backend, PCE, emulator or controller code was changed. The YANG draft is committed in `salasim_gmpls_yang` (commits 3844b95, 586cbf0). Evidence scripts are in `docs/pce-run-start-golden/`.

Method and confidence. Every row below was read from source on 2026-10-05, not inferred from names: Python in `salasim_gmpls_backend/src/salasim_backend/`, Java in `salasim_gmpls_pce/src/main/java/es/tid/pce/`. "No consumer" claims were checked by case-insensitive search of the whole PCE source tree including tests. Line numbers drift; function names are the stable handle. What could not be verified is listed in section 11.

Abbreviations: **TCS** = `topology_clock_service.py`. **RRC** = `sim/RunRuntimeConfig.java`. **SSH / PSSH / SCH** = `http/SimStartHandler.java`, `http/ParentSimStartHandler.java`, `http/SimClockHandler.java`. **Profile** = a configuration profile resolved by `configuration_defaults.py` / `configuration_schema.py` against `configuration_defaults/*.v1.yaml` (`fields:` = per-field bounds, `constraints:` = cross-field rules, `fieldDefaults.numericMinimum: 1` = default minimum of every numeric field not overridden).

## 1. Numbers

| Item | Domain PCE | Parent PCE |
|---|---|---|
| Top-level keys of the sim/start body | 20 (2 optional: `statisticsNatsUrl`, `frameSchedule`) | 20 (same) |
| Scalar leaves in a real captured body, excluding the `frames` array | 64 | 69 |
| ... of which in `runRuntimeConfig` (incl. envelope) | 27 | 32 |
| ... of which in `endToEndReuse` | 11 (10 read by PCE) | 11 (10 read) |
| ... of which in `frameSchedule` (incl. `networkOverrides`) | 9 (+3 legacy Gbps spellings) | 9 (+3) |
| Leaves not carried by the YANG model (derived, dead, constant, echo; the 3 deleted knobs and an empty token included) | 17 | 14 |
| Leaves of YANG `prepare-run` input | 49 (27 common + 22 domain case) | 57 (27 + 30 parent case) |
| Leaves of YANG `prepare-run` output | 26 (mostly frame-source telemetry; the backend reads 3 things) | same |
| sim/clock request leaves (legacy, one overloaded payload) | 8 (4 in a normal anchor change) | same |
| sim/clock response leaves | 19 (normal), 5 (probe), 4 (freeze rejected), 1 (ignored) | same |
| YANG `commit-clock` input / output | 5 / 22 | same |
| Deployment-profile leaves (not in the payload) | `domain-pce.v1.yaml` 65; 11 of them reach run start | `parent-pce.v1.yaml` 94; 28 reach run start (parent role); 12 of those same leaves (routingSearch, transient/structural curves) also reach every domain |

Lines of YANG: `salasim-run-config` 400, `salasim-run-runtime-config` 243, `salasim-pce-run` 333, `salasim-pce-fleet` 213, all pass `validate.sh`.

Golden check (section 10.4, step Y1): both real payloads (domain and parent) captured from the backend's own builder map into the YANG shape with no schema problems (47 resp. 55 leaves instantiated, every range, pattern and enum checked) and back to the original JSON with zero differences apart from the documented dropped/constant fields (domain 64 legacy leaves to 47, parent 69 to 55).

## 2. Layers: who owns a value, and how often it changes

| Layer | Meaning | Where it lives | Members (examples) | Changes |
|---|---|---|---|---|
| L0 per deployment, immutable | Rendered into the pod (`-D` system properties, k8s resources) by the compiler `scenario_compiler._apply_deployment_config`; pod carries the annotation `salasim.io/pce-config-digest` (`runtime_artifacts.PCE_CONFIG_ANNOTATION`) | `domain-pce`, `parent-pce`, `emulator`, `statistics-transport` profiles composed as category `deployment`; guarded by `tests/test_configuration_property_drift.py` (every `salasim.*` Java property must be profile-owned or listed in `_UNOWNED_BY_PROFILE`) | resources, jvm, executors, admission, timeouts, batching, retention. `_UNOWNED_BY_PROFILE` is layer 1 orchestration (`api.port`, `parent`, `deployment.id`, `domain.id`, `pcc.expectedCount`) and layer 4 constants | on redeploy only. Not in the run-start payload except via `configDigest` |
| L0' deployment-owned, frozen per run | Values of L0 profiles that the PCE re-reads at every run start ("Re-read and frozen for each simulation by POST /sim/start", `domain-pce.v1.yaml` precomputation) | same profiles, delivered in `runRuntimeConfig` | routingSearch, recovery curves, precomputation sizing | between runs without redeploy |
| L1 per run, profile | Simulation Run profile category `simulation-run`: `simulation`, `workload`, `fault` | `simulation.v1.yaml` | speedup, sliceCount, stabilityWindowFrames, precompute mode, endToEndReuse.*, networkDefaults.*, topologyWindow.* (derived) | per run |
| L2 per run, orchestration | Not in any profile | backend `settings`, deployment metadata, runtime IDs | runId, simulatorRunId, statistics URLs, internal token, anchors, horizon | per run |
| L3 per clock commit | The operator entry points of invariant I1 | backend `start_simulation`, `pause_simulation`, `resume_simulation`, terminal drain | wallAnchorMs, simAnchorMs, speedup, terminal flags | several times per run |

Fixed block, not a profile: `configuration_defaults.FIXED_RUN_CONFIG_BLOCKS` pins `routing.constraints` (three `REJECT` strings) because `RoutingRunConfig.java` throws on anything else.

## 3. Transport, retries, errors (today)

- Endpoints on every PCE (`PceApiServer`): `POST /api/v1/sim/start`, `/sim/clock`, `/sim/release`, `/sim/reset`, `/sim/frames`; `GET /sim/status`. sim/start and sim/clock use `RawJsonCommandHandler` on the fast executor, so a slow handler is bounded by `apiCommandMs`. The parent's start also runs real child PCInitiate DELETEs when the connection pool is enabled (`ParentSimStartHandler`: `reclaimPool(-1, true)`), so its duration is not bounded by parsing.
- Backend client: `_post_all` fans out with up to 16 workers, timeout `_HTTP_TIMEOUT` 30 s, 2 retries (0.25 s backoff) on connection errors and 5xx; terminal clock commits use 8 retries (0.5 s). 4xx is terminal.
- Error mapping: `IllegalArgumentException` becomes 400, everything else 500 for sim/start. **sim/clock turns every exception, even `IllegalStateException`, into 400 "Invalid JSON"** (`SimClockHandler.handle`). A run-id mismatch on sim/clock is a successful 200 `{"ignored": true}`.
- What the backend reads back: see section 7. It treats a start target as failed if `error` is set or `runtimeConfigDigest` differs from what it sent (`_raise_for_failed_start_targets`), and a clock commit as failed unless `error` is absent, `ignored` is falsy and `phase == "playing"` (start commit only, `start_simulation`).

## 4. Domain PCE `POST /api/v1/sim/start`

Python builder: `TCS._build_start_targets` (domain loop), inputs prepared in `start_simulation` (push_config, around the `push_config = {` block). Java consumer: `SimStartHandler.execute`. "Py" = validated in Python before sending, "Java" = validated by the PCE. "req" = rejected when absent. All times in the legacy body are epoch milliseconds unless stated.

| Field | JSON type | Required | Default (if absent) | Bounds / values | Py validates | Java validates | Consumer | Layer / owner |
|---|---|---|---|---|---|---|---|---|
| `runId` | string | Java req | none | non-empty, `"<deploymentId>-run-<wall ms>"` | built from a safe deployment id (`k8s_safety._SAFE_ID` via `assert_safe_identifier`) | non-empty, **not trimmed** (the other three ids are) (`SSH.execute`) | `SimulationRun`, run fencing (stale-run 409s), `SimulationFaultRegistry.clearRun`, frameSchedule echo | L2, backend `start_simulation` |
| `simulatorRunId` | string | Java req | none | `"simrun-<12 hex>"` | none | non-blank (`requiredIdentity`) | `PceWebhookSender` (`beginRun`, `validateRunStart`: a run id that already ran cannot restart), `SimulationRun`, telemetry stamping | L2 |
| `deploymentId` | string | Java req | none | safe id | `assert_safe_identifier` | non-blank | `SimulationRun` (stamping) | L2; the PCE already knows it as `salasim.deployment.id` (L0 orchestration) and never compares |
| `domainId` | string | Java req | none | domain name, `"core"` for the parent | none | non-blank | `SimulationRun`; `FrameScheduleDescriptor.domainId` selects the ground-link bandwidth default in `NetworkOverrides` (substring `ground` in the id) | L2; PCE knows it as `salasim.domain.id`, never compares |
| `serviceType` | string | no | none | derived from the URL (`_infer_service_type`) | n/a | **not read anywhere** (search of PCE source: no hit) | none | dead field |
| `statisticsBackendUrl` | string | Py req, Java optional | none (telemetry then undelivered: log "awaiting backend URL") | base URL; Java strips trailing `/`, no syntax check | required, non-empty (`start_simulation` raises) | `PceWebhookSender.normalizeBackendUrl`; cannot be retargeted once the outbox is open (409-style IllegalState) | `PceWebhookSender.setBackendUrl` | L2: `statisticsBackendBaseUrl` in deployment metadata or `settings.statistics_backend_base_url` |
| `statisticsNatsUrl` | string | no | absent = HTTP run | `nats|tls|opentls|ws|wss://host[:port]`, comma separated | emitted only when `settings.statistics_jetstream_enabled`; `_build_statistics_nats_url` raises if enabled and empty | `normalizeNatsUrl` (scheme check, message names the field), `validateRunTransport` (no retarget for the same run) | `PceWebhookSender.openRunTransport` | L2: derived from `nats_deploy.nats_service_url(core namespace)` |
| `internalToken` | string | no | "" (treated as unset) | secret | `settings.backend_internal_token` | none | `PceWebhookSender.setInternalToken`, authenticates callbacks to the backend | L2 secret |
| `wallAnchorMs` | long | no | now | epoch ms; `speedup` 0 here, so irrelevant | none | none | `ClockAnchor` | L3 (prepare: `_utc_now_ms()`, derived) |
| `simAnchorMs` | **ISO-8601 string here** (sim/clock sends an integer; Java accepts both, `parseSimAnchorMs`: `Instant.parse` else `Long.parseLong`) | no | now | absolute simulated instant | derived from `simulation.playAnchorEffectiveTime` (profile `startTimePolicy` RUN_START / FIXED + `fixedStartTime`) | parse only | `ClockAnchor`, clock threads | L1 profile -> L3 |
| `speedup` | double | no | 1.0 | prepare always sends **0.0** (armed frozen); `simulation.speedup` is 0 < x <= 1000 | none at this step | **none** (negative is silently "paused") | `ClockAnchor` | L1 `simulation.speedup`, committed later by sim/clock |
| `inventoryCapacity` | int | no | PCE param `getDefaultSimInventoryCapacity()` if > 0, else 5 (domain); 5 (parent) | >= stabilityWindowFrames + 1 | checked in `start_simulation` (`capacity < stability_window_frames + 1` raises); value from `topologyWindow.pceCapacitySliceCount` (derived `2 * (window + 1)`) | none | `SimulationRun` frame inventory; `ScheduleFileFrameSource.windowClamped` | L1 derived |
| `resultSliceCount` | int | Java req | none | >= 1, profile `sliceCount` 1..10000 | `start_simulation`: positive **only if duration > 0** (a zero duration lets 0 through) | `SimulationRun.configureResultHorizon`: `count <= 0` rejected. **Checked after `SimRegistry.resetForStart`, `new SimulationRun`, `ChildImpactPublisher.startRun`**, so a bad value leaves a half-started run (contradicts the "validated before any state is touched" comment above it) | `SimulationRun` (activatable frames, terminal boundary) | L1 `simulation.sliceCount` |
| `topologyFrameCount` | int | no | none | sliceCount + stability window + 1 (backend check against the schedule length) | `start_simulation` | **not read** | none (backend validates the compiled schedule with it) | dead on the PCE |
| `horizonSimTimeMs` | long | Java req | none | >= 0 | derived: anchor + `sliceDurationSeconds * sliceCount * 1000` | `configureResultHorizon`: `< 0` rejected (same late check) | `SimulationRun` | L1 derived |
| `routingConfig` | object | Java req | none | exactly `{"constraints": {...}}` | `_build_start_targets` requires key set `{"constraints"}` | `RoutingRunConfig.from` | `SimRegistry.resetForStart` (the object only carries compat fields) | fixed block |
| `routingConfig.constraints.missingBandwidthPolicy` / `missingTeDataPolicy` / `unsupportedEndpointPolicy` | string | Java req | none | only `REJECT` | none (injected by `FIXED_RUN_CONFIG_BLOCKS`) | must equal `REJECT` | none (validation only) | constant |
| `runRuntimeConfig` | object | Java req | none | exact key set, see section 6 | `_build_pce_run_runtime_config` | `RunRuntimeConfig.from` (`requireExactKeys` at every level) | `SimRegistry.resetForStart`, precompute workers, recovery | L0' + L1 |
| `endToEndReuse` | object | Java req | none | see below | `_build_start_targets` | `EndToEndReuseRunRequest.parse` | `EndToEndLspReuseRegistry.configureForRun` | L1 `simulation.endToEndReuse` |
| `endToEndReuse.enabled` | bool | no | false | | none | `Boolean.TRUE.equals` | registry | L1 |
| `...selectionMode` | string | Java req | none | `SEEDED_RANDOM` only | none | non-null in parse; `configureForRun` rejects anything else | registry | L1 (profile enum has one value) |
| `...randomSeed` | long | Java req | none | profile 0..2147483647 | none | `getLong` non-null | registry | L1 |
| `...retainRatio` | double | Java req | none | 0..1 | none | `requiredRatio` 0..1 | registry | L1 |
| `...maxIdleEntries` | int | Java req | none | 0..4096 | integer, 0..4096 (`type(x) is int`) | `requiredInt` 0..4096 | registry | L1 |
| `...retainTtlSlices` | int | Java req | none | 0..4096 | same | same | registry | L1 |
| `...maxIdleBandwidthBps` | int | Java req | none | >= 0, 0 = unbounded | integer >= 0 | number >= 0 | registry | L1 |
| `...bandwidthMatch` | string | Java req | none | `EXACT`, `AT_LEAST` | checked | `requiredChoice` | registry | L1 |
| `...objectiveMatch` | string | Java req | none | `STRICT`, `ANY` | checked | `requiredChoice` | registry | L1 |
| `...idleReuseForRecovery` | bool | Java req | none | | `type is bool` | non-null | registry | L1 |
| `...countIdleAgainstCapacity` | bool | no | n/a | | none | **DELETED (D7)**; today **ignored** (parse rejects only the old key `maxEntries`) | read by the backend only (`routers/services.py` admission accounting) | backend-only, leaks into the PCE body because `dict(end_to_end_reuse)` is passed whole |
| `frames` | array of objects | no | `[]` | one full frame per initial index; unbounded size | n/a | each element needs `index` >= 0 and `simTime`; bad ones are skipped with a warning, not rejected (`parsePreparedFrames`) | `SimulationRun.addPreparedFrames`, `LinkIdentityRegistry` | opaque blob (section 8) |
| `frameSchedule` | object | no | absent = backend pushes frames through `/sim/frames` | see below | `_frame_schedule_descriptors`: all-or-nothing across all PCEs, only when `SALASIM_PCE_FRAME_SOURCE=schedule` and the files exist | `FrameScheduleDescriptor.parse` | `FrameSchedule.load`, `ScheduleFileFrameSource` | L2 |
| `frameSchedule.version` | int | Java req (when object present) | none | must be 1 | constant | must equal 1 | none | constant |
| `...scheduleFile` | string | Java req | none | absolute path | constant `POD_DOMAIN_SCHEDULE_FILE` | non-empty, absolute | frame source | constant of the image layout |
| `...runtimeDir` | string | no | `scheduleFile/../..` | absolute | constant `POD_ARTIFACTS_RUNTIME_DIR` | absolute if given | frame source | constant |
| `...marginFrames` | int | no | system property `salasim.pce.frameSource.marginFrames`, 2 | >= 0 | never sent | non-negative | `ScheduleFileFrameSource` | override of an L0 constant |
| `...runId`, `...domainId` | string | no | n/a | must equal the body's | echo | `requireEcho` | none | echoes |
| `...networkOverrides` | object | no | absent/empty = files as is | see below | `network` from `simulation.networkDefaults`, deep-copied; omitted if falsy | must be an object | `NetworkOverrides.apply` on every loaded frame | L1 `simulation.networkDefaults` |
| `...networkOverrides.islBandwidthBps` / `gslBandwidthBps` / `fiberBandwidthBps` | number | no | | profile >= 1, default 400000000000 | none | none; **both Python and Java raise any value below 1 Mbit/s to 1 Mbit/s silently** (`max(1_000_000, ...)`) | `NetworkOverrides.pickLinkBps` | L1 |
| `...networkOverrides.bandwidth.{islGbps,gslGbps,fiberGbps}` | number | no | | legacy spelling | none | accepted (`pickLegacyLinkGbps`) | same | legacy; no profile produces it |

Domain-only behaviours worth knowing: the PCE falls back to its own parameter for `inventoryCapacity`; `SimulationRun` retention comes from `runtimeConfig.precompute.cacheRetentionSlices` (parent hard-codes 1); `domainPceServer.sendReachabilityNow()` is a side effect of start.

## 5. Parent PCE `POST /api/v1/sim/start`

Same handler shape (`PSSH.execute`), same builder (`base_core_payload`, `domainId` = `"core"`). Differences:

| Aspect | Domain | Parent |
|---|---|---|
| `inventoryCapacity` default | PCE parameter, else 5 | 5 |
| `runRuntimeConfig.role` | `"domain"` | `"parent"` (PCE refuses a mismatch) |
| precomputation / recovery shape | section 6.1 | section 6.2 |
| `frames[i]` content | needs `topology` object only for `SimTopologyFrameParser`; also `nodes` | needs a non-empty `topology` object, else the frame is skipped silently |
| `frameSchedule.scheduleFile` | `topology-schedule.json` | `parent-schedule.json` |
| `networkOverrides` hint | `domainId` | `isParent = true` (no ground-name sniffing) |
| `SimulationRun` frame retention | `cacheRetentionSlices` | 1 |
| Start side effects | reset domain state, reachability nudge | `reclaimPool` (real child PCInitiate DELETEs), reroute fence, MD-LSP DB wipe, protection-group clear |
| Validation order | resultSlice/horizon check after `resetForStart` | same, but after the pool reclaim and DB wipes, so a bad horizon costs real signaling |

All other fields (`runId`, `simulatorRunId`, `deploymentId`, `domainId`, statistics*, anchors, `speedup`, `resultSliceCount`, `topologyFrameCount`, `horizonSimTimeMs`, `routingConfig`, `endToEndReuse`, `frames`, `frameSchedule`) have exactly the rows of section 4. The two handlers each carry their own copy of `parseSimAnchorMs`; `EndToEndReuseRunRequest` and `SimStartHandler.requiredIdentity` are shared.

## 6. `runRuntimeConfig` in detail

Built by `TCS._build_pce_run_runtime_config(deployment_config, role, config_digest, stability_window_frames, precompute_mode)`, parsed by `RunRuntimeConfig` which **rejects any missing or unsupported key at every nesting level** (`requireExactKeys`; tests `RunRuntimeConfigTest`). The profile bounds below are the `fields:` entries of the owning yaml; "min 1" means the profile-wide `fieldDefaults.numericMinimum`.

Envelope (both roles):

| Field | Type | Req | Values | Py | Java | Source |
|---|---|---|---|---|---|---|
| `schemaVersion` | int | Java req | must be 1 | constant | `requiredPositiveInt`, must be 1 | constant |
| `configDigest` | string | Java req | `sha256:<hex>` | non-empty (`config_digest` argument) | non-blank; **only echoed back** in the response and in `/sim/status` | `configuration_digest(composed deployment)` |
| `role` | string | Java req | `domain`, `parent` | argument | must equal the handler's role | constant per target |

### 6.1 Domain PCE

| Field | Type | Profile default | Profile bounds | Java check | PCE-side clamp | Consumer | Source key |
|---|---|---|---|---|---|---|---|
| `routingSearch.boundarySearchStrategy` | string | `FULL_COST` | `FULL_COST`, `LEGACY` | exactly those two | | `MDHPCEMinNumberDomainsKSPAlgorithm`, `RouteSelectionEvidence` | `parent-pce.routingSearch` (both roles) |
| `routingSearch.computeTimeoutMs` | long | 16000 | min 1 | positive | | `RouteSearchBudget`, `/sim/status` | same |
| `routingSearch.maxChildRequestCountPerTunnel` | int | 96 | min 1 | positive | | `RouteSearchBudget`, KSP algorithm | same |
| `routingSearch.maxPrecomputeChildRequestCountPerTunnel` | int | 48 | min 1; constraint atMost the previous | positive | PCE takes the min of the two | same | same |
| `routingSearch.maxCandidatePathCount` | int | 64 | min 1 | positive | derives four legacy limits in `RoutingRunConfig` | KSP algorithm, `RoutePlanningPolicy` | same |
| `routingSearch.postSuccessLookahead` | int | 3 | 0..64 | non-negative | | KSP algorithm | same |
| `precomputation.enabled` | bool | false | | boolean | | `SimStartHandler` (starts workers) | `domain-pce.precomputation.enabled`, **overridden by `simulation.precomputation.mode` ON/OFF** (INHERIT keeps it) |
| `precomputation.applyEnabled` | bool | false | | boolean | | `RouteApplier` | `domain-pce.precomputation.applyEnabled`; with mode ON/OFF equals `enabled` |
| `precomputation.stabilityWindowFrames` | int | 3 | 1..100; constraint atMost `sliceCount - 1` | positive | | window logic in 12 classes | **`simulation.precomputation.stabilityWindowFrames`** (not a PCE profile value) |
| `precomputation.computeParallelism` | int | 8 | 1..128 | positive | | `DomainRoutePrecomputer` | `domain-pce.executors.precomputePathComputation.workerCount` (`_positive_nested`) |
| `precomputation.computeQueueCapacity` | int | 256 | min 1 | positive | | same | `...precomputePathComputation.queueCapacity` |
| `precomputation.applyParallelism` | int | 8 | 1..128 | positive | | `RouteApplier` | `...precomputedRouteApply.workerCount` |
| `precomputation.applyQueueCapacity` | int | 512 | min 1 | positive | | `RouteApplier` | `...precomputedRouteApply.queueCapacity` |
| DELETED (D7) `precomputation.futurePcreqTimeoutMs` | long | 120000 | min 1 | positive | **raised to >= 100** | **none** | `domain-pce.precomputation` (passed through whole by `**dict(precompute)`) |
| `precomputation.cacheRetentionSlices` | int | 1 | min 1 | non-negative (0 legal) | | `SimStartHandler` -> `SimulationRun` retention | same |
| DELETED (D7) `precomputation.pcreqBundleSize` | int | 32 | min 1, no max | positive | **lowered to <= 32** | **none** | same |
| `precomputation.hysteresisMinHopImprovement` | int | 0 | min 0 | non-negative | | `RouteApplier` | same |
| DELETED (D7) `precomputation.transientLinkPenalty` | double | 1000.0 | min 1 (not overridden) | non-negative (0 legal) | | **none** | same |
| `recovery.transient.{initialDelaySlices, initialDelayRounds, maximumDelaySlices}` | int x3 | 1, 1, 4 | 1..16, 1..8, 1..64; constraint `initial <= maximum` | positive; `maximum >= initial` | | `LspRerouteBackoff` | `parent-pce.recovery.transient` (both roles) |
| `recovery.structural.{same three}` | int x3 | 2, 1, 8 | same | same | | `LspRerouteBackoff` | `parent-pce.recovery.structural` |

### 6.2 Parent PCE

`routingSearch` and `recovery.transient|structural` as above. The rest differs:

| Field | Type | Profile default | Profile bounds | Java check | Consumer | Source key |
|---|---|---|---|---|---|---|
| `precomputation.enabled` / `applyEnabled` | bool | false / false | | boolean | `ParentSimStartHandler`, `CrossDomainRoutePrecomputer` | `parent-pce.precomputation`, same mode override (Python requires both to be real `bool`) |
| `precomputation.stabilityWindowFrames` | int | 3 | 1..100 | positive | `ParentMdLspReroute`, precomputer | `simulation.precomputation.stabilityWindowFrames` |
| `precomputation.workerCount` | int | 48 | 1..64 | positive | `CrossDomainRoutePrecomputer` (`parentWorkerCount`) | `parent-pce.executors.crossDomainPrecomputation.workerCount` |
| `precomputation.impactCoalescingMs` | long | 1000 | min 1 | positive | same | `parent-pce.timeouts.crossDomainPrecomputationImpactCoalescingMs` |
| `precomputation.passDeadlineMs` | long | 80000 | min 1 | positive | same | `...PassDeadlineMs` |
| `precomputation.deadlineSafetyMs` | long | 10000 | min 1; constraint `<= passDeadlineMs` | positive | same | `...DeadlineSafetyMs` |
| `precomputation.perLspBudgetMs` | long | 8000 | min 1 | positive | same | `...PerLspBudgetMs` |
| `recovery.compensation.{initialDelaySlices, initialDelayRounds, maximumDelaySlices}` | int x3 | 1, 2, 4 | 1..16, 1..8, 1..64 | positive; `max >= initial` | `PendingCompensationReconciler` | `parent-pce.recovery.compensation` |
| `recovery.compensation.evidenceBoundAfterAttempts` | int | 3 | 1..16 | positive | same | same |
| `recovery.capacityContention.maximumAttemptsPerSlice` | int | 3 | 1..8 | positive | `LspRerouteBackoff` | `parent-pce.recovery.capacityContention` |
| `recovery.capacityContention.cooldownMs` | int | 2000 | 100..60000 | positive | same | same |
| `recovery.outageRecovery` (object, **optional**) | | present in the defaults | | absent = 1 attempt, no cooldown | same | `parent-pce.recovery.outageRecovery` |
| `...outageRecovery.maximumAttemptsPerSlice` | int | 2 | 1..3 | positive | same | same |
| `...outageRecovery.cooldownMs` | int | 10000 | 1000..60000 | positive | same | same |
| `...outageRecovery.protectedPacedRetryCooldownMs` | int | 10000 | 1000..60000 | optional, positive | same | same |

Cross-layer facts the table exposes: the domain role takes `routingSearch` and the retry curves from the **parent** profile (the domain profile has none); `stabilityWindowFrames` comes from the simulation profile; `enabled` is resolved by the backend from `simulation.precomputation.mode` and the deployment profile, so the PCE never sees the mode. 25 domain leaves = 12 from the parent profile + 11 from the domain profile (7 verbatim + 4 executor sizes) + 1 simulation + 1 digest.

## 7. sim/clock and the other run operations

Builders in TCS; Java `SimClockHandler.execute`. One endpoint, six payload shapes distinguished by flag combinations:

| Purpose | Builder | Payload keys | Notes |
|---|---|---|---|
| start commit | `_start_simulation_after_run_recorded` | `runId`, `wallAnchorMs` = now + `_DEFAULT_START_BARRIER_DELAY_MS` (30000), `simAnchorMs` (int ms), `speedup` > 0 | success = no `error`, not `ignored`, `phase == "playing"` |
| pause | `freeze_simulation_clock` | `runId`, `wallAnchorMs` now, `simAnchorMs` = frozen sim time, `speedup` 0.0 | response not inspected |
| resume | `resume_simulation` | `runId`, `wallAnchorMs` now, `simAnchorMs` = frozen time, `speedup` = original | **response not inspected** (an `ignored` answer goes unnoticed) |
| terminal boundary | `_begin_terminal_drain` | pause shape + `terminalBoundary: true` | failure = `error` or `terminalBoundaryClosed is not True` |
| terminal freeze | terminal-drain completion | pause shape + `terminalFreeze: true` | failure = `error` only. A 200 `freezeRejectedReason: RESULT_BOUNDARY_NOT_CLOSED` is **not** a failure to the backend (no Python reads `freezeRejectedReason`) |
| clock probe | `_probe_pce_wall_clock` | `probeOnly: true`, `controllerNowMs` | no run required; offset computed by the backend from its own send/receive times |

Request fields (Java): `runId` (string; a mismatch with the current run answers `{"ignored": true}`, absent counts as mismatch), `wallAnchorMs` (long, default now), `simAnchorMs` (ISO or numeric string, default = the run's current sim time, unlike start where the default is now), `speedup` (double, default **1.0**, unbounded, 0 = paused), `terminalBoundary`, `terminalFreeze` (bool, default false; both true is accepted and not documented), `probeOnly`, `controllerNowMs`. Python validates none of them; the only bound is the profile's `simulation.speedup` 0 < x <= 1000.

Response fields the backend consumes: `error`/`statusCode` (client), `ignored`, `phase`, `terminalBoundaryClosed`, `terminalClockLagFrames`, `terminalTelemetryEvidenceComplete`, `terminalTelemetryPermanentlyRejected`, `discardedTerminalWork`, `terminalizedSignalingAttempts` (+ByType, +Samples), `unfinishedRecoveryCount` (+TunnelIdentities, ByPhase, OldestWaitMs, Samples), `serverWallNowMs`. Unconsumed but sent: `activeIndex` (logged), `simTime`, `lastResultSliceIndex`, `terminalBoundary` (echo), `terminalTelemetrySettled`, `freezeRejectedReason`, `clockOffsetMs`/`probe`/`controllerNowMs`/`activeRunId`. `phase` values: `playing`, `paused`, `finalizing`, `idle` (`SimulationRun.Phase` lower-cased).

sim/start response: `started` (always true or an error), `runId`, `runtimeConfigDigest` (**echo of the request**, so the gate proves only that this PCE parsed this body), `framesLoaded`, `inventory{frameCount, lowestIndex, highestIndex, versions{index: version}, baseIdentities{index: identity}}`, and `frameSource` (18 keys, present only when the PCE accepted the schedule). The backend reads `inventory.highestIndex`, `inventory.versions`, `inventory.baseIdentities` (`_merge_sim_start_responses`), `frameSource.mode == "schedule"` (`_resolve_frame_source_mode`: all offered PCEs must acknowledge or the start fails; a PCE that silently declines because `FrameSchedule.Unavailable` is indistinguishable from an old image), and `runtimeConfigDigest`.

Other operations: `POST /sim/release` body `{runId}`, answers `{released, runId}` or `{released:false, reason:"run-mismatch", currentRunId}` (backend: best effort, after sealing). `POST /sim/reset` body `{runId, reason}` (`reason` and `runId` are only logged), unconditional, answers `{cleared, evictedFrames}`; the backend calls it before every start (`reason: "sim-start-cleanup"`) and at stop. `GET /sim/status` is the readback of the start gate: the backend reads `phase` (must be playing or paused), `runId`, `activeIndex`; the other 23 keys (lateness summaries, precompute counters, `runtimeConfigDigest`, `precomputeOperational`, ...) are for humans and are not modelled.

## 8. Findings

### (a) Validated on one side only

Python only: `safe-identifier` pattern on the deployment id (and so on the derived run id); `statisticsBackendUrl` required; `inventoryCapacity >= window + 1`; `sliceCount * step == duration`; schedule length equals `topologyFrameCount`; executor/timeout copies are positive (`_positive_nested`, which also accepts `3.7` and `"5"` through `int()`); `endToEndReuse` fields must be exact `int`/`bool` types.

Java only: exact key set at every level of `runRuntimeConfig` (Python merely `dict()`-copies profile sections, so a profile key the PCE does not know fails the run at start, which the tests pin deliberately); every runtime number >= 1 (except the zero-legal ones); `maximumDelaySlices >= initialDelaySlices` (also a profile constraint); `selectionMode == SEEDED_RANDOM`; `retainRatio` in [0, 1] (also the profile); frameSchedule shape and absolute paths; NATS scheme; `resultSliceCount > 0` and `horizonSimTimeMs >= 0` (Python only checks the first when duration > 0).

Profile only (never reaches either runtime check): `precomputation.stabilityWindowFrames <= sliceCount - 1`; `maxPrecomputeChildRequestCountPerTunnel <= maxChildRequestCountPerTunnel`; `deadlineSafetyMs <= passDeadlineMs`; all `fields:` maxima (for example `outageRecovery.maximumAttemptsPerSlice <= 3`). The Java side accepts values the profile editor would reject.

Neither side: `speedup` sign and range at the PCE; `deploymentId`/`domainId` against the PCE's own `salasim.deployment.id`/`salasim.domain.id`; run id and simulator run id format at the PCE.

Ordering defect: the Java comments say validation happens "before any state is touched", but `resultSliceCount`/`horizonSimTimeMs` are checked after `SimRegistry.resetForStart`, run construction and (domain) `ChildImpactPublisher.startRun`, and on the parent after the pool reclaim and MD-LSP database wipe. A typed RPC with mandatory leaves moves this check in front of the handler.

### (b) Opaque or reference-like content that should not be typed

1. `frames[]` (initial topology frames): one full frame per index, MB-sized on a 3,640-satellite deployment, parsed field by field by `SimTopologyFrameParser`. Never typed. In the YANG draft it is absent; a typed `prepare-run` requires `frame-schedule` in practice, which removes the push path from the NETCONF route (decision D1).
2. The content of the schedule and topology files behind `frameSchedule` (compiled scenario artifacts mounted in the pod). Only the two path strings are modelled. They are constants of the image layout and are candidates to become PCE deployment configuration. Note two different "schedule file" formats exist: this one is the compiler's own JSON, while `salasim-simulation:start-run/schedule-file` for emulator nodes is RFC 9195 instance data.
3. Deployment configuration sections (resources, jvm, executors, admission, timeouts, batching): not in the payload at all; `config-digest` is the only link. Not modelled and should stay that way here. Their typing belongs to a deployment-config module if the controller ever owns pod configuration.
4. Statistics transport values: typed as URL strings (patterns for NATS), but they are environment facts (service discovery) and `internal-token` is a secret. They should become references (a NATS service reference, a credential reference) when the controller owns credentials and discovery. NACM `default-deny-all` is set on the token; whether NACM covers an RPC input leaf in the chosen server is unverified.
5. `networkOverrides`: **typed** in the draft (three bps leaves with a 1 Mbit/s floor), contrary to the instruction's presumption. Reason: it is a closed three-key object produced by a profile, not a free blob. Only the legacy `bandwidth: {islGbps, ...}` spelling is dropped; if that must stay, make the container `anydata` (decision D4).
6. Terminal diagnostic samples in the `sim/clock` response (`terminalizedSignalingAttemptSamples`, `unfinishedRecoverySamples`): modelled as `anydata`. How the controller's yangtools handles arbitrary JSON inside `anydata` is unverified.

### (c) Derived values that should not be inputs

| Field | Derived from | Handling in the draft |
|---|---|---|
| `wallAnchorMs`, `speedup` of the prepare step | prepare is "armed frozen"; anchors are committed later | not inputs of `prepare-run` |
| `horizonSimTimeMs` | anchor + sliceDurationSeconds * sliceCount | kept as input (`horizon-sim-time`, the PCE cannot derive it without the profile); a candidate to derive from `result-slice-count` and a slice duration |
| `inventoryCapacity` | `2 * (stabilityWindowFrames + 1)` | optional input; could be dropped and computed |
| `precomputation.stabilityWindowFrames`, `enabled`, `applyEnabled`, the four executor sizes | copies of simulation / deployment profile values, with the mode already resolved | kept (the PCE parser needs them), documented per leaf |
| `role` | the PCE's own role | the choice case of `prepare-run`; `pce-run/role` shows it |
| `serviceType` | URL port | dropped (no consumer) |
| `topologyFrameCount` | schedule | dropped (backend-only) |
| `routingConfig.constraints.*` | constant `REJECT` | dropped |
| `runRuntimeConfig.schemaVersion` | constant 1 | dropped (module revision) |
| `frameSchedule.version`, `runId`, `domainId` | constant / echoes of body fields | dropped |
| `deploymentId`, `domainId` | the PCE's own `salasim.deployment.id` / `salasim.domain.id` | kept as inputs for now; recommended to be validated against the PCE's own values (decision D6) |
| `runtimeConfigDigest` in the response | echo | kept, documented as echo; `pce-run/deployment-config-digest` is the real value to compare |
| `reset` body `reason`, `runId` | logs only | `run-id` kept optional, `reason` dropped |

### (d) Inconsistencies and dead fields

Dead on the PCE: `serviceType`; `topologyFrameCount`; `endToEndReuse.countIdleAgainstCapacity` (not read by the PCE; a live backend admission switch, so deleting it removes a feature); `precomputation.futurePcreqTimeoutMs`, `precomputation.pcreqBundleSize`, `precomputation.transientLinkPenalty` (parsed, required, clamped, **read by nothing** in main or test code outside their constructor; they are Run Profile knobs that do nothing; deleted by D7); `RoutingRunConfig` compat fields and `PrecomputationSelectionMode` ("retained only so old internal diagnostic classes compile"); `TCS._broadcast_clock` phase label `terminal-drain` (no payload carries `terminalDrain`); `reason` of reset.

Silent clamps that contradict profile bounds: `futurePcreqTimeoutMs` to >= 100 (profile min 1), `pcreqBundleSize` to <= 32 (profile no max), network bandwidth to >= 1 Mbit/s (profile min 1). The YANG ranges are the clamps, so these become errors; the profile should declare the same bounds.

Profile narrower than the PCE: `cacheRetentionSlices` min 1 vs PCE accepts 0; `transientLinkPenalty` min 1 vs PCE accepts 0 ("0 disables"). Profile `speedup` > 0 vs YANG `start-run` 0.01 minimum.

Defaults differ between operations: `simAnchorMs` absent means "now" on start but "current sim time" on clock; `speedup` absent means 1.0 on both although every Python caller sends it; `wallAnchorMs` absent means "now", which silently destroys the shared absolute anchor.

Type differences: `simAnchorMs` is an ISO string in start and an integer in clock; `domainId` for the parent is the magic string `"core"`; `runId` is not trimmed while the other ids are; `maxIdleBandwidthBps` is an exact Python `int` but any number in Java.

Responses not honoured: resume and pause never inspect the answer; terminal freeze ignores `freezeRejectedReason`; `ignored` is only examined on the start commit; `frameSource` absence conflates "declined" with "old image".

Vacuous gate: `runtimeConfigDigest` is `runtimeConfig.configDigest` copied from the request (`SSH`, `PSSH`, `SimStatusHandler`), so the start gate cannot detect a PCE running stale configuration; the real check is the separate pod-annotation gate `_verify_pce_configuration_current`.

Naming/state mismatches in the existing YANG: `salasim-simulation:run-state` (created, running, paused, stopped, failed) is not the PCE phase (playing, paused, finalizing, idle); `salasim-pce:pce-config/lookahead-frames` (default 1) and the run's `stabilityWindowFrames` (default 3) name the same concept; `salasim-simulation:sim-time` is run-relative while every PCE anchor is absolute (the controller must add the run's start sim time when it maps `pause-run`).

Two parsers of one value: `parseSimAnchorMs` exists twice (SSH, PSSH) and again in `SCH`; `requiredIdentity` is shared but `runId` bypasses it.

## 9. YANG design (decided)

Five modules, all in `salasim_gmpls_yang/salasim/`; `PYANG=.../pyang ./validate.sh` reports errors=0 for every module and "full set loads together: ok". Commits 3844b95, 586cbf0, 17bd892, aabff64.

| Module (revision) | Lines | Content |
|---|---|---|
| `salasim-run-config` (2026-10-06) | 400 | typedefs (`safe-identifier`, `sim-instant`, `clock-speedup`, `config-digest`, `nats-url-list`, enums), groupings `run-identity`, `result-horizon`, `statistics-transport`, `end-to-end-reuse`, `network-overrides`, `frame-schedule`, `frame-inventory`, `frame-source-state` |
| `salasim-run-runtime-config` (2026-10-06) | 243 | groupings `routing-search`, `backoff-curve`, `domain-recovery`, `parent-recovery`, `precompute-common`, `domain-precompute` (9 leaves after D7), `parent-precompute`, `domain-runtime-config`, `parent-runtime-config` |
| `salasim-pce-run` (2026-10-06) | 333 | state `pce-run` (role, deployment-config-digest, `current-run` readback); RPCs `prepare-run`, `commit-clock`, `probe-clock`, `release-run`, `reset-run` |
| `salasim-pce-fleet` (2026-10-06) | 213 | controller RPCs `prepare-run-on-pces`, `commit-clock-on-pces`, `reset-run-on-pces`, `release-run-on-pces`, modelled like `salasim-fleet` (reuses `sfleet:device-id`; per-PCE list carries only role, domain-id and schedule file; everything shared is given once) |
| `salasim-pce` (2026-10-02, edited in place) | 52 | description points at `salasim-pce-run`; no revision bump (nothing is deployed; `PceManagementModel.SALASIM_REV` stays valid) |

Leaf counts: `prepare-run` input 49 (domain) / 57 (parent), output 26, `commit-clock` 5 in / 22 out. Net change of the second pass: three leaves removed from `domain-precompute`, `frame-schedule` and `frame-source` are now required rather than optional/presence, token and id descriptions rewritten.

Decisions applied in the YANG:

- **D1** `prepare-run/input/frame-schedule` is a non-presence container holding a mandatory `schedule-file`, so it is required; the output `frame-source` is always present (success means the PCE loads its own frames; a PCE that cannot must fail prepare-run instead of silently declining as it does today). The `frames[]` array is not modelled.
- **D3** enum values keep the profile spelling (`FULL_COST`, `AT_LEAST`).
- **D4** `network-overrides` typed: three bps leaves, range `1000000..max`, legacy Gbps spelling gone.
- **D5** `sim-anchor-time` and `wall-anchor-time` mandatory, clamps are ranges (`cache-retention-slices` is the only range that is looser than the profile and the profile is to follow it), `safe-identifier` pattern on all ids.
- **D6** `deployment-id` / `domain-id` descriptions say the PCE refuses a mismatch with its own `salasim.deployment.id` / `salasim.domain.id`. That check is an explicit Java check (the schema cannot know the PCE's own values).
- **D7** `future-pcreq-timeout-ms`, `pcreq-bundle-size`, `transient-link-penalty` removed; `count-idle-against-capacity` was never in the model.
- **D8** `internal-token` description starts "SENSITIVE", forbids logging, storing, echoing in `pce-run/current-run` and evidence tables, declares `nacm:default-deny-all` and states that NACM coverage of RPC input is not relied on (section 10.2 did not test NACM). Intended replacement: the PCE reads the token from a tenant ConfigMap at start. The tenant gateway rejects Secret volumes and env references (`controller_deploy.py` module docstring, commit 32201c0), which is why the NETCONF password and TLS stores already travel as ConfigMaps in tenant mode (`netconf_credential_store=auto` resolves to `configmap` when `SALASIM_RUNTIME_MODE=tenant`, `credential_store()`). The same mechanism fits: the backend writes a ConfigMap `salasim-pce-run-token` (ClusterIP-internal namespace) with the token, the PCE pod mounts it read-only at `/etc/salasim/run/internal-token` and `PceWebhookSender.setInternalToken` reads it per run, and the leaf is deleted. Caveat: a ConfigMap is not encrypted at rest and is readable by anyone with ConfigMap read in the namespace; this is the same exposure as the NETCONF password today.

Ownership of validation after the change (what lives where):

| Check | Lives in |
|---|---|
| types, ranges, patterns, enum values, unknown or duplicate elements, both choice cases at once | the NETCONF server's XML parser, before the handler (verified, 10.2) |
| mandatory leaves, mandatory choice, required container, `must` rules | **not** enforced by the server (verified); explicit checks in the PCE (10.3) |
| cross-profile rules (`stabilityWindowFrames <= sliceCount - 1`, `capacity >= window + 1`, schedule length equals frame count, statistics URL present) | backend preflight at the producer (unchanged Python validators in `topology_clock_service`) |
| profile editing bounds | profile `fields:`/`constraints:` (unchanged), kept equal to the YANG ranges by a test (step B1) |
| the PCE's own identity (`deployment-id`, `domain-id`, role) | explicit PCE check |
| stateful run rules (cannot restart a previous telemetry run, no telemetry retarget) | PCE, at run time |

## 10. Typed-parser-first migration plan

### 10.1 Principle

The typed Java object is the contract between "input" and "run start". Both entry points build the same immutable object and call one starter:

```
HTTP POST /sim/start  --JSON adapter-->  RunStartConfig  --\
                                                            +--> RunStarter.start(cfg)   (today's body of execute(), after parsing)
NETCONF prepare-run   --DOM mapper---->  RunStartConfig  --/
```

The HTTP route is kept during the transition (recommended and decided in the coordinator's message) so that the golden payloads can prove both inputs give `equals()` objects, and so that every step is revertible by dropping the new entry point. HTTP is deleted last.

`RunStartConfig` is a plain immutable class tree (no JSON, no ODL types): identity, anchor, result horizon, capacity, statistics transport, end-to-end reuse, frame schedule (paths, margin, network overrides), and one of `DomainRuntime` / `ParentRuntime`. The existing `RunRuntimeConfig`, `RoutingRunConfig` and `PrecomputeConfig` keep their public fields (the 12-plus consumer classes listed in section 6 are untouched) but are constructed from `RunStartConfig` instead of parsing JSON; their `requireExactKeys` and `requiredXxx` helpers disappear in the step that makes the JSON adapter the only JSON reader.

### 10.2 What the NETCONF server does with an invalid input (verified)

Experiment (`docs/pce-run-start-golden/schema-validation-probe/`, results in `results.txt`): the three YANG modules were loaded into the same ODL stack that `salasim_gmpls_netconf` embeds (netconf-server 11.0.0, mdsal 16.0.3, yangtools 15.0.2), a no-op `prepare-run` was registered, and 21 documents were sent through `NetconfOperationRouter.onNetconfMessage`, i.e. the real `RuntimeRpc` XML parsing. Caveat: in process only (the sandbox forbids socket binds), so the translation of the thrown `DocumentedException` into an `<rpc-error>` on the wire is the library's standard path but was not captured; the error tag and type below are read from the exception object.

| Invalid input | Server result | What the handler sees | Client error (tag / type) |
|---|---|---|---|
| unknown leaf (`bogus-knob`, `serviceType`, a deleted knob) | rejected in the parser, names the node and its parent | handler not called | `malformed-message` / protocol, message "Schema for node with name X ... does not exist in parent ..." |
| duplicate leaf | rejected | not called | `malformed-message` / protocol, names the element |
| value out of range (`result-slice-count 0`, `compute-parallelism 200`, `initial-delay-slices 0`) | rejected by the integer codec (`YangInvalidValueException: Value '0' is not in required ranges [[1..4294967295]]`) | not called | `operation-failed` / application, message **"Unexpected error"**, no field name; detail only in the server log |
| pattern violation (`md5:zz` digest, id `bad id!`) | rejected | not called | same generic `operation-failed` |
| bad enum value, not a number | rejected | not called | same generic `operation-failed` |
| both cases of the runtime-config choice | rejected | not called | same generic `operation-failed` |
| **missing mandatory leaf** (`run-id`, `compute-timeout-ms` deep in the tree) | **accepted** | handler called with the leaf absent | none |
| **missing mandatory choice** (no runtime config) | **accepted** | handler called | none |
| **missing required container / its mandatory leaf** (`frame-schedule` absent or empty) | **accepted** | handler called | none |
| **`must` violated** (precompute budget above reactive; maximum delay below initial) | **accepted** | handler called | none |

Consequences, stated precisely:

1. "YANG schema validation rejects it before the handler" is true for unknown and duplicate nodes (loudly, with the name) and for every type constraint (range, pattern, length, enum, number syntax), but not for presence: **mandatory, required containers and `must` are not checked by this server for RPC input.** A missing key is exactly what `RunRuntimeConfig.requireExactKeys` rejected loudly today, so this gap must be closed in the PCE or a missing leaf becomes a silent default.
2. The type-constraint rejections reach the client as a generic "Unexpected error" with no field name. The hand parser's messages named the field (`runRuntimeConfig.routingSearch contains missing or unsupported fields; missing=[...]`). That diagnostic loss is real. Mitigations (all in the plan): the backend keeps its own field-named preflight so most bad inputs never reach the PCE; the NETCONF layer logs the cause (it does, at ERROR); and the PCE registers a one-line correlation in its log. A fix in the library (wrapping `RuntimeRpc` to report the cause) is possible but is a change to ODL integration code and is not planned.
3. Typed values arrive as yangtools types (printed as numbers; uint64 and decimal64 are not JSON numbers on the RESTCONF side but are typed numbers in the DOM tree), so the mapper must convert through `Number`/`BigDecimal`, never through strings.

### 10.3 What the hand parser failed loudly on that the schema does not cover (explicit checks to keep)

Each of these becomes a named check in `RunStartConfigYang` (the DOM mapper) or in `RunStarter`, with a unit test that feeds the bad input and asserts the error text and that no run state changed.

| Today's loud failure | Covered by schema? | Kept as |
|---|---|---|
| a missing key at any level of `runRuntimeConfig` (`requireExactKeys`) | no (verified) | generic schema-driven mandatory validator, see below |
| `runtimeConfig.role` must equal the handler's role | no | the case present in the choice must equal the PCE role (`pce-run/role`), else error |
| `resultSliceCount > 0`, `horizonSimTimeMs >= 0`, `frameSchedule.scheduleFile` present | range covers the numbers; presence not | mandatory validator; plus horizon ordering check performed before any state change (also fixes the late-validation defect of section 8(a)) |
| `maximumDelaySlices >= initialDelaySlices` (x4 curves) | `must`, not enforced | explicit check |
| `maxPrecomputeChildRequestCountPerTunnel <= maxChildRequestCountPerTunnel` (PCE took the min silently) | `must`, not enforced | explicit check (now an error) |
| `deadlineSafetyMs <= passDeadlineMs` (profile rule only today) | `must`, not enforced | explicit check |
| `deploymentId`/`domainId` equal the PCE's own (new, D6) | no | explicit check against `salasim.deployment.id` / `salasim.domain.id` |
| `frameSchedule` files unusable (`FrameSchedule.Unavailable`) | no | prepare-run fails with operation-failed (D1), instead of the silent decline |
| `simulatorRunId` already ran / telemetry retarget / NATS retarget | no (stateful) | unchanged `validateRunStart` / `validateRunTransport` calls inside `RunStarter`, before state changes |
| stale `selectionMode`, `bandwidthMatch`, `objectiveMatch` | yes (enum) | `EndToEndLspReuseRegistry.configureForRun` keeps its own check as defence in depth |
| `endToEndReuse.maxEntries` old key rejected | yes (unknown element) | nothing |
| `outageRecovery` optional, `protectedPacedRetryCooldownMs` optional | presence container, optional leaf | mapper maps absent to the documented fallback (1, 0, 0) |

The generic validator is the cheap way to close the largest gap: a helper in `salasim_gmpls_netconf` (`RpcInputValidator`) walks the RPC's `input` schema (`EffectiveModelContext`) and the received `ContainerNode`, reports every mandatory leaf, choice or container with mandatory descendants that is missing (naming the path, which is better than today's generic errors), and is applied to every RPC registered through `NetconfManagementServer.registerRpc`. It evaluates no XPath: the `must` rules remain explicit and a test asserts that the number of `must` statements in the PCE modules equals the number of registered explicit rules, so a new `must` without a Java check fails the build.

How the PCE parses the DOM input: the handler receives a `ContainerNode`; `RunStartConfigYang.from(ContainerNode, PceRole)` reads leaves by QName (the `RpcSupport.requiredString` style already in the library), converts numbers through `Number`, decimal64 through `BigDecimal`, `date-and-time` through `Instant.parse` (the YANG type allows offsets that `Instant.parse` rejects; the mapper uses `OffsetDateTime.parse(...).toInstant()`), and enumerations by name. Binding-generated classes (mdsal binding from the YANG) were considered and not chosen for this step: they add a code-generation plugin to the PCE build and a second source of typed classes for a 50-leaf input; a hand mapper plus a schema-coverage test (every leaf of the input schema is read by the mapper, checked by walking the schema) gives the same drift protection without it. Revisit when more RPCs are modelled.

### 10.4 Ordered steps

Owners: **yang** = `salasim_gmpls_yang`, **pce** = `salasim_gmpls_pce`, **netconf** = `salasim_gmpls_netconf`, **backend** = `salasim_gmpls_backend`, **frontend** = `salasim_gmpls_frontend`, **controller** = `salasim_gmpls_controller`. Estimates are agent-days.

| # | Owner | What changes, exactly | Proof | Revert | Days |
|---|---|---|---|---|---|
| Y1 | yang | The five modules (done), golden instance JSON `prepare-run-input.{domain,parent}.json` and the checker (done, `docs/pce-run-start-golden/`) | `validate.sh`; `check_roundtrip.py`: domain 64 legacy leaves to 47 YANG leaves (17 not carried: 13 structural, 3 deleted knobs, empty token), parent 69 to 55 (14), schema problems none, round-trip differences none | `git revert` | done |
| D1 | backend + pce + frontend (one coordinated change, **atomic deploy**: the PCE rejects missing and unknown keys, so no mixed old/new pair works) | Delete the 3 dead knobs and `countIdleAgainstCapacity`, apply the two bound changes. Backend: `configuration_defaults/domain-pce.v1.yaml` (3 keys under `precomputation`, bump `defaultsRevision` 17 to 18, add `fields: precomputation.cacheRetentionSlices: {unit: count, minimum: 0}`); `configuration_defaults/simulation.v1.yaml` (key at line 43, its `fields:` entry at line 136, bump 11 to 12); `routers/services.py` `_resolve_pool_charged_against_capacity` (returns `reuse.enabled`, i.e. always charged); tests `test_configuration_defaults.py:52`, `test_configuration_profiles.py:318`, `test_service_admission_cap.py:435` (the `charged` parametrisation is a feature test of the toggle: delete the `False` case). `topology_clock_service._build_pce_run_runtime_config` and `_build_start_targets` stop leaking by replacing the `**dict(precompute)` / `dict(end_to_end_reuse)` pass-through with an explicit key projection. PCE: `PrecomputeConfig` (3 fields, constructor parameters, clamps, parent-constructor assignments), `RunRuntimeConfig` (`DOMAIN_PRECOMPUTE_KEYS`, the 3 `required...` calls), tests `RunRuntimeConfigTest` lines 67 to 71 (retarget the missing-key test at `computeQueueCapacity`) and 163 to 164, `RunRuntimeConfigFixtures` lines 80 to 82. Frontend: `src/i18n/config-labels.js` entries at 331, 333, 338, 438 (the label-coverage test enforces the pair). Docs: `docs/configuration-ownership.md:49`, `docs/configuration-inventory-2026-09.md:94-95`. Left alone: backend `docs/evidence/*.json` (historical records), frontend `.next-*` build output. No drift/property test references these fields (searched `tests/`: none) | backend suite incl. `test_configuration_label_coverage`; PCE `RunRuntimeConfigTest`; `_build_start_targets` output has no deleted key (assertion added); a full run on a fixture deployment | `git revert` of the three commits together | 1.5 to 2 |
| B1 | backend | New test: every `fields:` bound of the profile leaves that appear in `salasim-run-*.yang` lies inside the YANG range (pyang-library walk, the `check_roundtrip.py` walker moved to `tests/`); decide the `speedup` bound (profile `> 0` vs YANG `0.01`) | the test | delete the test | 1 |
| P1 | pce | **Pure refactor, no contract change.** Introduce `RunStartConfig` and `RunStartConfigJson` (the existing parsers moved: `RunRuntimeConfig.from`, `RoutingRunConfig.from`, `EndToEndReuseRunRequest.parse`, `FrameScheduleDescriptor.parse`, anchor and identity parsing, with identical error messages) and `RunStarter.start(RunStartConfig)` (the body of `SimStartHandler.execute` / `ParentSimStartHandler.execute` after parsing). Handlers become `RunStarter.start(RunStartConfigJson.parse(body, role))`. `RunRuntimeConfig` and friends keep their fields | characterization tests written **first** against the unmodified code: the real payloads (`sim-start-payloads.json`, both roles) produce the expected values field by field, and a table of 25 bad bodies (missing key, extra key, wrong role, each range, bad anchor, bad frameSchedule) asserts today's exact exception message; all existing PCE tests unchanged and green | `git revert` (HTTP behaviour was never altered) | 2 to 3 |
| P2 | pce | Fix ordering inside `RunStarter`: all validation (horizon, slice count, identity, transport) before the first state change (`resetForStart`, `beginRun`, pool reclaim) | test: a bad horizon leaves `SimRegistry`, `TunnelUpdateEmitter` and the MD-LSP database untouched (parent and domain) | `git revert` | 0.5 |
| N1 | netconf | `RpcInputValidator` (mandatory leaf / choice / required container, schema-driven, names the missing path) wired into `registerRpc`; unit tests from the 21-case probe matrix | in-process test: the four "accepted today" rows of 10.2 now fail with `missing-element`/`operation-failed` naming the path; valid input still reaches the handler | `git revert`; the validator is only active for RPCs registered through the library | 1.5 |
| P3 | pce | Copy the modules into `src/main/resources/yang` with the sync script (hash-checked by `YangResources.verify`); `PceManagementModel` QNames; `RunStartConfigYang.from(ContainerNode, role)` (explicit checks of 10.3 including the `must` rules and the D6 identity check); RPC handlers `prepare-run`, `commit-clock`, `probe-clock`, `release-run`, `reset-run` registered in `PceNetconfManagement` behind `salasim.pce.netconf.runRpc.enabled` (default **false**), each calling the same `RunStarter` / existing clock, release and reset code through `PceControlPort`; `pce-run` state incl. `deployment-config-digest` from the pod annotation (downward API env); `rpc-error` mapping | **Equivalence**: a Java test parses the golden `prepare-run-input.*.json` with yangtools' `JsonParserStream` against the real schema into a `ContainerNode`, maps it with `RunStartConfigYang` and asserts `equals()` with the object `RunStartConfigJson` builds from the corresponding legacy `sim-start-payloads.json` entry (both roles). **Negative matrix**: the 21 probe cases plus the explicit checks, each asserting the error and that no run state changed. **Coverage**: schema walk asserts every input leaf is read by the mapper and every `must` has a rule. In-process NETCONF test via `InProcessNetconf` (the real RPC path) | flag off (default); or `git revert` | 5 to 6 |
| C1 | yang + controller | `salasim-pce-fleet` implementation: `prepare-run-on-pces` expands the shared input into one `prepare-run` per mounted PCE, parallel with bounded retries on transient errors only, collects `prepared` outputs; on any failure sends `reset-run` to every PCE that accepted (best effort, `rolled-back`); `commit-clock-on-pces` (start idempotent for the same anchor; partial failure freezes the committed ones again with `speedup 0`); `reset-run-on-pces`, `release-run-on-pces` best effort; per-device results always returned so the backend keeps its retry-the-pending-targets logic of the terminal steps | fixture test with one PCE rejecting: all others reset, `accepted=false`, no half-armed PCE; commit failure test; timing of prepare on a 100-PCE fixture; fan-out does not mutate on probe | `git revert`; the backend does not call it yet | 3 to 4 |
| B2 | backend | **Producer of the typed input.** `pce_run_input.py`: `build_prepare_run_on_pces(...)` from the same sources `_build_start_targets` uses (profiles, `push_config`, statistics URLs); the Python validators in `topology_clock_service` (`_build_pce_run_runtime_config`, `_required_curve`, `_positive_nested`, end-to-end checks, capacity, statistics URL) stay as producer-side preflight with field-named messages; the pyang-derived range tables are used in tests only (no runtime XPath/YANG engine in Python). Clock commits, pause, resume, terminal boundary and freeze, probe, release, reset get typed builders and response mappers feeding the unchanged consumers (`_merge_sim_start_responses`, `_resolve_frame_source_mode`, terminal drain). Toggle `SALASIM_RUN_START_VIA=http|controller` (default `http`, per run, recorded in the clock meta like `faultDeliveryVia`) | for each profile matrix point (INHERIT/ON/OFF, protection on/off, parent with and without `outageRecovery`, FIXED start time) the typed input converted back by the `check_roundtrip` adapter equals the legacy JSON of `_build_start_targets` minus the documented dropped fields; existing backend suite unchanged in `http` mode | flip the toggle; `git revert` | 3 to 4 (+1.5 clock/terminal) |
| V1 | all, cluster | 169 verification: two same-seed runs, `http` vs `controller`: start duration, `windowLagEvents == 0`, result sealing, fault run, forced failure of one PCE during prepare (all reset), controller restart between prepare and commit | evidence rows identical in `record_topology_delivery` / `record_clock_delivery` | toggle back to `http` | 2 |
| X1 | pce + backend + controller | Delete: HTTP `/sim/start`, `/sim/clock` (release/reset keep a route only if still used), `RunStartConfigJson`, the JSON builders in the backend, the frames push path (`/sim/frames`), `requireExactKeys`; flag `runRpc.enabled` becomes the only path (C4) | grep for the removed names; golden test reduced to the YANG side; drift tests | `git revert` | 2 |

### 10.5 Deployment and compatibility notes

- D1 is the only step that cannot be rolled forward gradually: the PCE's exact-key parser and the backend's pass-through are two halves of one contract. Deploy PCE images, backend and frontend together (the PCE StatefulSets must be rolled and, per the emulator reconnect lesson, the emulators restarted afterwards). Nothing needs a profile migration under the "no premature migration" rule, but the `defaultsRevision` bump is what tells a user whose saved profile still overrides a deleted key.
- Steps P1, P2, N1, P3 change no deployed behaviour: the NETCONF RPCs exist but are off, the HTTP route is the only live path.
- C1 and B2 are independent of the PCE change except for the shared YANG; the toggle defaults to `http` until V1 passes.

### 10.6 Revised estimate

| Block | Steps | Agent-days |
|---|---|---|
| Delete dead fields, bounds | D1, B1 | 2.5 to 3 |
| PCE typed object and refactor, ordering fix | P1, P2 | 2.5 to 3.5 |
| Generic RPC input validator (netconf) | N1 | 1.5 |
| PCE NETCONF RPCs, mapper, explicit checks, tests | P3 | 5 to 6 |
| Controller fleet RPCs | C1 | 3 to 4 |
| Backend producer, toggle, clock and terminal | B2 | 4 to 5.5 |
| Cluster verification | V1 | 2 |
| Delete legacy | X1 | 2 |
| Total | | about 23 to 28 (previous adapter-first estimate 17 to 23) |

The increase comes from the typed object refactor being its own step with characterization tests written against the unmodified code (P1 is the safety net for everything after it), the generic validator the experiment showed is necessary (N1), and the explicit-check suite that replaces what the exact-key parser gave for free. The least certain items remain P3 (the first RPC with 49 to 57 typed inputs, a `choice` and 64-bit values in this stack; the experiment shows the parse works) and C1 (no existing controller fan-out for a long-running, non-idempotent RPC).

### 10.7 Risks of typed-parser-first the user should know

1. **The server does not enforce mandatory leaves, required containers or `must`** (verified). The exact-key strictness the PCE has today does not survive by itself; it survives only if N1 and the explicit checks are built and tested. Skipping them turns a missing leaf into a silent default (0, false, null), which is the failure class the current parser was written to prevent.
2. **Diagnostics get worse for type violations**: the client sees "Unexpected error" without the field name. Backend preflight and server logs compensate; the PCE operator loses the one-line field message unless the library is patched.
3. **Two input paths exist until X1**, and a divergence between the JSON adapter and the DOM mapper would be invisible to either's own tests. The shared golden `equals()` test is the only guard, so it must stay in CI for both roles and every profile-matrix payload.
4. **P1 is a refactor of the start path of every run** with no functional gain; its safety rests entirely on the characterization tests written before the move. Reviewing it as "no behaviour change" requires the 25-case failure table, not just the happy path.
5. **D1 is a flag-day** across backend, PCE and frontend (exact-key parsing on one side, pass-through on the other).
6. **`countIdleAgainstCapacity` is not dead code.** It is a live backend admission-accounting switch (`routers/services.py`, "an operator can turn it off to measure what retention would cost a network that ignored the pool"). Deleting it removes the experiment mode and fixes the behaviour to "charged"; it is listed in D1 but is a feature removal, not a cleanup.
7. **`prepare-run` is long and not transactional.** It must not run on the NETCONF session thread without a bound (the parent may signal child deletes); the handler returns a future and the controller fan-out timeout must be measured, not guessed (not measured).
8. **Handwritten mapper over a schema** can drift from the YANG; the coverage test (every input leaf is read) is what prevents it. Binding-generated classes would remove the drift class at the price of a code-generation plugin in the PCE build.
9. **Token in an RPC input**: until the ConfigMap route exists, the token transits the controller and is visible to anything logging RPC inputs; N1's logging and the controller must redact it.
10. Wire-level behaviour (`<rpc-error>` encoding, the SSH/TCP session) was not exercised; only the operation router was.

## 11. What could not be determined

- The wire encoding of the errors in 10.2 (sandbox forbids socket binds); NACM behaviour on RPC input leaves; lighty (the controller's runtime) as opposed to the embedded ODL stack the PCE and netconf library use.
- Real prepare-run and commit-clock latency on the 169 cluster and under 10k-service load.
- Whether anything besides the backend calls sim/start or sim/clock (searched `scripts/`, `src/`, tests, frontend, controller: only `topology_clock_service.py`).
- `must` expressions were never evaluated by an engine (no libyang/yanglint installed); the pyang-library checker covers types and structure.
- The exact consumer set of the `recovery` curves beyond the classes named in section 6 (name search, not call graph), and whether `cacheRetentionSlices = 0` is behaviourally safe in `SimulationRun`.
- Whether the profile's `speedup` lower bound (> 0) may be tightened to 0.01; the user has not decided it (it is the third profile-bound change, beyond the two approved).
- How the frontend or other readers use the `/sim/status` fields not modelled in `pce-run`.

## 12. Final decision list

| ID | Decision | Status |
|---|---|---|
| D1 | `frame-schedule` required in `prepare-run`; no inline `frames`; HTTP push path stays until deleted | decided (user) |
| D2 | typed parser directly, HTTP route kept during transition, both build the same `RunStartConfig` | decided (user, against the earlier recommendation) |
| D3 | enums keep the profile spelling | decided (coordinator default) |
| D4 | `networkOverrides` typed, legacy Gbps spelling dropped | decided (coordinator default) |
| D5 | all tightenings accepted: anchors mandatory, clamps become errors, id pattern at the PCE | decided (user) |
| D6 | the PCE checks `deployment-id` / `domain-id` against its own `salasim.deployment.id` / `salasim.domain.id` | decided (coordinator default) |
| D7 | delete `futurePcreqTimeoutMs`, `pcreqBundleSize`, `transientLinkPenalty`, `countIdleAgainstCapacity`; `cacheRetentionSlices` allows 0 | decided (user; 2026-10-06 reconfirmed knowing that the last one is a feature removal: the operator can no longer switch off charging of pooled connections) |
| D8 | token and statistics URLs stay typed inputs for now; token marked sensitive; NACM gap noted; token to move to a tenant ConfigMap read by the PCE | decided (coordinator default) |
| D9 | profile `speedup` lower bound tightened to 0.01 to match the YANG | decided (user, recommended option) |
| D10 | the PCE's typed classes are **binding-generated** from the YANG (yangtools codegen in the PCE build, as the controller already does), not a hand mapper | decided (user, against the planned hand mapper); consequences for P3 below |

### Consequence of D10 for step P3 (2026-10-06)

P3 now builds the typed objects from the generated bindings instead of a hand mapper: the PCE build gains the yang-maven-plugin/binding-codegen step (copy the controller's pom wiring, including the Pekko/guava pins only if the PCE needs them; the PCE is `--release 21`, check the generated code compiles and the PCE jar size and build time impact), `RunStartConfigYang.from(...)` becomes a thin adapter from the binding classes to `RunStartConfig` (still needed because the JSON path and the NETCONF path must produce an equal `RunStartConfig`), and the explicit checks of 10.3 stay. The shared golden test is unchanged in shape. The extra risk is build complexity in the PCE repo (Docker builder image, offline Maven repo copies in the sandbox).
