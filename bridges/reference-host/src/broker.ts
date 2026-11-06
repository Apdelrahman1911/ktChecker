import { PREVIEW_CONTRACT, type BrokerPort, type JsonObject } from '@ktchecker/mcp-preview/rpc';
import type { Registration, Submission, ReviewStatus, EventPage, ContextReceipt, ObservationReceipt, GateCut, GateResult, PolicyReassessmentRequest } from './types.js';
import { checkedPolicyReassessmentView, checkedReviewEventPage, policyReassessmentRequestSchema, policyReassessmentStatusSchema } from '@ktchecker/mcp-preview';
import { HostError } from './journal.js';

/** Distinct client/adapter capabilities never enter model context or caller-controlled params. */
export class KernelBroker {
  constructor(private readonly rpc: BrokerPort, readonly registration: Registration) {
    if(registration.contractVersion!==PREVIEW_CONTRACT || registration.binding.clientId!==registration.client.clientId ||
       registration.binding.clientId!==registration.adapter.clientId || registration.client.token===registration.adapter.token)
      throw new Error('invalid_client_registration');
  }
  private client<T>(method:string,params:JsonObject={}):Promise<T>{
    return this.rpc.request<T>(method,{...params,credential:this.registration.client});
  }
  private adapter<T>(method:string,params:JsonObject):Promise<T>{
    return this.rpc.request<T>(method,{...params,credential:this.registration.adapter});
  }
  submit(request:Submission){return this.client<ReviewStatus>('review.submit',{...request});}
  status(reviewId:string){return this.client<ReviewStatus>('review.status',{reviewId});}
  /** Client capability only. These calls do not alter host snapshot/cut/receipt state. */
  async reassessPolicy(request:PolicyReassessmentRequest){
    const detached=policyReassessmentRequestSchema.parse(request);
    const value=await this.client<unknown>('review.policy.reassess',{request:detached});
    return checkedPolicyReassessmentView(value,this.registration.client.clientId,detached,this.registration.binding.generation);
  }
  async policyReassessmentStatus(idempotencyKey:string){
    const params=policyReassessmentStatusSchema.parse({idempotencyKey});
    const value=await this.client<unknown>('review.policy.status',params);
    return checkedPolicyReassessmentView(value,this.registration.client.clientId,idempotencyKey,this.registration.binding.generation);
  }
  cancel(reviewId:string){return this.client<ReviewStatus>('review.cancel',{reviewId});}
  async events(cursor:string|null,limit=100):Promise<EventPage>{
    const page=await this.client<unknown>('review.events',{cursor,limit});
    // Close the entire page and every generic/policy entry before any durable
    // inbox/cursor commit. A malformed page cannot be filtered or partly ACKed.
    try{return checkedReviewEventPage(page,this.registration.client.clientId,this.registration.binding.generation);}
    catch{throw new HostError('unsupported_event_payload');}
  }
  ack(eventIds:string[],receipt:ContextReceipt){return this.adapter<JsonObject>('review.ack',{eventIds,receipt});}
  observe(eventIds:string[],receipt:ObservationReceipt){return this.adapter<JsonObject>('review.observe',{eventIds,receipt});}
  prepareGate(){return this.client<GateCut>('gate.prepare');}
  completeGate(cutId:string){return this.client<GateResult>('gate.complete',{cutId});}
}
