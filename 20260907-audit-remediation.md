# 2026-09-07 audit remediation

Run: `simrun-b5368956d832`. Evidence on 169:
`/home/wings/salasim_gmpls/audit-evidence/simrun-b5368956d832`.

The run was interrupted by the Backend update while finalizing. It is not a
completed acceptance run. Preserve the captured history; do not manufacture the
missing final result slice. Correct routing frame is 19, not predictive frame 20.

## Confirmed fixes

- Backend result horizon used uploaded inventory length. Fixed to use
  `resultSliceCount`. Correct-frame replay reduced selected missing edges from
  27 to zero. Other feasibility counts must not be inherited from frame 20.
- Terminal retry maintenance stayed at result slice 19, missing late failures due
  at sentinel retry 20. Fixed retry admission without advancing topology.
- Scheduler completed-generation deduplication also blocked that terminal retry.
  Added a distinct final admission cycle on the same authoritative TED.
- Backend restart discarded the PCE run identity, so later stop returned success
  without delivering reset. Retain identity without restarting timers; clear it
  after reset delivery. Existing interrupted run was explicitly reset by its
  verified identity; all 22 PCE LSP counts were subsequently zero.
- Search termination audit read the wrong field. Original facts report
  `B_REACHED=439`, `DEADLINE=30`, not 465 unknown decisions.
- Cache-hit audit counted both OPEN and terminal events. Count unique successful
  terminal operation IDs; missing identities produce unavailable counts.
- Service projection omitted shared SRLG IDs already present in authoritative
  path protection diagnostics. Project them without inferring risk IDs.
- Protection test used unsynchronized increments in concurrent child requests.
  Replace with atomic counters; retain all protection assertions.

## Validation and deployment

- PCE full suite passed after final admission and atomic counter fixes.
- Backend runtime regression: 186 passed; signaling audit: 6 passed.
- SRLG projection regression passed.
- PCE GitHub commit `474705f`; Backend latest `29dd564`.
- 169 screen `fixes-474705f` builds and deploys only this deployment's 22 PCEs.
  PCE digest: `sha256:de9d005b9ec88e4e30e8a6dae86c4cfea12b6391ff4fccf9b7e6d1d30fc03539`.
- Backend `0e93997` was included in that deployment job; latest `29dd564` still
  needs pulling and Backend-only deployment before the next test.
- No Emulator rollout or cluster redeployment.

## Remaining investigations / acceptance

1. Correct-frame replay still finds 30 DOWN services with a feasible primary and
   six shared-SRLG pairs. Recheck explicit degraded risk IDs after projection fix.
2. Reconcile Parent/Child/Backend identities and child segment ownership against
   exact PCC + PLSP + path identity. Do not delete records by symbolic name alone.
3. Recompute bandwidth conservation using correct-frame ownership. Prior 22
   reported discrepancies are not yet proven leaks.
4. Runtime recovery backlog peaked at 946; queue p95 93.4s. Terminal retry fixes
   do not prove runtime throughput improved. Diagnose before changing queue sizes.
5. Precompute coverage was 1343/9270 pass records. Cache hit event count 170 was
   doubled; Parent confirmed 85 precomputed replacements. Audit H3/H2/H1 and
   unique operations; these pass totals are not a per-service probability.
6. Examine 72 post-break capacity failures and recovery behavior. A feasible
   later path does not establish historical capacity availability.
7. Recheck QoS within the same requested window and selection-time evidence.
   Current shortest path comparison is not proof of historical suboptimality.
8. After deployment, run 20 x 100s, 1x, 500 cross-domain star-ground bidirectional
   MPLS 1+1 SRLG, lazy, 400Gbps. Use screen and a single check after 40 minutes.
9. Audit real browser pages when browser tooling is available; otherwise mark
   UI validation blocked. Normal stop must verify LSP, pending work and bandwidth
   zero at all 22 PCEs. Emulator post-stop resource retention remains excluded.
