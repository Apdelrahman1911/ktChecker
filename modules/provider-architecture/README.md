# Declared architecture graph policies

Rules run only with an exact, single-use authenticated owner policy capability bound
to a verified project model, namespace, snapshot and configuration. Project JSON,
package names, imports and customary layering conventions cannot activate a policy.

Implemented graph inputs are actual Gradle module dependencies and source-set
`dependsOn` refinement. The owner explicitly selects a closed compilation scope,
exact actual-identity-to-owner-node mapping, direct or transitive traversal,
mapped-self-edge treatment, allowed/forbidden edges and exact SCC exceptions.

**These are owner-node quotient graph policies.** Actual edge endpoints are mapped
before transitive closure or SCC detection. If `A1` and `A2` are both mapped to `A`,
actual edges `B -> A1` and `A2 -> C` form the owner-node path `B -> A -> C`, even
without a connected actual-module path. An owner-node cycle likewise need not be
an actual-module cycle. Witness evidence retains actual origins for each quotient
hop and explicitly states this precision limit. Use a one-to-one mapping when this
identity consolidation is not the desired contract. Graph size and cumulative
transitive witness storage are bounded, and traversal polls cancellation.
Rule finding batches are also bounded (1,024 findings and 8 MiB of serialized
finding evidence); exceeding a bound abstains rather than silently truncating a
report or issuing negative coverage. Policy hashes and source anchors are indexed
once, not recomputed by rescanning the entire model for every edge.
Import validates a conservative 8 MiB JSON-text estimate before immutable copying;
policy conflict checks use hashed sets. Scope/graph text is bounded before
canonicalization, and finding construction reserves escape-safe witness (512 KiB),
diagnosis, context and location budgets before rendering or serialization. These
conservative text limits may reject graphs below the node/edge count ceilings.

Findings anchor captured build files at file start, not inferred dependency source
spans. Missing mappings, variants, producer closure or global graph gaps abstain.
Resolved-symbol graphs, effective API/export policies and declaration/binary
ownership policies remain explicitly unsupported; declared `public` visibility
and import spelling are not substitutes for those facts.

No runtime tuple is qualified. Positive findings retain implemented graph evidence;
negative results remain `unqualified`, never successful release coverage.

The first central unit run passed all 14 tests. Subsequent text-budget fixes and
three large-text regressions bring the current source to 17 tests; all passed a
fresh central run on 2026-11-04 at 10:22 UTC, with zero failures/errors/skips.
That historical result predates the test-only host-capability correction: fresh trusted temp
roots are canonicalized, and verified-model fixtures explicitly skip where anchored
no-follow model reads are unavailable. The current 17-test source passed a fresh
central rerun at 11:15 UTC, with zero failures/errors/skips, alongside model 37/37
and config 35/35. Exact XML/logs and the executable/test-source digest checkpoint
are retained in `build/evidence/model-native-start/20261104-first/`; subsequent
README-only status updates remain separate from that source freeze. The later
upstream model 47/47 run at 11:49 UTC is separately recorded under
`build/evidence/model-cli-composition/20261104-first/`; it is not another architecture
test run. The first actual exporter was confirmed but rejected for immutable-seed
mutation. The fresh-home actual exporter subsequently passed process/native/input
verification but remained unavailable at the exact compilation-domain guard, with
no imported model or certificate. Neither attempt proves architecture execution,
waives compilation obligations, or establishes Windows model/architecture support
or runtime qualification.
