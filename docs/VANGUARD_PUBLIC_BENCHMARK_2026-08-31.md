# SecureMesh VANGUARD — Public Benchmark and Informal Maturity Rating

Date: 2026-08-31
Status: CANONICAL RESEARCH APPENDIX

This document records a public-source comparison of the exact SecureMesh VANGUARD v1.0.4 routing baseline against open routing standards/projects and publicly described tactical MANET systems.

It is not a certification, military qualification, security classification, or claim of performance not demonstrated by SecureMesh evidence.

## Exact SecureMesh identities

- Executable routing/package baseline: `b11a92d8925870d29b3b2bc22553d87730053810`.
- Canonical VANGUARD upgrade baseline commit: `fe8d56c0652645b94e5d1eea706797f16381d65a`.
- Deep-audit research commit: `54ca8b8d981923635218ce6f7af279f13d3992ca`.
- Deep-audit Actions run: `33413483336` — SUCCESS.
- Full canonical Google Doc: `1U80-inhTgdGf5xvAhS95eLAwvjoInI4eA14hSUWhAXM`.

## Informal 1–10 scale

This is an engineering communication aid only.

- 1–2 — "детский": naive flooding/static routes, little freshness/safety/recovery/evidence.
- 3–4 — "школьный": simple hop-count/RSSI and retries, basic loop handling, limited tests.
- 5–7 — "серьёзный инженерный": explicit route state, freshness/loop prevention, metrics, bounded resources, repair and meaningful automated verification.
- 8–9 — "военный инженерный класс" as an informal capability ceiling, not certification: fast self-healing, strong redundancy, adversarial-RF resilience/spectrum agility, strong security, QoS/observability and substantial scale/field qualification.
- 10 — "секретный/стратегический" is not a valid technical category. Classified systems cannot be honestly ranked by non-public algorithms, and secrecy does not imply better routing.

## SecureMesh rating on 2026-08-31

### Routing Safety/Core design: approximately 8.3 / 10

Strong properties:

- NetworkScope admission;
- destination generation/freshness;
- feasibility/FD before optimization;
- exact ordered path vector/mask validation;
- exact node-disjoint G2 with different first hop;
- source-private pathTag/flow-label pinning;
- fail-closed on pinned path loss;
- path-specific RERR;
- bounded airtime-aware control;
- bounded store-and-forward with downstream hop-ACK commit semantics.

### Whole current system/evidence maturity: approximately 6.9 / 10

The score is reduced by known evidence and integration gaps:

- passive stale Primary does not yet use exactly the same failure ladder as hard ACK failure;
- current LinkEstimator has optimistic unknown-prior discontinuity, lifetime-history inertia and RREQ directionality issues;
- G2 route lease and relay flow-label lease are not yet one end-to-end qualification contract;
- discovery attempt timing is not tied to confirmed control emission;
- current qualification boundary is the 5-node profile;
- no real-code collision-aware VANGUARD simulator yet;
- physical Primary/G2/failover qualification remains open;
- no demonstrated adaptive-channel/FHSS/anti-jam routing integration;
- no large-scale field qualification.

Verdict today: upper "серьёзный инженерный" class, not a military-qualified system.

## Target after canonical P1 hardening

After passive-stale unification, directional recent estimator + explicit confidence, calibrated hysteresis/admission, unified G2/path lease, emission-aware discovery timing, complete scope identity, pressure observability, real-code collision-aware simulation and 3-radio/10+ node evidence, an engineering target around 8.2–8.5 / 10 is reasonable.

A score around 9 would require system-level evidence beyond routing: measured spectrum agility/FHSS/anti-jam behaviour, stronger routing trust/security, larger-scale operational qualification, robust time/MAC behaviour and repeatable field reliability.

## Open standards/projects reviewed

### Babel — RFC 8966

Reference: https://www.rfc-editor.org/rfc/rfc8966.html

Key lesson: feasibility-first loop safety is a mature design family. Babel also explicitly recommends route-history hysteresis and smoothed metrics for continuously varying wireless costs. This supports preserving VANGUARD Safety/Core while replacing its current estimator/history behaviour.

### RPL/MRHOF — RFC 6550 / RFC 6719

References:
- https://www.rfc-editor.org/rfc/rfc6550.html
- https://www.rfc-editor.org/rfc/rfc6719.html

Key lesson: explicit `MAX_LINK_METRIC`, `MAX_PATH_COST`, switch thresholds and small alternate parent sets are useful patterns. RPL's root/DODAG orientation does not map cleanly to SecureMesh peer-to-peer exact G2.

### OLSRv2 — RFC 7181

Reference: https://www.rfc-editor.org/rfc/rfc7181.html

Key lesson: incoming and outgoing wireless link metrics must be treated as directional, and receiver-side evidence is important. MPR-like control reduction is a future scaling option only after simulation.

### AODV — RFC 3561

Reference: https://www.rfc-editor.org/rfc/rfc3561.html

Reactive RREQ/RREP/RERR and destination freshness are useful foundations, but replacing VANGUARD with plain AODV would lose exact G2/pathTag/fail-closed properties.

### Meshtastic

Reference: https://github.com/meshtastic/firmware/blob/develop/src/mesh/ReliableRouter.cpp

The current public firmware combines next-hop routing with reliable retransmission and airtime-aware retry timing. It is much more field/user mature than SecureMesh today, but its public routing model is less strict than VANGUARD's exact disjoint standby architecture.

### MeshCore

References:
- https://github.com/meshcore-dev/MeshCore/blob/main/docs/packet_format.md
- https://github.com/meshcore-dev/MeshCore/discussions/1995

Useful source/direct path and trace concepts. Public 2026 discussion documents "first packet wins" path selection concerns, which reinforces VANGUARD's bounded settle + metric-selection direction.

### Reticulum

References:
- https://reticulum.network/manual/networks.html
- https://reticulum.network/manual/understanding.html
- https://reticulum.network/manual/interfaces.html

Useful lessons include cryptographic identity integration, bounded announce bandwidth and path-request behaviour. Transport-node role separation is not adopted because SecureMesh's canonical owner architecture keeps ordinary nodes independently capable peers.

### LoRaMesher 1.0

References:
- https://www.sciencedirect.com/science/article/pii/S2352711026000646
- https://github.com/LoRaMesher/LoRaMesher

A close practical comparator: ESP32 + RadioLib + FreeRTOS + LoRa mesh. Current project documentation describes proactive distance-vector routing over a TDMA superframe with elected Network Manager semantics. This is useful as a MAC/simulation benchmark but is not a drop-in replacement for SecureMesh.

### Bramble

Reference: https://github.com/justinlindh/bramble

The strongest lesson is validation methodology: real protocol code driven over a virtual LoRa medium modelling time-on-air, collisions, capture, half-duplex and LBT, with explicit correction of earlier optimistic scale results. SecureMesh should adopt this evidence discipline.

## Public tactical/mission-critical system ceiling

The following are used only as public capability/evidence references because their internal routing algorithms are largely proprietary.

### TrellisWare TSM / Katana / WREN

References:
- https://www.trellisware.com/waveforms/tsm-waveform/
- https://www.trellisware.com/waveforms/katana-nb-waveform/
- https://www.trellisware.com/waveforms/wren-tsm-waveform/

Publicly described capabilities include very large tactical MANET deployments, mobility, voice/data/video/PLI, anti-jam/frequency-hopping variants and High-Assurance-enabled security on qualified partner platforms. Public vendor material reports 800+ radio testing for TSM.

### Persistent Systems Wave Relay / MPU5

References:
- https://persistentsystems.com/mpu5-specs/
- https://persistentsystems.com/mpu5-networking-radio-and-embedded-module-achieve-fips-140-2-security-validation/

Public material describes self-forming/self-healing peer-to-peer MANET operation and FIPS 140-2 validated product security. Exact proprietary route mathematics are not public enough for a direct algorithmic score.

### Silvus StreamCaster / MN-MIMO

Reference: https://silvustechnologies.com/products/streamcaster-4400-enhanced/

Public material describes self-forming/self-healing MANET, adaptive links, MIMO/beamforming and hundreds of nodes. This is a system-level reference, not an exact routing-algorithm comparator.

### Doodle Labs Mesh Rider

References:
- https://doodlelabs.com/capabilities/mesh/
- https://doodlelabs.com/news/sense-interference-avoidance-release/

Public material describes tactical self-forming/self-healing mesh, 100-node swarm deployments and interference/channel/band agility for anti-jam use cases.

### Rajant InstaMesh

Reference: https://rajant.com/technology/rajant-kinetic-wireless-mesh-networks/

Public material describes decentralized peer-to-peer multi-radio operation, multiple simultaneous links/frequencies and dynamic selection of traffic paths. Internal proprietary routing details are not directly comparable.

## Comparative conclusion

Among the open LoRa/mesh designs reviewed in this audit, VANGUARD is in the upper group for routing-correctness architecture, especially because optimization cannot bypass feasibility/scope safety and because its G2 is an exact pinned standby rather than merely a second next hop.

It is not yet in the same system-maturity class as tactical MANET products. The gap is dominated by evidence and whole-radio-system capability, not by the absence of a sophisticated routing idea.

The correct path is therefore unchanged:

`prove current physical routing → P1 hardening → real-code collision-aware simulator → calibrated hardware traces → repeat qualification → only then spectrum/MAC/security/scale upgrades`.

Vendor performance/scalability/anti-jam claims are treated as vendor statements unless independently verified; none of them are SecureMesh evidence.