# ktChecker
Fast, evidence-based Kotlin Multiplatform code review for AI agents.

**Status: researched implementation plan, not a released checker.** Initial targets:
Android, iOS and Desktop/JVM. Research baseline: **2026-11-04**.

## Start here

**[Comprehensive product and implementation plan →](docs/PLAN.md)**

| Specification | Contents |
|---|---|
| [Architecture](docs/architecture.md) | Kotlin/JVM core, alternatives, workers, incremental analysis, snapshots, caching and security |
| [Repository and local installation design](docs/repository.md) | Proposed modules, dependency boundaries, packaging, per-user state and multiple-project use |
| [CLI, MCP and host delivery](docs/protocol.md) | Contracts, example findings, patch sequences, real background context delivery and recovery |
| [Rule inventory](docs/rules/README.md) | Atomic rule records, applicability, source/version evidence, limitations and per-rule validation requirements |
| [Testing, compatibility, CI and benchmarks](docs/validation-ci.md) | False-positive gates, realistic corpus, OS/target matrices and cold/warm measurements |
| [Primary-source research](docs/research/README.md) | Official documentation, maintained provider catalogs, observed versions and documented gaps |
| [Independent design audit](docs/review-audit.md) | Correctness challenges, remedies and remaining proof obligations |

### Non-negotiable contract

- Review **every completed agent patch**, including fixes; diagnose and advise, never edit source.
- Preserve **broad relevant launch coverage**, not a handful of starter rules.
- Require meaningful ktChecker tests for **every included rule**, including adopted rules.
- Make applicability, evidence, certainty, fixes and verification understandable to weak models.
- Keep snapshots/caches correct and incomplete, unavailable or stale work visible.
- Demonstrate background findings entering a supported agent's context—not just emitting an MCP notification.
- Treat **useful findings within 1–3 seconds on small warm patches as an unproven benchmark target**, not a speed claim.

The recommended direction is a **Kotlin/JVM engine and CLI with version-isolated
providers**, plus a thin replaceable TypeScript bridge where current MCP/host SDK
capabilities require it. No external LLM service is required for analysis.

### What has—and has not—been validated

The current workflow checks the planning registry, generated indexes, links,
synthetic application-schema examples and validator failure cases on Windows,
Linux and macOS. It does **not** build or test
a ktChecker engine, run Kotlin rules, establish accuracy or speed, or validate iOS
execution. All initial rules are explicitly **planned**. The implementation plan
defines the separate checker-portability and Android/Desktop/iOS release gates.

Run planning checks locally with Python 3.12:

```bash
python -m unittest discover -s scripts -p 'test_*.py' -v
python scripts/validate_plan.py
```

For the additional synthetic JSON Schema checks, install the hash-locked planning
dependencies in a virtual environment, then run:

```bash
python -m pip install --require-hashes -r scripts/requirements-plan.txt
python scripts/validate_contract_examples.py docs/protocol.md
```

Those examples check application data shapes and selected invalid combinations;
they do not exercise an MCP server, authentication or an agent host.

Upstream catalog descriptions/examples are attributed in
[third-party notices](THIRD_PARTY_NOTICES.md). The original project's software
license remains an explicit owner decision before implementation distribution.

After editing rule metadata, regenerate the human indexes with
`python scripts/validate_plan.py --write-catalogs`. Changes to the captured catalog
scope/dispositions also require an explicit baseline update and reason; do not
silently drop coverage to make a check pass.
