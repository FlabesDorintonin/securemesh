# VANGUARD P1 — Passive Stale Failure Candidate

Date: 2026-08-31
Status: CI/NATIVE SOURCE CANDIDATE — NOT MERGED, NOT HARDWARE TESTED

## Exact identity

- Source branch: `vanguard/p1-passive-stale-candidate`.
- Candidate workflow input HEAD: `a4097f5eb6c01120a72321a3ff7773542045ec8b`.
- GitHub Actions run: `33419233821` — SUCCESS.
- Workflow artifact id: `9768234909`.
- Workflow artifact digest: `sha256:90b90ae2ffbf80f609c0fe79968f81e5a4dd263352824eb5e7fae2a7eb2bfd86`.
- Materialized firmware `.ino` SHA-256: `73034b0386a6150f622a774727d4c50216c7b9f65b8e2f3712cde3c77dbbd2ec`.
- Wire contract change: NONE.
- Merge into production runtime: NOT PERFORMED.
- Hardware gate: REQUIRED.

## Change

The candidate modifies only the neighbour lifecycle integration after deterministic materialization.

On a real `Fresh -> Stale` transition, the firmware calls the existing `notifyVanguardHopFailure(neighborNodeId)` path. That path already feeds `VanguardRuntime::onLocalHopFailure(...)`, so passive disappearance enters the same bounded route-failure/promotion logic used by explicit local hop failure.

The transition is edge-triggered by `neighborLifecycleFresh[]`. A neighbour that remains stale cannot generate one failure action on every 1-second lifecycle tick.

Fresh recovery does not call the failure path and does not resurrect an old dynamic route by this change.

## Verification

Run `33419233821` passed:

- deterministic candidate materialization;
- second materialization is a byte-identical no-op;
- `git diff --check`;
- dedicated passive-stale source gate;
- existing firmware Arduino-preprocessor gate;
- existing degraded-radio/UI gate;
- all `vanguard_*.cpp` native tests under g++;
- all `vanguard_*.cpp` native tests under clang++;
- ASan + UBSan over the routing tests;
- artifact packaging and SHA identity.

The downloaded workflow artifact was independently unpacked after CI and its embedded `SHA256SUMS.txt` matched the materialized `.ino` SHA-256 above.

## Evidence boundary

This proves source transformation/integration shape plus native/runtime regression behaviour only.

It does NOT yet prove:

- physical passive disappearance -> G2 promotion;
- actual failover blackout time;
- E2E message delivery after promotion;
- absence of duplicate repair traffic under all RF timing races;
- physical G2 availability/replenishment;
- improvement in PDR or reliability.

Therefore the capability remains at its prior NATIVE evidence level.

## Required hardware test before merge

Three physical nodes with exact candidate firmware:

1. establish Primary and exact G2;
2. remove/power-off the Primary next-hop without first causing active TX exhaustion;
3. wait for the freshness edge and record exact timestamps;
4. verify one bounded failure event and G2/alternate promotion;
5. send the first DATA after promotion and prove E2E delivery;
6. verify no repeated RERR/failure storm on subsequent stale lifecycle ticks;
7. restore the neighbour and verify old destination route is not resurrected without normal validation/discovery;
8. verify background G2 replenishment;
9. repeat separately for explicit hard TX failure and compare behaviour.

Only after that hardware evidence should this narrow change be considered for merge into the executable baseline.
