import { lstat, readFile } from 'node:fs/promises';
import * as z from 'zod/v4';
import { PREVIEW_CONTRACT } from '@ktchecker/mcp-preview/rpc';
import { assertPrivateStatePlatform } from '@ktchecker/mcp-preview/private-state';
import { HostError } from './journal.js';

const id = z.string().min(1).max(256);
const credential = z.object({ clientId: id, token: z.string().min(1).max(4096) }).strict();
const binding = z.object({ ownerId: id, workspaceId: id, worktreeId: id, clientId: id, lineageId: id,
  generation: z.number().int().positive().max(Number.MAX_SAFE_INTEGER), sessionId: id.optional() }).strict();
export const hostConfigSchema = z.object({
  broker: z.object({ command: z.string().min(1), args: z.array(z.string()), cwd: z.string().optional() }).strict(),
  registration: z.object({ contractVersion: z.literal(PREVIEW_CONTRACT), binding, client: credential, adapter: credential }).strict(),
  inboxDirectory: z.string().min(1),
  codex: z.object({ sessionDirectory: z.string().min(1), cwd: z.string().min(1) }).strict(),
  submissions: z.array(z.object({ patchId: id, idempotencyKey: id, input: z.record(z.string(), z.unknown()) }).strict()).max(128).default([]),
}).strict();
export type HostConfig = z.infer<typeof hostConfigSchema>;

/** Owner configuration, never a model tool parameter. POSIX mode is not same-UID isolation. */
export async function readHostConfig(path: string): Promise<HostConfig> {
  assertPrivateStatePlatform();
  const stat = await lstat(path);
  if (!stat.isFile() || stat.isSymbolicLink() || stat.size > 1_048_576) throw new HostError('unsafe_host_configuration');
  if ((stat.mode & 0o077) !== 0 || (process.getuid && stat.uid !== process.getuid())) throw new HostError('host_configuration_permissions');
  return hostConfigSchema.parse(JSON.parse(await readFile(path, 'utf8')));
}
