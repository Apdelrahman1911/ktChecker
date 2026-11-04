import type { JsonObject } from '@ktchecker/mcp-preview/rpc';
export interface ClientBinding {
  ownerId: string; workspaceId: string; worktreeId: string; clientId: string;
  lineageId: string; generation: number; sessionId?: string | undefined;
}
export interface Credential { clientId: string; token: string }
export interface Registration { binding: ClientBinding; client: Credential; adapter: Credential; contractVersion: string }
export interface HostBinding { client: ClientBinding; hostKind: string; hostThreadId: string }
export interface ReviewEvent {
  contractVersion: string; eventId: string; clientId: string; sequence: number;
  reviewId: string; patchId: string; sourceSnapshotId: string; kind: string;
  deliveryRequired: boolean; payloadDigest: string; payload: string; generation: number;
}
export interface EventPage { contractVersion: string; events: ReviewEvent[]; cursor: string; hasMore: boolean }
export interface Submission { patchId: string; idempotencyKey: string; input: JsonObject }
export interface ReviewStatus extends JsonObject { reviewId: string; sourceSnapshotId: string; state: string }
export interface ContextReceipt { receiptId: string; contextDigest: string; contextItemId: string; insertedAt: string }
export interface ObservationReceipt { observationId: string; modelRequestId: string; contextDigests: string[] }
export interface GateCut {
  contractVersion: string; cutId: string; clientId: string; lineageId: string;
  generation: number; lineageEpoch: number; sourceSnapshotId: string | null;
  eventFrontier: number; requiredEventIds: string[]; ready: boolean; reasons: string[];
  sourceEpoch?: number | null; analysisEpoch?: string | null;
}
export interface GateResult { allowed: boolean; actionPerformed: boolean; reasons: string[]; cutId: string;
  clientId: string; lineageId: string; generation: number; contractVersion: string }
export interface ContextItem {
  marker: string; text: string; digest: string; hostThreadId: string; generation: number;
}
export interface InsertedContext {
  receipt: ContextReceipt; marker: string; hostThreadId: string; generation: number;
}
export interface ModelRequest {
  invocationId: string; hostThreadId: string; generation: number;
  contexts: ContextItem[]; requestDigest: string;
}
export type ModelWitness = {
  kind: 'actual-model-request'; invocationId: string; modelRequestId: string;
  hostThreadId: string; generation: number; requestDigest: string; contextDigests: string[];
} | { kind: 'unavailable' | 'insertion-only' | 'test-only'; reason: string };
/** Protected controller SPI. Never deserialize these witnesses from a model-facing tool. */
export interface HostPort {
  readonly binding: HostBinding;
  findContext(marker: string): Promise<InsertedContext | null>;
  insertContext(item: ContextItem, receiptId: string): Promise<InsertedContext>;
  /** Return actual request inclusion evidence, NOT an enqueue/send/turn-start receipt. */
  invokeModel(request: ModelRequest): Promise<ModelWitness>;
}
export interface InboxEntry {
  event: ReviewEvent; item: ContextItem | null; insertionReceiptId: string | null;
  inserted: InsertedContext | null; acknowledged: boolean;
  observation: ObservationReceipt | null; observed: boolean; stale: boolean;
}
export interface PendingSubmission { request: Submission; status: ReviewStatus | null }
export interface JournalState {
  version: 1; binding: HostBinding; fetchCursor: string | null;
  inbox: Record<string, InboxEntry>; submissions: Record<string, PendingSubmission>;
  currentSnapshotId: string | null; localEpoch: number;
  cut: { value: GateCut; localEpoch: number } | null;
  lastProblem: string | null;
  /** Saved before inference; reuse the same ID to reconcile an interrupted invocation. */
  pendingModelRequest?: ModelRequest | null;
}
