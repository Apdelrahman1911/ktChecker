import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Client } from '@modelcontextprotocol/client';
import { StdioClientTransport } from '@modelcontextprotocol/client/stdio';
import { bridgeConfigSchema, MCP_PROFILE } from '../src/server.js';
import { PREVIEW_CONTRACT } from '../src/rpc.js';

const brokerFixture=`import readline from 'node:readline';readline.createInterface({input:process.stdin}).on('line',line=>{const r=JSON.parse(line);const {credential,...params}=r.params;process.stdout.write(JSON.stringify({id:r.id,contractVersion:'${PREVIEW_CONTRACT}',result:{contractVersion:'${PREVIEW_CONTRACT}',method:r.method,clientId:credential.clientId,params,state:'pending',outcome:'unknown',...(params.reviewId==='large'?{large:'x'.repeat(40000)}:{})}})+'\\n')});`;
async function setup(t: {after(fn:()=>Promise<unknown>):void}, mode: 'legacy'|{pin:string}={pin:MCP_PROFILE}) {
  const dir=await mkdtemp(join(tmpdir(),'ktchecker-mcp-test-'));
  const path=join(dir,'config.json');
  await writeFile(path,JSON.stringify({broker:{command:process.execPath,args:['--input-type=module','-e',brokerFixture]},credential:{clientId:'client-a',token:'fixture-private-client-capability'},submissions:{prepared:{patchId:'p1',idempotencyKey:'key-p1',input:{snapshot:{snapshotId:'fixture-only'},materializedRoot:'/owner/prepared/fixture'}}}}),{mode:0o600});
  const transport=new StdioClientTransport({command:process.execPath,args:[fileURLToPath(new URL('../src/main.js',import.meta.url)),'--config',path],stderr:'pipe'});
  const client=new Client({name:'ktchecker-sdk-protocol-test',version:'0.1.0'},{versionNegotiation:{mode}});
  t.after(async()=>{await client.close();await rm(dir,{recursive:true,force:true});});
  await client.connect(transport);
  return client;
}

test('official SDK current-profile discovery/tools and structured preview result', {timeout:10000}, async t=>{
  const client=await setup(t);
  const tools=await client.listTools();
  assert.deepEqual(tools.tools.map(x=>x.name).sort(),['review.cancel','review.events','review.status','review.submit']);
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
