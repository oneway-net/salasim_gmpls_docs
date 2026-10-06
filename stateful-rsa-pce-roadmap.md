# Roadmap: Stateful Multi-Switching PCE (WSON + MPLS, parallel)


Status: planning. Captures the agreed target and the migration path from today's
code. Supersedes the earlier draft.

## 1. Agreed scope decisions

1. **Two switching types in PARALLEL** — WSON (fixed-grid wavelength) and
   MPLS (packet/bandwidth). Each LSP is of ONE type; they
   coexist as peers (GMPLS multi-switching-capability, single layer).
2. **NOT MPLS-over-WSON** — no multi-layer / VNT / inter-layer adaptation for now.
3. **RWA-style assignment** for the optical type (routing + wavelength).
   MPLS = routing + bandwidth.
4. **PCE-initiated LSPs** (RFC 8281), not only rerouting delegated ones.
5. **H-PCE multi-domain** — keep parent/child.
6. **Assign once, stable across frames** — minimise reconfiguration over time;
   reroute only segments that actually break; lookahead pre-plans minimal MBB.
   Future occupancy is DERIVED by projecting the ledger onto known future-frame
   topology, NOT maintained as live reservations (see §6).

## 2. Target architecture

Active **stateful** PCE doing **centralized resource assignment** per switching
type, pushing explicit-resource EROs to a data plane that only executes + confirms
admission.

Standards: RFC 8231 (stateful), RFC 8281 (initiated), RFC 6805/8685 (H-PCE),
RFC 8779/8780 (GMPLS/WSON RWA PCEP), RFC 6205 (WSON
wavelength label), RFC 3473 §5.1 (explicit label control). MPLS = RFC 5440 + 3209.

### Per-type resource model
| Type | Resource | Constraint | Label / assignment |
| --- | --- | --- | --- |
| WSON | per-fibre wavelength (fixed grid) | continuity, exclusive | DWDM label (n), RWA |
| MPLS | per-link unreserved bandwidth | none (label-swap), soft/shared | local label, BW-CSPF |

The **ledger is the stateful PCE LSP-DB** for both. The data plane stops
choosing resources (optical) / honours bandwidth admission (MPLS).

## 3. Readiness today (from code audit)

| Dimension | WSON | MPLS |
| --- | --- | --- |
| PCE compute algorithm | EXISTS, disabled (OF 1000/1001 commented) | PARTIAL — `MPLS_MinTH` / `DefaultSinglePathComputing` ignore bandwidth |
| Algorithm manager | EXISTS (`SP_FF_RWA`, `AURE_*`, `KSP_*`) | EXISTS (`MPLS_MinTH_AlgorithmManager`) |
| Emulator resource mgr | EXISTS (`WSONResourceManager`) | STUB (`MPLSResourceManager` returns false/null) |
| TED attributes | wavelength bitmap (`TE_Information`) | `UnreservedBandwidth` field present but never checked |
| Ledger representation | PARTIAL (slot=wavelength, numSlots=1; see §5) | MISSING (no bandwidth field, no type tag) |
| Reservation via overlay | `ResourceOverlay.applyToTed`/`setWavelengthReserved` — live once ledger carries real slots | none |

Bottom line: **WSON feature-complete but switched off +
ledger wiring; MPLS needs real building (BW-CSPF + real resource mgr + ledger BW
field).**

## 4. Foundation already built (Phase 0)

| Component | Work | Fits which type |
| --- | --- | --- |
| LSP-DB w/ truthful state | A0 (emulator reports actual wavelength), A (ledger parses real label) | WSON once slot-extraction handles fixed-grid label |
| LSP-DB no-leak | D (timeout revert to last confirmed) | all |
| occupancy -> compute | B: `FrameView` branches `getWSONinfo()->applyToTed` (WSON) | **WSON** |
| protocol fix | LabelRequest L3PID decode | optical |
| time-varying + MBB | frames, precompute, pending tracking | all |
| stateful PCEP | delegation, PCUpd, PCRpt, parent BRPC | all |

Note: the `FrameView` WSON branch from B is exactly right for WSON. The L3PID continuity hack is abandoned (decode fix kept as a plain
correctness fix).

## 5. Cross-cutting generalisation needed

- **Ledger / LedgerEvent must become switching-type-aware**:
  - add a `switchingType` tag (WSON | MPLS);
  - add a `bandwidth` field (MPLS);
  - keep slotStart/numSlots for optical (WSON = single wavelength: numSlots=1).
  - `LedgerWriter.eroToSlot` must also read the WSON fixed-grid `DWDMWavelengthLabel`
    (only `n` set, `m` may be 0) — today it requires `m>0`, so WSON wavelengths
    would be dropped. Fix when WSON is enabled.
- **Effective-TED projection per resource dimension**: WSON via the existing
  overlay/lambda paths; MPLS via unreserved-bandwidth subtraction.
- **Algorithm selection**: already by OF code in `RequestProcessorThread`
  (`singleAlgorithmList`). Register WSON + MPLS OF
  codes; ensure each request carries the OF for its switching type.

## 6. Resource allocation timing — DECISION (2026-06-10)

**H-PCE scheme = reactive allocation + RESOURCE-FREE precompute.** Resources are
allocated only at apply/reroute time, against CURRENT real state. Precompute does
NOT model future resources — that was judged too complex and is self-invalidating in
a time-varying network. Precompute is at most topology-level prediction (affected
set / route hint). **No PREDICTIVE_HELD, no future occupancy projection at the parent.**

- **Data plane = pure executor (Option 1, 2026-06-10).** The emulator does NOT use
  topology for its own decisions (no local admission / no slot re-pick); it executes
  the PCE-assigned explicit-label ERO and reports PCRpt. PCE is the sole authority for
  topology (frames) + resources (ledger). Neighbor flooding kept as a sensor only.
  Emulator topology switching disabled. Already largely true for WSON via S1/S2/A0.
- **Cross-domain uses inter-domain BANDWIDTH, not wavelength continuity** (resolves
  §8.1). Wavelength continuity is intra-domain only (S1–S5).
- **Trade-off accepted:** lose proactive hitless make-before-break (react at the
  break, not before). Mitigation: precompute the route topology ahead; SA reactively.
- **Intra-domain S3/S4** (future-frame seed + penalise, already built) are KEPT as an
  optional proactive intra-domain MBB tier; NOT extended to inter-domain.
- **Inter-domain bandwidth model (bounded, parent-side only, NO emulator change** —
  the parent is the sole inter-domain bandwidth allocator; the data plane does no
  inter-domain bandwidth admission, so the parent's accounting is authoritative):
  1. Residual = link capacity − Σ(active MD-LSP bw on the link), DERIVED by projecting
     `multiDomainLSPDB` over each `MD_LSP.fullERO`. No reserve table, no hold.
  2. Make `MDHPCEMinNumberDomainsKSPAlgorithm` bandwidth-constrained: prune inter-domain
     edges with residual < requested before `KShortestPaths`.
  - Verify first: inter-domain edge bandwidth attributes populated from topology (can
    be null)? Non-trivial: mapping `MD_LSP.fullERO` → inter-domain edges.

## 7. Phased plan

### Phase 1 — Ledger = single occupancy authority (all types)
- Generalise ledger (§5: type tag + bandwidth). Effective TED always ledger-derived.
- Parent-level LSP-DB + inter-domain occupancy (H-PCE).

### Phase 2 — Enable WSON
- Re-enable WSON OF codes in config; fix `eroToSlot` for fixed-grid label.
- Verify WSON LSPs flow through ledger -> `applyToTed` -> WSON algorithms.
- Lowest-effort win: WSON is already built, just disabled + needs ledger wiring.

### Phase 3 — Build MPLS for real
- Bandwidth-constrained CSPF: prune/weight edges by unreserved bandwidth before
  Dijkstra (in `DefaultSinglePathComputing` or `MPLS_MinTH`).
- Implement real `MPLSResourceManager` (bandwidth admission: reserve/check/free).
- Add ledger bandwidth field; effective-TED subtracts booked bandwidth.

### Phase 4 — Centralized assignment + explicit control (optical)
- Route reroute/precompute through the type's RWA algorithm (not topology-only
  Default) against the ledger-derived effective TED; emit explicit labels.
- Emulator transit honours explicit optical labels strictly; no local re-pick ->
  PathErr (RFC 3473). (MPLS keeps per-hop bandwidth admission.)

### Phase 5 — Consistent allocation + frame stability (decision 6)
- Atomic batch allocation (reserve each computed path before next within a batch).
- Stability objective: route over persistent links; lookahead pre-plans minimal
  MBB; cost weights churn over per-frame optimality; future-state by projection.

### Phase 6 — PCE-initiated LSPs (decision 4)
- Add/verify PCInitiate (RFC 8281); multi-domain initiation via parent.

## 8. Open design questions

1. **Optical cross-domain continuity at gateways**: RESOLVED (§6) — cross-domain
   uses inter-domain bandwidth, not wavelength continuity.
2. **Per-request switching-type signalling**: confirm each LSP request reliably
   carries the OF / switching capability so the right algorithm + resource mgr is
   chosen, and the emulator node selects the matching resource manager.
3. Stability metric & lookahead horizon (decision 6).
4. PCInitiate scope (all initiated vs hybrid with delegation).
5. Parent LSP-DB persistence / run isolation.

## 9. Suggested order

Phase 1 (generalise ledger) -> Phase 2 (turn on WSON, cheap) -> Phase 3 (build
MPLS) -> Phase 4 (centralized assignment + explicit control) -> Phase 5
(stability) -> Phase 6 (PCInitiate). Each phase compiles + runs + verified before
the next.
