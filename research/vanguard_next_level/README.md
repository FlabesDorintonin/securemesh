# VANGUARD next-level research

Status: RESEARCH ONLY. No production/runtime behaviour is changed by this directory.

Baseline under study: `b11a92d8925870d29b3b2bc22553d87730053810`.
Canonical upgrade decision: `fe8d56c0652645b94e5d1eea706797f16381d65a`.
Public benchmark appendix: `a56ce8ea70144fa238206ce76717b59faf3048b8`.

## Questions

1. Which recent-evidence estimator family removes the current unknown-prior discontinuity and lifetime-history inertia without introducing route flapping?
2. How should confidence be separated from link quality?
3. How large is the current discovery-timer vs deferred-emission race window?
4. Which properties can be proven by native/model tests before touching runtime?

## Candidate families

The research intentionally sweeps parameters instead of choosing constants by taste:

- current cumulative Wilson estimator with the current synthetic unknown prior;
- bounded sliding windows of 8, 16, and 32 outcomes;
- exponentially decayed effective counts with decay 0.85, 0.90, and 0.95;
- explicit `Unknown / Probing / Qualified / Degraded / Stale` confidence state;
- a route-history switch gate that prevents an unknown/probing challenger from displacing a qualified incumbent merely because of a synthetic prior.

The eventual firmware constants MUST be calibrated from physical directional ACK/RX traces. Synthetic experiments are not hardware evidence.

## Research invariants

- Safety (`NetworkScope`, generation, FD/feasibility, exact masks, loop prevention) is not part of the optimizer sweep and must not be weakened.
- RX and TX evidence are directional and remain separate.
- RSSI/SNR are not turned into an opaque weighted routing score.
- Hard failure may bypass hysteresis; quality-driven route changes may not.
- Exact G2/pathTag fail-closed semantics remain mandatory.
- No runtime code is modified in this research stage.
