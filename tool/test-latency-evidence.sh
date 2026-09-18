#!/usr/bin/env bash
set -euo pipefail

repo=$(cd "$(dirname "$0")/.." && pwd)
verifier="$repo/tool/verify-latency-evidence.py"
fixtures="$repo/tool/testdata/latency-evidence"

aligned=$("$verifier" --events "$fixtures/aligned.jsonl")
[[ "$aligned" == *'"status": "DERIVED"'* ]]

set +e
missing=$("$verifier" --events "$fixtures/missing-presentation.jsonl")
missing_status=$?
set -e
[[ "$missing_status" == 2 ]]
[[ "$missing" == *'"status": "UNDERIVABLE"'* ]]
