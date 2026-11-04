# Third-party attribution for this planning repository

The rule catalogs and generated indexes contain **extracted or adapted upstream
diagnostic descriptions, identifiers and small example excerpts**, alongside new
ktChecker applicability, classification and validation-planning metadata. Sources
are linked per record. Those transformations are ktChecker planning work, not
upstream changes or endorsements. No analyzer binaries or complete provider
implementations are distributed here.

These notices apply to the incorporated upstream material **now**, not only to a
future executable release. They do not choose a license for the owner's original
ktChecker work. Public availability alone does not grant an open-source license
for that original work. Future distributions need a separate complete dependency,
license, NOTICE, SDK-redistribution and SBOM review.

| Source and researched version | Incorporated material / attribution | Applicable notice |
|---|---|---|
| [Kotlin 2.4.21](https://github.com/JetBrains/kotlin/tree/v2.4.21) | Compiler diagnostic identities/messages and contract references; Copyright 2010–2024 JetBrains s.r.o. and Kotlin Programming Language contributors / respective authors and developers | [Apache 2.0](third_party/licenses/Apache-2.0.txt), [upstream NOTICE](third_party/licenses/Kotlin-NOTICE.txt), [upstream copyright](third_party/licenses/Kotlin-COPYRIGHT.txt) |
| [Detekt 2.0.0-alpha.6](https://github.com/detekt/detekt/tree/v2.0.0-alpha.6) | Rule descriptions, source-documentation examples and test-case references; attribution to the Detekt authors and contributors | [Apache 2.0](third_party/licenses/Apache-2.0.txt); [pinned upstream license](https://github.com/detekt/detekt/blob/v2.0.0-alpha.6/LICENSE) |
| [Ktlint 1.8.0](https://github.com/ktlint/ktlint/tree/1.8.0) | Rule identifiers, descriptions/examples and test references; Copyright 2019 Pinterest, Inc.; Copyright 2016–2019 Stanley Shyiko | [Unmodified upstream MIT license and notices](third_party/licenses/Ktlint-MIT.txt) |
| AOSP Android Lint, versioned source artifacts listed in the platform catalog | Diagnostic descriptors and rationale; The Android Open Source Project, with source-file copyright years varying by file. The published Lint source NOTICE identifies Copyright (c) 2011, The Android Open Source Project | [Published Lint source NOTICE including Apache 2.0 terms](third_party/licenses/AOSP-Lint-NOTICE.txt) |
| AndroidX Compose, Lifecycle, Navigation and other AndroidX providers, exact versions in the platform catalog | Diagnostic descriptors/rationale; The Android Open Source Project, with copyright years retained in linked source artifacts. Inspected source artifacts carry Apache 2.0 license files and file-level notices | [Apache 2.0](third_party/licenses/Apache-2.0.txt); per-artifact source URLs in [platform records](data/rules-platform.json) |
| [Compose Rules 0.6.8](https://github.com/mrmans0n/compose-rules/tree/v0.6.8) | Rule-contract references and adapted descriptions; upstream README credits Copyright 2022 Twitter, Inc. and Copyright 2026 Nacho Lopez; individual sources also credit Nacho Lopez in their respective years | [Apache 2.0](third_party/licenses/Apache-2.0.txt); [upstream license](https://github.com/mrmans0n/compose-rules/blob/v0.6.8/LICENSE.md) |

The retained license/NOTICE files are copied without modifying their terms. The
Apache text is from Kotlin's pinned `license/LICENSE.txt`; the Kotlin notices and
Ktlint license are from the pinned repositories above. The AOSP notice is extracted
from the published
[`lint-checks-32.5.0-sources.jar`](https://dl.google.com/dl/android/maven2/com/android/tools/lint/lint-checks/32.5.0/lint-checks-32.5.0-sources.jar).
Detekt's pinned root license was also fetched and matches the researched Apache
license; no root NOTICE was present in its fetched tree. This is not an assertion
that every transitive dependency has the same license.

Kotlin's [license-scope explanation](https://github.com/JetBrains/kotlin/blob/v2.4.21/license/README.md)
documents third-party exceptions inside its repository. Those complete components
are not bundled by this plan; a future compiler distribution must preserve their
applicable terms. Android/Apple SDKs and downloaded toolchains have separate terms;
an Apache license on a diagnostic source does not authorize all SDK redistribution.

Other library/API statements are attributed research and proposed checks, not
bundled implementations. Follow their per-rule primary-source links and the
[research evidence map](docs/research/README.md) for exact provenance, version
limitations and implementation-license checks still required.
