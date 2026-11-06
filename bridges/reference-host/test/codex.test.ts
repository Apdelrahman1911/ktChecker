import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdir, mkdtemp, readFile, realpath, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { CodexAppServer } from '../src/codex-app-server.js';
import { CodexHostPort } from '../src/codex-host.js';
import { CodexStore } from '../src/codex-store.js';
import { captureApplicationEvidence } from './application-evidence.js';
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
 state.methods.push(r.method);(state.requests??=[]).push({method:r.method,params:r.params});save();let result={};
 if(r.method==='thread/start'||r.method==='thread/resume')result={thread:{id:'synthetic-owned-thread'},model:state.wrongModel?'another-model':'gpt-6-astra',
   ...(state.omitEffort?{}:{reasoningEffort:state.wrongEffort?'low':r.params.config?.model_reasoning_effort??'low'})};
 else if(r.method==='account/read')result={account:null,requiresOpenaiAuth:false};
 else if(r.method==='thread/inject_items'){state.items.push(...r.params.items);save();}
 else if(r.method==='thread/turns/list')result={data:state.turns.map(x=>({id:x.id,status:'completed'})),nextCursor:null};
 else if(r.method==='thread/items/list')result={data:r.params.turnId?state.turns.filter(x=>x.id===r.params.turnId).map(x=>({turnId:x.id,item:{type:'agentMessage',id:x.itemId,text:x.text}})):state.items.map(x=>({item:{...x,type:'functionCallOutput'}})),nextCursor:null};
 else if(r.method==='turn/start'){
  if(state.crashWithoutPublicTurn){state.crashWithoutPublicTurn=false;save();return process.exit(0);}
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

async function fixture(t: { after(fn: () => Promise<unknown> | void): void }, overrides: Record<string, unknown> = {}, parent = tmpdir()) {
  const created = await mkdtemp(join(parent, 'ktchecker-codex-unit-'));
  t.after(() => rm(created, { recursive: true, force: true }));
  // CodexHostPort persists realpath(cwd). Direct test-only store reopens must
  // use that same identity, including macOS /var -> /private/var temp aliases.
  const dir = await realpath(created);
  const script = join(dir, 'synthetic-server.mjs'), statePath = join(dir, 'synthetic-state.json');
  await writeFile(script, fakeCodex);
  await writeFile(statePath, JSON.stringify({ methods: [], items: [], turns: [], ...overrides }));
  const r = registration(); const kernel = new FixtureKernel(r);
  const options = { directory: join(dir, 'session'), client: r.binding, cwd: dir,
    server: { executable: process.execPath, args: [script, statePath] } };
  return { dir, r, kernel, options, read: async () => JSON.parse(await readFile(statePath, 'utf8')) as {
    methods: string[]; turns: unknown[]; items: unknown[]; requests: { method: string; params: Record<string, unknown> }[] } };
}

test('synthetic fixture binds canonical cwd through a temporary parent alias without starting a server', async t => {
  const root = await mkdtemp(join(tmpdir(), 'ktchecker-codex-cwd-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const target = join(root, 'real-parent'), alias = join(root, 'parent-alias');
  await mkdir(target);
  // A directory junction needs no symlink privilege on Windows; elsewhere the
  // type is ignored and this is an ordinary directory symlink, all newly owned.
  await symlink(target, alias, 'junction');
  const f = await fixture(t, {}, alias);
  assert.equal(f.dir, await realpath(f.dir));
  assert.equal(dirname(f.dir), await realpath(target));
  assert.equal(f.options.cwd, f.dir);
  assert.equal(f.options.directory, join(f.dir, 'session'));
  assert.deepEqual((await f.read()).methods, []);
  assert.deepEqual((await f.read()).turns, []);
});

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


test('synthetic bounded backlog reconciles the exact lost-response batch before any resumed follow-up', {timeout:20_000}, async t=>{
  const f=await fixture(t,{crashAfterTurn:true}),options={...f.options,effort:'max'};
  let port=await CodexHostPort.open(options);t.after(()=>port.close());
  let journal=new InboxJournal(join(f.dir,'bounded-inbox'),port.binding);t.after(()=>journal.close());
  const broker=new KernelBroker(f.kernel,f.r);
  let adapter=new ReferenceHost(journal,broker,port);
  const events=['event-z','event-y','event-x'].map((id,index)=>event(f.r,id,undefined,index+1));
  f.kernel.eventsByClient.set(f.r.binding.clientId,events);await adapter.pollOnce();await adapter.drainContext();
  const items=events.map(value=>journal.read().inbox[value.eventId]!.item!);
  const sizes=items.map(item=>Buffer.byteLength(item.text)),maxContextBytes=sizes[0]!+sizes[1]!;
  assert(sizes.every(size=>size<=maxContextBytes));assert(sizes.reduce((a,b)=>a+b,0)>maxContextBytes);
  adapter=new ReferenceHost(journal,broker,port,{maxContextBytes});
  await assert.rejects(adapter.requestModelObservation(),/codex_disconnected/);
  const saved=structuredClone(journal.read().pendingModelRequest!);
  assert.deepEqual(saved.contexts,items.slice(0,2).sort((a,b)=>a.marker.localeCompare(b.marker)));
  const applicationRequest=structuredClone(port.evidence()[0]!.applicationRequest);
  const originalTurn=(await f.read()).requests.find(request=>request.method==='turn/start')!;
  assert.equal(originalTurn.params.effort,'max');assert.equal((await f.read()).turns.length,1);
  assert.equal(f.kernel.acked.size,3);assert.equal(f.kernel.observed.size,0);
  await port.close();journal.close();
  // This new lower-marker event arrives while the controller is offline, not in the saved request.
  events.push(event(f.r,'event-a',undefined,4));
  port=await CodexHostPort.open(options);journal=new InboxJournal(join(f.dir,'bounded-inbox'),port.binding);
  adapter=new ReferenceHost(journal,broker,port,{maxContextBytes});
  assert.equal(port.resumed,true);assert.deepEqual(journal.read().pendingModelRequest,saved);
  await adapter.pollOnce();await adapter.drainContext();
  assert.deepEqual(journal.read().pendingModelRequest,saved);assert.equal(f.kernel.acked.size,4);
  assert.equal(f.kernel.observed.size,0);assert.equal((await f.read()).turns.length,1);
  const calls:ModelRequest[]=[];const invoke=port.invokeModel.bind(port);
  port.invokeModel=async request=>{calls.push(structuredClone(request));return invoke(request);};
  // A lower restart budget must not trim/re-batch an already-sent uncertain request.
  const narrower=new ReferenceHost(journal,broker,port,{maxContextBytes:Math.max(...sizes)});
  await assert.rejects(narrower.requestModelObservation(),/required_context_exceeds_budget/);
  assert.deepEqual(journal.read().pendingModelRequest,saved);assert.equal(calls.length,0);
  assert.equal((await adapter.requestModelObservation()).observed,true);
  assert.deepEqual(calls,[saved]);assert.equal(journal.read().pendingModelRequest,null);
  const proof=await captureApplicationEvidence(port,journal);
  assert.equal(proof.invocationId,saved.invocationId);assert.deepEqual(proof.applicationRequest,applicationRequest);
  assert.equal(proof.model,'gpt-6-astra');assert.equal(proof.effort,'max');
  const recovered=await f.read();
  assert.deepEqual(recovered.requests.filter(request=>request.method==='turn/start'),[originalTurn]);
  assert.equal(recovered.turns.length,1);assert.equal(recovered.items.length,4);assert(!recovered.methods.includes('thread/list'));
  assert.deepEqual([...f.kernel.observed].sort(),['event-y','event-z']);
  for(const id of ['event-x','event-a']){
    assert.equal(journal.read().inbox[id]!.acknowledged,true);assert.equal(journal.read().inbox[id]!.observed,false);
  }
  const cut=await adapter.prepareFinal();
  assert.equal((await adapter.completeFinal(cut.cutId)).allowed,false);assert.equal(adapter.mutationFence().allowed,false);
  // A separately authorized call may now create a NEW request for just the remaining contexts.
  assert.equal((await adapter.requestModelObservation()).observed,true);
  assert.equal(calls.length,2);assert.notEqual(calls[1]!.invocationId,saved.invocationId);
  assert.deepEqual(calls[1]!.contexts,events.slice(2).map(value=>journal.read().inbox[value.eventId]!.item!).sort((a,b)=>a.marker.localeCompare(b.marker)));
  assert.equal(f.kernel.observed.size,4);assert.equal((await f.read()).turns.length,2);assert.equal((await f.read()).items.length,4);
  assert.equal((await f.read()).requests.filter(request=>request.method==='turn/start').length,2);
  assert(port.evidence().every(value=>value.model==='gpt-6-astra'&&value.effort==='max'));
  const complete=await adapter.completeFinal(cut.cutId);
  assert.equal(complete.allowed,true);assert.equal(complete.actionPerformed,false);assert.equal(adapter.mutationFence().allowed,true);
});

test('synthetic bounded backlog never skips an unresolved disconnected batch to invoke newer work', {timeout:20_000}, async t=>{
  const f=await fixture(t,{crashWithoutPublicTurn:true}),options={...f.options,effort:'max'};
  let port=await CodexHostPort.open(options);t.after(()=>port.close());
  let journal=new InboxJournal(join(f.dir,'unknown-inbox'),port.binding);t.after(()=>journal.close());
  const broker=new KernelBroker(f.kernel,f.r);let adapter=new ReferenceHost(journal,broker,port);
  const events=['event-z','event-y'].map((id,index)=>event(f.r,id,undefined,index+1));
  f.kernel.eventsByClient.set(f.r.binding.clientId,events);await adapter.pollOnce();await adapter.drainContext();
  const items=events.map(value=>journal.read().inbox[value.eventId]!.item!);
  const maxContextBytes=Math.max(...items.map(item=>Buffer.byteLength(item.text)));
  adapter=new ReferenceHost(journal,broker,port,{maxContextBytes});
  await assert.rejects(adapter.requestModelObservation(),/codex_disconnected/);
  const saved=structuredClone(journal.read().pendingModelRequest!);assert.deepEqual(saved.contexts,[items[0]]);
  await port.close();journal.close();events.push(event(f.r,'event-a',undefined,3));
  port=await CodexHostPort.open(options);journal=new InboxJournal(join(f.dir,'unknown-inbox'),port.binding);
  adapter=new ReferenceHost(journal,broker,port,{maxContextBytes});await adapter.pollOnce();await adapter.drainContext();
  // No recoverable completed public turn is NOT evidence that no inference happened.
  // Even repeated explicit recovery calls may only reconcile this exact saved request.
  for(let attempt=0;attempt<2;attempt++){
    await assert.rejects(adapter.requestModelObservation(),/codex_model_request_outcome_unknown/);
    assert.deepEqual(journal.read().pendingModelRequest,saved);assert.equal(f.kernel.observed.size,0);
  }
  const state=await f.read();
  assert.equal(state.requests.filter(request=>request.method==='turn/start').length,1);
  assert.equal(state.turns.length,0);assert.equal(state.items.length,3);assert.equal(f.kernel.acked.size,3);
  assert.equal(port.evidence().length,1);assert.equal(port.evidence()[0]!.status,'uncertain');
  const cut=await adapter.prepareFinal();
  assert.equal((await adapter.completeFinal(cut.cutId)).allowed,false);assert.equal(adapter.mutationFence().allowed,false);
});

for(const obstruction of ['oversized','insertion-failed'] as const){
  test(`synthetic saved reconciliation precedes ${obstruction} new context without dropping its obligation`, {timeout:20_000}, async t=>{
    const f=await fixture(t,{crashAfterTurn:true}),options={...f.options,effort:'max'};
    let port=await CodexHostPort.open(options);t.after(()=>port.close());
    let journal=new InboxJournal(join(f.dir,'pending-priority-inbox'),port.binding);t.after(()=>journal.close());
    const broker=new KernelBroker(f.kernel,f.r);let adapter=new ReferenceHost(journal,broker,port);
    const original=event(f.r,'event-z',undefined,1),events=[original];
    f.kernel.eventsByClient.set(f.r.binding.clientId,events);await adapter.pollOnce();await adapter.drainContext();
    const originalItem=structuredClone(journal.read().inbox[original.eventId]!.item!);
    const maxContextBytes=Buffer.byteLength(originalItem.text);
    adapter=new ReferenceHost(journal,broker,port,{maxContextBytes});
    await assert.rejects(adapter.requestModelObservation(),/codex_disconnected/);
    const saved=structuredClone(journal.read().pendingModelRequest!);
    assert.deepEqual(saved.contexts,[originalItem]);
    const before=await f.read(),originalStarts=before.requests.filter(request=>request.method==='turn/start');
    assert.equal(originalStarts.length,1);assert.equal(before.turns.length,1);assert.equal(before.items.length,1);
    assert.equal(f.kernel.acked.size,1);assert.equal(f.kernel.observed.size,0);
    await port.close();journal.close();

    port=await CodexHostPort.open(options);journal=new InboxJournal(join(f.dir,'pending-priority-inbox'),port.binding);
    adapter=new ReferenceHost(journal,broker,port,{maxContextBytes});
    assert.equal(port.resumed,true);assert.deepEqual(journal.read().pendingModelRequest,saved);
    const late=event(f.r,'event-y',undefined,2);
    if(obstruction==='oversized'){
      const payload=JSON.parse(late.payload) as {result:{findings:{diagnosis:string}[]}};
      payload.result.findings[0]!.diagnosis+='x'.repeat(maxContextBytes);
      late.payload=canonical(payload);late.payloadDigest=digest(late.payload);
    }
    events.push(late);
    let insertionAttempts=0;
    if(obstruction==='insertion-failed')port.insertContext=async()=>{insertionAttempts++;throw new Error('synthetic_required_insertion_failure');};
    const resumedCalls:ModelRequest[]=[];const invoke=port.invokeModel.bind(port);
    port.invokeModel=async request=>{resumedCalls.push(structuredClone(request));return invoke(request);};
    // Commit the new obligation, but do not let an explicit preparatory drain hide the ordering under test.
    await adapter.pollOnce();
    assert.deepEqual(journal.read().pendingModelRequest,saved);
    assert.equal(journal.read().inbox[late.eventId]!.item,null);
    assert.equal((await adapter.requestModelObservation()).observed,true);
    assert.deepEqual(resumedCalls,[saved]);assert.equal(journal.read().pendingModelRequest,null);
    assert.deepEqual([...f.kernel.observed],[original.eventId]);assert.equal(f.kernel.acked.size,1);
    assert.equal(insertionAttempts,0);
    const recovered=await f.read();
    assert.deepEqual(recovered.requests.filter(request=>request.method==='turn/start'),originalStarts);
    assert.equal(recovered.turns.length,1);assert.equal(recovered.items.length,1);
    const blocked=journal.read().inbox[late.eventId]!;
    assert.equal(blocked.event.payload,late.payload);assert.equal(blocked.event.payloadDigest,late.payloadDigest);
    assert.equal(blocked.item,null);assert.equal(blocked.inserted,null);assert.equal(blocked.acknowledged,false);
    assert.equal(blocked.observation,null);assert.equal(blocked.observed,false);
    assert.equal(adapter.mutationFence().allowed,false);

    // Once pending is gone, ordinary draining must STILL expose the obstruction before any new invocation.
    const failure=obstruction==='oversized'?/required_context_exceeds_budget/:/synthetic_required_insertion_failure/;
    await assert.rejects(adapter.requestModelObservation(),failure);
    assert.deepEqual(resumedCalls,[saved]);assert.equal(journal.read().pendingModelRequest,null);
    assert.equal(insertionAttempts,obstruction==='insertion-failed'?1:0);
    const retained=journal.read().inbox[late.eventId]!;
    assert.deepEqual(retained.event,late);assert.equal(retained.inserted,null);assert.equal(retained.acknowledged,false);
    assert.equal(retained.observation,null);assert.equal(retained.observed,false);
    assert.deepEqual(journal.read().inbox[original.eventId]!.item,originalItem);
    assert.deepEqual([...f.kernel.observed],[original.eventId]);assert.equal(f.kernel.acked.size,1);
    assert.equal(adapter.mutationFence().allowed,false);
    const finalState=await f.read();
    assert.deepEqual(finalState.requests.filter(request=>request.method==='turn/start'),originalStarts);
    assert.equal(finalState.turns.length,1);assert.equal(finalState.items.length,1);
  });
}

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

test('synthetic exact Astra/max selection is persisted and binds exact turn/start bytes and public witness', async t => {
  const f = await fixture(t); const options = { ...f.options, effort: 'max' };
  let port = await CodexHostPort.open(options); t.after(() => port.close());
  const journal = new InboxJournal(join(f.dir, 'max-inbox'), port.binding); t.after(() => journal.close());
  const adapter = new ReferenceHost(journal, new KernelBroker(f.kernel, f.r), port);
  f.kernel.eventsByClient.set(f.r.binding.clientId, [event(f.r)]); await adapter.pollOnce();
  assert.equal((await adapter.requestModelObservation()).observed, true);
  const proof = await captureApplicationEvidence(port, journal);
  assert.equal(proof.model, 'gpt-6-astra'); assert.equal(proof.effort, 'max');
  assert.equal(JSON.parse(proof.serializedApplicationRequest as string).params.effort, 'max');
  const requests = (await f.read()).requests;
  const started = requests.find(value => value.method === 'thread/start')!;
  assert.equal(started.params.model, 'gpt-6-astra'); assert.equal(started.params.allowProviderModelFallback, false);
  assert.deepEqual(started.params.config, { model_reasoning_effort: 'max' });
  assert.equal(requests.filter(value => value.method === 'turn/start').length, 1);
  assert.equal(requests.find(value => value.method === 'turn/start')!.params.effort, 'max');
  await port.close();
  await assert.rejects(CodexHostPort.open(f.options), /codex_session_binding_mismatch/);
  port = await CodexHostPort.open(options); assert.equal(port.resumed, true);
  assert.equal(port.evidence()[0]!.effort, 'max'); assert.equal((await f.read()).turns.length, 1);
});

test('synthetic mismatched explicit effort is unavailable before any model turn, with no fallback', async t => {
  const f = await fixture(t, { wrongEffort: true });
  await assert.rejects(CodexHostPort.open({ ...f.options, effort: 'max' }), /codex_requested_effort_not_confirmed/);
  const state = await f.read(); assert.equal(state.turns.length, 0); assert.equal(state.methods.filter(value => value === 'thread/start').length, 1);
});

test('synthetic missing explicit effort confirmation is unavailable, but legacy default remains low', async t => {
  const f = await fixture(t, { omitEffort: true });
  await assert.rejects(CodexHostPort.open({ ...f.options, effort: 'max' }), /codex_requested_effort_not_confirmed/);
  const g = await fixture(t, { omitEffort: true }); const port = await CodexHostPort.open(g.options); t.after(() => port.close());
  const journal = new InboxJournal(join(g.dir, 'legacy-inbox'), port.binding); t.after(() => journal.close());
  const adapter = new ReferenceHost(journal, new KernelBroker(g.kernel, g.r), port);
  g.kernel.eventsByClient.set(g.r.binding.clientId, [event(g.r)]); await adapter.pollOnce();
  assert.equal((await adapter.requestModelObservation()).observed, true); assert.equal(port.evidence()[0]!.effort, 'low');
});

test('historical missing effort fields can reopen only as the historical low selection', async t => {
  const f = await fixture(t), directory = join(f.dir, 'legacy-store');
  const first = new CodexStore(directory, f.r.binding, f.dir, 'gpt-6-astra');
  first.edit(state => { delete state.effort; }); first.close();
  const second = new CodexStore(directory, f.r.binding, f.dir, 'gpt-6-astra'); second.close();
  assert.throws(() => new CodexStore(directory, f.r.binding, f.dir, 'gpt-6-astra', 'max'), /codex_session_binding_mismatch/);
});

test('invalid explicit effort rejects before launching a synthetic server or creating a store', async t => {
  const f = await fixture(t);
  for (const effort of ['', 'max\n', 'max effort', 'x'.repeat(33)]) await assert.rejects(CodexHostPort.open({ ...f.options, effort }), /invalid_codex_effort/);
  assert.equal((await f.read()).methods.length, 0);
});

test('mismatched persisted invocation effort rejects owner selection even when session selection matches', async t => {
  const f = await fixture(t); const options = { ...f.options, effort: 'max' };
  const port = await CodexHostPort.open(options); t.after(() => port.close());
  const journal = new InboxJournal(join(f.dir, 'mismatch-inbox'), port.binding); t.after(() => journal.close());
  const adapter = new ReferenceHost(journal, new KernelBroker(f.kernel, f.r), port);
  f.kernel.eventsByClient.set(f.r.binding.clientId, [event(f.r)]); await adapter.pollOnce();
  assert.equal((await adapter.requestModelObservation()).observed, true); await port.close();
  const store = new CodexStore(options.directory, f.r.binding, f.dir, 'gpt-6-astra', 'max');
  store.edit(state => { Object.values(state.invocations)[0]!.effort = 'low'; }); store.close();
  await assert.rejects(CodexHostPort.open(options), /codex_session_binding_mismatch/);
  assert.equal((await f.read()).turns.length, 1);
});


test('synthetic saved reconciliation publishes its proof despite unrelated ACK backpressure without resending', {timeout:20_000}, async t=>{
  const f=await fixture(t,{crashAfterTurn:true}),options={...f.options,effort:'max'};
  let port=await CodexHostPort.open(options);t.after(()=>port.close());
  let journal=new InboxJournal(join(f.dir,'receipt-priority-inbox'),port.binding);t.after(()=>journal.close());
  const broker=new KernelBroker(f.kernel,f.r);let adapter=new ReferenceHost(journal,broker,port);
  const original=event(f.r,'event-z',undefined,1),late=event(f.r,'event-y',undefined,2),events=[original];
  f.kernel.eventsByClient.set(f.r.binding.clientId,events);await adapter.pollOnce();await adapter.drainContext();
  await assert.rejects(adapter.requestModelObservation(),/codex_disconnected/);
  const saved=structuredClone(journal.read().pendingModelRequest!);
  const applicationRequest=structuredClone(port.evidence()[0]!.applicationRequest);
  const originalStarts=(await f.read()).requests.filter(request=>request.method==='turn/start');
  assert.equal(originalStarts.length,1);assert.equal(originalStarts[0]!.params.effort,'max');
  await port.close();journal.close();events.push(late);
  port=await CodexHostPort.open(options);journal=new InboxJournal(join(f.dir,'receipt-priority-inbox'),port.binding);
  adapter=new ReferenceHost(journal,broker,port);assert.equal(port.resumed,true);
  const ack=broker.ack.bind(broker),ackFailure=new Error('synthetic_unrelated_ack_backpressure');
  let rejectLateAck=true,lateAckAttempts=0;
  broker.ack=async(eventIds,receipt)=>{
    if(eventIds.includes(late.eventId)){
      lateAckAttempts++;if(rejectLateAck)throw ackFailure;
    }
    return ack(eventIds,receipt);
  };
  await adapter.pollOnce();await assert.rejects(adapter.drainContext(),error=>error===ackFailure);
  const retained=structuredClone(journal.read().inbox[late.eventId]!);
  assert(retained.inserted);assert.equal(retained.acknowledged,false);assert.equal(retained.observation,null);
  assert.deepEqual(journal.read().pendingModelRequest,saved);
  const resumedCalls:ModelRequest[]=[];const invoke=port.invokeModel.bind(port);
  port.invokeModel=async request=>{resumedCalls.push(structuredClone(request));return invoke(request);};
  // The failed unrelated receipt still rejects this call, but cannot withhold a ready recovered proof.
  await assert.rejects(adapter.requestModelObservation(),error=>error===ackFailure);
  assert.deepEqual(resumedCalls,[saved]);assert.equal(journal.read().pendingModelRequest,null);
  assert.equal(journal.read().inbox[original.eventId]!.observed,true);
  assert.deepEqual([...f.kernel.observed],[original.eventId]);assert.equal(f.kernel.acked.size,1);
  assert.deepEqual(journal.read().inbox[late.eventId],retained);assert.equal(adapter.mutationFence().allowed,false);
  const observation=structuredClone(journal.read().inbox[original.eventId]!.observation!);
  const proof=await captureApplicationEvidence(port,journal);
  assert.equal(proof.invocationId,saved.invocationId);assert.deepEqual(proof.applicationRequest,applicationRequest);
  assert.equal(proof.model,'gpt-6-astra');assert.equal(proof.effort,'max');
  // The retained ACK error also blocks a NEW invocation; recovery must not resend the old one.
  await assert.rejects(adapter.requestModelObservation(),error=>error===ackFailure);
  assert.deepEqual(resumedCalls,[saved]);assert.equal(journal.read().pendingModelRequest,null);
  assert.deepEqual(journal.read().inbox[original.eventId]!.observation,observation);
  rejectLateAck=false;await adapter.syncReceipts();assert.equal(lateAckAttempts,4);
  const after=journal.read().inbox[late.eventId]!;
  assert.deepEqual(after.event,late);assert.deepEqual(after.item,retained.item);assert.deepEqual(after.inserted,retained.inserted);
  assert.equal(after.acknowledged,true);assert.equal(after.observation,null);assert.equal(after.observed,false);
  const cut=await adapter.prepareFinal(),decision=await adapter.completeFinal(cut.cutId);
  assert.equal(decision.allowed,false);assert.equal(decision.actionPerformed,false);assert.equal(adapter.mutationFence().allowed,false);
  const state=await f.read();assert.deepEqual(state.requests.filter(request=>request.method==='turn/start'),originalStarts);
  assert.equal(state.turns.length,1);assert.equal(state.items.length,2);assert(!state.methods.includes('thread/list'));
  assert.deepEqual([...f.kernel.observed],[original.eventId]);assert.equal(f.kernel.acked.size,2);
  assert.deepEqual(resumedCalls,[saved]);assert.equal(port.evidence().length,1);
});
