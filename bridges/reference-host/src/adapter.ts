import { randomUUID } from 'node:crypto';
import { PREVIEW_CONTRACT } from '@ktchecker/mcp-preview/rpc';
import { KernelBroker } from './broker.js';
import { canonical, digest, HostError, InboxJournal } from './journal.js';
import { prepareDiagnosticContext } from './context-projection.js';
import type { ContextItem, GateCut, GateResult, HostPort, InboxEntry, InsertedContext, ModelRequest, Submission } from './types.js';

const pause=(milliseconds:number,signal:AbortSignal)=>new Promise<void>(resolve=>{
  if(signal.aborted){resolve();return;}
  const done=()=>{clearTimeout(timer);signal.removeEventListener('abort',done);resolve();};
  const timer=setTimeout(done,milliseconds);signal.addEventListener('abort',done,{once:true});
});
export interface AdapterOptions { maxContextBytes?:number; maxPendingSubmissions?:number }

/** Polling/inbox controller only. No token or proof operation is exposed as a coding-model tool. */
export class ReferenceHost {
  private draining:Promise<number>|undefined;
  private modelActive=false;
  private readonly maxContextBytes:number;
  private readonly maxPendingSubmissions:number;
  constructor(readonly journal:InboxJournal,private readonly broker:KernelBroker,private readonly host:HostPort,options:AdapterOptions={}){
    if(canonical(journal.binding)!==canonical(host.binding)||canonical(journal.binding.client)!==canonical(broker.registration.binding))throw new HostError('host_binding_mismatch');
    this.maxContextBytes=options.maxContextBytes??32_768;this.maxPendingSubmissions=options.maxPendingSubmissions??32;
  }
  /** Trusted capture integration calls once after EVERY completed mutation, including repairs. */
  async recordCompletedPatch(request:Submission):Promise<void>{
    const snapshot=request.input.snapshot as {snapshotId?:unknown}|undefined;
    if(typeof snapshot?.snapshotId!=='string'||!/^sha256:[0-9a-f]{64}$/.test(snapshot.snapshotId))throw new HostError('missing_immutable_snapshot');
    const snapshotId=snapshot.snapshotId;
    if(['__proto__','constructor','prototype'].includes(request.idempotencyKey))throw new HostError('invalid_idempotency_key');
    this.journal.edit(state=>{
      const previous=state.submissions[request.idempotencyKey];
      if(previous){if(canonical(previous.request)!==canonical(request))throw new HostError('idempotency_conflict');return;}
      if(Object.values(state.submissions).filter(x=>!x.status).length>=this.maxPendingSubmissions)throw new HostError('submission_backpressure');
      state.submissions[request.idempotencyKey]={request:structuredClone(request),status:null};
      state.currentSnapshotId=snapshotId;state.localEpoch++;state.cut=null;
    });
    await this.recoverSubmissions();
  }
  async recoverSubmissions():Promise<void>{
    for(const pending of Object.values(this.journal.read().submissions).filter(x=>!x.status)){
      const status=await this.broker.submit(pending.request);
      if(!status||status.contractVersion!==PREVIEW_CONTRACT||status.clientId!==this.journal.binding.client.clientId||
        typeof status.reviewId!=='string'||!status.reviewId||typeof status.state!=='string'||
        status.sourceSnapshotId!==(pending.request.input.snapshot as {snapshotId:string}).snapshotId)throw new HostError('submission_snapshot_mismatch');
      this.journal.edit(state=>{state.submissions[pending.request.idempotencyKey]!.status=status;});
    }
  }
  async pollOnce():Promise<boolean>{
    const page=await this.broker.events(this.journal.read().fetchCursor);
    this.journal.commitPage(page);
    return page.hasMore;
  }
  /** Owned runtime loop. It never calls a model just to poll or treats an empty page as clean. */
  async run(signal:AbortSignal):Promise<void>{
    let delay=100;
    while(!signal.aborted){
      try{
        await this.recoverSubmissions();
        const more=await this.pollOnce();
        await this.drainContext();
        this.journal.edit(state=>{state.lastProblem=null;});
        delay=more?0:500;
      }catch(error){
        const code=error instanceof HostError?error.code:'broker_or_host_unavailable';
        this.journal.edit(state=>{state.lastProblem=code;});
        delay=Math.min(Math.max(delay*2,100),2000);
      }
      await pause(delay,signal);
    }
  }
  drainContext():Promise<number>{
    if(this.draining)return this.draining;
    this.draining=this.drain().finally(()=>{this.draining=undefined;});
    return this.draining;
  }
  private async drain():Promise<number>{
    let insertedCount=0;
    for(const saved of Object.values(this.journal.read().inbox).sort((a,b)=>a.event.sequence-b.event.sequence)){
      if(!saved.event.deliveryRequired)continue;
      const eventId=saved.event.eventId;
      let entry=this.journal.read().inbox[eventId]!;
      if(!entry.item){
        this.journal.edit(state=>{
          const current=state.inbox[eventId]!;
          const compared=state.currentSnapshotId;
          current.stale=compared!==null&&compared!==current.event.sourceSnapshotId;
          const item=prepareDiagnosticContext(current.event,state.binding,compared);
          if(Buffer.byteLength(item.text)>this.maxContextBytes)throw new HostError('required_context_exceeds_budget');
          current.item=item;
          current.insertionReceiptId=randomUUID();
        });
        entry=this.journal.read().inbox[eventId]!;
      }
      if(!entry.inserted){
        // Reconciliation is mandatory after an interrupted insertion. Never invent exactly-once delivery.
        const reconciled=await this.host.findContext(entry.item!.marker);
        const inserted=reconciled??await this.host.insertContext(entry.item!,entry.insertionReceiptId!);
        this.validateInsertion(entry,inserted);
        this.journal.edit(state=>{state.inbox[eventId]!.inserted=inserted;});insertedCount++;
      }
    }
    await this.syncReceipts();
    return insertedCount;
  }
  private validateInsertion(entry:InboxEntry,inserted:InsertedContext):void{
    const item=entry.item!;
    if(inserted.marker!==item.marker||inserted.hostThreadId!==item.hostThreadId||inserted.generation!==item.generation||
       inserted.receipt.receiptId!==entry.insertionReceiptId||inserted.receipt.contextDigest!==item.digest||!inserted.receipt.contextItemId||
       !Number.isFinite(Date.parse(inserted.receipt.insertedAt)))throw new HostError('injection_unconfirmed');
  }
  /** Replay insertion ACK and observation separately. Cursor progress is unrelated. */
  async syncReceipts():Promise<void>{
    for(const entry of Object.values(this.journal.read().inbox)){
      if(entry.inserted&&!entry.acknowledged){
        const result=await this.broker.ack([entry.event.eventId],entry.inserted.receipt);
        this.validateReceiptResult(result,[entry.event.eventId],'acknowledged');
        this.journal.edit(state=>{state.inbox[entry.event.eventId]!.acknowledged=true;});
      }
    }
    const groups=new Map<string,InboxEntry[]>();
    for(const entry of Object.values(this.journal.read().inbox))if(entry.observation&&!entry.observed){
      const group=groups.get(entry.observation.observationId)??[];group.push(entry);groups.set(entry.observation.observationId,group);
    }
    for(const entries of groups.values()){
      const receipt=entries[0]!.observation!;
      if(entries.some(e=>!e.acknowledged))throw new HostError('observation_before_ack');
      const eventIds=entries.map(e=>e.event.eventId);
      const result=await this.broker.observe(eventIds,receipt);
      this.validateReceiptResult(result,eventIds,'model_observed');
      this.journal.edit(state=>{for(const entry of entries)state.inbox[entry.event.eventId]!.observed=true;});
    }
  }
  private validateReceiptResult(result:Record<string,unknown>,eventIds:string[],state:string):void{
    if(result.contractVersion!==PREVIEW_CONTRACT||result.clientId!==this.journal.binding.client.clientId||result.state!==state||
       !Array.isArray(result.eventIds)||canonical([...result.eventIds].sort())!==canonical([...eventIds].sort()))throw new HostError('receipt_not_confirmed');
  }
  /** Owner-enabled host scheduling calls this, active or resumed. It cannot unlock guarded actions by insertion alone. */
  async requestModelObservation():Promise<{observed:boolean;reason:string|null}>{
    if(this.modelActive)throw new HostError('model_invocation_in_flight');
    this.modelActive=true;
    try{
      await this.drainContext();
      const pending=this.journal.read().pendingModelRequest;
      const entries=Object.values(this.journal.read().inbox).filter(e=>e.event.deliveryRequired&&e.acknowledged&&!e.observed&&
        (!pending||pending.contexts.some(item=>item.digest===e.item?.digest&&item.marker===e.item.marker)));
      if(!entries.length)return {observed:false,reason:'no_pending_context'};
      const contexts=entries.map(e=>e.item!).sort((a,b)=>a.marker.localeCompare(b.marker));
      if(contexts.reduce((bytes,item)=>bytes+Buffer.byteLength(item.text),0)>this.maxContextBytes)throw new HostError('required_context_exceeds_budget');
      const b=this.journal.binding;
      const request:ModelRequest=pending??{invocationId:randomUUID(),hostThreadId:b.hostThreadId,generation:b.client.generation,contexts,
        requestDigest:digest(canonical({hostThreadId:b.hostThreadId,generation:b.client.generation,contexts}))};
      if(pending&&canonical(pending.contexts)!==canonical(contexts))throw new HostError('pending_model_context_mismatch');
      if(!pending)this.journal.edit(state=>{state.pendingModelRequest=request;});
      const witness=await this.host.invokeModel(request);
      if(witness.kind!=='actual-model-request'){
        this.journal.edit(state=>{state.lastProblem='model_observation_unavailable';state.pendingModelRequest=null;});
        return {observed:false,reason:witness.reason};
      }
      const expected=contexts.map(c=>c.digest).sort();
      if(witness.invocationId!==request.invocationId||witness.hostThreadId!==b.hostThreadId||witness.generation!==b.client.generation||
         witness.requestDigest!==request.requestDigest||!witness.modelRequestId||canonical([...witness.contextDigests].sort())!==canonical(expected))throw new HostError('model_request_proof_mismatch');
      const observation={observationId:randomUUID(),modelRequestId:witness.modelRequestId,contextDigests:expected};
      this.journal.edit(state=>{
        for(const previous of Object.values(state.inbox))if(previous.observation?.modelRequestId===witness.modelRequestId&&canonical(previous.observation.contextDigests)!==canonical(expected))throw new HostError('model_request_proof_reused');
        for(const entry of entries)state.inbox[entry.event.eventId]!.observation=observation;
        state.pendingModelRequest=null;
      });
      await this.syncReceipts();
      return {observed:true,reason:null};
    }finally{this.modelActive=false;}
  }
  mutationFence():{allowed:boolean;reasons:string[]}{
    const state=this.journal.read();const reasons:string[]=[];
    if(Object.values(state.submissions).some(x=>!x.status))reasons.push('submission_not_durable');
    if(Object.values(state.inbox).some(e=>e.event.deliveryRequired&&!e.observed))reasons.push('required_context_not_observed');
    if(state.lastProblem)reasons.push(state.lastProblem);
    // This is a controller scheduling decision, not a source/configuration writer lease.
    return {allowed:reasons.length===0,reasons};
  }
  async prepareFinal():Promise<GateCut>{
    await this.recoverSubmissions();await this.pollOnce();await this.drainContext();
    const cut=await this.broker.prepareGate();const b=this.journal.binding.client;
    if(cut.contractVersion!==PREVIEW_CONTRACT||cut.clientId!==b.clientId||cut.generation!==b.generation||cut.lineageId!==b.lineageId||
      typeof cut.cutId!=='string'||!cut.cutId||!Array.isArray(cut.requiredEventIds)||cut.requiredEventIds.some(id=>typeof id!=='string')||
      !Number.isSafeInteger(cut.eventFrontier)||!Number.isSafeInteger(cut.lineageEpoch)||typeof cut.ready!=='boolean')throw new HostError('gate_binding_mismatch');
    this.journal.edit(state=>{state.cut={value:cut,localEpoch:state.localEpoch};});return cut;
  }
  async completeFinal(cutId:string):Promise<GateResult>{
    const state=this.journal.read();const cut=state.cut;
    const binding=state.binding.client;
    if(!cut||cut.value.cutId!==cutId||cut.localEpoch!==state.localEpoch)throw new HostError('gate_cut_invalidated');
    if(cut.value.requiredEventIds.some(id=>!state.inbox[id]?.observed))return {allowed:false,actionPerformed:false,reasons:['required_context_not_observed'],cutId,
      clientId:binding.clientId,lineageId:binding.lineageId,generation:binding.generation,contractVersion:PREVIEW_CONTRACT};
    // Broker revalidates finite frontier and full current identity. Remote decision is NOT an action capability.
    const result=await this.broker.completeGate(cutId);
    if(result.actionPerformed)throw new HostError('unexpected_remote_guarded_action');
    if(result.contractVersion!==PREVIEW_CONTRACT||result.cutId!==cutId||result.clientId!==binding.clientId||
       result.lineageId!==binding.lineageId||result.generation!==binding.generation||typeof result.allowed!=='boolean'||result.actionPerformed!==false||
       !Array.isArray(result.reasons)||result.reasons.some(reason=>typeof reason!=='string'))throw new HostError('gate_result_binding_mismatch');
    return result;
  }
}
