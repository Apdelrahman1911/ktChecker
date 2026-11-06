import { canonical, digest, HostError } from './journal.js';
import type { ReviewEvent } from './types.js';

type ObjectValue = Record<string, unknown>;
function object(value: unknown): value is ObjectValue { return value !== null && typeof value === 'object' && !Array.isArray(value); }
const codes = ['coverage_unimplemented', 'coverage_unqualified', 'coverage_unsuccessful'] as const;
type Code = typeof codes[number];
const reasonPattern = /^(coverage_unimplemented|coverage_unqualified|coverage_unsuccessful):([A-Za-z0-9][A-Za-z0-9_.-]{0,255})$/;
const rowKeys = ['ruleId', 'context', 'state', 'reason', 'required', 'depth', 'provenance', 'certificateId', 'implemented', 'tested', 'qualified'].sort();
const contextKeys = ['module', 'sourceSet', 'target', 'compilation'].sort();

// Kotlin/JVM Char.isWhitespace is Character.isWhitespace || Character.isSpaceChar.
// ECMAScript trim is NOT equivalent: it includes FEFF but excludes 001C..001F.
const kotlinJvmBlank = /^[\u0009-\u000D\u001C-\u0020\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000]*$/u;
export function coverageSuccessful(row: ObjectValue): boolean {
  return ['passed', 'findings', 'not_applicable'].includes(String(row.state)) &&
    typeof row.certificateId === 'string' && !kotlinJvmBlank.test(row.certificateId) &&
    ['executed', 'reused_with_proof'].includes(String(row.provenance));
}

interface ProofRow extends ObjectValue {
  ruleId: string;
  context: ObjectValue;
  state: string;
  implemented: boolean;
  tested: boolean;
  qualified: boolean;
}
function proofRow(value: unknown): ProofRow {
  const context = object(value) && object(value.context) ? value.context : null;
  if (!object(value) || canonical(Object.keys(value).sort()) !== canonical(rowKeys) ||
      typeof value.ruleId !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9_.-]{0,255}$/.test(value.ruleId) ||
      !context || canonical(Object.keys(context).sort()) !== canonical(contextKeys) ||
      // RuleContext preserves an empty exported target for the produced JVM lane.
      // Empty is data, never a relabeling to "jvm"; all other scope fields stay nonempty.
      contextKeys.some(key => typeof context[key] !== 'string' || (key !== 'target' && !(context[key] as string).length)) ||
      typeof value.state !== 'string' || typeof value.depth !== 'string' || typeof value.provenance !== 'string' ||
      (value.reason !== null && typeof value.reason !== 'string') ||
      (value.certificateId !== null && typeof value.certificateId !== 'string') ||
      ['required', 'implemented', 'tested', 'qualified'].some(key => typeof value[key] !== 'boolean')) {
    throw new HostError('coverage_validation_proof_unavailable');
  }
  return value as ProofRow;
}
function violates(row: ProofRow, code: Code): boolean {
  switch (code) {
    case 'coverage_unimplemented': return !row.implemented;
    case 'coverage_unqualified': return !row.tested || !row.qualified;
    case 'coverage_unsuccessful': return !coverageSuccessful(row);
  }
}

/** Only the three exact top-level WorkScheduler.validateFinal preview-1 reason
 * families are eligible. Provider reasons, diagnosis strings, parent plans and
 * unknown codes are never edited. This is representation, not a new certificate.
 * A digest identifies retained evidence; it is not proof that omitted bytes were
 * read by a model. The complete immutable event remains the retrieval authority.
 */
export function projectCoverageValidationReasons(
  event: ReviewEvent, original: ObjectValue, coverage: unknown[],
): { reasons: string[]; summary: ObjectValue } | null {
  // Progress/policy/provider traffic has no final-validation-origin guarantee.
  if (event.kind !== 'result' || original.reasons === undefined) return null;
  if (!Array.isArray(original.reasons) || original.reasons.some(reason => typeof reason !== 'string')) {
    throw new HostError('unsupported_review_reasons');
  }
  const reasons = original.reasons as string[];
  const candidates = reasons.filter(reason => reasonPattern.test(reason));
  if (!candidates.length) return null;
  if (event.contractVersion !== 'ktchecker/preview-1' || original.contractVersion !== event.contractVersion ||
      original.clientId !== event.clientId || original.reviewId !== event.reviewId || original.patchId !== event.patchId ||
      original.sourceSnapshotId !== event.sourceSnapshotId || !Number.isSafeInteger(original.sequence) || Number(original.sequence) < 1 ||
      typeof original.cancelled !== 'boolean' || !['incomplete', 'cancelled'].includes(String(original.state)) ||
      (original.state === 'cancelled') !== original.cancelled) throw new HostError('coverage_validation_origin_unconfirmed');
  if (new Set(reasons).size !== reasons.length) throw new HostError('coverage_validation_duplicate_reason');

  const rows = coverage.map(proofRow);
  const identities = new Set<string>(), ruleIds = new Set<string>();
  for (const row of rows) {
    const identity = canonical([row.context, row.ruleId]);
    if (identities.has(identity)) throw new HostError('coverage_validation_duplicate_row');
    // Kernel reasons collapse ruleId across contexts. Do not guess how to narrow
    // an alias/multi-context inventory; it needs a separately qualified projection.
    if (ruleIds.has(row.ruleId)) throw new HostError('coverage_validation_ambiguous_context');
    identities.add(identity); ruleIds.add(row.ruleId);
  }
  const groups = codes.map(code => {
    const matching = rows.filter(row => violates(row, code));
    const generatedReasons = matching.map(row => code + ':' + row.ruleId).sort();
    const contexts = [...new Set(matching.map(row => canonical(row.context)))].sort();
    return { code, matching, generatedReasons, contexts };
  });
  const expected = groups.flatMap(group => group.generatedReasons).sort();
  if (canonical([...candidates].sort()) !== canonical(expected)) throw new HostError('coverage_validation_reason_set_mismatch');
  const retained = reasons.filter(reason => !reasonPattern.test(reason));
  return {
    reasons: retained,
    summary: {
      schema: 'ktchecker/coverage-validation-projection-preview-1',
      source: 'ReviewStatus.reasons',
      derivation: 'WorkScheduler.validateFinal_preview_1_distinct_exact_coverage_predicates',
      hasCoverageValidationFailures: true,
      originalReasonCount: reasons.length, retainedReasonCount: retained.length, omittedReasonCount: candidates.length,
      fullReasonArrayDigest: digest(canonical(reasons)), omittedReasonArrayDigest: digest(canonical(candidates)),
      omissionIndicesDigest: digest(canonical(reasons.flatMap((reason, index) => reasonPattern.test(reason) ? [index] : []))),
      fullCoverageDigest: digest(canonical(coverage)),
      groups: groups.map(group => ({
        code: group.code, reasonCount: group.generatedReasons.length, matchingCoverageRows: group.matching.length,
        generatedReasonSetDigest: digest(canonical(group.generatedReasons)), matchingCoverageRowsDigest: digest(canonical(group.matching)),
        contextCount: group.contexts.length, contextsDigest: digest(canonical(group.contexts)),
      })),
      fullReasonsRetainedInOriginalPayload: true,
      observationScope: 'predicate_groups_counts_and_digests_not_original_omitted_reason_bytes',
    },
  };
}
