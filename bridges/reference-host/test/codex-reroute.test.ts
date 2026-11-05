/** SYNTHETIC local protocol controls. No Codex account, real app-server or model is used. */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { CodexAppServer } from '../src/codex-app-server.js';

const fake = `
import {createInterface} from 'node:readline';
const send=value=>process.stdout.write(JSON.stringify(value)+'\\n');
const notice=(threadId='owned',turnId='turn')=>({method:'model/rerouted',params:{threadId,turnId,fromModel:'gpt-6-astra',toModel:'synthetic-other-model',reason:'highRiskCyberActivity'}});
createInterface({input:process.stdin}).on('line',line=>{
 const r=JSON.parse(line);if(r.id===undefined)return;
 if(r.method==='test/fire'){send(r.params.notice);send({id:r.id,result:{}});return;}
 if(r.method==='turn/start'){
  const entries=[{id:r.id,result:{turn:{id:'turn',status:'inProgress'}}},
   {method:'item/completed',params:{threadId:'owned',turnId:'turn',item:{type:'agentMessage',id:'public',text:'{}'}}},
   {method:'turn/completed',params:{threadId:'owned',turn:{id:'turn',status:'completed'}}}];
  if(r.params.mode==='before-response')entries.unshift(notice());
  if(r.params.mode==='after-completion')entries.push(notice());
  if(r.params.mode==='unrelated')entries.unshift(notice('another-thread'));
  process.stdout.write(entries.map(value=>JSON.stringify(value)+'\\n').join(''));return;
 }
 send({id:r.id,result:{}});
});`;
const notice = (overrides: Record<string, unknown> = {}) => ({ method: 'model/rerouted', params: {
  threadId: 'owned', turnId: 'turn', fromModel: 'gpt-6-astra', toModel: 'synthetic-other-model', reason: 'highRiskCyberActivity', ...overrides,
} });
async function fixture(t: { after(fn: () => Promise<void>): void }, guard: boolean | null = true): Promise<CodexAppServer> {
  const server = new CodexAppServer({ executable: process.execPath, args: ['--input-type=module', '-e', fake],
    ...(guard === null ? {} : { failOnModelReroute: guard }) });
  t.after(() => server.close()); await server.connect(); return server;
}

test('opt-in exact-model guard rejects owned reroute before the turn/start response', async t => {
  const server = await fixture(t); server.ownThread('owned');
  await assert.rejects(server.runTurn({ threadId: 'owned', input: [], mode: 'before-response' }), /codex_selected_model_rerouted/);
  assert.throws(() => server.assertNoModelReroute(), /codex_selected_model_rerouted/);
});
test('opt-in exact-model guard rejects owned reroute after completion in the same protocol batch', async t => {
  const server = await fixture(t); server.ownThread('owned');
  await assert.rejects(server.runTurn({ threadId: 'owned', input: [], mode: 'after-completion' }), /codex_selected_model_rerouted/);
});
test('owned reroute arriving after a returned public completion invalidates later acceptance', async t => {
  const server = await fixture(t); server.ownThread('owned');
  assert.equal((await server.runTurn({ threadId: 'owned', input: [] })).status, 'completed');
  await assert.rejects(server.request('test/fire', { notice: notice() }), /codex_selected_model_rerouted/);
  assert.throws(() => server.assertNoModelReroute(), /codex_selected_model_rerouted/);
  await server.close(); assert.throws(() => server.assertNoModelReroute(), /codex_selected_model_rerouted/);
});
test('bounded pre-ownership notification joins only the subsequently confirmed owned ID', async t => {
  const server = await fixture(t);
  await server.request('test/fire', { notice: notice({ threadId: 'another-thread' }) });
  await server.request('test/fire', { notice: notice() });
  assert.doesNotThrow(() => server.assertNoModelReroute());
  assert.throws(() => server.ownThread('owned'), /codex_selected_model_rerouted/);
});
test('unrelated thread reroute cannot contaminate this owned thread before or during its turn', async t => {
  const server = await fixture(t);
  await server.request('test/fire', { notice: notice({ threadId: 'another-thread' }) }); server.ownThread('owned');
  assert.equal((await server.runTurn({ threadId: 'owned', input: [], mode: 'unrelated' })).status, 'completed');
  assert.doesNotThrow(() => server.assertNoModelReroute());
});
test('malformed foreign pre-ownership reroute does not abort an unrelated confirmed thread', async t => {
  const server = await fixture(t);
  await server.request('test/fire', { notice: notice({ threadId: 'another-thread', reason: '\nmalformed' }) });
  server.ownThread('owned'); assert.doesNotThrow(() => server.assertNoModelReroute());
  assert.equal((await server.runTurn({ threadId: 'owned', input: [] })).status, 'completed');
});
test('malformed pre-ownership reroute is rejected when the returned thread identity matches', async t => {
  const server = await fixture(t);
  await server.request('test/fire', { notice: notice({ reason: '\nmalformed' }) });
  assert.throws(() => server.ownThread('owned'), /codex_model_reroute_notification_invalid/);
});
test('reroute guard defaults off for backward-compatible protocol consumers', async t => {
  const server = await fixture(t, null); server.ownThread('owned');
  assert.equal((await server.runTurn({ threadId: 'owned', input: [], mode: 'before-response' })).status, 'completed');
  assert.doesNotThrow(() => server.assertNoModelReroute());
});
test('malformed owned reroute data is rejected without preserving arbitrary reason text', async t => {
  const server = await fixture(t); server.ownThread('owned');
  const sensitive = 'not-logged\nprivate-reason';
  await assert.rejects(server.request('test/fire', { notice: notice({ reason: sensitive }) }), error => {
    assert.equal((error as Error).message, 'codex_model_reroute_notification_invalid'); assert(!(error as Error).message.includes(sensitive)); return true;
  });
});
test('nonobject reroute params cannot bypass opt-in validation', async t => {
  const server = await fixture(t); server.ownThread('owned');
  await assert.rejects(server.request('test/fire', { notice: { method: 'model/rerouted', params: [] } }), /codex_model_reroute_notification_invalid/);
});
test('pre-ownership reroute identity retention is finite and rejects instead of trimming', async t => {
  const server = await fixture(t);
  for (let index = 0; index < 32; index++) await server.request('test/fire', { notice: notice({ threadId: 'candidate-' + index }) });
  await assert.rejects(server.request('test/fire', { notice: notice({ threadId: 'candidate-32' }) }), /codex_model_reroute_ownership_bound/);
});
