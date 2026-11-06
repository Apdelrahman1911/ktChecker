import { createHash } from 'node:crypto';
import * as z from 'zod/v4';
import { BrokerError, PREVIEW_CONTRACT } from './rpc.js';

export const POLICY_REASSESSMENT_KIND = 'policy_reassessed' as const;
export const POLICY_REASSESSMENT_FORMAT = 'ktchecker/policy-reassessment-view/1' as const;
const id = z.string().min(1).max(256);
const hash = z.string().regex(/^sha256:[0-9a-f]{64}$/);
const integer = z.number().int().min(Number.MIN_SAFE_INTEGER).max(Number.MAX_SAFE_INTEGER);
const positive = integer.min(1);
const coordinate = integer.min(-2_147_483_648).max(2_147_483_647);

/** Expectations only: no supplied evidence, policy edits, execution settings or receipts. */
export const policyReassessmentRequestSchema = z.object({
  reviewId: id, idempotencyKey: id, generation: positive, expectedPolicyDigest: hash,
}).strict();
export const policyReassessmentStatusSchema = z.object({ idempotencyKey: id }).strict();
export type PolicyReassessmentRequest = z.infer<typeof policyReassessmentRequestSchema>;

// Closed preview-1 evidence DTOs. Preserve every explicit null/default and every array
// entry; never strip unknown fields, coerce numbers or relabel unexecuted coverage.
const context = z.object({ target: z.string(), sourceSet: z.string(), module: z.string(), compilation: z.string() }).strict();
const location = z.object({
  path: z.string(), fileDigest: z.string(), line: coordinate, column: coordinate,
  endLine: coordinate, endColumn: coordinate, excerpt: z.string(),
}).strict();
// Match the Kotlin nonblank check without trim/coercion: these strings remain exact
// receipt material. The listed whitespace is the JVM Char.isWhitespace/SpaceChar set.
const retrievalText = z.string().refine(value =>
  !/^[\u0009-\u000d\u001c-\u0020\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]*$/.test(value) &&
  !value.includes('\u0000'), { message: 'Invalid retrieval kind or locator' });
const retrievalAttachment = z.object({
  kind: retrievalText, digest: hash.length(71), artifactRef: retrievalText,
}).strict();
const finding = z.object({
  ruleId: z.string(), title: z.string(), diagnosis: z.string(), whyItMatters: z.string(), location,
  fingerprint: z.string(), providerId: z.string(), context, severity: z.string(), kind: z.string(), certainty: z.string(),
  evidence: z.array(z.object({ kind: z.string(), summary: z.string(), artifactRef: z.string().nullable(), digest: z.string().nullable() }).strict()),
  fixHint: z.string().nullable(),
  verification: z.array(z.object({ kind: z.string(), expected: z.string(), command: z.array(z.string()), state: z.string() }).strict()),
  upstreamId: z.string().nullable(), symbolId: z.string().nullable(), semanticDigest: z.string().nullable(),
  relatedLocations: z.array(location), disposition: z.string(),
  // Absence must stay absent; default([]) would alter older view/event checksums.
  retrievalAttachments: z.array(retrievalAttachment).optional(),
}).strict().refine(value => (value.retrievalAttachments ?? []).every(attachment =>
  value.evidence.some(item => item.kind === attachment.kind && item.digest === attachment.digest)),
{ message: 'Retrieval attachment does not match evidence kind and digest' });
const coverage = z.object({
  ruleId: z.string(), context, state: z.string(), reason: z.string().nullable(), required: z.boolean(), depth: z.string(),
  provenance: z.string(), certificateId: z.string().nullable(), implemented: z.boolean(), tested: z.boolean(), qualified: z.boolean(),
}).strict();
const result = z.object({
  providerId: z.string(), status: z.string(), findings: z.array(finding), coverage: z.array(coverage), reasons: z.array(z.string()),
  rawDiagnostics: z.array(z.object({ message: z.string(), severity: z.string(), upstreamId: z.string().nullable(), location: location.nullable() }).strict()),
  metrics: z.object({ elapsedMillis: integer, cpuMillis: integer.nullable(), peakRssBytes: integer.nullable(), outputBytes: integer }).strict(),
  providerVersion: z.string(),
}).strict();

/** Public immutable view, NOT ReviewStatus, a new execution, a currentness proof or a gate result.
 * Closed capture/certificate objects admit compact identifiers only, never full policy archives.
 */
export const policyReassessmentViewSchema = z.object({
  contractVersion: z.literal(PREVIEW_CONTRACT).optional(),
  format: z.literal(POLICY_REASSESSMENT_FORMAT), state: z.literal(POLICY_REASSESSMENT_KIND),
  viewId: z.string().regex(/^policy-sha256:[0-9a-f]{64}$/), request: policyReassessmentRequestSchema, clientId: id, reviewId: id, patchId: id,
  sourceSnapshotId: hash, sequence: positive, generation: positive,
  certified: z.object({
    reviewSequence: positive, inputKey: hash, workKey: hash, effectiveInputKey: hash, workCertificateId: hash,
    originalPolicyCertificateDigest: hash, rawResultDigest: hash, originalEffectiveResultDigest: hash, subjectDigest: hash,
  }).strict(),
  capture: z.object({
    format: z.literal('ktchecker/policy-reassessment-capture/1'),
    originalPolicyDigest: hash, currentPolicyDigest: hash, originalCaptureId: hash, currentCaptureId: hash, analysisAuthorityDigest: hash,
  }).strict(),
  evaluatorIdentity: hash, issuerIdentity: hash,
  result, auditOnly: z.boolean(), reasons: z.array(z.string()), certificateDigest: hash,
}).strict().refine(view => view.reviewId === view.request.reviewId && view.generation === view.request.generation &&
  view.capture.currentPolicyDigest === view.request.expectedPolicyDigest, { message: 'Inconsistent policy view identity' });
export type PolicyReassessmentView = z.infer<typeof policyReassessmentViewSchema>;

// SOURCE-matched canonical JSON: sorted object keys, array order and explicit values.
// This content checksum is NOT an issuer signature, evidence qualification or currentness proof.
function canonicalPolicyValue(value: unknown): string {
  if (Array.isArray(value)) return '[' + value.map(canonicalPolicyValue).join(',') + ']';
  if (value !== null && typeof value === 'object') return '{' + Object.keys(value).sort().map(key =>
    JSON.stringify(key) + ':' + canonicalPolicyValue((value as Record<string, unknown>)[key])).join(',') + '}';
  const text = JSON.stringify(value);
  if (text === undefined) throw new BrokerError('invalid_policy_reassessment_view', 'Non-JSON policy view');
  return text;
}
function policyDigest(text: string): string { return 'sha256:' + createHash('sha256').update(text, 'utf8').digest('hex'); }
/** Closed DTO, content self-consistency and binding only; never authority or currentness. */
export function parsePolicyReassessmentView(value: unknown): PolicyReassessmentView {
  const parsed = policyReassessmentViewSchema.safeParse(value);
  if (!parsed.success) throw new BrokerError('invalid_policy_reassessment_view', 'Policy view is not the closed public DTO');
  // MCP adds contractVersion outside the Kotlin DTO; it is not part of its certificate.
  const { contractVersion: _transportVersion, ...view } = parsed.data;
  const checksum = policyDigest(canonicalPolicyValue({ ...view, certificateDigest: '' }));
  if (view.certificateDigest !== checksum) throw new BrokerError('invalid_policy_reassessment_view', 'Policy view content checksum differs');
  return parsed.data;
}
export function checkedPolicyReassessmentView(value: unknown, clientId: string,
  expected: PolicyReassessmentRequest | string, generation?: number): PolicyReassessmentView {
  const view = parsePolicyReassessmentView(value);
  const matches = typeof expected === 'string' ? view.request.idempotencyKey === expected :
    view.request.reviewId === expected.reviewId && view.request.idempotencyKey === expected.idempotencyKey &&
    view.request.generation === expected.generation && view.request.expectedPolicyDigest === expected.expectedPolicyDigest;
  if (view.clientId !== clientId || !matches || (generation !== undefined && view.generation !== generation)) {
    throw new BrokerError('invalid_policy_reassessment_view', 'Policy view does not match this client/request');
  }
  return view;
}

const policyEventSchema = z.object({
  contractVersion: z.literal(PREVIEW_CONTRACT), eventId: id, clientId: id, sequence: positive,
  reviewId: id, patchId: id, sourceSnapshotId: hash, kind: z.literal(POLICY_REASSESSMENT_KIND),
  deliveryRequired: z.literal(true), payloadDigest: hash, payload: z.string(), generation: positive,
}).strict();
function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
/** Validate policy-shaped traffic on EVERY read path, leaving ordinary events unchanged.
 * Kernel event payloads are canonical closed DTO bytes, not MCP tool response envelopes.
 * Null means non-policy, never permission to skip malformed required policy traffic.
 */
export function checkedPolicyReassessmentEvent(value: unknown, clientId?: string, generation?: number): PolicyReassessmentView | null {
  if (!object(value)) return null;
  let payload: unknown;
  try { payload = typeof value.payload === 'string' ? JSON.parse(value.payload) : undefined; } catch {
    if (value.kind === POLICY_REASSESSMENT_KIND) throw new BrokerError('invalid_policy_reassessment_view', 'Policy event payload is not JSON');
    return null;
  }
  const policyShaped = object(payload) && (payload.state === POLICY_REASSESSMENT_KIND || payload.format === POLICY_REASSESSMENT_FORMAT);
  if (value.kind !== POLICY_REASSESSMENT_KIND && !policyShaped) return null;
  const parsed = policyEventSchema.safeParse(value);
  if (!parsed.success || !object(payload) || Object.hasOwn(payload, 'contractVersion')) {
    throw new BrokerError('invalid_policy_reassessment_view', 'Policy event is not the closed required DTO');
  }
  const event = parsed.data;
  const view = parsePolicyReassessmentView(payload);
  if (policyDigest(event.payload) !== event.payloadDigest || canonicalPolicyValue(view) !== event.payload ||
      event.eventId !== view.viewId || event.clientId !== view.clientId || event.generation !== view.generation ||
      event.reviewId !== view.reviewId || event.patchId !== view.patchId || event.sourceSnapshotId !== view.sourceSnapshotId ||
      event.sequence !== view.sequence || (clientId !== undefined && event.clientId !== clientId) ||
      (generation !== undefined && event.generation !== generation)) {
    throw new BrokerError('invalid_policy_reassessment_view', 'Policy event identity or exact payload bytes differ');
  }
  return view;
}

// Exact generic KernelTypes.kt metadata. Ordinary kinds and payload string bytes stay
// open/opaque; only policy-shaped entries acquire the stricter policy semantics above.
// Long fields must be exactly representable in JS; do not round transport identities.
const reviewEventEnvelopeSchema = z.object({
  contractVersion: z.literal(PREVIEW_CONTRACT), eventId: z.string(), clientId: z.string(), sequence: integer,
  reviewId: z.string(), patchId: z.string(), sourceSnapshotId: z.string(), kind: z.string(),
  deliveryRequired: z.boolean(), payloadDigest: z.string(), payload: z.string(), generation: integer,
}).strict();
const reviewEventPageSchema = z.object({
  contractVersion: z.literal(PREVIEW_CONTRACT), events: z.array(z.unknown()), cursor: z.string(), hasMore: z.boolean(),
}).strict();
type CheckedReviewEventPage = Omit<z.infer<typeof reviewEventPageSchema>, 'events'> & {
  events: z.infer<typeof reviewEventEnvelopeSchema>[];
};
/** Whole-page refusal before model forwarding or durable cursor commit. Never filter,
 * normalize, drop entries or advance a cursor past invalid metadata/policy evidence.
 */
export function checkedReviewEventPage(value: unknown, clientId: string, generation?: number): CheckedReviewEventPage {
  const page = reviewEventPageSchema.safeParse(value);
  if (!page.success) throw new BrokerError('invalid_review_event_page', 'Event page is not the closed public DTO');
  for (const entry of page.data.events) {
    // Preserve the existing policy-specific refusals; ordinary entries still require
    // the complete generic envelope and the authenticated client/generation binding.
    checkedPolicyReassessmentEvent(entry, clientId, generation);
    const event = reviewEventEnvelopeSchema.safeParse(entry);
    if (!event.success || event.data.clientId !== clientId || (generation !== undefined && event.data.generation !== generation)) {
      throw new BrokerError('invalid_review_event_page', 'Event page contains invalid or misbound metadata');
    }
  }
  // Validation is observational: return the original complete page, not a Zod clone.
  return value as CheckedReviewEventPage;
}
