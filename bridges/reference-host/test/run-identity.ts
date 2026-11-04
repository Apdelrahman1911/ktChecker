/** Byte identities for an owner-approved demonstration. No private state/configuration
 * contents or credential-bearing argv are inspected. Installed Java bytes are identified
 * independently: a current Git commit is NOT assumed to be their build provenance.
 */
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { lstat, readdir } from 'node:fs/promises';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { canonical, digest, HostError } from '../src/journal.js';

async function identity(path: string, label: string): Promise<{ path: string; bytes: number; digest: string }> {
  const stat = await lstat(path);
  if (!stat.isFile() || stat.isSymbolicLink()) throw new HostError('demonstration_artifact_identity_invalid');
  const hash = createHash('sha256'); let bytes = 0;
  for await (const chunk of createReadStream(path)) { hash.update(chunk as Buffer); bytes += (chunk as Buffer).length; }
  return { path: label.split('\\').join('/'), bytes, digest: 'sha256:' + hash.digest('hex') };
}
async function files(root: string, path: string): Promise<Awaited<ReturnType<typeof identity>>[]> {
  const result: Awaited<ReturnType<typeof identity>>[] = [];
  for (const entry of (await readdir(path, { withFileTypes: true })).sort((a, b) => a.name.localeCompare(b.name))) {
    const child = join(path, entry.name);
    if (entry.isSymbolicLink()) throw new HostError('demonstration_artifact_symlink');
    if (entry.isDirectory()) result.push(...await files(root, child));
    else if (entry.isFile()) result.push(await identity(child, relative(root, child)));
    else throw new HostError('demonstration_artifact_type');
    if (result.length > 4096) throw new HostError('demonstration_artifact_inventory_bound');
  }
  return result;
}
export async function captureRunIdentity(launcher: string): Promise<Record<string, unknown>> {
  const repository = fileURLToPath(new URL('../../../../', import.meta.url));
  let commit: string | null = null, dirty: boolean | null = null;
  try {
    commit = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: repository, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim();
    dirty = execFileSync('git', ['status', '--porcelain', '--untracked-files=all'], { cwd: repository, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim().length > 0;
  } catch { /* Explicitly unavailable instead of inventing an installed artifact's revision. */ }
  const distribution = dirname(dirname(launcher));
  const javaArtifacts = [...await files(distribution, join(distribution, 'lib')),
    ...await files(distribution, join(distribution, 'providers', 'compiler'))];
  const bridgeSources = [...await files(repository, join(repository, 'bridges/mcp/src')),
    ...await files(repository, join(repository, 'bridges/reference-host/src'))];
  const executedBridgeArtifacts = [...await files(repository, join(repository, 'bridges/mcp/dist/src')),
    ...await files(repository, join(repository, 'bridges/reference-host/dist/src'))];
  for (const name of ['demo-cli-codex', 'actual-cli-fixture', 'application-evidence', 'run-identity', 'delivery-telemetry', 'owned-demo-threads']) {
    for (const [folder, extension, destination] of [['test', 'ts', bridgeSources], ['dist/test', 'js', executedBridgeArtifacts]] as const) {
      const path = join(repository, 'bridges/reference-host', folder, name + '.' + extension);
      destination.push(await identity(path, relative(repository, path)));
    }
  }
  return { currentCheckout: { commit, dirty }, bridgeSources, bridgeSourceDigest: digest(canonical(bridgeSources)),
    executedBridgeArtifacts, executedBridgeArtifactDigest: digest(canonical(executedBridgeArtifacts)),
    installedLauncher: await identity(launcher, 'bin/' + launcher.split(/[\\/]/).at(-1)),
    installedJavaArtifacts: javaArtifacts, installedJavaArtifactDigest: digest(canonical(javaArtifacts)),
    installedJavaSourceCommit: null,
    installedJavaSourceCommitReason: 'not_authenticated_by_this_driver_no_equivalence_to_current_checkout_assumed',
    sourceScope: 'bridge_production_sources_and_demo_drivers_not_an_entire_repository_snapshot' };
}
