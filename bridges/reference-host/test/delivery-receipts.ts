/** Captured application RPC confirmations, NOT signed kernel receipts, private-ledger
 * reads, or internal-inference attestations. Only explicitly selected public fields
 * cross the pre-await boundary. In particular, never serialize/hash/clone a request
 * object containing credentials, not even temporarily for a later redaction pass.
 */
import { PREVIEW_CONTRACT } from '@ktchecker/mcp-preview/rpc';
import { canonical, digest, HostError } from '../src/journal.js';
import { verifyPublicResponse } from '../src/codex-observation.js';
import type { ClientBinding, ModelRequest } from '../src/types.js';

const MAX_EVENTS = 256, MAX_ROWS = 1_024, MAX_PAGE = 100, MAX_PENDING = 16;
const SHA256 = /^sha256:[0-9a-f]{64}$/;
const KINDS = new Set(['accepted', 'progress', 'result', 'recovered', 'cancelled', 'plan_accepted']);
type ObjectValue = Record<string, unknown>;
function object(value: unknown): ObjectValue {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new HostError('delivery_capture_shape');
  return value as ObjectValue;
}
function identifier(value: unknown): string {
  if (typeof value !== 'string' || !/^[a-zA-Z0-9_.:-]{1,256}$/.test(value)) throw new HostError('delivery_capture_identity');
  return value;
}
function sha(value: unknown): string {
  if (typeof value !== 'string' || !SHA256.test(value)) throw new HostError('delivery_capture_digest');
  return value;
}
function integer(value: unknown, minimum = 1): number {
  if (!Number.isSafeInteger(value) || (value as number) < minimum) throw new HostError('delivery_capture_integer');
  return value as number;
}
function same(a: unknown, b: unknown, code = 'delivery_capture_mismatch'): void {
  if (canonical(a) !== canonical(b)) throw new HostError(code);
}
/** Domain separation makes different opaque identity roles non-interchangeable. */
export function deliveryIdentity(kind: string, value: string): string {
  return digest(canonical({ scope: 'ktchecker-captured-rpc-confirmation/preview-1', kind, value: identifier(value) }));
}
function list(value: unknown, project: (item: unknown) => string): readonly string[] {
  if (!Array.isArray(value) || value.length < 1 || value.length > MAX_PAGE) throw new HostError('delivery_capture_set_bound');
  const values = value.map(project).sort();
  if (new Set(values).size !== values.length) throw new HostError('delivery_capture_duplicate');
  return Object.freeze(values);
}
function eventIds(value: unknown): readonly string[] { return list(value, id => deliveryIdentity('event', identifier(id))); }

export interface DeliveryBinding {
  readonly ownerIdDigest: string; readonly workspaceIdDigest: string; readonly worktreeIdDigest: string;
  readonly clientIdDigest: string; readonly lineageIdDigest: string; readonly sessionIdDigest: string | null;
  readonly generation: number;
}
export function deliveryBinding(value: ClientBinding): Readonly<DeliveryBinding> {
  // Do not enumerate or clone the input; callers pass only the public binding, never registration.
  return Object.freeze({ ownerIdDigest: deliveryIdentity('owner', value.ownerId),
    workspaceIdDigest: deliveryIdentity('workspace', value.workspaceId), worktreeIdDigest: deliveryIdentity('worktree', value.worktreeId),
    clientIdDigest: deliveryIdentity('client', value.clientId), lineageIdDigest: deliveryIdentity('lineage', value.lineageId),
    sessionIdDigest: value.sessionId === undefined ? null : deliveryIdentity('session', value.sessionId),
    generation: integer(value.generation) });
}
export interface DeliveryEventMetadata {
  readonly contractVersion: string; readonly eventIdDigest: string; readonly clientIdDigest: string;
  readonly generation: number; readonly sequence: number; readonly reviewIdDigest: string; readonly patchIdDigest: string;
  readonly sourceSnapshotId: string; readonly payloadDigest: string; readonly kind: string; readonly deliveryRequired: boolean;
}
function eventMetadata(value: unknown): Readonly<DeliveryEventMetadata> {
  const event = object(value);
  if (event.contractVersion !== PREVIEW_CONTRACT || !KINDS.has(event.kind as string) || typeof event.deliveryRequired !== 'boolean') {
    throw new HostError('delivery_capture_event_contract');
  }
  return Object.freeze({ contractVersion: PREVIEW_CONTRACT, eventIdDigest: deliveryIdentity('event', identifier(event.eventId)),
    clientIdDigest: deliveryIdentity('client', identifier(event.clientId)), generation: integer(event.generation), sequence: integer(event.sequence),
    reviewIdDigest: deliveryIdentity('review', identifier(event.reviewId)), patchIdDigest: deliveryIdentity('patch', identifier(event.patchId)),
    sourceSnapshotId: sha(event.sourceSnapshotId), payloadDigest: sha(event.payloadDigest), kind: event.kind as string,
    deliveryRequired: event.deliveryRequired });
}
export interface AckProjection {
  readonly receiptIdDigest: string; readonly contextDigest: string; readonly contextItemIdDigest: string; readonly insertedAtDigest: string;
}
export interface ObservationProjection {
  readonly observationIdDigest: string; readonly modelRequestIdDigest: string; readonly applicationRequestIdDigest: string;
  readonly contextDigests: readonly string[];
}
export interface ResponseProjection {
  readonly contractVersion: string; readonly clientIdDigest: string; readonly eventIdDigests: readonly string[];
  readonly state: 'acknowledged' | 'model_observed';
}
export interface DeliveryConfirmationRow {
  readonly confirmationOrder: number; readonly eventOccurrence: number; readonly method: 'review.ack' | 'review.observe';
  readonly binding: Readonly<DeliveryBinding>; readonly event: Readonly<DeliveryEventMetadata>;
  readonly ack: Readonly<AckProjection>; readonly observation: Readonly<ObservationProjection> | null;
  readonly priorAckConfirmationOrder: number | null;
  readonly requestProjectionDigest: string; readonly response: Readonly<ResponseProjection>; readonly responseProjectionDigest: string;
}
export interface DeliveryReceiptSnapshot {
  readonly schema: 'ktchecker-captured-delivery-rpc-confirmations/preview-1';
  readonly evidenceScope: 'captured_application_rpc_confirmations_not_signed_kernel_or_internal_inference_attestations';
  readonly complete: boolean; readonly successfulConfirmationCalls: number; readonly capturedEvents: number;
  readonly rows: readonly Readonly<DeliveryConfirmationRow>[];
}
export interface PreparedDeliveryCapture {
  returned(value: unknown): void;
  rejected(): void;
}
type Intent = { readonly clientIdDigest: string; readonly kind: 'review.events' } | {
  readonly clientIdDigest: string; readonly kind: 'review.ack' | 'review.observe'; readonly eventIdDigests: readonly string[];
  readonly ack: Readonly<AckProjection> | null; readonly observation: Readonly<ObservationProjection> | null;
  readonly requestProjectionDigest: string;
};

/** All returned snapshots are bounded immutable values; repeats are retained as
 * separate confirmations, never relabeled as a deduplicated durable mutation.
 */
export class DeliveryReceiptCapture {
  private readonly bindings = new Map<string, Readonly<DeliveryBinding>>();
  private readonly events = new Map<string, Readonly<DeliveryEventMetadata>>();
  private readonly rows: Readonly<DeliveryConfirmationRow>[] = [];
  private pending = 0;
  private calls = 0;
  private failed = false;
  bind(value: ClientBinding): void {
    this.healthy();
    const binding = deliveryBinding(value), old = this.bindings.get(binding.clientIdDigest);
    if (old) same(old, binding, 'delivery_capture_binding_revision');
    else if (this.bindings.size >= 8) throw new HostError('delivery_capture_binding_bound');
    this.bindings.set(binding.clientIdDigest, binding);
  }
  private healthy(): void { if (this.failed) throw new HostError('delivery_capture_failed'); }
  beforeRequest(method: string, params: Record<string, unknown>): PreparedDeliveryCapture | null {
    if (method !== 'review.events' && method !== 'review.ack' && method !== 'review.observe') return null;
    this.healthy();
    if (this.pending >= MAX_PENDING) throw new HostError('delivery_capture_pending_bound');
    // The credential's only accessed property is public clientId. Neither token,
    // arbitrary extras, toJSON nor a caller-owned nested object survives this point.
    const clientIdDigest = deliveryIdentity('client', identifier(object(params.credential).clientId));
    let intent: Intent;
    if (method === 'review.events') intent = Object.freeze({ kind: method, clientIdDigest });
    else {
      const ids = eventIds(params.eventIds), receipt = object(params.receipt);
      let ack: Readonly<AckProjection> | null = null, observation: Readonly<ObservationProjection> | null = null;
      if (method === 'review.ack') {
        const insertedAt = receipt.insertedAt;
        if (typeof insertedAt !== 'string' || insertedAt.length > 40 || !/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z$/.test(insertedAt) || !Number.isFinite(Date.parse(insertedAt))) {
          throw new HostError('delivery_capture_insertion_time');
        }
        ack = Object.freeze({ receiptIdDigest: deliveryIdentity('ack-receipt', identifier(receipt.receiptId)),
          contextDigest: sha(receipt.contextDigest), contextItemIdDigest: deliveryIdentity('context-item', identifier(receipt.contextItemId)),
          insertedAtDigest: digest(insertedAt) });
      } else {
        const modelRequestId = identifier(receipt.modelRequestId);
        if (!modelRequestId.startsWith('codex-app-request:')) throw new HostError('delivery_capture_application_request_required');
        observation = Object.freeze({ observationIdDigest: deliveryIdentity('observation', identifier(receipt.observationId)),
          modelRequestIdDigest: deliveryIdentity('model-request', modelRequestId),
          applicationRequestIdDigest: deliveryIdentity('application-request', modelRequestId.slice('codex-app-request:'.length)),
          contextDigests: list(receipt.contextDigests, sha) });
      }
      // Hash only this explicit public projection, never params or credential.
      const requestProjectionDigest = digest(canonical({ method, clientIdDigest, eventIdDigests: ids, receipt: ack ?? observation }));
      intent = Object.freeze({ kind: method, clientIdDigest, eventIdDigests: ids, ack, observation, requestProjectionDigest });
    }
    this.pending++;
    let settled = false;
    return Object.freeze({
      returned: (value: unknown) => {
        if (settled) throw new HostError('delivery_capture_already_settled');
        settled = true; this.pending--;
        this.healthy();
        try { this.returned(intent, value); }
        catch { this.failed = true; throw new HostError('delivery_capture_confirmation_rejected'); }
      },
      rejected: () => {
        if (settled) return;
        settled = true; this.pending--;
      },
    });
  }
  private returned(intent: Intent, value: unknown): void {
    const response = object(value), binding = this.bindings.get(intent.clientIdDigest);
    if (!binding || response.contractVersion !== PREVIEW_CONTRACT) throw new HostError('delivery_capture_contract');
    if (intent.kind === 'review.events') {
      if (!Array.isArray(response.events) || response.events.length > MAX_PAGE) throw new HostError('delivery_capture_page_bound');
      const additions = new Map<string, Readonly<DeliveryEventMetadata>>();
      for (const raw of response.events) {
        const event = eventMetadata(raw), payload = object(raw).payload;
        if (typeof payload !== 'string' || payload.length > 2_097_152 || Buffer.byteLength(payload) > 2_097_152 || digest(payload) !== event.payloadDigest ||
            event.clientIdDigest !== binding.clientIdDigest || event.generation !== binding.generation) throw new HostError('delivery_capture_event_binding');
        const previous = additions.get(event.eventIdDigest) ?? this.events.get(event.eventIdDigest);
        if (previous) same(previous, event, 'delivery_capture_event_revision');
        additions.set(event.eventIdDigest, event);
      }
      if (new Set([...this.events.keys(), ...additions.keys()]).size > MAX_EVENTS) throw new HostError('delivery_capture_event_bound');
      for (const [id, event] of additions) this.events.set(id, event);
      return;
    }
    const state = intent.kind === 'review.ack' ? 'acknowledged' : 'model_observed';
    if (response.state !== state || deliveryIdentity('client', identifier(response.clientId)) !== binding.clientIdDigest) {
      throw new HostError('delivery_capture_response_binding');
    }
    const returnedIds = eventIds(response.eventIds);
    same(returnedIds, intent.eventIdDigests);
    const projection = Object.freeze({ contractVersion: PREVIEW_CONTRACT, clientIdDigest: binding.clientIdDigest, eventIdDigests: returnedIds, state });
    const responseProjectionDigest = digest(canonical(projection)), order = this.calls + 1;
    if (this.rows.length + returnedIds.length > MAX_ROWS) throw new HostError('delivery_capture_row_bound');
    const additions: Readonly<DeliveryConfirmationRow>[] = [];
    for (const eventId of returnedIds) {
      const event = this.events.get(eventId);
      if (!event || event.clientIdDigest !== binding.clientIdDigest || event.generation !== binding.generation || !event.deliveryRequired) {
        throw new HostError('delivery_capture_missing_event');
      }
      const previous = this.rows.filter(row => row.event.eventIdDigest === eventId);
      const priorAck = previous.findLast(row => row.method === 'review.ack');
      if (intent.kind === 'review.observe' && !priorAck) throw new HostError('delivery_capture_ack_required');
      const ack = intent.ack ?? priorAck!.ack;
      if (priorAck) same(priorAck.ack, ack, 'delivery_capture_ack_revision');
      additions.push(Object.freeze({ confirmationOrder: order, eventOccurrence: previous.length + 1, method: intent.kind,
        binding, event, ack, observation: intent.observation, priorAckConfirmationOrder: intent.kind === 'review.observe' ? priorAck!.confirmationOrder : null,
        requestProjectionDigest: intent.requestProjectionDigest, response: projection, responseProjectionDigest }));
    }
    if (intent.observation) same([...new Set(additions.map(row => row.ack.contextDigest))].sort(), intent.observation.contextDigests,
      'delivery_capture_context_set');
    // Atomic local append: a malformed success never publishes a valid prefix.
    this.rows.push(...additions); this.calls = order;
  }
  snapshot(): Readonly<DeliveryReceiptSnapshot> {
    return Object.freeze({ schema: 'ktchecker-captured-delivery-rpc-confirmations/preview-1',
      evidenceScope: 'captured_application_rpc_confirmations_not_signed_kernel_or_internal_inference_attestations',
      complete: !this.failed && this.pending === 0, successfulConfirmationCalls: this.calls, capturedEvents: this.events.size,
      rows: Object.freeze([...this.rows]) });
  }
}

/** Offline join against the already retained, exact application request/public
 * response. No RPC, store, history lookup or model invocation occurs here.
 * The caller supplies the independently known public registration binding.
 */
export function verifyDeliveryReceiptJoin(
  snapshot: DeliveryReceiptSnapshot, phaseValue: unknown, expectedBinding: ClientBinding,
): Readonly<{ joinedEvents: number; applicationRequestIdDigest: string; observationConfirmationOrders: readonly number[] }> {
  const phase = object(phaseValue), binding = deliveryBinding(expectedBinding);
  if (snapshot.schema !== 'ktchecker-captured-delivery-rpc-confirmations/preview-1' ||
      snapshot.evidenceScope !== 'captured_application_rpc_confirmations_not_signed_kernel_or_internal_inference_attestations' || !snapshot.complete ||
      !Array.isArray(snapshot.rows) || snapshot.rows.length > MAX_ROWS || integer(snapshot.capturedEvents, 0) > MAX_EVENTS ||
      integer(snapshot.successfulConfirmationCalls, 0) > MAX_ROWS) throw new HostError('delivery_join_snapshot');
  same(phase.deliveryBinding, binding, 'delivery_join_binding');
  if (phase.consumer !== expectedBinding.clientId || phase.status !== 'verified' || phase.internalInferenceRequestTrace !== false || phase.model !== 'gpt-6-astra') {
    throw new HostError('delivery_join_phase');
  }
  const applicationEvidence = object(phase.applicationRequest);
  const serialized = phase.serializedApplicationRequest;
  if (typeof serialized !== 'string' || serialized.length > 262_144 || digest(serialized) !== applicationEvidence.serializedRequestDigest || applicationEvidence.method !== 'turn/start') {
    throw new HostError('delivery_join_application_bytes');
  }
  const application = object(JSON.parse(serialized)), params = object(application.params), toolOutput = object(params.toolOutput);
  if (application.method !== 'turn/start' || application.id !== applicationEvidence.applicationRequestId || !Array.isArray(params.input) ||
      params.input.length !== 0 || toolOutput.name !== 'ktchecker' || typeof toolOutput.output !== 'string') throw new HostError('delivery_join_application');
  const applicationId = identifier(application.id), threadId = identifier(params.threadId), output = object(JSON.parse(toolOutput.output));
  if (output.contractVersion !== PREVIEW_CONTRACT || !Array.isArray(output.contexts) || !output.contexts.length || output.contexts.length > MAX_PAGE) {
    throw new HostError('delivery_join_contexts');
  }
  const contextEvents: { contextDigest: string; event: Readonly<DeliveryEventMetadata> }[] = [];
  const contexts = output.contexts.map(raw => {
    const context = object(raw), text = context.text, contextDigest = sha(context.contextDigest);
    if (typeof text !== 'string' || text.length > 32_768 || digest(text) !== contextDigest) throw new HostError('delivery_join_context_digest');
    const envelope = object(JSON.parse(text.slice(text.indexOf('\n') + 1)));
    if (envelope.clientId !== expectedBinding.clientId || envelope.generation !== binding.generation ||
        envelope.marker !== `ktchecker-event:${envelope.eventId}:${envelope.payloadDigest}`) throw new HostError('delivery_join_context_binding');
    // The original event sequence/deliveryRequired aren't in the model envelope;
    // join the other exact metadata now, then compare the full captured ACK below.
    const metadata = eventMetadata({ ...envelope, contractVersion: PREVIEW_CONTRACT, sequence: 1, deliveryRequired: true });
    contextEvents.push({ contextDigest, event: metadata });
    return { marker: identifierMarker(envelope.marker), text, digest: contextDigest, hostThreadId: threadId, generation: binding.generation };
  });
  if (new Set(contextEvents.map(row => row.event.eventIdDigest)).size !== contexts.length) throw new HostError('delivery_join_duplicate_context');
  const request: ModelRequest = { invocationId: identifier(output.invocationId), requestDigest: sha(output.requestDigest), hostThreadId: threadId,
    generation: binding.generation, contexts };
  same(request.requestDigest, digest(canonical({ hostThreadId: threadId, generation: binding.generation, contexts })), 'delivery_join_request_digest');
  same(phase.requestDigest, request.requestDigest); same(phase.invocationId, request.invocationId);
  same([...(phase.contextDigests as string[])].sort(), contexts.map(context => context.digest).sort());
  if (typeof phase.verifiedPublicResponseText !== 'string' || !verifyPublicResponse(phase.verifiedPublicResponseText, request) ||
      digest(phase.verifiedPublicResponseText) !== phase.publicResponseDigest) throw new HostError('delivery_join_public_response');
  const applicationRequestIdDigest = deliveryIdentity('application-request', applicationId);
  const modelRequestIdDigest = deliveryIdentity('model-request', 'codex-app-request:' + applicationId);
  const orders = new Set<number>();
  let previousOrder = 0;
  const occurrences = new Map<string, number>(), groups = new Map<number, DeliveryConfirmationRow[]>();
  for (const row of snapshot.rows) {
    if (integer(row.confirmationOrder) < previousOrder || row.confirmationOrder > snapshot.successfulConfirmationCalls) throw new HostError('delivery_join_confirmation_order');
    previousOrder = row.confirmationOrder;
    const occurrence = (occurrences.get(row.event.eventIdDigest) ?? 0) + 1; occurrences.set(row.event.eventIdDigest, occurrence);
    if (row.eventOccurrence !== occurrence) throw new HostError('delivery_join_occurrence');
    for (const value of [row.binding.ownerIdDigest, row.binding.workspaceIdDigest, row.binding.worktreeIdDigest, row.binding.clientIdDigest,
      row.binding.lineageIdDigest, row.event.eventIdDigest, row.event.clientIdDigest, row.event.reviewIdDigest, row.event.patchIdDigest,
      row.event.sourceSnapshotId, row.event.payloadDigest, row.ack.receiptIdDigest, row.ack.contextDigest, row.ack.contextItemIdDigest, row.ack.insertedAtDigest]) sha(value);
    if (row.binding.sessionIdDigest !== null) sha(row.binding.sessionIdDigest);
    integer(row.binding.generation); integer(row.event.sequence); integer(row.event.generation);
    if (row.event.contractVersion !== PREVIEW_CONTRACT || !KINDS.has(row.event.kind) || row.event.deliveryRequired !== true) throw new HostError('delivery_join_event_contract');
    same(list(row.response.eventIdDigests, sha), row.response.eventIdDigests, 'delivery_join_event_order');
    if (row.responseProjectionDigest !== digest(canonical(row.response)) || row.response.contractVersion !== PREVIEW_CONTRACT ||
        row.response.clientIdDigest !== row.binding.clientIdDigest || row.event.clientIdDigest !== row.binding.clientIdDigest ||
        row.event.generation !== row.binding.generation || !row.response.eventIdDigests.includes(row.event.eventIdDigest) ||
        row.response.state !== (row.method === 'review.ack' ? 'acknowledged' : 'model_observed')) throw new HostError('delivery_join_response');
    if (row.method === 'review.ack') {
      if (row.observation !== null || row.priorAckConfirmationOrder !== null) throw new HostError('delivery_join_ack');
    } else if (row.method === 'review.observe') {
      const ack = snapshot.rows.find(prior => prior.method === 'review.ack' && prior.confirmationOrder === row.priorAckConfirmationOrder &&
        prior.event.eventIdDigest === row.event.eventIdDigest);
      if (!ack || ack.confirmationOrder >= row.confirmationOrder || !row.observation) throw new HostError('delivery_join_prior_ack');
      same(ack.binding, row.binding); same(ack.event, row.event); same(ack.ack, row.ack);
      sha(row.observation.observationIdDigest); sha(row.observation.applicationRequestIdDigest); sha(row.observation.modelRequestIdDigest);
      same(list(row.observation.contextDigests, sha), row.observation.contextDigests);
    } else throw new HostError('delivery_join_method');
    if (row.requestProjectionDigest !== digest(canonical({ method: row.method, clientIdDigest: row.binding.clientIdDigest,
      eventIdDigests: row.response.eventIdDigests, receipt: row.method === 'review.ack' ? row.ack : row.observation }))) throw new HostError('delivery_join_request_projection');
    groups.set(row.confirmationOrder, [...(groups.get(row.confirmationOrder) ?? []), row]);
  }
  if (groups.size !== snapshot.successfulConfirmationCalls) throw new HostError('delivery_join_missing_confirmation');
  for (const group of groups.values()) {
    same(group.map(row => row.event.eventIdDigest).sort(), group[0]!.response.eventIdDigests, 'delivery_join_confirmation_event_set');
    for (const row of group) {
      same(row.response, group[0]!.response); same(row.observation, group[0]!.observation); same(row.binding, group[0]!.binding);
      if (row.method === 'review.ack') same(row.ack, group[0]!.ack);
    }
    if (group[0]!.observation) same([...new Set(group.map(row => row.ack.contextDigest))].sort(), group[0]!.observation.contextDigests);
  }
  for (const context of contextEvents) {
    const observed = snapshot.rows.filter(row => row.method === 'review.observe' && row.event.eventIdDigest === context.event.eventIdDigest &&
      row.observation?.applicationRequestIdDigest === applicationRequestIdDigest);
    if (!observed.length) throw new HostError('delivery_join_observation_missing');
    for (const row of observed) {
      same(row.binding, binding, 'delivery_join_binding');
      same({ ...row.event, sequence: 1 }, context.event, 'delivery_join_event');
      if (row.ack.contextDigest !== context.contextDigest || row.observation!.modelRequestIdDigest !== modelRequestIdDigest) throw new HostError('delivery_join_observation_binding');
      same(row.observation!.contextDigests, contexts.map(item => item.digest).sort());
      orders.add(row.confirmationOrder);
    }
  }
  return Object.freeze({ joinedEvents: contexts.length, applicationRequestIdDigest, observationConfirmationOrders: Object.freeze([...orders].sort((a, b) => a - b)) });
}
function identifierMarker(value: unknown): string {
  if (typeof value !== 'string' || value.length > 512 || !value.startsWith('ktchecker-event:')) throw new HostError('delivery_join_marker');
  return value;
}
