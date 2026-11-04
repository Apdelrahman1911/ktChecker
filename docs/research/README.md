# Research evidence map

Research was performed against primary documentation, maintained rule catalogs,
release metadata and, where relevant, published provider/SDK source on **2026-11-04**.
The date is an access/research baseline, not a claim that every upstream webpage is
immutable or every latest release has been tested together.

| Evidence | Questions answered |
|---|---|
| [Core rule research](core-rules.md) | Kotlin compiler/Analysis API, Detekt and formatting catalogs; versions, implementation paths, limitations and licensing |
| [Core library research](libraries-core.md) | Coroutine/Flow, serialization/time, network/persistence/DI/image/test API contracts and platform/version boundaries |
| [Platform/build research](platform-build.md) | Atomic Lint/provider registries, Compose/Android/native/build source applicability and integration feasibility |
| [Extended ecosystem research](ecosystem-extensions.md) | Additional commonly used ecosystems, alternatives, overlap and justified exclusions |
| [Declared-policy provider alternatives](policy-provider-alternatives.md) | Konsist, ArchUnit and diKTat: source-policy versus JVM-bytecode scope, licenses, maintenance, execution trust and unexpanded mappings |
| [Engine sources](engine-sources.md) | Kotlin/Gradle/provider/project-model capabilities, compiler compatibility, soundness and target execution restrictions |
| [MCP and agent hosts](mcp-hosts.md) | Current/legacy protocol differences, SDK implementation evidence, tasks and real host delivery capabilities |
| [Validation and compatibility sources](../validation-ci.md) | Kotlin/Gradle/AGP/JDK/Xcode/runner tuples and evaluation methodology |
| [Lead source checks](../../data/research-source-checks.json) | Independent fetched-byte hashes for selected consequential protocol/CI sources |

## Evidence labels

- **Fetched fact:** a specific primary page/artifact/source supports a statement.
  An upstream assertion remains upstream evidence, not a ktChecker test result.
- **Recommendation/design:** a reasoned choice or proposed contract in this plan.
- **Candidate supported version:** a version to qualify in implementation CI; not
  yet supported merely because it exists, resolves, or lies in an upstream range.
- **Unproven target:** latency, resource, reliability or operational goals requiring
  measurements on declared workloads and hardware.
- **Gap/unavailable:** missing evidence, unexpanded provider catalog, incompatible
  version, unsupported target/host, failed fetch or unexecuted experiment.

Mutable documentation sometimes redirects to a newer product line. The research
records distinguish those redirects from pinned source/artifact releases. Never use
a 'latest' landing page as evidence that a rule applies to an older dependency.
Where an official registry was enumerated, the enumeration validates **catalog
membership and metadata only**, not detection correctness, ktChecker integration,
platform compatibility or false-positive rate.

Release implementation must retain a lock of artifact coordinates, checksums, source
commits/licenses, enabled diagnostic IDs, validation runs and compatibility contexts.
Network fetches used for research are not required on every review. Optional updates
must not change an active review's rule/version identity midway through execution.

## Source use and public sharing

This repository contains planning text, rule metadata, links and small attributed
upstream description/example excerpts; it does not include private customer source
or raw terminal transcripts. See [third-party notices](../../THIRD_PARTY_NOTICES.md)
and the retained license texts for that incorporated material. No provider binaries
or complete analyzer implementations are bundled. Selecting the original project's
software license and verifying all redistribution/notice obligations for future
implementation dependencies are explicit release tasks; a public repository alone
is not an open-source license.
