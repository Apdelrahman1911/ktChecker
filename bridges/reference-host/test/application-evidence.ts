import assert from 'node:assert/strict';
import type { CodexHostPort } from '../src/codex-host.js';
import { canonical, digest, HostError, type InboxJournal } from '../src/journal.js';
import { expectedPublicResponse } from '../src/codex-observation.js';
import type { ModelRequest } from '../src/types.js';

/** Select only matched public output from this controller's explicitly owned thread/turn.
 * Exact serialized application bytes are independently reconstructed and digest checked.
 * No internal inference, account, raw notification, or reasoning trace is requested/retained.
 */
export async function captureApplicationEvidence(host: CodexHostPort, journal: InboxJournal): Promise<Record<string, unknown>> {
  const proof = host.evidence().at(-1);
  if (!proof || proof.status !== 'verified') throw new HostError('application_evidence_unverified');
  const application = proof.applicationRequest as { applicationRequestId: string; serializedRequestDigest: string };
  const contexts = Object.values(journal.read().inbox).map(entry => entry.item!).filter(item => item &&
    (proof.contextDigests as string[]).includes(item.digest)).sort((a, b) => a.marker.localeCompare(b.marker));
  const output = canonical({ contractVersion: 'ktchecker/preview-1', invocationId: proof.invocationId,
    requestDigest: proof.requestDigest, contexts: contexts.map(item => ({ contextDigest: item.digest, text: item.text })) });
  const serializedApplicationRequest = JSON.stringify({ id: application.applicationRequestId, method: 'turn/start', params: {
    threadId: host.binding.hostThreadId, input: [], toolOutput: { name: 'ktchecker', output }, effort: proof.effort ?? 'low',
  } });
  assert.equal(digest(serializedApplicationRequest), application.serializedRequestDigest);
  const publicItems = await host.server.request<{ data: { item: { type: string; id: string; text?: string } }[] }>('thread/items/list', {
    threadId: host.binding.hostThreadId, turnId: proof.turnId, limit: 100, sortDirection: 'desc',
  });
  const message = publicItems.data.find(entry => entry.item.type === 'agentMessage' && entry.item.id === proof.publicItemId)?.item.text;
  assert(message); assert.equal(digest(message), proof.publicResponseDigest);
  const request: ModelRequest = { invocationId: proof.invocationId as string, requestDigest: proof.requestDigest as string,
    hostThreadId: host.binding.hostThreadId, generation: host.binding.client.generation, contexts };
  assert.deepEqual(JSON.parse(message), expectedPublicResponse(request));
  return { ...proof, serializedApplicationRequest, verifiedPublicResponse: JSON.parse(message), verifiedPublicResponseText: message,
    publicTextDigestIndependentlyRecomputable: true,
    responseTextDigest: digest(message), allFindingDiagnosisLocationVerificationAndDigestsMatched: true };
}
