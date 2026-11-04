#!/usr/bin/env node
/** Sole evidence/final-stdout writer for the explicit, finite owner opt-in run.
 * All CLI/Git/Codex/store work lives in an independently terminable child.
 * This responsive controller does not attest native or remote-model quiescence.
 */
import { fork } from 'node:child_process';
import { write } from 'node:fs';
import { mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { ACTUAL_DEMO_USAGE, actualDemoInitialReport, parseActualDemoArgs, reserveActualDemoEvidence } from './actual-cli-fixture.js';
import { directActualDemoWorker, superviseActualDemo } from './actual-demo-controls.js';

// fs.write's asynchronous callback API is intentional. process.stdout/stderr.write
// may perform blocking writes on a terminal/file and defeat an event-loop timer.
async function output(fd: number, text: string): Promise<void> {
  const bytes = Buffer.from(text);
  for (let offset = 0; offset < bytes.length;) {
    const written = await new Promise<number>((resolve, reject) => {
      write(fd, bytes, offset, bytes.length - offset, null, (error, length) => error ? reject(error) : resolve(length));
    });
    if (!written) throw new Error('actual_demo_output_unavailable');
    offset += written;
  }
}
let selection: ReturnType<typeof parseActualDemoArgs>;
try { selection = parseActualDemoArgs(process.argv.slice(2)); }
catch {
  // No reservation/worker exists for invalid argv. Even this best-effort usage
  // write must not leave the controller waiting indefinitely on its terminal.
  await Promise.race([output(2, 'Explicit selections and approval required: ' + ACTUAL_DEMO_USAGE + '\n').catch(() => {}),
    new Promise(resolve => setTimeout(resolve, 250))]);
  process.exit(2);
}
const outcome = await superviseActualDemo({
  maxRuntimeMs: selection.maxRuntimeMs, initialReport: actualDemoInitialReport(),
  reserve: () => reserveActualDemoEvidence(selection.evidence),
  async start(signal) {
    // Timer is already armed and the NEW evidence handle is exclusively reserved.
    const directory = await mkdtemp(join(tmpdir(), 'ktchecker-public-actual-delivery-'));
    await output(2, 'Run tree retained for owner-only manual reconciliation/cleanup; native quiescence is not observed: ' + directory + '\n');
    if (signal.aborted) throw new Error('actual_demo_start_cancelled');
    const child = fork(new URL('./demo-cli-codex-run.js', import.meta.url), [], {
      stdio: ['ignore', 'ignore', 'ignore', 'ipc'], execArgv: [], serialization: 'json',
    });
    const owned = directActualDemoWorker(child);
    return { ...owned, begin() {
      if (signal.aborted) { owned.terminate('SIGKILL'); return; }
      child.send({ kind: 'start', launcher: selection.launcher, compilerJavaHome: selection.compilerJavaHome, directory }, () => {});
    } };
  },
  publish: summary => output(1, JSON.stringify(summary) + '\n'),
});
// Do not wait for dangling I/O, child finally blocks or inherited handles after
// the cap. A timed-out final write is not success even if a file contains true.
process.exit(outcome.exitCode);
