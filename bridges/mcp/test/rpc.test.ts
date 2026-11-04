import { test } from 'node:test';
import assert from 'node:assert/strict';
import { JsonLineRpc, RestartingJsonLineRpc, PREVIEW_CONTRACT } from '../src/rpc.js';

const child = `
import readline from 'node:readline';
const lines = readline.createInterface({input:process.stdin});
lines.on('line', line => {
 const r=JSON.parse(line);
 if(r.method==='disconnect') return process.exit(0);
 if(r.method==='malformed') return process.stdout.write('not-json\\n');
 if(r.method==='oversize') return process.stdout.write('x'.repeat(4096));
 if(r.method==='invalidUtf8') {
  const bytes=Buffer.from(JSON.stringify({id:r.id,contractVersion:'${PREVIEW_CONTRACT}',result:{value:'X'}})+'\\n');
  bytes[bytes.indexOf(Buffer.from('"X"'))+1]=0xff;return process.stdout.write(bytes);
 }
 if(r.method==='largePage') return process.stdout.write(JSON.stringify({id:r.id,contractVersion:'${PREVIEW_CONTRACT}',result:{payload:'x'.repeat(2*1024*1024)}})+'\\n');
 const contractVersion=r.method==='wrongVersion'?'ktchecker/1':'${PREVIEW_CONTRACT}';
 const body=r.method==='domainError'?{error:{code:'permission_denied',message:'Denied'}}:{result:{method:r.method,params:r.params}};
 const respond=()=>process.stdout.write(JSON.stringify({id:r.id,contractVersion,...body})+'\\n');
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
