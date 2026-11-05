# Reproducible current coverage selection

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
eight **entire context objects**, and the 35 repository files referenced by those
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
default activation or release qualification. The independently observed selection
has two implemented cells, zero tested cells and zero qualified cells; that
strict-reporter observation does not claim this wrapper was exercised and is not
an entitlement to retain those counts if inputs change. The
complete release matrix, per-rule six-kind tests and qualification gaps remain.
Do not edit hashes, exclude records, drop contexts or select archival sidecars to
make a failing report pass. A genuine evidence successor requires explicit review
of its full inputs, context identity and historical disposition.
