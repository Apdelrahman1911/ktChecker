// Explicit source-checkout-only target-native build. No network, postinstall, runtime compilation,
// alternate runtime search, automatic retries, or native-support certification.
import { createHash } from 'node:crypto';
import { closeSync, constants, existsSync, fstatSync, mkdirSync, mkdtempSync, openSync,
  readFileSync, readSync, realpathSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { basename, isAbsolute, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { spawnSync } from 'node:child_process';

const fail = code => { throw new Error(code); };
function hashFile(path, bound) {
  const fd = openSync(path, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
  try {
    const stat = fstatSync(fd);
    if (!stat.isFile() || stat.nlink !== 1 || stat.size > bound || stat.size < 0 ||
        (stat.mode & 0o022) || ![0, process.getuid()].includes(stat.uid)) fail('unsafe_build_input');
    const hash = createHash('sha256'); const chunk = Buffer.alloc(65536); let total = 0;
    for (;;) {
      const count = readSync(fd, chunk, 0, chunk.length, null);
      if (!count) break;
      total += count; if (total > bound) fail('build_input_bound'); hash.update(chunk.subarray(0, count));
    }
    const after = fstatSync(fd);
    if (total !== stat.size || stat.dev !== after.dev || stat.ino !== after.ino || stat.size !== after.size ||
        stat.mtimeMs !== after.mtimeMs || stat.ctimeMs !== after.ctimeMs) fail('build_input_changed');
    return hash.digest('hex');
  } finally { closeSync(fd); }
}
const environment = Object.fromEntries(Object.entries(process.env).filter(([key]) =>
  !/^(?:DYLD_|LD_|NODE_OPTIONS$|NODE_PATH$|CPATH$|CPLUS_INCLUDE_PATH$|LIBRARY_PATH$|SDKROOT$|DEVELOPER_DIR$|MACOSX_DEPLOYMENT_TARGET$)/.test(key)));
environment.PATH = '/usr/bin:/bin:/usr/sbin:/sbin';
function command(path, args, timeout = 30000) {
  const result = spawnSync(path, args, { encoding: 'utf8', env: environment, timeout,
    maxBuffer: 1048576, stdio: ['ignore', 'pipe', 'pipe'] });
  if (result.error || result.status !== 0 || result.signal) fail('native_build_command_failed');
  return result.stdout.trim();
}

let work;
let completed = false;
try {
  if (process.platform !== 'darwin' || !['arm64', 'x64'].includes(process.arch) || process.versions.node !== '24.20.0' ||
      process.getuid() === 0 || process.getuid() !== process.geteuid() || process.getgid() !== process.getegid()) fail('native_build_target_unqualified');
  if (process.env.KTC_DARWIN_PRIVATE_STATE_BUILD !== '1') fail('explicit_native_build_opt_in_required');
  const macosVersion = command('/usr/bin/sw_vers', ['-productVersion']);
  if (!/^26(?:\.\d+){1,2}$/.test(macosVersion)) fail('native_build_target_unqualified');
  if (process.env.DEVELOPER_DIR !== undefined) {
    if (!/^\/Applications\/Xcode_26\.\d+(?:\.\d+)?\.app\/Contents\/Developer$/.test(process.env.DEVELOPER_DIR))
      fail('selected_developer_directory_unqualified');
    environment.DEVELOPER_DIR = process.env.DEVELOPER_DIR;
  }
  const args = process.argv.slice(2);
  const withFaults = args.length === 7 && args[6] === '--with-test-faults';
  if ((!withFaults && args.length !== 6) || args[0] !== '--node-archive' || args[2] !== '--arch' || args[4] !== '--sdk-version' ||
      !isAbsolute(args[1]) || args[3] !== process.arch || !/^26\.\d+(?:\.\d+)?$/.test(args[5])) fail('invalid_native_build_arguments');
  const [archive, arch, sdkVersion] = [args[1], args[3], args[5]];
  const root = fileURLToPath(new URL('.', import.meta.url));
  const pins = JSON.parse(readFileSync(join(root, 'node-release-pins.json'), 'utf8'));
  const selected = pins.archives[arch];
  if (pins.version !== 1 || pins.nodeVersion !== process.versions.node || basename(archive) !== selected.file ||
      hashFile(archive, 128 * 1024 * 1024) !== selected.sha256) fail('official_node_archive_mismatch');
  const target = join(root, `darwin-${arch}`);
  if (existsSync(target)) fail('native_output_already_exists');
  const faultTarget = join(root, 'test-output', `darwin-${arch}`);
  if (withFaults && existsSync(faultTarget)) fail('native_test_output_already_exists');
  work = mkdtempSync(join(root, '.darwin-build-'));
  const release = join(work, 'release'); mkdirSync(release, { mode: 0o700 });
  command('/usr/bin/tar', ['-xzf', archive, '-C', release, '--strip-components=1'], 60000);
  const nodeSha256 = hashFile(join(release, 'bin/node'), 256 * 1024 * 1024);
  if (hashFile(realpathSync(process.execPath), 256 * 1024 * 1024) !== nodeSha256) fail('running_node_not_selected_official_release');
  // Both compiler headers and the running runtime are from this exact archive.
  const include = join(release, 'include/node');
  for (const name of ['node_api.h', 'node_api_types.h', 'js_native_api.h', 'js_native_api_types.h'])
    hashFile(join(include, name), 1048576);
  if (command('/usr/bin/xcrun', ['--sdk', 'macosx', '--show-sdk-version']) !== sdkVersion) fail('selected_sdk_version_mismatch');
  const sdkPath = command('/usr/bin/xcrun', ['--sdk', 'macosx', '--show-sdk-path']);
  const compiler = command('/usr/bin/xcrun', ['--sdk', 'macosx', '--find', 'clang++']);
  if (!isAbsolute(sdkPath) || !isAbsolute(compiler)) fail('native_toolchain_path_unqualified');
  const compilerSha256 = hashFile(realpathSync(compiler), 1024 * 1024 * 1024);
  const compilerVersion = command(compiler, ['--version']);
  if (!compilerVersion.startsWith('Apple clang version ') || compilerVersion.length > 4096) fail('native_compiler_unqualified');
  const source = join(root, 'darwin-private-state.cc');
  const sourceSha256 = hashFile(source, 1048576);
  const output = join(work, 'output'); mkdirSync(output, { mode: 0o700 });
  const binary = join(output, 'private-state.node');
  const flags = ['-std=c++17', '-O2', '-fvisibility=hidden', '-Wall', '-Wextra', '-Werror', '-bundle',
    '-undefined', 'dynamic_lookup', '-mmacosx-version-min=26.0', '-arch', arch === 'x64' ? 'x86_64' : 'arm64',
    '-isysroot', sdkPath, '-I', include, '-DNAPI_VERSION=8', '-DNODE_GYP_MODULE_NAME=ktc_darwin_private_state',
    `-DKTC_SOURCE_SHA256="${sourceSha256}"`, source, '-o', binary];
  command(compiler, flags, 120000);
  // Probe the just-built addon in THIS Node process, before publishing output.
  // Addon init rejects root/set-ID/Rosetta; an external sysctl subprocess would
  // identify that utility, not the Node process whose native status matters.
  const loaded = createRequire(import.meta.url)(binary);
  if (loaded.abi !== 1 || loaded.sourceSha256 !== sourceSha256 || 'testFilesecFaults' in loaded)
    fail('native_build_load_unqualified');
  let faultOutput;
  if (withFaults) {
    faultOutput = join(work, 'fault-output'); mkdirSync(faultOutput, { mode: 0o700 });
    const faultSource = join(root, 'test/fstatx-fault.cc');
    hashFile(faultSource, 1048576);
    command(compiler, [...flags.slice(0, -3), faultSource, '-o', join(faultOutput, 'filesec-fault.node')], 120000);
  }
  if (hashFile(source, 1048576) !== sourceSha256 || hashFile(realpathSync(compiler), 1024 * 1024 * 1024) !== compilerSha256 ||
      hashFile(archive, 128 * 1024 * 1024) !== selected.sha256) fail('native_build_inputs_changed');
  const manifest = { version: 1, status: 'experimental-unqualified', platform: 'darwin', arch,
    nodeVersion: process.versions.node, nodeSha256, nodeReleaseSha256: selected.sha256,
    nativeSha256: hashFile(binary, 8 * 1024 * 1024), sourceSha256,
    buildScriptSha256: hashFile(fileURLToPath(import.meta.url), 1048576),
    sdkVersion, macosVersion, compilerSha256,
    compilerVersion, minimumMacosVersion: '26.0', napiVersion: 8, nativeRuntimeAdmissionAtBuild: true,
    provenance: 'checked official HTTPS-pinned release archive; local target build; not code signing or hosted qualification' };
  writeFileSync(join(output, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
  if (faultOutput) {
    mkdirSync(join(root, 'test-output'), { recursive: true, mode: 0o700 });
    renameSync(faultOutput, faultTarget);
  }
  renameSync(output, target);
  completed = true;
  process.stdout.write(JSON.stringify({ status: 'built-unqualified', arch, nativeSha256: manifest.nativeSha256, sourceSha256 }) + '\n');
} catch (error) {
  process.stderr.write(`Darwin native build failed: ${error instanceof Error && /^[a-z_]+$/.test(error.message) ? error.message : 'bounded_build_error'}\n`);
  process.exitCode = 2;
} finally {
  // Only this successful, newly created scratch attempt. Failed/uncertain work
  // is retained, never discovered/replayed/removed by a subsequent invocation.
  if (work && completed) rmSync(work, { recursive: true, force: true });
}
