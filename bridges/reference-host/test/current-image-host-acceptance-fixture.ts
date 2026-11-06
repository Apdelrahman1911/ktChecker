/** Fixed PUBLIC source DATA for a NEW main+test friend patch/fix, never a fixture/owner replay.
 * No source paths are read, no JVM/Gradle/provider is run, and no owner capability is created.
 * The public Kotlin task wiring follows RealProducedJvmFixture's reviewed friend branch.
 */
import { canonical, digest } from '../src/journal.js';
import type { ClientBinding } from '../src/types.js';
import { check, frozen, id, integer, same, sha, type CompletedPatch, type SnapshotLineage } from './current-image-acceptance-evidence.js';
import type { AcceptanceAuthorization } from './current-image-acceptance-runner.js';

export const CURRENT_IMAGE_PUBLIC_FIXTURE = 'ktchecker-current-image-public-friend-patch-fix/1';
export const CURRENT_IMAGE_TRUSTED_HOST_POLICY = 'explicit_trusted_host_public_fixture_no_read_isolation_attestation';
export const CURRENT_IMAGE_PUBLIC_FINDING = Object.freeze({ ruleId: 'ktc.compiler.common.unresolved_reference',
  upstreamId: 'UNRESOLVED_REFERENCE', path: 'src/test/kotlin/Use.kt' });
export const CURRENT_IMAGE_PUBLIC_COMPILER_RULES: readonly string[] = Object.freeze([
  'ktc.compiler.common.unresolved_reference', 'ktc.compiler.common.unresolved_import',
  'ktc.compiler.common.return_type_mismatch', 'ktc.compiler.common.initializer_type_mismatch',
  'ktc.compiler.common.assignment_type_mismatch', 'ktc.compiler.common.argument_type_mismatch',
  'ktc.compiler.common.null_for_nonnull_type', 'ktc.compiler.common.unsafe_call',
  'ktc.compiler.common.no_value_for_parameter', 'ktc.compiler.common.too_many_arguments',
  'ktc.compiler.common.named_parameter_not_found', 'ktc.compiler.common.argument_passed_twice',
  'ktc.compiler.common.non_vararg_spread', 'ktc.compiler.common.spread_of_nullable',
].sort());

const settings = `pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
rootProject.name = 'ktc_current_image_public_friend_patch_fix'
`;
const build = `import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins { id 'org.jetbrains.kotlin.jvm' version '2.4.21' }
repositories { mavenCentral() }
kotlin { jvmToolchain(21) }
def actualCompilations = kotlin.target.compilations
def producer = actualCompilations.getByName('main')
def consumer = actualCompilations.getByName('test')
def producerTask = producer.compileTaskProvider.get()
def consumerTask = consumer.compileTaskProvider.get()
def external = configurations.getByName(consumer.compileDependencyConfigurationName)
def producerOutput = producer.compileTaskProvider.flatMap { it.destinationDirectory }
// Pre-capture public Kotlin-only task selection; never filter a returned export/model.
consumerTask.libraries.setFrom(producerOutput, external)
consumerTask.libraries.disallowChanges()
consumerTask.friendPaths.setFrom(producerOutput)
consumerTask.friendPaths.disallowChanges()
tasks.withType(KotlinCompile).configureEach { compilerOptions.jvmTarget.set(JvmTarget.JVM_21) }
producerTask.compilerOptions.moduleName.set('ktc_current_image_public_main')
consumerTask.compilerOptions.moduleName.set('ktc_current_image_public_test')
gradle.projectsEvaluated {
    if (actualCompilations.toList().collect { it.name }.sort() != ['main', 'test'].sort())
        throw new GradleException('Connected compilation domain changed')
    if (!producer.allAssociatedCompilations.isEmpty()) throw new GradleException('Producer has unexpected association')
    def associations = consumer.allAssociatedCompilations.toList()
    if (associations.size() != 1 || !associations[0].is(producer))
        throw new GradleException('Actual friend association differs from selected fixture')
    def output = producerTask.destinationDirectory.get().asFile
    if (output.exists()) throw new GradleException('Fresh exporter cannot borrow preexisting produced output')
    if (consumerTask.libraries.files.toList() != [output] + external.files.toList())
        throw new GradleException('Whole actual task classpath edge/order differs')
    if (consumerTask.friendPaths.files.toList() != [output])
        throw new GradleException('Actual friend path vector differs')
    if (producerTask.sources.asFileTree.files.isEmpty() || consumerTask.sources.asFileTree.files.isEmpty())
        throw new GradleException('Both connected compilations must be genuinely nonempty')
    tasks.withType(KotlinCompile).configureEach {
        pluginClasspath.setFrom([])
        pluginClasspath.disallowChanges()
    }
}
`;
const properties = 'org.gradle.java.installations.auto-detect=false\norg.gradle.java.installations.auto-download=false\n';
const main = 'package sample\n\ninternal fun declaredAcrossFiles(): String = "public-fixture"\n';
const before = 'package sample\n\nfun useAcrossFiles(): String = missingDeclaration()\n';
const after = 'package sample\n\nfun useAcrossFiles(): String = declaredAcrossFiles()\n';

/** Whole immutable project input, not just changed paths; BOTH compilation source sets remain. */
export function currentImagePublicFixtureFiles(ordinal: number): CompletedPatch['files'] {
  check(ordinal === 1 || ordinal === 2, 'driver_fixture_ordinal');
  const files: Record<string, string> = { 'settings.gradle': settings, 'build.gradle': build, 'gradle.properties': properties,
    'src/main/kotlin/Api.kt': main, [CURRENT_IMAGE_PUBLIC_FINDING.path]: ordinal === 1 ? before : after };
  return frozen(Object.keys(files).sort().map(path => ({ rootId: 'project', path,
    contentBase64: Buffer.from(files[path]!, 'utf8').toString('base64'), executable: false })));
}
export function currentImagePublicFixtureDigest(): string {
  return digest(canonical({ fixture: CURRENT_IMAGE_PUBLIC_FIXTURE, roots: [{ id: 'project', mountPath: '' }],
    patches: [currentImagePublicFixtureFiles(1), currentImagePublicFixtureFiles(2)],
    finding: CURRENT_IMAGE_PUBLIC_FINDING, compilerRules: CURRENT_IMAGE_PUBLIC_COMPILER_RULES }));
}
export interface CurrentImageDriverConsent {
  readonly schema: 'ktchecker-current-image-thin-driver-consent/1';
  readonly fixture: typeof CURRENT_IMAGE_PUBLIC_FIXTURE; readonly fixtureDigest: string;
  readonly trustedHostPolicy: typeof CURRENT_IMAGE_TRUSTED_HOST_POLICY;
  readonly retainedLifetimeAccepted: true;
}
/** Root-pinned consent DATA in the launch-selected independent document, NOT proof of a sandbox.
 * Approval flags cannot manufacture a source review, test result, execution grant or account access.
 */
export function validateCurrentImageDriverConsent(value: CurrentImageDriverConsent): void {
  same(Object.keys(value).sort(), ['fixture', 'fixtureDigest', 'retainedLifetimeAccepted', 'schema', 'trustedHostPolicy'], 'driver_consent_schema');
  check(value.schema === 'ktchecker-current-image-thin-driver-consent/1' && value.fixture === CURRENT_IMAGE_PUBLIC_FIXTURE &&
    value.fixtureDigest === currentImagePublicFixtureDigest() && value.trustedHostPolicy === CURRENT_IMAGE_TRUSTED_HOST_POLICY &&
    value.retainedLifetimeAccepted === true, 'driver_explicit_public_fixture_consent_required');
}
export type CurrentImageFixtureSchedule = readonly Readonly<{ patchId: string; taskId: string }>[];
export function currentImageFixtureSchedule(authorization: AcceptanceAuthorization): CurrentImageFixtureSchedule {
  same(authorization.compilerRuleIds, CURRENT_IMAGE_PUBLIC_COMPILER_RULES, 'driver_exact_public_compiler_rules');
  check(authorization.scheduledPatches.length === 2, 'driver_patch_fix_required');
  const schedule = authorization.scheduledPatches.map((patch, index) => {
    same(Object.keys(patch).sort(), ['findings', 'patchId', 'taskId'], 'driver_schedule_keys');
    id(patch.patchId); id(patch.taskId);
    same(patch.findings, [{ ...CURRENT_IMAGE_PUBLIC_FINDING, present: index === 0 }], 'driver_exact_public_finding_schedule');
    return { patchId: patch.patchId, taskId: patch.taskId };
  });
  check(schedule[0]!.patchId !== schedule[1]!.patchId, 'driver_distinct_patch_ids'); return frozen(schedule);
}
export function captureCurrentImagePublicPatch(schedule: CurrentImageFixtureSchedule, ordinal: number,
  previous: SnapshotLineage | null, binding: Readonly<ClientBinding>): CompletedPatch {
  check(schedule.length === 2 && (ordinal === 1 || ordinal === 2) && (ordinal === 1 ? previous === null : previous !== null),
    'driver_patch_sequence');
  same(Object.keys(binding).sort(), ['clientId', 'generation', 'lineageId', 'ownerId', 'sessionId', 'workspaceId', 'worktreeId'], 'driver_public_binding_keys');
  for (const field of ['ownerId', 'workspaceId', 'worktreeId', 'clientId', 'lineageId', 'sessionId'] as const) id(binding[field]);
  check(binding.generation === 1, 'driver_fresh_binding_generation');
  if (previous !== null) { sha(previous.snapshotId); integer(previous.headRevision, 1); same(previous.binding, binding, 'driver_snapshot_binding'); }
  const selected = schedule[ordinal - 1]!; id(selected.patchId); id(selected.taskId);
  return frozen({ patchId: selected.patchId, taskId: selected.taskId, ordinal, binding: { ...binding },
    baseSnapshotId: previous?.snapshotId ?? null, expectedHeadRevision: previous?.headRevision ?? 0,
    roots: [{ id: 'project', mountPath: '' }], files: currentImagePublicFixtureFiles(ordinal) });
}
