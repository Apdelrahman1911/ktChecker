import { canonical, digest, HostError } from './journal.js';
import type { ReviewEvent } from './types.js';

type ObjectValue = Record<string, unknown>;
function object(value: unknown): value is ObjectValue { return value !== null && typeof value === 'object' && !Array.isArray(value); }
const countInto = (counts: Record<string, number>, key: string) => {
  // Use prefixed keys so hostile source strings cannot address Object.prototype.
  const safeKey = 'state:' + key; counts[safeKey] = (counts[safeKey] ?? 0) + 1;
};
export const CONTEXT_SCHEMA = 'ktchecker/context-preview-1';
export interface DiagnosticProjection {
  diagnosticPayload: string;
  diagnosticPayloadDigest: string;
  findingObligations: { index: number; findingDigest: string; obligationId: string }[];
  originalPayloadRef: { kind: 'private_durable_inbox'; clientId: string; generation: number; eventId: string; payloadDigest: string; bytes: number };
}

/** Summarize only repetitive coverage inventory. NEVER drop a finding, raw diagnostic or failure.
 * The original payload remains immutable in InboxJournal; ACK and observation apply to this
 * explicitly scoped context projection, not to omitted original coverage bytes.
 */
export function projectDiagnostic(event: ReviewEvent): DiagnosticProjection {
  if (digest(event.payload) !== event.payloadDigest) throw new HostError('event_digest_mismatch');
  const original: unknown = JSON.parse(event.payload);
  if (!object(original) || typeof original.state !== 'string') throw new HostError('unsupported_event_payload');
  const result = object(original.result) ? original.result : null;
  const findings = result?.findings ?? [];
  if (!Array.isArray(findings)) throw new HostError('unsupported_findings');
  const findingObligations = findings.map((finding, index) => {
    if (!object(finding)) throw new HostError('unsupported_findings');
    const findingDigest = digest(canonical(finding));
    return { index, findingDigest, obligationId: digest(canonical({ eventId: event.eventId, index, findingDigest })) };
  });
  let projectedResult: ObjectValue | null = result;
  if (result) {
    const coverage = result.coverage ?? [];
    if (!Array.isArray(coverage)) throw new HostError('unsupported_coverage');
    let requiredRows = 0, requiredUnsuccessfulRows = 0;
    let requiredUnqualifiedRows = 0, requiredUntestedRows = 0, requiredUnimplementedRows = 0;
    const countsByState: Record<string, number> = {}, requiredCountsByState: Record<string, number> = {};
    const contexts = new Set<string>(); const reasons = new Map<string, number>();
    for (const entry of coverage) {
      if (!object(entry) || typeof entry.state !== 'string') throw new HostError('unsupported_coverage');
      countInto(countsByState, entry.state);
      contexts.add(canonical(entry.context ?? null));
      if (entry.required === true) {
        requiredRows++; countInto(requiredCountsByState, entry.state);
        const certified = ['passed', 'findings', 'not_applicable'].includes(entry.state) &&
          typeof entry.certificateId === 'string' && entry.certificateId.trim().length > 0 &&
          ['executed', 'reused_with_proof'].includes(String(entry.provenance));
        if (!certified) requiredUnsuccessfulRows++;
        if (entry.qualified !== true) requiredUnqualifiedRows++;
        if (entry.tested !== true) requiredUntestedRows++;
        if (entry.implemented !== true) requiredUnimplementedRows++;
      }
      if (typeof entry.reason === 'string') reasons.set(entry.reason, (reasons.get(entry.reason) ?? 0) + 1);
    }
    const reasonGroups = [...reasons.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));
    projectedResult = {
      ...result,
      // The original array is available only through originalPayloadRef. This is not ProviderResult.
      coverage: [], coverageRowsOmitted: coverage.length,
      coverageSummary: {
        totalRows: coverage.length, requiredRows, countsByState, requiredCountsByState, requiredUnsuccessfulRows,
        hasUnsuccessfulRequiredCoverage: requiredUnsuccessfulRows > 0,
        requiredUnqualifiedRows, requiredUntestedRows, requiredUnimplementedRows,
        hasUnqualifiedRequiredCoverage: requiredUnqualifiedRows > 0,
        fullCoverageDigest: digest(canonical(coverage)),
        contextCount: contexts.size, contextsDigest: digest(canonical([...contexts].sort())),
        reasonSamples: reasonGroups.slice(0, 8).map(([reason, count]) => ({ reason, count })),
        omittedDistinctReasons: Math.max(0, reasonGroups.length - 8),
        omittedReasonRows: reasonGroups.slice(8).reduce((sum, [, count]) => sum + count, 0),
        fullRowsRetainedInOriginalPayload: true,
      },
    };
  }
  const diagnosticPayload = canonical({ ...original, result: projectedResult });
  return {
    diagnosticPayload, diagnosticPayloadDigest: digest(diagnosticPayload), findingObligations,
    originalPayloadRef: { kind: 'private_durable_inbox', clientId: event.clientId, generation: event.generation,
      eventId: event.eventId, payloadDigest: event.payloadDigest, bytes: Buffer.byteLength(event.payload) },
  };
}
