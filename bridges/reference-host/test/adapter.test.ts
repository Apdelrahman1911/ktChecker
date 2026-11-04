import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import type { JsonObject } from '@ktchecker/mcp-preview/rpc';
import { InboxJournal } from '../src/journal.js';
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
test('events arriving during model request are NOT retroactively observed; polling does not invoke model',async t=>{
  const {r,kernel,port,journal,adapter}=await setup(t);kernel.eventsByClient.set(r.binding.clientId,[event(r)]);await adapter.pollOnce();assert.equal(port.modelCalls,0);
  port.mode='protected-fixture';let release!:()=>void;port.barrier=new Promise(resolve=>{release=resolve;});
  const observing=adapter.requestModelObservation();while(!port.modelCalls)await new Promise(resolve=>setTimeout(resolve,1));
  kernel.eventsByClient.get(r.binding.clientId)!.push(event(r,'event-2',S2,2));await adapter.pollOnce();release();await observing;
  assert.equal(journal.read().inbox['event-1']?.observed,true);assert.equal(journal.read().inbox['event-2']?.observed,false);assert.equal(adapter.mutationFence().allowed,false);
});
test('two clients have independent cursors and cannot ACK another client or use ordinary token for proof',async t=>{
  const {r,kernel,broker,adapter}=await setup(t);const other=registration('client-b');kernel.clients.set(other.binding.clientId,other);kernel.eventsByClient.set(other.binding.clientId,[event(other,'other-event')]);
  kernel.eventsByClient.set(r.binding.clientId,[event(r)]);await adapter.pollOnce();await adapter.drainContext();assert(!kernel.acked.has('other-event'));
  const receipt={receiptId:'receipt',contextDigest:'digest',contextItemId:'item',insertedAt:'2026-11-04T00:00:00Z'};
  await assert.rejects(broker.ack(['other-event'],receipt),/cross_consumer_receipt/);
  await assert.rejects(kernel.request('review.ack',{credential:r.client,eventIds:['event-1'],receipt}),/unauthorized/);
  await assert.rejects(broker.events('client-b:0'),/cursor_wrong_client/);
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
