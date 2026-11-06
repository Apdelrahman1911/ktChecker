import { test } from 'node:test';
import assert from 'node:assert/strict';
import { CLIENT_CAPABILITIES_META_KEY, CLIENT_INFO_META_KEY, PROTOCOL_VERSION_META_KEY, Client, InMemoryTransport } from '@modelcontextprotocol/client';
import { bridgeConfigSchema, serveBridge, FINDING_WIRE_CAPABILITY, MCP_PROFILE } from '../src/server.js';
import { FINDING_WIRE_PROFILE, PREVIEW_CONTRACT, type BrokerPort, type JsonObject } from '../src/rpc.js';

const compatibleCapabilities={experimental:{[FINDING_WIRE_CAPABILITY]:{profile:FINDING_WIRE_PROFILE}}};
type Capabilities=NonNullable<ConstructorParameters<typeof Client>[1]>['capabilities'];
const attachment={kind:'upstream_sarif',digest:'sha256:'+'c'.repeat(64),artifactRef:'  /outputs/attempt-1/provider-a/detekt.sarif  '};
// These are actual SDK/production dispatcher controls, not config-file privacy or
// native storage qualification. Keep those guards in main.ts and their own tests;
// an unsupported OS must not prevent this independent in-memory MCP coverage.
function fixtureTransport(){
  let dispatchCount=0;
  const broker:BrokerPort={async request<T=JsonObject>(method:string,parameters:JsonObject):Promise<T>{
    const {credential,...params}=structuredClone(parameters);
    assert.deepEqual(credential,{clientId:'client-a',token:'fixture-private-client-capability'});
    return {contractVersion:PREVIEW_CONTRACT,method,clientId:'client-a',params,state:'pending',outcome:'unknown',dispatchCount:++dispatchCount,
      ...(params.reviewId==='large'?{large:'x'.repeat(40000)}:{}),
      ...(params.reviewId==='attachments'?{findings:[{ruleId:'fixture.rule',evidence:[{kind:'upstream_sarif',summary:'Complete report',digest:attachment.digest,artifactRef:null}],retrievalAttachments:[structuredClone(attachment)]}]}:{})} as T;
  }};
  const [transport,serverTransport]=InMemoryTransport.createLinkedPair();
  const config=bridgeConfigSchema.parse({broker:{command:'unused-in-memory',args:[]},credential:{clientId:'client-a',token:'fixture-private-client-capability'},submissions:{prepared:{patchId:'p1',idempotencyKey:'key-p1',input:{snapshot:{snapshotId:'fixture-only'},materializedRoot:'/owner/prepared/fixture'}}}});
  const server=serveBridge(broker,config,{transport:serverTransport});
  return {server,transport};
}
async function setup(t: {after(fn:()=>Promise<unknown>):void}, mode: 'legacy'|{pin:string}={pin:MCP_PROFILE}, capabilities:Capabilities=compatibleCapabilities) {
  const {server,transport}=fixtureTransport();
  const client=new Client({name:'ktchecker-sdk-protocol-test',version:'0.1.0'},
    {versionNegotiation:{mode},...(capabilities===undefined?{}:{capabilities})});
  t.after(async()=>{try{await server.close();}finally{await client.close();}});
  await client.connect(transport);
  return client;
}

// Low-level SDK transport deliberately varies per-request metadata, including invalid data.
// No SDK client capability cache or model tool argument is used as a replacement grant.
async function rawPeer(t:{after(fn:()=>Promise<unknown>):void}){
  const {server,transport}=fixtureTransport();let nextId=0;
  t.after(async()=>{try{await server.close();}finally{await transport.close();}});
  await transport.start();
  return {close:()=>transport.close(),request:(method:string,params:Record<string,unknown>,capabilities:unknown=compatibleCapabilities,protocol:string=MCP_PROFILE)=>{
    const id=++nextId;
    return new Promise<Record<string,unknown>>((resolve,reject)=>{
      const timer=setTimeout(()=>reject(new Error('fixture_response_timeout')),5000);
      const fail=(error:unknown)=>{clearTimeout(timer);reject(error);};
      transport.onerror=fail;transport.onclose=()=>fail(new Error('fixture_peer_closed'));
      transport.onmessage=message=>{
        if(!('id' in message)||message.id!==id)return;
        clearTimeout(timer);resolve(message as unknown as Record<string,unknown>);
      };
      void transport.send({jsonrpc:'2.0',id,method,params:{...params,_meta:{
        [PROTOCOL_VERSION_META_KEY]:protocol,[CLIENT_INFO_META_KEY]:{name:'raw-admission-fixture',version:'0.1.0'},
        [CLIENT_CAPABILITIES_META_KEY]:capabilities,
      }}}).catch(fail);
    });
  }};
}
function wireResult(reply:Record<string,unknown>):Record<string,unknown>{
  assert.equal(reply.error,undefined);
  assert(reply.result&&typeof reply.result==='object'&&!Array.isArray(reply.result));
  return reply.result as Record<string,unknown>;
}
function compatibilityError(reply:Record<string,unknown>):void{
  const result=wireResult(reply);assert.equal(result.isError,true);
  assert.equal((result.structuredContent as {error:{code:string}}).error.code,'incompatible_finding_wire_profile');
  assert(!JSON.stringify(reply).includes('fixture-private-client-capability'));
}

test('official SDK current-profile discovery/tools and structured preview result', {timeout:10000}, async t=>{
  const client=await setup(t);
  const tools=await client.listTools();
  assert.deepEqual(tools.tools.map(x=>x.name).sort(),['review.cancel','review.events','review.policy.reassess','review.policy.status','review.status','review.submit']);
  for(const method of ['review.ack','review.observe','client.register','gate.complete','snapshot.register']) assert(!tools.tools.some(x=>x.name===method));
  const result=await client.callTool({name:'review.status',arguments:{reviewId:'r1'}});
  assert.equal(result.isError,undefined);
  const body=result.structuredContent as Record<string,unknown>;
  assert.equal(body.contractVersion,PREVIEW_CONTRACT);
  assert.equal(body.state,'pending');
  assert.equal(body.outcome,'unknown');
  assert.equal(body.clientId,'client-a');
  assert(!JSON.stringify(result).includes('fixture-private-client-capability'));
});
test('model cannot select credentials, execution settings, or an unprepared submission', {timeout:10000}, async t=>{
  const client=await setup(t);
  const invalid=await client.callTool({name:'review.status',arguments:{reviewId:'r1',credential:{clientId:'client-b',token:'other'}}});
  assert.equal(invalid.isError,true);
  const missing=await client.callTool({name:'review.submit',arguments:{submissionId:'not-prepared'}});
  assert.equal(missing.isError,true);
  const inherited=await client.callTool({name:'review.submit',arguments:{submissionId:'toString'}});
  assert.equal((inherited.structuredContent as {error:{code:string}}).error.code,'unknown_prepared_submission');
  const valid=await client.callTool({name:'review.submit',arguments:{submissionId:'prepared'}});
  const submitted=valid.structuredContent as Record<string,unknown>;
  assert.equal(submitted.method,'review.submit');
  assert.equal((submitted.params as {patchId:string}).patchId,'p1');
  await assert.rejects(client.callTool({name:'review.observe',arguments:{}}), /not found/);
});
test('MCP model-facing output budget is separate from private RPC payload capacity', {timeout:10000}, async t=>{
  const client=await setup(t);
  const result=await client.callTool({name:'review.status',arguments:{reviewId:'large'}});
  assert.equal(result.isError,true);
  assert.equal((result.structuredContent as {error:{code:string}}).error.code,'model_output_exceeds_budget');
});
test('legacy handshake rejected rather than masquerading as modern MCP', {timeout:10000}, async t=>{
  await assert.rejects(setup(t,'legacy'),/protocol|version|unsupported/i);
});
test('configuration does not accept adapter credentials or arbitrary server properties',()=>{
  assert.throws(()=>bridgeConfigSchema.parse({broker:{command:'ktchecker',args:['rpc']},credential:{clientId:'c',token:'token'},adapter:{token:'secret'}}));
});

// All admission controls below are synthetic SOURCE, not executed profile conformance.
test('admitted SDK client retains nonempty attachment locators without adding profile to result bytes',{timeout:10000},async t=>{
  const client=await setup(t);
  const result=await client.callTool({name:'review.status',arguments:{reviewId:'attachments'}});
  assert.equal(result.isError,undefined);
  const body=result.structuredContent as {findings:{retrievalAttachments:unknown[]}[];params:unknown};
  assert.deepEqual(body.findings[0]!.retrievalAttachments,[attachment]);
  assert.deepEqual(body.params,{reviewId:'attachments'});
  assert.equal(Object.hasOwn(body,'findingWireProfile'),false);
});
test('old current-profile SDK peer may discover tools but cannot invoke without the Finding declaration',{timeout:10000},async t=>{
  const client=await setup(t,{pin:MCP_PROFILE},{});
  assert.equal((await client.listTools()).tools.length,6);
  const result=await client.callTool({name:'review.status',arguments:{reviewId:'r1'}});
  assert.equal(result.isError,true);
  assert.equal((result.structuredContent as {error:{code:string}}).error.code,'incompatible_finding_wire_profile');
});
test('discovery advertises exact admission; all six unadmitted tools fail before any broker dispatch',{timeout:15000},async t=>{
  const peer=await rawPeer(t);
  const discovery=wireResult(await peer.request('server/discover',{},{}));
  assert.deepEqual((discovery.capabilities as {experimental:Record<string,unknown>}).experimental[FINDING_WIRE_CAPABILITY],{profile:FINDING_WIRE_PROFILE});
  const operations:[string,Record<string,unknown>][]=[
    ['review.submit',{submissionId:'prepared'}],['review.status',{reviewId:'r1'}],
    ['review.policy.reassess',{reviewId:'r1',idempotencyKey:'policy-1',generation:1,expectedPolicyDigest:'sha256:'+'c'.repeat(64)}],
    ['review.policy.status',{idempotencyKey:'policy-1'}],['review.events',{}],['review.cancel',{reviewId:'r1'}],
  ];
  for(const [name,args] of operations)compatibilityError(await peer.request('tools/call',{name,arguments:args},{}));
  const accepted=wireResult(await peer.request('tools/call',{name:'review.status',arguments:{reviewId:'r1'}}));
  assert.equal((accepted.structuredContent as {dispatchCount:number}).dispatchCount,1);
});
test('missing, malformed, extra-field and different declarations never imply admission',{timeout:15000},async t=>{
  const peer=await rawPeer(t);
  for(const capabilities of [
    {experimental:{}},
    {experimental:{[FINDING_WIRE_CAPABILITY]:{}}},
    {experimental:{[FINDING_WIRE_CAPABILITY]:{profile:1}}},
    {experimental:{[FINDING_WIRE_CAPABILITY]:{profile:'ktchecker/finding-retrieval-attachments-2'}}},
    {experimental:{[FINDING_WIRE_CAPABILITY]:{profile:FINDING_WIRE_PROFILE,allowLegacy:true}}},
    {experimental:{'ktchecker/other-wire':{profile:FINDING_WIRE_PROFILE}}},
  ])compatibilityError(await peer.request('tools/call',{name:'review.status',arguments:{reviewId:'r1'}},capabilities));
  for(const capabilities of [null,{experimental:{[FINDING_WIRE_CAPABILITY]:[]}}]){
    const reply=await peer.request('tools/call',{name:'review.status',arguments:{reviewId:'r1'}},capabilities);
    if(reply.error===undefined)compatibilityError(reply); // The SDK may reject invalid envelope data earlier.
    else assert.equal(Object.hasOwn(reply,'result'),false);
  }
  const wrongProtocol=await peer.request('tools/call',{name:'review.status',arguments:{reviewId:'r1'}},compatibleCapabilities,'2026-07-29');
  assert(wrongProtocol.error!==undefined||wireResult(wrongProtocol).isError===true);
  const spoof=await peer.request('tools/call',{name:'review.status',arguments:{reviewId:'r1',findingWireProfile:FINDING_WIRE_PROFILE}},{});
  assert(spoof.error!==undefined||wireResult(spoof).isError===true);
  const accepted=wireResult(await peer.request('tools/call',{name:'review.status',arguments:{reviewId:'r1'}}));
  assert.equal((accepted.structuredContent as {dispatchCount:number}).dispatchCount,1);
});
test('request and reconnect boundaries never inherit a previous successful capability grant',{timeout:15000},async t=>{
  const first=await rawPeer(t),params={name:'review.status',arguments:{reviewId:'r1'}};
  assert.notEqual(wireResult(await first.request('tools/call',params)).isError,true);
  compatibilityError(await first.request('tools/call',params,{}));
  compatibilityError(await first.request('tools/call',params,{experimental:{[FINDING_WIRE_CAPABILITY]:{profile:'other'}}}));
  const next=wireResult(await first.request('tools/call',params));
  assert.equal((next.structuredContent as {dispatchCount:number}).dispatchCount,2);
  await first.close();
  const reconnected=await rawPeer(t);
  compatibilityError(await reconnected.request('tools/call',params,{}));
  const accepted=wireResult(await reconnected.request('tools/call',params));
  assert.equal((accepted.structuredContent as {dispatchCount:number}).dispatchCount,1);
});
