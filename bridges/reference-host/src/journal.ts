import { chmodSync } from 'node:fs';
import { randomUUID, createHash } from 'node:crypto';
import { PREVIEW_CONTRACT } from '@ktchecker/mcp-preview/rpc';
import { assertPrivateStatePlatform } from '@ktchecker/mcp-preview/private-state';
import { HostError, PrivateDatabase } from './private-database.js';
export { HostError, privatePath } from './private-database.js';
import type { HostBinding, JournalState, EventPage, ReviewEvent } from './types.js';

export function digest(text:string):string{return 'sha256:'+createHash('sha256').update(text,'utf8').digest('hex');}
export function canonical(value:unknown):string {
  if(Array.isArray(value)) return '['+value.map(canonical).join(',')+']';
  if(value!==null && typeof value==='object') return '{'+Object.keys(value).sort().map(k=>JSON.stringify(k)+':'+canonical((value as Record<string,unknown>)[k])).join(',')+'}';
  const result=JSON.stringify(value); if(result===undefined)throw new Error('non_json_value'); return result;
}
export interface JournalOptions {maxEvents?:number;maxStateBytes?:number;leaseMillis?:number;now?:()=>number}

/** SQLite FULL/WAL atomic page+cursor commits. One leased controller per explicitly bound store. */
export class InboxJournal {
  private readonly db:PrivateDatabase;
  private readonly owner=randomUUID();
  private readonly now:()=>number;
  private readonly leaseMillis:number;
  private readonly heartbeat:NodeJS.Timeout;
  private readonly maxEvents:number;
  private readonly maxStateBytes:number;
  private closed=false;
  private leaseProblem=false;
  constructor(directory:string,readonly binding:HostBinding,options:JournalOptions={}){
    assertPrivateStatePlatform();
    if(!Number.isSafeInteger(binding.client.generation)||binding.client.generation<1||!binding.hostThreadId)throw new HostError('invalid_binding');
    this.now=options.now??Date.now;this.leaseMillis=options.leaseMillis??15_000;
    this.maxEvents=options.maxEvents??4096;this.maxStateBytes=options.maxStateBytes??16*1024*1024;
    if(this.leaseMillis<300||this.maxEvents<1||this.maxStateBytes<1024)throw new HostError('invalid_journal_limits');
    this.db=new PrivateDatabase(directory,'inbox.sqlite');
    try{
      this.db.exec('CREATE TABLE IF NOT EXISTS journal (id INTEGER PRIMARY KEY CHECK(id=1), state TEXT NOT NULL)');
      this.db.exec('CREATE TABLE IF NOT EXISTS controller (id INTEGER PRIMARY KEY CHECK(id=1), owner TEXT NOT NULL, expires INTEGER NOT NULL)');
      this.db.exec('BEGIN IMMEDIATE');
      const existing=this.db.prepare('SELECT state FROM journal WHERE id=1').get() as {state:string}|undefined;
      if(existing){
        const previous=JSON.parse(existing.state) as JournalState;
        if(previous.version!==1)throw new HostError('incompatible_journal_version');
        if(canonical(previous.binding)!==canonical(binding))throw new HostError('session_generation_or_binding_mismatch');
      }
      else{
        const state:JournalState={version:1,binding,fetchCursor:null,inbox:{},submissions:{},currentSnapshotId:null,localEpoch:0,cut:null,lastProblem:null};
        this.db.prepare('INSERT INTO journal(id,state) VALUES(1,?)').run(JSON.stringify(state));
      }
      const lease=this.db.prepare('SELECT owner,expires FROM controller WHERE id=1').get() as {owner:string;expires:number}|undefined;
      if(lease&&lease.expires>this.now())throw new HostError('consumer_controller_busy');
      this.db.prepare('INSERT INTO controller(id,owner,expires) VALUES(1,?,?) ON CONFLICT(id) DO UPDATE SET owner=excluded.owner,expires=excluded.expires').run(this.owner,this.now()+this.leaseMillis);
      this.db.exec('COMMIT');
      // Darwin admission/creator permissions precede every write: no late chmod
      // "repair" may turn an unsafe sidecar into a claimed private write.
      if(process.platform!=='win32'&&process.platform!=='darwin')for(const suffix of ['-wal','-shm']){try{chmodSync(this.db.path+suffix,0o600);}catch(error){if((error as NodeJS.ErrnoException).code!=='ENOENT')throw error;}}
    }catch(error){try{this.db.exec('ROLLBACK');}catch{}this.db.close();throw error;}
    this.heartbeat=setInterval(()=>{try{this.edit(()=>{});}catch{this.leaseProblem=true;}},Math.floor(this.leaseMillis/3));
    this.heartbeat.unref();
  }
  read():JournalState{
    if(this.closed)throw new HostError('journal_closed');
    const lease=this.db.prepare('SELECT owner FROM controller WHERE id=1').get() as {owner:string}|undefined;
    if(lease?.owner!==this.owner)throw new HostError('controller_lease_lost');
    return JSON.parse((this.db.prepare('SELECT state FROM journal WHERE id=1').get() as {state:string}).state) as JournalState;
  }
  /** Protected local artifact access. This read does not constitute insertion or model observation. */
  readPayload(eventId:string,expectedDigest:string):string{
    const entry=this.read().inbox[eventId];
    if(!entry||entry.event.payloadDigest!==expectedDigest||digest(entry.event.payload)!==expectedDigest)throw new HostError('payload_artifact_mismatch');
    return entry.event.payload;
  }
  edit<T>(operation:(state:JournalState)=>T):T{
    if(this.closed||this.leaseProblem)throw new HostError('controller_lease_lost');
    this.db.exec('BEGIN IMMEDIATE');
    try{
      const lease=this.db.prepare('SELECT owner FROM controller WHERE id=1').get() as {owner:string}|undefined;
      if(lease?.owner!==this.owner)throw new HostError('controller_lease_lost');
      const state=this.read();const result=operation(state);const serialized=JSON.stringify(state);
      if(Buffer.byteLength(serialized)>this.maxStateBytes)throw new HostError('inbox_capacity');
      this.db.prepare('UPDATE journal SET state=? WHERE id=1').run(serialized);
      this.db.prepare('UPDATE controller SET expires=? WHERE id=1 AND owner=?').run(this.now()+this.leaseMillis,this.owner);
      this.db.exec('COMMIT');return result;
    }catch(error){this.db.exec('ROLLBACK');throw error;}
  }
  commitPage(page:EventPage):void{
    if(page.contractVersion!==PREVIEW_CONTRACT||!Array.isArray(page.events)||typeof page.cursor!=='string'||typeof page.hasMore!=='boolean')throw new HostError('invalid_event_page');
    this.edit(state=>{
      for(const event of page.events){
        this.validateEvent(event);
        const previous=state.inbox[event.eventId];
        if(previous){if(canonical(previous.event)!==canonical(event))throw new HostError('event_revision_conflict');continue;}
        if(Object.keys(state.inbox).length>=this.maxEvents)throw new HostError('inbox_capacity');
        state.inbox[event.eventId]={event,item:null,insertionReceiptId:null,inserted:null,acknowledged:false,observation:null,observed:false,stale:false};
      }
      // Cursor NEVER advances until all event bytes commit in this same transaction.
      state.fetchCursor=page.cursor;
    });
  }
  private validateEvent(event:ReviewEvent):void{
    const b=this.binding.client;
    if(event.contractVersion!==PREVIEW_CONTRACT||event.clientId!==b.clientId||event.generation!==b.generation)throw new HostError('cross_consumer_event');
    if(typeof event.eventId!=='string'||!event.eventId||event.eventId.length>256||['__proto__','prototype','constructor'].includes(event.eventId)||
       typeof event.reviewId!=='string'||typeof event.patchId!=='string'||typeof event.kind!=='string'||!Number.isSafeInteger(event.sequence)||event.sequence<1||
       typeof event.payload!=='string'||typeof event.deliveryRequired!=='boolean'||digest(event.payload)!==event.payloadDigest||
       !/^sha256:[0-9a-f]{64}$/.test(event.sourceSnapshotId))throw new HostError('invalid_event');
    try{JSON.parse(event.payload);}catch{throw new HostError('invalid_event_payload');}
  }
  close():void{
    if(this.closed)return;clearInterval(this.heartbeat);
    try{this.db.prepare('DELETE FROM controller WHERE id=1 AND owner=?').run(this.owner);}finally{this.closed=true;this.db.close();}
  }
}
