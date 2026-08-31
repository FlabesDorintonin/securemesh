#!/usr/bin/env python3
"""Broader synthetic Pareto sweep for VANGUARD estimator candidates."""
from __future__ import annotations

import csv
import json
from pathlib import Path
from estimator_experiments import SlidingWindow, DecayedCounts, failures_to_cross, successes_to_cross, noisy_switch_count

OUT = Path(__file__).resolve().parent / "results"


def dominates(a: dict, b: dict) -> bool:
    keys = ("degrade_failures_to_rel_below_0.95", "mean_switches", "recovery_successes_to_rel_0.95")
    return all(a[k] <= b[k] for k in keys) and any(a[k] < b[k] for k in keys)


def evaluate(name, factory) -> dict:
    degrade = failures_to_cross(factory(), 100, 0.95)
    recovery = successes_to_cross(factory(), 16, 0.95)
    counts = [noisy_switch_count(factory, seed, samples=3000) for seed in range(20)]
    return {
        "candidate": name,
        "degrade_failures_to_rel_below_0.95": int(degrade if degrade is not None else 9999),
        "recovery_successes_to_rel_0.95": int(recovery if recovery is not None else 9999),
        "mean_switches": round(sum(counts) / len(counts), 3),
        "max_switches": max(counts),
    }


def main() -> None:
    rows = []
    for size in (8, 12, 16, 20, 24, 32, 40, 48, 64):
        rows.append(evaluate(f"window_{size}", lambda s=size: SlidingWindow(s)))
    for i in range(80, 99):
        d = i / 100.0
        rows.append(evaluate(f"decay_{d:.2f}", lambda x=d: DecayedCounts(x)))

    pareto = [row for row in rows if not any(dominates(other, row) for other in rows if other is not row)]
    pareto.sort(key=lambda r: (r["mean_switches"], r["degrade_failures_to_rel_below_0.95"], r["recovery_successes_to_rel_0.95"]))

    OUT.mkdir(parents=True, exist_ok=True)
    with (OUT / "estimator_pareto_all.csv").open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=rows[0].keys()); w.writeheader(); w.writerows(rows)
    with (OUT / "estimator_pareto_front.csv").open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=pareto[0].keys()); w.writeheader(); w.writerows(pareto)

    summary = {
        "evidence_boundary": "SYNTHETIC_RESEARCH_ONLY",
        "objective": "minimize degradation detection delay, near-equal-link route churn, and recovery delay",
        "pareto_front": pareto,
        "warning": "Pareto membership is specific to these synthetic traces/margin/dwell assumptions; hardware directional traces must calibrate final constants.",
    }
    (OUT / "estimator_pareto_summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
