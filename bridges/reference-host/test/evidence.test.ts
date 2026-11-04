import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { canonical, digest } from '../src/journal.js';
import { verifyPublicResponse } from '../src/codex-observation.js';
import type { ModelRequest } from '../src/types.js';

test('retained live synthetic evidence independently binds exact application bytes, contexts and public content, not internal inference', async () => {
  for (const file of ['codex-inbox-synthetic-events.json', 'codex-inbox-projected-synthetic-events.json']) {
    const report = JSON.parse(await readFile(new URL('../../evidence/' + file, import.meta.url), 'utf8'));
    assert.equal(report.verified, true); assert.equal(report.internalInferenceRequestTrace, false);
    assert.equal(report.actualKernelIntegration, false); assert.equal(report.actualKotlinCompilerRun, false);
    assert.equal(report.phases.length, 2); assert.equal(report.activeTurnJoined, true);
    for (const phase of report.phases) {
      const application = JSON.parse(phase.serializedApplicationRequest);
      assert.equal(digest(phase.serializedApplicationRequest), phase.applicationRequest.serializedRequestDigest);
      assert.equal(application.id, phase.applicationRequest.applicationRequestId);
      assert.equal(application.method, 'turn/start'); assert.deepEqual(application.params.input, []);
      const output = JSON.parse(application.params.toolOutput.output);
      const contexts = output.contexts.map((context: { contextDigest: string; text: string }) => {
        const envelope = JSON.parse(context.text.slice(context.text.indexOf('\n') + 1));
        assert.equal(digest(context.text), context.contextDigest);
        return { marker: envelope.marker, text: context.text, digest: context.contextDigest,
          hostThreadId: application.params.threadId, generation: envelope.generation };
      });
      const request: ModelRequest = { invocationId: output.invocationId, requestDigest: output.requestDigest,
        contexts, hostThreadId: application.params.threadId, generation: contexts[0].generation };
      assert.equal(request.requestDigest, digest(canonical({ hostThreadId: request.hostThreadId, generation: request.generation, contexts })));
      assert(verifyPublicResponse(JSON.stringify(phase.verifiedPublicResponse), request));
      assert.equal(phase.status, 'verified'); assert.equal(phase.model, 'gpt-6-astra');
      assert.equal(phase.internalInferenceRequestTrace, false);
      assert(!verifyPublicResponse('{"marker":"received"}', request));
    }
  }
});
