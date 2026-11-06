import { closeSync, constants, fstatSync, openSync, readSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { resolve } from 'node:path';
import { release } from 'node:os';
import { fileURLToPath } from 'node:url';

/** A native experiment is not hosted/macOS qualification. Default remains closed. */
export class PrivateStatePlatformError extends Error {
  readonly code = 'private_state_platform_unqualified';
  constructor(readonly reason: string) { super('private_state_platform_unqualified'); this.name = 'PrivateStatePlatformError'; }
}
export interface DarwinStoreCapability { readonly databasePath: string }
export interface DarwinPrivateStateBackend {
  readonly abi: 1;
  readonly sourceSha256: string;
  readConfig(path: string): Buffer;
  verifyPath(path: string, directory: boolean): void;
  openStore(path: string, basename: 'inbox.sqlite' | 'codex.sqlite'): DarwinStoreCapability;
  verifyStore(capability: DarwinStoreCapability): void;
  /** Caller MUST retain the capability until DatabaseSync.close has succeeded. */
  closeStore(capability: DarwinStoreCapability): void;
}
interface NativeManifest {
  version: 1; status: 'experimental-unqualified'; platform: 'darwin'; arch: 'arm64' | 'x64';
  nodeVersion: '24.20.0'; nodeSha256: string; nodeReleaseSha256: string; nativeSha256: string; sourceSha256: string;
  sdkVersion: string; minimumMacosVersion: '26.0'; napiVersion: 8; nativeRuntimeAdmissionAtBuild: true;
}
const sha256 = /^[0-9a-f]{64}$/;
// Checked official Node HTTPS release metadata, not an independent signature
// verification. See native/node-release-pins.json and PRIMARY-SOURCES.md.
const releaseArchives: Record<string, string> = {
  arm64: '40e5607e5ecb3db9192723776da2d75d966260fc74a7a9e731c1bd67dda96bc8',
  x64: '9e5b2644cf107befb6aefca676b96d3296bc10138096f022ed378d6233ed81f4',
};
let backend: DarwinPrivateStateBackend | undefined;

/** Installation/code trust is a prerequisite, not supplied by a state-directory check.
 * This binds the explicit build receipt to the selected file bytes. It is NOT a
 * loaded-image attestation or permission to replace root's native qualification.
 */
function artifact(path: string, limit: number, retain: boolean): { digest: string; bytes: Buffer } {
  const fd = openSync(path, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
  try {
    const before = fstatSync(fd);
    if (!before.isFile() || before.nlink !== 1 || before.size < 0 || before.size > limit ||
        (before.mode & 0o022) !== 0 || (before.uid !== 0 && before.uid !== process.getuid?.()))
      throw new PrivateStatePlatformError('darwin_native_artifact_unqualified');
    const hash = createHash('sha256'); const chunks: Buffer[] = []; const chunk = Buffer.alloc(64 * 1024);
    let total = 0;
    for (;;) {
      const length = readSync(fd, chunk, 0, chunk.length, null);
      if (!length) break;
      total += length;
      if (total > limit) throw new PrivateStatePlatformError('darwin_native_artifact_unqualified');
      hash.update(chunk.subarray(0, length));
      if (retain) chunks.push(Buffer.from(chunk.subarray(0, length)));
    }
    const after = fstatSync(fd);
    if (before.dev !== after.dev || before.ino !== after.ino || before.size !== after.size || total !== before.size ||
        before.mtimeMs !== after.mtimeMs || before.ctimeMs !== after.ctimeMs)
      throw new PrivateStatePlatformError('darwin_native_artifact_unqualified');
    return { digest: hash.digest('hex'), bytes: retain ? Buffer.concat(chunks) : Buffer.alloc(0) };
  } finally { closeSync(fd); }
}
function loadDarwin(): DarwinPrivateStateBackend {
  if (process.env.KTC_DARWIN_PRIVATE_STATE_EXPERIMENTAL !== '1')
    throw new PrivateStatePlatformError('darwin_native_backend_opt_in_required');
  if (process.getuid?.() === 0 || process.getuid?.() !== process.geteuid?.() || process.getgid?.() !== process.getegid?.())
    throw new PrivateStatePlatformError('darwin_native_identity_unqualified');
  if (backend) return backend;
  if (process.platform !== 'darwin' || !['arm64', 'x64'].includes(process.arch) || process.versions.node !== '24.20.0' ||
      !/^25\./.test(release()) || Object.keys(process.env).some(key => /^(?:DYLD_|LD_PRELOAD$)/.test(key)))
    throw new PrivateStatePlatformError('darwin_native_runtime_unqualified');
  try {
    const directory = new URL(`../../native/darwin-${process.arch}/`, import.meta.url);
    const manifest = JSON.parse(artifact(fileURLToPath(new URL('manifest.json', directory)), 16384, true).bytes.toString('utf8')) as NativeManifest;
    if (manifest.version !== 1 || manifest.status !== 'experimental-unqualified' || manifest.platform !== 'darwin' ||
        manifest.arch !== process.arch || manifest.nodeVersion !== process.versions.node ||
        manifest.nodeReleaseSha256 !== releaseArchives[process.arch] || manifest.minimumMacosVersion !== '26.0' ||
        manifest.napiVersion !== 8 || manifest.nativeRuntimeAdmissionAtBuild !== true || !/^26\.\d+(?:\.\d+)?$/.test(manifest.sdkVersion) ||
        !sha256.test(manifest.nodeSha256) || !sha256.test(manifest.nativeSha256) || !sha256.test(manifest.sourceSha256))
      throw new PrivateStatePlatformError('darwin_native_artifact_unqualified');
    const target = fileURLToPath(new URL('private-state.node', directory));
    if (artifact(target, 8 * 1024 * 1024, false).digest !== manifest.nativeSha256 ||
        artifact(process.execPath, 256 * 1024 * 1024, false).digest !== manifest.nodeSha256 ||
        artifact(fileURLToPath(new URL('../darwin-private-state.cc', directory)), 1048576, false).digest !== manifest.sourceSha256)
      throw new PrivateStatePlatformError('darwin_native_artifact_unqualified');
    // Fixed selected artifact only: no download/build at startup, NODE_PATH,
    // executable lookup, external ACL utility, alternate package or fallback.
    const loaded = createRequire(import.meta.url)(target) as DarwinPrivateStateBackend;
    if (loaded.abi !== 1 || 'testFilesecFaults' in loaded || loaded.sourceSha256 !== manifest.sourceSha256 || ['readConfig', 'verifyPath', 'openStore', 'verifyStore', 'closeStore']
      .some(key => typeof (loaded as unknown as Record<string, unknown>)[key] !== 'function'))
      throw new PrivateStatePlatformError('darwin_native_abi_unqualified');
    backend = loaded;
    return backend;
  } catch (error) {
    if (error instanceof PrivateStatePlatformError) throw error;
    throw new PrivateStatePlatformError('darwin_native_artifact_unavailable');
  }
}
export function assertPrivateStatePlatform(platform: NodeJS.Platform = process.platform): void {
  if (platform === 'win32') throw new PrivateStatePlatformError('windows_native_acl_unqualified');
  if (platform === 'darwin') void loadDarwin();
}
export function darwinPrivateStateBackend(): DarwinPrivateStateBackend | null {
  assertPrivateStatePlatform();
  return process.platform === 'darwin' ? loadDarwin() : null;
}
/** Resolve only clean owner paths; never lexically erase a symlink/.. ambiguity. */
export function nativePrivateStatePath(path: string): string {
  if (!path || path.includes('\0') || path.split('/').some(part => part === '.' || part === '..'))
    throw new Error('unsafe_state_path');
  return resolve(path);
}
