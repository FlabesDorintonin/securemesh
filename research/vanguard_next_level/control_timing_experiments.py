#!/usr/bin/env python3
"""Model the discovery deadline vs deferred-control admission contract.

CURRENT models the v1.0.4 behaviour where the deadline starts in beginDiscovery.
CANDIDATE models Queued -> Emitted -> Waiting, where timeout/count starts only
when the control packet is actually admitted/emitted.
"""
from __future__ import annotations

import csv
import json
from pathlib import Path

BASE_DISCOVERY_TIMEOUT_MS = 9537
DEFERRED_CONTROL_MAX_AGE_MS = 12000
OUT = Path(__file__).resolve().parent / "results"


def current_retry_before_first_emit(queue_delay_ms: int) -> bool:
    return queue_delay_ms >= BASE_DISCOVERY_TIMEOUT_MS


def candidate_retry_before_first_emit(queue_delay_ms: int) -> bool:
    # While Queued there is no Waiting deadline and no consumed emitted attempt.
    return False


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    rows = []
    for delay in range(0, DEFERRED_CONTROL_MAX_AGE_MS + 1, 100):
        rows.append({
            "queue_delay_ms": delay,
            "current_retry_before_emit": current_retry_before_first_emit(delay),
            "candidate_retry_before_emit": candidate_retry_before_first_emit(delay),
        })

    vulnerable = [r for r in rows if r["current_retry_before_emit"]]
    candidate_vulnerable = [r for r in rows if r["candidate_retry_before_emit"]]
    assert vulnerable, "Expected current timing model to expose a retry-before-emit window"
    assert not candidate_vulnerable, "Emission-aware candidate must remove retry-before-emit by construction"

    summary = {
        "evidence_boundary": "MODEL_RESEARCH_ONLY",
        "base_discovery_timeout_ms": BASE_DISCOVERY_TIMEOUT_MS,
        "deferred_control_max_age_ms": DEFERRED_CONTROL_MAX_AGE_MS,
        "race_window_ms": DEFERRED_CONTROL_MAX_AGE_MS - BASE_DISCOVERY_TIMEOUT_MS,
        "current_first_vulnerable_delay_ms": min(r["queue_delay_ms"] for r in vulnerable),
        "candidate_retry_before_emit_cases": len(candidate_vulnerable),
        "candidate_state_machine": ["Queued", "Emitted", "Waiting"],
        "required_contract": "attempt counter and deadline begin on confirmed control emission, not enqueue",
    }
    (OUT / "control_timing_summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    with (OUT / "control_timing_sweep.csv").open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=rows[0].keys())
        writer.writeheader(); writer.writerows(rows)
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
