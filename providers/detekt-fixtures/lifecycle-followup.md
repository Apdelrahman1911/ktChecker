# Ordinary native fixture lifecycle follow-up

This successor is source authoring only. Compilation, the 30 authored synthetic
control methods per provider (15 retained/updated and 15 additional methods),
and genuine ordinary Detekt/Ktlint reruns have not been performed for these
bytes. The earlier eight-path inventory was not source-cleared; its packet and
all previous execution evidence remain unchanged. No tested or qualification
promotion is claimed.

The ordinary integration classes use the same test-only lifecycle adapter that
the controls exercise. BeforeEach allocates only an in-memory method identity;
the existing opt-in assumptions still precede filesystem/native setup. Thread
ownership binds on first execution access, not instance discovery. SAME_THREAD
JUnit execution and owner/reentry/late/duplicate checks stop uncertain admission.
Foreign or reentrant callbacks cannot start evidence I/O. This is not a new
native timeout, kill, cross-JVM reconciliation, retry or cleanup policy.

Each method owns an immutable job/run binding before fallible new-job setup.
A partial second start, artifact failure or JUnit callback cannot fall back to
the first method's path or summary. Created run/scratch/artifact directories are
private `0700`. Partial initialization records separately whether each directory
was created/verified; planned and last-known paths are not proof those paths
currently exist. No automatic removal or permission restoration occurs.

Initial directory provisioning still needs a trusted/cooperative namespace:
Java SecureDirectoryStream does not provide mkdir-at. Subsequent evidence writes
use a verified directory handle and CREATE_NEW, with no nested mkdir or target
replacement. Logical START/artifact names map to bounded, digest-indexed flat
filenames in the job's evidence-artifacts directory; references retain the
logical name and exact-byte digest. A collision fails closed. This does not
claim adversarial namespace/ABA containment or replace existing snapshot/runtime
scratch APIs. On custody loss or an evidence write/close/postcheck failure,
admission stops and watchers never reopen the rejected path. Retention is a
no-cleanup policy; custody and location are explicitly last-known observations.

Runtime callbacks alone do not release admission. The existing full native,
exact pre-setup denial and no-callback abstention checks are preserved, including
valid nonzero Ktlint exits. Terminal counters and method completion commit only
after an immutable prospective checkpoint is written, closed and postvalidated.
A surviving prospective record is still a candidate, not proof of that commit,
native PID closure, JUnit success or successful build completion.

**Publication handoff changed:** tests never write or delete live verified
sidecars, including on failure, abort or AfterAll. Only bounded per-run
`sidecar-candidate-*.json` wrappers are retained; both wrapper and rule payload
keep `tested=false` and `qualified=false`. Completion files are append-only
method observations (`completion-started-unfinished.json`,
`completion-method-assertions-checked.json`, and
`completion-junit-method-success-observed.json`), not mutable completion.json or
suite/build certificates. Watcher checkpoint failures keep the class incomplete
and are surfaced by AfterAll without more path I/O. A separately authorized root
post-build publisher/collector must verify the exact run, lifecycle, JUnit,
build and generation tuple. That publisher and downstream compatibility are
not implemented or verified by this source packet.

All historical public receipts, archives, native sidecars and guards remain
unchanged. These source changes invalidate old current-source attribution until
a separately authorized real rerun yields a fresh attributable tuple. Do not
renew old guards, borrow old executions, or treat a new JVM as cleanup proof.
The owner must inspect retained bytes and settle actual native quiescence before
manual cleanup. The separate bounded corpus/build/compiler work is untouched.
