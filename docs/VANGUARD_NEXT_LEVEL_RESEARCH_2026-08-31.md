# SecureMesh VANGUARD — Next-Level Upgrade Research

Date: 2026-08-31
Status: RESEARCH IMPLEMENTATION CANDIDATE — NOT PRODUCTION RUNTIME

## Exact identities

- Executable/current routing baseline under study: `b11a92d8925870d29b3b2bc22553d87730053810`.
- Canonical upgrade baseline: `fe8d56c0652645b94e5d1eea706797f16381d65a`.
- Public benchmark appendix: `a56ce8ea70144fa238206ce76717b59faf3048b8`.
- Research branch: `research/vanguard-next-level-20260831`.
- Final research CI input HEAD: `53123dd1a52c11eb5e709bb517f375e2faec00aa`.
- GitHub Actions run: `33418256254` — SUCCESS.
- Research artifact id: `9767861942`.
- Research artifact ZIP SHA-256: `f30e6068c9c683dd4b27cf46f16768ff71acfd6a4e6e1101ce47b5e2886b7e62`.

No production VANGUARD runtime behaviour is modified by this research stage.

## Verification executed

Final run `33418256254` passed:

- all native C++ tests under g++;
- all native C++ tests under clang++;
- ASan + UBSan routing tests;
- five deterministic 1,000,000-operation invariant fuzz seeds = 5,000,000 operations;
- exact-current characterization tests;
- LinkEstimator parameter sweep;
- LinkEstimator Pareto sweep;
- executable proposed failure/G2 lease specification;
- control-emission timing model.

Evidence boundary: NATIVE / MODEL / RESEARCH ONLY. No new HARDWARE TESTED or FIELD TESTED claim is created.

## Current estimator behaviour reproduced

The exact current estimator was modeled with:

- unknown-link per-attempt prior `p=0.75`;
- measured links using one-sided Wilson lower bound;
- four hop attempts for transaction reliability.

The model reproduced the known discontinuity:

- unknown current transaction reliability: `0.99609375`;
- current measured `4/4` ACK transaction reliability: approximately `0.99287055`;
- therefore the synthetic unknown link still looks numerically better than a 4/4 measured link.

The candidate policy separates quality from confidence. An `Unknown` or `Probing` challenger is not permitted to displace a `Qualified` incumbent merely because a synthetic/bootstrap number looks better.

## Adaptation experiment

Synthetic step-degradation after 100 successful outcomes produced the following number of subsequent failures before transaction reliability fell below 0.95:

- current cumulative estimator: `74` failures;
- sliding window 8: `3`;
- sliding window 16: `6`;
- sliding window 32: `12`;
- decayed effective counts 0.85: `2`;
- decay 0.90: `4`;
- decay 0.95: `9`.

Recovery after 16 failures to transaction reliability >=0.95 required:

- current cumulative: `27` successes;
- window 8: `6`;
- window 16: `11`;
- window 32: `21`;
- decay 0.85: `9`;
- decay 0.90: `11`;
- decay 0.95: `15`.

These numbers are synthetic research results and are not RF performance measurements.

## Flapping experiment

For two near-equal synthetic links (`p=0.82` and `p=0.81`) using a quality switch margin of 0.03 and confirmation dwell of eight samples, mean route switches per 2000 samples were:

- window 8: `17.8`;
- window 16: `8.8`;
- window 32: `2.2`;
- decay 0.85: `10.1`;
- decay 0.90: `6.7`;
- decay 0.95: `1.7`.

This rejects the naive assumption that the shortest evidence window is automatically best. Fast degradation detection and route stability must be optimized together.

## Pareto sweep

A broader synthetic sweep covered sliding windows 8..64 and decays 0.80..0.98. Under the selected synthetic objective (degradation delay, recovery delay, and near-equal-link churn), the useful stable tradeoff region was dominated by decayed-evidence candidates.

Representative Pareto points:

| Candidate | Failures to <0.95 | Recovery successes to >=0.95 | Mean switches / 3000 samples |
|---|---:|---:|---:|
| decay 0.98 | 22 | 20 | 0.05 |
| decay 0.97 | 15 | 18 | 0.20 |
| decay 0.96 | 11 | 17 | 1.10 |
| decay 0.95 | 9 | 15 | 2.35 |
| decay 0.94 | 7 | 14 | 3.65 |
| decay 0.93 | 6 | 13 | 5.40 |
| decay 0.92 | 5 | 12 | 7.35 |

No final decay constant is approved from synthetic data. Physical directional traces must calibrate it. The initial hardware-calibration search region should emphasize approximately 0.93–0.97 rather than fixing one value in advance.

## Directional evidence decision

RX and TX evidence must remain separate.

A deliberately asymmetric synthetic characterization produced an incoming `B→A` transaction-reliability estimate around `0.9982` while outgoing `A→B` was around `0.6548`. The exact numbers are synthetic, but they demonstrate why a single undirected link-quality value is unsafe.

Implementation direction:

- RREQ `previousHop→local`: receiver-side recent RX/HELLO evidence;
- RREP/DATA forwarding `local→nextHop`: recent TX hop-ACK evidence;
- lifetime cumulative counters remain diagnostics;
- RSSI/SNR remain RF-margin/diagnostic inputs unless physical evidence justifies a direct admissibility role;
- no opaque weighted `RSSI + SNR + PDR + battery + queue` score.

## LinkEstimator v2 implementation candidate

Preserve all VANGUARD Safety/Core gates before optimization.

Per directional link maintain:

1. freshness;
2. recent/decayed success-failure evidence;
3. explicit effective evidence count/confidence;
4. state `Unknown / Probing / Qualified / Degraded / Stale`;
5. optional RF-margin class for diagnostics/admission;
6. lifetime diagnostic counters separately.

Route selection candidate:

1. reject stale/hard-bad links;
2. enforce calibrated per-link/path admissibility ceilings;
3. never allow Unknown/Probing to displace a Qualified incumbent solely by synthetic score;
4. prefer incumbent unless challenger is materially better for a confirmation/dwell period;
5. compare reliability/confidence class;
6. then expected attempts / expected airtime;
7. hop count remains a late tie-breaker;
8. hard failure bypasses quality hysteresis.

The exact decay, qualification sample count, switch margin, dwell and admission thresholds are calibration parameters, not constants chosen by taste.

## Unified failure manager candidate

Executable specification tests passed the following proposed rules:

- `STALE` Primary and `HARD_TX_FAILED` Primary enter the same bounded promotion/repair ladder;
- failure is edge-triggered, preventing one RERR on every lifecycle tick;
- stale G2/backup invalidates only the affected backup and does not damage a live Primary;
- neighbor recovery proves neighbor liveness only and never resurrects an old dynamic destination route without new validation/discovery;
- promotion preference remains qualified exact G2, then feasible alternate, then rediscovery;
- G2 replenishment runs after promotion without blocking data forwarding.

Local stale/hard-failure unification can be implemented without changing the wire contract.

## Exact G2 / PathLease candidate

Immediate-standby status must refer to the entire exact path, not only source→first-hop liveness.

Proposed source-visible qualification:

- `Hot`: exact + valid `pathTag` + recent end-to-end pinned validation;
- `Warm`: path is known but not recent enough to promise immediate failover; refresh before claiming Hot;
- `Cold`: retained recovery candidate only;
- `Expired`: invalid.

The research executable model used the current rough lease classes Hot <=12s, Warm <=30s, Cold <=90s solely to exercise semantics; production values remain subject to hardware/airtime calibration.

Whether explicit end-to-end pinned-path validation requires a new control packet or can safely reuse an existing E2E service remains TBD. If the wire contract changes, firmware, Android/Commander decoder, protocol documentation and contract/behaviour tests must change together.

## Control timing candidate

Current architecture was characterized as starting the discovery deadline at `beginDiscovery()` before the outer TX/control layer confirms actual radio emission.

With the current modeled base timeout `9537 ms` and outer deferred-control max age `12000 ms`, the theoretical timing overlap is `2463 ms` under extreme queue pressure.

Proposed state machine:

`Queued → Emitted → Waiting`

Rules:

- enqueue does not consume an emitted attempt;
- attempt counter increments on admitted/confirmed emission;
- response deadline starts at actual emission;
- retry cannot be generated before a previous attempt actually left the queue;
- deferred control should coalesce by semantic request identity, not only byte equality;
- expose queue-delay/retry-before-emit counters.

The model produced zero retry-before-emit cases by construction. This is MODEL evidence, not measured firmware latency.

## Simulator admission contract

The next validation tool must run the actual C++ `VanguardCore` / `VanguardRuntime`, not a Python rewrite of the algorithm.

Minimum virtual-radio model:

- exact SecureMesh/LoRa time-on-air calculations;
- single-channel half-duplex behaviour;
- overlapping transmission collisions;
- timing/power capture model;
- hidden terminals;
- sensitivity/SNR reception threshold;
- directional/asymmetric links;
- burst loss and step changes/mobility;
- radio blackout;
- TX/control queues and token budget;
- reboot and destination generation changes;
- network partition/merge;
- bounded caches and allocation pressure.

Required comparison variants:

- current VANGUARD;
- VANGUARD + unified stale failure;
- VANGUARD + LinkEstimator v2;
- simple AODV-like single-path baseline;
- explicitly bounded managed-flood baseline.

Required metrics:

- E2E PDR;
- discovery p50/p95/p99;
- failover blackout p50/p95/p99;
- route churn and false failover;
- control airtime per useful delivered byte;
- queue/cache allocation/drop pressure;
- G2 availability, promotion success and replenishment time;
- memory/stack/watchdog/reset behaviour.

No scalability claim above the currently qualified profile may be created from graph-only simulation.

## External design lessons incorporated

- Babel RFC 8966: feasibility remains separate from metric optimization; recent link history plus smoothed/route-history hysteresis is a mature pattern.
- MRHOF RFC 6719: explicit link/path admissibility ceilings and meaningful switch thresholds.
- OLSRv2 RFC 7181: strict incoming/outgoing wireless metric directionality.
- LoRa simulation literature/tools: collision, capture, half-duplex and hidden-terminal effects must be first-class before scale claims.

External results are design references, not SecureMesh evidence.

## Implementation order approved by this research

1. Keep current production runtime unchanged until an implementation branch is created from an exact baseline.
2. Implement local unified stale/hard failure ladder first; this is narrow and can remain wire-compatible.
3. Add observability required to capture directional physical traces.
4. Capture three-node physical baseline traces before selecting estimator constants.
5. Implement directional decayed LinkEstimator v2 + explicit confidence and calibrated hysteresis/admission.
6. Implement emission-aware discovery timing.
7. Design/qualify exact G2 end-to-end PathLease; decide explicitly whether signalling requires a wire revision.
8. Build the real-code collision-aware simulator and compare current vs candidate implementations.
9. Re-run 3-node hardware qualification and then investigate 10+ nodes.
10. Only after that investigate coordinated MAC, adaptive channels/FHSS, energy/load routing and stronger route-origin security.

## Evidence verdict

This research materially narrows the next VANGUARD implementation and rejects several naive designs, but it does not prove RF performance improvement.

- Existing VANGUARD Core safety: NATIVE TESTED.
- Next-level research models/specification: NATIVE/MODEL RESEARCH PASS.
- LinkEstimator v2 production implementation: PLANNED.
- Unified stale failure production implementation: PLANNED.
- End-to-end G2 PathLease production implementation: PLANNED.
- Emission-aware discovery production implementation: PLANNED.
- Real-code collision-aware simulator: PLANNED.
- Physical next-level routing improvement: TODO EVIDENCE.
