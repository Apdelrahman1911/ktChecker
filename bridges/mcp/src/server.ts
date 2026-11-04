import { McpServer } from '@modelcontextprotocol/server';
import { serveStdio, type ServeStdioOptions, type StdioServerHandle } from '@modelcontextprotocol/server/stdio';
import * as z from 'zod/v4';
import { BrokerError, PREVIEW_CONTRACT, type BrokerPort, type JsonObject } from './rpc.js';

export const MCP_PROFILE = '2026-07-28' as const;
const id = z.string().min(1).max(256);
const credentialSchema = z.object({ clientId: id, token: z.string().min(1).max(4096) }).strict();
export const bridgeConfigSchema = z.object({
  broker: z.object({ command: z.string().min(1), args: z.array(z.string()), cwd: z.string().optional() }).strict(),
  credential: credentialSchema,
  // Prepared by the trusted capture/controller. Model input never supplies roots/execution settings.
  submissions: z.record(id, z.object({ patchId: id, idempotencyKey: id, input: z.record(z.string(), z.unknown()) }).strict()).default({}),
}).strict();
export type BridgeConfig = z.infer<typeof bridgeConfigSchema>;

export function createBridge(broker: BrokerPort, config: BridgeConfig): McpServer {
  const server = new McpServer({ name: 'ktchecker-preview', version: '0.1.0-preview.1' }, {
    capabilities: { tools: {} }, supportedProtocolVersions: [MCP_PROFILE], maxToolInputElements: 128,
    instructions: 'Preview Kotlin review evidence, not a clean-verdict oracle. Pending/incomplete/unavailable is not passed. Background delivery requires a separate host adapter.',
  });
  const call = async (method: string, params: JsonObject) => {
    try {
      const result = await broker.request<JsonObject>(method, { ...params, credential: config.credential });
      if (typeof result !== 'object' || result === null || Array.isArray(result)) throw new BrokerError('invalid_broker_response', 'Expected an application object');
      if (result.contractVersion !== undefined && result.contractVersion !== PREVIEW_CONTRACT) throw new BrokerError('incompatible_broker_contract', 'Unexpected application version');
      const body = { ...result, contractVersion: PREVIEW_CONTRACT };
      const serialized = JSON.stringify(body);
      if (Buffer.byteLength(serialized, 'utf8') > 32_768) throw new BrokerError('model_output_exceeds_budget', 'Use the durable trusted-host diagnostic projection; this full result exceeds the model-facing bound');
      if (serialized.includes(config.credential.token)) throw new BrokerError('private_data_rejected', 'Broker result unexpectedly contained a transport credential');
      return { content: [{ type: 'text' as const, text: serialized }], structuredContent: body };
    } catch (error) {
      // No stderr/stack/argv/input dump is inserted into model context.
      const code = error instanceof BrokerError && /^[a-z][a-z0-9_]{0,63}$/.test(error.code) ? error.code : 'bridge_failure';
      const body = { contractVersion: PREVIEW_CONTRACT, error: { code, message: 'Review operation did not yield a verified result. Consult the trusted host controller.' } };
      return { isError: true, content: [{ type: 'text' as const, text: JSON.stringify(body) }], structuredContent: body };
    }
  };
  server.registerTool('review.submit', {
    description: 'Submit an immutable owner-prepared review. Acceptance is not completion; no model-supplied code execution or capture authority.',
    inputSchema: z.object({ submissionId: id }).strict(), annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false },
  }, async ({ submissionId }) => {
    const prepared = Object.hasOwn(config.submissions, submissionId) ? config.submissions[submissionId] : undefined;
    if (!prepared) {
      const body = { contractVersion: PREVIEW_CONTRACT, error: { code: 'unknown_prepared_submission', message: 'A trusted host must capture and prepare this patch first.' } };
      return { isError: true, structuredContent: body, content: [{ type: 'text' as const, text: JSON.stringify(body) }] };
    }
    return call('review.submit', prepared);
  });
  server.registerTool('review.status', {
    description: 'Read review state, findings and coverage for this client. No delivery acknowledgement.',
    inputSchema: z.object({ reviewId: id }).strict(), annotations: { readOnlyHint: true, openWorldHint: false },
  }, ({ reviewId }) => call('review.status', { reviewId }));
  server.registerTool('review.events', {
    description: 'Read this client event stream. Reading is not insertion, ACK, model observation or background wakeup.',
    inputSchema: z.object({ cursor: z.string().max(4096).nullable().optional(), limit: z.number().int().min(1).max(100).default(100) }).strict(),
    annotations: { readOnlyHint: true, openWorldHint: false },
  }, ({ cursor, limit }) => call('review.events', { cursor: cursor ?? null, limit }));
  server.registerTool('review.cancel', {
    description: 'Detach this requester only. Does not erase patch obligations or cancel another client\'s shared computation.',
    inputSchema: z.object({ reviewId: id }).strict(), annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false },
  }, ({ reviewId }) => call('review.cancel', { reviewId }));
  return server;
}
export function serveBridge(broker: BrokerPort, config: BridgeConfig, options: ServeStdioOptions = {}): StdioServerHandle {
  // The SDK owns framing, current-profile discovery, metadata validation and tool dispatch.
  // Legacy is rejected, not hand-emulated or silently represented as current MCP.
  return serveStdio(() => createBridge(broker, config), { ...options, legacy: 'reject' });
}
