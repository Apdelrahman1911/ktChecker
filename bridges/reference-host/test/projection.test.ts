import { test } from 'node:test';
import assert from 'node:assert/strict';
import { chmod, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { projectDiagnostic } from '../src/context-projection.js';
import { canonical, digest, InboxJournal } from '../src/journal.js';
import { KernelBroker } from '../src/broker.js';
import { ReferenceHost } from '../src/adapter.js';
import { readHostConfig, hostConfigSchema } from '../src/config.js';
import { binding, event, FixtureHost, FixtureKernel, registration } from './fixtures.js';

test('1663-row coverage projection is bounded, explicit about missing coverage, and retains every finding obligation', async t=>{
  const r=registration();const e=event(r);
  const payload=JSON.parse(e.payload);
  payload.result.coverage=Array.from({length:1663},(_,index)=>({ruleId:'rule.'+index,required:true,state:index<2?'findings':'unavailable',
    context:{module:':fixture',sourceSet:'main',target:'jvm',compilation:'main'},
    reason:index<2?'diagnostic_found':'no_compatible_installed_rule_implementation',
    certificateId:index<2?'executed-certificate':null,provenance:index<2?'executed':'not_executed'}));
  payload.reasons=['required_rule_coverage_unavailable'];
  e.payload=canonical(payload);e.payloadDigest=digest(e.payload);
  const projected=projectDiagnostic(e);const compact=JSON.parse(projected.diagnosticPayload);
  assert.equal(compact.result.coverageSummary.totalRows,1663);
  assert.equal(compact.result.coverageSummary.requiredUnsuccessfulRows,1661);
  assert.equal(compact.result.coverageSummary.hasUnsuccessfulRequiredCoverage,true);
  assert.equal(compact.result.coverageSummary.countsByState['state:unavailable'],1661);
  assert.deepEqual(compact.result.findings,payload.result.findings);
  assert.deepEqual(compact.reasons,payload.reasons);
  assert.equal(projected.findingObligations.length,1);
  assert.equal(projected.findingObligations[0]!.findingDigest,digest(canonical(payload.result.findings[0])));
  assert.equal(projected.originalPayloadRef.payloadDigest,e.payloadDigest);
  assert.notEqual(projected.diagnosticPayloadDigest,e.payloadDigest);
  assert(Buffer.byteLength(projected.diagnosticPayload)<5000);
  const dir=await mkdtemp(join(tmpdir(),'ktchecker-projection-'));const journal=new InboxJournal(dir,binding(r));
  t.after(async()=>{journal.close();await rm(dir,{recursive:true,force:true});});
  const kernel=new FixtureKernel(r),host=new FixtureHost(binding(r));kernel.eventsByClient.set(r.binding.clientId,[e]);
  const adapter=new ReferenceHost(journal,new KernelBroker(kernel,r),host);
  await adapter.pollOnce();await adapter.drainContext();
  assert.equal(journal.readPayload(e.eventId,e.payloadDigest),e.payload);
  assert.throws(()=>journal.readPayload(e.eventId,'wrong'),/payload_artifact_mismatch/);
  const context=journal.read().inbox[e.eventId]!.item!;
  assert(context.text.includes('projected_context_only'));assert(Buffer.byteLength(context.text)<10000);
  assert.equal(journal.read().inbox[e.eventId]!.observed,false);
});

test('over-budget mandatory finding content is not silently truncated or ACKed',async t=>{
  const r=registration(),e=event(r);const payload=JSON.parse(e.payload);payload.result.findings[0].diagnosis='x'.repeat(40_000);
  e.payload=canonical(payload);e.payloadDigest=digest(e.payload);
  const dir=await mkdtemp(join(tmpdir(),'ktchecker-projection-large-'));const journal=new InboxJournal(dir,binding(r));
  t.after(async()=>{journal.close();await rm(dir,{recursive:true,force:true});});
  const kernel=new FixtureKernel(r),host=new FixtureHost(binding(r));kernel.eventsByClient.set(r.binding.clientId,[e]);
  const adapter=new ReferenceHost(journal,new KernelBroker(kernel,r),host);await adapter.pollOnce();
  await assert.rejects(adapter.drainContext(),/required_context_exceeds_budget/);
  assert.equal(kernel.acked.size,0);assert.equal(host.insertionCalls,0);
  assert.equal(journal.readPayload(e.eventId,e.payloadDigest),e.payload);
});

test('blank certificates cannot summarize as successful, and structural coverage never hides unqualified required rows',()=>{
  const r=registration(),e=event(r),payload=JSON.parse(e.payload);
  payload.result.coverage=[
    {ruleId:'blank',state:'passed',required:true,certificateId:' \t ',provenance:'executed',qualified:true,tested:true,implemented:true},
    {ruleId:'unqualified',state:'passed',required:true,certificateId:'present',provenance:'executed',qualified:false,tested:false,implemented:false},
  ];
  e.payload=canonical(payload);e.payloadDigest=digest(e.payload);
  const summary=JSON.parse(projectDiagnostic(e).diagnosticPayload).result.coverageSummary;
  assert.equal(summary.requiredRows,2);assert.equal(summary.requiredUnsuccessfulRows,1);
  assert.equal(summary.hasUnsuccessfulRequiredCoverage,true);
  assert.equal(summary.requiredUnqualifiedRows,1);assert.equal(summary.requiredUntestedRows,1);
  assert.equal(summary.requiredUnimplementedRows,1);assert.equal(summary.hasUnqualifiedRequiredCoverage,true);
});

test('owner configuration forbids model settings and insecure files; generation is a safe integer',async t=>{
  const r=registration();const config={broker:{command:'ktchecker',args:['rpc','--state','/owner/private']},registration:r,
    inboxDirectory:'/owner/inbox',codex:{sessionDirectory:'/owner/session',cwd:'/owner/synthetic-fixture'},submissions:[]};
  assert.equal(hostConfigSchema.parse(config).registration.binding.generation,1);
  assert.throws(()=>hostConfigSchema.parse({...config,codex:{...config.codex,model:'another-model'}}));
  assert.throws(()=>hostConfigSchema.parse({...config,registration:{...r,binding:{...r.binding,generation:2**54}}}));
  const dir=await mkdtemp(join(tmpdir(),'ktchecker-private-config-'));t.after(()=>rm(dir,{recursive:true,force:true}));
  const path=join(dir,'config.json');await writeFile(path,JSON.stringify(config),{mode:0o644});
  await chmod(path,0o644);
  await assert.rejects(readHostConfig(path),process.platform==='win32'?/private_state_platform_unqualified/:/host_configuration_permissions/);
});
