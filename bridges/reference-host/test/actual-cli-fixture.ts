/** Owner-side helpers for an explicitly approved public-fixture integration run.
 * No fake kernel events, compiler results, credentials or observation witnesses.
 * Helpers have isolated argument/filesystem tests; no Gradle build or model inference lives here.
 */
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { lstat, mkdir, open, readFile, writeFile, type FileHandle } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { promisify } from 'node:util';
import { BrokerError, RestartingJsonLineRpc, type BrokerPort, type JsonObject, type RpcOptions } from '@ktchecker/mcp-preview/rpc';
import { canonical, digest, HostError } from '../src/journal.js';
import type { Registration, Submission, ReviewStatus } from '../src/types.js';

const execute = promisify(execFile);
export const COMPILER_RULE = 'ktc.compiler.common.abstract_member_not_implemented';
export const FIXTURE_PATH = 'Contract.kt';
export const BROKEN_SOURCE = 'interface Contract { fun run(): String }\nclass Missing : Contract\n';
export const REPAIRED_SOURCE = 'interface Contract { fun run(): String }\nclass Missing : Contract { override fun run(): String = "ok" }\n';
export interface PreparedFixture extends Submission {
  binding: Registration['binding']; coverageScope: string; executionAuthorized: boolean;
  profiles: JsonObject; contractVersion: string;
}
export interface OverlayResult { manifest: { snapshotId: string }; headRevision: number; sourceEpoch: number }

export interface ActualDemoArguments {
  readonly launcher: string;
  readonly compilerJavaHome: string;
  readonly evidence: string;
}
export const ACTUAL_DEMO_USAGE = 'demo-cli-codex --allow-inference --cli KT_CHECKER_LAUNCHER --compiler-java-home COMPLETE_COMPILER_JDK --evidence NEW_EVIDENCE_JSON';

function explicitPath(value: unknown, code: string): string {
  if (typeof value !== 'string' || !value.trim() || Buffer.byteLength(value) > 16_384 || /\p{Cc}/u.test(value)) {
    throw new HostError(code);
  }
  return resolve(value);
}

function explicitCompilerJavaHome(value: unknown, code: string): string {
  // The runtime verifier rejects the literal spelling before normalization: collapsing
  // symlink/../jdk could select a different home than the owner's OS path lookup.
  if (typeof value === 'string' && value.split(/[\\/]/u).some(part => part === '.' || part === '..')) {
    throw new HostError('actual_compiler_java_home_dot_segments');
  }
  return explicitPath(value, code);
}

/** No environment/default lookup: selecting a path is not qualification of its JDK contents.
 * The actual CLI independently verifies the complete, explicitly selected compiler JDK.
 */
export function parseActualDemoArgs(args: readonly string[]): Readonly<ActualDemoArguments> {
  const values = new Map<string, string>();
  let allowInference = false;
  for (let i = 0; i < args.length; i++) {
    const flag = args[i];
    if (flag === '--allow-inference') {
      if (allowInference) throw new HostError('actual_demo_duplicate_option');
      allowInference = true;
      continue;
    }
    if (flag !== '--cli' && flag !== '--compiler-java-home' && flag !== '--evidence') {
      throw new HostError('actual_demo_unknown_option');
    }
    if (values.has(flag)) throw new HostError('actual_demo_duplicate_option');
    const value = args[++i];
    if (typeof value !== 'string' || value.startsWith('--')) throw new HostError('actual_demo_option_value_required');
    values.set(flag, flag === '--compiler-java-home' ? explicitCompilerJavaHome(value, 'actual_demo_invalid_path') :
      explicitPath(value, 'actual_demo_invalid_path'));
  }
  if (!allowInference) throw new HostError('actual_demo_inference_approval_required');
  if (!values.has('--cli')) throw new HostError('actual_demo_launcher_required');
  if (!values.has('--compiler-java-home')) throw new HostError('actual_demo_compiler_java_home_required');
  if (!values.has('--evidence')) throw new HostError('actual_demo_evidence_required');
  return Object.freeze({ launcher: values.get('--cli')!, compilerJavaHome: values.get('--compiler-java-home')!, evidence: values.get('--evidence')! });
}

export interface ActualDemoEvidence {
  readonly path: string;
  write(contents: string): Promise<void>;
  close(): Promise<void>;
}

/** Thread deletion and RPC closure never prove native process-group quiescence.
 * Every created run tree is retained, even with zero threads or successful delivery.
 * This reports a conservative policy, not a cleanup permit or a process receipt.
 */
export function actualDemoCleanupEvidence(threadCleanup: Readonly<{ allConfirmed: boolean }> | null = null) {
  return Object.freeze({
    ownedDisposableThreadsDeleted: threadCleanup?.allConfirmed ?? null,
    privateRecoveryStateRetained: true as const,
    runtimeCleanup: Object.freeze({
      state: 'unverified' as const,
      nativeQuiescenceObserved: false as const,
      automaticRunDirectoryDeletionAuthorized: false as const,
      retainedScope: 'entire_private_and_public_run_directory',
      reason: 'rpc_close_and_owned_thread_deletion_do_not_attest_native_quiescence',
      ownerActionRequired: 'manual_owner_reconciliation_before_explicit_cleanup',
    }),
  });
}

/** Reserve a NEW owner-selected evidence file before starting runtime/model work.
 * Writes stay on this single open handle, never reopen/replace an existing pathname.
 * Identity checks detect replacement; they do not claim protection from a malicious owner.
 */
export async function reserveActualDemoEvidence(path: string): Promise<ActualDemoEvidence> {
  const selected = explicitPath(path, 'actual_demo_invalid_evidence_path');
  let opened: FileHandle | undefined;
  try {
    await mkdir(dirname(selected), { recursive: true });
    const handle = await open(selected, 'wx', 0o600);
    opened = handle;
    const original = await handle.stat({ bigint: true });
    const verifyIdentity = async (expectedSize: bigint): Promise<void> => {
      const current = await lstat(selected, { bigint: true }), retained = await handle.stat({ bigint: true });
      if (!original.isFile() || !current.isFile() || current.isSymbolicLink() || !retained.isFile() ||
          current.dev !== original.dev || current.ino !== original.ino || retained.dev !== original.dev || retained.ino !== original.ino ||
          current.nlink !== 1n || retained.nlink !== 1n || current.size !== expectedSize || retained.size !== expectedSize) {
        throw new HostError('actual_demo_evidence_path_changed');
      }
    };
    await verifyIdentity(0n);
    let writePromise: Promise<void> | undefined, closePromise: Promise<void> | undefined;
    return Object.freeze({
      path: selected,
      write(contents: string): Promise<void> {
        if (writePromise || closePromise) return Promise.reject(new HostError('actual_demo_evidence_already_used'));
        writePromise = (async () => {
          await verifyIdentity(0n);
          await handle.writeFile(contents, 'utf8');
          await handle.sync();
          await verifyIdentity(BigInt(Buffer.byteLength(contents)));
        })().catch(error => { throw error instanceof HostError ? error : new HostError('actual_demo_evidence_write_failed'); });
        return writePromise;
      },
      close(): Promise<void> {
        closePromise ??= (async () => {
          if (writePromise) await writePromise.catch(() => {});
          await handle.close();
        })().catch(() => { throw new HostError('actual_demo_evidence_close_failed'); });
        return closePromise;
      },
    });
  } catch (error) {
    await opened?.close().catch(() => {});
    if (error instanceof HostError) throw error;
    throw new HostError((error as NodeJS.ErrnoException).code === 'EEXIST' ? 'actual_demo_evidence_exists' : 'actual_demo_evidence_reservation_failed');
  }
}

export class ActualCliFixture {
  private sequence = 0;
  readonly launcher: string;
  readonly directory: string;
  readonly state: string;
  readonly #compilerJavaHome: string;
  constructor(launcher: string, directory: string, compilerJavaHome: string) {
    this.launcher = explicitPath(launcher, 'actual_cli_launcher_required');
    this.directory = explicitPath(directory, 'actual_cli_directory_required');
    this.state = join(this.directory, 'kernel');
    this.#compilerJavaHome = explicitCompilerJavaHome(compilerJavaHome, 'actual_cli_compiler_java_home_required');
  }
  get compilerJavaHome(): string { return this.#compilerJavaHome; }
  async initialize(): Promise<void> { await mkdir(this.directory, { recursive: true, mode: 0o700 }); }
  private async ownerFile(label: string, value: unknown): Promise<string> {
    const path = join(this.directory, `${++this.sequence}-${label}.json`);
    await writeFile(path, JSON.stringify(value), { mode: 0o600, flag: 'wx' }); return path;
  }
  private outputFile(label: string): string { return join(this.directory, `${++this.sequence}-${label}.json`); }
  private runtimeArguments(args: readonly string[], workers: 0 | 1): string[] {
    if (workers !== 0 && workers !== 1) throw new HostError('actual_cli_invalid_worker_count');
    if (!args.length || args.some(arg => typeof arg !== 'string' || /\p{Cc}/u.test(arg))) throw new HostError('actual_cli_invalid_arguments');
    if (args.some(arg => /^(--compiler-java-home|--state|--workers)(=|$)/.test(arg))) throw new HostError('actual_cli_fixture_option_conflict');
    return [...args, '--state', this.state, '--workers', String(workers), '--compiler-java-home', this.#compilerJavaHome];
  }
  ownerArguments(args: readonly string[]): string[] { return this.runtimeArguments(args, 0); }
  async call(args: readonly string[]): Promise<JsonObject> {
    const runtimeArgs = this.ownerArguments(args);
    let stdout: string;
    try {
      ({ stdout } = await execute(this.launcher, runtimeArgs, {
        encoding: 'utf8', maxBuffer: 16 * 1024 * 1024, timeout: 120_000, windowsHide: true,
      }));
    } catch (error) {
      // An exec error contains argv/private paths and sometimes stdout/stderr. Do not propagate it.
      let code = 'actual_cli_operation_failed';
      try {
        const result = JSON.parse(String((error as { stdout?: string }).stdout ?? '')) as { error?: { code?: string } };
        if (result.error?.code && /^[a-z][a-z0-9_]{0,127}$/.test(result.error.code)) code = 'actual_cli_' + result.error.code;
      } catch { /* retain bounded generic identifier */ }
      throw new HostError(code);
    }
    if (!stdout.trim()) return {}; // --out owner commands return only the explicitly selected file.
    try { return JSON.parse(stdout) as JsonObject; } catch { throw new HostError('actual_cli_non_json_output'); }
  }
  async register(client: string): Promise<Registration> {
    const out = this.outputFile('private-enrollment');
    await this.call(['register', '--workspace', 'public-compiler-delivery', '--worktree', 'public-synthetic-tree',
      '--client', client, '--session', client + '-session', '--lineage', client + '-lineage', '--out', out]);
    const registration = JSON.parse(await readFile(out, 'utf8')) as Registration;
    assert.equal(registration.binding.clientId, client); return registration;
  }
  async enableCompilerRule(registration: Registration): Promise<void> {
    const revision = this.outputFile('owner-policy-revision');
    await this.call(['suppressions', 'inspect', '--client', registration.binding.clientId, '--out', revision]);
    const current = JSON.parse(await readFile(revision, 'utf8')) as { effectiveRules?: Record<string, { enabled?: boolean }> };
    // Consumers deliberately share a worktree and its SHARED owner policy. A's explicit
    // enable is already visible to B; repeating an unchanged policy mutation is an error,
    // not an authorization step or an independent consumer requirement.
    if (current.effectiveRules?.[COMPILER_RULE]?.enabled === true) return;
    await this.call(['suppressions', 'enable', '--client', registration.binding.clientId, '--rule', COMPILER_RULE,
      '--expected', revision, '--owner-action']);
  }
  async overlay(registration: Registration, patchId: string, source: string, previous?: OverlayResult): Promise<OverlayResult> {
    const b = registration.binding;
    const request = await this.ownerFile('public-overlay', {
      workspaceId: b.workspaceId, worktreeId: b.worktreeId, clientId: b.clientId, sessionId: b.sessionId,
      patchId, baseSnapshotId: previous?.manifest.snapshotId ?? null, expectedHeadRevision: previous?.headRevision ?? 0,
      changes: [{ operation: 'put', path: FIXTURE_PATH, content: source }],
    });
    const out = this.outputFile('overlay-result'); await this.call(['overlay', '--request', request, '--out', out]);
    return JSON.parse(await readFile(out, 'utf8')) as OverlayResult;
  }
  async prepare(registration: Registration, snapshot: OverlayResult, patchId: string): Promise<PreparedFixture> {
    const request = await this.ownerFile('compilation-request', {
      clientId: registration.binding.clientId, snapshotId: snapshot.manifest.snapshotId, patchId,
      idempotencyKey: registration.binding.clientId + '-' + patchId,
      compilationFiles: [FIXTURE_PATH], scope: { target: 'jvm', sourceSet: 'main', module: ':public-fixture', compilation: 'main' },
    });
    const out = this.outputFile('private-prepared-review');
    await this.call(['prepare', '--request', request, '--authorize-execution', '--out', out]);
    const prepared = JSON.parse(await readFile(out, 'utf8')) as PreparedFixture;
    assert.equal(prepared.executionAuthorized, true);
    assert.equal(prepared.coverageScope, 'all-catalog-candidates-for-declared-target');
    assert(Object.hasOwn(prepared.profiles, 'kotlin-compiler-jvm'));
    return prepared;
  }
  submission(prepared: PreparedFixture): Submission {
    return { patchId: prepared.patchId, idempotencyKey: prepared.idempotencyKey, input: prepared.input };
  }
  rpcOptions(workers: 0 | 1 = 1): RpcOptions {
    return { command: this.launcher, args: this.runtimeArguments(['rpc'], workers), timeoutMs: 60_000 };
  }
}

/** Fault injection is only between the REAL successful response and its controller caller.
 * The accepted mutation is never fabricated. The first process has zero workers, so the
 * deliberate disconnect cannot orphan an executing compiler child. Subsequent explicit
 * operations start one worker and recover the exact durable submission key.
 */
export class ActualFaultBroker implements BrokerPort {
  private transport: RestartingJsonLineRpc;
  private dropNextSubmit = true;
  private workers: 0 | 1 = 0;
  readonly calls: { method: string; clientId: string | null; idempotencyKey?: string; requestDigest?: string }[] = [];
  acceptedBeforeInjectedLoss: { reviewId: string; statusDigest: string } | null = null;
  disconnects = 0;
  constructor(private readonly fixture: ActualCliFixture) { this.transport = new RestartingJsonLineRpc(fixture.rpcOptions(0)); }
  async request<T = JsonObject>(method: string, params: JsonObject): Promise<T> {
    const credential = params.credential as { clientId?: string } | undefined;
    this.calls.push({ method, clientId: credential?.clientId ?? null,
      ...(method === 'review.submit' ? { idempotencyKey: String(params.idempotencyKey),
        requestDigest: digest(canonical({ patchId: params.patchId, idempotencyKey: params.idempotencyKey, input: params.input })) } : {}) });
    const result = await this.transport.request<T>(method, params);
    if (method === 'review.submit' && this.dropNextSubmit) {
      this.dropNextSubmit = false;
      const accepted = result as ReviewStatus;
      assert.equal(typeof accepted.reviewId, 'string');
      this.acceptedBeforeInjectedLoss = { reviewId: accepted.reviewId, statusDigest: digest(canonical(accepted)) };
      await this.disconnect();
      throw new BrokerError('demonstration_submit_response_dropped', 'Injected controller response loss after actual broker acceptance');
    }
    return result;
  }
  async disconnect(workers: 0 | 1 = 1): Promise<void> {
    await this.transport.close(); this.disconnects++; this.workers = workers;
    this.transport = new RestartingJsonLineRpc(this.fixture.rpcOptions(this.workers));
  }
  async close(): Promise<void> { await this.transport.close(); }
}
