import { realpathSync } from 'node:fs';
import { canonical, digest, HostError } from './journal.js';
import { CodexAppServer, type CodexOptions, type PublicTurn } from './codex-app-server.js';
import { CodexStore, type CodexInvocation } from './codex-store.js';
import { CODEX_DIAGNOSTIC_INSTRUCTIONS, expectedPublicResponse, verifyPublicResponse } from './codex-observation.js';
import type { ClientBinding, ContextItem, HostBinding, HostPort, InsertedContext, ModelRequest, ModelWitness } from './types.js';

type ObjectValue = Record<string, unknown>;
function object(value: unknown): value is ObjectValue { return value !== null && typeof value === 'object' && !Array.isArray(value); }
export interface CodexHostOptions {
  directory: string;
  client: ClientBinding;
  /** Owner-selected synthetic workspace. This reference controller does not browse other sessions. */
  cwd: string;
  /** Owner-only selection. Not a model tool argument, and never automatically substituted. */
  effort?: string;
  server?: CodexOptions;
  dynamicTools?: ObjectValue[];
  onModelRequestAccepted?: (turnId: string) => void;
}

/** Controlled read-only Codex integration, not a patch to the stock interactive Codex client.
 * Application-request inclusion + a content-bound public response are evidenced. Internal provider
 * request bodies/IDs are not observed. Never grant native execution/finalization authority from it.
 */
export class CodexHostPort implements HostPort {
  readonly binding: HostBinding;
  readonly resumed: boolean;
  private constructor(
    readonly server: CodexAppServer, private readonly store: CodexStore,
    resumed: boolean, private readonly accepted?: (turnId: string) => void,
  ) {
    const state = store.read();
    if (!state.threadId) throw new HostError('codex_thread_unconfirmed');
    this.binding = { client: state.client, hostKind: 'codex-app-server-controlled-preview', hostThreadId: state.threadId };
    this.resumed = resumed;
  }
  static async open(options: CodexHostOptions): Promise<CodexHostPort> {
    const model = 'gpt-6-astra';
    const effort = options.effort ?? 'low';
    const cwd = realpathSync(options.cwd);
    const store = new CodexStore(options.directory, options.client, cwd, model, effort);
    const server = new CodexAppServer(options.server);
    try {
      await server.connect();
      const state = store.read();
      let response: { thread: { id: string }; model: string; reasoningEffort?: string | null };
      const resumed = state.threadId !== null;
      if (state.threadId) {
        // Only our own durable ID. No thread/list, guessed ID, rollout path, or imported history.
        response = await server.request('thread/resume', {
          threadId: state.threadId, model, cwd, sandbox: 'read-only', approvalPolicy: 'never', excludeTurns: true,
          developerInstructions: CODEX_DIAGNOSTIC_INSTRUCTIONS,
          ...(options.effort !== undefined ? { config: { model_reasoning_effort: effort } } : {}),
        });
        if (response.thread?.id !== state.threadId) throw new HostError('codex_resume_binding_mismatch');
      } else {
        if (state.creationStarted) throw new HostError('codex_thread_creation_outcome_unknown');
        store.edit(value => { value.creationStarted = true; });
        response = await server.request('thread/start', {
          model, allowProviderModelFallback: false, ephemeral: false, historyMode: 'paginated',
          cwd, sandbox: 'read-only', approvalPolicy: 'never',
          developerInstructions: CODEX_DIAGNOSTIC_INSTRUCTIONS,
          ...(options.effort !== undefined ? { config: { model_reasoning_effort: effort } } : {}),
          ...(options.dynamicTools ? { dynamicTools: options.dynamicTools } : {}),
        });
        if (!response.thread?.id) throw new HostError('codex_thread_unconfirmed');
        store.edit(value => { value.threadId = response.thread.id; });
      }
      // A changed or silently substituted model cannot receive a single diagnostic invocation.
      if (response.model !== model) throw new HostError('codex_requested_model_not_confirmed');
      // Explicit selections require actual thread-response confirmation before any model turn.
      // A provider that rejects/normalizes max cannot silently become a low-effort experiment.
      if (options.effort !== undefined && response.reasoningEffort !== effort) throw new HostError('codex_requested_effort_not_confirmed');
      server.ownThread(response.thread.id);
      return new CodexHostPort(server, store, resumed, options.onModelRequestAccepted);
    } catch (error) { await server.close(); store.close(); throw error; }
  }
  async findContext(marker: string): Promise<InsertedContext | null> {
    const saved = this.store.read().contexts[marker];
    if (!saved) return null;
    if (saved.inserted) return saved.inserted;
    if (!saved.attempts) return null;
    // Crash after injection but before local receipt: search only this owned thread, boundedly.
    for await (const entry of this.items()) {
      const item = entry.item;
      if (object(item) && item.type === 'functionCallOutput' && item.name === 'ktchecker' &&
          item.output === saved.item.text && typeof item.id === 'string') {
        const inserted = this.receipt(saved.item, saved.receiptId, item.id);
        this.store.edit(state => { state.contexts[marker]!.inserted = inserted; });
        return inserted;
      }
    }
    return null;
  }
  async insertContext(item: ContextItem, receiptId: string): Promise<InsertedContext> {
    this.validateContext(item);
    if (!receiptId || !item.marker.startsWith('ktchecker-event:')) throw new HostError('invalid_context_identity');
    this.store.edit(state => {
      const saved = state.contexts[item.marker];
      if (saved && (canonical(saved.item) !== canonical(item) || saved.receiptId !== receiptId)) throw new HostError('codex_context_revision');
      state.contexts[item.marker] ??= { item, receiptId, attempts: 0, inserted: null };
    });
    const reconciled = await this.findContext(item.marker);
    if (reconciled) return reconciled;
    this.store.edit(state => {
      const saved = state.contexts[item.marker]!;
      if (saved.attempts >= 3) throw new HostError('codex_insertion_reconciliation_required');
      saved.attempts++;
    });
    const itemId = 'ktchecker-' + receiptId;
    await this.server.request('thread/inject_items', {
      threadId: this.binding.hostThreadId,
      items: [{ type: 'function_call_output', id: itemId, name: 'ktchecker', output: item.text }],
    });
    // The response confirms append-to-history, NOT a model call or content consumption.
    const inserted = this.receipt(item, receiptId, itemId);
    this.store.edit(state => { state.contexts[item.marker]!.inserted = inserted; });
    return inserted;
  }
  private receipt(item: ContextItem, receiptId: string, itemId: string): InsertedContext {
    return { marker: item.marker, hostThreadId: item.hostThreadId, generation: item.generation,
      receipt: { receiptId, contextDigest: item.digest, contextItemId: itemId, insertedAt: new Date().toISOString() } };
  }
  private validateContext(item: ContextItem): void {
    if (item.hostThreadId !== this.binding.hostThreadId || item.generation !== this.binding.client.generation || digest(item.text) !== item.digest) throw new HostError('codex_context_binding_mismatch');
  }
  async invokeModel(request: ModelRequest): Promise<ModelWitness> {
    this.server.assertNoModelReroute();
    if (request.hostThreadId !== this.binding.hostThreadId || request.generation !== this.binding.client.generation ||
        request.requestDigest !== digest(canonical({ hostThreadId: request.hostThreadId, generation: request.generation, contexts: request.contexts }))) throw new HostError('codex_model_request_binding_mismatch');
    if (!/^[a-zA-Z0-9-]{1,128}$/.test(request.invocationId)) throw new HostError('invalid_invocation_id');
    for (const context of request.contexts) {
      this.validateContext(context);
      const saved = this.store.read().contexts[context.marker];
      if (!saved?.inserted || canonical(saved.item) !== canonical(context)) throw new HostError('codex_context_not_inserted');
    }
    // Validate diagnostic shape before spending any inference or sending arbitrary framing.
    expectedPublicResponse(request);
    const previous = this.store.read().invocations[request.invocationId];
    const effort = this.store.read().effort ?? 'low';
    if (previous) {
      if ((previous.effort ?? 'low') !== effort) throw new HostError('codex_invocation_effort_revision');
      if (canonical(previous.request) !== canonical(request)) throw new HostError('codex_invocation_revision');
      if (previous.status === 'verified') return this.witness(previous);
      if (previous.status === 'rejected') return { kind: 'unavailable', reason: 'public_response_did_not_bind_diagnostic_content' };
      // Never implicitly resend an uncertain model request; reconcile saved owned-thread history.
      const recovered = await this.recoverPublicTurn(previous);
      if (!recovered) throw new HostError('codex_model_request_outcome_unknown');
      return this.confirm(request, recovered);
    }
    const output = canonical({
      contractVersion: 'ktchecker/preview-1', invocationId: request.invocationId, requestDigest: request.requestDigest,
      contexts: request.contexts.map(context => ({ contextDigest: context.digest, text: context.text })),
    });
    const completed = await this.server.runTurn({
      threadId: request.hostThreadId, input: [], toolOutput: { name: 'ktchecker', output }, effort,
    }, 120_000, evidence => {
      this.store.edit(state => { state.invocations[request.invocationId] = {
        request, effort, applicationRequest: evidence, turnId: null, status: 'uncertain', publicResponseDigest: null, publicItemId: null,
      }; });
    }, turnId => {
      this.store.edit(state => { state.invocations[request.invocationId]!.turnId = turnId; });
      this.accepted?.(turnId);
    });
    return this.confirm(request, completed);
  }
  private confirm(request: ModelRequest, turn: PublicTurn): ModelWitness {
    this.server.assertNoModelReroute();
    const response = turn.messages.find(message => verifyPublicResponse(message.text, request));
    this.store.edit(state => {
      const invocation = state.invocations[request.invocationId]!;
      invocation.turnId = turn.turnId;
      invocation.status = turn.status === 'completed' && response ? 'verified' : 'rejected';
      invocation.publicResponseDigest = response ? digest(response.text) : null;
      invocation.publicItemId = response?.itemId ?? null;
    });
    const saved = this.store.read().invocations[request.invocationId]!;
    if (saved.status !== 'verified') return { kind: 'unavailable', reason: 'public_response_did_not_bind_diagnostic_content' };
    return this.witness(saved);
  }
  private witness(saved: CodexInvocation): ModelWitness {
    return {
      kind: 'actual-model-request', invocationId: saved.request.invocationId,
      // This is an APPLICATION request ID, explicitly not an internal inference/Responses ID.
      modelRequestId: 'codex-app-request:' + saved.applicationRequest.applicationRequestId,
      hostThreadId: saved.request.hostThreadId, generation: saved.request.generation,
      requestDigest: saved.request.requestDigest, contextDigests: saved.request.contexts.map(context => context.digest),
    };
  }
  private async *items(turnId?: string): AsyncGenerator<ObjectValue> {
    let cursor: string | null = null;
    for (let page = 0; page < 40; page++) {
      const response: { data: ObjectValue[]; nextCursor?: string | null } = await this.server.request('thread/items/list', {
        threadId: this.binding.hostThreadId, ...(turnId ? { turnId } : {}), cursor, limit: 100, sortDirection: 'desc',
      });
      if (!Array.isArray(response.data)) throw new HostError('codex_history_unconfirmed');
      for (const entry of response.data) yield entry;
      if (!response.nextCursor) return;
      if (response.nextCursor === cursor) throw new HostError('codex_history_cursor_stalled');
      cursor = response.nextCursor;
    }
    throw new HostError('codex_reconciliation_history_bound');
  }
  private async recoverPublicTurn(saved: CodexInvocation): Promise<PublicTurn | null> {
    const response = await this.server.request<{ data: { id: string; status: string }[] }>('thread/turns/list', {
      threadId: this.binding.hostThreadId, sortDirection: 'desc', limit: 64, itemsView: 'notLoaded',
    });
    if (!Array.isArray(response.data)) throw new HostError('codex_history_unconfirmed');
    for (const turn of response.data) {
      if (saved.turnId && turn.id !== saved.turnId) continue;
      if (turn.status !== 'completed') continue;
      const messages: PublicTurn['messages'] = [];
      for await (const entry of this.items(turn.id)) {
        const item = entry.item;
        if (object(item) && item.type === 'agentMessage' && typeof item.id === 'string' && typeof item.text === 'string' && verifyPublicResponse(item.text, saved.request)) {
          messages.push({ itemId: item.id, text: item.text });
        }
      }
      if (messages.length) return { threadId: this.binding.hostThreadId, turnId: turn.id, status: turn.status, messages };
    }
    return null;
  }
  /** Digests and identities only; never raw public/diagnostic text, account data, or reasoning. */
  evidence(): ObjectValue[] {
    return Object.values(this.store.read().invocations).map(saved => ({
      evidenceLevel: 'application_request_and_content_bound_public_response',
      internalInferenceRequestTrace: false, model: 'gpt-6-astra',
      effort: saved.effort ?? 'low',
      invocationId: saved.request.invocationId, requestDigest: saved.request.requestDigest,
      contextDigests: saved.request.contexts.map(item => item.digest),
      applicationRequest: saved.applicationRequest, turnId: saved.turnId,
      publicItemId: saved.publicItemId, publicResponseDigest: saved.publicResponseDigest,
      status: saved.status,
    }));
  }
  async close(): Promise<void> { try { await this.server.close(); } finally { this.store.close(); } }
}
