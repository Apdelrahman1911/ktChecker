# Semantic-fact M0 fixtures

Original fixtures for `:semantic-facts:semanticFixtures`; source is copied into the
integrated immutable snapshot store before any compiler invocation. All source-set
assignments and refinement edges are explicit driver data, not inferred activation.

| Case | Actual compiler behavior required |
|---|---|
| Binary dependency | Produce a separate JAR or target KLIB from `dependency/Library.kt` |
| Positive | Resolve overloads, aliases, local shadowing, generic dependency types; common→intermediate→target expect/actual and typealias matches |
| Unresolved | Source diagnostic and explicit unknown/unvisited scope, no invented function target |
| Malformed | Source syntax error remains incomplete despite any partial facts |
| Missing dependency | Removing selected JAR/KLIB makes imports/calls unresolved, never reusable success |

`Coordinates.kt` intentionally retains CRLF and UTF-8 supplementary characters; its
`.gitattributes` entry disables line-ending conversion. The Kotlin sources are not
textual fake-semantics fixtures: assertions inspect emitted compiler-resolved IDs,
type structures, matching attributes, compiler contexts and exact source bytes.

No fixture result qualifies a catalog rule. The Native iOS cells remain unverified
until the selected Apple runner actually executes and retains its evidence. A local
JVM pass does not fill those cells. Refer to the module README for exact commands,
selected toolchains and known limitations.
