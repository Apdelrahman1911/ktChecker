import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { digest, HostError } from './journal.js';

type ObjectValue = Record<string, unknown>;
function object(value: unknown): value is ObjectValue {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
export interface CodexOptions {
  executable?: string;
  args?: string[];
  timeoutMs?: number;
  maxLineBytes?: number;
  maxPublicBytes?: number;
}
export interface ApplicationRequestEvidence {
  applicationRequestId: string;
  method: string;
  /** SHA-256 of the exact UTF-8 JSON request line, excluding its trailing LF. */
  serializedRequestDigest: string;
  sentAt: string;
}
export interface PublicTurn {
  threadId: string;
  turnId: string;
  status: string;
  messages: { itemId: string; text: string }[];
}
export interface CodexTurnResult extends PublicTurn {
  applicationRequest: ApplicationRequestEvidence;
}
interface Pending {
  resolve(value: unknown): void;
  reject(error: Error): void;
  timer: NodeJS.Timeout;
}
interface TurnWaiter { resolve(value: PublicTurn): void; reject(error: Error): void; timer: NodeJS.Timeout }

/** Codex JSON-RPC is its own protocol, neither MCP nor the ktChecker application transport.
 * Only public agentMessage completions and turn status for explicitly owned threads are retained.
 * Raw Responses items, reasoning notifications, account data and stderr are never recorded.
 */
export class CodexAppServer {
  private child: ChildProcessWithoutNullStreams | undefined;
  private buffer = Buffer.alloc(0);
  private readonly utf8 = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true });
  private pending = new Map<string, Pending>();
  private failure: HostError | undefined;
  private closed = false;
  private readonly options: Required<CodexOptions>;
  private readonly ownedThreads = new Set<string>();
  private readonly turns = new Map<string, PublicTurn>();
  private readonly waiters = new Map<string, TurnWaiter[]>();
  private readonly toolHandlers = new Map<string, (params: ObjectValue) => Promise<ObjectValue>>();

  constructor(options: CodexOptions | string = {}) {
    const supplied = typeof options === 'string' ? { executable: options } : options;
    this.options = {
      executable: supplied.executable ?? 'codex', args: supplied.args ?? ['app-server', '--stdio'],
      timeoutMs: supplied.timeoutMs ?? 15_000,
      maxLineBytes: supplied.maxLineBytes ?? 1_048_576,
      maxPublicBytes: supplied.maxPublicBytes ?? 262_144,
    };
    if (!Number.isSafeInteger(this.options.timeoutMs) || this.options.timeoutMs < 1 ||
        this.options.timeoutMs > 60_000 || this.options.maxLineBytes < 128 || this.options.maxPublicBytes < 128) {
      throw new HostError('invalid_codex_limits');
    }
  }
  async connect(): Promise<void> {
    if (this.child || this.closed) throw new HostError('codex_connection_state');
    this.child = spawn(this.options.executable, this.options.args, {
      stdio: ['pipe', 'pipe', 'pipe'], shell: false, windowsHide: true,
    });
    this.child.stderr.resume();
    this.child.stdout.on('data', (chunk: Buffer) => this.receive(chunk));
    this.child.stdin.on('error', () => this.fail('codex_disconnected'));
    this.child.on('error', () => this.fail('codex_unavailable'));
    this.child.on('exit', () => this.fail('codex_disconnected'));
    await this.request('initialize', {
      clientInfo: { name: 'ktchecker_reference_host', version: '0.1.0-preview.1' },
      capabilities: { experimentalApi: true },
    });
    this.child.stdin.write(JSON.stringify({ method: 'initialized', params: {} }) + '\n');
  }
  /** Call only with an ID returned by this controller's own thread/start or saved resume record. */
  ownThread(threadId: string): void {
    if (!threadId || this.ownedThreads.size >= 8) throw new HostError('codex_owned_thread_bound');
    this.ownedThreads.add(threadId);
  }
  /** Explicit fixed-purpose controller tools only. Approvals and all other server requests stay denied. */
  handleTool(name: string, handler: (params: ObjectValue) => Promise<ObjectValue>): void {
    if (!/^[a-z][a-z0-9_]{0,63}$/.test(name)) throw new HostError('invalid_controller_tool');
    this.toolHandlers.set(name, handler);
  }
  request<T = ObjectValue>(method: string, params: ObjectValue): Promise<T> {
    return this.requestRecorded<T>(method, params).then(value => value.result);
  }
  async requestRecorded<T = ObjectValue>(
    method: string, params: ObjectValue,
    beforeSend?: (evidence: ApplicationRequestEvidence) => void,
  ): Promise<{ result: T; evidence: ApplicationRequestEvidence }> {
    if (!this.child || this.closed || this.failure) throw this.failure ?? new HostError('codex_disconnected');
    if (this.pending.size >= 32) throw new HostError('codex_backpressure');
    const id = randomUUID();
    const serialized = JSON.stringify({ id, method, params });
    if (Buffer.byteLength(serialized) > this.options.maxLineBytes) throw new HostError('codex_request_too_large');
    const evidence: ApplicationRequestEvidence = {
      applicationRequestId: id, method, serializedRequestDigest: digest(serialized), sentAt: new Date().toISOString(),
    };
    // Allows a trusted controller to commit the request intent before writing any bytes.
    beforeSend?.(evidence);
    const result = await new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new HostError('codex_request_timeout_outcome_unknown'));
      }, this.options.timeoutMs);
      this.pending.set(id, { resolve: value => resolve(value as T), reject, timer });
      this.child!.stdin.write(serialized + '\n', error => { if (error) this.fail('codex_disconnected'); });
    });
    return { result, evidence };
  }
  async runTurn(
    params: ObjectValue, timeoutMs = 120_000,
    beforeSend?: (evidence: ApplicationRequestEvidence) => void,
    accepted?: (turnId: string) => void,
  ): Promise<CodexTurnResult> {
    const threadId = params.threadId;
    if (typeof threadId !== 'string' || !this.ownedThreads.has(threadId)) throw new HostError('codex_unowned_thread');
    const response = await this.requestRecorded<{ turn: { id: string; status: string } }>('turn/start', params, beforeSend);
    const turn = response.result?.turn;
    if (!turn || typeof turn.id !== 'string') throw new HostError('codex_turn_unconfirmed');
    accepted?.(turn.id);
    const completed = await this.waitForTurn(threadId, turn.id, timeoutMs);
    return { ...completed, applicationRequest: response.evidence };
  }
  waitForTurn(threadId: string, turnId: string, timeoutMs = 120_000): Promise<PublicTurn> {
    if (!this.ownedThreads.has(threadId)) return Promise.reject(new HostError('codex_unowned_thread'));
    if (this.failure || this.closed) return Promise.reject(this.failure ?? new HostError('codex_disconnected'));
    const key = threadId + ':' + turnId;
    const turn = this.turns.get(key);
    if (turn && turn.status !== 'inProgress') return Promise.resolve(structuredClone(turn));
    return new Promise((resolve, reject) => {
      const waiter: TurnWaiter = {
        resolve, reject, timer: setTimeout(() => {
          const remaining = (this.waiters.get(key) ?? []).filter(x => x !== waiter);
          if (remaining.length) this.waiters.set(key, remaining); else this.waiters.delete(key);
          reject(new HostError('codex_public_turn_timeout'));
        }, timeoutMs),
      };
      this.waiters.set(key, [...(this.waiters.get(key) ?? []), waiter]);
    });
  }
  private receive(chunk: Buffer): void {
    if (this.failure || this.closed) return;
    this.buffer = Buffer.concat([this.buffer, chunk]);
    let index: number;
    while ((index = this.buffer.indexOf(10)) >= 0) {
      if (index > this.options.maxLineBytes) { this.fail('codex_response_too_large'); return; }
      const bytes = this.buffer.subarray(0, index);
      this.buffer = this.buffer.subarray(index + 1);
      let value: unknown;
      try { value = JSON.parse(this.utf8.decode(bytes)); } catch { this.fail('codex_protocol_error'); return; }
      if (!object(value)) { this.fail('codex_protocol_error'); return; }
      if (value.id !== undefined && typeof value.method === 'string') {
        void this.serverRequest(value);
      } else if (typeof value.id === 'string') {
        const pending = this.pending.get(value.id);
        if (!pending) continue;
        this.pending.delete(value.id); clearTimeout(pending.timer);
        if (Object.hasOwn(value, 'error')) pending.reject(new HostError('codex_request_rejected'));
        else if (Object.hasOwn(value, 'result')) pending.resolve(value.result);
        else pending.reject(new HostError('codex_protocol_error'));
      } else if (typeof value.method === 'string' && object(value.params)) {
        this.publicNotification(value.method, value.params);
      }
    }
    if (this.buffer.length > this.options.maxLineBytes) this.fail('codex_response_too_large');
  }
  private async serverRequest(value: ObjectValue): Promise<void> {
    const params = value.params;
    const allowed = value.method === 'item/tool/call' && object(params) &&
      typeof params.threadId === 'string' && this.ownedThreads.has(params.threadId) &&
      typeof params.tool === 'string' ? this.toolHandlers.get(params.tool) : undefined;
    let response: ObjectValue;
    try {
      response = allowed && object(params) ? { id: value.id, result: await allowed(params) } : {
        id: value.id, error: { code: -32601, message: 'Interactive requests unavailable in this controlled host' },
      };
    } catch {
      response = { id: value.id, error: { code: -32603, message: 'Controlled tool unavailable' } };
    }
    if (!this.closed && !this.failure) this.child?.stdin.write(JSON.stringify(response) + '\n');
  }
  private publicNotification(method: string, params: ObjectValue): void {
    if (typeof params.threadId !== 'string' || !this.ownedThreads.has(params.threadId)) return;
    let turnId: string;
    let message: { itemId: string; text: string } | undefined;
    let status: string | undefined;
    if (method === 'item/completed' && object(params.item) && params.item.type === 'agentMessage' &&
        typeof params.turnId === 'string' && typeof params.item.id === 'string' && typeof params.item.text === 'string') {
      turnId = params.turnId; message = { itemId: params.item.id, text: params.item.text };
    } else if (method === 'turn/completed' && object(params.turn) && typeof params.turn.id === 'string' && typeof params.turn.status === 'string') {
      turnId = params.turn.id; status = params.turn.status;
    } else return; // Includes ALL reasoning and raw-response notifications.
    const key = params.threadId + ':' + turnId;
    if (!this.turns.has(key) && this.turns.size >= 64) { this.fail('codex_public_history_bound'); return; }
    const turn = this.turns.get(key) ?? { threadId: params.threadId, turnId, status: 'inProgress', messages: [] };
    if (message) {
      if (turn.messages.length >= 64 || turn.messages.reduce((n, x) => n + Buffer.byteLength(x.text), 0) + Buffer.byteLength(message.text) > this.options.maxPublicBytes) {
        this.fail('codex_public_output_bound'); return;
      }
      const existing = turn.messages.find(x => x.itemId === message.itemId);
      if (existing && existing.text !== message.text) { this.fail('codex_public_message_revision'); return; }
      if (!existing) turn.messages.push(message);
    }
    if (status) turn.status = status;
    this.turns.set(key, turn);
    if (status) {
      for (const waiter of this.waiters.get(key) ?? []) { clearTimeout(waiter.timer); waiter.resolve(structuredClone(turn)); }
      this.waiters.delete(key);
    }
  }
  private fail(code: string): void {
    this.failure ??= new HostError(code);
    for (const pending of this.pending.values()) { clearTimeout(pending.timer); pending.reject(this.failure); }
    for (const waiters of this.waiters.values()) for (const waiter of waiters) { clearTimeout(waiter.timer); waiter.reject(this.failure); }
    this.pending.clear(); this.waiters.clear(); this.child?.kill('SIGTERM');
  }
  async close(): Promise<void> {
    if (this.closed) return;
    this.closed = true; this.fail('codex_closed');
    const child = this.child;
    if (!child || child.exitCode !== null || child.signalCode !== null) return;
    await new Promise<void>(resolve => {
      const timeout = setTimeout(() => { child.kill('SIGKILL'); resolve(); }, 2000);
      child.once('exit', () => { clearTimeout(timeout); resolve(); }); child.stdin.end();
    });
  }
}
export interface CodexProbe {
  protocolConnected: boolean;
  accountPresent: boolean;
  requiresOpenaiAuth: boolean | null;
  inferenceAvailability: 'not_probed';
  automaticDelivery: 'unverified';
  modelObservation: 'not_probed';
  reason: string;
}
/** Read-only account inquiry, NOT an inference-availability test. No threads are enumerated or resumed. */
export async function probeCodex(executable = 'codex'): Promise<CodexProbe> {
  const server = new CodexAppServer(executable);
  try {
    await server.connect();
    const status = await server.request<{ account: unknown | null; requiresOpenaiAuth?: boolean }>('account/read', { refreshToken: false });
    return {
      protocolConnected: true, accountPresent: status.account !== null && status.account !== undefined,
      requiresOpenaiAuth: status.requiresOpenaiAuth ?? null,
      inferenceAvailability: 'not_probed', automaticDelivery: 'unverified', modelObservation: 'not_probed',
      reason: 'account_state_does_not_establish_provider_inference_availability',
    };
  } finally { await server.close(); }
}
