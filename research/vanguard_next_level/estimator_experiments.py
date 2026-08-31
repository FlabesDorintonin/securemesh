#!/usr/bin/env python3
"""VANGUARD LinkEstimator research harness.

This file models estimator candidates only. It does not claim production routing
behaviour except where explicitly labelled CURRENT. Synthetic traces are used to
reject bad estimator families and narrow the hardware calibration space.
"""
from __future__ import annotations

import csv
import json
import math
import random
from collections import deque
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

Z = 1.28
MAX_ATTEMPTS = 4
UNKNOWN_CURRENT_P = 0.75
OUT = Path(__file__).resolve().parent / "results"


def wilson_lower(successes: float, total: float, z: float = Z) -> float:
    if total <= 0.0:
        return 0.0
    phat = successes / total
    z2 = z * z
    denom = 1.0 + z2 / total
    center = phat + z2 / (2.0 * total)
    spread = z * math.sqrt(max(0.0, (phat * (1.0 - phat) + z2 / (4.0 * total)) / total))
    return max(0.0, min(1.0, (center - spread) / denom))


def tx_reliability(p: float, attempts: int = MAX_ATTEMPTS) -> float:
    return 1.0 - (1.0 - p) ** attempts


def eca(p: float, attempts: int = MAX_ATTEMPTS) -> float:
    if p <= 0.0:
        return float(attempts)
    q = 1.0 - p
    return sum((k * (q ** (k - 1)) * p) for k in range(1, attempts + 1)) + attempts * (q ** attempts)


@dataclass
class Estimate:
    p_lower: float
    reliability: float
    eca: float
    evidence: float
    state: str


class CurrentCumulative:
    name = "current_cumulative"

    def __init__(self) -> None:
        self.success = 0.0
        self.total = 0.0

    def update(self, ok: bool) -> None:
        self.total += 1.0
        self.success += 1.0 if ok else 0.0

    def estimate(self) -> Estimate:
        p = UNKNOWN_CURRENT_P if self.total == 0 else wilson_lower(self.success, self.total)
        state = "Unknown" if self.total == 0 else ("Probing" if self.total < 5 else "Qualified")
        return Estimate(p, tx_reliability(p), eca(p), self.total, state)


class SlidingWindow:
    def __init__(self, size: int, prior_success: float = 1.5, prior_failure: float = 0.5) -> None:
        self.size = size
        self.name = f"window_{size}"
        self.samples: deque[int] = deque(maxlen=size)
        self.prior_success = prior_success
        self.prior_failure = prior_failure

    def update(self, ok: bool) -> None:
        self.samples.append(1 if ok else 0)

    def estimate(self) -> Estimate:
        n = len(self.samples)
        total = self.prior_success + self.prior_failure + n
        success = self.prior_success + sum(self.samples)
        p = wilson_lower(success, total)
        state = "Unknown" if n == 0 else ("Probing" if n < 5 else "Qualified")
        return Estimate(p, tx_reliability(p), eca(p), float(n), state)


class DecayedCounts:
    def __init__(self, decay: float, prior_success: float = 1.5, prior_failure: float = 0.5) -> None:
        self.decay = decay
        self.name = f"decay_{decay:.2f}"
        self.success = 0.0
        self.failure = 0.0
        self.samples_seen = 0
        self.prior_success = prior_success
        self.prior_failure = prior_failure

    def update(self, ok: bool) -> None:
        self.success *= self.decay
        self.failure *= self.decay
        if ok:
            self.success += 1.0
        else:
            self.failure += 1.0
        self.samples_seen += 1

    def estimate(self) -> Estimate:
        success = self.prior_success + self.success
        total = self.prior_success + self.prior_failure + self.success + self.failure
        p = wilson_lower(success, total)
        effective = self.success + self.failure
        state = "Unknown" if self.samples_seen == 0 else ("Probing" if self.samples_seen < 5 else "Qualified")
        return Estimate(p, tx_reliability(p), eca(p), effective, state)


def candidates():
    return [
        CurrentCumulative(),
        SlidingWindow(8), SlidingWindow(16), SlidingWindow(32),
        DecayedCounts(0.85), DecayedCounts(0.90), DecayedCounts(0.95),
    ]


def feed(estimator, outcomes: Iterable[bool]) -> None:
    for value in outcomes:
        estimator.update(value)


def failures_to_cross(estimator, successes_before: int, threshold: float, max_failures: int = 256) -> int | None:
    feed(estimator, [True] * successes_before)
    for i in range(1, max_failures + 1):
        estimator.update(False)
        if estimator.estimate().reliability < threshold:
            return i
    return None


def successes_to_cross(estimator, failures_before: int, threshold: float, max_successes: int = 256) -> int | None:
    feed(estimator, [False] * failures_before)
    for i in range(1, max_successes + 1):
        estimator.update(True)
        if estimator.estimate().reliability >= threshold:
            return i
    return None


def deterministic_adaptation_rows() -> list[dict]:
    rows: list[dict] = []
    for factory_name in ["current", "w8", "w16", "w32", "d85", "d90", "d95"]:
        def make():
            return {
                "current": CurrentCumulative,
                "w8": lambda: SlidingWindow(8),
                "w16": lambda: SlidingWindow(16),
                "w32": lambda: SlidingWindow(32),
                "d85": lambda: DecayedCounts(0.85),
                "d90": lambda: DecayedCounts(0.90),
                "d95": lambda: DecayedCounts(0.95),
            }[factory_name]()
        probe = make()
        rows.append({
            "estimator": probe.name,
            "failures_after_100_success_to_rel_below_0.98": failures_to_cross(make(), 100, 0.98),
            "failures_after_100_success_to_rel_below_0.95": failures_to_cross(make(), 100, 0.95),
            "failures_after_100_success_to_rel_below_0.90": failures_to_cross(make(), 100, 0.90),
            "successes_after_16_failures_to_rel_at_least_0.95": successes_to_cross(make(), 16, 0.95),
        })
    return rows


def unknown_vs_qualified() -> dict:
    current_unknown = CurrentCumulative().estimate()
    current_measured = CurrentCumulative()
    feed(current_measured, [True] * 4)
    measured = current_measured.estimate()

    # Candidate policy: quality score and permission to displace are separate.
    candidate_unknown = SlidingWindow(16).estimate()
    candidate_qualified = SlidingWindow(16)
    feed(candidate_qualified, [True] * 8)
    qualified = candidate_qualified.estimate()

    current_bad = current_unknown.reliability > measured.reliability
    candidate_unknown_may_displace = (
        candidate_unknown.state == "Qualified" and
        candidate_unknown.reliability > qualified.reliability
    )
    assert current_bad, "Expected to reproduce current unknown-prior discontinuity"
    assert not candidate_unknown_may_displace, "Unknown candidate must not displace qualified incumbent"
    return {
        "current_unknown_reliability": current_unknown.reliability,
        "current_4_of_4_reliability": measured.reliability,
        "current_unknown_looks_better": current_bad,
        "candidate_unknown_state": candidate_unknown.state,
        "candidate_8_of_8_state": qualified.state,
        "candidate_unknown_may_displace_qualified": candidate_unknown_may_displace,
    }


def noisy_switch_count(factory, seed: int, samples: int = 2000, p_a: float = 0.82, p_b: float = 0.81,
                       switch_margin: float = 0.03, dwell: int = 8) -> int:
    rng = random.Random(seed)
    a = factory()
    b = factory()
    incumbent = "A"
    confirmations = 0
    switches = 0
    for _ in range(samples):
        a.update(rng.random() < p_a)
        b.update(rng.random() < p_b)
        ea, eb = a.estimate(), b.estimate()
        # Quality-driven switch is allowed only when both paths are qualified.
        if ea.state != "Qualified" or eb.state != "Qualified":
            confirmations = 0
            continue
        challenger_better = (eb.reliability - ea.reliability) if incumbent == "A" else (ea.reliability - eb.reliability)
        if challenger_better > switch_margin:
            confirmations += 1
            if confirmations >= dwell:
                incumbent = "B" if incumbent == "A" else "A"
                switches += 1
                confirmations = 0
        else:
            confirmations = 0
    return switches


def noisy_rows() -> list[dict]:
    factories = {
        "window_8": lambda: SlidingWindow(8),
        "window_16": lambda: SlidingWindow(16),
        "window_32": lambda: SlidingWindow(32),
        "decay_0.85": lambda: DecayedCounts(0.85),
        "decay_0.90": lambda: DecayedCounts(0.90),
        "decay_0.95": lambda: DecayedCounts(0.95),
    }
    rows = []
    for name, factory in factories.items():
        counts = [noisy_switch_count(factory, seed) for seed in range(10)]
        rows.append({"estimator": name, "mean_switches_2000_samples": sum(counts) / len(counts), "max_switches": max(counts)})
    return rows


def directionality_characterization() -> dict:
    # Deliberately asymmetric link: incoming B->A excellent; outgoing A->B poor.
    rx = SlidingWindow(16)
    tx = SlidingWindow(16)
    feed(rx, [True] * 15 + [False])
    feed(tx, [True] * 5 + [False] * 11)
    rx_e = rx.estimate()
    tx_e = tx.estimate()
    assert rx_e.reliability > tx_e.reliability
    return {
        "incoming_B_to_A_reliability": rx_e.reliability,
        "outgoing_A_to_B_reliability": tx_e.reliability,
        "must_remain_separate": True,
    }


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    adaptation = deterministic_adaptation_rows()
    noisy = noisy_rows()
    summary = {
        "evidence_boundary": "SYNTHETIC_RESEARCH_ONLY",
        "unknown_vs_qualified": unknown_vs_qualified(),
        "directionality": directionality_characterization(),
        "adaptation": adaptation,
        "noisy_near_equal_links": noisy,
        "decision": "Do not choose final window/decay constants without physical directional traces.",
    }
    (OUT / "estimator_summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    with (OUT / "estimator_adaptation.csv").open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=adaptation[0].keys())
        writer.writeheader(); writer.writerows(adaptation)
    with (OUT / "estimator_flapping.csv").open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=noisy[0].keys())
        writer.writeheader(); writer.writerows(noisy)

    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
