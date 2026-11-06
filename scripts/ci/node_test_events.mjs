/** Bounded supplementary Node reporter. JUnit omits a parent's own terminal
 * outcome when it has children. Preserve the actual terminal events instead of
 * inferring success from their children. This private stream is never uploaded;
 * run_bridge_checks.py projects only source-known names and fixed error codes.
 * No imports, executable selection, environment reads, hooks or test execution.
 */
const MAX_EVENTS = 10_000, MAX_BYTES = 8 * 1024 * 1024;
const failureTypes = new Set(['testCodeFailure', 'cancelledByParent', 'testTimeoutFailure',
  'subtestsFailed', 'testAborted', 'uncaughtException', 'unhandledRejection']);
const reasonCodes = new Set(['private_state_platform_unqualified', 'ERR_TEST_FAILURE', 'ERR_ASSERTION',
  'ENOENT', 'EACCES', 'EPERM', 'private_path_unsupported', 'private_path_alias_unsupported',
  'private_acl_unavailable', 'unsafe_state_path', 'state_permissions', 'private_parent_permissions',
  'state_directory_in_use', 'private_operation_unsupported', 'native_windows_required',
  'private_helper_command_bound', 'native_windows_control_setup_failed']);
const reject = () => { throw new Error('node_test_event_shape_or_bound'); };
function field(value, key) {
  if (value === null || typeof value !== 'object') return undefined;
  const descriptor = Object.getOwnPropertyDescriptor(value, key);
  if (descriptor && !Object.hasOwn(descriptor, 'value')) reject();
  return descriptor?.value;
}
function directive(value) {
  if (value === undefined) return { active: false, junit: false };
  if (value !== true && typeof value !== 'string') reject();
  // Node counts even an empty-string directive, while its JUnit reporter tests
  // the value's truthiness. Retain both facts, never relabel it as a passed test.
  return { active: true, junit: Boolean(value) };
}
export default async function* nodeTestEvents(source) {
  let ordinal = 0, bytes = 0;
  const line = value => {
    const text = JSON.stringify(value) + '\n'; // Fresh records with primitives only; no user toJSON/getters.
    const size = Buffer.byteLength(text); bytes += size;
    if (size > 65_536 || bytes > MAX_BYTES) reject();
    return text;
  };
  yield line({ schema: 'ktchecker-node-terminal-events/1', node_version: process.versions.node });
  for await (const event of source) {
    const kind = field(event, 'type');
    if (kind !== 'test:pass' && kind !== 'test:fail') continue;
    if (++ordinal > MAX_EVENTS) reject();
    const data = field(event, 'data'), details = field(data, 'details');
    const name = field(data, 'name'), file = field(data, 'file') ?? null;
    const nesting = field(data, 'nesting'), type = field(details, 'type');
    if (typeof name !== 'string' || name.length > 4096 ||
        (file !== null && (typeof file !== 'string' || file.length > 8192)) ||
        !Number.isSafeInteger(nesting) || nesting < 0 || nesting > 128 ||
        (type !== 'test' && type !== 'suite')) reject();
    const skip = directive(field(data, 'skip')), todo = directive(field(data, 'todo'));
    if (skip.active && todo.active) reject();
    let error = field(details, 'error');
    const failureType = field(error, 'failureType');
    const codes = new Set(), seen = new Set();
    for (let depth = 0; error !== null && typeof error === 'object' && depth < 4 && !seen.has(error); depth++) {
      seen.add(error);
      const code = field(error, 'code');
      if (reasonCodes.has(code)) codes.add(code);
      error = field(error, 'cause');
    }
    yield line({ ordinal, name, file, nesting, test_type: type, event: kind === 'test:pass' ? 'pass' : 'fail',
      skip: skip.active, todo: todo.active, junit_skip: skip.junit, junit_todo: todo.junit,
      failure_type: failureTypes.has(failureType) ? failureType : null, reason_codes: [...codes].sort() });
  }
  yield line({ complete: true, occurrences: ordinal });
}
