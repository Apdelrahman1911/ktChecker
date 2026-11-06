// Synthetic SOURCE controls, all UNRUN. No SDK/provider/model call or delivery qualification.
import { test, type TestContext } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { FINDING_WIRE_PROFILE, PREVIEW_CONTRACT, RestartingJsonLineRpc } from '@ktchecker/mcp-preview/rpc';
import { ReferenceHost } from '../src/adapter.js';
import { KernelBroker } from '../src/broker.js';
import { canonical, digest, InboxJournal } from '../src/journal.js';
import { binding, event, FixtureHost, registration } from './fixtures.js';

type ProfileMode='compatible'|'missing'|'mismatch'|'malformed';
interface PeerPlan {
  profile?:ProfileMode;
  byMethod?:Record<string,ProfileMode>;
  badAckEventId?:string;
  badResumedPage?:boolean;
  eventCount?:number;
}
const attachment={kind:'upstream_sarif',digest:digest('complete upstream report bytes'),artifactRef:'  /outputs/attempt-1/provider-a/detekt.sarif  '};
function attachedEvent(index:number){
  const base=event(registration(),'event-'+index,undefined,index),payload=JSON.parse(base.payload);
  payload.result.findings[0].evidence=[{kind:attachment.kind,summary:'Complete upstream report',digest:attachment.digest,artifactRef:null}];
  payload.result.findings[0].retrievalAttachments=[attachment];
  const text=canonical(payload);return {...base,payload:text,payloadDigest:digest(text)};
}
async function setup(t:TestContext,plan:PeerPlan={}){
  const r=registration(),events=Array.from({length:plan.eventCount??1},(_,i)=>attachedEvent(i+1));
  // The protected fixture authenticates the normal client vs adapter credentials separately.
  // A deliberately bad success envelope is emitted AFTER its handler, so no test may
  // infer remote rollback or safe automatic mutation replay from a rejected response.
  const child=`
    import readline from 'node:readline';
    import { FixtureKernel } from ${JSON.stringify(new URL('./fixtures.js',import.meta.url).href)};
    const registration=${JSON.stringify(r)},plan=${JSON.stringify(plan)};
    const kernel=new FixtureKernel(registration);kernel.eventsByClient.set(registration.binding.clientId,${JSON.stringify(events)});
    const send=value=>process.stdout.write(JSON.stringify(value)+'\\n');
    readline.createInterface({input:process.stdin}).on('line',async line=>{
      const request=JSON.parse(line),base={id:request.id,contractVersion:'${PREVIEW_CONTRACT}'};
      if(request.findingWireProfile!=='${FINDING_WIRE_PROFILE}')return send({...base,error:{code:'incompatible_finding_wire_profile',message:'Profile required before dispatch'}});
      if(request.method==='fixture.disconnect')return process.exit(0);
      try{
        const result=await kernel.request(request.method,request.params);
        const mode=plan.badAckEventId&&request.method==='review.ack'&&request.params.eventIds.includes(plan.badAckEventId)?'missing':
          plan.badResumedPage&&request.method==='review.events'&&request.params.cursor!==null?'mismatch':
          plan.byMethod?.[request.method]??plan.profile??'compatible';
        const profile=mode==='missing'?{}:{findingWireProfile:mode==='mismatch'?'ktchecker/finding-retrieval-attachments-2':mode==='malformed'?[]:'${FINDING_WIRE_PROFILE}'};
        send({...base,...profile,result});
      }catch(error){send({...base,error:{code:error.code??'fixture_failure',message:'Synthetic operation failed'}});}
    });`;
  const dir=await mkdtemp(join(tmpdir(),'ktchecker-finding-wire-host-'));
  const rpc=new RestartingJsonLineRpc({command:process.execPath,args:['--input-type=module','-e',child],timeoutMs:3000});
  let journal:InboxJournal|undefined;
  t.after(async()=>{journal?.close();await rpc.close();await rm(dir,{recursive:true,force:true});});
  journal=new InboxJournal(join(dir,'inbox'),binding(r));
  const host=new FixtureHost(binding(r)),adapter=new ReferenceHost(journal,new KernelBroker(rpc,r),host);
  return {rpc,journal,host,adapter,events};
}

test('admitted producer retains nonempty locator bytes through durable page and synthetic context',{timeout:10000},async t=>{
  const {journal,host,adapter,events}=await setup(t);
  await adapter.pollOnce();await adapter.drainContext();
  const entry=journal.read().inbox['event-1']!;
  assert.equal(entry.event.payload,events[0]!.payload);
  assert.equal(entry.event.payloadDigest,events[0]!.payloadDigest);
  assert.equal(digest(entry.event.payload),entry.event.payloadDigest);
  const context=JSON.parse(entry.item!.text.slice(entry.item!.text.indexOf('\n')+1));
  assert.deepEqual(JSON.parse(context.diagnosticPayload).result.findings[0].retrievalAttachments,[attachment]);
  assert.equal(entry.item!.digest,digest(entry.item!.text));
  assert.equal(Object.hasOwn(entry.event,'findingWireProfile'),false);
  assert.equal(entry.acknowledged,true);assert.equal(entry.observed,false);
  assert.equal(host.insertionCalls,1);assert.equal(host.modelCalls,0);
});
test('incompatible page cannot commit inbox/cursor or create a final cut',{timeout:15000},async t=>{
  for(const profile of ['missing','mismatch','malformed'] as const){
    const {journal,host,adapter}=await setup(t,{profile});
    const before=canonical(journal.read());
    await assert.rejects(adapter.pollOnce(),{code:'incompatible_finding_wire_profile'});
    assert.equal(canonical(journal.read()),before);
    await assert.rejects(adapter.prepareFinal(),{code:'incompatible_finding_wire_profile'});
    assert.equal(canonical(journal.read()),before);
    assert.equal(journal.read().fetchCursor,null);assert.equal(journal.read().cut,null);
    assert.equal(host.insertionCalls,0);assert.equal(host.modelCalls,0);
  }
});
test('bad ACK response is locally unconfirmed, not rolled back; an independent compatible ACK still confirms',{timeout:15000},async t=>{
  const {journal,host,adapter,events}=await setup(t,{eventCount:2,badAckEventId:'event-1'});
  await adapter.pollOnce();
  await assert.rejects(adapter.drainContext(),{code:'incompatible_finding_wire_profile'});
  const state=journal.read(),first=state.inbox['event-1']!,second=state.inbox['event-2']!;
  assert(first.inserted);assert(second.inserted);
  assert.equal(first.acknowledged,false);assert.equal(second.acknowledged,true);
  assert.equal(first.observed,false);assert.equal(second.observed,false);
  assert.equal(first.event.payload,events[0]!.payload);assert.equal(second.event.payload,events[1]!.payload);
  assert.equal(state.fetchCursor,'client-a:2');assert.equal(state.cut,null);
  assert.equal(adapter.mutationFence().allowed,false);assert.equal(host.modelCalls,0);
  // The first remote handler already ran in this fixture. Its local false flag
  // denotes an unconfirmed response only; no remote non-dispatch/rollback claim.
  // Confirming event-2 does not retry, consume or confirm event-1's obligation.
});
test('resumed polling revalidates producer profile instead of inheriting a prior connection grant',{timeout:10000},async t=>{
  const {rpc,journal,host,adapter}=await setup(t,{badResumedPage:true});
  await adapter.pollOnce();const before=canonical(journal.read());
  await assert.rejects(rpc.request('fixture.disconnect',{}),{code:'broker_disconnected'});
  await assert.rejects(adapter.pollOnce(),{code:'incompatible_finding_wire_profile'});
  assert.equal(canonical(journal.read()),before);
  assert.equal(journal.read().fetchCursor,'client-a:1');
  assert.equal(host.insertionCalls,0);assert.equal(host.modelCalls,0);
});
test('an incompatible gate decision never becomes local allowed/completion success',{timeout:10000},async t=>{
  const {journal,host,adapter}=await setup(t,{eventCount:0,byMethod:{'gate.complete':'mismatch'}});
  const cut=await adapter.prepareFinal();assert.equal(cut.ready,true);
  const before=canonical(journal.read());
  await assert.rejects(adapter.completeFinal(cut.cutId),{code:'incompatible_finding_wire_profile'});
  assert.equal(canonical(journal.read()),before);assert.equal(host.modelCalls,0);
});
test('an incompatible prepared-cut response cannot replace a local cut',{timeout:10000},async t=>{
  const {journal,adapter}=await setup(t,{eventCount:0,byMethod:{'gate.prepare':'missing'}});
  await assert.rejects(adapter.prepareFinal(),{code:'incompatible_finding_wire_profile'});
  assert.equal(journal.read().cut,null);
});
