# ktChecker
Fast, evidence-based Kotlin Multiplatform code review for AI agents.

**Status: executable, unqualified development preview—not a completed release.**
The full planned Android, iOS and Desktop/JVM rule scope is unchanged. Research
baseline: **2026-11-04**. Missing implementations and qualification remain visible;
preview analysis cannot grant full-project completion.

## Build and try the preview

Requires **JDK 21**, Python 3.12 for the example, and network access during the
explicit build to resolve pinned dependencies. Project target fixtures separately
use JDK 25 and their Android/Xcode toolchains.

```bash
./gradlew --no-daemon --max-workers=1 test :cli:installDist
modules/cli/build/install/ktchecker/bin/ktchecker doctor --format json
python3 scripts/run_preview_example.py --output /tmp/ktchecker-example
```

On Windows use `gradlew.bat`, `bin\ktchecker.bat` and a **new** output directory.
The example creates synthetic immutable inputs and two independent clients. It
does not touch an existing project's source. It selects an experimental compiler
rule explicitly and reports incomplete/unqualified coverage honestly. See
[installation and actual commands](docs/implementation-usage.md),
[suppression controls](docs/suppressions.md), and
[MCP/host integration](docs/implementation-host.md).

The CLI is Kotlin/JVM. The separate MCP/host bridges require the pinned Node/npm
tooling in their package directories. Review itself does not require an LLM;
genuine model-delivery experiments are separate explicit host operations.

## Start here

**[Comprehensive product and implementation plan →](docs/PLAN.md)**

| Specification | Contents |
|---|---|
| [Architecture](docs/architecture.md) | Kotlin/JVM core, alternatives, workers, incremental analysis, snapshots, caching and security |
| [Implementation contract](docs/implementation-contract.md) | Concurrent clients, immutable capture, suppression authority, completion and unchanged release gates |
| [Repository and local installation design](docs/repository.md) | Baseline module design, dependency boundaries, packaging and per-user state |
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

Local tests now exercise the real SQLite kernel, snapshot store, configuration
controls, isolated provider runtime, and actual compiler/Detekt integrations.
These tests do **not** qualify the complete catalog or establish real-world
precision, incremental equivalence for arbitrary projects, Native semantic
coverage, or the warm 1–3-second objective. The catalog remains the release
requirements ledger; a separate machine-readable report records evidence:

```bash
python3 scripts/rule_coverage.py --output build/reports/rule-coverage.json
```

[Implementation evidence](docs/implementation-evidence.md) distinguishes observed,
unrun and failed checks. GitHub Actions has separate checker-host and actual-target
suites; a planning-only CI run is not implementation evidence. A rule-ID mapping
is not an implemented/tested/qualified detector. No default-qualified rule set has
been installed; preview execution requires explicit owner selection, and opt-in
rules are never silently enabled.

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
license and complete redistribution review remain explicit release decisions.

After editing rule metadata, regenerate the human indexes with
`python scripts/validate_plan.py --write-catalogs`. Changes to the captured catalog
scope/dispositions also require an explicit baseline update and reason; do not
silently drop coverage to make a check pass.
