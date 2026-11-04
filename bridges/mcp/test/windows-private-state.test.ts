import { test, type TestContext } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { lstat, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { DatabaseSync } from 'node:sqlite';
import { windowsPrivateInvocation, WINDOWS_PRIVATE_STATE_PROGRAM, verifyWindowsPrivateResponse, runWindowsPrivateState } from '../src/windows-private-state.js';

test('Windows helper invocation is fixed code with paths only in bounded JSON data, not native ACL qualification', () => {
  const path = String.raw`C:\owner\private ' $() ; [literal] 雪 café Ω\config.json`;
  const invocation = windowsPrivateInvocation({ operation: 'verify_file', path }, String.raw`C:\Windows`);
  assert.equal(invocation.command, String.raw`C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe`);
  assert.deepEqual(invocation.args.slice(0, -1), ['-NoLogo', '-NoProfile', '-NonInteractive', '-EncodedCommand']);
  assert.equal(Buffer.from(invocation.args.at(-1)!, 'base64').toString('utf16le'), WINDOWS_PRIVATE_STATE_PROGRAM);
  assert(invocation.args.at(-1)!.length <= 30_000); assert.deepEqual(JSON.parse(invocation.input), { operation: 'verify_file', path });
  const other = windowsPrivateInvocation({ operation: 'prepare_store', path: String.raw`D:\another`, database: 'inbox.sqlite' }, String.raw`C:\Windows`);
  assert.deepEqual(other.args, invocation.args); assert(!invocation.args.join(' ').includes(path));
  for (const root of [undefined, 'powershell', String.raw`\\server\windows`, String.raw`C:\Windows\..\other`]) {
    assert.throws(() => windowsPrivateInvocation({ operation: 'verify_file', path }, root), /private_acl_unavailable/);
  }
  assert.throws(() => windowsPrivateInvocation({ operation: 'verify_file', path: 'x'.repeat(2049) }, String.raw`C:\Windows`), /private_operation_unsupported/);
  assert.throws(() => windowsPrivateInvocation({ operation: 'verify_file', path, token: 'must_not_be_forwarded' } as any, String.raw`C:\Windows`), /private_operation_unsupported/);
  if (process.platform !== 'win32') assert.throws(() => runWindowsPrivateState({ operation: 'verify_file', path }), /native_windows_required/);
});

test('Windows helper result decoder rejects arbitrary output, UTF-8 repair and process failures without revealing diagnostics', () => {
  const result = (code: string, status = 0) => ({ stdout: Buffer.from(JSON.stringify({ schema: 'ktchecker-windows-private-state/preview-1', code })), status });
  assert.doesNotThrow(() => verifyWindowsPrivateResponse(result('ok')));
  assert.throws(() => verifyWindowsPrivateResponse(result('state_permissions', 1)), /state_permissions/);
  assert.throws(() => verifyWindowsPrivateResponse(result('ok', 1)), /private_acl_unavailable/);
  const secret = 'PRIVATE_HELPER_PATH_AND_TOKEN';
  for (const failed of [result(secret, 1), { stdout: Buffer.from(secret), status: 1 }, { stdout: Buffer.alloc(70_000), status: 0 },
    { ...result('ok'), error: new Error(secret) }, { ...result('ok'), signal: 'SIGTERM' },
    { stdout: Buffer.from([0xff, ...result('ok').stdout]), status: 0 }]) {
    assert.throws(() => verifyWindowsPrivateResponse(failed), error => error instanceof Error && error.message === 'private_acl_unavailable' && !error.message.includes(secret));
  }
});

/** Native cases register only on a real Windows runtime; POSIX runs execute
 * only the two source/invocation controls above, never these ACL cases. */
// Fixed test-only ACL mutations. Paths are JSON data; never interpolated into code.
const controlProgram = String.raw`
$ErrorActionPreference='Stop'
[Console]::InputEncoding=[System.Text.UTF8Encoding]::new($false,$true)
[Console]::OutputEncoding=New-Object System.Text.UTF8Encoding($false)
try {
$r=[Console]::In.ReadToEnd() | ConvertFrom-Json
$user=[Security.Principal.WindowsIdentity]::GetCurrent().User
$world=[Security.Principal.SecurityIdentifier]::new('S-1-1-0')
$isDir=[IO.Directory]::Exists([string]$r.path)
if ($r.operation -eq 'junction') { New-Item -ItemType Junction -Path $r.path -Target $r.target | Out-Null }
elseif ($r.operation -eq 'hardlink') { New-Item -ItemType HardLink -Path $r.path -Target $r.target | Out-Null }
elseif ($r.operation -eq 'null_dacl') {
Add-Type -TypeDefinition @'
using System; using System.Runtime.InteropServices;
public static class KtNativeTestAcl {
 [DllImport("advapi32.dll",CharSet=CharSet.Unicode,SetLastError=true)]
 public static extern uint SetNamedSecurityInfo(string path,int kind,uint flags,IntPtr owner,IntPtr group,IntPtr dacl,IntPtr sacl);
}
'@
if ([KtNativeTestAcl]::SetNamedSecurityInfo($r.path,1,[uint32]2147483652,[IntPtr]::Zero,[IntPtr]::Zero,[IntPtr]::Zero,[IntPtr]::Zero) -ne 0) { throw 'setup' }
}
else {
$acl=Get-Acl -LiteralPath $r.path
switch ($r.operation) {
 'world_read' { $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($world,[Security.AccessControl.FileSystemRights]::Read,[Security.AccessControl.AccessControlType]::Allow)) }
 'world_delete_child' { $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($world,[Security.AccessControl.FileSystemRights]::DeleteSubdirectoriesAndFiles,[Security.AccessControl.AccessControlType]::Allow)) }
 'world_metadata_write' { $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($world,[Security.AccessControl.FileSystemRights]::WriteAttributes,[Security.AccessControl.AccessControlType]::Allow)) }
 'system_read' { $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new('S-1-5-18'),[Security.AccessControl.FileSystemRights]::Read,[Security.AccessControl.AccessControlType]::Allow)) }
 'foreign_owner' { $acl.SetOwner([Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')) }
 'unprotect' { $acl.SetAccessRuleProtection($false,$true) }
 default { throw 'setup' }
}
Set-Acl -LiteralPath $r.path -AclObject $acl
}
[Console]::Write('{"ok":true}')
} catch { [Console]::Write('{"ok":false}'); exit 1 }
`;
function control(operation: string, path: string, target?: string): void {
  const invocation = windowsPrivateInvocation({ operation: 'verify_file', path }, process.env.SystemRoot);
  const result = spawnSync(invocation.command, [...invocation.args.slice(0, -1), Buffer.from(controlProgram, 'utf16le').toString('base64')], {
    input: JSON.stringify({ operation, path, target }), timeout: 20_000, maxBuffer: 65_536, shell: false, windowsHide: true,
  });
  if (result.error || result.status !== 0 || result.stdout.toString('utf8') !== '{"ok":true}') throw new Error('native_windows_control_setup_failed');
}
async function root(t: TestContext, suffix = ''): Promise<string> {
  // Native creation sets the current SID explicitly, not the process's guessed username/default owner.
  const path = join(tmpdir(), 'ktchecker-native-acl-' + randomUUID() + suffix);
  t.after(() => rm(path, { recursive: true, force: true }));
  return path;
}
const prepare = (path: string) => runWindowsPrivateState({ operation: 'prepare_store', path, database: 'inbox.sqlite' });
const verify = (path: string) => runWindowsPrivateState({ operation: 'verify_file', path });

if (process.platform === 'win32') {
  test('native Windows creates SID-private stores before bytes and verifies real SQLite WAL SHM inheritance and reopen', async t => {
    const path = await root(t); prepare(path);
    const file = join(path, 'inbox.sqlite'); let db = new DatabaseSync(file);
    try {
      db.exec("PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; CREATE TABLE data (value TEXT); INSERT INTO data VALUES ('public-native-fixture');");
      verify(file); verify(file + '-wal'); verify(file + '-shm'); prepare(path);
    } finally { db.close(); }
    prepare(path); db = new DatabaseSync(file);
    try { assert.equal((db.prepare('SELECT value FROM data').get() as { value: string }).value, 'public-native-fixture'); }
    finally { db.close(); }
  });

  test('native Windows treats Unicode quote dollar and bracket paths as data and allows readonly ordinary-user ancestors', async t => {
    const parent = await root(t, " ' $() ; [literal] 雪 café Ω"); prepare(parent); control('world_read', parent);
    const path = join(parent, 'child'); prepare(path); verify(join(path, 'inbox.sqlite'));
  });

  test('native Windows rejects broad file ACLs and null DACL instead of reading owner credentials', async t => {
    const path = await root(t); prepare(path); const file = join(path, 'inbox.sqlite');
    control('world_read', file); assert.throws(() => verify(file), /state_permissions/);
    control('null_dacl', file); assert.throws(() => verify(file), /state_permissions/);
  });

  test('native Windows verifies leaf current SID ownership rather than trusting a privileged group owner', async t => {
    const path = await root(t); prepare(path); const file = join(path, 'inbox.sqlite');
    control('foreign_owner', file); assert.throws(() => verify(file), /state_permissions/);
  });

  test('native Windows rejects parent delete-child authority even when the private child DACL is unchanged', async t => {
    const parent = await root(t); prepare(parent); const path = join(parent, 'child'); prepare(path);
    control('world_delete_child', parent); assert.throws(() => verify(join(path, 'inbox.sqlite')), /private_parent_permissions/);
  });

  test('native Windows rejects ordinary-principal parent metadata control despite a private child DACL', async t => {
    const parent = await root(t); prepare(parent); const path = join(parent, 'child'); prepare(path);
    control('world_metadata_write', parent); assert.throws(() => verify(join(path, 'inbox.sqlite')), /private_parent_permissions/);
  });

  test('native Windows does not repair a nonempty shared or inheritance-unprotected state root', async t => {
    const path = await root(t); prepare(path); const file = join(path, 'inbox.sqlite');
    await writeFile(file, 'public-unmodified-state'); control('system_read', path);
    assert.throws(() => prepare(path), /state_directory_in_use/); assert.equal(await readFile(file, 'utf8'), 'public-unmodified-state');
    control('unprotect', path); assert.throws(() => prepare(path), /state_directory_in_use|state_permissions/);
  });

  test('native Windows rejects leaf hardlinks and ancestor junctions with no target mutation', async t => {
    const path = await root(t); prepare(path); const file = join(path, 'inbox.sqlite');
    await writeFile(file, 'public-original'); control('hardlink', join(path, 'other'), file);
    assert.throws(() => verify(file), /unsafe_state_path/);
    const alias = await root(t); control('junction', alias, path);
    assert.throws(() => prepare(join(alias, 'child')), /unsafe_state_path/);
    assert.equal(await readFile(file, 'utf8'), 'public-original');
  });

  test('native Windows rejects dangling reparse sidecars and ancestors without recreating their missing targets', async t => {
    const path = await root(t); prepare(path);
    const target = await root(t); prepare(target);
    control('junction', join(path, 'inbox.sqlite-wal'), target);
    const alias = await root(t); control('junction', alias, target);
    await rm(target, { recursive: true });
    assert.throws(() => prepare(path), /unsafe_state_path/);
    assert.throws(() => prepare(join(alias, 'child')), /unsafe_state_path/);
    await assert.rejects(lstat(target), { code: 'ENOENT' });
  });

  test('native Windows refuses device UNC alternate-stream and trailing-dot paths before touching a store', async () => {
    for (const path of [String.raw`\\server\share\config`, String.raw`\\?\C:\private`, String.raw`C:\private\file:stream`,
      String.raw`C:\private\NUL`, String.raw`C:\private\name.`, String.raw`C:\private\..\other`]) {
      assert.throws(() => prepare(path), /private_path_unsupported/);
    }
  });

  test('native Windows refuses existing nonempty owner-unproven directories without adopting their bytes', async t => {
    const path = await root(t); await mkdir(path); const sentinel = join(path, 'unrelated'); await writeFile(sentinel, 'public-unrelated');
    assert.throws(() => prepare(path), /state_directory_in_use|state_permissions/);
    assert.equal(await readFile(sentinel, 'utf8'), 'public-unrelated');
  });
}
