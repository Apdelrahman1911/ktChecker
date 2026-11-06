import { test } from 'node:test';
import assert from 'node:assert/strict';
import { chmod, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { prepareDiagnosticContext, projectDiagnostic } from '../src/context-projection.js';
import { canonical, digest, InboxJournal } from '../src/journal.js';
import { KernelBroker } from '../src/broker.js';
import { ReferenceHost } from '../src/adapter.js';
import { readHostConfig, hostConfigSchema } from '../src/config.js';
import { observationProjection } from '../src/codex-observation.js';
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

test('required plan_accepted retains the complete parent vector in insertion and public observation, without retroactive null fields',async t=>{
  const r=registration(),e=event(r);e.kind='plan_accepted';
  const parentPlan={contractVersion:'ktchecker/preview-1',planId:'parent-plan',clientId:r.binding.clientId,
    patchId:'parent-patch',sourceSnapshotId:e.sourceSnapshotId,planDigest:digest('plan'),state:'accepted',reasons:[],
    slots:[{slotId:'main-jvm',reviewId:e.reviewId,context:{target:'jvm',sourceSet:'main',module:':fixture',compilation:'main'},requiredRuleIds:['compiler.rule'],state:'queued',reasons:[]}]};
  e.payload=canonical({state:'plan_accepted',result:null,parentPlan});e.payloadDigest=digest(e.payload);
  const dir=await mkdtemp(join(tmpdir(),'ktchecker-parent-context-'));const journal=new InboxJournal(dir,binding(r));
  t.after(async()=>{journal.close();await rm(dir,{recursive:true,force:true});});
  const kernel=new FixtureKernel(r),host=new FixtureHost(binding(r));kernel.eventsByClient.set(r.binding.clientId,[e]);
  const adapter=new ReferenceHost(journal,new KernelBroker(kernel,r),host);await adapter.pollOnce();await adapter.drainContext();
  const saved=journal.read().inbox[e.eventId]!;assert.equal(saved.acknowledged,true);assert.equal(saved.observed,false);
  assert.deepEqual(observationProjection(saved.item!).parentPlan,parentPlan);
  const old=event(r,'old-without-parent');kernel.eventsByClient.get(r.binding.clientId)!.push(old);
  await adapter.pollOnce();await adapter.drainContext();
  assert.equal(Object.hasOwn(observationProjection(journal.read().inbox[old.eventId]!.item!),'parentPlan'),false);
});

test('a 512-slot parent vector exceeding the aggregate budget remains unACKed, never narrowed to fit',async t=>{
  const r=registration(),e=event(r);e.kind='plan_accepted';
  e.payload=canonical({state:'plan_accepted',result:null,parentPlan:{slots:Array.from({length:512},(_,i)=>({slotId:'slot-'+i,
    requiredRuleIds:['rule-'+i],context:{target:'jvm',sourceSet:'main',module:':module-'+i,compilation:'main'},reviewId:'review-'+i,state:'queued',reasons:[]}))}});
  e.payloadDigest=digest(e.payload);
  const dir=await mkdtemp(join(tmpdir(),'ktchecker-parent-large-'));const journal=new InboxJournal(dir,binding(r));
  t.after(async()=>{journal.close();await rm(dir,{recursive:true,force:true});});
  const kernel=new FixtureKernel(r),host=new FixtureHost(binding(r));kernel.eventsByClient.set(r.binding.clientId,[e]);
  const adapter=new ReferenceHost(journal,new KernelBroker(kernel,r),host);await adapter.pollOnce();
  await assert.rejects(adapter.drainContext(),/required_context_exceeds_budget/);
  assert.equal(kernel.acked.size,0);assert.equal(host.insertionCalls,0);
  assert.equal(journal.readPayload(e.eventId,e.payloadDigest),e.payload);
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


test('retrieval attachments stay exact in finding obligations projected context and original payload checksums',()=>{
  // Pure ordinary-event projection over synthetic fixtures; no insertion, ACK or model observation.
  const r=registration(),base=event(r),reportDigest=digest('complete upstream report bytes');
  const routed=(artifactRef?:string)=>{
    const payload=JSON.parse(base.payload),finding=payload.result.findings[0];
    finding.evidence=[{kind:'upstream_sarif',summary:'Complete upstream report',digest:reportDigest,artifactRef:null}];
    if(artifactRef!==undefined)finding.retrievalAttachments=[{kind:'upstream_sarif',digest:reportDigest,artifactRef}];
    const text=canonical(payload);
    return {...base,payload:text,payloadDigest:digest(text)};
  };
  const first=routed('  /outputs/attempt-1/provider-a/detekt.sarif  ');
  const second=routed('  /outputs/attempt-2/provider-b/detekt.sarif  ');
  const a=projectDiagnostic(first),b=projectDiagnostic(second);
  for(const [eventValue,projection] of [[first,a],[second,b]] as const){
    const original=JSON.parse(eventValue.payload),projected=JSON.parse(projection.diagnosticPayload);
    assert.deepEqual(projected.result.findings,original.result.findings);
    assert.deepEqual(projected.result.findings[0].retrievalAttachments,original.result.findings[0].retrievalAttachments);
    assert.equal(projection.findingObligations[0]!.findingDigest,digest(canonical(original.result.findings[0])));
    assert.equal(projection.originalPayloadRef.payloadDigest,eventValue.payloadDigest);
    assert.equal(projection.diagnosticPayloadDigest,digest(projection.diagnosticPayload));
  }
  assert.deepEqual(JSON.parse(first.payload).result.findings[0].evidence,JSON.parse(second.payload).result.findings[0].evidence);
  assert.notEqual(a.findingObligations[0]!.findingDigest,b.findingObligations[0]!.findingDigest);
  assert.notEqual(a.findingObligations[0]!.obligationId,b.findingObligations[0]!.obligationId);
  assert.notEqual(a.diagnosticPayloadDigest,b.diagnosticPayloadDigest);
  assert.notEqual(a.originalPayloadRef.payloadDigest,b.originalPayloadRef.payloadDigest);
  // Hold comparison/currentness inputs fixed so only locator-containing payload bytes vary.
  const ac=prepareDiagnosticContext(first,binding(r),base.sourceSnapshotId);
  const bc=prepareDiagnosticContext(second,binding(r),base.sourceSnapshotId);
  assert.notEqual(ac.text,bc.text);assert.notEqual(ac.digest,bc.digest);
  for(const [context,eventValue] of [[ac,first],[bc,second]] as const){
    const envelope=JSON.parse(context.text.slice(context.text.indexOf('\n')+1));
    assert.equal(envelope.comparedSnapshotId,base.sourceSnapshotId);
    assert.deepEqual(JSON.parse(envelope.diagnosticPayload).result.findings[0].retrievalAttachments,
      JSON.parse(eventValue.payload).result.findings[0].retrievalAttachments);
    assert.equal(context.digest,digest(context.text));
  }
  assert.throws(()=>projectDiagnostic({...second,payloadDigest:first.payloadDigest}),/event_digest_mismatch/);
  const absent=routed(),absentProjection=projectDiagnostic(absent);
  assert.equal(Object.hasOwn(JSON.parse(absentProjection.diagnosticPayload).result.findings[0],'retrievalAttachments'),false);
  assert.equal(absentProjection.findingObligations[0]!.findingDigest,digest(canonical(JSON.parse(absent.payload).result.findings[0])));
});
