/** Public-safe failure evidence: fixed field names, counts, byte sizes and hashes.
 * Never serializes diagnostics, reason strings, credentials, paths or RPC logs.
 */
import { prepareDiagnosticContext } from '../src/context-projection.js';
import { canonical, digest, HostError } from '../src/journal.js';
import type { JournalState } from '../src/types.js';

const permanentProblems = new Set([
  'required_context_exceeds_budget', 'unsupported_event_payload', 'unsupported_findings', 'unsupported_coverage',
  'unsupported_review_reasons', 'projection_field_collision', 'coverage_validation_proof_unavailable',
  'coverage_validation_origin_unconfirmed', 'coverage_validation_duplicate_reason', 'coverage_validation_duplicate_row',
  'coverage_validation_ambiguous_context', 'coverage_validation_reason_set_mismatch',
]);
export function permanentDeliveryProblem(state: JournalState): string | null {
  return state.lastProblem && permanentProblems.has(state.lastProblem) ? state.lastProblem : null;
}
export function safeDeliveryProblem(problem: unknown): string | null {
  return problem === null ? null : typeof problem === 'string' && permanentProblems.has(problem) ? problem : 'unclassified_delivery_problem';
}
function field(value: unknown): Record<string, unknown> {
  return { present: value !== undefined, bytes: value === undefined ? 0 : Buffer.byteLength(canonical(value)),
    entries: Array.isArray(value) ? value.length : null };
}
export function captureDeliveryTelemetry(state: JournalState): Record<string, unknown> {
  const entries = Object.values(state.inbox).sort((a, b) => a.event.sequence - b.event.sequence);
  return {
    schema: 'ktchecker-delivery-byte-telemetry/preview-1',
    lastPermanentProblem: permanentDeliveryProblem(state),
    totalEvents: entries.length, omittedEventTelemetry: Math.max(0, entries.length - 32),
    requiredUnacknowledgedEvents: entries.filter(entry => entry.event.deliveryRequired && !entry.acknowledged).length,
    requiredUnobservedEvents: entries.filter(entry => entry.event.deliveryRequired && !entry.observed).length,
    events: entries.slice(-32).map(entry => {
      const payload = JSON.parse(entry.event.payload), result = payload.result ?? {};
      let preparedContextBytes: number | null = null, projectionProblem: string | null = null;
      try { preparedContextBytes = Buffer.byteLength(prepareDiagnosticContext(entry.event, state.binding, state.currentSnapshotId).text); }
      catch (error) { projectionProblem = error instanceof HostError && permanentProblems.has(error.code) ? error.code : 'unclassified_projection_failure'; }
      return {
        eventIdentityDigest: digest(canonical({ eventId: entry.event.eventId, reviewId: entry.event.reviewId, patchId: entry.event.patchId })),
        kind: ['accepted', 'progress', 'result', 'plan_accepted'].includes(entry.event.kind) ? entry.event.kind : 'other',
        payloadDigest: digest(entry.event.payload), originalPayloadBytes: Buffer.byteLength(entry.event.payload),
        preparedContextBytes, preparedContextExceedsDefaultBudget: preparedContextBytes === null ? null : preparedContextBytes > 32_768,
        savedContextBytes: entry.item ? Buffer.byteLength(entry.item.text) : null,
        inserted: entry.inserted !== null, acknowledged: entry.acknowledged, observed: entry.observed, projectionProblem,
        fieldSizes: { topLevelReasons: field(payload.reasons), resultReasons: field(result.reasons), coverage: field(result.coverage),
          findings: field(result.findings), rawDiagnostics: field(result.rawDiagnostics), parentPlan: field(payload.parentPlan) },
      };
    }),
  };
}
