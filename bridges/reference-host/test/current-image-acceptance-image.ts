/** Explicit fresh image selection only. Hashes disk bytes, not loaded memory; no build,
 * installed-version command, compiler probe, resolver, git subprocess or model call.
 */
import { fileURLToPath } from 'node:url';
import { canonical, digest } from '../src/journal.js';
import { absoluteReal, hashRealFile, jsonReal, readRealFile, REAL_MODEL_TREE_BYTES } from './real-model-run-support.js';
import { REAL_CODEX_ELF_BYTES, REAL_CODEX_ELF_DIGEST, REAL_CODEX_MODEL, REAL_CODEX_EFFORT } from './real-model-codex-selection.js';
import { check, frozen, id, integer, record, same, sha } from './current-image-acceptance-evidence.js';

export interface ImagePin { readonly label: string; readonly path: string; readonly digest: string; readonly bytes: number }
export interface CurrentImageSelection {
  readonly schema: 'ktchecker-current-image-owner-selection/1'; readonly approvalId: string;
  readonly sourceInputs: readonly ImagePin[]; readonly installedOutputs: readonly ImagePin[]; readonly controllerOutputs: readonly ImagePin[];
  /** Exact owner-selected compiler JAR path order, backed by installedOutputs pins. */
  readonly compilerArtifactPaths: readonly string[];
  readonly node: ImagePin; readonly nodeVersion: string; readonly codex: ImagePin;
  readonly buildRecord: ImagePin; readonly ownerCodeImageDigest: string;
}
export interface CurrentImageEvidence {
  readonly selectionDigest: string; readonly buildRecordDigest: string; readonly sourceInputsDigest: string;
  readonly installedOutputsDigest: string; readonly controllerOutputsDigest: string; readonly ownerCodeImageDigest: string;
  readonly compilerArtifactOrderDigest: string;
  readonly nodeDigest: string; readonly nodeVersion: string; readonly codexDigest: string;
  readonly sourceLinkage: 'owner_pinned_successful_build_record_linkage_not_reproducibility_or_loaded_memory_attestation';
  readonly loadedImageAttestation: false;
}
const logicalPins = (pins: readonly ImagePin[]) => pins.map(pin => ({ label: pin.label, digest: pin.digest, bytes: pin.bytes }));

export function validateImageSelection(selection: CurrentImageSelection, build: unknown): Omit<CurrentImageEvidence, 'selectionDigest'> {
  check(selection.schema === 'ktchecker-current-image-owner-selection/1', 'image_selection_schema'); id(selection.approvalId);
  const validate = (pins: readonly ImagePin[]) => {
    check(Array.isArray(pins) && pins.length > 0 && pins.length <= 8192, 'image_inventory_bound');
    let bytes = 0;
    for (const pin of pins) {
      check(typeof pin.label === 'string' && pin.label.length > 0 && pin.label.length <= 4096 && !pin.label.startsWith('/') &&
        !pin.label.includes('\\') && pin.label.split('/').every((part: string) => !['', '.', '..'].includes(part)), 'image_label');
      absoluteReal(pin.path); sha(pin.digest); integer(pin.bytes, 1);
      check(pin.bytes <= 512 * 1024 * 1024 && (bytes += pin.bytes) <= REAL_MODEL_TREE_BYTES, 'image_byte_bound');
    }
    check(new Set(pins.map(pin => pin.label)).size === pins.length && new Set(pins.map(pin => pin.path)).size === pins.length, 'image_inventory_alias');
    same(pins.map(pin => pin.label), pins.map(pin => pin.label).sort(), 'image_inventory_order');
  };
  validate(selection.sourceInputs); validate(selection.installedOutputs); validate(selection.controllerOutputs);
  validate([selection.node]); validate([selection.codex]); validate([selection.buildRecord]);
  const all = [...selection.sourceInputs, ...selection.installedOutputs, ...selection.controllerOutputs, selection.node, selection.codex, selection.buildRecord];
  check(new Set(all.map(pin => pin.path)).size === all.length, 'image_role_alias');
  check(selection.codex.digest === REAL_CODEX_ELF_DIGEST && selection.codex.bytes === REAL_CODEX_ELF_BYTES &&
    /^24\.(?:2[0-9]|[3-9][0-9]|[1-9][0-9]{2,})\.[0-9]+$/.test(selection.nodeVersion), 'image_runtime_selection');
  sha(selection.ownerCodeImageDigest);
  check(Array.isArray(selection.compilerArtifactPaths) && selection.compilerArtifactPaths.length > 0 && selection.compilerArtifactPaths.length <= 128 &&
    new Set(selection.compilerArtifactPaths).size === selection.compilerArtifactPaths.length, 'image_compiler_artifact_order');
  const compilerPins = selection.compilerArtifactPaths.map(path => {
    check(absoluteReal(path).endsWith('.jar'), 'image_compiler_artifact_kind');
    const pin = selection.installedOutputs.find(pin => pin.path === path); check(pin, 'image_compiler_artifact_unpinned'); return pin;
  });
  const compilerArtifactOrderDigest = digest(canonical(logicalPins(compilerPins)));
  const sourceInputsDigest = digest(canonical(logicalPins(selection.sourceInputs))), installedOutputsDigest = digest(canonical(logicalPins(selection.installedOutputs))),
    controllerOutputsDigest = digest(canonical(logicalPins(selection.controllerOutputs))), value = record(build);
  // This record MUST be independently pinned by the owner after a successful admitted build.
  // A checkout commit, matching artifact name, JSON flag or this verifier cannot manufacture that provenance.
  check(value.schema === 'ktchecker-owner-approved-current-image-build/1' && value.status === 'verified_success' &&
    value.ownerApprovalId === selection.approvalId && value.sourceInputsDigest === sourceInputsDigest &&
    value.installedOutputsDigest === installedOutputsDigest && value.controllerOutputsDigest === controllerOutputsDigest &&
    value.compilerArtifactOrderDigest === compilerArtifactOrderDigest &&
    value.ownerCodeImageDigest === selection.ownerCodeImageDigest && value.nodeDigest === selection.node.digest &&
    value.nodeVersion === selection.nodeVersion && value.model === REAL_CODEX_MODEL && value.effort === REAL_CODEX_EFFORT,
  'installed_source_build_linkage_unproven');
  id(value.buildId); sha(value.buildEvidenceDigest);
  return frozen({ buildRecordDigest: selection.buildRecord.digest, sourceInputsDigest, installedOutputsDigest, controllerOutputsDigest,
    compilerArtifactOrderDigest, ownerCodeImageDigest: selection.ownerCodeImageDigest, nodeDigest: selection.node.digest, nodeVersion: selection.nodeVersion,
    codexDigest: selection.codex.digest, sourceLinkage: 'owner_pinned_successful_build_record_linkage_not_reproducibility_or_loaded_memory_attestation',
    loadedImageAttestation: false });
}

export function requiredCurrentImageControllerModules(): { path: string; source: string }[] {
  const tests = ['current-image-acceptance-image', 'current-image-acceptance-evidence', 'current-image-acceptance-runner',
    'current-image-acceptance-launch', 'current-image-acceptance-policy', 'current-image-owner-wire', 'current-image-owner-clocks',
    'current-image-owner-channel', 'current-image-owner-selection', 'current-image-owner-transport',
    'real-model-run-support', 'real-model-codex-selection', 'real-model-delivery', 'application-evidence', 'delivery-receipts'];
  const sources = ['adapter', 'broker', 'journal', 'private-database', 'types', 'codex-host', 'codex-store', 'codex-app-server',
    'codex-observation', 'context-projection', 'coverage-validation-projection'];
  return [
    ...tests.map(name => ({ path: fileURLToPath(new URL(name + '.js', import.meta.url)), source: `bridges/reference-host/test/${name}.ts` })),
    ...sources.map(name => ({ path: fileURLToPath(new URL('../src/' + name + '.js', import.meta.url)), source: `bridges/reference-host/src/${name}.ts` })),
    ...['rpc', 'private-state'].map(name => ({ path: fileURLToPath(import.meta.resolve('@ktchecker/mcp-preview/' + name)), source: `bridges/mcp/src/${name}.ts` })),
  ];
}
export function assertCurrentImageControllerClosure(selection: CurrentImageSelection): void {
  const required = requiredCurrentImageControllerModules();
  check(required.every(module => selection.controllerOutputs.some(pin => pin.path === module.path)), 'current_acceptance_driver_image_omitted');
  check(required.every(module => selection.sourceInputs.some(pin => pin.label === module.source)), 'current_acceptance_source_omitted');
}
/** Only this disk-verifying class is accepted by the actual runner. Its own selected module files
 * and the existing delivery/projection/controller modules cannot be omitted from image capture.
 * Still a bounded declared code closure, not attestation of dynamic/transformed/native/JDK memory.
 */
export class PinnedCurrentImage {
  readonly evidence: CurrentImageEvidence;
  private constructor(private readonly selection: CurrentImageSelection, readonly selectionPath: string,
    selectionDigest: string, build: unknown) {
    this.evidence = frozen({ selectionDigest, ...validateImageSelection(selection, build) });
    assertCurrentImageControllerClosure(selection);
    check(selection.node.path === process.execPath && selection.nodeVersion === process.versions.node, 'current_node_not_selected');
    this.assertCurrent();
  }
  static open(selectionPath: string, expectedSelectionDigest: string): PinnedCurrentImage {
    const read = readRealFile(selectionPath, 2 * 1024 * 1024); check(read.evidence.digest === sha(expectedSelectionDigest), 'image_selection_changed');
    const selection = frozen(jsonReal(read.bytes) as CurrentImageSelection), pin = selection.buildRecord;
    const build = readRealFile(pin.path, 1024 * 1024);
    check(build.evidence.digest === pin.digest && build.evidence.bytes === pin.bytes, 'build_record_changed');
    return new PinnedCurrentImage(selection, selectionPath, expectedSelectionDigest, jsonReal(build.bytes));
  }
  assertCurrent(): CurrentImageEvidence {
    check(readRealFile(this.selectionPath, 2 * 1024 * 1024).evidence.digest === this.evidence.selectionDigest, 'image_selection_changed');
    for (const pin of [...this.selection.sourceInputs, ...this.selection.installedOutputs, ...this.selection.controllerOutputs,
      this.selection.node, this.selection.codex, this.selection.buildRecord]) {
      const actual = hashRealFile(pin.path);
      check(actual.digest === pin.digest && actual.bytes === pin.bytes, 'current_image_bytes_changed');
    }
    return this.evidence;
  }
  assertCompilerArtifactPaths(paths: readonly string[]): void {
    same(paths, this.selection.compilerArtifactPaths, 'current_compiler_artifact_order_changed');
    // assertCurrent already rehashes every installed pin; this check binds the exact invocation selection to that inventory.
    check(paths.every(path => this.selection.installedOutputs.some(pin => pin.path === path)), 'current_compiler_artifact_unpinned');
  }
  assertLaunchExecutable(path: string): Readonly<ImagePin> {
    check(absoluteReal(path) === this.selection.codex.path, 'host_launch_executable_not_selected');
    // Before/after byte verification, not a claim about spawn's loaded image or provider routing.
    this.assertCurrent(); return frozen({ ...this.selection.codex });
  }
  privateInputLocations(): readonly string[] {
    return frozen([this.selectionPath, ...this.selection.sourceInputs.map(pin => pin.path), ...this.selection.installedOutputs.map(pin => pin.path),
      ...this.selection.controllerOutputs.map(pin => pin.path), this.selection.node.path, this.selection.codex.path, this.selection.buildRecord.path]);
  }
  assertInputsOutside(runRoot: string, additional: readonly string[]): void {
    absoluteReal(runRoot);
    check([...this.privateInputLocations(), ...additional].every(input => {
      const path = absoluteReal(input); return path !== runRoot && !path.startsWith(runRoot + '/');
    }), 'fresh_launch_inputs_inside_run_forbidden');
  }
}
