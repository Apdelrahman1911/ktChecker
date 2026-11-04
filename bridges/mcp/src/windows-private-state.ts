import { spawnSync } from 'node:child_process';
import { win32 } from 'node:path';

export type WindowsPrivateRequest = { operation: 'verify_file'; path: string } |
  { operation: 'prepare_store'; path: string; database: 'inbox.sqlite' | 'codex.sqlite' };
export class WindowsPrivateStateError extends Error {
  constructor(readonly code: string) { super(code); this.name = 'WindowsPrivateStateError'; }
}

/** Fixed program, never constructed from a path, SID, username, project file or
 * model argument. Paths/operations cross stdin as JSON DATA. Windows PowerShell
 * uses the native .NET Framework ACL-at-creation APIs; pinned no-SHARE_DELETE
 * handles verify every traversed component and reject reparses/hardlinks. ACLs
 * are read from the pinned handle, not a potentially redirected pathname;
 * attributes/final paths are rechecked after the entire ACL chain is accepted.
 * This is an ordinary-other-user boundary, not isolation from the same SID or
 * privileged administrators/SYSTEM/TrustedInstaller. It is a candidate until
 * native hosted Windows controls pass; callers must retain the platform guard.
 */
export const WINDOWS_PRIVATE_STATE_PROGRAM = String.raw`
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false, $true)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
try {
Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Text;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Principal;
using Microsoft.Win32.SafeHandles;
public static class KtCheckerPrivateState {
  sealed class Rejected : Exception { public readonly string Code; public Rejected(string code) { Code = code; } }
  [StructLayout(LayoutKind.Sequential)] struct Ft { public uint Low, High; }
  [StructLayout(LayoutKind.Sequential)] struct Info {
    public uint Attributes; public Ft Created, Accessed, Written;
    public uint Volume, SizeHigh, SizeLow, Links, IndexHigh, IndexLow;
  }
  [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
  static extern SafeFileHandle CreateFile(string path, uint access, uint share, IntPtr security, uint disposition, uint flags, IntPtr template);
  [DllImport("kernel32.dll", SetLastError=true)] static extern bool GetFileInformationByHandle(SafeFileHandle handle, out Info info);
  [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern uint GetFileAttributes(string path);
  [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
  static extern uint GetFinalPathNameByHandle(SafeFileHandle handle, StringBuilder path, uint size, uint flags);
  [DllImport("advapi32.dll", SetLastError=true)]
  static extern bool GetKernelObjectSecurity(SafeFileHandle handle, uint flags, [Out] byte[] data, uint size, out uint needed);
  sealed class Held { public SafeFileHandle Handle; public string Path; public bool Directory; }
  static SecurityIdentifier User;
  static List<Held> Pins;
  static void Fail(string code) { throw new Rejected(code); }
  static bool Privileged(string sid) {
    return sid == "S-1-5-18" || sid == "S-1-5-32-544" ||
      sid == "S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464";
  }
  static bool Trusted(string sid) { return sid == User.Value || Privileged(sid); }
  static bool Exists(string path) {
    if (GetFileAttributes(path) != 0xffffffff) return true;
    if (Marshal.GetLastWin32Error() != 2) Fail("private_acl_unavailable");
    return false;
  }
  static uint Expand(int raw) {
    uint mask = unchecked((uint)raw);
    if ((mask & 0x10000000) != 0) mask |= 0x1f01ff;
    if ((mask & 0x80000000) != 0) mask |= 0x120089;
    if ((mask & 0x40000000) != 0) mask |= 0x120116;
    if ((mask & 0x20000000) != 0) mask |= 0x1200a0;
    return mask & 0x0fffffff;
  }
  static string Full(string path) {
    if (String.IsNullOrEmpty(path) || path.Length > 2048 || path.Length < 4 ||
        !Char.IsLetter(path[0]) || path[1] != ':' || path[2] != '\\' || path.IndexOf(':', 2) >= 0 || path.IndexOf('/') >= 0)
      Fail("private_path_unsupported");
    string[] pieces = path.Substring(3).Split('\\');
    foreach (string piece in pieces) {
      if (piece.Length == 0 || piece == "." || piece == ".." || piece.EndsWith(".") || piece.EndsWith(" ") ||
          piece.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0) Fail("private_path_unsupported");
      string stem = piece.Split('.')[0].ToUpperInvariant();
      if (stem == "CON" || stem == "PRN" || stem == "AUX" || stem == "NUL" ||
          (stem.Length == 4 && (stem.StartsWith("COM") || stem.StartsWith("LPT")) && stem[3] >= '0' && stem[3] <= '9'))
        Fail("private_path_unsupported");
    }
    string full = Path.GetFullPath(path);
    if (!String.Equals(full, path, StringComparison.OrdinalIgnoreCase)) Fail("private_path_unsupported");
    return full;
  }
  static SafeFileHandle Pin(string path, bool directory) {
    SafeFileHandle handle = CreateFile(path, 0x20080, 3, IntPtr.Zero, 3, 0x02200000, IntPtr.Zero);
    if (handle.IsInvalid) { handle.Dispose(); Fail("private_acl_unavailable"); }
    Pins.Add(new Held { Handle = handle, Path = path, Directory = directory });
    Check(handle, path, directory);
    return handle;
  }
  static void Check(SafeFileHandle handle, string path, bool directory) {
    Info info;
    if (!GetFileInformationByHandle(handle, out info)) Fail("private_acl_unavailable");
    if ((info.Attributes & 0x400) != 0 || ((info.Attributes & 0x10) != 0) != directory || (!directory && info.Links != 1))
      Fail("unsafe_state_path");
    StringBuilder actual = new StringBuilder(4096);
    uint length = GetFinalPathNameByHandle(handle, actual, 4096, 0);
    if (length == 0 || length >= 4096) Fail("private_acl_unavailable");
    string resolved = actual.ToString();
    if (resolved.StartsWith(@"\\?\")) resolved = resolved.Substring(4);
    if (!String.Equals(resolved.TrimEnd('\\'), path.TrimEnd('\\'), StringComparison.OrdinalIgnoreCase)) Fail("private_path_alias_unsupported");
  }
  static FileSystemSecurity Security(SafeFileHandle handle, bool directory) {
    byte[] data = new byte[65536]; uint needed;
    if (!GetKernelObjectSecurity(handle, 5, data, 65536, out needed) || needed == 0 || needed > 65536) Fail("private_acl_unavailable");
    Array.Resize(ref data, (int)needed);
    FileSystemSecurity security = directory ? (FileSystemSecurity)new DirectorySecurity() : new FileSecurity();
    security.SetSecurityDescriptorBinaryForm(data); return security;
  }
  static RawSecurityDescriptor Descriptor(FileSystemSecurity security) {
    RawSecurityDescriptor raw = new RawSecurityDescriptor(security.GetSecurityDescriptorBinaryForm(), 0);
    if (raw.Owner == null || raw.DiscretionaryAcl == null || raw.DiscretionaryAcl.Count > 256 ||
        (raw.ControlFlags & ControlFlags.DiscretionaryAclPresent) == 0) Fail("state_permissions");
    return raw;
  }
  static CommonAce Ace(GenericAce value) {
    CommonAce ace = value as CommonAce;
    if (ace == null || ace.IsCallback || (ace.AceQualifier != AceQualifier.AccessAllowed && ace.AceQualifier != AceQualifier.AccessDenied))
      Fail("state_permissions");
    return ace;
  }
  static void Parent(SafeFileHandle handle) {
    RawSecurityDescriptor raw = Descriptor(Security(handle, true));
    if (!Trusted(raw.Owner.Value)) Fail("private_parent_permissions");
    foreach (GenericAce value in raw.DiscretionaryAcl) {
      CommonAce ace = Ace(value);
      if ((ace.AceFlags & AceFlags.InheritOnly) != 0 || ace.AceQualifier == AceQualifier.AccessDenied) continue;
      if (!Trusted(ace.SecurityIdentifier.Value) && (Expand(ace.AccessMask) & 0xd0152) != 0) Fail("private_parent_permissions");
    }
  }
  static bool Private(FileSystemSecurity security, bool root) {
    RawSecurityDescriptor raw = Descriptor(security);
    if (raw.Owner.Value != User.Value || (root && !security.AreAccessRulesProtected)) return false;
    bool read = false, inheritedOwner = !root;
    foreach (GenericAce value in raw.DiscretionaryAcl) {
      CommonAce ace = Ace(value);
      if (ace.AceQualifier == AceQualifier.AccessDenied) continue;
      if (ace.SecurityIdentifier.Value != User.Value) return false;
      uint mask = Expand(ace.AccessMask);
      if ((ace.AceFlags & AceFlags.InheritOnly) == 0 && (mask & 0x20001) == 0x20001) read = true;
      if (root && (ace.AceFlags & (AceFlags.ObjectInherit | AceFlags.ContainerInherit)) == (AceFlags.ObjectInherit | AceFlags.ContainerInherit) &&
          (ace.AceFlags & (AceFlags.InheritOnly | AceFlags.NoPropagateInherit)) == 0 && (mask & 0x1f01ff) == 0x1f01ff) inheritedOwner = true;
    }
    return read && inheritedOwner;
  }
  static DirectorySecurity DirectoryAcl() {
    DirectorySecurity security = new DirectorySecurity(); security.SetOwner(User); security.SetAccessRuleProtection(true, false);
    security.AddAccessRule(new FileSystemAccessRule(User, FileSystemRights.FullControl,
      InheritanceFlags.ObjectInherit | InheritanceFlags.ContainerInherit, PropagationFlags.None, AccessControlType.Allow));
    return security;
  }
  static FileSecurity FileAcl() {
    FileSecurity security = new FileSecurity(); security.SetOwner(User); security.SetAccessRuleProtection(true, false);
    security.AddAccessRule(new FileSystemAccessRule(User, FileSystemRights.FullControl, AccessControlType.Allow)); return security;
  }
  static bool NonEmpty(string path) {
    using (var entries = Directory.EnumerateFileSystemEntries(path).GetEnumerator()) return entries.MoveNext();
  }
  static void SealEmptyOwnedRoot(string path, SafeFileHandle handle) {
    RawSecurityDescriptor raw = Descriptor(Security(handle, true));
    if (raw.Owner.Value != User.Value) Fail("state_permissions");
    foreach (GenericAce value in raw.DiscretionaryAcl) {
      CommonAce ace = Ace(value);
      if (ace.AceQualifier == AceQualifier.AccessAllowed && !Trusted(ace.SecurityIdentifier.Value)) Fail("state_permissions");
    }
    if (NonEmpty(path)) Fail("state_directory_in_use");
    Directory.SetAccessControl(path, DirectoryAcl());
    if (NonEmpty(path)) Fail("state_directory_in_use");
  }
  static void Parents(string path, bool create, bool includeRoot) {
    string parent = includeRoot ? path : Path.GetDirectoryName(path);
    string current = Path.GetPathRoot(path);
    SafeFileHandle handle = Pin(current, true); Parent(handle);
    foreach (string piece in parent.Substring(current.Length).Split(new char[] {'\\'}, StringSplitOptions.RemoveEmptyEntries)) {
      current = Path.Combine(current, piece);
      if (!Exists(current)) {
        if (!create) Fail("unsafe_state_path");
        Directory.CreateDirectory(current, DirectoryAcl());
      }
      handle = Pin(current, true); Parent(handle);
    }
    if (includeRoot) {
      if (!Private(Security(handle, true), true)) SealEmptyOwnedRoot(path, handle);
      if (!Private(Security(handle, true), true)) Fail("state_permissions");
    }
  }
  static void VerifyFile(string path) {
    SafeFileHandle handle = Pin(path, false);
    if (!Private(Security(handle, false), false)) Fail("state_permissions");
  }
  public static string Run(string operation, string requested, string database) {
    Pins = new List<Held>();
    try {
      User = WindowsIdentity.GetCurrent().User;
      if (User == null) Fail("private_acl_unavailable");
      string path = Full(requested);
      if (operation == "verify_file") { Parents(path, false, false); VerifyFile(path); }
      else if (operation == "prepare_store" && (database == "inbox.sqlite" || database == "codex.sqlite")) {
        Parents(path, true, true);
        string file = Path.Combine(path, database);
        if (!Exists(file)) {
          using (FileStream stream = new FileStream(file, FileMode.CreateNew, FileSystemRights.FullControl,
            FileShare.ReadWrite, 4096, FileOptions.None, FileAcl())) { }
        }
        VerifyFile(file);
        foreach (string suffix in new string[] { "-wal", "-shm", "-journal" }) {
          string sidecar = file + suffix;
          if (Exists(sidecar)) VerifyFile(sidecar);
        }
      } else Fail("private_operation_unsupported");
      foreach (Held pin in Pins) Check(pin.Handle, pin.Path, pin.Directory);
      return "ok";
    } catch (Rejected error) { return error.Code; }
      catch { return "private_acl_unavailable"; }
      finally { for (int i = Pins.Count - 1; i >= 0; i--) Pins[i].Handle.Dispose(); }
  }
}
'@
$request = [Console]::In.ReadToEnd() | ConvertFrom-Json
$code = [KtCheckerPrivateState]::Run([string]$request.operation, [string]$request.path, [string]$request.database)
$response = @{schema='ktchecker-windows-private-state/preview-1';code=$code}
[Console]::Write(($response | ConvertTo-Json -Compress))
if ($code -ne 'ok') { exit 1 }
} catch {
[Console]::Write('{"schema":"ktchecker-windows-private-state/preview-1","code":"private_acl_unavailable"}')
exit 1
}
`.replace(/^[ \t]+/gm, ''); // Fixed indentation-only normalization bounds the encoded command; no runtime code input.

const codes = new Set(['ok', 'private_path_unsupported', 'private_path_alias_unsupported', 'private_acl_unavailable',
  'unsafe_state_path', 'state_permissions', 'private_parent_permissions', 'state_directory_in_use', 'private_operation_unsupported']);

/** Pure invocation construction is testable on POSIX; it is not native ACL proof. */
export function windowsPrivateInvocation(request: WindowsPrivateRequest, systemRoot: string | undefined): {
  command: string; args: string[]; input: string;
} {
  if (!systemRoot || !/^[A-Za-z]:\\[^\0]*$/.test(systemRoot) || systemRoot.includes('..') || systemRoot.includes('/')) {
    throw new WindowsPrivateStateError('private_acl_unavailable');
  }
  if (request === null || typeof request !== 'object' || Array.isArray(request) ||
      typeof request.path !== 'string' || request.path.length > 2048 || request.path.includes('\0') ||
      (request.operation !== 'verify_file' && request.operation !== 'prepare_store') ||
      (request.operation === 'prepare_store' && !['inbox.sqlite', 'codex.sqlite'].includes(request.database)) ||
      Object.keys(request).sort().join(',') !== (request.operation === 'prepare_store' ? 'database,operation,path' : 'operation,path')) {
    throw new WindowsPrivateStateError('private_operation_unsupported');
  }
  const encoded = Buffer.from(WINDOWS_PRIVATE_STATE_PROGRAM, 'utf16le').toString('base64');
  if (encoded.length > 30_000) throw new WindowsPrivateStateError('private_helper_command_bound');
  return { command: win32.join(systemRoot, 'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe'),
    args: ['-NoLogo', '-NoProfile', '-NonInteractive', '-EncodedCommand', encoded], input: JSON.stringify(request) };
}

/** Decodes only a fixed, bounded native result vocabulary. No helper stderr,
 * exception message, path, identity or arbitrary stdout is returned to callers.
 */
export function verifyWindowsPrivateResponse(result: { stdout: Uint8Array | null; status: number | null; error?: unknown; signal?: unknown }): void {
  let code: string;
  try {
    if (!result.stdout || result.stdout.byteLength > 65_536) throw new Error('bound');
    const response: unknown = JSON.parse(new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(result.stdout));
    if (response === null || typeof response !== 'object' || Array.isArray(response) ||
        Object.keys(response).sort().join(',') !== 'code,schema') throw new Error('shape');
    const value = response as Record<string, unknown>;
    if (value.schema !== 'ktchecker-windows-private-state/preview-1' || typeof value.code !== 'string' || !codes.has(value.code)) throw new Error('shape');
    code = value.code;
  } catch { throw new WindowsPrivateStateError('private_acl_unavailable'); }
  if (result.error || result.signal || result.status !== 0 || code !== 'ok') {
    throw new WindowsPrivateStateError(code === 'ok' ? 'private_acl_unavailable' : code);
  }
}

/** Candidate native check; production Windows entry points retain their guard
 * until this implementation has actual hosted positive/negative evidence.
 */
export function runWindowsPrivateState(request: WindowsPrivateRequest): void {
  if (process.platform !== 'win32') throw new WindowsPrivateStateError('native_windows_required');
  const invocation = windowsPrivateInvocation(request, process.env.SystemRoot);
  const result = spawnSync(invocation.command, invocation.args, { input: invocation.input, shell: false, windowsHide: true,
    timeout: 20_000, maxBuffer: 65_536, stdio: ['pipe', 'pipe', 'pipe'] });
  verifyWindowsPrivateResponse(result);
}
