/** Source-only opt-in acceptance support. No entry point, subprocess retry, signal or cleanup.
 * Runtime operations below are used ONLY by the separately approved standalone companion.
 */
import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { constants, closeSync, fstatSync, fsyncSync, lstatSync, mkdirSync, openSync, opendirSync,
  readSync, statfsSync, writeSync, type BigIntStats } from 'node:fs';
import { dirname, isAbsolute, join, normalize, parse, relative, sep } from 'node:path';
import { PREVIEW_CONTRACT, PRIVATE_RPC_LINE_BYTES, type BrokerPort, type JsonObject } from '@ktchecker/mcp-preview/rpc';
import { canonical, digest, HostError } from '../src/journal.js';

export const REAL_MODEL_CASE = 'ktc_live_model_no_test_feasibility';
export const REAL_MODEL_OVERALL_MS = 900_000;
export const REAL_MODEL_REVIEW_MS = 300_000;
export const REAL_MODEL_CONTEXT_BYTES = 2 * 1024 * 1024;
export const REAL_MODEL_PUBLIC_BYTES = 2 * 1024 * 1024;
export const REAL_MODEL_RPC_BYTES = 16 * 1024 * 1024;
export const REAL_MODEL_TREE_BYTES = 1024 * 1024 * 1024;
export const REAL_MODEL_TREE_ENTRIES = 40_000;
export const REAL_MODEL_INPUTS_DIGEST = 'sha256:aac7ac1481e913646232b79b46fdf99ad847341d5d960e2be5b7021581733de7';
export const REAL_MODEL_INPUTS_BYTES = 3_315_974;
export const SHA256 = /^sha256:[0-9a-f]{64}$/;

export function requireReal(condition: unknown, code: string): asserts condition {
  if (!condition) throw new HostError(code);
}
export function objectReal(value: unknown): JsonObject {
  requireReal(value !== null && typeof value === 'object' && !Array.isArray(value), 'real_model_object_required');
  return value as JsonObject;
}
export function sameReal(actual: unknown, expected: unknown, code: string): void {
  requireReal(canonical(actual) === canonical(expected), code);
}
export function exactReal(value: JsonObject, keys: readonly string[]): void {
  sameReal(Object.keys(value).sort(), [...keys].sort(), 'real_model_unexpected_fields');
}
export function stringReal(value: unknown): string {
  requireReal(typeof value === 'string' && value.length > 0 && value.length <= 16_384 && !/\p{Cc}/u.test(value), 'real_model_string_required');
  return value;
}
export function identifierReal(value: unknown): string {
  requireReal(typeof value === 'string' && /^[a-zA-Z0-9_.:-]{1,256}$/.test(value), 'real_model_identity_required');
  return value;
}
export function shaReal(value: unknown): string {
  requireReal(typeof value === 'string' && SHA256.test(value), 'real_model_digest_required'); return value;
}
export function absoluteReal(value: unknown): string {
  const path = stringReal(value);
  requireReal(process.platform === 'linux' && path.length <= 4096 && isAbsolute(path) && path !== '/' && normalize(path) === path &&
    !path.includes('\\') && !path.endsWith('/') && path.split('/').length <= 128, 'real_model_absolute_path_required');
  return path;
}
export function safeRealFailure(error: unknown): string {
  return error instanceof HostError && /^[a-z0-9_]{1,160}$/.test(error.code) ? error.code : 'real_model_companion_operation_failed';
}

/** One ORIGINAL observation clock, captured before any fixture/home/owner preparation.
 * A wait timeout never cancels anything and never establishes a refreshed execution budget.
 */
export class RealModelClock {
  readonly enteredNanos = process.hrtime.bigint();
  readonly enteredAt = new Date().toISOString();
  readonly originalDeadlineNanos = this.enteredNanos + BigInt(REAL_MODEL_OVERALL_MS) * 1_000_000n;
  elapsedMs(): number { return Number(process.hrtime.bigint() - this.enteredNanos) / 1_000_000; }
  remainingMs(): number {
    const remaining = Math.floor(Number(this.originalDeadlineNanos - process.hrtime.bigint()) / 1_000_000);
    requireReal(remaining > 0, 'real_model_original_observation_deadline'); return remaining;
  }
  checkpoint(): void { this.remainingMs(); }
  async wait<T>(operation: () => Promise<T>, capMs = REAL_MODEL_OVERALL_MS): Promise<T> {
    const delay = Math.min(this.remainingMs(), capMs);
    let timer: NodeJS.Timeout | undefined;
    try {
      return await Promise.race([operation(), new Promise<never>((_resolve, reject) => {
        timer = setTimeout(() => reject(new HostError('real_model_wait_outcome_unknown')), delay);
      })]);
    } finally { if (timer) clearTimeout(timer); } // Timer only; never process or owner cleanup.
  }
}

function fileKey(stat: BigIntStats): string { return stat.dev.toString() + ':' + stat.ino.toString(); }
function unchanged(a: BigIntStats, b: BigIntStats): boolean {
  return fileKey(a) === fileKey(b) && a.size === b.size && a.mtimeNs === b.mtimeNs && a.mode === b.mode && a.nlink === b.nlink;
}
function checkRegular(stat: BigIntStats, limit: number, privateFile: boolean): void {
  requireReal(stat.isFile() && !stat.isSymbolicLink() && stat.nlink === 1n && stat.size >= 0n && stat.size <= BigInt(limit), 'real_model_regular_file_bound');
  if (privateFile) requireReal((stat.mode & 0o077n) === 0n && process.getuid !== undefined && stat.uid === BigInt(process.getuid()), 'real_model_private_file_required');
}
/** Linux self-fd directory anchoring, not an inspection of any other process or session.
 * Every component opens O_DIRECTORY|O_NOFOLLOW, and each retained parent edge is rechecked.
 */
function inDirectory<T>(path: string, action: (fd: number) => T): T {
  requireReal(process.platform === 'linux' && isAbsolute(path) && normalize(path) === path, 'real_model_secure_io_unavailable');
  const fds: number[] = [];
  const edges: { parent: number; name: string; stat: BigIntStats; child: number }[] = [];
  try {
    let fd = openSync('/', constants.O_RDONLY | constants.O_DIRECTORY | constants.O_NOFOLLOW); fds.push(fd);
    for (const name of path.split('/').filter(Boolean)) {
      const anchored = '/proc/self/fd/' + fd + '/' + name;
      const before = lstatSync(anchored, { bigint: true });
      requireReal(before.isDirectory() && !before.isSymbolicLink(), 'real_model_parent_unsafe');
      const child = openSync(anchored, constants.O_RDONLY | constants.O_DIRECTORY | constants.O_NOFOLLOW); fds.push(child);
      requireReal(fileKey(fstatSync(child, { bigint: true })) === fileKey(before), 'real_model_parent_changed');
      edges.push({ parent: fd, name, stat: before, child }); fd = child;
    }
    const result = action(fd);
    for (const edge of edges) {
      requireReal(fileKey(fstatSync(edge.child, { bigint: true })) === fileKey(edge.stat) &&
        fileKey(lstatSync('/proc/self/fd/' + edge.parent + '/' + edge.name, { bigint: true })) === fileKey(edge.stat), 'real_model_parent_changed');
    }
    return result;
  } finally { for (const fd of fds.reverse()) closeSync(fd); }
}
export interface RealFileEvidence { path: string; digest: string; bytes: number; fileKey: string }
export function readRealFile(pathValue: string, limit: number, privateFile = true): { bytes: Buffer; evidence: RealFileEvidence } {
  const path = absoluteReal(pathValue);
  requireReal(Number.isSafeInteger(limit) && limit > 0 && limit <= REAL_MODEL_RPC_BYTES, 'real_model_read_bound');
  return inDirectory(dirname(path), parent => {
    const anchored = '/proc/self/fd/' + parent + '/' + parse(path).base;
    const before = lstatSync(anchored, { bigint: true }); checkRegular(before, limit, privateFile);
    const fd = openSync(anchored, constants.O_RDONLY | constants.O_NOFOLLOW);
    try {
      requireReal(unchanged(before, fstatSync(fd, { bigint: true })), 'real_model_file_changed');
      const bytes = Buffer.alloc(Number(before.size)); let offset = 0;
      while (offset < bytes.length) {
        const size = readSync(fd, bytes, offset, bytes.length - offset, null);
        requireReal(size > 0, 'real_model_file_changed'); offset += size;
      }
      requireReal(readSync(fd, Buffer.alloc(1), 0, 1, null) === 0 && unchanged(before, fstatSync(fd, { bigint: true })) &&
        unchanged(before, lstatSync(anchored, { bigint: true })), 'real_model_file_changed');
      return { bytes, evidence: { path, digest: 'sha256:' + createHash('sha256').update(bytes).digest('hex'), bytes: bytes.length, fileKey: fileKey(before) } };
    } finally { closeSync(fd); }
  });
}
export function hashRealFile(pathValue: string, limit = 512 * 1024 * 1024): RealFileEvidence {
  const path = absoluteReal(pathValue);
  requireReal(Number.isSafeInteger(limit) && limit > 0 && limit <= 512 * 1024 * 1024, 'real_model_hash_bound');
  return inDirectory(dirname(path), parent => {
    const anchored = '/proc/self/fd/' + parent + '/' + parse(path).base;
    const before = lstatSync(anchored, { bigint: true }); checkRegular(before, limit, false);
    const fd = openSync(anchored, constants.O_RDONLY | constants.O_NOFOLLOW);
    try {
      requireReal(unchanged(before, fstatSync(fd, { bigint: true })), 'real_model_file_changed');
      const hash = createHash('sha256'), buffer = Buffer.alloc(64 * 1024); let size = 0;
      for (;;) { const count = readSync(fd, buffer, 0, buffer.length, null); if (count === 0) break;
        size += count; requireReal(size <= Number(before.size), 'real_model_file_changed'); hash.update(buffer.subarray(0, count)); }
      requireReal(size === Number(before.size) && unchanged(before, fstatSync(fd, { bigint: true })) &&
        unchanged(before, lstatSync(anchored, { bigint: true })), 'real_model_file_changed');
      return { path, digest: 'sha256:' + hash.digest('hex'), bytes: size, fileKey: fileKey(before) };
    } finally { closeSync(fd); }
  });
}
export function jsonReal(bytes: Buffer): unknown {
  const text = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes);
  requireReal(!text.startsWith('\uFEFF'), 'real_model_json_bom'); return JSON.parse(text) as unknown;
}
export function absentReal(pathValue: string): boolean {
  const path = absoluteReal(pathValue);
  return inDirectory(dirname(path), fd => {
    try { lstatSync('/proc/self/fd/' + fd + '/' + parse(path).base); return false; }
    catch (error) { if ((error as NodeJS.ErrnoException).code === 'ENOENT') return true; throw error; }
  });
}
export function makeFreshRealDirectory(pathValue: string): string {
  const path = absoluteReal(pathValue);
  return inDirectory(dirname(path), parent => {
    const anchored = '/proc/self/fd/' + parent + '/' + parse(path).base;
    mkdirSync(anchored, { mode: 0o700 });
    const stat = lstatSync(anchored, { bigint: true });
    requireReal(stat.isDirectory() && !stat.isSymbolicLink() && (stat.mode & 0o777n) === 0o700n &&
      process.getuid !== undefined && stat.uid === BigInt(process.getuid()), 'real_model_private_directory_required');
    return fileKey(stat);
  });
}
export function writeNewReal(pathValue: string, value: Uint8Array): RealFileEvidence {
  const path = absoluteReal(pathValue);
  requireReal(value.byteLength <= REAL_MODEL_RPC_BYTES, 'real_model_evidence_bound');
  inDirectory(dirname(path), parent => {
    const fd = openSync('/proc/self/fd/' + parent + '/' + parse(path).base,
      constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW, 0o400);
    try { let written = 0; while (written < value.byteLength) {
      const size = writeSync(fd, value, written, value.byteLength - written); requireReal(size > 0, 'real_model_evidence_write_stalled'); written += size;
    } fsyncSync(fd); } finally { closeSync(fd); }
  });
  const expected = 'sha256:' + createHash('sha256').update(value).digest('hex');
  const actual = hashRealFile(path); requireReal(actual.digest === expected && actual.bytes === value.byteLength, 'real_model_evidence_changed'); return actual;
}
export function writeNewRealJson(path: string, value: unknown): RealFileEvidence {
  return writeNewReal(path, Buffer.from(canonical(value) + '\n', 'utf8'));
}
export function usableRealBytes(path: string): number {
  const stat = statfsSync(path, { bigint: true }); const bytes = stat.bavail * stat.bsize;
  requireReal(bytes >= 0n && bytes <= BigInt(Number.MAX_SAFE_INTEGER), 'real_model_disk_measurement_unavailable'); return Number(bytes);
}
/** BOUNDARY check, not a continuous quota or process resource measurement. No files removed. */
export function realRunBoundary(rootValue: string): { entries: number; bytes: number; continuousQuota: false } {
  const root = absoluteReal(rootValue); let entries = 0, bytes = 0;
  const visit = (fd: number, depth: number): void => {
    requireReal(depth <= 128 && ++entries <= REAL_MODEL_TREE_ENTRIES, 'real_model_run_entry_bound');
    // Fixed-size directory buffer: never allocate an unbounded child-name array before the cap.
    const directory = opendirSync('/proc/self/fd/' + fd, { bufferSize: 32 });
    try {
      for (;;) {
        const child = directory.readSync(); if (child === null) break;
        requireReal(entries < REAL_MODEL_TREE_ENTRIES, 'real_model_run_entry_bound');
        const path = '/proc/self/fd/' + fd + '/' + child.name;
        const before = lstatSync(path, { bigint: true });
        requireReal(!before.isSymbolicLink() && (before.isDirectory() || before.isFile()), 'real_model_run_nonregular');
        if (before.isDirectory()) {
          const nested = openSync(path, constants.O_RDONLY | constants.O_DIRECTORY | constants.O_NOFOLLOW);
          try {
            requireReal(fileKey(fstatSync(nested, { bigint: true })) === fileKey(before), 'real_model_run_directory_changed');
            visit(nested, depth + 1);
            requireReal(fileKey(lstatSync(path, { bigint: true })) === fileKey(before), 'real_model_run_directory_changed');
          } finally { closeSync(nested); }
        } else {
          entries++; requireReal(before.nlink === 1n, 'real_model_run_file_alias');
          requireReal(before.size >= 0n && before.size <= BigInt(REAL_MODEL_TREE_BYTES - bytes), 'real_model_run_byte_boundary');
          bytes += Number(before.size);
        }
      }
    } finally { directory.closeSync(); }
  };
  inDirectory(root, fd => visit(fd, 1)); return { entries, bytes, continuousQuota: false };
}

export interface RealModelSelection {
  schemaVersion: 1; fixture: typeof REAL_MODEL_CASE; runRoot: string; ownerInputs: string;
  modelWorkerBridge: string; modelWorkerBridgeDigest: string; modelWorkerBridgeBytes: number;
  ownerJava: string; ownerClasspath: string;
}
export function parseRealModelArgs(args: readonly string[]): { selectionPath: string; selectionDigest: string; codexPath: string; codexDigest: string } {
  requireReal(args.length === 9 && args[0] === '--selection' && args[2] === '--selection-sha256' &&
    args[4] === '--codex-selection' && args[6] === '--codex-selection-sha256' && args[8] === '--approve-real-no-test-feasibility',
    'real_model_explicit_selection_required');
  return { selectionPath: absoluteReal(args[1]), selectionDigest: shaReal(args[3]), codexPath: absoluteReal(args[5]), codexDigest: shaReal(args[7]) };
}
export function loadRealSelection(pathValue: string, expectedDigest: string): { selection: RealModelSelection; evidence: RealFileEvidence } {
  const path = absoluteReal(pathValue); shaReal(expectedDigest);
  const read = readRealFile(path, 64 * 1024); requireReal(read.evidence.digest === expectedDigest, 'real_model_selection_changed');
  const value = objectReal(jsonReal(read.bytes)); exactReal(value, ['schemaVersion', 'fixture', 'runRoot', 'ownerInputs', 'modelWorkerBridge',
    'modelWorkerBridgeDigest', 'modelWorkerBridgeBytes', 'ownerJava', 'ownerClasspath']);
  requireReal(value.schemaVersion === 1 && value.fixture === REAL_MODEL_CASE, 'real_model_selection_schema');
  for (const key of ['runRoot', 'ownerInputs', 'modelWorkerBridge', 'ownerJava']) absoluteReal(value[key]);
  shaReal(value.modelWorkerBridgeDigest);
  requireReal(Number.isSafeInteger(value.modelWorkerBridgeBytes) && (value.modelWorkerBridgeBytes as number) > 0 &&
    (value.modelWorkerBridgeBytes as number) <= 16 * 1024 * 1024, 'real_model_bridge_bound');
  const classpath = stringReal(value.ownerClasspath).split(':');
  requireReal(classpath.length > 0 && classpath.length <= 128, 'real_model_owner_classpath_bound'); classpath.forEach(absoluteReal);
  const selection = value as unknown as RealModelSelection;
  requireReal(path !== selection.runRoot && !path.startsWith(selection.runRoot + '/') &&
    [selection.ownerInputs, selection.ownerJava, selection.modelWorkerBridge, ...classpath].every(input => input !== selection.runRoot && !input.startsWith(selection.runRoot + '/')),
    'real_model_inputs_inside_run_forbidden');
  return { selection, evidence: read.evidence };
}
export function preflightRealOwner(selection: RealModelSelection): { ownerJava: RealFileEvidence; bridge: RealFileEvidence; privateRoots: string[] } {
  const input = readRealFile(selection.ownerInputs, REAL_MODEL_INPUTS_BYTES);
  requireReal(input.evidence.digest === REAL_MODEL_INPUTS_DIGEST && input.evidence.bytes === REAL_MODEL_INPUTS_BYTES, 'real_model_owner_inputs_pin_changed');
  const manifest = objectReal(jsonReal(input.bytes)), javaHome = absoluteReal(manifest.javaHome);
  requireReal(selection.ownerJava === join(javaHome, 'bin/java') && Array.isArray(manifest.files), 'real_model_same_owner_jdk_required');
  const entries = manifest.files.map(objectReal).filter(file => file.kind === 'jdk' && file.relativePath === 'bin/java');
  requireReal(entries.length === 1, 'real_model_java_pin_unavailable');
  const ownerJava = hashRealFile(selection.ownerJava);
  requireReal(ownerJava.digest === entries[0]!.digest && ownerJava.bytes === entries[0]!.size && entries[0]!.executable === true, 'real_model_owner_java_changed');
  const bridge = hashRealFile(selection.modelWorkerBridge, 16 * 1024 * 1024);
  requireReal(bridge.digest === selection.modelWorkerBridgeDigest && bridge.bytes === selection.modelWorkerBridgeBytes, 'real_model_bridge_changed');
  return { ownerJava, bridge, privateRoots: [selection.runRoot, selection.ownerInputs, selection.modelWorkerBridge,
    javaHome, absoluteReal(manifest.gradleHome), absoluteReal(manifest.gradleSeedHome), absoluteReal(manifest.gradleArchive),
    ...selection.ownerClasspath.split(':')] };
}

/** Fixed owner launch environment; neither JVM option injection nor another user's HOME survives.
 * This does not claim protection from a hostile same-UID process or loaded-memory attestation.
 */
export function realOwnerEnvironment(selection: RealModelSelection): NodeJS.ProcessEnv {
  for (const key of ['JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH', 'LD_PRELOAD', 'LD_LIBRARY_PATH']) {
    requireReal(process.env[key] === undefined || process.env[key] === '', 'real_model_owner_injection_environment');
  }
  const home = join(selection.runRoot, 'owner-environment-home'), tmp = join(selection.runRoot, 'owner-environment-tmp');
  makeFreshRealDirectory(home); makeFreshRealDirectory(tmp);
  return { PATH: dirname(selection.ownerJava) + ':/usr/bin:/bin', JAVA_HOME: dirname(dirname(selection.ownerJava)),
    HOME: home, TMPDIR: tmp, LANG: 'C.UTF-8', LC_ALL: 'C.UTF-8', TZ: 'UTC' };
}

interface PendingOwner { resolve(value: unknown): void; reject(error: Error): void; timer: NodeJS.Timeout }
interface ExitOwner { code: number | null; signal: NodeJS.Signals | null; observedMs: number }
/** Distinct fixed single-launch owner transport. Unknown outcome RETAINS live stdin and pipes.
 * Neither fail nor timeout closes, signals, retries, restarts or cancels the owner/generation.
 */
export class RetainedRealModelOwner implements BrokerPort {
  private readonly child: ChildProcessWithoutNullStreams;
  private readonly pending = new Map<string, PendingOwner>();
  private buffer = Buffer.alloc(0);
  private failure: HostError | undefined;
  private eofPermitted = false;
  private eofSentMs: number | null = null;
  private exit: ExitOwner | null = null;
  private closed: ExitOwner | null = null;
  private readonly closeWaiters: ((value: ExitOwner) => void)[] = [];
  private stdoutBytes = 0; private stderrBytes = 0;
  private requests = 0;
  constructor(readonly selection: RealModelSelection, selectedPath: string, selectedDigest: string,
    private readonly clock: RealModelClock, cwd: string) {
    clock.checkpoint(); requireReal(PRIVATE_RPC_LINE_BYTES === REAL_MODEL_RPC_BYTES, 'real_model_rpc_limit_changed');
    const env = realOwnerEnvironment(selection); clock.checkpoint();
    this.child = spawn(selection.ownerJava, ['-Xmx512m', '-XX:MaxMetaspaceSize=256m', '-Duser.home=' + env.HOME,
      '-Djava.io.tmpdir=' + env.TMPDIR, '-cp', selection.ownerClasspath,
      'dev.ktchecker.cli.RealModelOwnerMain', '--selection', selectedPath, '--selection-sha256', selectedDigest,
      '--approve-real-no-test-feasibility'], { cwd, env, shell: false,
      stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
    this.child.stdout.on('data', (bytes: Buffer) => this.receive(bytes));
    this.child.stderr.on('data', (bytes: Buffer) => { this.stderrBytes += bytes.length;
      if (this.stderrBytes > 1024 * 1024) this.fail('real_model_owner_stderr_bound'); }); // Drain, never retain a private transcript.
    this.child.stdin.on('error', () => this.fail('real_model_owner_input_disconnected'));
    this.child.stdout.on('error', () => this.fail('real_model_owner_output_disconnected'));
    this.child.stderr.on('error', () => this.fail('real_model_owner_stderr_disconnected'));
    this.child.on('error', () => this.fail('real_model_owner_start_unavailable'));
    this.child.on('exit', (code, signal) => {
      this.exit = { code, signal, observedMs: clock.elapsedMs() };
      if (!this.eofPermitted || code !== 0 || signal !== null) this.fail('real_model_owner_exit_not_verified');
    });
    this.child.on('close', (code, signal) => {
      this.closed = { code, signal, observedMs: clock.elapsedMs() };
      if (!this.eofPermitted || code !== 0 || signal !== null || this.buffer.length !== 0 || this.pending.size !== 0) this.fail('real_model_owner_close_not_verified');
      for (const resolve of this.closeWaiters.splice(0)) resolve(this.closed);
    });
  }
  check(): void { if (this.failure) throw this.failure; this.clock.checkpoint(); }
  async request<T = JsonObject>(method: string, params: JsonObject): Promise<T> {
    this.check(); requireReal(!this.eofPermitted && this.pending.size < 4 && /^[a-z][a-zA-Z0-9_.]*$/.test(method), 'real_model_owner_request_state');
    requireReal(++this.requests <= 4096 && ['owner.hello', 'owner.phase.begin', 'owner.phase.status', 'owner.retire',
      'review.events', 'review.ack', 'review.observe', 'gate.prepare', 'gate.complete'].includes(method), 'real_model_owner_operation_bound');
    const id = randomUUID(), bytes = Buffer.from(JSON.stringify({ id, method, params }) + '\n', 'utf8');
    requireReal(bytes.length <= REAL_MODEL_RPC_BYTES, 'real_model_owner_request_bound');
    return new Promise<T>((resolve, reject) => {
      // Wait bound only. Owner captures/retains its own ORIGINAL clocks before registration IO.
      const timer = setTimeout(() => this.fail('real_model_owner_rpc_outcome_unknown'), Math.min(this.clock.remainingMs(), REAL_MODEL_REVIEW_MS));
      this.pending.set(id, { resolve: value => resolve(value as T), reject, timer });
      this.child.stdin.write(bytes, error => { if (error) this.fail('real_model_owner_write_outcome_unknown'); });
    });
  }
  private receive(bytes: Buffer): void {
    this.stdoutBytes += bytes.length;
    if (this.failure) return; // Keep draining the pipe without retaining unbounded/invalid bytes.
    if (bytes.length + this.buffer.length > REAL_MODEL_RPC_BYTES + 1) { this.fail('real_model_owner_line_bound'); return; }
    this.buffer = Buffer.concat([this.buffer, bytes]);
    let newline: number;
    while ((newline = this.buffer.indexOf(10)) >= 0) {
      const line = this.buffer.subarray(0, newline); this.buffer = this.buffer.subarray(newline + 1);
      let envelope: JsonObject;
      try { envelope = objectReal(jsonReal(line)); } catch { this.fail('real_model_owner_protocol_invalid'); return; }
      if (typeof envelope.id !== 'string' || envelope.contractVersion !== PREVIEW_CONTRACT ||
        Object.hasOwn(envelope, 'result') === Object.hasOwn(envelope, 'error')) { this.fail('real_model_owner_contract_invalid'); return; }
      const pending = this.pending.get(envelope.id);
      if (!pending) { this.fail('real_model_owner_unsolicited_response'); return; }
      this.pending.delete(envelope.id); clearTimeout(pending.timer);
      if (Object.hasOwn(envelope, 'error')) { pending.reject(new HostError('real_model_owner_rpc_rejected')); this.fail('real_model_owner_rpc_rejected'); return; }
      pending.resolve(envelope.result);
    }
    if (this.buffer.length > REAL_MODEL_RPC_BYTES) this.fail('real_model_owner_line_bound');
  }
  private fail(code: string): void {
    this.failure ??= new HostError(code);
    for (const pending of this.pending.values()) { clearTimeout(pending.timer); pending.reject(this.failure); }
    this.pending.clear();
    // CRITICAL: no stdin.end/destroy, ChildProcess.kill, close, cancel, restart or replay.
  }
  evidence(): JsonObject {
    return { launchedOwnerCount: 1, failure: this.failure?.code ?? null, stdinEndSentMs: this.eofSentMs,
      exit: this.exit, stdioClosed: this.closed, stdoutBytes: this.stdoutBytes, stderrBytesDiscarded: this.stderrBytes,
      requests: this.requests, ownerSignalsSent: 0, ownerRetries: 0, automaticOwnerCleanup: false };
  }
  /** This is the ONLY owner EOF path. Independently reads actual sealed owner receipt BEFORE end.
   * The receipt was produced after actual runtime.retire AND independent runtime.status CLOSED.
   */
  async endAfterVerifiedRetirement(response: unknown, originalGeneration: JsonObject, evidenceDirectory: string): Promise<JsonObject> {
    this.check(); requireReal(!this.eofPermitted && this.exit === null && this.closed === null && this.pending.size === 0 &&
      !this.child.stdin.writableEnded && !this.child.stdin.destroyed, 'real_model_owner_eof_precondition');
    const receipt = objectReal(response), generation = objectReal(receipt.generation);
    requireReal(receipt.state === 'explicit_retirement_verified' && receipt.verifiedBeforeTransportEof === true && receipt.qualified === false &&
      generation.state === 'retired' && generation.retirementDisposition === 'CLOSED', 'real_model_retirement_not_verified');
    for (const key of ['generationId', 'namespaceDigest', 'generationOriginalDeadlineIdentity', 'generationOriginalDeadlineMillis',
      'launchDigest', 'installedToolchainDigest', 'runtimeImplementationIdentity', 'wireImplementationIdentity', 'actualHello'])
      sameReal(generation[key], originalGeneration[key], 'real_model_retired_generation_changed');
    sameReal(generation.retirementReasons, [], 'real_model_retirement_has_reasons');
    sameReal(generation.retirementTransportReasons, [], 'real_model_retirement_transport_has_reasons');
    const ownerEvidence = join(this.selection.runRoot, 'owner/evidence');
    requireReal(absentReal(join(ownerEvidence, 'transport-eof-after-verified-retirement.json')), 'real_model_eof_receipt_preexisted');
    const read = readRealFile(join(ownerEvidence, 'retirement-verified.json'), REAL_MODEL_RPC_BYTES);
    sameReal(jsonReal(read.bytes), receipt, 'real_model_independent_retirement_receipt_changed');
    const observed = this.clock.elapsedMs();
    writeNewRealJson(join(evidenceDirectory, 'independent-retirement-before-owner-eof.json'), {
      receipt, ownerReceiptFile: read.evidence, independentlyObservedMs: observed, ownerInputOpenAtVerification: true,
      ownerExitObservedAtVerification: false, compilerRetirementBasis: 'actual owner runtime.retire plus independent status and SAME CLOSED object; not Codex lifecycle',
    });
    this.check(); this.eofPermitted = true; this.eofSentMs = this.clock.elapsedMs();
    this.child.stdin.end(); // Never called by any failure handler or finally block.
    const closed = await this.clock.wait(() => this.closed ? Promise.resolve(this.closed) :
      new Promise<ExitOwner>(resolve => this.closeWaiters.push(resolve)), 15_000);
    // The asynchronous exit listener updates this field while the await above is pending.
    const exited = this.exit as ExitOwner | null;
    this.check(); requireReal(closed.code === 0 && closed.signal === null && exited?.code === 0, 'real_model_owner_normal_exit_unconfirmed');
    const eof = readRealFile(join(ownerEvidence, 'transport-eof-after-verified-retirement.json'), 64 * 1024);
    const value = objectReal(jsonReal(eof.bytes));
    requireReal(value.state === 'owner_eof_after_independent_verified_retirement' && value.retirementVerifiedBeforeEof === true &&
      value.generationId === originalGeneration.generationId, 'real_model_owner_eof_order_unconfirmed');
    return { retirementObservedMs: observed, stdinEndSentMs: this.eofSentMs, normalExit: this.exit, stdioClosed: closed,
      independentlyReadRetirement: read.evidence, independentlyReadLaterEof: eof.evidence, ownerSignalsSent: 0 };
  }
}
