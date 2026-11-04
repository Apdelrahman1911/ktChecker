import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { randomUUID } from 'node:crypto';

export const PREVIEW_CONTRACT = 'ktchecker/preview-1' as const;
/** Kernel event pages may contain 8 MiB of payloads plus JSON-string escaping/envelopes. */
export const PRIVATE_RPC_LINE_BYTES = 16 * 1024 * 1024;
export type JsonObject = Record<string, unknown>;
export interface BrokerPort {
  request<T = JsonObject>(method: string, params: JsonObject): Promise<T>;
}
export class BrokerError extends Error {
  constructor(readonly code: string, message: string) { super(message); this.name = 'BrokerError'; }
}
export interface RpcOptions {
  command: string;
  args: string[];
  cwd?: string;
  env?: NodeJS.ProcessEnv;
  timeoutMs?: number;
  maxLineBytes?: number;
  maxPending?: number;
}
interface Pending {
  resolve(value: unknown): void;
  reject(error: Error): void;
  timer: NodeJS.Timeout;
}
function object(value: unknown): value is JsonObject {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** Private application transport, NOT MCP. No shells, implicit registration, or mutation retries. */
export class JsonLineRpc implements BrokerPort {
  private child: ChildProcessWithoutNullStreams | undefined;
  private pending = new Map<string, Pending>();
  private buffer = Buffer.alloc(0);
  private readonly utf8 = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true });
  private closed = false;
  private failure: BrokerError | undefined;
  private readonly timeoutMs: number;
  private readonly maxLineBytes: number;
  private readonly maxPending: number;
  get disconnected(): boolean { return this.closed || this.failure !== undefined; }
  constructor(private readonly options: RpcOptions) {
    if (!options.command || !Array.isArray(options.args)) throw new Error('Fixed broker argv is required');
    this.timeoutMs = options.timeoutMs ?? 10_000;
    this.maxLineBytes = options.maxLineBytes ?? PRIVATE_RPC_LINE_BYTES;
    this.maxPending = options.maxPending ?? 32;
    if (!Number.isSafeInteger(this.timeoutMs) || this.timeoutMs < 1 || this.timeoutMs > 60_000) throw new Error('Invalid RPC timeout');
    if (!Number.isSafeInteger(this.maxLineBytes) || this.maxLineBytes < 128) throw new Error('Invalid line bound');
    if (!Number.isSafeInteger(this.maxPending) || this.maxPending < 1) throw new Error('Invalid concurrency bound');
  }
  private start(): ChildProcessWithoutNullStreams {
    if (this.closed) throw new BrokerError('transport_closed', 'Broker transport is closed');
    if (this.failure) throw this.failure;
    if (this.child) return this.child;
    const child = spawn(this.options.command, this.options.args, {
      ...(this.options.cwd ? { cwd: this.options.cwd } : {}),
      ...(this.options.env ? { env: this.options.env } : {}),
      shell: false, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true,
    });
    this.child = child;
    // Never mix broker logs with MCP stdout or forward arbitrary private transcripts.
    child.stderr.resume();
    child.stdout.on('data', (chunk: Buffer) => this.receive(chunk));
    child.stdin.on('error', () => this.fail('broker_disconnected', 'Broker input disconnected'));
    child.on('error', () => this.fail('broker_unavailable', 'Configured broker could not be started'));
    child.on('exit', () => this.fail('broker_disconnected', 'Broker exited; reconcile durable submissions before retry'));
    return child;
  }
  async request<T = JsonObject>(method: string, params: JsonObject): Promise<T> {
    if (!/^[a-z][a-zA-Z0-9_.]*$/.test(method) || !object(params)) throw new BrokerError('invalid_request', 'Invalid application request');
    if (this.pending.size >= this.maxPending) throw new BrokerError('backpressure', 'Too many outstanding broker requests');
    const id = randomUUID();
    const bytes = Buffer.from(JSON.stringify({ id, method, params }) + '\n');
    if (bytes.length > this.maxLineBytes) throw new BrokerError('request_too_large', 'Request exceeds the transport bound');
    const child = this.start();
    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new BrokerError('request_timeout', 'Response deadline expired; operation outcome is unknown, not cancelled'));
      }, this.timeoutMs);
      this.pending.set(id, { resolve: value => resolve(value as T), reject, timer });
      child.stdin.write(bytes, error => { if (error) this.fail('broker_disconnected', 'Broker write failed'); });
    });
  }
  private receive(chunk: Buffer): void {
    if (this.failure || this.closed) return;
    this.buffer = Buffer.concat([this.buffer, chunk]);
    let newline: number;
    while ((newline = this.buffer.indexOf(10)) >= 0) {
      if (newline > this.maxLineBytes) { this.fail('invalid_broker_response', 'Broker response exceeds the line bound'); return; }
      const bytes = this.buffer.subarray(0, newline);
      this.buffer = this.buffer.subarray(newline + 1);
      let envelope: unknown;
      try { envelope = JSON.parse(this.utf8.decode(bytes)); } catch { this.fail('invalid_broker_response', 'Broker stdout is not valid UTF-8 application JSON'); return; }
      if (!object(envelope) || typeof envelope.id !== 'string' || envelope.contractVersion !== PREVIEW_CONTRACT ||
          (Object.hasOwn(envelope, 'result') === Object.hasOwn(envelope, 'error'))) {
        this.fail('incompatible_broker_contract', 'Expected a versioned preview application response'); return;
      }
      const pending = this.pending.get(envelope.id);
      if (!pending) continue; // A late response cannot revive a timed-out request.
      this.pending.delete(envelope.id); clearTimeout(pending.timer);
      if (Object.hasOwn(envelope, 'error')) {
        const error = envelope.error;
        if (!object(error) || typeof error.code !== 'string' || typeof error.message !== 'string') {
          pending.reject(new BrokerError('invalid_broker_response', 'Malformed broker error'));
          this.fail('invalid_broker_response', 'Malformed broker error'); return;
        }
        pending.reject(new BrokerError(error.code, error.message));
      } else pending.resolve(envelope.result);
    }
    if (this.buffer.length > this.maxLineBytes) this.fail('invalid_broker_response', 'Unterminated broker line exceeds the bound');
  }
  private fail(code: string, message: string): void {
    if (!this.failure) this.failure = new BrokerError(code, message);
    for (const item of this.pending.values()) { clearTimeout(item.timer); item.reject(this.failure); }
    this.pending.clear();
    this.child?.kill('SIGTERM');
  }
  async close(): Promise<void> {
    if (this.closed) return;
    this.closed = true;
    this.fail('transport_closed', 'Broker connection closed; analysis was not implicitly cancelled');
    const child = this.child;
    if (!child || child.exitCode !== null || child.signalCode !== null) return;
    await new Promise<void>(resolve => {
      const timer = setTimeout(() => { child.kill('SIGKILL'); resolve(); }, 2_000);
      child.once('exit', () => { clearTimeout(timer); resolve(); });
      child.stdin.end();
    });
  }
}

/** Restarts a disconnected subprocess for the NEXT explicit operation. It never retries the
 * failed request. A caller must reconcile durable submission/receipt IDs after unknown outcomes.
 */
export class RestartingJsonLineRpc implements BrokerPort {
  private current: JsonLineRpc | undefined;
  private allocation: Promise<void> = Promise.resolve();
  private closed = false;
  constructor(private readonly options: RpcOptions) {}
  async request<T = JsonObject>(method: string, params: JsonObject): Promise<T> {
    let connection: JsonLineRpc | undefined;
    const allocate = this.allocation.then(async () => {
      if (this.closed) throw new BrokerError('transport_closed', 'Broker transport is closed');
      if (this.current?.disconnected) { await this.current.close(); this.current = undefined; }
      this.current ??= new JsonLineRpc(this.options);
      connection = this.current;
    });
    this.allocation = allocate.catch(() => {});
    await allocate;
    return connection!.request<T>(method, params);
  }
  async close(): Promise<void> {
    this.closed = true;
    await this.allocation;
    await this.current?.close();
  }
}
