#!/usr/bin/env node
import { probeCodex } from './codex-app-server.js';
import { HostError } from './journal.js';
try{
  const result=await probeCodex();
  process.stdout.write(JSON.stringify(result)+'\n');
  process.exitCode=0; // Only the read-only protocol/account inquiry was performed.
}catch(error){
  process.stdout.write(JSON.stringify({protocolConnected:false,automaticDelivery:'unverified',modelObservation:'unavailable',reason:error instanceof HostError?error.code:'probe_failed'})+'\n');
  process.exitCode=7;
}
