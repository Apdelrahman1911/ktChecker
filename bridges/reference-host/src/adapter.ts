import { randomUUID } from 'node:crypto';
import { PREVIEW_CONTRACT } from '@ktchecker/mcp-preview/rpc';
import { KernelBroker } from './broker.js';
import { canonical, digest, HostError, InboxJournal } from './journal.js';
import { prepareDiagnosticContext } from './context-projection.js';
import type { ContextItem, GateCut, GateResult, HostPort, InboxEntry, InsertedContext, JournalState, ModelRequest, Submission } from './types.js';

const pause=(milliseconds:number,signal:AbortSignal)=>new Promise<void>(resolve=>{
  if(signal.aborted){resolve();return;}
  const done=()=>{clearTimeout(timer);signal.removeEventListener('abort',done);resolve();};
  const timer=setTimeout(done,milliseconds);signal.addEventListener('abort',done,{once:true});
});
export interface AdapterOptions { maxContextBytes?:number; maxPendingSubmissions?:number }

// Fixed public HostError codes only. Unknown codes/messages never become background diagnostics.
const BACKGROUND_PROBLEM_CODES=new Set([
  'codex_backpressure','codex_closed','codex_connection_state',
  'codex_context_binding_mismatch','codex_context_not_inserted','codex_context_revision',
  'codex_controller_busy','codex_controller_lease_lost','codex_disconnected',
  'codex_history_cursor_stalled','codex_history_unconfirmed','codex_insertion_reconciliation_required',
  'codex_invocation_effort_revision','codex_invocation_revision','codex_model_request_binding_mismatch',
  'codex_model_request_outcome_unknown','codex_model_reroute_notification_invalid','codex_model_reroute_ownership_bound',
  'codex_owned_thread_bound','codex_protocol_error','codex_public_history_bound',
  'codex_public_message_revision','codex_public_output_bound','codex_public_turn_timeout',
  'codex_reconciliation_history_bound','codex_request_rejected','codex_request_timeout_outcome_unknown',
  'codex_request_too_large','codex_requested_effort_not_confirmed','codex_requested_model_not_confirmed',
  'codex_response_too_large','codex_resume_binding_mismatch','codex_selected_model_rerouted',
  'codex_session_binding_mismatch','codex_store_capacity','codex_thread_creation_outcome_unknown',
  'codex_thread_unconfirmed','codex_turn_unconfirmed','codex_unavailable',
  'codex_unowned_thread','consumer_controller_busy','context_digest_mismatch',
  'context_marker_mismatch','controller_lease_lost','coverage_validation_ambiguous_context',
  'coverage_validation_duplicate_reason','coverage_validation_duplicate_row','coverage_validation_origin_unconfirmed',
  'coverage_validation_proof_unavailable','coverage_validation_reason_set_mismatch','cross_consumer_event',
  'darwin_database_close_uncertain','darwin_sql_surface_unqualified','darwin_sqlite_build_unqualified',
  'darwin_sqlite_schema_unqualified','darwin_sqlite_sync_unqualified','darwin_sqlite_temp_unqualified',
  'darwin_sqlite_timeout_unqualified','darwin_sqlite_wal_unqualified','event_digest_mismatch',
  'event_revision_conflict','finding_obligation_mismatch','gate_binding_mismatch',
  'gate_cut_invalidated','gate_result_binding_mismatch','host_binding_mismatch',
  'host_configuration_permissions','idempotency_conflict','inbox_capacity',
  'incompatible_journal_version','injection_unconfirmed','invalid_binding',
  'invalid_codex_effort','invalid_codex_limits','invalid_context_budget',
  'invalid_context_identity','invalid_controller_tool','invalid_event',
  'invalid_event_page','invalid_event_payload','invalid_idempotency_key',
  'invalid_invocation_id','invalid_journal_limits','journal_closed',
  'missing_immutable_snapshot','model_invocation_in_flight','model_request_proof_mismatch',
  'model_request_proof_reused','observation_before_ack','payload_artifact_mismatch',
  'payload_digest_mismatch','pending_model_context_mismatch','private_database_unavailable',
  'projection_field_collision','projection_scope_mismatch','receipt_not_confirmed',
  'required_context_exceeds_budget','session_generation_or_binding_mismatch','state_permissions',
  'submission_backpressure','submission_snapshot_mismatch','unexpected_remote_guarded_action',
  'unsafe_host_configuration','unsafe_state_path','unsupported_coverage',
  'unsupported_diagnostic_context','unsupported_event_payload','unsupported_findings',
  'unsupported_review_reasons','usage_reference_host_config_private_json_optional_max_observations_0_to_99',
]);
function backgroundProblem(error:unknown):string{
  const code=(cause:unknown)=>cause instanceof HostError&&BACKGROUND_PROBLEM_CODES.has(cause.code)?cause.code:'broker_or_host_unavailable';
  if(error instanceof AggregateError&&error.message==='receipt_sync_failed'){
    // Bounded distinct codes in first-seen order; direct callers retain every raw cause.
    return 'receipt_sync_failed:'+canonical([...new Set(error.errors.map(code))]);
  }
  return code(error);
}

// Local response fence only, never permission for a later unfenced native action.
// Receipt/inbox progress may continue while a gate RPC is in flight.
function gateResponseFence(state:JournalState):string{
  return canonical({binding:state.binding,localEpoch:state.localEpoch,cut:state.cut});
}

/** Polling/inbox controller only. No token or proof operation is exposed as a coding-model tool. */
export class ReferenceHost {
  private draining:Promise<number>|undefined;
  private modelActive=false;
  private readonly maxContextBytes:number;
  private readonly maxPendingSubmissions:number;
  constructor(readonly journal:InboxJournal,private readonly broker:KernelBroker,private readonly host:HostPort,options:AdapterOptions={}){
    if(canonical(journal.binding)!==canonical(host.binding)||canonical(journal.binding.client)!==canonical(broker.registration.binding))throw new HostError('host_binding_mismatch');
    this.maxContextBytes=options.maxContextBytes??32_768;this.maxPendingSubmissions=options.maxPendingSubmissions??32;
    if(!Number.isSafeInteger(this.maxContextBytes)||this.maxContextBytes<1)throw new HostError('invalid_context_budget');
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
        const code=backgroundProblem(error);
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
  /** Replay each receipt independently; ready observation proofs precede unrelated ACKs. */
  async syncReceipts():Promise<void>{
    const failures:unknown[]=[];
    try{
      const groups=new Map<string,InboxEntry[]>();
      for(const entry of Object.values(this.journal.read().inbox))if(entry.observation&&!entry.observed){
        const group=groups.get(entry.observation.observationId)??[];group.push(entry);groups.set(entry.observation.observationId,group);
      }
      const publish=async(entries:InboxEntry[])=>{
        const current=this.journal.read();
        if(entries.some(e=>!current.inbox[e.event.eventId]?.acknowledged)){
          failures.push(new HostError('observation_before_ack'));return;
        }
        const receipt=entries[0]!.observation!,eventIds=entries.map(e=>e.event.eventId);
        try{
          const result=await this.broker.observe(eventIds,receipt);
          this.validateReceiptResult(result,eventIds,'model_observed');
        }catch(error){failures.push(error);return;}
        this.journal.edit(state=>{for(const entry of entries)state.inbox[entry.event.eventId]!.observed=true;});
      };
      const deferred:InboxEntry[][]=[];
      for(const entries of groups.values()){
        if(entries.every(e=>e.acknowledged))await publish(entries);
        else deferred.push(entries);
      }
      const ackIds=Object.values(this.journal.read().inbox).filter(e=>e.inserted&&!e.acknowledged).map(e=>e.event.eventId);
      for(const eventId of ackIds){
        // A previous failed/held RPC may outlive journal ownership. Recheck before EVERY ACK.
        const entry=this.journal.read().inbox[eventId]!;
        if(entry.inserted&&!entry.acknowledged){
          try{
            const result=await this.broker.ack([entry.event.eventId],entry.inserted.receipt);
            this.validateReceiptResult(result,[entry.event.eventId],'acknowledged');
          }catch(error){failures.push(error);continue;}
          this.journal.edit(state=>{state.inbox[entry.event.eventId]!.acknowledged=true;});
        }
      }
      // Only these groups depend on this pass's ACKs. No failed RPC is retried here.
      for(const entries of deferred)await publish(entries);
    }catch(error){
      // Local journal/lease failures stop further RPCs, without losing prior failures.
      failures.push(error);
    }
    if(failures.length===1)throw failures[0];
    if(failures.length>1)throw new AggregateError(failures,'receipt_sync_failed');
  }
  private validateReceiptResult(result:Record<string,unknown>,eventIds:string[],state:string):void{
    if(result.contractVersion!==PREVIEW_CONTRACT||result.clientId!==this.journal.binding.client.clientId||result.state!==state||
       !Array.isArray(result.eventIds)||canonical([...result.eventIds].sort())!==canonical([...eventIds].sort()))throw new HostError('receipt_not_confirmed');
  }
  /** Owner-enabled host scheduling calls this, active or resumed. Success covers only this batch;
   * remaining required entries still block mutation/finalization. Insertion alone never unlocks them. */
  async requestModelObservation():Promise<{observed:boolean;reason:string|null}>{
    if(this.modelActive)throw new HostError('model_invocation_in_flight');
    this.modelActive=true;
    try{
      // A saved request may reconcile without inserting unrelated new contexts first.
      // Its exact context/budget/proof checks still apply; new obligations stay blocked.
      if(!this.journal.read().pendingModelRequest)await this.drainContext();
      const pending=this.journal.read().pendingModelRequest;
      let entries=Object.values(this.journal.read().inbox).filter(e=>e.event.deliveryRequired&&e.acknowledged&&!e.observed&&
        (!pending||pending.contexts.some(item=>item.digest===e.item?.digest&&item.marker===e.item.marker)));
      if(!entries.length)return {observed:false,reason:'no_pending_context'};
      if(!pending){
        // Only NEW invocations take a bounded oldest-first prefix. Never re-batch an
        // uncertain saved request or skip older work to pack smaller, newer entries.
        entries.sort((a,b)=>a.event.sequence-b.event.sequence||(a.event.eventId<b.event.eventId?-1:a.event.eventId>b.event.eventId?1:0));
        let bytes=0;
        const batch:InboxEntry[]=[];
        for(const entry of entries){
          const size=Buffer.byteLength(entry.item!.text);
          if(size>this.maxContextBytes)throw new HostError('required_context_exceeds_budget');
          if(bytes+size>this.maxContextBytes)break;
          batch.push(entry);bytes+=size;
        }
        // Omitted entries retain their complete items/ACKs and remain unobserved.
        // Follow-up batches need separate owner-budgeted observation calls.
        entries=batch;
      }
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
    const before=this.journal.read(),fence=gateResponseFence(before),b=before.binding.client;
    await this.recoverSubmissions();await this.pollOnce();await this.drainContext();
    const cut=await this.broker.prepareGate();
    if(cut.contractVersion!==PREVIEW_CONTRACT||cut.clientId!==b.clientId||cut.generation!==b.generation||cut.lineageId!==b.lineageId||
      typeof cut.cutId!=='string'||!cut.cutId||!Array.isArray(cut.requiredEventIds)||cut.requiredEventIds.some(id=>typeof id!=='string')||
      !Number.isSafeInteger(cut.eventFrontier)||!Number.isSafeInteger(cut.lineageEpoch)||typeof cut.ready!=='boolean')throw new HostError('gate_binding_mismatch');
    this.journal.edit(state=>{
      // Never stamp an old reply with a newer epoch or replace a concurrently prepared cut.
      if(gateResponseFence(state)!==fence)throw new HostError('gate_cut_invalidated');
      state.cut={value:cut,localEpoch:before.localEpoch};
    });return cut;
  }
  async completeFinal(cutId:string):Promise<GateResult>{
    const state=this.journal.read();const cut=state.cut;
    const fence=gateResponseFence(state);
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
    // A remote decision computed before known local invalidation is not current on arrival.
    if(gateResponseFence(this.journal.read())!==fence)throw new HostError('gate_cut_invalidated');
    return result;
  }
}
