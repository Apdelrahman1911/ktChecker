# Owned live transport, preview-2 — M0 normative contract (revision 2)

**Status:** implemented, unqualified transport preview with scoped local Linux
[execution/control evidence](../../../docs/implementation-evidence.md#current-checkpoint-2026-11-04);
real JVM byte-duplex passed. This is not compiler-application HELLO/semantic-worker
integration, host-model delivery, distribution, licensing, performance/SLO, ABI,
or release qualification.
The capitalized MUST/MUST NOT requirements below are normative. No preview-1
route, compiler/provider permission, scheduler, application protocol, or client
cancellation policy changes in M0.

## 1. Scope and evidence boundaries

This is a low-level, bounded duplex transport to one native-owned child
generation. It is not a finite batch adapter. A caller can receive child bytes
while the generation remains alive and send a later request on the same handle.
The native helper alone owns/signals/reaps its actual fork child and group.
Java MUST NOT derive a PGID, enumerate/rediscover processes, signal a PID/group,
destroy the helper, reconnect, replay, or substitute an executable from PATH.

There are three distinct evidence layers:

1. Valid native framing and `BOUND` associate this handle with its fresh nonce
   and exact installed image. They establish an **owned transport channel**,
   not successful child exec or worker readiness.
2. A future worker-application HELLO and job receipt, independently parsed and
   validated by a separately reviewed adapter, may establish application
   readiness/receipt. M0 supplies neither. `ACK(DATA)` means only kernel-pipe
   writes, never child reads, parsing, execution, completion, or job ownership.
3. An eventual, validated native `FINAL`, EOF, helper exit zero, image check,
   quiescence acknowledgement, closed pumps, and image cleanup establish
   generation closure. There is exactly one native FINAL per generation, never
   a fabricated per-job FINAL or `ProcessOutcome`.

Child STDOUT/STDERR observations are detached **provisional opaque bytes**.
Readiness-pipe CLOEXEC EOF, BOUND, ARM ACK, or INPUT ACK MUST NOT set an execution
or ready bit. Native `EXEC_CONFIRMED` exists only at FINAL, using preview-1's
conservative complete gate + actual normal non-126 leader-exit rule (§9).

This retains preview-1's trusted/cooperative provider boundary: no hostile
same-UID sandbox, group-escape protection, forced-helper-death guarantee,
CPU/RSS hard containment, or loaded-memory/OS-library attestation is claimed.
Durable restart reconciliation is not implemented; process-local quarantine
MUST NOT be represented as a durable cleanup proof. No warm compiler benefit,
compiler cleanup/isolation, semantic facts, latency or SLO follows from M0.

## 2. Names, platform, immutable launch configuration

| Item | Exact value |
|---|---|
| Installed protocol | `ktchecker-owned-child-supervisor/preview-2` |
| Native argv discriminator | `--ktc-owned-live-v2` |
| Helper-to-owner magic | ASCII `KTCLIV2\n` (8 bytes) |
| Owner-to-helper magic | ASCII `KTCCTL2\n` (8 bytes) |
| Installed resource prefix | `dev/ktchecker/providers/runtime/native-live/` |
| Manifest | prefix + `manifest` |
| Executable resource | prefix + `<target>/supervisor` |
| Initially supported targets | `linux-x86_64`, `linux-aarch64` only |

The v2 manifest is exactly four LF-terminated lines (five split elements):
protocol, target, `sha256:` + 64 lowercase source-digest hex digits, and either
`sha256:` + 64 lowercase binary-digest hex digits or `unavailable`. The manifest
limit is 512 bytes; the binary limit is 1,048,576 bytes. Source digest is a build
manifest claim; binary digest MUST be freshly checked. Manifest MUST be reread
after binary reading. Missing/unsupported/unavailable/mismatched v2 is
unavailable, never an invitation to fall back to v1. Non-Linux hosts explicitly
remain unavailable for v2; v1 platform behavior is unchanged.

Only the existing exact installed runtime code-source origin is allowed. The
closed resource-name allowlist may add only this manifest and these two target
binary names. No caller resource roots, context classloaders, PATH helpers,
environment overrides, or independent/untrusted digest authority are added.
Existing Coordinator/CodeImage identity already covers installed code/resources.
Private extraction/check/cleanup uses the existing verified image mechanism;
it MUST NOT make a verified image mutable or permit arbitrary origin selection.

Native invocation (exact ordering):

```
<private-image> --ktc-owned-live-v2 <remaining-generation-ms> <input-limit>
  <output-limit> <nonce-lowercase-hex32> -- <absolute-target> [target-args...]
```

The three numeric arguments use canonical positive ASCII decimal (no sign,
space, leading zero, overflow, or suffix). Nonce is 16 bytes, not all zero;
owner creates it freshly per generation (cryptographic random), passes its
32-character lowercase hex form in argv, and sends its raw bytes in the control
preamble. It is channel association, not a credential/security capability.
All arguments/configuration are immutable and detached before helper launch.
No shell is used. The following are **owner-side prelaunch** checks, as in v1:
target and cwd are absolute; at most 4096 target argv
entries and 4096 environment entries; each strict UTF-8 string at most 65,536
bytes and code units, no NUL, total argv/cwd/env string bytes plus terminators at
most 1,048,576. Environment names are nonempty and exclude `=`. Reject (do not
silently strip) exact POSIX prefixes `LD_`, `DYLD_` and keys `GLIBC_TUNABLES`,
`GCONV_PATH`, `LOCPATH`. Helper and child get this same exact environment.
The native helper cannot retroactively defend its own dynamic loader. Native
independently validates its exact discriminator/argument positions, canonical
numeric/nonce fields, an absolute target, at most 4096 target argv strings of
at most 65,536 bytes each with at most 1,048,576 aggregate argv bytes including
terminators, and all wire rules. It does not claim native UTF-8/code-unit or
cwd/environment aggregate/interposition validation after process launch.

## 3. Fixed bounds and time domains

| Quantity | Bound |
|---|---:|
| Generation lifetime | 1..3,600,000 ms |
| Each configured generation input/output limit | 1..16,777,216 bytes |
| DATA application payload | 1..8192 bytes |
| Each STDOUT/STDERR frame payload | 1..8192 bytes |
| Accepted ordinary control requests, entire generation | 8192 |
| ARM epochs, entire generation | 4096 (epochs exactly 1,2,...) |
| Outstanding ordinary requests, all kinds combined | 1 |
| Native pending application DATA | 1 chunk, at most 8192 bytes |
| Native pending child-output frame | 1 frame, at most 8197 framed bytes |
| Native reserved control-output frame | 1, at most 85 framed bytes |
| Native control work per event-loop turn | at most 16,384 bytes AND 32 complete records |
| Native output/input syscall data chunk / read scratch | at most 8192 bytes each |
| Native poll delay during ordinary/cleanup loop | at most 10 ms, shortened for nearer deadlines |
| JVM retained output queue | at most 64 frames AND 262,144 payload bytes |
| JVM retained pending control | 1 request, at most 8205 framed bytes, plus one CANCEL bit |
| Native TERM grace on exceptional retirement | 250 ms |
| Native total cleanup from retirement | 2000 ms (includes TERM grace) |
| Native final-output flush after cleanup | 500 ms |
| JVM total retirement/cleanup grace | at most 4000 ms |
| JVM monitor sleep/poll | at most 5 ms while OPENING/LIVE/RETIRING |
| JVM fixed service threads per launched generation | at most 5, never per message |
| JVM diagnostic bytes examined before stopping that pump | at most 65,536 (any byte is a failure) |
| JVM distinct locally retained reason codes | at most 64, fixed allowlist, deduplicated |

A limit is not a promise that the full byte allowance can always be consumed:
8192 tiny DATA/control requests exhaust request credit first. Kernel pipe
capacity, caller-retained returned bytes, argv storage, and thread stacks are
not represented as application queue memory. All in-process wire buffers are
fixed-count and bounded; no unbounded linked queue, control ACK list, per-chunk
future/thread, or callback-owned output queue is allowed.

Native monotonic lifetime starts before waiting for the parent preamble or
fork/setup, is immutable, and is not extended by ARM, idle/disarmed time, I/O,
ACKs, or progress. A single optional epoch watchdog is subordinate to this
lifetime. All native duration arithmetic MUST check overflow. A failed clock
read/arithmetic MUST fail closed (I/O failure), disable dispatch/gate granting,
and attempt immediate final owned signals/bounded reaping without inventing a
fresh clock budget; inability to prove closure means nonzero/missing FINAL and
JVM quarantine. This does not promise OS scheduling or uninterruptible syscall
latency bounds.

The JVM uses an immutable monotonic `OwnedSessionDeadline`, created by the
owner **before** host preparation/queueing. Duration is 1..3,600,000 ms; compute
elapsed by monotonic subtraction (not wall-clock timestamps). Generation and
ARM carry the remaining whole milliseconds sampled just before launch/record
write. Remaining <1 ms is expired, never rounded into a fresh full budget.
The JVM retains and monitors each original deadline: native receipt/ACK MUST
NOT reset it. Wire/launch delay can make native relative deadlines later than
the host deadline; the host independently retires at its earlier deadline.
Trusted Java filesystem/image/launcher calls are cooperative, not forcibly
preemptible; check deadlines before and after them and retire any returned
started channel if time is already exhausted.

## 4. Preamble and common framing

All multibyte wire values are unsigned big-endian except `FINAL.childExit`,
which is signed i32. Values represented by JVM Long MUST be 0..Long.MAX_VALUE;
this protocol's tighter field-specific caps always apply. No padding,
compression, text encoding, version negotiation, optional extension, or trailing
bytes are permitted. Each framed record is `tag:u8, payloadLength:u32, payload`.
Unknown tags, nonexact fixed sizes, impossible counters, stale/duplicate
sequences/epochs, malformed preambles, and illegal transitions fail closed.
Lengths MUST be validated before allocation/body reading.

Owner writes exactly `KTCCTL2\n` + raw 16-byte generation nonce (24 bytes) before
any control record. Helper validates this complete preamble against argv before
forking. Preamble reads are nonblocking/incremental under the generation
lifetime. A bad/truncated preamble or preamble-timeout never forks; no valid
BOUND can be claimed. Helper may exit nonzero without FINAL in this case;
owner cannot turn an invalid channel into cleanup evidence and quarantines any
launched transport lacking a valid FINAL. No child is launched speculatively
while a preamble is incomplete.

Helper stream starts `KTCLIV2\n` then exactly one `BOUND` before other frames.
BOUND is emitted only after the valid parent preamble; it may precede child
fork/group setup. A later setup failure is consequently expressible without
claiming that a child existed. Owner MUST wait until it has completely parsed
matching BOUND before writing any ordinary control. Native accepts ordinary
controls only after successful fork **and** complete BOUND-frame write; an
earlier ordinary control is protocol failure (no extra request buffer). This
keeps BOUND and ordinary ACK from competing for the one reserved output slot.
CANCEL remains valid while OPENING, without waiting for BOUND/child fork, and
cannot authorize further ordinary dispatch. No magic scanning/resynchronization
is allowed.
EOF before complete FINAL is invalid, even if the helper exits zero. EOF after
FINAL is mandatory; another FINAL or any trailing byte is invalid.

## 5. Owner-to-helper controls

Ordinary tags are ARM=17 (`0x11`), DATA=18 (`0x12`), DISARM=19 (`0x13`),
CLOSE_STDIN=20 (`0x14`). CANCEL=127 (`0x7f`) is exceptional, not ordinary.

| Tag | Payload (offsets in bytes) | Length |
|---|---|---:|
| ARM | 0 `sequence:u64`, 8 `epoch:u64`, 16 `remainingBudgetMillis:u64` | 24 |
| DATA | 0 `sequence:u64`, 8 opaque application bytes | 9..8200 |
| DISARM | 0 `sequence:u64`, 8 `epoch:u64` | 16 |
| CLOSE_STDIN | 0 `sequence:u64` | 8 |
| CANCEL | empty | 0 |

Maximum ordinary payload is 8200; maximum framed record is 8205. Sequence starts
at 1 and increments exactly once for each accepted ordinary request, regardless
of kind, through at most 8192. DATA's write sequence is this ordinary sequence;
it need not be consecutive among DATA alone. Rejected/partially received
requests are not accepted/counted. Only complete, valid DATA is accepted and
counted; partial DATA is never forwarded speculatively. No wrap/retry/replay.

### Shared ordinary credit and ACK

Owner MUST have at most one ordinary request in flight and MUST wait until its
exact ACK has been parsed before issuing the next. Locally attempting another
while busy is an immediate `Rejected(notSent=true, busy)`, not a wait queue.
Native ordinary credit is consumed when a complete request is accepted, and is
returned only when that request's entire ACK frame has been written to helper
stdout (not merely queued). A next ordinary request arriving earlier is a
protocol failure/retirement, not a reason to stop reading controls indefinitely.
Because owner waits for receipt, it cannot legitimately get ahead of this rule.

ARM installs a watchdog with exactly next epoch (1..4096), remaining duration
1..3,600,000 ms, clamped to the immutable generation deadline. No epoch may
replace/extend an active epoch. It may be used after stdin has closed, since the
watchdog is generation bookkeeping; it grants no new input permission then.
DATA requires open child stdin and an armed, unexpired watchdog. Its size must
fit the remaining generation input allowance. Native ACKs DATA only after all
bytes of that chunk have been successfully written into the child's kernel
stdin pipe. An ACK is **not** evidence the worker has read even one byte.

DISARM requires exactly the active epoch and no pending DATA. Native MUST
sample/check expiration immediately before accepting the complete DISARM; a
late DISARM cannot erase timeout. JVM retains its original epoch deadline until
it receives a valid DISARM ACK in time; delayed ACK never retroactively succeeds.
Disarm is bookkeeping only, not worker/session cleanup or native quiescence.

CLOSE_STDIN requires stdin not previously closed and no other outstanding
ordinary request. It closes only child stdin after all previously accepted DATA
has been pipe-written. Global one-request credit makes that drain precondition
explicit (a pending DATA cannot be bypassed by CLOSE). ACK means the native
stdin write descriptor was closed; it does not mean the child read EOF or exited.
It never closes owner control, cancels, disarms, or extends a deadline. No DATA
or second CLOSE is allowed thereafter. A child naturally closing its read end
while DATA is pending is I/O failure; no synthetic input ACK is allowed.

CANCEL requires no sequence, epoch, ACK, ordinary credit, or remaining
input/control budget. Owner sends at most one; local repeated cancellation is
idempotent. Native recognizes it at any complete record boundary, including
while child stdin and helper stdout are both blocked or ordinary ACK is pending.
It immediately initiates retirement, discards unforwarded accepted DATA, and
closes child stdin. Duplicate CANCEL while retiring changes nothing. It has no
separate ACK: only eventual validated native FINAL/quiescence closes the handle.
Once retirement begins, no ordinary request is accepted or acknowledged anew;
ordinary bytes racing with retirement may be consumed/discarded with bounded
work, never used to resume dispatch. Continue bounded control draining/EOF
recognition and CANCEL flag handling until final snapshot (or an already broken
parser), without extending cleanup. Previously completed pending ACKs may
still be flushed. Bytes after a protocol violation need not be resynchronized:
retirement already occurred, so a following CANCEL is not needed to trigger it.

Unsolicited owner-control EOF always means owner loss and retirement. EOF with
an incomplete preamble/record additionally marks protocol failure where a
structured FINAL remains possible. Only a complete, validly parsed CANCEL **before** control EOF permits the
explicit-requested-termination exception: retain cancellation rather than add
owner loss for that EOF. A CANCEL prefix, malformed CANCEL, intended/queued but
unparsed CANCEL, or unsolicited EOF does not qualify. This exception is always
irrevocable retirement: it never means healthy continuation/reuse or replaces
FINAL/quiescence proof. Native MUST NOT treat EOF as normal
child-stdin completion: only CLOSE_STDIN performs that action. The control
channel remains open across jobs and across CLOSE_STDIN until retirement.

### Control flood bounds / progress

All ordinary requests (including ARM/DISARM/CLOSE, not just DATA) consume the
same finite 8192-request budget. The 8193rd ordinary request triggers CONTROL_LIMIT
retirement. Exhausting the 4096-epoch budget likewise triggers CONTROL_LIMIT.
An oversized/zero DATA frame is protocol failure; a well-sized DATA exceeding
the generation byte allowance is INPUT_LIMIT retirement. CANCEL is classified
before ordinary-budget checks. Idempotent cancellation allocates no queue;
post-retirement flooding cannot extend cleanup/flush budgets. The bound on
legal ordinary framing plus application input is at most
`8192 * 29 + inputLimit` bytes (plus the 24-byte preamble and one 5-byte CANCEL).

The native event loop MUST service deadlines/leader observation/control even
when a DATA write is EAGAIN or either output buffer is blocked. All pipe endpoints
used in the helper event loop are nonblocking. Control parsing is bounded per
turn, never a blocking `readExact`. Each accepted record and each child-stdin
write rechecks current time. Check deadline BEFORE DISARM/gate/data acceptance,
not only once after a batch. Poll/control dispatch never waits for a child read,
free ACK output, or application consumer. Ordinary ACKs use the fixed reserved
control-output slot; output data cannot consume it. At a frame boundary, ready
control output has priority over new child-output data. A partly written frame
is completed before another frame begins (no interleaving).

JVM uses one fixed writer pump. Only it writes the preamble/records/closes
owner control. Cancellation wins at its next record boundary and wakes an idle
writer; one already-started record is bounded to 8205 bytes. Under a conforming
native loop this record can drain from the owner pipe without waiting for child
stdin or helper stdout. There is no per-chunk blocking writer/future and no
caller-thread write/flush/close. If the helper/OS/writer is broken and will not
consume control, caller cancellation still returns promptly, the original
native lifetime remains, and the JVM quarantines at its bounded grace instead
of signaling/killing the helper or claiming successful cancellation delivery.

## 6. Helper-to-owner frames

| Tag | Name | Payload length |
|---:|---|---:|
| 1 | BOUND | 40 |
| 2 | STDOUT | 1..8192 |
| 3 | STDERR | 1..8192 |
| 4 | ACK | 32 |
| 5 | FINAL | 80 |

BOUND payload: offsets 0..15 raw nonce; 16 generation-duration argument:u64;
24 input-limit argument:u64; 32 output-limit argument:u64. Every value MUST
match this launch exactly. No execution/group/ready flag exists in BOUND.

ACK payload:

| Offset | Field |
|---:|---|
| 0 | acknowledged request tag:u32 (17,18,19,20 only) |
| 4 | reserved:u32 (zero) |
| 8 | ordinary request sequence:u64 |
| 16 | valueA:u64 |
| 24 | valueB:u64 |

For ARM and DISARM, valueA is that exact epoch and valueB is zero. For DATA,
valueA is that chunk's application byte count and valueB is cumulative actual
child-stdin bytes written, including this chunk. For CLOSE_STDIN, both are zero.
Only the exact current outstanding request can be acknowledged, once. No stale,
unsolicited, duplicate, wrong-tag, wrong-epoch, wrong-size, decreasing, or
mismatched cumulative ACK is valid. ACK output is bounded by accepted ordinary
requests; no independent ACK stream/heartbeat/periodic progress frames exist.

STDOUT/STDERR are opaque child bytes, ordered within each descriptor. Framing
may interleave descriptors arbitrarily; it does not establish cross-stream or
job order. Native reads at most one bounded chunk when its one data-output slot
is available. It counts all bytes read as observed, retains up to the remaining
output allowance as captured, and retires with OUTPUT_LIMIT if a read exceeds
that allowance. On first overflow it closes both child-output read endpoints
and makes no further child-output reads: therefore
`0 <= captured <= observed <= outputLimit + 8192`, and `captured <= outputLimit`.
Observed means **bytes actually read by the helper**, never exact total bytes
produced by the child. Overflow/closed read ends cannot claim natural EOF.
Without overflow, normal/retirement cleanup drains available output under the
same bounds/deadline. A broken output pipe does not prevent owned cleanup, but
cannot produce an acknowledged complete transport closure to that owner.

On a valid completed wire stream, captured exactly equals the sum of all
STDOUT/STDERR payloads before FINAL (pending data has been completely flushed).
A locally full JVM output queue is a transport failure/retirement, never silent
success/truncation. Reader may continue bounded parsing/discarding after that
failure to obtain real closure; discarded payloads are explicitly counted and
capture is marked incomplete. No unbounded accumulator is added to the queue.
Control ACK, BOUND and FINAL are processed directly in bounded state, not placed
behind application data or lost on application-queue overflow.

## 7. FINAL layout, causes and validation

| Offset | Field |
|---:|---|
| 0 | cause:u32 |
| 4 | flags:u32 |
| 8 | childExit:i32 (-1 absent; otherwise 0..255) |
| 12 | reserved:u32 = 0 |
| 16 | inputAccepted:u64 |
| 24 | inputWritten:u64 |
| 32 | inputAckFlushed:u64 |
| 40 | outputObserved:u64 |
| 48 | outputCaptured:u64 |
| 56 | requestsAccepted:u64 |
| 64 | acksFlushed:u64 |
| 72 | lastEpoch:u64 |

InputAccepted is complete valid DATA payload accepted (not malformed/rejected
or partial records). InputWritten is actual successful native writes to the
kernel stdin pipe, including a partially forwarded pending chunk.
InputAckFlushed counts only DATA bytes whose whole ACK frame reached helper
stdout. It does not mean the owner received/parses the ACK. Missing ACK after a
partial or complete write is ambiguous; no automatic replay/retry is allowed.
FINAL counters can report that ambiguity but cannot make a failed operation a
worker-job receipt. RequestsAccepted is also the last accepted ordinary
sequence (zero before any). AcksFlushed counts entire ordinary ACK frames,
not partial writes. LastEpoch is the last accepted ARM epoch (zero before ARM).

Flags retain v1 numeric meanings at bits 0..13, but v2 FINAL size/validation is
separate; v1 readers MUST reject v2 rather than reinterpret it.

| Hex bit | Name |
|---:|---|
| 0x00001 | EXIT_OBSERVED |
| 0x00002 | LEADER_REAPED |
| 0x00004 | QUIESCENT |
| 0x00008 | GROUP_READY |
| 0x00010 | EXEC_CONFIRMED (FINAL only) |
| 0x00020 | CANCELLED |
| 0x00040 | TIMED_OUT |
| 0x00080 | OUTPUT_LIMIT |
| 0x00100 | PARENT_LOST |
| 0x00200 | IO_FAILED |
| 0x00400 | EXEC_FAILED |
| 0x00800 | PROTOCOL_FAILED |
| 0x01000 | OWNERSHIP_LOST |
| 0x02000 | SHUTDOWN_FAILED |
| 0x04000 | INPUT_LIMIT |
| 0x08000 | CONTROL_LIMIT |
| 0x10000 | INPUT_INCOMPLETE |
| 0x20000 | STDIN_CLOSED |
| 0x40000 | OUTPUT_EOF |
| 0x80000 | NO_CHILD |

Known mask is 0xFFFFF. STDIN_CLOSED means the native child-stdin write descriptor
is closed (also true if no child was ever created). OUTPUT_EOF means both
child-output pipes reached actual EOF without read errors/overflow/forced
closure (vacuously true for NO_CHILD). INPUT_INCOMPLETE is exactly
`inputAccepted > inputWritten`. NO_CHILD is irreversible proof no successful
fork occurred, not an inference from missing exit/group evidence.

Cause is the first non-completed retirement cause; subsequent failures OR their
flags without erasing that first cause. Before accepting any control, expired
generation/watchdog wins over a just-arrived DISARM/CANCEL in that dispatch;
CANCELLED can still be added as a flag. Before final snapshot, a clock/ownership
failure cannot be suppressed by an earlier cancellation. Codes and required
flags:

| Code | Cause | Required flag(s) |
|---:|---|---|
| 0 | COMPLETED | exact completed mask below |
| 1 | CANCELLED | CANCELLED |
| 2 | TIMEOUT (generation or epoch) | TIMED_OUT |
| 3 | OUTPUT_LIMIT | OUTPUT_LIMIT |
| 4 | PARENT_LOST | PARENT_LOST |
| 5 | IO_FAILURE | IO_FAILED |
| 6 | EXEC_FAILURE | EXEC_FAILED |
| 7 | SETUP_FAILURE / unconfirmed exec | no EXEC_CONFIRMED |
| 8 | SHUTDOWN_UNVERIFIED | SHUTDOWN_FAILED |
| 9 | PROTOCOL_FAILURE | PROTOCOL_FAILED |
| 10 | OWNERSHIP_LOST | OWNERSHIP_LOST + SHUTDOWN_FAILED |
| 11 | INPUT_LIMIT | INPUT_LIMIT |
| 12 | CONTROL_LIMIT | CONTROL_LIMIT |

Every decoder MUST additionally enforce:

* `0 <= inputAckFlushed <= inputWritten <= inputAccepted <= inputLimit`;
  `0 <= outputCaptured <= outputObserved <= outputLimit + 8192`;
  outputCaptured equals actual preceding received output-frame bytes and is at
  most outputLimit. Observed != captured requires OUTPUT_LIMIT or IO_FAILED;
  OUTPUT_LIMIT requires observed > captured and no OUTPUT_EOF.
* `0 <= acksFlushed <= requestsAccepted <= 8192` and
  `requestsAccepted - acksFlushed <= 1` (one shared credit). AcksFlushed equals
  the number of preceding complete ACK frames; InputAckFlushed equals the sum
  of preceding ACK(DATA) byte counts. LastEpoch is 0..4096 and no greater than
  requestsAccepted. It agrees with all preceding ARM ACKs; at most the one
  outstanding ARM may increase it by one.
* The accepted request prefix/epochs/input counters are consistent with the
  owner's detached request ledger: all ACKed requests are accepted; zero or
  one additional current request can be accepted but unacknowledged. No final
  counter can acknowledge an unsent request or unknown bytes. InputWritten
  beyond ACKed DATA is possible only within that last unacknowledged DATA.
* INPUT_INCOMPLETE iff inputAccepted > inputWritten. No final success hides
  pending/discarded input, even if all child-output bytes look complete.
* EXIT_OBSERVED iff childExit >= 0; LEADER_REAPED requires EXIT_OBSERVED.
  EXEC_CONFIRMED requires GROUP_READY + EXIT_OBSERVED, no EXEC_FAILED,
  childExit != 126, and no NO_CHILD. EXEC_FAILED requires GROUP_READY and
  excludes EXEC_CONFIRMED. OWNERSHIP_LOST requires SHUTDOWN_FAILED and excludes
  QUIESCENT. QUIESCENT excludes SHUTDOWN_FAILED and requires STDIN_CLOSED.
* If NO_CHILD: childExit=-1; no GROUP_READY/EXIT_OBSERVED/LEADER_REAPED/
  EXEC_CONFIRMED/EXEC_FAILED; all input/counter/epoch values zero; QUIESCENT,
  STDIN_CLOSED, and OUTPUT_EOF set; output counts zero. It cannot have cause 0.
  A helper cannot accept ordinary controls until its child has been forked.
* Without NO_CHILD, QUIESCENT requires EXIT_OBSERVED + LEADER_REAPED. A child
  can fail before group setup, so GROUP_READY need not accompany cleanup.
* Cause 0 requires flags exactly `0x6001F` (the v1 completed 0x1F plus
  STDIN_CLOSED + OUTPUT_EOF); actual exit present/non-126; observed=captured;
  inputAccepted=inputWritten=inputAckFlushed; requestsAccepted=acksFlushed.
  It means native generation completed/closed, not that child exit code is zero,
  a watchdog represented a job, or all application results were successful.

A well-framed but noncompleted FINAL can still prove owned cleanup if its
quiescence facts and external closure gates validate. Normal leader exit while
accepted DATA remains unwritten MUST cause IO_FAILURE + INPUT_INCOMPLETE, not
cause 0. A fully pipe-written chunk without its ACK can finish flushing its ACK
before FINAL; an incomplete chunk never receives a synthesized ACK.

## 8. Internal Kotlin API and local state

M0 adds internal types/wire/transport/image files, not provider routes:
`OwnedProcessSessionTypes.kt`, `OwnedProcessSessionWire.kt`,
`OwnedProcessSessionTransport.kt`, `OwnedLiveSupervisorImage.kt`.
Names in this section fix the minimum API; auxiliary internal classes may be
added without widening origin, ownership, or result claims.

`OwnedSessionDeadline.afterMillis(duration)` captures the calling monotonic
instant immediately; the object is immutable and duration-bound as in §3.
`OwnedSessionConfiguration` holds detached command/cwd/environment,
generationDeadline, inputLimit, outputLimit. `OwnedProcessSessions.open(config)`
uses only installed v2 resources and the shared slot budget. Its result is
`StartedTransport(handle)` or `Rejected(reasons)`; StartedTransport only means
a known helper channel was obtained, initially OPENING. It does not imply child
exec, bound transport, readiness, or job acceptance.

A handle has these synchronous, bounded/cooperative operations:

* `snapshot()` returns a detached immutable state/counter/reason snapshot.
* `arm(epoch, deadline)` issues one ARM and waits under the original epoch and
  generation deadlines for its exact ACK or retirement. The local epoch
  watchdog begins at admission, before writer queueing; not at native ACK.
* `write(bytes)` accepts exactly one 1..8192 byte detached chunk, requires an
  acknowledged active epoch, and waits under those same deadlines for INPUT
  ACK or retirement. There is no automatic chunk splitting/retry/replay.
* `disarm(epoch)` waits for exact ACK within that epoch deadline. Native and
  host deadline checks remain live while ACK is pending.
* `closeStdin()` waits for its exact ACK within the active epoch (if any) and
  generation deadlines. It is graceful stdin EOF, not retirement.
* `cancel()` is idempotent owner-authorized retirement. It sets local state and
  wakes the existing monitor/writer; caller does no pipe I/O, filesystem I/O,
  user callback, or blocking join. It does not return a cleanup acknowledgement.
* `pollOutput(waitMillis)` returns one detached provisional output observation
  or null, bounded to 0..1000 ms per call. Per-stream order is retained.
* `awaitTerminal(waitMillis)` waits 0..4000 ms for an immutable terminal or
  quarantine snapshot, returning null if only this observation wait expires.
  Observer timeout/cancellation is not authority to retire the generation.

Ordinary operation result is either `Acknowledged(sequence, kind, values)` or
`Failed(reasons, sequence?, delivery)` or `Rejected(reasons, notSent=true)`.
Delivery classification for failures is exactly `NOT_SENT` (writer never began
that record) or `MAY_HAVE_BEEN_WRITTEN`; even a full INPUT ACK is never a worker
receipt. Original local deadline expiration/retirement wins over a concurrent
late ACK; late information cannot turn a failed ordinary call into success.
An interrupted ordinary wait is an owner-operation failure and retires
conservatively, but MUST preserve the one outstanding request ledger and
NOT_SENT/MAY_HAVE_BEEN_WRITTEN distinction. Never erase that record on caller
failure: a structurally valid later ACK may contribute to real owned closure
within the original grace, but cannot upgrade the failed call, reopen dispatch,
or rehabilitate QUARANTINED. An interrupted snapshot/output/terminal observer
wait preserves interruption and does not acquire generation-cancel authority.
Busy, not-LIVE, wrong-epoch, closed-stdin, or invalid-size local calls are
rejected before retention/writing. An attempted request beyond configured count/byte allowance retires the
generation (no hidden rollover/new helper); merely reaching the exact allowance
does not invalidate its last permitted ACK. Local rejection/retirement reasons
are not fabricated native flags. Unknown native protocol/state is
failure/retirement. API caller-owned collection/array mutation cannot alter
stored input, output, configuration, snapshots, identity, or request ledger.

There is one ordinary request cell plus its waiting caller; competing calls
are rejected, not parked in a hidden dispatch queue. Completed request metadata
can be represented by bounded aggregate counters plus one outstanding record,
not an unbounded per-request log. All local reasons come from a fixed allowlist
of at most 64 codes and are deduplicated; never retain exception messages,
arbitrary protocol strings, diagnostics, or a failure-per-poll history. Check
byte/count bounds incrementally before addition/allocation, including every
unsigned field conversion and overflow case. Frame reader correlates ACK directly with
that record even if the writer's write call has not yet returned; mark intent
before the first write. No transport lock is held across pipe/image/launcher
I/O, a blocking wait, or caller code. API is internal plumbing only; it does not
assign compiler work, apply pooling/affinity, or connect Kernel review cancel.

States are OPENING -> LIVE -> RETIRING -> TERMINAL or QUARANTINED. OPENING may
also go directly to RETIRING; LIVE is entered only after exactly matching
BOUND. LIVE means channel association only. Any active host deadline, owned
cancel, wire/image/diagnostic failure, queue overflow, or native retirement
prevents further dispatch and starts/reduces one fixed cleanup deadline. Later
failure/progress never extends it. A natural valid FINAL likewise enters
RETIRING pending external closure checks. No TERMINAL/QUARANTINED -> LIVE path.
No EOF or FINAL observation alone frees a handle/slot.

JVM output queue bounds are enforced on both byte and record count before
retention; a rejected frame is explicitly counted as discarded and capture
incomplete. On queue overflow retire but continue draining/validating within
the existing generation/cleanup bounds when possible. Snapshot separates
received/delivered/retained/discarded output counts, native provisional FINAL,
local failure reasons, and **validated termination**. Capture completeness is
true only if native output was fully captured, no local frame was discarded,
wire/EOF validated, and no local output corruption occurred; retained frames
need not already have been consumed. Native counters cannot hide local loss.
Provisional bytes remain provisional even after generation cleanup: only a
separate application adapter may qualify worker/job results.

Fixed service threads are one writer, one frame reader, one diagnostic reader,
one monitor/retained-cleanup worker, and at most one close worker. No new thread
for each command, cancellation, poll, timeout, or late ACK. Thread-creation/start
failure after launch retires and, without full cleanup proof, quarantines; it
MUST NOT release capacity merely because startup was incomplete. Any diagnostic
byte or unreliable diagnostic stream fails the transport (do not retain/log
unbounded or potentially private diagnostics). Closing streams is done off the
caller and monitor critical path; a blocked close cannot conceal deadline
expiration. An unresolved daemon/pump is charged to its unreleased slot.

## 9. Owned native lifecycle, closure and capacity

Native v2 preserves preview-1's crucial Linux ownership mechanism: SIGCHLD not
ignored/auto-reaped; actual fork child performs private setsid handshake behind
an exec gate; helper retains the actual leader (including as zombie) with
waitid(WNOWAIT); it is the sole reaper. Unexpected ownership/wait errors revoke
all real signaling rather than guess. Every real signal goes through one
owned-signal function. The irreversible signals-finished state precedes every
waitpid; no signals after any reap, no PID/PGID rediscovery or liveness-based
reacquisition. Linux subreaper ECHILD plus actual leader reaping, not a /proc
census, proves quiescence within the stated cooperative boundary.

Child stdin is the new native-owned pipe, not inherited parent control. Child
stdout/stderr remain private pipes. The group gate must be valid before target
exec; readiness EOF alone remains ambiguous. Only FINAL can set EXEC_CONFIRMED,
after complete R/G handshake, complete readiness EOF without failure marker,
actual **normal** leader exit, and exit !=126. Signals/pre-exec death/126 remain
recorded but unconfirmed. No live EXEC_READY event is defined or permitted.

Retirement closes the exec gate and child stdin, discards unforwarded DATA and
marks INPUT_INCOMPLETE when appropriate. It sends owned TERM, then final owned
KILL after at most 250 ms (zero extra TERM wait on already-observed normal
leader-exit cleanup, as in v1), and performs bounded reaping/draining under the
2000 ms cleanup budget. Cleanup failure/remaining pipes at deadline marks the
appropriate shutdown/I/O failure. Each reaping turn has at most 64 wait calls;
if the monotonic clock fails, the fallback makes at most one such bounded
reaping pass after immediate final signals, then exits nonzero rather than
resetting/claiming a timed cleanup proof. Final stream flush has its own
at-most-500 ms budget and at-most-1024-pass fallback; failure returns nonzero,
never a fake successful FINAL. Before constructing FINAL, flush the existing
partial data frame and any already-eligible ACK completely, within that same
500 ms budget; snapshot counters only afterward, so ACK-flushed counters equal
the actual preceding wire frames. FINAL admission uses its reserved control
slot, never ordinary-request credit. Once final flush begins, no code can
signal again; no new child-output reads or ordinary requests occur in flush.
Output pressure can defeat delivery/closure evidence but MUST NOT postpone
control/deadline service or actual cleanup initiation indefinitely.

Production v1 and v2 share one process-local `SupervisorSlots(4)` owner. The
only v1 capacity edit extracts that same singleton budget for both production
entry points; v1 route/run semantics and explicit test-injected slots remain
unchanged. Opening, LIVE idle/disarmed, draining, unresolved and quarantined
generations all consume it. There is no replacement/overcommit while an
unresolved generation retains its slot. This bounds supervised generations,
not all OS processes or compiler RSS. Existing active-work worker caps are not
reinterpreted as retained-idle-JVM accounting.

TERMINAL/slot return requires **all** of:

1. Exact nonce/limits/magic, strictly valid single FINAL and EOF; all ACK/FINAL
   ledger/counter checks pass. FINAL claims QUIESCENT + STDIN_CLOSED and no
   OWNERSHIP_LOST/SHUTDOWN_FAILED, with explicit no-child or reaped-child facts.
2. Real helper exit code zero, empty diagnostics with reliable EOF, and fresh
   post-exit verification of the same private installed image.
3. All writer/frame/diagnostic/close pumps ended, their streams closed, and
   that image's exact owned files safely removed, before the original bounded
   cleanup deadline. Monitor may publish final state and return at this point;
   it must never perform further owned I/O afterward.

Native/provisional input ACK, application result, process exit alone, or a
quiescence bit alone is insufficient. Queue overflow can still end with safely
acknowledged owned cleanup, but its output/ordinary operation remains failed.
Similarly a native noncompleted cause can have valid owned cleanup; do not
confuse operation success with slot-release safety.

At grace expiration or failed closure proof, publish immutable QUARANTINED
failure, permanently retain the slot for this JVM lifetime, and never upgrade
it because a late FINAL/EOF/exit/ACK/pump completion arrives. The existing
bounded-count monitor can wait for helper/pumps to actually end and then remove
only the known image files; that late hygiene MUST NOT restore capacity or
rewrite the returned outcome. No forced helper kill or external PID cleanup.
Restarting this JVM is not cleanup evidence; durable reconciliation/operator
fail-closed restart policy remains a separate blocker, not solved here.

Before any helper launch, a known byte-only load/validation rejection can
return its slot. If an image handle exists, returning a not-launched slot also
requires its successful safe cleanup. An extraction failure without an owned
cleanup receipt is conservatively unresolved; no assumption that an exception
means all artifacts were cleaned. Launcher failure can release only if it
unambiguously means no process was started and image cleanup succeeds; any
post-start/unknown outcome retains the slot. No guessed recovery.

## 10. M0 file ownership and verification gates

* Shared contract + JVM author: this file; four new Kotlin files in §8; new
  corresponding pure wire/types/image/fake-transport tests and a separately
  gated real duplex integration test; only narrow existing edits to
  `OwnedProcessSupervisor.kt` (shared capacity) and `OwnedSupervisorImage.kt`
  (closed v2 resource names). No Execution/provider/model/kernel/CLI edits.
* Native author: new `owned_live_process_supervisor.c`,
  `build_live_supervisor.py`, `live_supervisor_probe.c`,
  `test_live_supervisor.py`. All v1 C/builder/probe/tests/README stay unchanged.
  The separate bounded role-redacted build receipt schema is
  `ktchecker-owned-child-supervisor-build/2`, protocol preview-2; it is not
  runtime authority or a transitive toolchain attestation.
* Root: additive `buildOwnedLiveProcessSupervisor` packaging in module Gradle,
  with a distinct generated resource directory. Each builder may clear only
  its own generated tree; never the other version's resources. Root owns
  integration and all JVM/native/build execution gates.

Required later verification (source/fake tests do not establish real operation):

* Existing v1 unit/image/native tests unchanged and passing; v1 child stdin
  remains /dev/null; both manifests/magics/argv versions reject each other.
* Fragment every prefix/header/body boundary; truncate/oversize/zero/unknown
  frames; wrong nonce/limits; stale/duplicate ACK/epoch/sequence; impossible
  FINAL flags/counters, repeated FINAL/trailing bytes; deterministic detachment;
  image drift/origin rejection/no fallback; all-control and byte budgets.
* Fake shared-capacity/cancel tests: LIVE idle/disarmed, RETIRING, and QUARANTINED
  all retain one of four slots across both versions; independent control pipes;
  local timeout/late ACK cannot rehabilitate; interrupted ordinary callers
  retain the ledger/delivery uncertainty, and observer interruption never
  cancels a generation; repeated failures do not grow reason storage;
  incremental byte/count arithmetic rejects overflow before allocation.
  Blocked writer/close has bounded caller/monitor behavior, no per-message
  threads or callback output buffering.
* Real Linux native probes: nonreading child stdin with pending/partial DATA;
  blocked/flooded helper output at cancel/deadline; control/ACK flooding; owner
  EOF/death; graceful stdin EOF; immutable generation lifetime while idle;
  armed deadline/expired DISARM; short lived/pre-exec death/126; fast leader exit
  and TERM-resistant descendant. Preserve the syscall audit that every real
  signal precedes leader reap; never claim actual PID reuse was manufactured.
  Probe programs self-expire; tests cannot invent numeric PID/helper-kill
  cleanup and then call the production cleanup mechanism verified.
* Real JVM/native duplex test: use one tiny controlled probe, issue request 1,
  receive response 1 while the handle is LIVE, **then** issue request 2 on that
  same live handle and receive response 2, finally CLOSE/retire and verify the
  actual eventual native closure. Not a special four-job compiler experiment,
  and not an application-readiness/compiler-warming qualification.

No source contract, authored test, private compile, benchmark, or prior finite
experiment is evidence a newly authored v2 implementation passed these gates.
Pending distribution licensing forbids publication, not this local source work.
