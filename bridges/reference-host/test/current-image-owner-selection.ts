/** Explicit owner-selected Java/classpath/compiler-pair custody for one NEW pipe launch.
 * No discovery, report-derived enrollment, compiler/provider probes, build or model execution.
 * Digests remain exact `sha256:` strings; Unix identities are actual decimal dev:ino DATA.
 */
import { constants, closeSync, fstatSync, lstatSync, openSync } from 'node:fs';
import { basename, dirname, join } from 'node:path';
import { canonical, digest } from '../src/journal.js';
import { absoluteReal, absentReal, hashRealFile, makeFreshRealDirectory, readRealFile,
  type RealFileEvidence } from './real-model-run-support.js';
import { frozen, id, same, sha } from './current-image-acceptance-evidence.js';
import { assertCurrentImageControllerClosure, validateImageSelection,
  type CurrentImageSelection, type ImagePin } from './current-image-acceptance-image.js';
import type { FreshCurrentImageOwnerContext } from './current-image-acceptance-launch.js';
import type { OwnerInvocationSelection } from './current-image-acceptance-runner.js';
import { OwnerSessionClocks } from './current-image-owner-clocks.js';
import { ownerCheck, ownerInteger, ownerKeys, ownerObject, strictOwnerJson } from './current-image-owner-wire.js';

// EXACT existing CompilerModelWire bounds, not a widened acceptance/invocation budget.
export const OWNER_COMPILER_ARTIFACTS = 16;
export const OWNER_COMPILER_ARTIFACT_BYTES = 67_108_864;
export const OWNER_COMPILER_ARTIFACT_TOTAL_BYTES = 134_217_728;
export interface OwnerCompilerArtifactPair {
  readonly installedAbsolutePath: string; readonly cacheRelativePath: string; readonly digest: string; readonly bytes: number;
  readonly installedIdentity: string; readonly cacheIdentity: string;
}
export interface OwnerTransportSelection {
  readonly schema: 'ktchecker-owner-session-selection/1'; readonly attemptId: string;
  readonly ownerStateDirectory: string; readonly invocationRoot: string;
  readonly registration: { readonly workspaceId: string; readonly worktreeId: string; readonly clientId: string;
    readonly sessionId: string; readonly lineageId: string };
  readonly invocationSelection: OwnerInvocationSelection; readonly distributionHome: string;
  readonly jvmExecutable: ImagePin; readonly jvmClasspath: readonly ImagePin[];
  readonly imageSelectionPath: string; readonly imageSelectionDigest: string; readonly ownerCodeImageDigest: string;
  readonly compilerArtifacts: readonly OwnerCompilerArtifactPair[];
}
const transportKeys = ['schema', 'attemptId', 'ownerStateDirectory', 'invocationRoot', 'registration', 'invocationSelection',
  'distributionHome', 'jvmExecutable', 'jvmClasspath', 'imageSelectionPath', 'imageSelectionDigest', 'ownerCodeImageDigest', 'compilerArtifacts'];
const imageKeys = ['schema', 'approvalId', 'sourceInputs', 'installedOutputs', 'controllerOutputs', 'compilerArtifactPaths',
  'node', 'nodeVersion', 'codex', 'buildRecord', 'ownerCodeImageDigest'];
function validatePin(raw: unknown): asserts raw is ImagePin {
  const value = ownerObject(raw); ownerKeys(value, ['label', 'path', 'digest', 'bytes']);
  ownerCheck(typeof value.label === 'string' && value.label.length > 0 && Buffer.byteLength(value.label, 'utf8') <= 4096 &&
    !value.label.startsWith('/') && !value.label.includes('\\') && value.label.split('/').every(part => !['', '.', '..'].includes(part)), 'image_label');
  absoluteReal(value.path); sha(value.digest); ownerInteger(value.bytes, 536_870_912, 1);
}
export function ownerCacheArtifactParts(value: unknown): readonly string[] {
  ownerCheck(typeof value === 'string' && value.length <= 4096, 'cache_relative_invalid');
  const parts = value.split('/');
  ownerCheck(parts.length === 7 && parts[0] === 'modules-2' && parts[1] === 'files-2.1' &&
    parts.slice(2, 5).every(part => /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(part)) &&
    /^[0-9a-f]{40,64}$/.test(parts[5]!) && /^[A-Za-z0-9][A-Za-z0-9._-]*\.jar$/.test(parts[6]!), 'cache_relative_invalid');
  return Object.freeze(parts);
}
export function copiedOwnerArtifactPath(ownerStateDirectory: string, relative: string): string {
  absoluteReal(ownerStateDirectory); ownerCacheArtifactParts(relative);
  return ownerStateDirectory + '/model-command/artifacts/gradle-home/caches/' + relative;
}
function outside(root: string, path: string): boolean { return path !== root && !path.startsWith(root + '/') && !root.startsWith(path + '/'); }
function invocation(raw: unknown): asserts raw is OwnerInvocationSelection {
  const value = ownerObject(raw); ownerKeys(value, ['gradleHome', 'gradleCacheSeed', 'compilerJavaHome', 'compilerArtifactPaths', 'authorizeExecution']);
  ownerCheck(value.authorizeExecution === true, 'execution_not_selected');
  for (const key of ['gradleHome', 'gradleCacheSeed', 'compilerJavaHome']) absoluteReal(value[key]);
  ownerCheck(Array.isArray(value.compilerArtifactPaths) && value.compilerArtifactPaths.length > 0 && value.compilerArtifactPaths.length <= 128 &&
    new Set(value.compilerArtifactPaths).size === value.compilerArtifactPaths.length, 'compiler_artifact_order');
  for (const path of value.compilerArtifactPaths) ownerCheck(absoluteReal(path).endsWith('.jar'), 'compiler_artifact_kind');
}
/** Pure schema/order/membership join only. This cannot open an actual factory or confer authority. */
export function validateOwnerTransportSelection(raw: unknown, context: Pick<FreshCurrentImageOwnerContext,
  'attemptId' | 'ownerStateDirectory' | 'invocationRoot' | 'invocationSelection' | 'image' | 'imageSelectionPath'>, image: CurrentImageSelection): OwnerTransportSelection {
  const value = ownerObject(raw); ownerKeys(value, transportKeys); ownerKeys(ownerObject(image), imageKeys);
  ownerCheck(value.schema === 'ktchecker-owner-session-selection/1' && value.attemptId === context.attemptId, 'selection_context'); id(value.attemptId);
  const state = absoluteReal(value.ownerStateDirectory), runRoot = dirname(state);
  ownerCheck(state === context.ownerStateDirectory && absoluteReal(value.invocationRoot) === context.invocationRoot &&
    value.invocationRoot === state + '/model-command/runs', 'selected_state_or_invocation_root');
  const registration = ownerObject(value.registration); ownerKeys(registration, ['workspaceId', 'worktreeId', 'clientId', 'sessionId', 'lineageId']);
  for (const item of Object.values(registration)) id(item);
  invocation(value.invocationSelection); same(value.invocationSelection, context.invocationSelection, 'transport_invocation_not_selected');
  const selectedInvocation = value.invocationSelection;
  const home = absoluteReal(value.distributionHome); absoluteReal(value.imageSelectionPath); sha(value.imageSelectionDigest); sha(value.ownerCodeImageDigest);
  ownerCheck(value.imageSelectionPath === context.imageSelectionPath && value.imageSelectionDigest === context.image.selectionDigest && value.ownerCodeImageDigest === context.image.ownerCodeImageDigest &&
    image.ownerCodeImageDigest === context.image.ownerCodeImageDigest && image.schema === 'ktchecker-current-image-owner-selection/1', 'image_context_mismatch');
  same(image.compilerArtifactPaths, selectedInvocation.compilerArtifactPaths, 'transport_image_compiler_order');
  ownerCheck(Array.isArray(image.installedOutputs) && image.installedOutputs.length > 0 && image.installedOutputs.length <= 8192, 'installed_inventory_bound');
  for (const pin of image.installedOutputs) validatePin(pin);
  ownerCheck(new Set(image.installedOutputs.map(pin => pin.path)).size === image.installedOutputs.length &&
    new Set(image.installedOutputs.map(pin => pin.label)).size === image.installedOutputs.length, 'installed_inventory_alias');
  validatePin(value.jvmExecutable);
  ownerCheck(value.jvmExecutable.path === selectedInvocation.compilerJavaHome + '/bin/java' && !selectedInvocation.compilerJavaHome.includes(':'), 'jvm_executable_not_selected');
  ownerCheck(Array.isArray(value.jvmClasspath) && value.jvmClasspath.length >= 1 && value.jvmClasspath.length <= 128, 'jvm_classpath_bound');
  const classpath = value.jvmClasspath;
  for (const pin of classpath) {
    validatePin(pin);
    ownerCheck(dirname(pin.path) === home + '/lib' && pin.path.endsWith('.jar') && !pin.path.includes(':'), 'jvm_classpath_outside_selected_lib');
  }
  const jvm = [value.jvmExecutable, ...classpath];
  ownerCheck(new Set(jvm.map(pin => pin.path)).size === jvm.length && jvm.every(pin => image.installedOutputs.some(selected =>
    canonical(selected) === canonical(pin))), 'jvm_image_membership');
  ownerCheck(Array.isArray(value.compilerArtifacts) && value.compilerArtifacts.length >= 1 && value.compilerArtifacts.length <= OWNER_COMPILER_ARTIFACTS,
    'compiler_artifact_pair_bound');
  const pairs = value.compilerArtifacts, coordinates: string[] = [], names: string[] = [];
  let total = 0;
  for (const rawPair of pairs) {
    const pair = ownerObject(rawPair); ownerKeys(pair, ['installedAbsolutePath', 'cacheRelativePath', 'digest', 'bytes', 'installedIdentity', 'cacheIdentity']);
    const installed = absoluteReal(pair.installedAbsolutePath), parts = ownerCacheArtifactParts(pair.cacheRelativePath);
    sha(pair.digest); total += ownerInteger(pair.bytes, OWNER_COMPILER_ARTIFACT_BYTES, 1);
    ownerCheck(total <= OWNER_COMPILER_ARTIFACT_TOTAL_BYTES && typeof pair.installedIdentity === 'string' && typeof pair.cacheIdentity === 'string' &&
      /^[0-9]{1,20}:[0-9]{1,20}$/.test(pair.installedIdentity) && /^[0-9]{1,20}:[0-9]{1,20}$/.test(pair.cacheIdentity) &&
      pair.installedIdentity !== pair.cacheIdentity, 'artifact_pair_identity_or_bound');
    ownerCheck(basename(installed) === parts[6] && installed !== selectedInvocation.gradleCacheSeed + '/' + pair.cacheRelativePath &&
      image.installedOutputs.some(pin => pin.path === installed && pin.digest === pair.digest && pin.bytes === pair.bytes), 'artifact_pair_not_in_image_or_alias');
    coordinates.push(parts.slice(2, 5).join(':')); names.push(parts[6]!);
  }
  same(pairs.map(pair => ownerObject(pair).installedAbsolutePath), selectedInvocation.compilerArtifactPaths, 'transport_compiler_pair_order');
  ownerCheck(new Set(pairs.map(pair => ownerObject(pair).cacheRelativePath)).size === pairs.length &&
    new Set(coordinates).size === pairs.length && new Set(names).size === pairs.length, 'artifact_coordinate_or_cache_alias');
  const inputs = [home, value.jvmExecutable.path, ...classpath.map(pin => pin.path), value.imageSelectionPath,
    selectedInvocation.gradleHome, selectedInvocation.compilerJavaHome, ...selectedInvocation.compilerArtifactPaths];
  ownerCheck([...inputs, selectedInvocation.gradleCacheSeed].every(path => outside(runRoot, absoluteReal(path))), 'inputs_overlap_fresh_run');
  const seed = selectedInvocation.gradleCacheSeed;
  ownerCheck(inputs.every(path => outside(seed, absoluteReal(path))) &&
    [...image.sourceInputs, ...image.installedOutputs, ...image.controllerOutputs, image.node, image.codex, image.buildRecord]
      .every(pin => outside(seed, absoluteReal(pin.path))), 'selected_input_overlaps_external_seed');
  return frozen(structuredClone(value)) as unknown as OwnerTransportSelection;
}

/** Narrow secure directory identity read, not artifact discovery. Readonly ordinary ancestors
 * are allowed; every Linux edge is opened O_DIRECTORY|O_NOFOLLOW and rechecked. No Windows claim.
 */
export function ownerDirectoryIdentity(pathValue: string, privateLeaf = false): string {
  const path = absoluteReal(pathValue), fds: number[] = [], edges: { parent: number; name: string; identity: string; child: number }[] = [];
  const key = (stat: ReturnType<typeof fstatSync> | { dev: bigint; ino: bigint }) => stat.dev.toString() + ':' + stat.ino.toString();
  try {
    let fd = openSync('/', constants.O_RDONLY | constants.O_DIRECTORY | constants.O_NOFOLLOW); fds.push(fd);
    for (const name of path.slice(1).split('/')) {
      const anchored = '/proc/self/fd/' + fd + '/' + name, before = lstatSync(anchored, { bigint: true });
      ownerCheck(before.isDirectory() && !before.isSymbolicLink(), 'directory_unsafe');
      const child = openSync(anchored, constants.O_RDONLY | constants.O_DIRECTORY | constants.O_NOFOLLOW); fds.push(child);
      ownerCheck(key(fstatSync(child, { bigint: true })) === key(before), 'directory_changed');
      edges.push({ parent: fd, name, identity: key(before), child }); fd = child;
    }
    const stat = fstatSync(fd, { bigint: true });
    if (privateLeaf) ownerCheck((stat.mode & 0o777n) === 0o700n && process.getuid !== undefined && stat.uid === BigInt(process.getuid()), 'private_directory_required');
    for (const edge of edges) ownerCheck(key(fstatSync(edge.child, { bigint: true })) === edge.identity &&
      key(lstatSync('/proc/self/fd/' + edge.parent + '/' + edge.name, { bigint: true })) === edge.identity, 'directory_changed');
    return key(stat);
  } finally { for (const fd of fds.reverse()) closeSync(fd); } // Read descriptors only, never owner/process cleanup.
}
function samePin(actual: RealFileEvidence, expected: Pick<RealFileEvidence, 'digest' | 'bytes' | 'fileKey'>): void {
  ownerCheck(actual.digest === expected.digest && actual.bytes === expected.bytes && actual.fileKey === expected.fileKey, 'selected_file_changed');
}
/** Shared actual file leg verifier. Tests may use NEW inert temporary files, not executable stubs. */
export function verifyOwnerArtifactLeg(path: string, pair: OwnerCompilerArtifactPair, identity: string): RealFileEvidence {
  const pin = hashRealFile(path, OWNER_COMPILER_ARTIFACT_BYTES);
  samePin(pin, { digest: pair.digest, bytes: pair.bytes, fileKey: identity }); return Object.freeze(pin);
}
export function selectedOwnerJvmArguments(choice: OwnerTransportSelection, selectionPath: string, selectionDigest: string): readonly string[] {
  absoluteReal(selectionPath); sha(selectionDigest);
  ownerCheck(choice.jvmClasspath.length > 0 && choice.jvmClasspath.every(pin => !pin.path.includes(':')), 'jvm_classpath_delimiter');
  const root = dirname(choice.ownerStateDirectory);
  return Object.freeze(['-Xmx512m', '-XX:MaxMetaspaceSize=256m', '-Duser.home=' + join(root, 'owner-environment-home'),
    '-Djava.io.tmpdir=' + join(root, 'owner-environment-tmp'), '-cp', choice.jvmClasspath.map(pin => pin.path).join(':'),
    'dev.ktchecker.cli.MainKt', 'owner-model-session', '--owner-action', '--authorize-execution', '--request', selectionPath, '--selection-digest', selectionDigest]);
}

/** Read-only pin custody plus NEW environment creation. Never mints an owner/host capability. */
export class PreparedOwnerSessionSelection {
  readonly #inputs: readonly RealFileEvidence[]; readonly #seeds: readonly RealFileEvidence[];
  readonly #directories: readonly { path: string; identity: string }[];
  #environment: NodeJS.ProcessEnv | null = null; #environmentPins: readonly { path: string; identity: string }[] = [];
  #copies: readonly RealFileEvidence[] | null = null;
  private constructor(readonly selectionPath: string, readonly selectionDigest: string, readonly selection: OwnerTransportSelection,
    inputs: readonly RealFileEvidence[], seeds: readonly RealFileEvidence[], directories: readonly { path: string; identity: string }[]) {
    this.#inputs = frozen(inputs); this.#seeds = frozen(seeds); this.#directories = frozen(directories); Object.freeze(this);
  }
  static read(path: string, expected: string, context: FreshCurrentImageOwnerContext, clocks: OwnerSessionClocks): PreparedOwnerSessionSelection {
    clocks.checkpoint(); absoluteReal(path); sha(expected);
    ownerCheck(path === context.ownerTransportSelectionPath && expected === context.ownerTransportSelectionDigest, 'transport_not_launcher_selected');
    const selected = readRealFile(path, 2_097_152); ownerCheck(selected.evidence.digest === expected, 'selection_changed'); clocks.checkpoint();
    const raw = ownerObject(strictOwnerJson(selected.bytes)); ownerKeys(raw, transportKeys);
    const imagePath = absoluteReal(raw.imageSelectionPath), imageDigest = sha(raw.imageSelectionDigest);
    ownerCheck(imagePath === context.imageSelectionPath && imageDigest === context.image.selectionDigest, 'image_not_launcher_selected');
    const imageFile = readRealFile(imagePath, 2_097_152); ownerCheck(imageFile.evidence.digest === imageDigest, 'image_selection_changed'); clocks.checkpoint();
    const image = strictOwnerJson(imageFile.bytes) as CurrentImageSelection; ownerKeys(ownerObject(image), imageKeys);
    validatePin(image.buildRecord);
    const build = readRealFile(image.buildRecord.path, 1_048_576); clocks.checkpoint();
    ownerCheck(build.evidence.digest === image.buildRecord.digest && build.evidence.bytes === image.buildRecord.bytes, 'build_record_changed');
    same({ selectionDigest: imageDigest, ...validateImageSelection(image, strictOwnerJson(build.bytes)) }, context.image, 'transport_image_evidence');
    assertCurrentImageControllerClosure(image);
    ownerCheck(image.node.path === process.execPath && image.nodeVersion === process.versions.node, 'node_not_selected');
    const choice = validateOwnerTransportSelection(raw, context, image), root = dirname(choice.ownerStateDirectory);
    const pins = [...image.sourceInputs, ...image.installedOutputs, ...image.controllerOutputs, image.node, image.codex, image.buildRecord];
    ownerCheck([path, imagePath, ...pins.map(pin => pin.path)].every(input => outside(root, absoluteReal(input))), 'selected_input_overlaps_run');
    ownerCheck(outside(choice.invocationSelection.gradleCacheSeed, path), 'transport_selection_overlaps_external_seed');
    const directories = [root, choice.distributionHome, choice.invocationSelection.gradleHome, choice.invocationSelection.gradleCacheSeed,
      choice.invocationSelection.compilerJavaHome].map((directory, index) => {
      clocks.checkpoint(); return { path: directory, identity: ownerDirectoryIdentity(directory, index === 0) };
    });
    ownerCheck(absentReal(choice.ownerStateDirectory), 'owner_state_not_fresh');
    const inputs: RealFileEvidence[] = [selected.evidence, imageFile.evidence];
    for (const pin of pins) {
      clocks.checkpoint(); validatePin(pin); const actual = hashRealFile(pin.path);
      ownerCheck(actual.digest === pin.digest && actual.bytes === pin.bytes, 'image_pin_changed'); inputs.push(actual); clocks.checkpoint();
    }
    const seeds: RealFileEvidence[] = [];
    for (const pair of choice.compilerArtifacts) {
      clocks.checkpoint(); const installed = verifyOwnerArtifactLeg(pair.installedAbsolutePath, pair, pair.installedIdentity);
      ownerCheck(inputs.some(pin => pin.path === installed.path && pin.fileKey === installed.fileKey && pin.digest === installed.digest && pin.bytes === installed.bytes),
        'installed_pin_identity_changed');
      seeds.push(verifyOwnerArtifactLeg(choice.invocationSelection.gradleCacheSeed + '/' + pair.cacheRelativePath, pair, pair.cacheIdentity)); clocks.checkpoint();
    }
    const prepared = new PreparedOwnerSessionSelection(path, expected, choice, inputs, seeds, directories);
    prepared.assertBeforeSpawn(clocks); return prepared;
  }
  environment(clocks: OwnerSessionClocks): NodeJS.ProcessEnv {
    clocks.checkpoint(); ownerCheck(this.#environment === null, 'environment_reopened');
    for (const key of ['JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH', 'LD_PRELOAD', 'LD_LIBRARY_PATH'])
      ownerCheck(process.env[key] === undefined || process.env[key] === '', 'inherited_jvm_or_loader_injection');
    const root = dirname(this.selection.ownerStateDirectory), home = join(root, 'owner-environment-home'), tmp = join(root, 'owner-environment-tmp');
    const homeKey = makeFreshRealDirectory(home); this.#environmentPins = [{ path: home, identity: homeKey }]; clocks.checkpoint();
    const tmpKey = makeFreshRealDirectory(tmp); this.#environmentPins = frozen([...this.#environmentPins, { path: tmp, identity: tmpKey }]); clocks.checkpoint();
    this.#environment = Object.freeze({ PATH: this.selection.invocationSelection.compilerJavaHome + '/bin:/usr/bin:/bin',
      JAVA_HOME: this.selection.invocationSelection.compilerJavaHome, HOME: home, TMPDIR: tmp, LANG: 'C.UTF-8', LC_ALL: 'C.UTF-8', TZ: 'UTC' });
    return this.#environment;
  }
  #verifyInputs(clocks: OwnerSessionClocks, initialSeed = false): void {
    clocks.checkpoint();
    for (const directory of [...this.#directories, ...this.#environmentPins]) {
      if (!initialSeed && directory.path === this.selection.invocationSelection.gradleCacheSeed) continue;
      ownerCheck(ownerDirectoryIdentity(directory.path, directory.path === dirname(this.selection.ownerStateDirectory) ||
        this.#environmentPins.some(pin => pin.path === directory.path)) === directory.identity, 'selected_directory_changed'); clocks.checkpoint();
    }
    for (const pin of this.#inputs) { samePin(hashRealFile(pin.path), pin); clocks.checkpoint(); }
  }
  assertBeforeSpawn(clocks: OwnerSessionClocks): void {
    this.#verifyInputs(clocks, true); ownerCheck(this.#copies === null && absentReal(this.selection.ownerStateDirectory), 'before_spawn_state_reused');
    for (const seed of this.#seeds) { samePin(hashRealFile(seed.path, OWNER_COMPILER_ARTIFACT_BYTES), seed); clocks.checkpoint(); }
  }
  verifyInitialCopies(clocks: OwnerSessionClocks): void {
    clocks.checkpoint(); ownerCheck(this.#copies === null, 'copies_already_bound');
    const copies: RealFileEvidence[] = [];
    for (const pair of this.selection.compilerArtifacts) {
      const path = copiedOwnerArtifactPath(this.selection.ownerStateDirectory, pair.cacheRelativePath), pin = hashRealFile(path, OWNER_COMPILER_ARTIFACT_BYTES);
      ownerCheck(pin.digest === pair.digest && pin.bytes === pair.bytes && pin.fileKey !== pair.installedIdentity && pin.fileKey !== pair.cacheIdentity &&
        !copies.some(previous => previous.fileKey === pin.fileKey), 'copied_artifact_changed_or_alias');
      copies.push(Object.freeze(pin)); clocks.checkpoint();
    }
    this.#verifyInputs(clocks, true);
    // One final check of the ORIGINAL external seed after actual installation. Never per patch.
    for (const seed of this.#seeds) { samePin(hashRealFile(seed.path, OWNER_COMPILER_ARTIFACT_BYTES), seed); clocks.checkpoint(); }
    this.#copies = Object.freeze(copies); this.assertCurrent(clocks);
  }
  assertCurrent(clocks: OwnerSessionClocks): void {
    this.#verifyInputs(clocks); ownerCheck(this.#copies !== null, 'copied_installation_not_bound');
    for (const copy of this.#copies) { samePin(hashRealFile(copy.path, OWNER_COMPILER_ARTIFACT_BYTES), copy); clocks.checkpoint(); }
    // Deliberately no external cache seed capture/read here; same retained copied installation.
  }
  privateInputLocations(): readonly string[] {
    const choice = this.selection, root = dirname(choice.ownerStateDirectory);
    return Object.freeze([this.selectionPath, choice.imageSelectionPath, choice.distributionHome, choice.jvmExecutable.path,
      ...choice.jvmClasspath.map(pin => pin.path), choice.invocationSelection.gradleHome, choice.invocationSelection.gradleCacheSeed,
      choice.invocationSelection.compilerJavaHome, ...choice.compilerArtifacts.flatMap(pair => [pair.installedAbsolutePath,
        choice.invocationSelection.gradleCacheSeed + '/' + pair.cacheRelativePath, copiedOwnerArtifactPath(choice.ownerStateDirectory, pair.cacheRelativePath)]),
      join(root, 'owner-environment-home'), join(root, 'owner-environment-tmp')]);
  }
}
Object.freeze(PreparedOwnerSessionSelection.prototype); Object.freeze(PreparedOwnerSessionSelection);
