import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
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
