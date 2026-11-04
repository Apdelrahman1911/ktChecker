import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { PREVIEW_CONTRACT } from '@ktchecker/mcp-preview/rpc';
import { InboxJournal } from '../src/journal.js';
import { binding, event, registration } from './fixtures.js';
const page=(events:ReturnType<typeof event>[],cursor='cursor-1')=>({contractVersion:PREVIEW_CONTRACT,events,cursor,hasMore:false});
async function directory(t:{after(fn:()=>Promise<void>):void}){const d=await mkdtemp(join(tmpdir(),'ktchecker-inbox-'));t.after(()=>rm(d,{recursive:true,force:true}));return d;}

test('durable inbox/cursor survive close/reopen; duplicate replay is idempotent',async t=>{
  const dir=await directory(t),r=registration(),b=binding(r);let journal=new InboxJournal(dir,b);
  journal.commitPage(page([event(r)]));journal.close();journal=new InboxJournal(dir,b);t.after(()=>journal.close());
  assert.equal(journal.read().fetchCursor,'cursor-1');journal.commitPage(page([event(r)]));
  assert.equal(Object.keys(journal.read().inbox).length,1);assert.equal(journal.read().inbox['event-1']?.acknowledged,false);
});
test('digest mismatch/partial page/capacity failure cannot advance cursor',async t=>{
  const dir=await directory(t),r=registration(),journal=new InboxJournal(dir,binding(r),{maxEvents:1});t.after(()=>journal.close());
  const bad={...event(r,'bad'),payload:'{}'};
  assert.throws(()=>journal.commitPage(page([event(r),bad])),/invalid_event/);assert.equal(journal.read().fetchCursor,null);assert.equal(Object.keys(journal.read().inbox).length,0);
  assert.throws(()=>journal.commitPage(page([event(r),event(r,'second')])),/inbox_capacity/);assert.equal(journal.read().fetchCursor,null);
});
test('changed payload for same stable event ID is rejected without losing original',async t=>{
  const dir=await directory(t),r=registration(),journal=new InboxJournal(dir,binding(r));t.after(()=>journal.close());
  journal.commitPage(page([event(r)]));assert.throws(()=>journal.commitPage(page([{...event(r),kind:'changed'}],'cursor-2')),/event_revision_conflict/);
  assert.equal(journal.read().fetchCursor,'cursor-1');
});
test('client/generation/host-thread mismatch never inherits another inbox',async t=>{
  const dir=await directory(t),r=registration(),journal=new InboxJournal(dir,binding(r));
  assert.throws(()=>journal.commitPage(page([event(registration('client-b'))])),/cross_consumer_event/);
  assert.throws(()=>new InboxJournal(dir,binding(r)),/consumer_controller_busy/);
  journal.close();assert.throws(()=>new InboxJournal(dir,binding(registration('client-a',2))),/session_generation_or_binding_mismatch/);
  assert.throws(()=>new InboxJournal(dir,{...binding(r),hostThreadId:'another-thread'}),/session_generation_or_binding_mismatch/);
});
test('expired controller lease is reclaimable; displaced controller loses read/action authority',async t=>{
  const dir=await directory(t),r=registration();let now=1000;
  const first=new InboxJournal(dir,binding(r),{leaseMillis:300,now:()=>now});t.after(()=>first.close());
  now=2000;const second=new InboxJournal(dir,binding(r),{leaseMillis:300,now:()=>now});t.after(()=>second.close());
  assert.throws(()=>first.read(),/controller_lease_lost/);assert.throws(()=>first.edit(()=>{}),/controller_lease_lost/);
});
