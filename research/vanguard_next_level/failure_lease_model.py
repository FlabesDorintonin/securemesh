#!/usr/bin/env python3
"""Executable research model for the proposed VANGUARD failure/G2 contract.

This is a specification test, not production firmware.
"""
from __future__ import annotations

import json
from dataclasses import dataclass
from enum import Enum, auto
from pathlib import Path

OUT = Path(__file__).resolve().parent / "results"
HOT_MS = 12_000
WARM_MS = 30_000
EXPIRE_MS = 90_000


class Failure(Enum):
    SOFT_DEGRADED = auto()
    STALE = auto()
    HARD_TX_FAILED = auto()
    PINNED_PATH_FAILED = auto()
    SCOPE_INVALIDATED = auto()


class Lease(Enum):
    HOT = auto()
    WARM = auto()
    COLD = auto()
    EXPIRED = auto()


@dataclass
class Route:
    next_hop: str
    valid: bool = True
    exact: bool = False
    path_tag: int = 0
    last_pinned_validated_ms: int = 0


@dataclass
class Table:
    primary: Route | None
    g2: Route | None
    alternate: Route | None
    failure_epoch: int = 0
    rerr_events: int = 0
    rediscovery_events: int = 0
    promotions: int = 0


def lease(route: Route | None, now_ms: int) -> Lease:
    if route is None or not route.valid or not route.exact or route.path_tag == 0:
        return Lease.EXPIRED
    age = max(0, now_ms - route.last_pinned_validated_ms)
    if age <= HOT_MS:
        return Lease.HOT
    if age <= WARM_MS:
        return Lease.WARM
    if age <= EXPIRE_MS:
        return Lease.COLD
    return Lease.EXPIRED


def qualified_for_immediate_promotion(route: Route | None, now_ms: int) -> bool:
    # Conservative proposed contract: only a Hot, end-to-end pinned-validated
    # G2 may be advertised as "immediate" standby. Warm/Cold may be refreshed
    # or used as a non-immediate recovery candidate, but not promised as hot.
    return lease(route, now_ms) is Lease.HOT


def fail_next_hop(table: Table, failed_hop: str, failure: Failure, now_ms: int) -> str:
    """Edge-triggered failure action for both stale and hard TX events."""
    if failure not in (Failure.STALE, Failure.HARD_TX_FAILED, Failure.PINNED_PATH_FAILED):
        return "NO_ROUTE_ACTION"

    table.failure_epoch += 1
    primary_hit = table.primary is not None and table.primary.valid and table.primary.next_hop == failed_hop
    g2_hit = table.g2 is not None and table.g2.valid and table.g2.next_hop == failed_hop
    alt_hit = table.alternate is not None and table.alternate.valid and table.alternate.next_hop == failed_hop

    if g2_hit:
        table.g2.valid = False
    if alt_hit:
        table.alternate.valid = False

    if not primary_hit:
        return "INVALIDATE_NONPRIMARY_ONLY"

    table.primary.valid = False
    # One edge-triggered repair/error event per newly observed failure, not per tick.
    table.rerr_events += 1

    if qualified_for_immediate_promotion(table.g2, now_ms):
        table.primary = table.g2
        table.g2 = None
        table.promotions += 1
        return "PROMOTE_G2"
    if table.alternate is not None and table.alternate.valid:
        table.primary = table.alternate
        table.alternate = None
        table.promotions += 1
        return "PROMOTE_ALTERNATE"

    table.rediscovery_events += 1
    return "REDISCOVER"


def recover_neighbor(table: Table, recovered_hop: str) -> str:
    # A fresh HELLO/RX proves neighbour liveness, not the old destination route.
    # No old dynamic route is resurrected without new route validation/discovery.
    return "NEIGHBOR_FRESH_ROUTE_UNCHANGED"


def make_table() -> Table:
    return Table(
        primary=Route("B"),
        g2=Route("C", exact=True, path_tag=77, last_pinned_validated_ms=1000),
        alternate=Route("E"),
    )


def main() -> None:
    findings = {}

    hard = make_table()
    hard_action = fail_next_hop(hard, "B", Failure.HARD_TX_FAILED, 5000)
    assert hard_action == "PROMOTE_G2"

    stale = make_table()
    stale_action = fail_next_hop(stale, "B", Failure.STALE, 5000)
    assert stale_action == hard_action
    assert stale.rerr_events == hard.rerr_events == 1

    stale_backup = make_table()
    action = fail_next_hop(stale_backup, "C", Failure.STALE, 5000)
    assert action == "INVALIDATE_NONPRIMARY_ONLY"
    assert stale_backup.primary is not None and stale_backup.primary.valid and stale_backup.primary.next_hop == "B"
    assert stale_backup.g2 is not None and not stale_backup.g2.valid
    assert stale_backup.rerr_events == 0

    old_g2 = make_table()
    # First-hop liveness elsewhere must not refresh exact path readiness.
    assert lease(old_g2.g2, 96_000) is Lease.EXPIRED
    action = fail_next_hop(old_g2, "B", Failure.STALE, 96_000)
    assert action == "PROMOTE_ALTERNATE", "Expired exact G2 must not be promised as immediate standby"

    recovered = make_table()
    recovered.primary.valid = False
    before = recovered.primary.valid
    recovery = recover_neighbor(recovered, "B")
    assert recovery == "NEIGHBOR_FRESH_ROUTE_UNCHANGED"
    assert recovered.primary.valid == before

    findings.update({
        "evidence_boundary": "EXECUTABLE_SPEC_MODEL_ONLY",
        "hard_and_stale_same_primary_ladder": hard_action == stale_action,
        "edge_triggered_rerr_count": stale.rerr_events,
        "stale_backup_does_not_damage_primary": True,
        "expired_g2_not_immediate_standby": True,
        "neighbor_recovery_does_not_resurrect_route": True,
        "proposed_immediate_g2_requirement": "HOT + exact + pathTag + recent end-to-end pinned validation",
        "wire_change_required_for_local_failure_unification": False,
        "path_validation_signalling": "TBD; may require wire change if explicit end-to-end probe/ack is added",
    })
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "failure_lease_summary.json").write_text(json.dumps(findings, indent=2), encoding="utf-8")
    print(json.dumps(findings, indent=2))


if __name__ == "__main__":
    main()
