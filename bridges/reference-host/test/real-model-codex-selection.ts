/** Fixed source/data selection for the NEW opt-in experiment. No metadata command, resolver,
 * model-list, account query, thread lookup or fallback is performed here. */
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { canonical, digest } from '../src/journal.js';
import { absoluteReal, exactReal, hashRealFile, jsonReal, objectReal, readRealFile, requireReal, sameReal,
  shaReal, type RealFileEvidence } from './real-model-run-support.js';

export const REAL_CODEX_MODEL = 'gpt-6-astra';
export const REAL_CODEX_EFFORT = 'max';
export const REAL_CODEX_ELF_DIGEST = 'sha256:12eb3e81114588aca3b7998f4f19e8997b056aca08e57a7ca7c8a3ec8c652aad';
export const REAL_CODEX_ELF_BYTES = 289_101_384;
export const REAL_CODEX_SCHEMA_DIGEST = 'sha256:175e875155d24f485e39e0a3a0fee5870d7fdcbdadd5d182c27ace0c1aca30dd';
export const REAL_CODEX_SCHEMA_AGGREGATE = 'sha256:6ae4bb6e18b7978d06f6fe53ffbf89090333c6d14608210f6b8d0e52237f0fc3';

export interface RealCodexSelection {
  schemaVersion: 1; model: typeof REAL_CODEX_MODEL; effort: typeof REAL_CODEX_EFFORT;
  /** Explicit owner-selected location only; actual executable bytes remain exactly pinned. */
  executable: string; executableDigest: typeof REAL_CODEX_ELF_DIGEST; executableBytes: typeof REAL_CODEX_ELF_BYTES;
  schemaManifest: string; schemaManifestDigest: typeof REAL_CODEX_SCHEMA_DIGEST;
  schemaAggregateDigest: typeof REAL_CODEX_SCHEMA_AGGREGATE;
}
export function loadRealCodexSelection(path: string, expectedDigest: string): { selection: RealCodexSelection; evidence: RealFileEvidence } {
  shaReal(expectedDigest);
  const read = readRealFile(path, 64 * 1024);
  requireReal(read.evidence.digest === expectedDigest, 'real_model_codex_selection_changed');
  const value = objectReal(jsonReal(read.bytes));
  exactReal(value, ['schemaVersion', 'model', 'effort', 'executable', 'executableDigest', 'executableBytes',
    'schemaManifest', 'schemaManifestDigest', 'schemaAggregateDigest']);
  requireReal(value.schemaVersion === 1 && value.model === REAL_CODEX_MODEL && value.effort === REAL_CODEX_EFFORT &&
    value.executableDigest === REAL_CODEX_ELF_DIGEST && value.executableBytes === REAL_CODEX_ELF_BYTES &&
    value.schemaManifestDigest === REAL_CODEX_SCHEMA_DIGEST && value.schemaAggregateDigest === REAL_CODEX_SCHEMA_AGGREGATE,
  'real_model_codex_exact_selection_required');
  absoluteReal(value.executable); absoluteReal(value.schemaManifest);
  return { selection: value as unknown as RealCodexSelection, evidence: read.evidence };
}

export function preflightRealCodex(selection: RealCodexSelection): { executable: RealFileEvidence; schemaManifest: RealFileEvidence; schemas: RealFileEvidence[] } {
  const executable = hashRealFile(selection.executable);
  requireReal(executable.digest === REAL_CODEX_ELF_DIGEST && executable.bytes === REAL_CODEX_ELF_BYTES, 'real_model_codex_executable_changed');
  const read = readRealFile(selection.schemaManifest, 128 * 1024);
  requireReal(read.evidence.digest === REAL_CODEX_SCHEMA_DIGEST, 'real_model_codex_schema_manifest_changed');
  const manifest = objectReal(jsonReal(read.bytes));
  requireReal(manifest.aggregate_digest === REAL_CODEX_SCHEMA_AGGREGATE && Array.isArray(manifest.entries) && manifest.entries.length <= 1024,
    'real_model_codex_schema_selection_changed');
  const source = objectReal(manifest.source_executable);
  // The fixed manifest retains its historical producer location as provenance, not a launch
  // location. Moving identical pinned bytes requires a NEW explicit selection, never PATH lookup.
  absoluteReal(source.executable);
  requireReal(source.digest === executable.digest && source.digest === REAL_CODEX_ELF_DIGEST && source.before_after_unchanged === true,
    'real_model_codex_schema_producer_changed');
  const entries = manifest.entries.map(objectReal);
  const schemas = ['v2/ThreadStartParams.json', 'v2/ThreadStartResponse.json', 'v2/TurnStartParams.json',
    'v2/ThreadInjectItemsParams.json', 'v2/ThreadItemsListParams.json', 'v2/ThreadItemsListResponse.json', 'v2/ModelReroutedNotification.json'].map(path => {
    const selected = entries.filter(entry => entry.path === path);
    requireReal(selected.length === 1, 'real_model_codex_schema_entry_missing');
    const file = readRealFile(join(dirname(selection.schemaManifest), 'experimental-schema', path), 1024 * 1024, false);
    requireReal(file.evidence.digest === selected[0]!.digest && file.evidence.bytes === selected[0]!.bytes,
      'real_model_codex_schema_bytes_changed');
    const schema = objectReal(jsonReal(file.bytes)), properties = objectReal(schema.properties);
    const required = path.endsWith('/ThreadStartParams.json') ? ['model', 'allowProviderModelFallback', 'config', 'historyMode'] :
      path.endsWith('/ThreadStartResponse.json') ? ['model', 'reasoningEffort', 'thread'] :
      path.endsWith('/TurnStartParams.json') ? ['threadId', 'input', 'effort', 'toolOutput'] :
      path.endsWith('/ThreadInjectItemsParams.json') ? ['threadId', 'items'] :
      path.endsWith('/ThreadItemsListParams.json') ? ['threadId', 'turnId', 'limit'] :
      path.endsWith('/ModelReroutedNotification.json') ? ['threadId', 'turnId', 'fromModel', 'toModel', 'reason'] : ['data', 'nextCursor'];
    requireReal(required.every(key => Object.hasOwn(properties, key)), 'real_model_codex_schema_feature_unavailable');
    return file.evidence;
  });
  return { executable, schemaManifest: read.evidence, schemas };
}

/** Actual disk code identity only; no claim that disk hashes attest loaded memory or its source. */
export function captureRealCompanionImage(): { digest: string; files: RealFileEvidence[]; node: RealFileEvidence; nodeVersion: string } {
  const [major, minor] = process.versions.node.split('.').map(Number);
  requireReal(major === 24 && minor !== undefined && minor >= 20, 'real_model_node_version_unqualified');
  const local = ['demo-real-model-codex.js', 'real-model-run-support.js', 'real-model-codex-selection.js', 'real-model-delivery.js',
    'application-evidence.js', 'delivery-receipts.js', '../src/adapter.js', '../src/broker.js', '../src/journal.js',
    '../src/codex-host.js', '../src/codex-store.js', '../src/codex-app-server.js', '../src/codex-observation.js',
    '../src/context-projection.js', '../src/coverage-validation-projection.js'];
  const paths = local.map(path => fileURLToPath(new URL(path, import.meta.url)));
  paths.push(fileURLToPath(import.meta.resolve('@ktchecker/mcp-preview/rpc')), fileURLToPath(import.meta.resolve('@ktchecker/mcp-preview/private-state')));
  requireReal(new Set(paths).size === paths.length, 'real_model_companion_code_alias');
  const files = paths.map(path => hashRealFile(path, 2 * 1024 * 1024)), node = hashRealFile(process.execPath);
  return { digest: digest(canonical({ files, node, nodeVersion: process.versions.node })), files, node, nodeVersion: process.versions.node };
}
export function verifyRealCompanionImage(image: ReturnType<typeof captureRealCompanionImage>): void {
  sameReal(captureRealCompanionImage(), image, 'real_model_companion_image_changed');
}
