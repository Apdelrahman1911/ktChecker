# Selected-wrapper checksum format contract

This additive provider implements a source-only, noncertifying observation for the existing
`ktc.gradle.wrapper_declared_checksum_format` catalog ID. It neither launches Gradle nor downloads,
loads, or executes a wrapper, distribution, plugin, or project. Root-owned settings/CLI wiring and
real consumer fixtures are separate work.

## Owner API

`GradleWrapperChecksumProvider` consumes a `WrapperContextAdmission`. Its default admission is
unavailable, with an explicit `wrapper_selected_consumer_attestation_unavailable` reason for the
requested ID. No request JSON, policy string, file name, URL suffix, or claimed version can install
a context.

The nonserialized `InstalledWrapperContext.install` factory requires all of:

* Independent verification of the registered immutable owner snapshot and exact analysis context.
* A byte reader for the two explicitly selected captured files; both size and SHA-256 are verified
  on private byte copies. The provider never reads `materializedRoot` or a mutable checkout.
* An independent owner receipt proving that the actual selected wrapper entrypoint consumes those
  properties with the exact pinned wrapper JAR, and proving the 9.6.1 wrapper/distribution version
  provenance. A distribution URL basename is **not** version or artifact provenance.
* An independent, exact owner receipt for the selected fresh/unverified required-comparison path,
  or for a cached path without comparison. Unknown cache/path facts remain unavailable. The owner
  must check registered immutable/fenced plan evidence, not echo a project-provided enum.
* An independent receipt for the entire exact enable/suppression policy, including an empty list.

The factory returns a typed installed context or a fixed unavailable reason. There is no JSON
decoder or public context constructor. Contexts are single-use, bind the entire frozen input,
and reverify the owner receipts before analysis; changed/revoked path facts cannot silently retain
a fresh-path assertion. This cooperative host API is not a same-UID sandbox security boundary.
Mandatory callbacks in synthetic tests are fixtures, never production authority or qualification.

## Independently checked consumer slice

The only consumer profile is the exact repository wrapper JAR with SHA-256
`7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d`.
The module checks that artifact and the inspected `Install`/`WrapperExecutor` class hashes, without
class loading. An offline class-file inspection records `Properties.load(InputStream)`, untrimmed
`getProperty`, lowercase padded SHA-256 rendering, and case-sensitive `String.equals` comparison.
This profile does not claim that every wrapper with a 9.6.1 URL is identical, or that any other
version is supported. Actual selected version/provenance still requires the owner receipt.

Parsing uses JDK `java.util.Properties.load(InputStream)` on bounded captured bytes, preserving
ISO-8859-1, Unicode escapes, continuation, escaped keys, separators, whitespace and last-key-wins
semantics. There is no handwritten approximation. An invalid parser input abstains. The location
is deliberately file-level: `Properties` does not expose the final escaped/continued key span.

Only a declared parsed value outside the exact pinned consumer's lowercase 64-ASCII-hex range,
on an attested required comparison path, produces a format finding. Absence is a separate optional
policy; cached-without-comparison does not produce a runtime failure. A shape-valid but wrong
digest remains an unverified integrity issue outside this format predicate. Unknown wrapper,
version, parser, platform, model, selection or path facts produce explicit unavailable reasons.
Distribution URLs, credentials, checksum values, excerpts, policy justifications, and exception
text are never included in findings or failure reasons. No downloads occur.

Exact-ID owner disable/suppression is supported. A suppression is bound to the selected properties
file and complete `RuleContext`, requires a justification, and cannot be a family wildcard or a
sibling rule's suppression. Cross-context entries are not silently applied. Arbitrary project
`AnalysisContext.policies` strings are not policy authority.

## Evidence limits

All results remain partial, with null certificates and all implemented/tested/qualified coverage
stage claims false. Positive findings and accepted-shape/nonapplicability observations do not
discharge a required release plan. The resource mapping is metadata, not a stage evidence sidecar.
Authored tests cover useful detection, parser boundaries, cache/version/platform applicability,
immutable binding, owner receipts, cancellation, suppression and sibling/context isolation.
They have not been executed by the authoring agent. Real controlled execution of the selected
wrapper consumer remains required before qualification; portable tests do not prove downloads,
distribution integrity, Apple execution or cross-platform release support.

Provider-fixture controls derive their inert consumer-identity fixture from the
already-tracked `gradle/wrapper/gradle-wrapper.jar` through `processTestResources`;
no additional binary needs to be committed. Both the input and copied resource
have bounded reads and the exact size/SHA-256 pin checked before use. The old
duplicate `src/test/resources/fixtures/gradle-wrapper-7a9ce74c.jar`, if present in a
local checkout, is excluded by its exact resource path without deletion. Other
test resources and the embedded `META-INF/LICENSE` remain intact. Those
provider-fixture controls do not load or execute wrapper classes. Source-wiring
controls are synthetic checks, not proof of an actual Gradle run.

Separately, the eight `WrapperChecksumConsumerIntegrationTest` methods, when run,
compare provider observations with the pinned wrapper's
`Install.verifyDownloadChecksum(String, File, String)` API. Each loads only a
hash-checked, owned temporary copy of the resource with an isolated,
platform-parent class loader and calls that API once on a tiny owned target (or
an absent target for the null-checksum case). They do not invoke a wrapper
entrypoint, install Gradle, execute a project, or download a distribution. These
controls do not establish selected-wrapper/version provenance or release
qualification; the owner receipts above remain required.
# Full activation-policy preservation bound

The exact `AnalysisContext.policies["ktchecker.activation-plan"]` value is preserved
as opaque owner policy up to 1,048,576 UTF-8 bytes. Its byte count is checked without
allocating an encoded copy; every other policy value still has the existing
4,096-character limit. Library/environment maps do not get the activation exception.
The existing conservative aggregate 8 MiB JSON-envelope bound is retained before
input freezing, callbacks and serialization. No entry is stripped or narrowed.

The full-catalog unit resource contains all 1,663 actual catalog IDs in a synthetic
serialized activation plan. It tests preservation and exact changed-input binding,
not CLI installation, owner activation authority, detector execution or stage credit.
Independent owner snapshot, selected-consumer, path and exact-policy callbacks remain
mandatory. Default-unavailable routing and the fullProject owner boundary are unchanged.

Raw unpaired UTF-16 surrogates in the activation value are unavailable before owner
callbacks or input copies, avoiding replacement-encoding ambiguity in input digests.
Valid supplementary pairs remain supported. Literal ASCII JSON escape sequences are
preserved without decoding or inferring authority from the serialized policy.
