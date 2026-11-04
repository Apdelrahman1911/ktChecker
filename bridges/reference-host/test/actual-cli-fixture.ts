/** Owner-side helpers for an explicitly approved public-fixture integration run.
 * No fake kernel events, compiler results, credentials or observation witnesses.
 * Never executed by npm test; no Gradle build or model inference lives here.
 */
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
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

export class ActualCliFixture {
  private sequence = 0;
  readonly state: string;
  constructor(readonly launcher: string, readonly directory: string) {
    this.launcher = resolve(launcher); this.state = join(directory, 'kernel');
  }
  async initialize(): Promise<void> { await mkdir(this.directory, { recursive: true, mode: 0o700 }); }
  private async ownerFile(label: string, value: unknown): Promise<string> {
    const path = join(this.directory, `${++this.sequence}-${label}.json`);
    await writeFile(path, JSON.stringify(value), { mode: 0o600, flag: 'wx' }); return path;
  }
  private outputFile(label: string): string { return join(this.directory, `${++this.sequence}-${label}.json`); }
  async call(args: string[]): Promise<JsonObject> {
    let stdout: string;
    try {
      ({ stdout } = await execute(this.launcher, [...args, '--state', this.state, '--workers', '0'], {
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
  rpcOptions(workers = 1): RpcOptions {
    return { command: this.launcher, args: ['rpc', '--state', this.state, '--workers', String(workers)], timeoutMs: 60_000 };
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
  private workers = 0;
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
