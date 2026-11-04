/** Owner fixture controls only. A responsive supervisor owns the deadline and
 * evidence; synchronous version/Git/store operations and cleanup run in a child.
 * No OS-hard-real-time, remote-inference cancellation or native quiescence claim.
 */
import type { ChildProcess } from 'node:child_process';
import { digest, HostError } from '../src/journal.js';
import type { ApplicationRequestEvidence, CodexTurnResult } from '../src/codex-app-server.js';
import type { ActualDemoEvidence } from './actual-cli-fixture.js';

export const MIN_ACTUAL_DEMO_RUNTIME_MS = 1_000;
export const MAX_ACTUAL_DEMO_RUNTIME_MS = 1_800_000;
const MAX_MESSAGES = 256, MAX_PROGRESS_BYTES = 4_096, MAX_REPORT_PACKETS = 6;
export const MAX_ACTUAL_DEMO_REPORT_BYTES = 8 * 1_024 * 1_024;
const HASH = /^sha256:[0-9a-f]{64}$/;
export function actualDemoRuntime(value: unknown): number {
  if (typeof value !== 'string' || !/^[1-9][0-9]{0,6}$/.test(value)) throw new HostError('actual_demo_invalid_max_runtime');
  const milliseconds = Number(value);
  if (!Number.isSafeInteger(milliseconds) || milliseconds < MIN_ACTUAL_DEMO_RUNTIME_MS || milliseconds > MAX_ACTUAL_DEMO_RUNTIME_MS) {
    throw new HostError('actual_demo_invalid_max_runtime');
  }
  return milliseconds;
}
export interface DemoClock {
  now(): number;
  set(delayMs: number, callback: () => void): unknown;
  clear(timer: unknown): void;
}
const realClock: DemoClock = { now: () => performance.now(), set: (ms, callback) => setTimeout(callback, ms),
  clear: timer => clearTimeout(timer as NodeJS.Timeout) };
export function actualDemoBudget(maxRuntimeMs: number) {
  if (typeof maxRuntimeMs !== 'number' || !Number.isSafeInteger(maxRuntimeMs)) throw new HostError('actual_demo_invalid_max_runtime');
  actualDemoRuntime(String(maxRuntimeMs));
  const terminationGraceMs = Math.min(1_000, Math.floor(maxRuntimeMs / 10));
  const finalizationReserveMs = Math.min(5_000, Math.floor(maxRuntimeMs / 5));
  return Object.freeze({ maxRuntimeMs, workerAndCleanupMs: maxRuntimeMs - terminationGraceMs - finalizationReserveMs,
    terminationGraceMs, finalizationReserveMs });
}

const STAGES = new Set([
  'verify_codex_executable_version', 'capture_actual_application_bytecode_identity', 'owner_fixture_enrollment_and_preparation',
  'active_turn_and_actual_submit_recovery', 'waiting_for_actual_p1_result', 'actual_p1_diagnosis_and_privilege_negatives',
  'active_diagnostic_observation', 'independent_consumer_observation', 'owner_p2_repair_and_durable_submission',
  'p2_actual_compiler_finishes_while_consumer_disconnected', 'resumed_p2_diagnosis_observation',
  'verified', 'owned_disposable_cleanup_unconfirmed', 'cleanup',
]);
export type DemoProgress = { kind: 'stage'; stage: string } |
  { kind: 'creation_started'; attempt: number } |
  { kind: 'creation_confirmed'; attempt: number; threadIdDigest: string } |
  { kind: 'deletion_started'; threadIdDigest: string } |
  { kind: 'deletion_finished'; threadIdDigest: string; outcome: 'confirmed_deleted' | 'unconfirmed' } |
  { kind: 'cleanup_finished'; allConfirmed: boolean };
type Message = { kind: 'progress'; sequence: number; progress: DemoProgress } | { kind: 'result' | 'checkpoint'; sequence: number; report: Record<string, unknown> };
const REPORT_KEYS = new Set([
  'schema', 'date', 'evidenceScope', 'contractVersion', 'model', 'allowProviderModelFallback', 'internalInferenceRequestTrace',
  'actualKernelIntegration', 'actualKotlinCompilerRun', 'sourceAuthority', 'ownedDisposableThreadsDeleted', 'privateRecoveryStateRetained',
  'runtimeCleanup', 'fullProjectModel', 'releaseQualified', 'guardedActionPerformed', 'modelPollingCalls', 'node', 'codex',
  'compilerJdkSelection', 'compilerJdkAmbientFallback', 'compilerJdkValidationBoundary', 'sourceFixture', 'ownerExplicitlyEnabledRule',
  'candidateCoverageScope', 'phases', 'negatives', 'verified', 'stage', 'runIdentity', 'p1SnapshotId', 'lastCompilerCheckpoint',
  'activeTurnJoined', 'independentConsumers', 'p2', 'transportRecovery', 'backgroundEventPolls', 'adapterObservationCalls',
  'failure', 'lastProblems', 'failureByteTelemetry', 'threadCleanup', 'initialBarrier', 'deliveryRpcConfirmations', 'deliveryReceiptJoins',
]);
function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value) || Object.getPrototypeOf(value) !== Object.prototype) throw new HostError('actual_demo_ipc_shape');
  return value as Record<string, unknown>;
}
function exactKeys(value: Record<string, unknown>, names: readonly string[]): void {
  const keys = Reflect.ownKeys(value);
  if (keys.length !== names.length || keys.some(key => typeof key !== 'string' || !names.includes(key))) throw new HostError('actual_demo_ipc_fields');
  // Refuse accessors before callers read any value or JSON.stringify can run.
  if (keys.some(key => {
    const property = Object.getOwnPropertyDescriptor(value, key)!;
    return !property.enumerable || !Object.hasOwn(property, 'value');
  })) throw new HostError('actual_demo_ipc_fields');
}
function progressValue(value: unknown): DemoProgress {
  const data = object(value);
  const kind = Object.getOwnPropertyDescriptor(data, 'kind');
  if (!kind || !Object.hasOwn(kind, 'value')) throw new HostError('actual_demo_ipc_fields');
  if (data.kind === 'stage') {
    exactKeys(data, ['kind', 'stage']);
    if (typeof data.stage !== 'string' || !STAGES.has(data.stage)) throw new HostError('actual_demo_ipc_stage');
  } else if (data.kind === 'creation_started' || data.kind === 'creation_confirmed') {
    exactKeys(data, data.kind === 'creation_started' ? ['kind', 'attempt'] : ['kind', 'attempt', 'threadIdDigest']);
    if (!Number.isSafeInteger(data.attempt) || (data.attempt as number) < 1 || (data.attempt as number) > 8) throw new HostError('actual_demo_ipc_creation');
  } else if (data.kind === 'deletion_started' || data.kind === 'deletion_finished') {
    exactKeys(data, data.kind === 'deletion_started' ? ['kind', 'threadIdDigest'] : ['kind', 'threadIdDigest', 'outcome']);
    if (data.kind === 'deletion_finished' && data.outcome !== 'confirmed_deleted' && data.outcome !== 'unconfirmed') throw new HostError('actual_demo_ipc_deletion');
  } else if (data.kind === 'cleanup_finished') {
    exactKeys(data, ['kind', 'allConfirmed']);
    if (typeof data.allConfirmed !== 'boolean') throw new HostError('actual_demo_ipc_cleanup');
  } else throw new HostError('actual_demo_ipc_kind');
  if ('threadIdDigest' in data && (typeof data.threadIdDigest !== 'string' || !HASH.test(data.threadIdDigest))) throw new HostError('actual_demo_ipc_thread');
  return Object.freeze({ ...data }) as DemoProgress;
}

/** Validate/copy bounded JSON BEFORE serializing it. Accessors, toJSON and unknown
 * report fields are refused, rather than invoking them during a redact-later pass.
 * Report text is already public-fixture evidence, not arbitrary RPC request data.
 */
function boundedReport(value: unknown): Record<string, unknown> {
  const report = object(value);
  if (Object.keys(report).some(key => !REPORT_KEYS.has(key))) throw new HostError('actual_demo_report_fields');
  let bytes = 0, nodes = 0;
  const account = (size: number) => { bytes += size; if (bytes > MAX_ACTUAL_DEMO_REPORT_BYTES) throw new HostError('actual_demo_report_bound'); };
  const stringBytes = (text: string) => {
    if (text.length > MAX_ACTUAL_DEMO_REPORT_BYTES) throw new HostError('actual_demo_report_bound');
    account(2 + Buffer.byteLength(text));
    for (let i = 0; i < text.length; i++) {
      const code = text.charCodeAt(i);
      if (code < 32) account(code === 8 || code === 9 || code === 10 || code === 12 || code === 13 ? 1 : 5);
      else if (code === 34 || code === 92) account(1);
      else if (code >= 0xd800 && code <= 0xdfff) {
        if (code <= 0xdbff && i + 1 < text.length && text.charCodeAt(i + 1) >= 0xdc00 && text.charCodeAt(i + 1) <= 0xdfff) i++;
        else account(3); // JSON escapes an unpaired surrogate instead of UTF-8 replacement.
      }
    }
  };
  const copy = (current: unknown, depth: number): unknown => {
    if (++nodes > 150_000 || depth > 24) throw new HostError('actual_demo_report_bound');
    if (current === null || typeof current === 'boolean') { account(5); return current; }
    if (typeof current === 'number' && Number.isFinite(current)) { account(32); return current; }
    if (typeof current === 'string') { stringBytes(current); return current; }
    if (Array.isArray(current)) {
      if (current.length > 4_096) throw new HostError('actual_demo_report_bound');
      account(2 + current.length);
      return Array.from({ length: current.length }, (_, index) => {
        const property = Object.getOwnPropertyDescriptor(current, String(index));
        if (!property || !('value' in property)) throw new HostError('actual_demo_report_accessor');
        return copy(property.value, depth + 1);
      });
    }
    const record = object(current), keys = Object.keys(record), result: Record<string, unknown> = {};
    if (keys.length > 4_096) throw new HostError('actual_demo_report_bound');
    account(2 + keys.length * 2);
    for (const key of keys) {
      const property = Object.getOwnPropertyDescriptor(record, key);
      if (!property || !('value' in property) || ['__proto__', 'constructor', 'toJSON', 'credential', 'token', 'authorization'].includes(key)) {
        throw new HostError('actual_demo_report_fields');
      }
      stringBytes(key); result[key] = copy(property.value, depth + 1);
    }
    return result;
  };
  return copy(report, 0) as Record<string, unknown>;
}
export function actualDemoProgressPacket(sequence: number, progress: DemoProgress): string {
  const packet = JSON.stringify({ kind: 'progress', sequence, progress: progressValue(progress) });
  if (Buffer.byteLength(packet) > MAX_PROGRESS_BYTES) throw new HostError('actual_demo_ipc_bound');
  return packet;
}
export function actualDemoResultPacket(sequence: number, report: Record<string, unknown>): string {
  return JSON.stringify({ kind: 'result', sequence, report: boundedReport(report) });
}
export function actualDemoCheckpointPacket(sequence: number, report: Record<string, unknown>): string {
  return JSON.stringify({ kind: 'checkpoint', sequence, report: { ...boundedReport(report), verified: false } });
}
function messageValue(packet: unknown): Message {
  if (typeof packet !== 'string' || packet.length > MAX_ACTUAL_DEMO_REPORT_BYTES + 128 || Buffer.byteLength(packet) > MAX_ACTUAL_DEMO_REPORT_BYTES + 128) {
    throw new HostError('actual_demo_ipc_bound');
  }
  const data = object(JSON.parse(packet));
  if (!Number.isSafeInteger(data.sequence) || (data.sequence as number) < 1 || (data.sequence as number) > MAX_MESSAGES) throw new HostError('actual_demo_ipc_sequence');
  if (data.kind === 'progress') {
    exactKeys(data, ['kind', 'sequence', 'progress']);
    if (Buffer.byteLength(packet) > MAX_PROGRESS_BYTES) throw new HostError('actual_demo_ipc_bound');
    return { kind: data.kind, sequence: data.sequence as number, progress: progressValue(data.progress) };
  }
  if (data.kind === 'result' || data.kind === 'checkpoint') {
    exactKeys(data, ['kind', 'sequence', 'report']);
    return { kind: data.kind, sequence: data.sequence as number, report: boundedReport(data.report) };
  }
  throw new HostError('actual_demo_ipc_kind');
}

interface TrackedThread { digest: string; deletion: 'not_attempted' | 'pending_unknown' | 'confirmed_deleted' | 'unconfirmed' }
class ProgressTracker {
  stage = 'evidence_reservation';
  private readonly attempts = new Map<number, string | null>();
  private readonly threads = new Map<string, TrackedThread>();
  private cleanupFinished = false;
  private cleanupAllConfirmed = false;
  apply(progress: DemoProgress): void {
    if (progress.kind === 'stage') this.stage = progress.stage;
    else if (progress.kind === 'creation_started') {
      if (this.cleanupFinished || progress.attempt !== this.attempts.size + 1) throw new HostError('actual_demo_creation_order');
      this.attempts.set(progress.attempt, null);
    } else if (progress.kind === 'creation_confirmed') {
      if (this.cleanupFinished || !this.attempts.has(progress.attempt) || this.attempts.get(progress.attempt) !== null || this.threads.has(progress.threadIdDigest)) throw new HostError('actual_demo_creation_order');
      this.attempts.set(progress.attempt, progress.threadIdDigest);
      this.threads.set(progress.threadIdDigest, { digest: progress.threadIdDigest, deletion: 'not_attempted' });
    } else if (progress.kind === 'deletion_started' || progress.kind === 'deletion_finished') {
      const thread = this.threads.get(progress.threadIdDigest);
      if (!thread || this.cleanupFinished) throw new HostError('actual_demo_deletion_order');
      if (progress.kind === 'deletion_started') {
        if (thread.deletion !== 'not_attempted') throw new HostError('actual_demo_deletion_order');
        thread.deletion = 'pending_unknown';
      } else {
        if (thread.deletion !== 'pending_unknown') throw new HostError('actual_demo_deletion_order');
        thread.deletion = progress.outcome;
      }
    } else {
      if (this.cleanupFinished || (progress.allConfirmed && ([...this.attempts.values()].some(value => value === null) ||
          [...this.threads.values()].some(thread => thread.deletion !== 'confirmed_deleted')))) throw new HostError('actual_demo_cleanup_order');
      this.cleanupFinished = true;
      this.cleanupAllConfirmed = progress.allConfirmed;
    }
  }
  snapshot() {
    const threads = [...this.threads.values()];
    return { creationAttempts: this.attempts.size, knownOwnedThreads: threads.length,
      unknownCreationOutcomes: [...this.attempts.values()].filter(value => value === null).length,
      confirmedDeleted: threads.filter(thread => thread.deletion === 'confirmed_deleted').length,
      deletionUnconfirmed: threads.filter(thread => thread.deletion !== 'confirmed_deleted').length,
      cleanupReturned: this.cleanupFinished,
      allConfirmed: this.cleanupFinished && this.cleanupAllConfirmed && [...this.attempts.values()].every(value => value !== null) && threads.every(thread => thread.deletion === 'confirmed_deleted'),
      confirmationScope: 'supervisor_received_owned_thread_rpc_progress_not_native_quiescence',
      threads: threads.map(thread => ({ threadIdDigest: thread.digest, deletionOutcome: thread.deletion })) };
  }
}
export interface ActualDemoWorker {
  begin?(): void;
  onMessage(callback: (packet: unknown) => void): void;
  onExit(callback: (code: number | null) => void): void;
  onFailure?(callback: () => void): void;
  acknowledge(sequence: number): void;
  terminate(signal: 'SIGTERM' | 'SIGKILL'): void;
}
/** Direct ChildProcess only. No pgid caching, discovery, process-tree traversal or
 * signal after observed exit. Descendant/native/remote quiescence remains unknown.
 */
export function directActualDemoWorker(child: ChildProcess): ActualDemoWorker {
  let exited = child.exitCode !== null || child.signalCode !== null;
  let exitCode: number | null = child.exitCode, notified = exited;
  let onExit: ((code: number | null) => void) | undefined;
  let failed = false, onFailure: (() => void) | undefined;
  const ended = (code: number | null) => {
    if (notified) return;
    notified = true; exited = true; exitCode = code; onExit?.(code);
  };
  child.once('exit', ended);
  // ChildProcess 'error' can mean failed kill/send, NOT an observed process exit.
  // Keep direct termination eligible until the actual exit event/state is known.
  child.on('error', () => { failed = true; onFailure?.(); });
  return {
    onMessage: callback => { child.on('message', callback); },
    onExit: callback => { onExit = callback; if (notified) callback(exitCode); },
    onFailure: callback => { onFailure = callback; if (failed) callback(); },
    acknowledge: sequence => { if (child.connected && !exited) child.send({ kind: 'continue', sequence }, () => {}); },
    terminate: signal => { if (!exited && child.exitCode === null && child.signalCode === null) child.kill(signal); },
  };
}
export interface SupervisorOutcome { readonly exitCode: 0 | 1 | 2; readonly verified: boolean; readonly failure: string | null }
export interface ActualDemoSupervisorOptions {
  maxRuntimeMs: number;
  initialReport: Record<string, unknown>;
  reserve(): Promise<ActualDemoEvidence>;
  start(signal: AbortSignal): Promise<ActualDemoWorker>;
  publish(summary: Record<string, unknown>): Promise<void>;
  clock?: DemoClock;
}
function successShape(report: Record<string, unknown>, progress: ProgressTracker): boolean {
  const phases = report.phases;
  const names = ['actual_background_compiler_diagnosis_joined_active_codex_turn', 'independent_consumer_own_thread_and_own_diagnostic_observation',
    'owner_repair_p2_rechecked_and_consumed_after_owned_thread_and_broker_resume'];
  const barrier = report.initialBarrier as Record<string, unknown> | undefined;
  const receipts = report.deliveryRpcConfirmations as Record<string, unknown> | undefined;
  return report.verified === true && (report.failure === undefined || report.failure === null) && report.model === 'gpt-6-astra' && report.allowProviderModelFallback === false &&
    report.actualKernelIntegration === true && report.actualKotlinCompilerRun === true && report.internalInferenceRequestTrace === false &&
    report.releaseQualified === false && report.fullProjectModel === false && report.guardedActionPerformed === false &&
    Array.isArray(phases) && phases.length === 3 && phases.every((phase, i) => phase !== null && typeof phase === 'object' &&
      !Array.isArray(phase) && phase.phase === names[i]) &&
    barrier?.invocations === 1 && barrier.completed === true && barrier.failure === null &&
    receipts?.complete === true && Array.isArray(report.deliveryReceiptJoins) && report.deliveryReceiptJoins.length === 3 &&
    progress.snapshot().allConfirmed && progress.snapshot().knownOwnedThreads === 2;
}

/** The absolute timer is armed BEFORE reservation/start/finalization is attempted.
 * Even a never-settling asynchronous I/O operation loses to it. The executable
 * must force exit with this outcome; ordinary Node event-loop drain is not a cap.
 */
export function superviseActualDemo(options: ActualDemoSupervisorOptions): Promise<SupervisorOutcome> {
  const clock = options.clock ?? realClock, budget = actualDemoBudget(options.maxRuntimeMs), started = clock.now();
  const workDeadline = started + budget.workerAndCleanupMs, absoluteDeadline = started + budget.maxRuntimeMs;
  const signal = new AbortController(), tracker = new ProgressTracker();
  let evidence: ActualDemoEvidence | undefined, worker: ActualDemoWorker | undefined, workerExited = false;
  let report: Record<string, unknown> | undefined, sequence = 0, failure: string | null = null;
  let resultReceived = false, reportPackets = 0;
  let finalizing = false, finished = false, summaryStarted = false, exitCode: 0 | 1 | 2 = 1;
  let graceTimer: unknown;
  const terminationRequests: string[] = [];
  return new Promise(resolve => {
    const finish = (code: 0 | 1 | 2, problem: string | null) => {
      if (finished) return;
      finished = true; signal.abort(); clock.clear(absoluteTimer); clock.clear(workTimer); if (graceTimer !== undefined) clock.clear(graceTimer);
      resolve(Object.freeze({ exitCode: code, verified: code === 0, failure: problem }));
    };
    const terminate = (which: 'SIGTERM' | 'SIGKILL') => {
      try { if (worker && !workerExited && !terminationRequests.includes(which)) { terminationRequests.push(which); worker.terminate(which); } }
      catch { /* No invented termination receipt. */ }
    };
    const summary = (verified: boolean) => ({ evidenceScope: options.initialReport.evidenceScope, verified,
      phases: Array.isArray(report?.phases) ? report.phases.length : 0, failure,
      actualKotlinCompilerRun: report?.actualKotlinCompilerRun === true });
    const expire = () => {
      if (finished) return;
      failure = finalizing ? 'actual_demo_finalization_deadline' : 'actual_demo_controller_deadline';
      signal.abort(); terminate('SIGKILL');
      // Best effort only: never await a hung stdout/file write beyond the cap.
      if (!summaryStarted) { summaryStarted = true; void Promise.resolve().then(() => options.publish(summary(false))).catch(() => {}); }
      finish(1, failure);
    };
    const absoluteTimer = clock.set(budget.maxRuntimeMs, expire);
    const finalize = () => {
      if (finished || finalizing) return;
      if (clock.now() >= absoluteDeadline) { expire(); return; }
      finalizing = true;
      const candidate = failure === null && exitCode === 0 && report !== undefined;
      const retained: Record<string, unknown> = { ...options.initialReport, ...(report ?? {}), verified: candidate,
        stage: failure ? tracker.stage : report?.stage ?? tracker.stage,
        ...(failure ? { failure } : {}),
        supervisor: { ...budget, controllerDeadlineScope: 'responsive_node_supervisor_not_os_hard_real_time',
          budgetIncludesWorkCleanupTerminationAndFinalization: true, workerExitObserved: workerExited, terminationRequests: [...terminationRequests],
          finalResultPacketReceived: resultReceived, nonFinalCheckpointsReceived: reportPackets - (resultReceived ? 1 : 0),
          terminationScope: 'directly_owned_worker_only_descendant_and_remote_quiescence_unverified',
          evidenceSuccessRule: 'complete_evidence_AND_verified_final_stdout_AND_exit_0',
          evidenceFileAloneSufficient: false, ownedThreadProgress: tracker.snapshot() } };
      if (!candidate) retained.ownedDisposableThreadsDeleted = tracker.snapshot().allConfirmed ? true : null;
      void (async () => {
        try {
          if (evidence) { await evidence.write(JSON.stringify(retained, null, 2) + '\n'); await evidence.close(); }
          else if (!failure) { failure = 'actual_demo_evidence_unavailable'; exitCode = 1; }
          if (finished) return;
          if (clock.now() >= absoluteDeadline) { expire(); return; }
          summaryStarted = true;
          await options.publish(summary(candidate && failure === null));
          if (finished) return;
          if (clock.now() >= absoluteDeadline) { expire(); return; }
          finish(failure === null && candidate ? 0 : exitCode === 2 ? 2 : 1, failure);
        } catch {
          if (finished) return;
          failure = 'actual_demo_finalization_failed';
          // A failed close must not cause another unbounded close-and-retry chain.
          if (!summaryStarted) { summaryStarted = true; void Promise.resolve().then(() => options.publish(summary(false))).catch(() => {}); }
          finish(1, failure);
        }
      })();
    };
    const stop = (problem: string, code: 1 | 2 = 1) => {
      if (finished || finalizing) return;
      failure ??= problem; exitCode = code; signal.abort();
      if (!worker || workerExited) { finalize(); return; }
      terminate('SIGTERM');
      if (graceTimer === undefined) graceTimer = clock.set(Math.min(budget.terminationGraceMs, Math.max(0, absoluteDeadline - clock.now() - budget.finalizationReserveMs)), () => {
        terminate('SIGKILL'); finalize();
      });
    };
    const workTimer = clock.set(budget.workerAndCleanupMs, () => stop('actual_demo_controller_deadline'));
    const receive = (packet: unknown) => {
      if (finished || finalizing || failure !== null) return;
      if (clock.now() >= workDeadline) { stop('actual_demo_controller_deadline'); return; } // Latch before a late success.
      try {
        const message = messageValue(packet);
        if (message.sequence !== ++sequence || resultReceived) throw new HostError('actual_demo_ipc_order');
        if (message.kind === 'progress') tracker.apply(message.progress);
        else {
          if (reportPackets >= MAX_REPORT_PACKETS) throw new HostError('actual_demo_report_packet_bound');
          reportPackets++;
          resultReceived = message.kind === 'result';
          report = resultReceived ? message.report : { ...message.report, verified: false };
        }
        worker!.acknowledge(sequence);
      } catch { stop('actual_demo_worker_protocol'); }
    };
    void (async () => {
      try {
        const reserved = await options.reserve();
        if (finished || finalizing || failure !== null || clock.now() >= workDeadline) {
          void reserved.close().catch(() => {});
          if (!finished && !finalizing) stop('actual_demo_controller_deadline');
          return;
        }
        evidence = reserved;
        const child = await options.start(signal.signal);
        worker = child;
        if (finished || finalizing || failure !== null || clock.now() >= workDeadline) {
          terminate('SIGKILL');
          if (!finished && !finalizing) stop('actual_demo_controller_deadline');
          return;
        }
        child.onMessage(receive);
        child.onExit(code => {
          if (workerExited) return;
          workerExited = true;
          if (finished || finalizing) return;
          if (clock.now() >= workDeadline) failure ??= 'actual_demo_controller_deadline';
          if (!failure && (code !== 0 || !resultReceived || !report || !successShape(report, tracker))) {
            failure = report?.verified === false && typeof report.failure === 'string' && /^[a-z][a-z0-9_]{0,127}$/.test(report.failure)
              ? report.failure : 'actual_demo_worker_unverified';
          }
          exitCode = failure ? 1 : 0;
          finalize();
        });
        child.onFailure?.(() => stop('actual_demo_worker_error'));
        if (!finished && !finalizing && !failure) child.begin?.();
      } catch (error) {
        if (finished || finalizing) return;
        const code = error instanceof HostError && ['actual_demo_evidence_exists', 'actual_demo_evidence_path_changed', 'actual_demo_evidence_reservation_failed'].includes(error.code)
          ? error.code : 'actual_demo_worker_start_failed';
        stop(code, evidence ? 1 : 2);
      }
    })();
  });
}

/** Await the supervisor's receipt of an intent BEFORE starting thread creation or
 * deletion. Only one packet can be in flight; no unbounded process.send queue.
 */
export class ActualDemoReporter {
  private sequence = 0;
  private reportPackets = 0;
  private resultSent = false;
  private inFlight: { sequence: number; resolve(): void; reject(error: Error): void } | null = null;
  constructor(private readonly channel: {
    send(packet: string, callback: (error: Error | null) => void): void;
    onMessage(callback: (packet: unknown) => void): void;
  }) {
    channel.onMessage(packet => {
      let value: Record<string, unknown>;
      try { value = object(packet); exactKeys(value, ['kind', 'sequence']); }
      catch { return; }
      if (value.kind !== 'continue' || !this.inFlight || value.sequence !== this.inFlight.sequence) return;
      const waiting = this.inFlight; this.inFlight = null; waiting.resolve();
    });
  }
  progress(progress: DemoProgress): Promise<void> { return this.send(actualDemoProgressPacket(this.next('progress'), progress)); }
  checkpoint(report: Record<string, unknown>): Promise<void> { return this.send(actualDemoCheckpointPacket(this.next('checkpoint'), report)); }
  result(report: Record<string, unknown>): Promise<void> { return this.send(actualDemoResultPacket(this.next('result'), report)); }
  private next(kind: 'progress' | 'checkpoint' | 'result'): number {
    if (this.inFlight || this.sequence >= MAX_MESSAGES || this.resultSent) throw new HostError('actual_demo_reporter_bound');
    if (kind !== 'progress') {
      if (this.reportPackets >= MAX_REPORT_PACKETS) throw new HostError('actual_demo_report_packet_bound');
      this.reportPackets++;
      if (kind === 'result') this.resultSent = true;
    }
    return ++this.sequence;
  }
  private send(packet: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const waiting = { sequence: this.sequence, resolve, reject };
      this.inFlight = waiting;
      const failed = () => {
        if (this.inFlight === waiting) { this.inFlight = null; reject(new HostError('actual_demo_supervisor_disconnected')); }
      };
      try { this.channel.send(packet, error => { if (error) failed(); }); }
      catch { failed(); }
    });
  }
}

function opaque(value: unknown): string {
  if (typeof value !== 'string' || !/^[a-zA-Z0-9_.:-]{1,256}$/.test(value)) throw new HostError('actual_demo_barrier_binding');
  return value;
}
/** Handler errors are swallowed by CodexAppServer's server-request adapter, so
 * the latch is permanent and is rechecked independently through final cleanup.
 */
export class ExactlyOnceDemoBarrier {
  private count = 0;
  private failure: string | null = null;
  private released = false;
  private handlerCompleted = false;
  private completed = false;
  private confirmedTurn: string | null = null;
  private toolTurn: string | null = null;
  private application: Readonly<ApplicationRequestEvidence> | null = null;
  private releaseHeld!: () => void;
  private enter!: () => void;
  private readonly held = new Promise<void>(resolve => { this.releaseHeld = resolve; });
  private readonly entered = new Promise<void>(resolve => { this.enter = resolve; });
  constructor(private readonly ownedThread: string, private readonly clock: DemoClock = realClock) { opaque(ownedThread); }
  private fail(code: string): void { this.failure ??= code; this.release(); this.enter(); }
  assertHealthy(): void { if (this.failure) throw new HostError(this.failure); }
  release(): void { if (!this.released) { this.released = true; this.releaseHeld(); } }
  recordApplication(evidence: ApplicationRequestEvidence): void {
    try {
      if (this.application || evidence.method !== 'turn/start' || !HASH.test(evidence.serializedRequestDigest) ||
          typeof evidence.sentAt !== 'string' || evidence.sentAt.length > 40 || !Number.isFinite(Date.parse(evidence.sentAt))) throw new HostError('actual_demo_barrier_application');
      this.application = Object.freeze({ applicationRequestId: opaque(evidence.applicationRequestId), method: 'turn/start',
        serializedRequestDigest: evidence.serializedRequestDigest, sentAt: evidence.sentAt });
    } catch { this.fail('actual_demo_barrier_application'); this.assertHealthy(); }
  }
  confirmTurn(turnId: string): void {
    try {
      if (this.confirmedTurn !== null || (this.toolTurn !== null && this.toolTurn !== turnId)) throw new HostError('actual_demo_barrier_binding');
      this.confirmedTurn = opaque(turnId);
    } catch { this.fail('actual_demo_barrier_binding'); this.assertHealthy(); }
  }
  async invoke(params: Record<string, unknown>): Promise<Record<string, unknown>> {
    // Count and poison on the second entry BEFORE the first await (including
    // duplicate calls after release or after an earlier apparent completion).
    this.count++; this.enter();
    if (this.count !== 1) { this.fail('actual_demo_barrier_duplicate'); this.assertHealthy(); }
    try {
      this.assertHealthy();
      if (params.threadId !== this.ownedThread || params.tool !== 'hold_for_background_review') throw new HostError('actual_demo_barrier_binding');
      if (params.turnId !== undefined) {
        this.toolTurn = opaque(params.turnId);
        if (this.confirmedTurn !== null && this.toolTurn !== this.confirmedTurn) throw new HostError('actual_demo_barrier_binding');
      }
      await this.held;
      this.assertHealthy(); this.handlerCompleted = true;
      return { success: true, contentItems: [{ type: 'inputText', text: 'External compiler review is queued. Consume the ktchecker output using the diagnostic JSON contract; no polling is needed.' }] };
    } catch { this.fail(this.failure ?? 'actual_demo_barrier_handler_failed'); this.assertHealthy(); throw new HostError('actual_demo_barrier_handler_failed'); }
  }
  async waitForInvocation(timeoutMs: number): Promise<void> {
    let timer: unknown;
    try {
      await Promise.race([this.entered, new Promise<void>(resolve => {
        timer = this.clock.set(timeoutMs, () => { this.fail('actual_demo_barrier_not_invoked'); resolve(); });
      })]);
      this.assertHealthy();
    } finally { if (timer !== undefined) this.clock.clear(timer); }
  }
  confirmCompleted(result: CodexTurnResult): void {
    this.assertHealthy();
    const application = result?.applicationRequest;
    if (this.count !== 1 || !this.released || !this.handlerCompleted || !this.confirmedTurn || !this.application ||
        !application || result.threadId !== this.ownedThread || result.turnId !== this.confirmedTurn || result.status !== 'completed' ||
        application.applicationRequestId !== this.application.applicationRequestId || application.method !== this.application.method ||
        application.serializedRequestDigest !== this.application.serializedRequestDigest || application.sentAt !== this.application.sentAt) {
      this.fail('actual_demo_barrier_not_completed'); this.assertHealthy();
    }
    this.completed = true;
  }
  snapshot() {
    return Object.freeze({ invocations: this.count, released: this.released, handlerCompleted: this.handlerCompleted,
      completed: this.completed && !this.failure, failure: this.failure, ownedThreadIdDigest: digest(this.ownedThread),
      confirmedTurnIdDigest: this.confirmedTurn ? digest(this.confirmedTurn) : null,
      toolTurnIdDigest: this.toolTurn ? digest(this.toolTurn) : null,
      toolTurnBinding: this.toolTurn ? 'supplied_and_checked_against_confirmed_turn' : 'not_supplied_by_tool_request',
      applicationRequest: this.application });
  }
}
