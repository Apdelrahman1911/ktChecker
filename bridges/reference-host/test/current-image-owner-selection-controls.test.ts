/** SYNTHETIC data joins and NEW inert file-leg controls. Never prepares a launch, reads a
 * historical attempt, executes a stub/JVM/compiler/model, or claims selected build authority. */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtemp, mkdir, rename, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { digest } from '../src/journal.js';
import { hashRealFile } from './real-model-run-support.js';
import type { CurrentImageEvidence, CurrentImageSelection, ImagePin } from './current-image-acceptance-image.js';
import type { FreshCurrentImageOwnerContext } from './current-image-acceptance-launch.js';
import { copiedOwnerArtifactPath, ownerCacheArtifactParts, ownerDirectoryIdentity, PreparedOwnerSessionSelection,
  selectedOwnerJvmArguments, validateOwnerTransportSelection, verifyOwnerArtifactLeg,
  type OwnerCompilerArtifactPair, type OwnerTransportSelection } from './current-image-owner-selection.js';

const linuxOnly = { skip: process.platform !== 'linux' ? 'platform_unavailable:linux_current_image_support_required' : false };
type SyntheticValue = Record<string, any>; // Deliberately untrusted mutation shapes, never actual manifests.
function data() {
  const pin = (label: string, path: string): ImagePin => ({ label, path, digest: digest('SYNTHETIC ' + label), bytes: 16 });
  const compiler = [pin('z-compiler.jar', '/synthetic-owner/installed/z-compiler.jar'), pin('a-support.jar', '/synthetic-owner/installed/a-support.jar')];
  const java = pin('java', '/synthetic-owner/jdk/bin/java'), classpath = [pin('z-owner.jar', '/synthetic-owner/dist/lib/z-owner.jar'),
    pin('a-runtime.jar', '/synthetic-owner/dist/lib/a-runtime.jar')];
  const invocation = { gradleHome: '/synthetic-owner/gradle', gradleCacheSeed: '/synthetic-owner/cache', compilerJavaHome: '/synthetic-owner/jdk',
    compilerArtifactPaths: compiler.map(value => value.path), authorizeExecution: true as const };
  const image: CurrentImageSelection = { schema: 'ktchecker-current-image-owner-selection/1', approvalId: 'synthetic-owner-image',
    sourceInputs: [pin('source.kt', '/synthetic-owner/source/file.kt')], installedOutputs: [...compiler, java, ...classpath].sort((a, b) => a.label.localeCompare(b.label)),
    controllerOutputs: [pin('controller.js', '/synthetic-owner/controller/code.js')], compilerArtifactPaths: invocation.compilerArtifactPaths,
    node: pin('node', '/synthetic-owner/node'), nodeVersion: '24.20.0', codex: pin('codex', '/synthetic-owner/codex'),
    buildRecord: pin('build.json', '/synthetic-owner/build.json'), ownerCodeImageDigest: digest('SYNTHETIC owner code') };
  const evidence: CurrentImageEvidence = { selectionDigest: digest('SYNTHETIC selected image'), buildRecordDigest: image.buildRecord.digest,
    sourceInputsDigest: digest('SYNTHETIC source'), installedOutputsDigest: digest('SYNTHETIC installed'), controllerOutputsDigest: digest('SYNTHETIC controller'),
    ownerCodeImageDigest: image.ownerCodeImageDigest, compilerArtifactOrderDigest: digest('SYNTHETIC order'), nodeDigest: image.node.digest,
    nodeVersion: image.nodeVersion, codexDigest: image.codex.digest, loadedImageAttestation: false,
    sourceLinkage: 'owner_pinned_successful_build_record_linkage_not_reproducibility_or_loaded_memory_attestation' };
  const context: Pick<FreshCurrentImageOwnerContext, 'attemptId' | 'ownerStateDirectory' | 'invocationRoot' | 'invocationSelection' | 'image' | 'imageSelectionPath'> = {
    attemptId: 'synthetic-owner-attempt', ownerStateDirectory: '/synthetic-owner-new/attempt/owner-state',
    invocationRoot: '/synthetic-owner-new/attempt/owner-state/model-command/runs', invocationSelection: invocation,
    image: evidence, imageSelectionPath: '/synthetic-owner/image.json' };
  const selection: OwnerTransportSelection = { schema: 'ktchecker-owner-session-selection/1', attemptId: context.attemptId,
    ownerStateDirectory: context.ownerStateDirectory, invocationRoot: context.invocationRoot,
    registration: { workspaceId: 'synthetic-workspace', worktreeId: 'synthetic-worktree', clientId: 'synthetic-client', sessionId: 'synthetic-session', lineageId: 'synthetic-lineage' },
    invocationSelection: invocation, distributionHome: '/synthetic-owner/dist', jvmExecutable: java, jvmClasspath: classpath,
    imageSelectionPath: context.imageSelectionPath, imageSelectionDigest: evidence.selectionDigest, ownerCodeImageDigest: image.ownerCodeImageDigest,
    compilerArtifacts: compiler.map((value, index) => ({ installedAbsolutePath: value.path,
      cacheRelativePath: 'modules-2/files-2.1/org.synthetic/' + (index === 0 ? 'compiler' : 'support') + '/1.0/' + String(index + 1).repeat(40) + '/' + value.label,
      digest: value.digest, bytes: value.bytes, installedIdentity: '1:' + (index + 10), cacheIdentity: '2:' + (index + 10) })) };
  return { selection, context, image };
}
function artifactBoundsData(sizes: readonly number[]) {
  const f = data(), compilerArtifacts: OwnerCompilerArtifactPair[] = sizes.map((bytes, index) => ({
    installedAbsolutePath: '/synthetic-owner/installed/part-' + index + '.jar', bytes, digest: digest('SYNTHETIC part ' + index),
    cacheRelativePath: 'modules-2/files-2.1/org.synthetic/part-' + index + '/1.0/' + (index + 1).toString(16).padStart(40, '0') + '/part-' + index + '.jar',
    installedIdentity: '1:' + (100 + index), cacheIdentity: '2:' + (100 + index) }));
  const compilerArtifactPaths = compilerArtifacts.map(pair => pair.installedAbsolutePath),
    invocationSelection = { ...f.selection.invocationSelection, compilerArtifactPaths };
  const installedOutputs = [...f.image.installedOutputs.filter(pin => !f.selection.invocationSelection.compilerArtifactPaths.includes(pin.path)),
    ...compilerArtifacts.map((pair, index) => ({ label: 'part-' + index + '.jar', path: pair.installedAbsolutePath, digest: pair.digest, bytes: pair.bytes }))]
    .sort((a, b) => a.label.localeCompare(b.label));
  return { selection: { ...f.selection, invocationSelection, compilerArtifacts }, context: { ...f.context, invocationSelection },
    image: { ...f.image, compilerArtifactPaths, installedOutputs } };
}
test('transport pure join preserves explicit compiler/classpath order and exact installed pin tuples', linuxOnly, () => {
  const f = data(), selected = validateOwnerTransportSelection(f.selection, f.context, f.image);
  assert.deepEqual(selected, f.selection); assert(Object.isFrozen(selected)); assert(Object.isFrozen(selected.compilerArtifacts));
  assert.equal(selected.compilerArtifacts[0]!.installedAbsolutePath.endsWith('/z-compiler.jar'), true);
  assert.equal(selected.jvmClasspath[0]!.path.endsWith('/z-owner.jar'), true);
});
test('compiler-pair count stays at the existing exact16 bound without allocating artifact bytes', linuxOnly, () => {
  const valid = artifactBoundsData(Array(16).fill(16)), invalid = artifactBoundsData(Array(17).fill(16));
  assert.equal(validateOwnerTransportSelection(valid.selection, valid.context, valid.image).compilerArtifacts.length, 16);
  assert.throws(() => validateOwnerTransportSelection(invalid.selection, invalid.context, invalid.image), /compiler_artifact_pair_bound/);
});
test('compiler-pair aggregate stays at existing128MiB with independent64MiB per-file bounds', linuxOnly, () => {
  const valid = artifactBoundsData([67_108_864, 67_108_864]), invalid = artifactBoundsData([67_108_864, 67_108_864, 1]);
  assert.equal(validateOwnerTransportSelection(valid.selection, valid.context, valid.image).compilerArtifacts.length, 2);
  assert.throws(() => validateOwnerTransportSelection(invalid.selection, invalid.context, invalid.image), /artifact_pair_identity_or_bound/);
});
for (const [name, mutation] of [
  ['unselected image path with equal digest', (value: SyntheticValue) => { value.imageSelectionPath = '/synthetic-owner/other-image.json'; }],
  ['unselected image digest', (value: SyntheticValue) => { value.imageSelectionDigest = digest('other image'); }],
  ['different owner code', (value: SyntheticValue) => { value.ownerCodeImageDigest = digest('other owner'); }],
  ['different attempt', (value: SyntheticValue) => { value.attemptId = 'different-attempt'; }],
  ['different owner state', (value: SyntheticValue) => { value.ownerStateDirectory = '/synthetic-owner-other/state'; }],
  ['outside invocation root', (value: SyntheticValue) => { value.invocationRoot = '/synthetic-owner-other/runs'; }],
  ['extra discovery field', (value: SyntheticValue) => { value.discoverCompilerJars = true; }],
  ['unapproved execution', (value: SyntheticValue) => { value.invocationSelection.authorizeExecution = false; }],
  ['different selected Java', (value: SyntheticValue) => { value.jvmExecutable.path = '/synthetic-owner/other-jdk/bin/java'; }],
  ['JVM label changed despite equal path and bytes', (value: SyntheticValue) => { value.jvmExecutable.label = 'other-label'; }],
  ['JVM digest changed', (value: SyntheticValue) => { value.jvmExecutable.digest = digest('other-java'); }],
  ['JVM bytes changed', (value: SyntheticValue) => { value.jvmExecutable.bytes++; }],
  ['classpath label changed', (value: SyntheticValue) => { value.jvmClasspath[0].label = 'other-label'; }],
  ['classpath outside selected lib', (value: SyntheticValue) => { value.jvmClasspath[0].path = '/synthetic-owner/other/z-owner.jar'; }],
  ['classpath delimiter injection', (value: SyntheticValue) => { value.jvmClasspath[0].path = '/synthetic-owner/dist/lib/x:y.jar'; }],
  ['duplicate classpath member', (value: SyntheticValue) => { value.jvmClasspath.push(value.jvmClasspath[0]); }],
  ['empty classpath', (value: SyntheticValue) => { value.jvmClasspath = []; }],
  ['compiler pair order changed', (value: SyntheticValue) => { value.compilerArtifacts.reverse(); }],
  ['compiler pair missing', (value: SyntheticValue) => { value.compilerArtifacts.pop(); }],
  ['compiler bytes changed', (value: SyntheticValue) => { value.compilerArtifacts[0].bytes++; }],
  ['compiler digest changed', (value: SyntheticValue) => { value.compilerArtifacts[0].digest = digest('other compiler'); }],
  ['naked compiler hash', (value: SyntheticValue) => { value.compilerArtifacts[0].digest = 'f'.repeat(64); }],
  ['installed and seed role alias', (value: SyntheticValue) => { value.compilerArtifacts[0].cacheIdentity = value.compilerArtifacts[0].installedIdentity; }],
  ['nondecimal identity', (value: SyntheticValue) => { value.compilerArtifacts[0].installedIdentity = '(dev=fd00,ino=42)'; }],
  ['oversized artifact', (value: SyntheticValue) => { value.compilerArtifacts[0].bytes = 67_108_865; }],
  ['cache path traversal', (value: SyntheticValue) => { value.compilerArtifacts[0].cacheRelativePath = '../compiler.jar'; }],
  ['cache digest uppercase', (value: SyntheticValue) => { value.compilerArtifacts[0].cacheRelativePath = value.compilerArtifacts[0].cacheRelativePath.replace('1'.repeat(40), 'A'.repeat(40)); }],
  ['cache filename mismatch', (value: SyntheticValue) => { value.compilerArtifacts[0].cacheRelativePath = value.compilerArtifacts[0].cacheRelativePath.replace('z-compiler.jar', 'other.jar'); }],
  ['duplicate cache coordinate', (value: SyntheticValue) => { value.compilerArtifacts[1].cacheRelativePath = value.compilerArtifacts[1].cacheRelativePath.replace('/support/', '/compiler/'); }],
] as const) test('transport selection rejects ' + name + ' without IO', linuxOnly, () => {
  const f = data(), value = structuredClone(f.selection) as unknown as SyntheticValue; mutation(value);
  assert.throws(() => validateOwnerTransportSelection(value, f.context, f.image));
});
test('same compiler paths still require the explicit current image installed inventory order', linuxOnly, () => {
  const f = data();
  assert.throws(() => validateOwnerTransportSelection(f.selection, f.context, { ...f.image, compilerArtifactPaths: [...f.image.compilerArtifactPaths].reverse() }), /compiler_order/);
  assert.throws(() => validateOwnerTransportSelection(f.selection, f.context, { ...f.image, installedOutputs: f.image.installedOutputs.slice(1) }));
});
for (const role of ['sourceInputs', 'controllerOutputs', 'installedOutputs'] as const)
  test('later ' + role + ' pin cannot be nested under the original external seed', linuxOnly, () => {
    const f = data(), pins = [...f.image[role], { label: 'extra-seed-file', digest: digest('SYNTHETIC extra seed pin'), bytes: 16,
      path: f.selection.invocationSelection.gradleCacheSeed + '/indirect-selected-file' }];
    assert.throws(() => validateOwnerTransportSelection(f.selection, f.context, { ...f.image, [role]: pins }), /external_seed/);
  });
test('even matching explicit manifest path+digest cannot put later selected image reads under external seed', linuxOnly, () => {
  const f = data(), path = f.selection.invocationSelection.gradleCacheSeed + '/image.json';
  assert.throws(() => validateOwnerTransportSelection({ ...f.selection, imageSelectionPath: path }, { ...f.context, imageSelectionPath: path }, f.image), /external_seed/);
});
test('canonical mapping yields only the actual copied model-command artifact path', linuxOnly, () => {
  const f = data(), pair = f.selection.compilerArtifacts[0]!;
  assert.equal(ownerCacheArtifactParts(pair.cacheRelativePath).length, 7);
  assert.equal(copiedOwnerArtifactPath(f.selection.ownerStateDirectory, pair.cacheRelativePath),
    f.selection.ownerStateDirectory + '/model-command/artifacts/gradle-home/caches/' + pair.cacheRelativePath);
  for (const invalid of ['/absolute.jar', 'modules-2/files-2.1/g/a/v/' + 'a'.repeat(40) + '/x.jar/extra',
    'modules-2/files-2.1/g/a/v/' + 'a'.repeat(39) + '/x.jar', 'modules-2/files-2.1/g/../v/' + 'a'.repeat(40) + '/x.jar'])
    assert.throws(() => ownerCacheArtifactParts(invalid), /cache_relative_invalid/);
});
test('fixed selected JVM argv preserves literal Unicode quote dollar bracket path DATA, exact order and bounds', linuxOnly, () => {
  const f = data(), path = '/synthetic-selected/Δ-\'"-$-[literal]/transport.json', selectionDigest = digest('synthetic selected transport');
  const args = selectedOwnerJvmArguments(f.selection, path, selectionDigest);
  assert.deepEqual(args, ['-Xmx512m', '-XX:MaxMetaspaceSize=256m', '-Duser.home=/synthetic-owner-new/attempt/owner-environment-home',
    '-Djava.io.tmpdir=/synthetic-owner-new/attempt/owner-environment-tmp', '-cp', f.selection.jvmClasspath.map(pin => pin.path).join(':'),
    'dev.ktchecker.cli.MainKt', 'owner-model-session', '--owner-action', '--authorize-execution', '--request', path, '--selection-digest', selectionDigest]);
  assert(Object.isFrozen(args)); assert(!args.some(value => value.includes('ktchecker.home')));
  // Linux pure DATA only; native Windows treats Unicode quote dollar and bracket paths as data and allows readonly ordinary-user ancestors remains HELD.
});
test('actual inert file-leg hashing binds byte digest AND dev:ino; copied paths do not alias either source leg', linuxOnly, async t => {
  const root = await mkdtemp(join(tmpdir(), 'ktchecker-owner-leg-SYNTHETIC-'));
  t.after(() => rm(root, { recursive: true, force: true })); // Exclusively new inert files, never executable/owner attempts.
  const bytes = Buffer.from('SYNTHETIC inert artifact bytes - not a JAR or executable'), installed = join(root, 'installed.jar'), cache = join(root, 'cache.jar'), copy = join(root, 'copy.jar');
  await writeFile(installed, bytes, { flag: 'wx' }); await writeFile(cache, bytes, { flag: 'wx' }); await writeFile(copy, bytes, { flag: 'wx' });
  const a = hashRealFile(installed), b = hashRealFile(cache), c = hashRealFile(copy);
  const pair: OwnerCompilerArtifactPair = { installedAbsolutePath: installed, cacheRelativePath: 'modules-2/files-2.1/g/a/v/' + 'a'.repeat(40) + '/installed.jar',
    digest: a.digest, bytes: a.bytes, installedIdentity: a.fileKey, cacheIdentity: b.fileKey };
  assert.notEqual(a.fileKey, b.fileKey); assert.notEqual(c.fileKey, a.fileKey); assert.notEqual(c.fileKey, b.fileKey);
  assert.deepEqual(verifyOwnerArtifactLeg(installed, pair, a.fileKey), a); assert.deepEqual(verifyOwnerArtifactLeg(cache, pair, b.fileKey), b);
  assert.deepEqual(verifyOwnerArtifactLeg(copy, pair, c.fileKey), c);
  assert.throws(() => verifyOwnerArtifactLeg(copy, pair, b.fileKey), /selected_file_changed/);
  await rename(installed, join(root, 'retained-old-inert.jar')); await writeFile(installed, bytes, { flag: 'wx' });
  assert.throws(() => verifyOwnerArtifactLeg(installed, pair, a.fileKey), /selected_file_changed/);
  await writeFile(cache, Buffer.from('different bytes'));
  assert.throws(() => verifyOwnerArtifactLeg(cache, pair, b.fileKey), /selected_file_changed/);
});
test('anchored inert directory identity rejects a symlink; preparation class/static reader are immutable', linuxOnly, async t => {
  const root = await mkdtemp(join(tmpdir(), 'ktchecker-owner-dir-SYNTHETIC-')); t.after(() => rm(root, { recursive: true, force: true }));
  const selected = join(root, 'selected'), alias = join(root, 'alias'); await mkdir(selected, { mode: 0o700 }); await symlink(selected, alias);
  assert.match(ownerDirectoryIdentity(selected, true), /^\d+:\d+$/); assert.throws(() => ownerDirectoryIdentity(alias), /directory_unsafe/);
  assert(Object.isFrozen(PreparedOwnerSessionSelection)); assert(Object.isFrozen(PreparedOwnerSessionSelection.prototype));
  assert.throws(() => Object.defineProperty(PreparedOwnerSessionSelection, 'read', { value() { assert.fail('replacement'); } }));
});
