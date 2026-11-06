import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import type { JsonObject } from '@ktchecker/mcp-preview/rpc';
import { canonical, digest, HostError, InboxJournal } from '../src/journal.js';
import type { ModelRequest } from '../src/types.js';
import { KernelBroker } from '../src/broker.js';
import { ReferenceHost } from '../src/adapter.js';
import { FixtureHost, FixtureKernel, registration, binding, event, S1, S2 } from './fixtures.js';
async function setup(t:{after(fn:()=>Promise<unknown>|void):void}){
  const dir=await mkdtemp(join(tmpdir(),'ktchecker-adapter-'));const r=registration();const kernel=new FixtureKernel(r),port=new FixtureHost(binding(r));
  const journal=new InboxJournal(dir,binding(r)),broker=new KernelBroker(kernel,r),adapter=new ReferenceHost(journal,broker,port);
  t.after(async()=>{journal.close();await rm(dir,{recursive:true,force:true});});
  return {dir,r,kernel,port,journal,broker,adapter};
}
const patch=(patchId:string,snapshotId=S1)=>({patchId,idempotencyKey:'key-'+patchId,input:{snapshot:{snapshotId},materializedRoot:'/synthetic-owner-fixture'}});

interface HeldModelInvocationScope {
  readonly signal: AbortSignal;
  after(fn: () => void): void;
}
type HeldModelOutcome<T> = { kind: 'returned'; value: T } | { kind: 'rejected'; reason: unknown };
/** Test-only handshake for this fresh FixtureHost. Observe the operation before waiting for
 * entry: an early return/rejection must fail, not leave a polling timer running forever.
 * Abort bounds only this harness wait; it never claims cancellation of underlying work. */
async function withHeldModelInvocation<T>(t: HeldModelInvocationScope, port: FixtureHost,
  operation: () => Promise<T>, whileHeld: () => void | Promise<void>): Promise<T> {
  if(t.signal.aborted)throw t.signal.reason??new Error('synthetic_model_invocation_wait_aborted');
  assert.equal(port.barrier,null,'synthetic invocation harness requires a fresh unheld fixture');
  const originalInvoke=port.invokeModel,originalBarrier=port.barrier;
  let enter!:()=>void,release!:()=>void;
  const entered=new Promise<void>(resolve=>{enter=resolve;});
  const barrier=new Promise<void>(resolve=>{release=resolve;});
  port.barrier=barrier;
  port.invokeModel=request=>{
    const pending=originalInvoke.call(port,request);enter();return pending;
  };
  t.after(release); // Exact fixture barrier only; idempotent with every finally path below.
  let abort!:()=>void;
  const aborted=new Promise<never>((_resolve,reject)=>{
    abort=()=>reject(t.signal.reason??new Error('synthetic_model_invocation_wait_aborted'));
    t.signal.addEventListener('abort',abort,{once:true});if(t.signal.aborted)abort();
  });
  // Capture synchronous throws and attach both terminal handlers before operation starts.
  const settled:Promise<HeldModelOutcome<T>>=Promise.resolve().then(operation).then(
    value=>({kind:'returned' as const,value}),reason=>({kind:'rejected' as const,reason}));
  const premature=settled.then(outcome=>{
    if(outcome.kind==='rejected')throw outcome.reason;
    throw new Error('synthetic_model_observation_settled_before_invoke');
  });
  let failure:{reason:unknown}|undefined,outcome:HeldModelOutcome<T>|undefined;
  try{
    await Promise.race([entered,premature,aborted]);
    await Promise.race([Promise.resolve().then(whileHeld),aborted]);
  }catch(reason){failure={reason};}
  finally{
    release();
    try{outcome=await Promise.race([settled,aborted]);}
    catch(reason){failure??={reason};}
    finally{
      t.signal.removeEventListener('abort',abort);
      port.invokeModel=originalInvoke;port.barrier=originalBarrier;
    }
  }
  if(failure)throw failure.reason;
  assert(outcome,'synthetic invocation operation outcome required');
  if(outcome.kind==='rejected')throw outcome.reason;
  return outcome.value;
}

/** Hold a real fixture response AFTER it is computed, without clocks or production RPCs. */
function holdGateReply(kernel:FixtureKernel,method:'gate.prepare'|'gate.complete'){
  let enter!:()=>void,release!:()=>void,held=false,response:unknown;
  const entered=new Promise<void>(resolve=>{enter=resolve;});
  const barrier=new Promise<void>(resolve=>{release=resolve;});
  const dispatch=kernel.request.bind(kernel);
  kernel.request=async<T=JsonObject>(name:string,params:JsonObject):Promise<T>=>{
    const result=await dispatch<T>(name,params);
    if(name===method&&!held){held=true;response=structuredClone(result);enter();await barrier;}
    return result;
  };
  return {entered,release,get response(){return response;}};
}

test('late gate.prepare cannot attach a P1 cut to the newer local P2 epoch', {timeout:10_000}, async t=>{
  const {r,kernel,port,journal,adapter}=await setup(t);
  kernel.eventsByClient.set(r.binding.clientId,[event(r)]);port.mode='protected-fixture';
  await adapter.recordCompletedPatch(patch('p1'));await adapter.pollOnce();
  assert.equal((await adapter.requestModelObservation()).observed,true);
  const before=journal.read(),observation=before.inbox['event-1']!.observation;
  const held=holdGateReply(kernel,'gate.prepare');t.after(held.release);
  const pending=adapter.prepareFinal().then(value=>({value,error:null}),error=>({value:null,error}));
  try{
    await held.entered;
    await adapter.recordCompletedPatch(patch('p2',S2));
    kernel.eventsByClient.get(r.binding.clientId)!.push(event(r,'event-2',S2,2));await adapter.pollOnce();
    assert.equal(journal.read().cut,null);
  }finally{held.release();}
  const outcome=await pending;
  assert.equal(outcome.error?.code,'gate_cut_invalidated','a late P1 cut must not become a current P2 cut');
  assert.equal(outcome.value,null);
  const after=journal.read();
  assert.equal(after.cut,null);assert.equal(after.currentSnapshotId,S2);assert.equal(after.localEpoch,before.localEpoch+1);
  assert.deepEqual(Object.keys(after.submissions).sort(),['key-p1','key-p2']);
  assert(Object.values(after.submissions).every(submission=>submission.status!==null));
  assert.deepEqual(after.inbox['event-1']!.observation,observation);assert.equal(after.inbox['event-1']!.observed,true);
  assert.equal(after.inbox['event-2']!.observed,false);assert.equal(adapter.mutationFence().allowed,false);
  assert.equal(kernel.calls.filter(call=>call.method==='gate.prepare').length,1);
  assert.equal(kernel.calls.filter(call=>call.method==='gate.complete').length,0);
});

test('late gate.complete cannot return allowed after a locally recorded P2 invalidates its exact cut', {timeout:10_000}, async t=>{
  const {r,kernel,port,journal,adapter}=await setup(t);
  kernel.eventsByClient.set(r.binding.clientId,[event(r)]);port.mode='protected-fixture';
  await adapter.recordCompletedPatch(patch('p1'));await adapter.pollOnce();
  assert.equal((await adapter.requestModelObservation()).observed,true);
  const cut=await adapter.prepareFinal(),before=journal.read(),observation=before.inbox['event-1']!.observation;
  const held=holdGateReply(kernel,'gate.complete');t.after(held.release);
  const pending=adapter.completeFinal(cut.cutId).then(value=>({value,error:null}),error=>({value:null,error}));
  try{
    await held.entered;
    assert.equal((held.response as {allowed:boolean}).allowed,true);
    assert.equal((held.response as {actionPerformed:boolean}).actionPerformed,false);
    await adapter.recordCompletedPatch(patch('p2',S2));
    kernel.eventsByClient.get(r.binding.clientId)!.push(event(r,'event-2',S2,2));await adapter.pollOnce();
    assert.equal(journal.read().cut,null);
  }finally{held.release();}
  const outcome=await pending;
  assert.equal(outcome.error?.code,'gate_cut_invalidated','a late allowed P1 decision must not outlive known local invalidation');
  assert.equal(outcome.value,null);
  const after=journal.read();
  assert.equal(after.cut,null);assert.equal(after.currentSnapshotId,S2);assert.equal(after.localEpoch,before.localEpoch+1);
  assert.deepEqual(Object.keys(after.submissions).sort(),['key-p1','key-p2']);
  assert(Object.values(after.submissions).every(submission=>submission.status!==null));
  assert.deepEqual(after.inbox['event-1']!.observation,observation);assert.equal(after.inbox['event-1']!.observed,true);
  assert.equal(after.inbox['event-2']!.observed,false);assert.equal(adapter.mutationFence().allowed,false);
  assert.equal(kernel.calls.filter(call=>call.method==='gate.complete').length,1);
});

test('overlapping gate.prepare replies cannot overwrite the cut already installed at the same epoch', {timeout:10_000}, async t=>{
  const {kernel,journal,adapter}=await setup(t);
  const held=holdGateReply(kernel,'gate.prepare');t.after(held.release);
  const pending=adapter.prepareFinal().then(value=>({value,error:null}),error=>({value:null,error}));
  let replacement;
  try{
    await held.entered;
    // No controller-wide lock: this independent request must finish while the first is held.
    replacement=await adapter.prepareFinal();
    assert.equal(journal.read().localEpoch,0);
  }finally{held.release();}
  const outcome=await pending;
  assert.equal(outcome.error?.code,'gate_cut_invalidated');assert.equal(outcome.value,null);
  assert.deepEqual(journal.read().cut?.value,replacement);
  assert.equal(kernel.calls.filter(call=>call.method==='gate.prepare').length,2);
});

test('late gate.complete rejects a changed cut vector even when cut ID and local epoch are unchanged', {timeout:10_000}, async t=>{
  const {kernel,journal,adapter}=await setup(t);const cut=await adapter.prepareFinal();
  const held=holdGateReply(kernel,'gate.complete');t.after(held.release);
  const pending=adapter.completeFinal(cut.cutId).then(value=>({value,error:null}),error=>({value:null,error}));
  try{
    await held.entered;
    journal.edit(state=>{state.cut!.value.eventFrontier++;});
    assert.equal(journal.read().localEpoch,0);assert.equal(journal.read().cut?.value.cutId,cut.cutId);
  }finally{held.release();}
  const outcome=await pending;
  assert.equal(outcome.error?.code,'gate_cut_invalidated');assert.equal(outcome.value,null);
  assert.equal(journal.read().cut?.value.eventFrontier,cut.eventFrontier+1);
  assert.equal(kernel.calls.filter(call=>call.method==='gate.complete').length,1);
});

for(const method of ['gate.prepare','gate.complete'] as const){
  test(`late ${method} rejects changed lineage binding without relabeling the returned decision`, {timeout:10_000}, async t=>{
    const {kernel,journal,adapter}=await setup(t);
    const cut=method==='gate.complete'?await adapter.prepareFinal():null;
    const held=holdGateReply(kernel,method);t.after(held.release);
    const operation=cut?adapter.completeFinal(cut.cutId):adapter.prepareFinal();
    const pending=operation.then(value=>({value,error:null}),error=>({value:null,error}));
    try{
      await held.entered;
      // Protected journal fault injection, not a supported in-place binding migration.
      journal.edit(state=>{state.binding.client.lineageId='replacement-lineage';});
      assert.equal(journal.read().localEpoch,0);
    }finally{held.release();}
    const outcome=await pending;
    assert.equal(outcome.error?.code,'gate_cut_invalidated');assert.equal(outcome.value,null);
    assert.equal(journal.read().binding.client.lineageId,'replacement-lineage');
  });
}

test('an in-flight gate.prepare preserves independent observation progress at the unchanged response fence', {timeout:10_000}, async t=>{
  const {r,kernel,port,journal,adapter}=await setup(t);
  kernel.eventsByClient.set(r.binding.clientId,[event(r)]);port.mode='protected-fixture';
  await adapter.recordCompletedPatch(patch('p1'));await adapter.pollOnce();
  const held=holdGateReply(kernel,'gate.prepare');t.after(held.release);
  const pending=adapter.prepareFinal();
  try{
    await held.entered;
    assert.equal(journal.read().inbox['event-1']!.acknowledged,true);
    assert.equal((await adapter.requestModelObservation()).observed,true);
  }finally{held.release();}
  const cut=await pending,state=journal.read();
  assert.equal(state.cut?.value.cutId,cut.cutId);assert.equal(state.localEpoch,1);
  assert.equal(state.inbox['event-1']!.observed,true);assert.equal(kernel.observed.has('event-1'),true);
  assert.equal(port.modelCalls,1);assert.equal(Object.keys(state.submissions).length,1);
  const decision=await adapter.completeFinal(cut.cutId);
  assert.equal(decision.allowed,true);assert.equal(decision.actionPerformed,false);
});

test('insertion ACK, transport acceptance and test-only witness NEVER pass observation/final gate',async t=>{
  const {r,kernel,port,journal,adapter}=await setup(t);kernel.eventsByClient.set(r.binding.clientId,[event(r)]);
  await adapter.recordCompletedPatch(patch('p1'));await adapter.pollOnce();await adapter.drainContext();
  assert.equal(port.modelCalls,0);assert.equal(journal.read().inbox['event-1']?.acknowledged,true);assert.equal(journal.read().inbox['event-1']?.observed,false);
  const cut=await adapter.prepareFinal();assert.equal((await adapter.completeFinal(cut.cutId)).allowed,false);
  for(const mode of ['insertion-only','test-only','unavailable'] as const){port.mode=mode;assert.equal((await adapter.requestModelObservation()).observed,false);}
  assert.equal(kernel.observed.size,0);assert.equal(adapter.mutationFence().allowed,false);
});
test('synthetic protected-witness SPI exercises observation ordering, NOT live-host proof',async t=>{
  const {r,kernel,port,journal,adapter}=await setup(t);kernel.eventsByClient.set(r.binding.clientId,[event(r)]);port.mode='protected-fixture';
  await adapter.recordCompletedPatch(patch('p1'));await adapter.pollOnce();const cut=await adapter.prepareFinal();
  assert.equal((await adapter.requestModelObservation()).observed,true);assert.equal(journal.read().inbox['event-1']?.observed,true);
  const calls=kernel.calls.map(c=>c.method);assert(calls.indexOf('review.ack')<calls.indexOf('review.observe'));
  assert.equal((await adapter.completeFinal(cut.cutId)).actionPerformed,false);
  assert.equal((await adapter.completeFinal(cut.cutId)).allowed,true);
  const ack=kernel.calls.find(c=>c.method==='review.ack')!;assert.equal((ack.params.credential as {token:string}).token,r.adapter.token);
  assert(!JSON.stringify([...port.items.values()]).includes(r.adapter.token));
});
test('wrong invocation/body witness is rejected, and pending event remains unobserved',async t=>{
  const {r,kernel,port,journal,adapter}=await setup(t);kernel.eventsByClient.set(r.binding.clientId,[event(r)]);port.mode='protected-fixture';port.wrongRequest=true;
  await adapter.pollOnce();await assert.rejects(adapter.requestModelObservation(),/model_request_proof_mismatch/);
  assert.equal(journal.read().inbox['event-1']?.observed,false);assert.equal(kernel.observed.size,0);
});
test('lost durable submit response replays same key; every corrective patch retained',async t=>{
  const {kernel,journal,adapter}=await setup(t);kernel.dropSubmitOnce=true;
  await assert.rejects(adapter.recordCompletedPatch(patch('p1')),/lost_submit_response/);assert.equal(adapter.mutationFence().allowed,false);
  await adapter.recoverSubmissions();assert.equal(kernel.submissions.size,1);
  await adapter.recordCompletedPatch(patch('repair-p2',S2));assert.equal(Object.keys(journal.read().submissions).length,2);assert.equal(kernel.submissions.size,2);
});
test('crash after insertion and lost ACK replay reconcile marker without duplicate context',async t=>{
  const {dir,r,kernel,port,journal,broker,adapter}=await setup(t);kernel.eventsByClient.set(r.binding.clientId,[event(r)]);port.throwAfterInsertOnce=true;
  await adapter.pollOnce();await assert.rejects(adapter.drainContext(),/fixture_crash_after_insert/);journal.close();
  const recovered=new InboxJournal(dir,binding(r));t.after(()=>recovered.close());const resumed=new ReferenceHost(recovered,broker,port);kernel.dropAckOnce=true;
  await assert.rejects(resumed.drainContext(),/lost_ack_response/);await resumed.drainContext();
  assert.equal(port.insertionCalls,1);assert.equal(recovered.read().inbox['event-1']?.acknowledged,true);assert.equal(recovered.read().inbox['event-1']?.observed,false);
});
test('stale finding retains old snapshot/range and is labelled historical; new patch invalidates cut',async t=>{
  const {r,kernel,port,journal,adapter}=await setup(t);await adapter.recordCompletedPatch(patch('p2',S2));kernel.eventsByClient.set(r.binding.clientId,[event(r)]);
  await adapter.pollOnce();const cut=await adapter.prepareFinal();
  assert.equal(journal.read().inbox['event-1']?.stale,true);const text=[...port.items.values()][0]!.text;
  assert(text.includes('"freshnessAtPreparation":"stale"'));assert(text.includes(S1));assert(text.includes('Label.kt'));
  await adapter.recordCompletedPatch(patch('p3',S2));await assert.rejects(adapter.completeFinal(cut.cutId),/gate_cut_invalidated/);
});
test('events arriving during model request are NOT retroactively observed; polling does not invoke model', {timeout:10_000}, async t=>{
  const {r,kernel,port,journal,adapter}=await setup(t);kernel.eventsByClient.set(r.binding.clientId,[event(r)]);await adapter.pollOnce();assert.equal(port.modelCalls,0);
  port.mode='protected-fixture';
  await withHeldModelInvocation(t,port,()=>adapter.requestModelObservation(),async()=>{
    assert.equal(port.modelCalls,1);
    kernel.eventsByClient.get(r.binding.clientId)!.push(event(r,'event-2',S2,2));await adapter.pollOnce();
  });
  assert.equal(journal.read().inbox['event-1']?.observed,true);assert.equal(journal.read().inbox['event-2']?.observed,false);assert.equal(adapter.mutationFence().allowed,false);
});
for(const terminal of ['rejects','returns'] as const){
  test(`synthetic invocation harness fails promptly when the operation ${terminal} before model entry`, {timeout:10_000}, async t=>{
    const port=new FixtureHost(binding(registration())),originalInvoke=port.invokeModel;
    const rejection=new Error('synthetic_operation_rejected_before_model_entry');
    const trace:{barrier?:Promise<void>;calls:number;heldCalls:number}={calls:0,heldCalls:0};
    const pending=withHeldModelInvocation(t,port,async()=>{
      trace.calls++;assert(port.barrier);trace.barrier=port.barrier;
      if(terminal==='rejects')throw rejection;
      return {observed:false,reason:'no_pending_context'};
    },()=>{trace.heldCalls++;assert.fail('an operation settled before invocation cannot enter the held assertion body');});
    await assert.rejects(pending,(error:unknown)=>terminal==='rejects'?error===rejection:
      error instanceof Error&&error.message==='synthetic_model_observation_settled_before_invoke');
    assert.equal(trace.calls,1);assert.equal(trace.heldCalls,0);assert.equal(port.modelCalls,0);
    assert.equal(port.invokeModel,originalInvoke);assert.equal(port.barrier,null);
    assert(trace.barrier);await trace.barrier; // Proves finally released this exact barrier even without entry.
  });
}
test('synthetic invocation harness abort releases and observes its operation without waiting for model entry', {timeout:10_000}, async t=>{
  const controller=new AbortController(),port=new FixtureHost(binding(registration())),originalInvoke=port.invokeModel;
  const aborted=new Error('synthetic_abort_before_model_entry');
  let finish!:()=>void,heldCalls=0;
  const finished=new Promise<void>(resolve=>{finish=resolve;});
  const pending=withHeldModelInvocation({signal:controller.signal,after:t.after.bind(t)},port,async()=>{
    const barrier=port.barrier;assert(barrier);controller.abort(aborted);await barrier;finish();return 'released';
  },()=>{heldCalls++;assert.fail('aborted pre-entry operation cannot enter the held assertion body');});
  await assert.rejects(pending,(error:unknown)=>error===aborted);await finished;
  assert.equal(heldCalls,0);assert.equal(port.modelCalls,0);assert.equal(port.invokeModel,originalInvoke);assert.equal(port.barrier,null);
});
test('synthetic invocation harness preserves a held-body failure after releasing and observing the model operation', {timeout:10_000}, async t=>{
  const port=new FixtureHost(binding(registration())),originalInvoke=port.invokeModel;port.mode='protected-fixture';
  const failure=new Error('synthetic_held_body_failed');let settled=false;
  const request:ModelRequest={invocationId:'synthetic-harness-invocation',hostThreadId:port.binding.hostThreadId,
    generation:port.binding.client.generation,requestDigest:S1,contexts:[]};
  await assert.rejects(withHeldModelInvocation(t,port,async()=>{
    try{return await port.invokeModel(request);}finally{settled=true;}
  },()=>{assert.equal(port.modelCalls,1);assert.equal(settled,false);throw failure;}),(error:unknown)=>error===failure);
  assert.equal(settled,true);assert.equal(port.modelCalls,1);assert.equal(port.invokeModel,originalInvoke);assert.equal(port.barrier,null);
});
test('two clients have independent cursors and cannot ACK another client or use ordinary token for proof',async t=>{
  const {r,kernel,broker,adapter}=await setup(t);const other=registration('client-b');kernel.clients.set(other.binding.clientId,other);kernel.eventsByClient.set(other.binding.clientId,[event(other,'other-event')]);
  kernel.eventsByClient.set(r.binding.clientId,[event(r)]);await adapter.pollOnce();await adapter.drainContext();assert(!kernel.acked.has('other-event'));
  const receipt={receiptId:'receipt',contextDigest:'digest',contextItemId:'item',insertedAt:'2026-11-04T00:00:00Z'};
  await assert.rejects(broker.ack(['other-event'],receipt),/cross_consumer_receipt/);
  await assert.rejects(kernel.request('review.ack',{credential:r.client,eventIds:['event-1'],receipt}),/unauthorized/);
  await assert.rejects(broker.events('client-b:0'),/cursor_wrong_client/);
});

test('context byte budget must be a positive safe integer before any host work',async t=>{
  const {kernel,port,journal,broker}=await setup(t);
  for(const maxContextBytes of [0,-1,NaN,Infinity,-Infinity,1.5,Number.MAX_SAFE_INTEGER+1]){
    assert.throws(()=>new ReferenceHost(journal,broker,port,{maxContextBytes}),/invalid_context_budget/);
  }
  assert.equal(kernel.calls.length,0);assert.equal(port.insertionCalls,0);assert.equal(port.modelCalls,0);
});

test('synthetic bounded backlog keeps an older large context ahead of small active-turn arrivals', {timeout:10_000}, async t=>{
  const {r,kernel,port,journal,broker,adapter}=await setup(t);
  const oldest=event(r,'event-z',S1,1),heavy=event(r,'event-y',S1,2),newer=event(r,'event-x',S1,3);
  const payload=JSON.parse(heavy.payload) as {result:{findings:{diagnosis:string}[]}};
  payload.result.findings[0]!.diagnosis+='x'.repeat(6000);
  heavy.payload=canonical(payload);heavy.payloadDigest=digest(heavy.payload);
  kernel.eventsByClient.set(r.binding.clientId,[oldest,heavy,newer]);
  await adapter.pollOnce();await adapter.drainContext();
  const initial=journal.read().inbox;
  const firstItem=initial[oldest.eventId]!.item!,heavyItem=initial[heavy.eventId]!.item!,newerItem=initial[newer.eventId]!.item!;
  const maxContextBytes=Buffer.byteLength(heavyItem.text);
  assert(Buffer.byteLength(firstItem.text)+Buffer.byteLength(newerItem.text)<=maxContextBytes);
  assert(Object.values(initial).every(entry=>Buffer.byteLength(entry.item!.text)<=maxContextBytes));
  assert(Object.values(initial).reduce((bytes,entry)=>bytes+Buffer.byteLength(entry.item!.text),0)>maxContextBytes);
  const bounded=new ReferenceHost(journal,broker,port,{maxContextBytes});port.mode='protected-fixture';
  const requests:ModelRequest[]=[];const invoke=port.invokeModel.bind(port);
  let started!:()=>void,release!:()=>void;
  const entered=new Promise<void>(resolve=>{started=resolve;});
  port.barrier=new Promise<void>(resolve=>{release=resolve;});t.after(()=>release());
  port.invokeModel=async request=>{requests.push(structuredClone(request));started();return invoke(request);};
  const observing=bounded.requestModelObservation();
  try{
    // Also surface a pre-invocation failure immediately instead of waiting for a nonexistent barrier.
    await Promise.race([entered,observing]);
    assert.deepEqual(requests[0]!.contexts,[firstItem]);assert.equal(port.modelCalls,1);
    const late=event(r,'event-a',S1,4);kernel.eventsByClient.get(r.binding.clientId)!.push(late);
    await bounded.pollOnce();await bounded.drainContext();
    assert.equal(kernel.acked.size,4);assert.equal(kernel.observed.size,0);assert.equal(port.modelCalls,1);
    assert.deepEqual(journal.read().pendingModelRequest,requests[0]);
    assert.equal(journal.read().inbox[late.eventId]!.observed,false);
  }finally{release();}
  assert.equal((await observing).observed,true);
  assert.deepEqual([...kernel.observed],[oldest.eventId]);
  assert.equal(journal.read().inbox[heavy.eventId]!.acknowledged,true);
  assert.equal(journal.read().inbox[heavy.eventId]!.observation,null);
  const cut=await bounded.prepareFinal();
  assert.equal((await bounded.completeFinal(cut.cutId)).allowed,false);assert.equal(bounded.mutationFence().allowed,false);
  // Smaller later entries could fill this budget, but cannot jump ahead of the older heavy entry.
  assert.equal((await bounded.requestModelObservation()).observed,true);
  assert.deepEqual(requests[1]!.contexts,[heavyItem]);
  assert.deepEqual([...kernel.observed].sort(),[heavy.eventId,oldest.eventId].sort());
  assert.equal((await bounded.completeFinal(cut.cutId)).allowed,false);assert.equal(bounded.mutationFence().allowed,false);
  await bounded.pollOnce();await bounded.drainContext();assert.equal(port.modelCalls,2);
  const remaining=[newerItem,journal.read().inbox['event-a']!.item!].sort((a,b)=>a.marker.localeCompare(b.marker));
  assert.equal((await bounded.requestModelObservation()).observed,true);
  assert.deepEqual(requests[2]!.contexts,remaining);
  assert(requests.every(request=>request.contexts.reduce((bytes,item)=>bytes+Buffer.byteLength(item.text),0)<=maxContextBytes));
  assert.equal(kernel.observed.size,4);assert.equal(port.insertionCalls,4);assert.equal(port.modelCalls,3);
  assert.deepEqual(journal.read().inbox[heavy.eventId]!.item,heavyItem);
  for(const source of kernel.eventsByClient.get(r.binding.clientId)!){
    const entry=journal.read().inbox[source.eventId]!;
    assert.equal(entry.event.payload,source.payload);assert.equal(entry.event.payloadDigest,source.payloadDigest);
    assert.equal(entry.acknowledged,true);assert.equal(entry.observed,true);
  }
  const complete=await bounded.completeFinal(cut.cutId);
  assert.equal(complete.allowed,true);assert.equal(complete.actionPerformed,false);assert.equal(bounded.mutationFence().allowed,true);
  assert.deepEqual(await bounded.requestModelObservation(),{observed:false,reason:'no_pending_context'});
  assert.equal(port.modelCalls,3);
});

test('unconfirmed receipt result and oversized mandatory diagnostic cannot silently advance delivery',async t=>{
  const {r,kernel,journal,broker,port}=await setup(t);kernel.eventsByClient.set(r.binding.clientId,[event(r)]);
  const bounded=new ReferenceHost(journal,broker,port,{maxContextBytes:128});await bounded.pollOnce();await assert.rejects(bounded.drainContext(),/required_context_exceeds_budget/);assert.equal(kernel.acked.size,0);
  const adapter=new ReferenceHost(journal,broker,port);kernel.forceReceiptState='queued';await assert.rejects(adapter.drainContext(),/receipt_not_confirmed/);assert.equal(journal.read().inbox['event-1']?.acknowledged,false);
});
test('wrong-cut/client/generation or claimed remote guarded action cannot become an allowed host decision',async t=>{
  const {adapter,kernel}=await setup(t);const cut=await adapter.prepareFinal();
  for(const override of [{cutId:'other-cut'},{clientId:'other-client'},{generation:2},{contractVersion:'ktchecker/1'}]){
    kernel.gateResultOverride=override;await assert.rejects(adapter.completeFinal(cut.cutId),/gate_result_binding_mismatch/);
  }
  kernel.gateResultOverride={actionPerformed:true};await assert.rejects(adapter.completeFinal(cut.cutId),/unexpected_remote_guarded_action/);
});


test('synthetic ready observation publishes before a held unrelated ACK and still reports its failure', {timeout:10_000}, async t=>{
  const {r,kernel,port,journal,broker,adapter}=await setup(t);
  const original=event(r),late=event(r,'event-2',S2,2);
  kernel.eventsByClient.set(r.binding.clientId,[original]);port.mode='protected-fixture';
  await adapter.pollOnce();await adapter.drainContext();
  let enter!:()=>void,releaseModel!:()=>void,ackEnter!:()=>void,releaseAck!:()=>void;
  const entered=new Promise<void>(resolve=>{enter=resolve;});
  const ackEntered=new Promise<void>(resolve=>{ackEnter=resolve;});
  port.barrier=new Promise<void>(resolve=>{releaseModel=resolve;});
  const ackBarrier=new Promise<void>(resolve=>{releaseAck=resolve;});
  t.after(()=>{releaseModel();releaseAck();});
  const requests:ModelRequest[]=[];const invoke=port.invokeModel.bind(port);
  port.invokeModel=async request=>{requests.push(structuredClone(request));enter();return invoke(request);};
  const ack=broker.ack.bind(broker),ackFailure=new Error('synthetic_unrelated_ack_backpressure');
  let lateAttempts=0;
  broker.ack=async(eventIds,receipt)=>{
    if(eventIds.includes(late.eventId)){
      lateAttempts++;
      if(lateAttempts>1){ackEnter();await ackBarrier;}
      throw ackFailure;
    }
    return ack(eventIds,receipt);
  };
  const outcome=adapter.requestModelObservation().then(value=>({value,error:null}),error=>({value:null,error}));
  try{
    await Promise.race([entered,outcome]);assert.equal(requests.length,1);
    kernel.eventsByClient.get(r.binding.clientId)!.push(late);await adapter.pollOnce();
    await assert.rejects(adapter.drainContext(),error=>error===ackFailure);
    const retained=structuredClone(journal.read().inbox[late.eventId]!);
    assert(retained.inserted);assert.equal(retained.acknowledged,false);assert.equal(retained.observation,null);
    assert.deepEqual(journal.read().pendingModelRequest,requests[0]);
    releaseModel();
    await Promise.race([ackEntered,outcome]);assert.equal(lateAttempts,2);
    // This assertion is made WHILE the unrelated ACK is still held, not after its failure.
    assert.equal(journal.read().inbox[original.eventId]!.observed,true);
    assert.deepEqual([...kernel.observed],[original.eventId]);assert.equal(journal.read().pendingModelRequest,null);
    assert.deepEqual(journal.read().inbox[late.eventId],retained);
    assert.equal(adapter.mutationFence().allowed,false);assert.equal(port.modelCalls,1);
    const observations=kernel.calls.filter(call=>call.method==='review.observe');
    assert.equal(observations.length,1);assert.deepEqual(observations[0]!.params.eventIds,[original.eventId]);
    assert.deepEqual(observations[0]!.params.credential,r.adapter);
  }finally{releaseModel();releaseAck();}
  const completed=await outcome;
  assert.equal(completed.value,null);assert.equal(completed.error,ackFailure,'independent progress must not hide the failed ACK');
  assert.equal(journal.read().inbox[late.eventId]!.acknowledged,false);
  assert.equal(journal.read().inbox[late.eventId]!.observed,false);
});

test('synthetic receipt replay retains independent confirmation failures and later ACK progress', {timeout:10_000}, async t=>{
  for(const obstruction of ['rejected','wrong-client','wrong-event-set','queued'] as const){
    const corruption=obstruction==='wrong-event-set'?'event-set':'client';
    const {r,kernel,port,journal,broker,adapter}=await setup(t);
    const original=event(r),blocked=event(r,'event-2',S2,2),later=event(r,'event-3',S2,3);
    kernel.eventsByClient.set(r.binding.clientId,[original]);port.mode='protected-fixture';
    const trace:string[]=[],observe=broker.observe.bind(broker);
    let rejectObservation=true,rejectAck=true;
    broker.observe=async(eventIds,receipt)=>{
      trace.push('observe:'+eventIds.join(','));
      if(rejectObservation)return {contractVersion:r.contractVersion,clientId:corruption==='client'?'another-client':r.binding.clientId,
        eventIds:corruption==='event-set'?['another-event']:eventIds,state:'model_observed'};
      return observe(eventIds,receipt);
    };
    await adapter.pollOnce();await assert.rejects(adapter.requestModelObservation(),/receipt_not_confirmed/);
    const proof=structuredClone(journal.read().inbox[original.eventId]!.observation!);
    assert(proof);assert.equal(journal.read().pendingModelRequest,null);
    const ack=broker.ack.bind(broker),ackFailure=new Error('synthetic_independent_ack_failure');
    broker.ack=async(eventIds,receipt)=>{
      trace.push('ack:'+eventIds.join(','));
      if(rejectAck&&eventIds.includes(blocked.eventId)){
        if(obstruction==='rejected')throw ackFailure;
        return {contractVersion:r.contractVersion,clientId:obstruction==='wrong-client'?'another-client':r.binding.clientId,
          eventIds:obstruction==='wrong-event-set'?['another-event']:eventIds,state:obstruction==='queued'?'queued':'acknowledged'};
      }
      return ack(eventIds,receipt);
    };
    kernel.eventsByClient.get(r.binding.clientId)!.push(blocked,later);await adapter.pollOnce();trace.length=0;
    await assert.rejects(adapter.drainContext(),(error:unknown)=>{
      assert(error instanceof AggregateError);assert.equal(error.message,'receipt_sync_failed');assert.equal(error.errors.length,2);
      assert.equal((error.errors[0] as {code:string}).code,'receipt_not_confirmed');
      if(obstruction==='rejected')assert.equal(error.errors[1],ackFailure);
      else assert.equal((error.errors[1] as {code:string}).code,'receipt_not_confirmed');
      return true;
    });
    assert.deepEqual(trace,['observe:'+original.eventId,'ack:'+blocked.eventId,'ack:'+later.eventId]);
    const failed=journal.read().inbox;
    assert.deepEqual(failed[original.eventId]!.observation,proof);assert.equal(failed[original.eventId]!.observed,false);
    assert(failed[blocked.eventId]!.inserted);assert.equal(failed[blocked.eventId]!.acknowledged,false);
    assert.equal(failed[later.eventId]!.acknowledged,true);assert.equal(kernel.acked.has(later.eventId),true);
    for(const entry of [blocked,later]){
      assert.deepEqual(failed[entry.eventId]!.event,entry);assert.equal(failed[entry.eventId]!.observation,null);
      assert.equal(failed[entry.eventId]!.observed,false);
    }
    assert.equal(kernel.observed.size,0);assert.equal(port.modelCalls,1);assert.equal(adapter.mutationFence().allowed,false);
    rejectObservation=false;rejectAck=false;trace.length=0;await adapter.syncReceipts();
    assert.deepEqual(trace,['observe:'+original.eventId,'ack:'+blocked.eventId]);
    assert.deepEqual(journal.read().inbox[original.eventId]!.observation,proof);
    assert.equal(journal.read().inbox[original.eventId]!.observed,true);
    assert.equal(journal.read().inbox[blocked.eventId]!.acknowledged,true);
    assert.deepEqual([...kernel.observed],[original.eventId]);assert.equal(port.modelCalls,1);
    assert.equal(journal.read().pendingModelRequest,null);assert.equal(adapter.mutationFence().allowed,false);
  }
});

test('synthetic pending observation still requires its own exact client and event-set ACK', {timeout:10_000}, async t=>{
  const {r,kernel,port,journal,broker,adapter}=await setup(t);const original=event(r);
  kernel.eventsByClient.set(r.binding.clientId,[original]);port.mode='protected-fixture';
  const observe=broker.observe.bind(broker),deferredFailure=new Error('synthetic_deferred_publication');
  const order:string[]=[];let deferPublication=true,observeAttempts=0;
  broker.observe=async(eventIds,receipt)=>{
    order.push('observe');observeAttempts++;
    if(deferPublication)throw deferredFailure;
    return observe(eventIds,receipt);
  };
  await adapter.pollOnce();await assert.rejects(adapter.requestModelObservation(),error=>error===deferredFailure);
  const proof=structuredClone(journal.read().inbox[original.eventId]!.observation!);
  // Protected negative fixture: even a saved proof cannot bypass its own missing ACK confirmation.
  journal.edit(state=>{state.inbox[original.eventId]!.acknowledged=false;});kernel.acked.delete(original.eventId);
  deferPublication=false;const ack=broker.ack.bind(broker);
  for(const corruption of ['client','event-set'] as const){
    broker.ack=async(eventIds)=>({contractVersion:r.contractVersion,clientId:corruption==='client'?'another-client':r.binding.clientId,
      eventIds:corruption==='event-set'?['another-event']:eventIds,state:'acknowledged'});
    await assert.rejects(adapter.syncReceipts(),(error:unknown)=>{
      assert(error instanceof AggregateError);
      assert.deepEqual(error.errors.map(value=>(value as {code:string}).code),['receipt_not_confirmed','observation_before_ack']);return true;
    });
    const entry=journal.read().inbox[original.eventId]!;
    assert.equal(entry.acknowledged,false);assert.equal(entry.observed,false);assert.deepEqual(entry.observation,proof);
    assert.equal(observeAttempts,1);assert.equal(kernel.observed.size,0);assert.equal(adapter.mutationFence().allowed,false);
  }
  broker.ack=async(eventIds,receipt)=>{order.push('ack');return ack(eventIds,receipt);};order.length=0;
  await adapter.syncReceipts();assert.deepEqual(order,['ack','observe']);
  assert.equal(journal.read().inbox[original.eventId]!.acknowledged,true);
  assert.equal(journal.read().inbox[original.eventId]!.observed,true);
  assert.deepEqual(journal.read().inbox[original.eventId]!.observation,proof);
  assert.equal(port.modelCalls,1);assert.equal(observeAttempts,2);
  // Retained observed history is not pending ACK work: a no-op needs only two whole-state snapshots.
  const read=journal.read.bind(journal),rpcCount=kernel.calls.length;let reads=0;
  journal.read=()=>{reads++;return read();};
  try{await adapter.syncReceipts();assert.equal(reads,2);}
  finally{journal.read=read;}
  assert.equal(kernel.calls.length,rpcCount);assert.equal(port.modelCalls,1);
});

test('synthetic receipt replay stops after journal loss on confirmed or failed ACK responses', {timeout:10_000}, async t=>{
  for(const response of ['confirmed','rejected','malformed'] as const){
    const {dir,r,kernel,port,journal,broker,adapter}=await setup(t);
    const original=event(r),first=event(r,'event-2',S2,2),later=event(r,'event-3',S2,3);
    kernel.eventsByClient.set(r.binding.clientId,[original]);port.mode='protected-fixture';
    const observationFailure=new Error('synthetic_observation_unavailable');
    broker.observe=async()=>{throw observationFailure;};
    await adapter.pollOnce();await assert.rejects(adapter.requestModelObservation(),error=>error===observationFailure);
    const proof=structuredClone(journal.read().inbox[original.eventId]!.observation!);
    const ack=broker.ack.bind(broker),ackFailure=new Error('synthetic_ack_lost_after_journal_close');
    broker.ack=async(eventIds,receipt)=>{
      const result=await ack(eventIds,receipt);
      if(eventIds.includes(first.eventId)){
        journal.close();
        if(response==='rejected')throw ackFailure;
        if(response==='malformed')return {...result,clientId:'another-client'};
      }
      return result;
    };
    kernel.eventsByClient.get(r.binding.clientId)!.push(first,later);await adapter.pollOnce();
    await assert.rejects(adapter.drainContext(),(error:unknown)=>{
      assert(error instanceof AggregateError);assert.equal(error.errors[0],observationFailure);
      if(response==='confirmed'){
        assert.equal(error.errors.length,2);assert.equal((error.errors[1] as {code:string}).code,'controller_lease_lost');
      }else{
        assert.equal(error.errors.length,3);
        if(response==='rejected')assert.equal(error.errors[1],ackFailure);
        else assert.equal((error.errors[1] as {code:string}).code,'receipt_not_confirmed');
        assert.equal((error.errors[2] as {code:string}).code,'journal_closed');
      }
      return true;
    });
    assert.equal(kernel.acked.has(first.eventId),true);assert.equal(kernel.acked.has(later.eventId),false);
    assert.equal(kernel.calls.filter(call=>call.method==='review.ack'&&(call.params.eventIds as string[]).includes(later.eventId)).length,0);
    const recovered=new InboxJournal(dir,binding(r));t.after(()=>recovered.close());
    const state=recovered.read();assert.deepEqual(state.inbox[original.eventId]!.observation,proof);
    assert.equal(state.inbox[original.eventId]!.observed,false);assert.equal(state.pendingModelRequest,null);
    for(const value of [first,later]){
      assert.deepEqual(state.inbox[value.eventId]!.event,value);assert(state.inbox[value.eventId]!.inserted);
      assert.equal(state.inbox[value.eventId]!.acknowledged,false);assert.equal(state.inbox[value.eventId]!.observed,false);
    }
    assert.equal(port.modelCalls,1);
    assert.equal(new ReferenceHost(recovered,broker,port).mutationFence().allowed,false);
  }
});

test('synthetic background receipt diagnostics retain stable codes without private exception text', {timeout:10_000}, async t=>{
  const {r,kernel,port,journal,broker,adapter}=await setup(t);
  const original=event(r),plain=event(r,'event-2',S2,2),unknown=event(r,'event-3',S2,3);
  kernel.eventsByClient.set(r.binding.clientId,[original]);port.mode='protected-fixture';
  const publicationFailure=new Error('synthetic_deferred_publication');
  broker.observe=async()=>{throw publicationFailure;};
  await adapter.pollOnce();await assert.rejects(adapter.requestModelObservation(),error=>error===publicationFailure);
  const proof=structuredClone(journal.read().inbox[original.eventId]!.observation!);
  journal.edit(state=>{state.inbox[original.eventId]!.acknowledged=false;});kernel.acked.delete(original.eventId);
  kernel.eventsByClient.get(r.binding.clientId)!.push(plain,unknown);
  const privateText='synthetic-private-exception-text-not-for-background-output';
  const privateCode='synthetic_private_host_error_code_not_for_background_output';
  const plainFailure=new Error(privateText),unknownFailure=new HostError(privateCode);
  const stop=new AbortController();t.after(()=>stop.abort());
  broker.ack=async(eventIds)=>{
    stop.abort();
    if(eventIds.includes(plain.eventId))throw plainFailure;
    if(eventIds.includes(unknown.eventId))throw unknownFailure;
    return {contractVersion:r.contractVersion,clientId:'another-client',eventIds,state:'acknowledged'};
  };
  const sync=adapter.syncReceipts.bind(adapter);let reported:unknown;
  adapter.syncReceipts=async()=>{try{await sync();}catch(error){reported=error;throw error;}};
  // Abort during the first fixture ACK: run still commits this tick's honest diagnostic, without sleeping/retrying.
  await adapter.run(stop.signal);
  assert(reported instanceof AggregateError);assert.equal(reported.errors.length,4);
  assert.equal((reported.errors[0] as {code:string}).code,'receipt_not_confirmed');
  assert.equal(reported.errors[1],plainFailure);assert.equal(reported.errors[2],unknownFailure);
  assert.equal((reported.errors[3] as {code:string}).code,'observation_before_ack');
  const expected='receipt_sync_failed:'+canonical(['receipt_not_confirmed','broker_or_host_unavailable','observation_before_ack']);
  assert.equal(journal.read().lastProblem,expected);
  assert(!expected.includes(privateText));assert(!expected.includes(privateCode));
  assert(adapter.mutationFence().reasons.includes(expected));assert.equal(adapter.mutationFence().allowed,false);
  assert.deepEqual(journal.read().inbox[original.eventId]!.observation,proof);
  for(const value of [original,plain,unknown]){
    const entry=journal.read().inbox[value.eventId]!;assert.deepEqual(entry.event,value);
    assert.equal(entry.acknowledged,false);assert.equal(entry.observed,false);
  }
  assert.equal(journal.read().pendingModelRequest,null);assert.equal(kernel.observed.size,0);assert.equal(port.modelCalls,1);
  // The same boundary preserves known singleton codes and masks unknown HostError codes as well as ordinary messages.
  for(const failure of [new HostError('required_context_exceeds_budget'),unknownFailure,plainFailure]){
    const singleStop=new AbortController();
    adapter.recoverSubmissions=async()=>{singleStop.abort();throw failure;};
    await adapter.run(singleStop.signal);
    assert.equal(journal.read().lastProblem,failure===unknownFailure||failure===plainFailure?'broker_or_host_unavailable':'required_context_exceeds_budget');
  }
  assert.equal(port.modelCalls,1);assert.equal(adapter.mutationFence().allowed,false);
});
