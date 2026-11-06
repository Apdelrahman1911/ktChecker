/** Explicit disk/build-record admission for the pre-owner smoke ONLY. No model selection,
 * factory/context issuer, artifact discovery, installed launcher, build or tool probe.
 * Root must independently approve the successful build record and trusted JDK/Node launch
 * context. Matching JSON/disk hashes do not attest loaded memory or native/JDK closure.
 */
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import type { JsonObject } from '@ktchecker/mcp-preview/rpc';
import { canonical, digest } from '../src/journal.js';
import { frozen } from './current-image-acceptance-evidence.js';
import { requiredCurrentImageControllerModules, type ImagePin } from './current-image-acceptance-image.js';
import { OwnerSessionClocks } from './current-image-owner-clocks.js';
import { ownerCheck, ownerInteger, ownerKeys, ownerObject, strictOwnerJson } from './current-image-owner-wire.js';
import { absoluteReal, hashRealFile, readRealFile, shaReal, type RealFileEvidence } from './real-model-run-support.js';

export const PREOWNER_SELECTION_BYTES = 2 * 1024 * 1024;
export const PREOWNER_BUILD_RECORD_BYTES = 65_536;
export const PREOWNER_INPUT_TOTAL_BYTES = 1024 * 1024 * 1024;
export const PREOWNER_SENTINEL_TEXT = '{"schema":"ktchecker-NOT-an-owner-selection-preowner-smoke/1"}\n';
export interface PreownerSmokeSelection {
  readonly schema: 'ktchecker-owner-preowner-smoke-selection/1'; readonly approvalId: string;
  readonly sourceRoot: string; readonly distributionHome: string; readonly javaHome: string; readonly outputDirectory: string;
  readonly node: ImagePin; readonly nodeVersion: string; readonly jvmExecutable: ImagePin; readonly jvmClasspath: readonly ImagePin[];
  readonly sourceInputs: readonly ImagePin[]; readonly controllerOutputs: readonly ImagePin[]; readonly buildRecord: ImagePin;
}
export interface PreparedPreownerSmokeInputs {
  readonly selection: PreownerSmokeSelection; readonly selectionEvidence: RealFileEvidence;
  readonly build: JsonObject; readonly observed: readonly RealFileEvidence[];
}
const logicalPins = (pins: readonly ImagePin[]) => pins.map(pin => ({ label: pin.label, digest: pin.digest, bytes: pin.bytes }));
const outside = (root: string, path: string): boolean => path !== root && !path.startsWith(root + '/') && !root.startsWith(path + '/');
function identifier(value: unknown): string {
  ownerCheck(typeof value === 'string' && /^[a-zA-Z0-9_.:-]{1,128}$/.test(value), 'preowner_identifier'); return value;
}
function pin(raw: unknown): ImagePin {
  const value = ownerObject(raw); ownerKeys(value, ['label', 'path', 'digest', 'bytes']);
  ownerCheck(typeof value.label === 'string' && value.label.length >= 1 && value.label.length <= 4096 && !value.label.startsWith('/') &&
    !value.label.includes('\\') && !/\p{Cc}/u.test(value.label) && value.label.split('/').every(part => !['', '.', '..'].includes(part)), 'preowner_pin_label');
  absoluteReal(value.path); shaReal(value.digest); ownerInteger(value.bytes, 536_870_912, 1);
  return value as unknown as ImagePin;
}
function pins(raw: unknown, maximum: number, sorted: boolean): readonly ImagePin[] {
  ownerCheck(Array.isArray(raw) && raw.length >= 1 && raw.length <= maximum, 'preowner_inventory_bound');
  const values = raw.map(pin);
  ownerCheck(new Set(values.map(value => value.path)).size === values.length && new Set(values.map(value => value.label)).size === values.length,
    'preowner_inventory_alias');
  if (sorted) ownerCheck(values.map(value => value.label).join('\n') === values.map(value => value.label).sort().join('\n'), 'preowner_inventory_order');
  return values;
}
/** Existing declared controller closure plus this actual entry/gate/selection helper. Import
 * resolution names only; it neither imports a candidate dynamically nor discovers an artifact.
 * The conservative inherited closure includes dormant host modules, which are NOT executed.
 */
export function requiredPreownerSmokeModules(): readonly { path: string; source: string }[] {
  return Object.freeze([...requiredCurrentImageControllerModules(), ...[
    'current-image-owner-preowner-smoke', 'current-image-owner-preowner-proof', 'current-image-owner-preowner-inputs',
  ].map(name => ({ path: fileURLToPath(new URL(name + '.js', import.meta.url)), source: `bridges/reference-host/test/${name}.ts` }))]);
}
export function validatePreownerSmokeSelection(raw: unknown): PreownerSmokeSelection {
  const value = ownerObject(raw);
  ownerKeys(value, ['schema', 'approvalId', 'sourceRoot', 'distributionHome', 'javaHome', 'outputDirectory', 'node', 'nodeVersion',
    'jvmExecutable', 'jvmClasspath', 'sourceInputs', 'controllerOutputs', 'buildRecord']);
  ownerCheck(value.schema === 'ktchecker-owner-preowner-smoke-selection/1', 'preowner_selection_schema'); identifier(value.approvalId);
  const root = absoluteReal(value.sourceRoot), home = absoluteReal(value.distributionHome), javaHome = absoluteReal(value.javaHome);
  const output = absoluteReal(value.outputDirectory);
  const freshStagePrefix = root + '/build/preview-handoffs/private-preview-';
  ownerCheck(home.startsWith(freshStagePrefix) && /^[a-zA-Z0-9_-]{8,96}\/install\/ktchecker$/.test(home.slice(freshStagePrefix.length)),
    'preowner_fresh_reviewed_stage_required');
  // The ordinary installDist output and earlier fixed preview installation cannot match this shape.
  ownerCheck(dirname(output) === root + '/build/ci-evidence' && /^owner-preowner-smoke-[a-zA-Z0-9_-]{8,96}$/.test(output.slice(output.lastIndexOf('/') + 1)),
    'preowner_fresh_evidence_path_required');
  const node = pin(value.node), java = pin(value.jvmExecutable), build = pin(value.buildRecord);
  ownerCheck(typeof value.nodeVersion === 'string' && /^24\.(?:2[0-9]|[3-9][0-9]|[1-9][0-9]{2,})\.[0-9]+$/.test(value.nodeVersion), 'preowner_node_version');
  ownerCheck(java.path === javaHome + '/bin/java' && !javaHome.includes(':'), 'preowner_selected_java');
  const classpath = pins(value.jvmClasspath, 128, false), sources = pins(value.sourceInputs, 8192, true), controllers = pins(value.controllerOutputs, 256, true);
  ownerCheck(classpath.every(item => dirname(item.path) === home + '/lib' && item.path.endsWith('.jar') && !item.path.includes(':')), 'preowner_classpath_shape');
  const all = [...sources, ...controllers, node, java, ...classpath, build];
  ownerCheck(new Set(all.map(item => item.path)).size === all.length && all.every(item => outside(output, item.path)) &&
    all.reduce((total, item) => total + item.bytes, 0) <= PREOWNER_INPUT_TOTAL_BYTES, 'preowner_input_alias_overlap_or_bound');
  ownerCheck(build.bytes <= PREOWNER_BUILD_RECORD_BYTES, 'preowner_build_record_bound');
  const requiredModules = requiredPreownerSmokeModules();
  ownerCheck(requiredModules.every(item => controllers.some(selected => selected.path === item.path) &&
    sources.some(selected => selected.label === item.source && selected.path === join(root, item.source))), 'preowner_controller_or_source_omitted');
  const requiredJvm = ['Main.kt', 'OwnerModelSessionTransport.kt', 'OwnerModelTransportPreownerGate.kt',
    'OwnerModelTransportFrames.kt', 'OwnerModelTransportClocks.kt', 'OwnerModelTransportSelection.kt', 'OwnerModelCommand.kt', 'OwnerModelLineage.kt'];
  ownerCheck(requiredJvm.every(name => {
    const label = 'modules/cli/src/main/kotlin/dev/ktchecker/cli/' + name;
    return sources.some(selected => selected.label === label && selected.path === join(root, label));
  }), 'preowner_jvm_boundary_source_omitted');
  return frozen(structuredClone(value)) as unknown as PreownerSmokeSelection;
}
export function validatePreownerBuildRecord(raw: unknown, choice: PreownerSmokeSelection): JsonObject {
  const value = ownerObject(raw);
  ownerKeys(value, ['schema', 'status', 'approvalId', 'buildId', 'sourceDigest', 'sourceInputsDigest', 'jvmInputsDigest', 'controllerOutputsDigest',
    'nodeDigest', 'nodeVersion', 'buildEvidenceDigest', 'jvmBridgeBaseReviewDigest', 'preownerSuccessorReviewDigest', 'hostTransportReviewDigest']);
  ownerCheck(value.schema === 'ktchecker-owner-approved-preowner-smoke-build/1' && value.status === 'verified_success' &&
    value.approvalId === choice.approvalId && value.sourceInputsDigest === digest(canonical(logicalPins(choice.sourceInputs))) &&
    value.jvmInputsDigest === digest(canonical(logicalPins([choice.jvmExecutable, ...choice.jvmClasspath]))) &&
    value.controllerOutputsDigest === digest(canonical(logicalPins(choice.controllerOutputs))) && value.nodeDigest === choice.node.digest &&
    value.nodeVersion === choice.nodeVersion, 'preowner_successful_build_linkage_unproven');
  identifier(value.buildId);
  for (const key of ['sourceDigest', 'sourceInputsDigest', 'jvmInputsDigest', 'controllerOutputsDigest', 'nodeDigest', 'buildEvidenceDigest',
    'jvmBridgeBaseReviewDigest', 'preownerSuccessorReviewDigest', 'hostTransportReviewDigest']) shaReal(value[key]);
  return frozen(structuredClone(value));
}
/** Actual read-only pin verification under the SAME first120 ledger, before any spawn. */
export function readPreownerSmokeInputs(path: string, expectedDigest: string, clocks: OwnerSessionClocks): PreparedPreownerSmokeInputs {
  clocks.checkpoint(); absoluteReal(path); shaReal(expectedDigest);
  const selected = readRealFile(path, PREOWNER_SELECTION_BYTES);
  ownerCheck(selected.evidence.digest === expectedDigest, 'preowner_selection_changed'); clocks.checkpoint();
  const selection = validatePreownerSmokeSelection(strictOwnerJson(selected.bytes));
  ownerCheck(outside(selection.outputDirectory, path) && selection.node.path === process.execPath && selection.nodeVersion === process.versions.node,
    'preowner_selection_or_running_node');
  const record = readRealFile(selection.buildRecord.path, PREOWNER_BUILD_RECORD_BYTES);
  ownerCheck(record.evidence.digest === selection.buildRecord.digest && record.evidence.bytes === selection.buildRecord.bytes, 'preowner_build_record_changed');
  const build = validatePreownerBuildRecord(strictOwnerJson(record.bytes), selection);
  const observed: RealFileEvidence[] = [];
  for (const selectedPin of [...selection.sourceInputs, ...selection.controllerOutputs, selection.node, selection.jvmExecutable, ...selection.jvmClasspath, selection.buildRecord]) {
    clocks.checkpoint(); const actual = hashRealFile(selectedPin.path);
    ownerCheck(actual.digest === selectedPin.digest && actual.bytes === selectedPin.bytes, 'preowner_input_changed'); observed.push(actual);
  }
  clocks.checkpoint();
  ownerCheck(new Set(observed.map(item => item.fileKey)).size === observed.length && observed.every(item => item.fileKey !== selected.evidence.fileKey),
    'preowner_physical_input_alias');
  return frozen({ selection, selectionEvidence: selected.evidence, build, observed });
}
export function recheckPreownerSmokeInputs(prepared: PreparedPreownerSmokeInputs, checkpoint: () => void): void {
  for (const before of [prepared.selectionEvidence, ...prepared.observed]) {
    checkpoint(); const after = hashRealFile(before.path);
    ownerCheck(after.fileKey === before.fileKey && after.digest === before.digest && after.bytes === before.bytes, 'preowner_input_drift');
  }
  checkpoint();
}
export function preownerJvmArguments(choice: PreownerSmokeSelection, sentinel: RealFileEvidence): readonly string[] {
  ownerCheck(sentinel.path === choice.outputDirectory + '/unread-owner-selection.json' && sentinel.digest === digest(PREOWNER_SENTINEL_TEXT) &&
    sentinel.bytes === Buffer.byteLength(PREOWNER_SENTINEL_TEXT, 'utf8'), 'preowner_invalid_sentinel');
  return Object.freeze(['-Xmx512m', '-XX:MaxMetaspaceSize=256m', '-Duser.home=' + join(choice.outputDirectory, 'owner-environment-home'),
    '-Djava.io.tmpdir=' + join(choice.outputDirectory, 'owner-environment-tmp'), '-cp', choice.jvmClasspath.map(item => item.path).join(':'),
    'dev.ktchecker.cli.MainKt', 'owner-model-session', '--owner-action', '--authorize-execution', '--request', sentinel.path, '--selection-digest', sentinel.digest]);
}
