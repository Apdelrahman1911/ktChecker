// Synthetic test doubles. Their positive receipts exercise adapter state transitions, NOT real model delivery.
import { PREVIEW_CONTRACT, type BrokerPort, type JsonObject } from '@ktchecker/mcp-preview/rpc';
import { canonical, digest, HostError } from '../src/journal.js';
import type { Registration, ReviewEvent, HostBinding, HostPort, ContextItem, InsertedContext, ModelRequest, ModelWitness, ContextReceipt } from '../src/types.js';
export const S1='sha256:'+'a'.repeat(64),S2='sha256:'+'b'.repeat(64);
export function registration(clientId='client-a',generation=1):Registration{return {contractVersion:PREVIEW_CONTRACT,binding:{ownerId:'owner',workspaceId:'workspace',worktreeId:'tree',clientId,lineageId:'lineage-'+clientId,generation},client:{clientId,token:'client-token-'+clientId},adapter:{clientId,token:'adapter-token-'+clientId}};}
export function binding(r:Registration):HostBinding{return {client:r.binding,hostKind:'synthetic-test-port',hostThreadId:'thread-'+r.binding.clientId};}
export function event(r:Registration,id='event-1',snapshot=S1,sequence=1):ReviewEvent{
  const payload=canonical({state:'complete',result:{findings:[{ruleId:'fixture.nullability',diagnosis:'Nullable name cannot be assigned to String.',location:{path:'Label.kt',line:1,column:34},verification:[{kind:'compile',expected:'Compile this fixture without the type mismatch.'}]}],coverage:[{state:'findings',required:true}]}});
  return {contractVersion:PREVIEW_CONTRACT,eventId:id,clientId:r.binding.clientId,generation:r.binding.generation,sequence,reviewId:'review-'+id,patchId:'patch-'+id,sourceSnapshotId:snapshot,kind:'terminal',deliveryRequired:true,payloadDigest:digest(payload),payload};
}
export class FixtureKernel implements BrokerPort{
  readonly clients=new Map<string,Registration>();readonly eventsByClient=new Map<string,ReviewEvent[]>();
  readonly acked=new Map<string,ContextReceipt>();readonly observed=new Set<string>();
  readonly submissions=new Map<string,JsonObject>();readonly calls:{method:string;params:JsonObject}[]=[];
  dropAckOnce=false;dropSubmitOnce=false;forceReceiptState:string|null=null;
  gateResultOverride:JsonObject|null=null;
  constructor(...registrations:Registration[]){for(const r of registrations){this.clients.set(r.binding.clientId,r);this.eventsByClient.set(r.binding.clientId,[]);}}
  async request<T=JsonObject>(method:string,params:JsonObject):Promise<T>{
    this.calls.push({method,params:structuredClone(params)});
    const c=params.credential as {clientId:string;token:string};const r=this.clients.get(c.clientId);
    if(!r)throw new HostError('unauthorized');const privileged=['review.ack','review.observe'].includes(method);
    if(c.token!==(privileged?r.adapter.token:r.client.token))throw new HostError('unauthorized');
    const events=this.eventsByClient.get(c.clientId)!;let result:unknown;
    if(method==='review.events'){
      if(params.cursor!==null&&typeof params.cursor==='string'&&!params.cursor.startsWith(c.clientId+':'))throw new HostError('cursor_wrong_client');
      const n=params.cursor?Number(String(params.cursor).split(':')[1]):0;
      result={contractVersion:PREVIEW_CONTRACT,events:events.slice(n),cursor:c.clientId+':'+events.length,hasMore:false};
    }else if(method==='review.submit'){
      const key=c.clientId+':'+params.idempotencyKey;
      const input=params.input as {snapshot:{snapshotId:string}};
      result=this.submissions.get(key)??{contractVersion:PREVIEW_CONTRACT,clientId:c.clientId,reviewId:'review-'+params.patchId,sourceSnapshotId:input.snapshot.snapshotId,state:'pending'};
      this.submissions.set(key,result as JsonObject);
      if(this.dropSubmitOnce){this.dropSubmitOnce=false;throw new HostError('lost_submit_response');}
    }else if(privileged){
      const ids=params.eventIds as string[];
      for(const id of ids)if(!events.some(e=>e.eventId===id))throw new HostError('cross_consumer_receipt');
      if(method==='review.ack'){
        for(const id of ids)this.acked.set(id,params.receipt as unknown as ContextReceipt);
        if(this.dropAckOnce){this.dropAckOnce=false;throw new HostError('lost_ack_response');}
      }else{
        const receipt=params.receipt as {contextDigests:string[]};
        for(const id of ids)if(!this.acked.has(id)||!receipt.contextDigests.includes(this.acked.get(id)!.contextDigest))throw new HostError('unacknowledged_context');
        for(const id of ids)this.observed.add(id);
      }
      result={contractVersion:PREVIEW_CONTRACT,clientId:c.clientId,eventIds:[...ids].sort(),state:this.forceReceiptState??(method==='review.ack'?'acknowledged':'model_observed')};
    }else if(method==='gate.prepare')result={contractVersion:PREVIEW_CONTRACT,cutId:'cut-'+events.length,clientId:c.clientId,lineageId:r.binding.lineageId,generation:r.binding.generation,lineageEpoch:events.length,sourceSnapshotId:events.at(-1)?.sourceSnapshotId??null,eventFrontier:events.length,requiredEventIds:events.filter(e=>e.deliveryRequired).map(e=>e.eventId),ready:true,reasons:[]};
    else if(method==='gate.complete')result={contractVersion:PREVIEW_CONTRACT,clientId:c.clientId,lineageId:r.binding.lineageId,generation:r.binding.generation,cutId:params.cutId,allowed:params.cutId==='cut-'+events.length&&events.every(e=>!e.deliveryRequired||this.observed.has(e.eventId)),actionPerformed:false,reasons:[],...this.gateResultOverride};
    else throw new HostError('unknown_fixture_method');
    return structuredClone(result) as T;
  }
}
export class FixtureHost implements HostPort{
  readonly receipts=new Map<string,InsertedContext>();readonly items=new Map<string,ContextItem>();
  insertionCalls=0;modelCalls=0;throwAfterInsertOnce=false;
  mode:'unavailable'|'insertion-only'|'test-only'|'protected-fixture'='unavailable';
  wrongRequest=false;barrier:Promise<void>|null=null;
  constructor(readonly binding:HostBinding){}
  async findContext(marker:string){return structuredClone(this.receipts.get(marker)??null);}
  async insertContext(item:ContextItem,receiptId:string){
    this.insertionCalls++;const receipt:InsertedContext={marker:item.marker,hostThreadId:item.hostThreadId,generation:item.generation,
      receipt:{receiptId,contextDigest:item.digest,contextItemId:'fixture-item-'+this.insertionCalls,insertedAt:'2026-11-04T00:00:00Z'}};
    this.receipts.set(item.marker,receipt);this.items.set(item.marker,structuredClone(item));
    if(this.throwAfterInsertOnce){this.throwAfterInsertOnce=false;throw new HostError('fixture_crash_after_insert');}
    return structuredClone(receipt);
  }
  async invokeModel(request:ModelRequest):Promise<ModelWitness>{
    this.modelCalls++;if(this.barrier)await this.barrier;
    if(this.mode!=='protected-fixture')return {kind:this.mode,reason:'Synthetic receiver is not genuine host proof'};
    // Dependency-injection test of the protected witness SPI. Never a release/demo result.
    return {kind:'actual-model-request',invocationId:request.invocationId,modelRequestId:'synthetic-request-'+this.modelCalls,
      hostThreadId:request.hostThreadId,generation:request.generation,requestDigest:this.wrongRequest?'wrong':request.requestDigest,contextDigests:request.contexts.map(c=>c.digest)};
  }
}
