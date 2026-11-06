# Explicit coverage accounting contracts

## 1665 successor: declared obligations, current evidence unavailable

The explicit catalog successor is
`ktchecker/owner-full-catalog/1665-u2/1`: all 1,663 predecessor IDs plus
`ktc.compiler.common.error_suppression` and
`ktc.compiler.common.inapplicable_operator_modifier`. The former is planned and
advisory; the latter is planned and enabled only when independently validated.
Neither catalog metadata nor a compiler mapping enables a runtime default.

```sh
python3 -I -B -S scripts/run_declared_coverage.py \
  --output build/declared-coverage-1665-v1/inventory.json
```

This is a separate **declared ledger**, not a new successful current-evidence
inventory. Exit zero plus `ledger_valid: true` means only that its pinned
SOURCE/DATA declaration was checked. `inventory_valid`,
`current_coverage_known`, and all qualification flags remain false;
current-stage totals remain **null/unknown, not measured zero**.

`current-inputs-1665-v1.json` pins four catalogs, the exact same eight complete
context objects, a per-record baseline, the unchanged visibility reporter, and
explicit bookkeeping metadata. The canonical sorted-ID JSON digest is
`121660fc2a5aed084688d0c549f9e1bece2ec514e9ace4748211b55fa87cc1c5`.
The old 1,663 record identities are also checked, not just the new count.
All **1,665 × 8 = 13,320 obligations** remain visible and current evidence for
every row is unavailable. The 46 existing catalog exclusions remain visible in
all eight contexts (368 cells); they do not remove obligations or authorize new
rule disabling. No Android, iOS, Desktop, source-set, version, or native context
is dropped or re-labelled. The copied context objects are declarations, not new
execution receipts or a complete required release matrix.

The runner calls only the unchanged reporter's `catalog_visibility` function,
with explicitly empty evidence and the eight entire contexts. It never calls the
strict evidence generator, loads a mapping for stage credit, scans mapping
references, discovers envelopes, or reads a historical report, provider result,
raw log, archived source, binary, or execution artifact. The previous six maps,
five selected envelopes and 47 reference identities remain explicit metadata in
`legacy-evidence-1665-v1.json`; none of their target files is opened, statted or
hashed by this runner. The three unapproved archived Android JSON references stay
listed as denied, not read-to-classify, silently dropped, or re-pinned.

`withdrawals.json` remains byte-for-byte unchanged: **450** exact old provider /
rule / complete-context identities, including 443 compiler cells. The two new
catalog IDs never had those historical claims, so they do not fabricate two new
withdrawals. The original historical claims and gaps remain separately visible,
with no implementation, testing, qualification or activation transfer.

`historical-inventory-1663-c364.json` preserves **owner-supplied metadata only**:
old report digest
`26a0aeb9875a1ceeb34f873da230805e8dc94d4dacceb98d58aa5dd11ce65f98`,
1,663 × 8 cells, mapped 11,448 / implemented 2 / tested 0 / qualified 0, and
runtime defaults false. Its full source digest and the two implemented cell
identities are unknown/null. The abbreviated `c364` label is not a verified
source identity. Its status is `source_reference_unavailable`; this work does not
re-read or revalidate that report, and new bookkeeping tests cannot validate it.

Outputs are confined to `build/declared-coverage-1665-v1/`, so this command cannot
overwrite the old inventory or the old wrapper's default output. Pending and
failed ledgers contain no previous scope or counts. Publication is atomic, with
a 128 MiB serialized-file limit (not a process-memory limit). Before/after input
pins bind only the explicitly selected files; whole-worktree execution binding
requires a separately observed source/output receipt. Code availability or passing
bookkeeping tests is not evidence that the full ledger was actually generated.

## Frozen 1663 predecessor: reproduction contract, not current admission

`current-inputs.json`, the old strict runner/reporter, the eight contexts,
withdrawals and historical inventories remain unchanged. Current regeneration
through this old selection is **unavailable**: it requires archived Android JSON
whose read permission was not granted. Do not execute it as a workaround, follow
those paths to classify their contents, or drop references to force success.
After the catalog successor is adopted, its old catalog pins also reject the
changed current catalogs. It is not an alternate path accepting a 1,663-rule
"current" inventory.

The following describes the retained predecessor interface, **not permission to
run it against restricted inputs or a current-evidence claim**.

Run from a checked-out repository with Python 3.11 or later:

```sh
python3 -I -B -S scripts/run_current_coverage.py --output build/reports/rule-coverage.json
```

The script locates its repository independently of the caller's working directory.
There is no alternate-root or custom-selection CLI override. All input and output
paths are relative to the repository containing this script. Outputs must
be JSON files below its ignored `build/` directory. Use a quiet checkout and distinct
output paths for concurrent attempts. Check **both exit status 0 and
`inventory_valid: true`**; the file alone is not an execution receipt.

`current-inputs.json` fixes four catalogs, six maps, five evidence sidecars, all
eight **entire context objects**, and the 47 repository files referenced by those
maps/sidecars. It also pins the unchanged strict reporter and `withdrawals.json`.
There is no fallback to provider/evidence autodiscovery. The reporter's catalog
discovery is checked against the four explicit catalog paths. Added catalogs,
missing files, changed hashes, duplicate identities, symlink inputs and incomplete
reference closure fail rather than reducing scope or refreshing pins.

The selected inputs require no new archive, binary, provider execution, Gradle
invocation, network access or private temporary directory. Existing hash-referenced
historical artifacts and the existing wrapper JAR are read as bytes only. Context
`run` labels remain original repository-relative metadata, not paths that authorize
or require a new run. Generated reports are deliberately not checked in.

## Preserved scope and withdrawn historical evidence

Every one of the **1,663 catalog IDs remains in each of eight contexts**: 13,304
cells, including the existing exclusions and Android/iOS/Desktop applicability.
The eight files are byte-for-byte copies of the already approved context metadata;
copying them does not renew their evidence.

`withdrawals.json` preserves 450 exact provider/rule/context identities: 443 Kotlin
compiler cells, three Detekt syntax cells and four Ktlint syntax cells. Their
historical claims and original envelope hashes remain recorded, but no current
implementation/test/qualification stages transfer. The wrapper verifies each
withdrawn provider remains present with zero current stages and no selected
sidecar. This is not rule disabling, catalog exclusion or a smaller release scope.

The compiler envelope's original archive hash `1b708c156349a507ddaf5c1ad347bcad59a89b45b584069f63f3f0f0c435f81d`
differs from the older integration-checkout variant `e7d3acf0fbd4d5aa322893a1c8da49124767a41fdabc75fa201dce1c2eb1de8e`.
Neither is selected. Their shared relative filename is not proof of identical
contents. No historical envelope is overwritten or copied by this selection, and
the older integration variant adds **no ninth active context**. Public provenance
contains relative filenames and body identities, not private checkout locations.

## Failure and qualification boundaries

After selecting a safe output path, the wrapper atomically publishes a pending
invalid inventory **before input preflight**. It invokes the unchanged strict
`generate_coverage` function once with explicit selections, reconciles the complete
row/context/catalog identities and all 450 withdrawals, then rechecks the complete
input closure before atomically publishing success. Any preflight, reporter or
reconciliation error returns nonzero and leaves an invalid inventory, not old
successful counts. Invalid inventories have unknown counts; they are not zero
coverage claims. Unsafe/unwritable output paths cannot be repaired by this script;
a nonzero exit always disqualifies an existing file as evidence for that attempt.

Coverage accounting is **not** provider execution, current rule testing, runtime
default activation or release qualification. Historical metadata reported two
implemented cells, zero tested cells and zero qualified cells; the separately
recorded unknown full-source binding and cell identities above remain unknown.
Those old counts do not claim that this wrapper was exercised and are not an
entitlement to retain them if inputs change. The
complete release matrix, per-rule six-kind tests and qualification gaps remain.
Do not edit hashes, exclude records, drop contexts or select archival sidecars to
make a failing report pass. A genuine evidence successor requires explicit review
of its full inputs, context identity and historical disposition.
