#!/usr/bin/env python3
"""Derive latency only from exact, same-condition input/presentation pairs."""

import argparse
import json
from collections import defaultdict
from pathlib import Path


def underivable(reason: str) -> int:
    print(json.dumps({"status": "UNDERIVABLE", "reason": reason}))
    return 2


def identity_key(event: dict) -> str:
    identity = event.get("identity")
    if not isinstance(identity, dict) or not identity:
        raise ValueError("event has no non-empty identity")
    return json.dumps(identity, sort_keys=True, separators=(",", ":"))


def derive(events_path: Path) -> int:
    pairs: dict[str, dict[str, dict]] = defaultdict(dict)
    try:
        for line_number, line in enumerate(events_path.read_text().splitlines(), 1):
            if not line.strip():
                continue
            event = json.loads(line)
            if not isinstance(event, dict):
                return underivable(f"line {line_number} is not an event object")
            kind = event.get("kind")
            if kind not in {"input", "presentation"}:
                return underivable(f"line {line_number} has unsupported kind")
            if not isinstance(event.get("condition"), str) or not event["condition"]:
                return underivable(f"line {line_number} has no condition identity")
            timestamp = event.get("monotonic_ns")
            if not isinstance(timestamp, int):
                return underivable(f"line {line_number} has no integer monotonic_ns")
            key = identity_key(event)
            if kind in pairs[key]:
                return underivable(f"duplicate {kind} for identity {key}")
            pairs[key][kind] = event
    except (OSError, ValueError, json.JSONDecodeError) as error:
        return underivable(str(error))

    if not pairs:
        return underivable("no events")

    samples = []
    for key, pair in pairs.items():
        if set(pair) != {"input", "presentation"}:
            return underivable(f"missing paired event for identity {key}")
        input_event, presentation_event = pair["input"], pair["presentation"]
        if input_event["condition"] != presentation_event["condition"]:
            return underivable(f"condition mismatch for identity {key}")
        if presentation_event["monotonic_ns"] < input_event["monotonic_ns"]:
            return underivable(f"presentation precedes input for identity {key}")
        samples.append(
            {
                "identity": input_event["identity"],
                "condition": input_event["condition"],
                "latency_ms": (presentation_event["monotonic_ns"] - input_event["monotonic_ns"])
                / 1_000_000,
            }
        )
    print(json.dumps({"status": "DERIVED", "samples": samples}, sort_keys=True))
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--events", type=Path)
    source.add_argument("--perfetto", type=Path)
    args = parser.parse_args()
    if args.perfetto:
        return underivable(
            "Perfetto slice timing has no shared input/presentation identity; "
            "nearest-next pairing is prohibited"
        )
    return derive(args.events)


if __name__ == "__main__":
    raise SystemExit(main())
