import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { CodexAppServer } from '../src/codex-app-server.js';
import { CodexHostPort } from '../src/codex-host.js';
import { expectedPublicResponse, verifyPublicResponse } from '../src/codex-observation.js';
import { canonical, digest, InboxJournal } from '../src/journal.js';
import { KernelBroker } from '../src/broker.js';
import { ReferenceHost } from '../src/adapter.js';
import { event, FixtureKernel, registration } from './fixtures.js';
import type { ModelRequest } from '../src/types.js';

// Entirely SYNTHETIC app-server/model fixture. Positive outcomes below are state-machine tests,
// not live Codex inference or broker/compiler evidence. No network or inference runs in npm test.
const fakeCodex = `
import {readFileSync,writeFileSync} from 'node:fs';
import {createInterface} from 'node:readline';
import {expectedPublicResponse} from ${JSON.stringify(new URL('../src/codex-observation.js', import.meta.url).href)};
const path=process.argv[2];
let state=JSON.parse(readFileSync(path,'utf8'));
const save=()=>writeFileSync(path,JSON.stringify(state));
const send=x=>process.stdout.write(JSON.stringify(x)+'\\n');
createInterface({input:process.stdin}).on('line',line=>{
 const r=JSON.parse(line);if(r.id===undefined)return;
 state.methods.push(r.method);save();let result={};
 if(r.method==='thread/start'||r.method==='thread/resume')result={thread:{id:'synthetic-owned-thread'},model:state.wrongModel?'another-model':'gpt-6-astra'};
 else if(r.method==='account/read')result={account:null,requiresOpenaiAuth:false};
 else if(r.method==='thread/inject_items'){state.items.push(...r.params.items);save();}
 else if(r.method==='thread/turns/list')result={data:state.turns.map(x=>({id:x.id,status:'completed'})),nextCursor:null};
 else if(r.method==='thread/items/list')result={data:r.params.turnId?state.turns.filter(x=>x.id===r.params.turnId).map(x=>({turnId:x.id,item:{type:'agentMessage',id:x.itemId,text:x.text}})):state.items.map(x=>({item:{...x,type:'functionCallOutput'}})),nextCursor:null};
 else if(r.method==='turn/start'){
  const input=JSON.parse(r.params.toolOutput.output);
  const contexts=input.contexts.map(c=>({...c,digest:c.contextDigest,marker:JSON.parse(c.text.slice(c.text.indexOf('\\n')+1)).marker}));
  const text=state.markerOnly?'marker only':JSON.stringify(expectedPublicResponse({...input,contexts}));
  const turn={id:'synthetic-turn-'+(state.turns.length+1),itemId:'synthetic-public-'+(state.turns.length+1),text};
  state.turns.push(turn);save();
  if(state.crashAfterTurn){state.crashAfterTurn=false;save();return process.exit(0);}
  send({method:'item/reasoning/textDelta',params:{threadId:r.params.threadId,turnId:turn.id,delta:'SYNTHETIC_NOT_FOR_CAPTURE'}});
  send({method:'item/completed',params:{threadId:'unowned-thread',turnId:turn.id,item:{type:'agentMessage',id:'other',text:'NOT_OWNED'}}});
  send({method:'item/completed',params:{threadId:r.params.threadId,turnId:turn.id,item:{type:'agentMessage',id:turn.itemId,text}}});
  send({method:'turn/completed',params:{threadId:r.params.threadId,turn:{id:turn.id,status:'completed'}}});
  result={turn:{id:turn.id,status:'inProgress'}};
 }
 send({id:r.id,result});
});`;

test('Codex evidence boundary rejects malformed UTF-8 instead of replacing public bytes',async t=>{
  const script=`import {createInterface} from 'node:readline';
    createInterface({input:process.stdin}).on('line',line=>{const r=JSON.parse(line);if(!r.id)return;
    const bytes=Buffer.from(JSON.stringify({id:r.id,result:{value:'X'}})+'\\n');
    bytes[bytes.indexOf(Buffer.from('"X"'))+1]=0xff;process.stdout.write(bytes);});`;
  const server=new CodexAppServer({executable:process.execPath,args:['--input-type=module','-e',script]});
  t.after(()=>server.close());await assert.rejects(server.connect(),/codex_protocol_error/);
});

async function fixture(t: { after(fn: () => Promise<unknown> | void): void }, overrides: Record<string, unknown> = {}) {
  const dir = await mkdtemp(join(tmpdir(), 'ktchecker-codex-unit-'));
  const script = join(dir, 'synthetic-server.mjs'), statePath = join(dir, 'synthetic-state.json');
  await writeFile(script, fakeCodex);
  await writeFile(statePath, JSON.stringify({ methods: [], items: [], turns: [], ...overrides }));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const r = registration(); const kernel = new FixtureKernel(r);
  const options = { directory: join(dir, 'session'), client: r.binding, cwd: dir,
    server: { executable: process.execPath, args: [script, statePath] } };
  return { dir, r, kernel, options, read: async () => JSON.parse(await readFile(statePath, 'utf8')) as { methods: string[]; turns: unknown[]; items: unknown[] } };
}

test('synthetic Codex fixture proves only strict public-content binding and filters reasoning/unowned events', async t => {
  const f = await fixture(t); const port = await CodexHostPort.open(f.options); t.after(() => port.close());
  const journal = new InboxJournal(join(f.dir, 'inbox'), port.binding); t.after(() => journal.close());
  const adapter = new ReferenceHost(journal, new KernelBroker(f.kernel, f.r), port);
  f.kernel.eventsByClient.set(f.r.binding.clientId, [event(f.r)]);
  await adapter.pollOnce(); await adapter.drainContext();
  assert.equal(f.kernel.acked.size, 1); assert.equal(f.kernel.observed.size, 0);
  assert.equal((await adapter.requestModelObservation()).observed, true);
  const evidence = JSON.stringify(port.evidence());
  assert(!evidence.includes('SYNTHETIC_NOT_FOR_CAPTURE')); assert(!evidence.includes('NOT_OWNED'));
  assert(!evidence.includes('Nullable name')); assert(evidence.includes('internalInferenceRequestTrace'));
  const item = journal.read().inbox['event-1']!.item!;
  const request: ModelRequest = { invocationId: 'fixture-invocation', hostThreadId: item.hostThreadId, generation: item.generation, contexts: [item], requestDigest: 'fixture-request-digest' };
  const expected = expectedPublicResponse(request);
  assert(verifyPublicResponse(canonical(expected), request));
  assert(!verifyPublicResponse(item.marker, request));
  for (const replaced of ['Nullable name cannot be assigned to String.', 'Label.kt', 'Compile this fixture without the type mismatch.', item.digest]) {
    assert(!verifyPublicResponse(canonical(expected).replace(replaced, 'corrupt'), request));
  }
  const state = await f.read(); assert(!state.methods.includes('thread/list')); assert.equal(state.turns.length, 1);
});

test('synthetic Codex marker-only reply leaves acknowledged diagnosis unobserved', async t => {
  const f = await fixture(t, { markerOnly: true }); const port = await CodexHostPort.open(f.options); t.after(() => port.close());
  const journal = new InboxJournal(join(f.dir, 'inbox'), port.binding); t.after(() => journal.close());
  const adapter = new ReferenceHost(journal, new KernelBroker(f.kernel, f.r), port);
  f.kernel.eventsByClient.set(f.r.binding.clientId, [event(f.r)]);
  await adapter.pollOnce(); assert.equal((await adapter.requestModelObservation()).observed, false);
  assert.equal(f.kernel.acked.size, 1); assert.equal(f.kernel.observed.size, 0); assert.equal(adapter.mutationFence().allowed, false);
});

test('synthetic lost turn response reconciles same saved invocation after controller restart, without resending', async t => {
  const f = await fixture(t, { crashAfterTurn: true });
  let port = await CodexHostPort.open(f.options); t.after(() => port.close());
  let journal = new InboxJournal(join(f.dir, 'inbox'), port.binding); t.after(() => journal.close());
  let adapter = new ReferenceHost(journal, new KernelBroker(f.kernel, f.r), port);
  f.kernel.eventsByClient.set(f.r.binding.clientId, [event(f.r)]); await adapter.pollOnce();
  await assert.rejects(adapter.requestModelObservation(), /codex_disconnected/);
  const invocationId = journal.read().pendingModelRequest!.invocationId;
  await port.close(); journal.close();
  port = await CodexHostPort.open(f.options); journal = new InboxJournal(join(f.dir, 'inbox'), port.binding);
  adapter = new ReferenceHost(journal, new KernelBroker(f.kernel, f.r), port);
  assert.equal(port.resumed, true); assert.equal(journal.read().pendingModelRequest!.invocationId, invocationId);
  assert.equal((await adapter.requestModelObservation()).observed, true);
  assert.equal((await f.read()).turns.length, 1); assert.equal((await f.read()).items.length, 1);
});

test('changed Codex model is rejected before any turn, and live controller lease is exclusive', async t => {
  const f = await fixture(t, { wrongModel: true }); await assert.rejects(CodexHostPort.open(f.options), /codex_requested_model_not_confirmed/);
  assert.equal((await f.read()).turns.length, 0);
  const g = await fixture(t); const port = await CodexHostPort.open(g.options); t.after(() => port.close());
  await assert.rejects(CodexHostPort.open(g.options), /codex_controller_busy/);
  await assert.rejects(CodexHostPort.open({ ...g.options, client: { ...g.r.binding, generation: 2 } }), /codex_session_binding_mismatch/);
});

test('Codex client refuses unowned thread turns and has a content-addressed serialized application request', async t => {
  const f = await fixture(t); const server = new CodexAppServer(f.options.server); t.after(() => server.close()); await server.connect();
  await assert.rejects(server.runTurn({ threadId: 'guessed', input: [] }), /codex_unowned_thread/);
  let recorded: unknown;
  const result = await server.requestRecorded('account/read', { refreshToken: false }, evidence => { recorded = evidence; });
  assert.deepEqual(recorded, result.evidence);
  assert.equal(result.evidence.serializedRequestDigest, digest(JSON.stringify({ id: result.evidence.applicationRequestId, method: 'account/read', params: { refreshToken: false } })));
  assert.equal((result.result as { requiresOpenaiAuth: boolean }).requiresOpenaiAuth, false);
});
