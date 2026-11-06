import { test } from 'node:test';
import assert from 'node:assert/strict';
import { FINDING_WIRE_PROFILE, JsonLineRpc, RestartingJsonLineRpc, PREVIEW_CONTRACT } from '../src/rpc.js';

const attachment={kind:'upstream_sarif',digest:'sha256:'+'c'.repeat(64),artifactRef:'  /outputs/attempt-1/provider-a/detekt.sarif  '};
const child = `
import readline from 'node:readline';
const lines = readline.createInterface({input:process.stdin});
let dispatchCount=0;
lines.on('line', line => {
 const r=JSON.parse(line);
 if(r.findingWireProfile!=='${FINDING_WIRE_PROFILE}') return process.stdout.write(JSON.stringify({id:r.id,contractVersion:'${PREVIEW_CONTRACT}',error:{code:'incompatible_finding_wire_profile',message:'Profile required before dispatch'}})+'\\n');
 dispatchCount++;
 if(r.method==='disconnect') return process.exit(0);
 if(r.method==='malformed') return process.stdout.write('not-json\\n');
 if(r.method==='oversize') return process.stdout.write('x'.repeat(4096));
 if(r.method==='invalidUtf8') {
  const bytes=Buffer.from(JSON.stringify({id:r.id,contractVersion:'${PREVIEW_CONTRACT}',findingWireProfile:'${FINDING_WIRE_PROFILE}',result:{value:'X'}})+'\\n');
  bytes[bytes.indexOf(Buffer.from('"X"'))+1]=0xff;return process.stdout.write(bytes);
 }
 if(r.method==='largePage') return process.stdout.write(JSON.stringify({id:r.id,contractVersion:'${PREVIEW_CONTRACT}',findingWireProfile:'${FINDING_WIRE_PROFILE}',result:{payload:'x'.repeat(2*1024*1024)}})+'\\n');
 const contractVersion=r.method==='wrongVersion'?'ktchecker/1':'${PREVIEW_CONTRACT}';
 const findingWireProfile=r.method==='wrongProfile'?'ktchecker/finding-retrieval-attachments-2':r.method==='malformedProfile'?[]:'${FINDING_WIRE_PROFILE}';
 const body=r.method==='domainError'?{error:{code:'permission_denied',message:'Denied'}}:{result:{method:r.method,params:r.params,requestProfile:r.findingWireProfile,requestEnvelopeKeys:Object.keys(r).sort(),dispatchCount,...(r.method==='attachments'?{findings:[{retrievalAttachments:[${JSON.stringify(attachment)}]}]}:{})}};
 const profile=r.method==='missingProfile'||r.method==='domainError'?{}:{findingWireProfile};
 const respond=()=>process.stdout.write(JSON.stringify({id:r.id,contractVersion,...profile,...body})+'\\n');
 if(r.method==='delay') setTimeout(respond,150); else respond();
});`;
function transport(options: Partial<ConstructorParameters<typeof JsonLineRpc>[0]> = {}) {
  return new JsonLineRpc({command:process.execPath,args:['--input-type=module','-e',child],timeoutMs:1000,...options});
}

test('application NDJSON preserves IDs under concurrent replies; not MCP', async t => {
  const rpc=transport(); t.after(()=>rpc.close());
  const responses=await Promise.all(['one','two'].map(value=>rpc.request<{params:{value:string}}>('echo',{value})));
  assert.deepEqual(responses.map(x=>x.params.value),['one','two']);
});
test('disconnect fails outstanding operations and does not silently resubmit',async t=>{
  const rpc=transport();t.after(()=>rpc.close());
  await assert.rejects(rpc.request('disconnect',{}),{code:'broker_disconnected'});
  await assert.rejects(rpc.request('echo',{}),{code:'broker_disconnected'});
});
test('restart wrapper reconnects only on a subsequent explicit operation, never retries failed mutation',async t=>{
  const rpc=new RestartingJsonLineRpc({command:process.execPath,args:['--input-type=module','-e',child],timeoutMs:1000});
  t.after(()=>rpc.close());
  await assert.rejects(rpc.request('disconnect',{}),{code:'broker_disconnected'});
  const results=await Promise.all([rpc.request<{method:string}>('one',{}),rpc.request<{method:string}>('two',{})]);
  assert.deepEqual(results.map(x=>x.method),['one','two']);
  await rpc.close();await assert.rejects(rpc.request('echo',{}),{code:'transport_closed'});
});
test('timeout is unknown outcome, not successful acceptance or cancellation',async t=>{
  const rpc=transport({timeoutMs:50});t.after(()=>rpc.close());
  await assert.rejects(rpc.request('delay',{}),{code:'request_timeout'});
  await new Promise(resolve=>setTimeout(resolve,180));
  assert.equal((await rpc.request<{method:string}>('echo',{})).method,'echo');
});
test('plain stdout banners and wrong contract are rejected', async t=>{
  for(const [method,code] of [['malformed','invalid_broker_response'],['wrongVersion','incompatible_broker_contract']]){
    const rpc=transport();t.after(()=>rpc.close());
    await assert.rejects(rpc.request(method!,{}),{code});
  }
});
test('unterminated oversized response fails closed',async t=>{
  const rpc=transport({maxLineBytes:1024});t.after(()=>rpc.close());
  await assert.rejects(rpc.request('oversize',{}),{code:'invalid_broker_response'});
});
test('malformed UTF-8 bytes cannot be repaired into an accepted private response',async t=>{
  const rpc=transport();t.after(()=>rpc.close());
  await assert.rejects(rpc.request('invalidUtf8',{}),{code:'invalid_broker_response'});
});
test('private transport accepts a multi-megabyte event page independently of model context budget',async t=>{
  const rpc=transport();t.after(()=>rpc.close());
  assert.equal((await rpc.request<{payload:string}>('largePage',{})).payload.length,2*1024*1024);
});
test('errors remain errors; request/queue bounds are enforced',async t=>{
  const rpc=transport({maxPending:1,maxLineBytes:1024});t.after(()=>rpc.close());
  await assert.rejects(rpc.request('domainError',{}),{code:'permission_denied'});
  await assert.rejects(rpc.request('echo',{large:'x'.repeat(1024)}),{code:'request_too_large'});
  const pending=rpc.request('delay',{});
  await assert.rejects(rpc.request('echo',{}),{code:'backpressure'});
  await pending;
});

// SOURCE controls only: these synthetic subprocesses are not a Kernel/profile qualification.
test('Finding profile is transport-owned outer metadata; nonempty locators are not rewritten',async t=>{
  const rpc=transport();t.after(()=>rpc.close());
  const params={value:'unaltered',findingWireProfile:'not-an-envelope-grant'};
  const result=await rpc.request<{params:unknown;requestProfile:string;requestEnvelopeKeys:string[];findings:{retrievalAttachments:unknown[]}[]}>('attachments',params);
  assert.deepEqual(result.params,params);
  assert.equal(result.requestProfile,FINDING_WIRE_PROFILE);
  assert.deepEqual(result.requestEnvelopeKeys,['findingWireProfile','id','method','params']);
  assert.deepEqual(result.findings[0]!.retrievalAttachments,[attachment]);
  assert.equal(Object.hasOwn(result,'findingWireProfile'),false);
});
test('every success needs the exact echo, even after an admitted success on that connection',async t=>{
  for(const method of ['missingProfile','wrongProfile','malformedProfile']){
    const rpc=transport();t.after(()=>rpc.close());
    await rpc.request('echo',{});
    await assert.rejects(rpc.request(method,{}),{code:'incompatible_finding_wire_profile'});
    assert.equal(rpc.disconnected,true);
    await assert.rejects(rpc.request('echo',{}),{code:'incompatible_finding_wire_profile'});
  }
});
test('reconnect inherits no Finding-profile grant and never converts a rejected operation to success',async t=>{
  const rpc=new RestartingJsonLineRpc({command:process.execPath,args:['--input-type=module','-e',child],timeoutMs:1000});
  t.after(()=>rpc.close());
  await rpc.request('echo',{});
  await assert.rejects(rpc.request('disconnect',{}),{code:'broker_disconnected'});
  await assert.rejects(rpc.request('wrongProfile',{}),{code:'incompatible_finding_wire_profile'});
  // A separate explicit operation may reconnect; the failed operation is not repeated.
  const next=await rpc.request<{method:string;dispatchCount:number;requestProfile:string}>('echo',{});
  assert.equal(next.method,'echo');assert.equal(next.dispatchCount,1);assert.equal(next.requestProfile,FINDING_WIRE_PROFILE);
});
test('an old strict producer rejects the new request key; the client never downgrades',async t=>{
  const legacy=`import readline from 'node:readline';readline.createInterface({input:process.stdin}).on('line',line=>{
    const r=JSON.parse(line);const unknown=Object.keys(r).some(key=>!['id','method','params'].includes(key));
    process.stdout.write(JSON.stringify({id:r.id,contractVersion:'${PREVIEW_CONTRACT}',...(unknown?{error:{code:'invalid_request',message:'Unknown envelope key; handler not called'}}:{result:{dispatched:true}})})+'\\n');
  });`;
  const rpc=transport({args:['--input-type=module','-e',legacy]});t.after(()=>rpc.close());
  await assert.rejects(rpc.request('review.submit',{}),{code:'invalid_request'});
});
