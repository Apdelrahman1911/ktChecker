import { PREVIEW_CONTRACT, type BrokerPort, type JsonObject } from '@ktchecker/mcp-preview/rpc';
import type { Registration, Submission, ReviewStatus, EventPage, ContextReceipt, ObservationReceipt, GateCut, GateResult } from './types.js';

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
  cancel(reviewId:string){return this.client<ReviewStatus>('review.cancel',{reviewId});}
  events(cursor:string|null,limit=100){return this.client<EventPage>('review.events',{cursor,limit});}
  ack(eventIds:string[],receipt:ContextReceipt){return this.adapter<JsonObject>('review.ack',{eventIds,receipt});}
  observe(eventIds:string[],receipt:ObservationReceipt){return this.adapter<JsonObject>('review.observe',{eventIds,receipt});}
  prepareGate(){return this.client<GateCut>('gate.prepare');}
  completeGate(cutId:string){return this.client<GateResult>('gate.complete',{cutId});}
}
