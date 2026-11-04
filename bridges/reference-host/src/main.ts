#!/usr/bin/env node
import { RestartingJsonLineRpc } from '@ktchecker/mcp-preview/rpc';
import { readHostConfig } from './config.js';
import { KernelBroker } from './broker.js';
import { CodexHostPort } from './codex-host.js';
import { ReferenceHost } from './adapter.js';
import { HostError, InboxJournal } from './journal.js';

async function main(): Promise<void> {
  const args = process.argv.slice(2);
  if ((args.length !== 2 && args.length !== 4) || args[0] !== '--config' || !args[1] ||
      (args.length === 4 && (args[2] !== '--max-observations' || !/^(0|[1-9][0-9]?)$/.test(args[3]!)))) {
    throw new HostError('usage_reference_host_config_private_json_optional_max_observations_0_to_99');
  }
  const maximum = args[3] ? Number(args[3]) : 0;
  const config = await readHostConfig(args[1]);
  const rpc = new RestartingJsonLineRpc({ command: config.broker.command, args: config.broker.args,
    ...(config.broker.cwd ? { cwd: config.broker.cwd } : {}) });
  const broker = new KernelBroker(rpc, config.registration);
  let host: CodexHostPort | undefined, journal: InboxJournal | undefined, running: Promise<void> | undefined;
  const stop = new AbortController();
  const interrupt = () => stop.abort(); process.once('SIGINT', interrupt); process.once('SIGTERM', interrupt);
  try {
    host = await CodexHostPort.open({ directory: config.codex.sessionDirectory, client: config.registration.binding, cwd: config.codex.cwd });
    journal = new InboxJournal(config.inboxDirectory, host.binding);
    const adapter = new ReferenceHost(journal, broker, host);
    // Each immutable completed patch is durably recorded BEFORE its submit RPC, including P2.
    for (const submission of config.submissions) await adapter.recordCompletedPatch(submission);
    let pollerProblem:unknown;
    running = adapter.run(stop.signal).catch(error=>{pollerProblem=error;stop.abort();});
    // This loop is OUTSIDE the model. It never prompts a model to poll and never grants gate actions.
    let attempts = 0, previousAttempt = '';
    while (!stop.signal.aborted) {
      const state = journal.read();
      const pending = Object.values(state.inbox).filter(entry => entry.event.deliveryRequired && !entry.observed);
      const key = pending.map(entry => entry.event.eventId).sort().join('\n');
      if (attempts < maximum && key !== previousAttempt && pending.some(entry => ['result', 'cancelled', 'terminal'].includes(entry.event.kind))) {
        previousAttempt = key; attempts++;
        try {
          const result = await adapter.requestModelObservation();
          process.stdout.write(JSON.stringify({ kind: 'observation', observed: result.observed, reason: result.reason, remainingInvocationBudget: maximum - attempts }) + '\n');
        } catch (error) {
          process.stdout.write(JSON.stringify({ kind: 'observation', observed: false,
            reason: error instanceof HostError ? error.code : 'host_or_broker_unavailable', remainingInvocationBudget: maximum - attempts }) + '\n');
        }
      }
      await new Promise(resolve => setTimeout(resolve, 100));
    }
    if(pollerProblem)throw pollerProblem;
  } finally {
    stop.abort(); await running; await host?.close(); journal?.close(); await rpc.close();
    process.removeListener('SIGINT', interrupt); process.removeListener('SIGTERM', interrupt);
  }
}
main().catch(error => {
  process.stderr.write(JSON.stringify({ kind: 'reference_host_failed', reason: error instanceof HostError ? error.code : 'invalid_configuration_or_unavailable_runtime' }) + '\n');
  process.exitCode = 2;
});
