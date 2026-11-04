#!/usr/bin/env python3
"""Read-only structural checks of the single embedded protocol schema.

Usage:
  python scripts/validate_contract_examples.py docs/protocol.md

Install pinned planning dependencies from scripts/requirements-plan.txt.
Requires jsonschema==4.26.0. Does not execute ktChecker or any host. JSON
Schema `format` remains an annotation; authenticated receipts, time ordering,
identity equality, and other semantic invariants need implementation tests.
"""
from __future__ import annotations

import copy
import json
import re
import sys
from collections import Counter
from pathlib import Path

from jsonschema import Draft202012Validator


def require(condition, message):
    # Validation must not disappear under python -O / PYTHONOPTIMIZE.
    if not condition:
        raise ValueError(message)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON object key: {key}")
        result[key] = value
    return result


def invalid_constant(value):
    raise ValueError(f"non-JSON numeric constant: {value}")


def decode(value):
    return json.loads(
        value, object_pairs_hook=unique_object, parse_constant=invalid_constant
    )


path = Path(sys.argv[1] if len(sys.argv) > 1 else "docs/protocol.md")
document = path.read_text(encoding="utf-8")
fences = list(re.finditer(
    r"^```(json|ndjson)[ \t]*\n(.*?)^```[ \t]*$", document, re.M | re.S
))
require(fences, "no JSON fences found")
objects = []
for fence in fences:
    line = document[:fence.start()].count("\n") + 1
    texts = fence[2].splitlines() if fence[1] == "ndjson" else [fence[2]]
    for offset, value in enumerate(texts):
        if value.strip():
            objects.append((f"{path}:{line + 1 + offset}", decode(value)))

bundles = [obj for _, obj in objects if obj.get("$id") == "urn:ktchecker:contract:1"]
require(len(bundles) == 1, "expected exactly one embedded contract bundle")
bundle = bundles[0]
Draft202012Validator.check_schema(bundle)


def check_local_references(node):
    if isinstance(node, dict):
        if "$ref" in node:
            ref = node["$ref"]
            require(isinstance(ref, str) and ref.startswith("#/$defs/"), f"non-local schema reference: {ref}")
            require(ref[len("#/$defs/"):] in bundle["$defs"], f"missing definition: {ref}")
        for value in node.values():
            check_local_references(value)
    elif isinstance(node, list):
        for value in node:
            check_local_references(value)


check_local_references(bundle)
validators = {
    name: Draft202012Validator({
        "$schema": bundle["$schema"],
        "$id": bundle["$id"],
        "$defs": bundle["$defs"],
        "$ref": f"#/$defs/{name}",
    })
    for name in bundle["$defs"]
}

counts = Counter()
reviews = []
observations = []
protocol_envelopes = 0
for origin, obj in objects:
    if obj is bundle:
        continue
    if "jsonrpc" in obj:
        # Application schema intentionally does not validate MCP envelopes.
        protocol_envelopes += 1
        continue
    if "patchSequence" in obj and "idempotencyKey" in obj:
        name = "Submit"
    elif "findingId" in obj:
        name = "Finding"
    elif "reviewId" in obj and "coverage" in obj:
        name = "Review"
        reviews.append(obj)
    elif "consumerId" in obj and "receipt" in obj:
        name = "Ack"
    elif "observationId" in obj and "contextReceiptId" in obj:
        name = "Observe"
        observations.append(obj)
    else:
        raise AssertionError(f"unclassified application example at {origin}")
    errors = list(validators[name].iter_errors(obj))
    require(not errors, f"{origin}: {name}: " + "; ".join(e.message for e in errors))
    counts[name] += 1

require(all(counts[n] for n in ("Submit", "Finding", "Review", "Ack", "Observe")), f"Missing required fixture families: {counts}")
complete = next(obj for obj in reviews if obj["state"] == "complete")
negative_fixtures = []

bad = copy.deepcopy(complete)
bad["coverage"][0].update({
    "state": "unavailable",
    "reason": {"code": "sdk_missing", "message": "Synthetic negative fixture.",
               "retryable": True, "ownerAction": "Provision the SDK."},
    "provenance": {"mode": "notExecuted", "certificateId": None,
                   "sourceAnalysisId": None, "equivalenceProofId": None},
})
negative_fixtures.append(("Review", "required unavailable coverage marked complete", bad))

bad = copy.deepcopy(complete)
bad["coverage"][0]["provenance"]["certificateId"] = None
negative_fixtures.append(("Review", "completed coverage without certificate", bad))

bad = copy.deepcopy(complete)
bad["coverage"][0]["provenance"].update({
    "mode": "reusedWithProof", "sourceAnalysisId": complete["identity"]["analysisId"],
    "equivalenceProofId": None,
})
negative_fixtures.append(("Review", "reused coverage without equivalence proof", bad))

bad = copy.deepcopy(complete)
bad["outcome"] = "unknown"
negative_fixtures.append(("Review", "unknown outcome marked complete", bad))

bad = copy.deepcopy(complete)
bad["identity"]["analysisId"] = None
negative_fixtures.append(("Review", "unresolved analysis identity marked complete", bad))

# Structural negatives only: required references are not proof of their truth.
bad = copy.deepcopy(observations[0])
del bad["modelRequestId"]
negative_fixtures.append(("Observe", "observation without model-request reference", bad))

bad = copy.deepcopy(observations[0])
del bad["contextReceiptId"]
negative_fixtures.append(("Observe", "observation without insertion-receipt reference", bad))

bad = copy.deepcopy(observations[0])
bad["proofKind"] = "context_injected"
negative_fixtures.append(("Observe", "insertion-only proof kind used as observation", bad))

for schema_name, name, obj in negative_fixtures:
    require(not validators[schema_name].is_valid(obj), f"negative fixture accepted: {name}")
    print(f"REJECTED: {name}")

print(f"PASS: {len(fences)} JSON/NDJSON fences, {len(objects)} parsed objects; "
      f"Draft 2020-12 bundle with {len(bundle['$defs'])} definitions")
print(f"PASS: {sum(counts.values())} complete application fixtures: {dict(counts)}")
print(f"PASS: {len(negative_fixtures)} negative fixtures rejected")
print(f"NOTE: {protocol_envelopes} MCP envelopes syntax-checked only; "
      "no runtime/host/security/format validation")
