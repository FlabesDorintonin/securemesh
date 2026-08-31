# SecureMesh VANGUARD — Canonical Upgrade Baseline

Date: 2026-08-31
Status: CANONICAL WORKING BASELINE

This document fixes the engineering baseline for further VANGUARD routing work. It does not promote any hardware or field status.

## Exact identities

- Executable v1.0.4 routing/package baseline: `b11a92d8925870d29b3b2bc22553d87730053810`.
- Algorithm documentation baseline before this decision: `114b15b2e7f6467af20fef8fd1e515152b46d5d4`.
- Deep-audit research commit: `54ca8b8d981923635218ce6f7af279f13d3992ca`.
- Deep-audit GitHub Actions run: `33413483336` — SUCCESS.
- Full canonical research report: Google Doc ID `1U80-inhTgdGf5xvAhS95eLAwvjoInI4eA14hSUWhAXM`, title `CANONICAL — SecureMesh VANGUARD Routing Deep Audit & Upgrade Baseline — 2026-08-31`.

## Evidence boundary

Research CI passed the full native C++ routing suite on g++ and clang++, ASan + UBSan, and five deterministic 1,000,000-operation invariant-fuzz seeds (5,000,000 total operations), plus audit characterization tests.

This is NATIVE/RESEARCH evidence only. It does not establish physical LoRa PDR, failover latency, range, collision behaviour, scalability above the current qualified profile, HARDWARE TESTED, or FIELD TESTED status.

## Architectural decision

Do **not** replace VANGUARD with plain AODV, AOMDV, RPL, OLSRv2, flooding, Meshtastic-style routing, LoRaMesher distance-vector, or another protocol wholesale.

Preserve the current Safety/Core model unless new evidence disproves it:

1. complete NetworkScope admission;
2. destination generation/freshness;
3. feasibility/FD before optimization;
4. exact ordered path vector/mask checks;
5. loop prevention and fail-closed behaviour;
6. Primary route;
7. exact node-disjoint G2 with a different first hop;
8. source-private `pathTag`/flow-label pinning;
9. no silent generic reroute of tagged DATA;
10. path-specific RERR and bounded repair;
11. bounded airtime-aware control traffic;
12. bounded store-and-forward with downstream hop-ACK commit semantics.

The optimizer may evolve, but it must never bypass those safety gates.

## Confirmed weaknesses / hardening targets

### P0 — prove current physical behaviour

- Three-radio Primary + exact G2 qualification.
- Separate hard TX failure and passive disappearance/stale scenarios.
- Promotion, first DATA after promotion, path-specific RERR and G2 replenishment.
- Idle standby qualification at approximately 10 s / 35 s / 95 s.
- Record raw ACK/RSSI/SNR/HELLO/route snapshots and timestamps.
- Report distributions (PDR, discovery/failover p50/p95/p99), not anecdotal single values.

### P1 — routing hardening

1. **Unified failure ladder**: edge-triggered Fresh→Stale next-hop invalidation must use the same bounded promotion/repair logic as hard ACK exhaustion without repeated RERR storms.
2. **Directional recent LinkEstimator v2**: keep RX and TX evidence separate; use bounded recent/decaying evidence; retain lifetime counters only for diagnostics.
3. **Confidence separate from quality**: Unknown/Probing/Qualified/Degraded/Stale states. An unknown synthetic prior may bootstrap a missing route but must not displace an established route merely because of optimistic prior mathematics.
4. **Route-history hysteresis**: fast degradation detection plus smoothed comparison/dwell; hard failure bypasses hysteresis.
5. **Explicit admissibility limits**: calibrated `MAX_LINK_METRIC` / `MAX_PATH_COST`-style gates before path comparison.
6. **Unified exact G2/pathTag lease**: source-visible end-to-end pinned validation, not only first-hop liveness. Hot/Warm/Cold standby semantics must reflect the whole exact chain.
7. **Discovery clock on actual emission**: state `Queued → Emitted → Waiting`; attempt timeout/count begins only for an admitted/emitted control attempt.
8. **Complete NetworkScope identity in Core**: epoch plus manifest/scope revision/digest; dynamic routes/labels/discovery clear on epoch, digest or local slot-map change.
9. **Bounded-state observability**: high-water/allocation/drop/reuse counters for routes, discovery, reverse state, seen RREQ/RERR, flow labels, precursors and deferred control.
10. **Header/build hygiene**: VANGUARD headers must compile self-contained; add a compile-header gate.
11. **Real-code collision-aware simulator**: execute real `VanguardCore`/`VanguardRuntime` over a virtual LoRa PHY/MAC with ToA, half-duplex, collisions, capture, hidden terminals, asymmetric links, burst loss, queues/token budget, blackout, reboot and partition/merge.

### P2 — only after P0/P1 evidence

- Expected Airtime Cost when adaptive SF/BW/channel profiles exist.
- Additional standby/K-parent set only if simulator evidence shows benefit.
- Slow energy/load constraints only after calibrated telemetry exists.
- Opportunistic passive route hints that never bypass Safety.
- MPR/scoped relay optimization for larger networks only after collision-aware simulation.
- Coordinated MAC/TDMA only after a formal network-time/synchronization/recovery contract.
- Adaptive channel selection/FHSS only after RF interference benchmarks.
- Stronger authenticated route-origin/attestation only if the threat model requires compromised-member/Byzantine resistance.

## Metric direction

Do not introduce an opaque weighted score such as `a*RSSI + b*SNR + c*PDR + d*battery + e*queue`.

Preferred direction:

- freshness is an admission condition;
- receiver-side recent evidence characterizes incoming `previousHop → local` RREQ links;
- transmitter-side recent hop-ACK evidence characterizes outgoing DATA/RREP direction;
- confidence/effective sample count is explicit;
- reliability and expected airtime remain explainable quantities;
- RSSI/SNR are RF-margin/diagnostic evidence unless measured data proves a safe direct role;
- hop count remains a late tie-breaker rather than the primary objective.

## Comparator lessons adopted from external review

- Babel: feasibility-first architecture, recent/smoothed metrics and route-history hysteresis.
- MRHOF/RPL: explicit link/path cost ceilings and meaningful switch thresholds.
- OLSRv2: strict incoming/outgoing metric separation.
- AOMDV: multipath/disjointness as a useful comparator, not a replacement for exact pinned G2.
- DSR/MeshCore: path trace/diagnostics, while keeping compact `pathTag` DATA instead of full source route overhead.
- Meshtastic: practical retransmission/operational diagnostics, but no unbounded flood fallback for normal exact DATA.
- Reticulum: future path-request abuse controls/authenticated routing metadata if required by threat model.
- LoRaMesher/Bramble: simulation discipline; real protocol code must be exercised against airtime/collision/half-duplex models before scalability claims.

External simulation numbers are not SecureMesh evidence.

## Change rule

Every routing change must answer all of the following before merge:

- NetworkScope/manifest epoch+digest impact;
- generation/freshness impact;
- FD/feasibility and loop prevention;
- Primary selection;
- exact G2 disjointness and first-hop exclusion;
- `pathTag`/pinning/fail-closed behaviour;
- passive stale + hard failure promotion;
- G2 replenishment/expiration;
- store-and-forward/replay commit;
- bounded control airtime and queue pressure;
- contract/wire compatibility;
- native tests on g++/clang++, sanitizers and multi-seed fuzz;
- simulator scenarios when available;
- exact hardware qualification before any HARDWARE/FIELD status increase.

An implementation that improves a metric but weakens any safety invariant is rejected.

## Working order

The working sequence is therefore:

`prove current hardware → implement P1 failure/metric/lease/timing/observability hardening → run real-code simulator → calibrate from physical traces → re-run hardware qualification → only then add larger-scale/MAC/RF complexity`.
