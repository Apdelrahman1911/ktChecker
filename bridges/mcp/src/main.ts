#!/usr/bin/env node
import { lstat, readFile } from 'node:fs/promises';
import { StdioServerTransport } from '@modelcontextprotocol/server/stdio';
import { bridgeConfigSchema, serveBridge } from './server.js';
import { RestartingJsonLineRpc } from './rpc.js';
import { assertPrivateStatePlatform } from './private-state.js';

async function main(): Promise<void> {
  const args = process.argv.slice(2);
  if (args.length !== 2 || args[0] !== '--config' || !args[1]) throw new Error('Usage: ktchecker-mcp-preview --config OWNER_PRIVATE_JSON');
  assertPrivateStatePlatform();
  const stat = await lstat(args[1]);
  if (!stat.isFile() || stat.isSymbolicLink() || stat.size > 1_048_576) throw new Error('Configuration must be a bounded regular private file');
  if ((stat.mode & 0o077) !== 0 || (process.getuid && stat.uid !== process.getuid())) throw new Error('Configuration must be owned by this user and mode 0600');
  const config = bridgeConfigSchema.parse(JSON.parse(await readFile(args[1], 'utf8')));
  const broker = new RestartingJsonLineRpc({ command: config.broker.command, args: config.broker.args,
    ...(config.broker.cwd ? { cwd: config.broker.cwd } : {}) });
  const handle = serveBridge(broker, config, {
    transport: new StdioServerTransport(process.stdin, process.stdout, { maxBufferSize: 1_048_576 }),
    onerror: () => { process.stderr.write('ktchecker MCP protocol/transport error\n'); },
  });
  let closing = false;
  const close = async () => { if (closing) return; closing = true; await handle.close(); await broker.close(); };
  process.stdin.once('end', () => { void close(); });
  process.once('SIGINT', () => { process.exitCode = 130; void close(); });
  process.once('SIGTERM', () => { void close(); });
}
main().catch(() => { process.stderr.write('ktchecker MCP preview failed to start; check private configuration and runtime\n'); process.exitCode = 2; });
