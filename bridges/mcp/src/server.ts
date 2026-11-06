import { CLIENT_CAPABILITIES_META_KEY, PROTOCOL_VERSION_META_KEY, McpServer, type ServerContext } from '@modelcontextprotocol/server';
import { serveStdio, type ServeStdioOptions, type StdioServerHandle } from '@modelcontextprotocol/server/stdio';
import * as z from 'zod/v4';
import { BrokerError, FINDING_WIRE_PROFILE, PREVIEW_CONTRACT, type BrokerPort, type JsonObject } from './rpc.js';
import { checkedPolicyReassessmentView, checkedReviewEventPage, policyReassessmentRequestSchema, policyReassessmentStatusSchema } from './policy-reassessment.js';
export { POLICY_REASSESSMENT_KIND, POLICY_REASSESSMENT_FORMAT, policyReassessmentRequestSchema, policyReassessmentStatusSchema,
  policyReassessmentViewSchema, parsePolicyReassessmentView, checkedPolicyReassessmentView, checkedPolicyReassessmentEvent, checkedReviewEventPage,
  type PolicyReassessmentRequest, type PolicyReassessmentView } from './policy-reassessment.js';

export const MCP_PROFILE = '2026-07-28' as const;
export const FINDING_WIRE_CAPABILITY = 'ktchecker/finding-retrieval-attachments' as const;
function object(value: unknown): value is JsonObject {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
function findingWireAdmitted(context: ServerContext): boolean {
  // SDK 2.4.0 lifts the CURRENT request's reserved metadata here. Its neutral
  // declaration is {}, so narrow unknown data rather than inventing a named member.
  // Never use the deprecated connection-wide getClientCapabilities() accessor.
  const envelope: unknown = context.mcpReq.envelope;
  if (!object(envelope) || envelope[PROTOCOL_VERSION_META_KEY] !== MCP_PROFILE) return false;
  const capabilities = envelope[CLIENT_CAPABILITIES_META_KEY];
  if (!object(capabilities) || !object(capabilities.experimental)) return false;
  const declaration = capabilities.experimental[FINDING_WIRE_CAPABILITY];
  return object(declaration) && Object.keys(declaration).length === 1 &&
    Object.hasOwn(declaration, 'profile') && declaration.profile === FINDING_WIRE_PROFILE;
}
function failedCall(code: string) {
  const body = { contractVersion: PREVIEW_CONTRACT, error: { code, message: 'Review operation did not yield a verified result. Consult the trusted host controller.' } };
  return { isError: true, content: [{ type: 'text' as const, text: JSON.stringify(body) }], structuredContent: body };
}
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
    capabilities: { tools: {}, experimental: { [FINDING_WIRE_CAPABILITY]: { profile: FINDING_WIRE_PROFILE } } },
    supportedProtocolVersions: [MCP_PROFILE], maxToolInputElements: 128,
    instructions: 'Preview Kotlin review evidence, not a clean-verdict oracle. Pending/incomplete/unavailable is not passed. Background delivery requires a separate host adapter.',
  });
  const call = async (context: ServerContext, method: string, params: JsonObject, validate: (value: JsonObject) => JsonObject = value => value) => {
    try {
      if (!findingWireAdmitted(context)) throw new BrokerError('incompatible_finding_wire_profile', 'Client did not declare the required Finding wire profile');
      const result = await broker.request<JsonObject>(method, { ...params, credential: config.credential });
      if (typeof result !== 'object' || result === null || Array.isArray(result)) throw new BrokerError('invalid_broker_response', 'Expected an application object');
      if (result.contractVersion !== undefined && result.contractVersion !== PREVIEW_CONTRACT) throw new BrokerError('incompatible_broker_contract', 'Unexpected application version');
      const body = { ...validate(result), contractVersion: PREVIEW_CONTRACT };
      const serialized = JSON.stringify(body);
      if (Buffer.byteLength(serialized, 'utf8') > 32_768) throw new BrokerError('model_output_exceeds_budget', 'Use the durable trusted-host diagnostic projection; this full result exceeds the model-facing bound');
      if (serialized.includes(config.credential.token)) throw new BrokerError('private_data_rejected', 'Broker result unexpectedly contained a transport credential');
      return { content: [{ type: 'text' as const, text: serialized }], structuredContent: body };
    } catch (error) {
      // No stderr/stack/argv/input dump is inserted into model context.
      const code = error instanceof BrokerError && /^[a-z][a-z0-9_]{0,63}$/.test(error.code) ? error.code : 'bridge_failure';
      return failedCall(code);
    }
  };
  server.registerTool('review.submit', {
    description: 'Submit an immutable owner-prepared review. Acceptance is not completion; no model-supplied code execution or capture authority.',
    inputSchema: z.object({ submissionId: id }).strict(), annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false },
  }, async ({ submissionId }, context) => {
    if (!findingWireAdmitted(context)) return failedCall('incompatible_finding_wire_profile');
    const prepared = Object.hasOwn(config.submissions, submissionId) ? config.submissions[submissionId] : undefined;
    if (!prepared) {
      const body = { contractVersion: PREVIEW_CONTRACT, error: { code: 'unknown_prepared_submission', message: 'A trusted host must capture and prepare this patch first.' } };
      return { isError: true, structuredContent: body, content: [{ type: 'text' as const, text: JSON.stringify(body) }] };
    }
    return call(context, 'review.submit', prepared);
  });
  server.registerTool('review.status', {
    description: 'Read review state, findings and coverage for this client. No delivery acknowledgement.',
    inputSchema: z.object({ reviewId: id }).strict(), annotations: { readOnlyHint: true, openWorldHint: false },
  }, ({ reviewId }, context) => call(context, 'review.status', { reviewId }));
  server.registerTool('review.policy.reassess', {
    description: 'Reevaluate certified original evidence under already-authorized current policy. Historical same-raw view only: no policy edit, execution, ACK, observation or completion permission.',
    inputSchema: policyReassessmentRequestSchema,
    annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false },
  }, (request, context) => call(context, 'review.policy.reassess', { request }, value => checkedPolicyReassessmentView(value, config.credential.clientId, request)));
  server.registerTool('review.policy.status', {
    description: 'Read the exact immutable policy view for this client/request ID. No reevaluation, current-policy substitution, delivery acknowledgement or completion permission.',
    inputSchema: policyReassessmentStatusSchema, annotations: { readOnlyHint: true, openWorldHint: false },
  }, ({ idempotencyKey }, context) => call(context, 'review.policy.status', { idempotencyKey },
    value => checkedPolicyReassessmentView(value, config.credential.clientId, idempotencyKey)));
  server.registerTool('review.events', {
    description: 'Read this client event stream. Reading is not insertion, ACK, model observation or background wakeup.',
    inputSchema: z.object({ cursor: z.string().max(4096).nullable().optional(), limit: z.number().int().min(1).max(100).default(100) }).strict(),
    annotations: { readOnlyHint: true, openWorldHint: false },
  }, ({ cursor, limit }, context) => call(context, 'review.events', { cursor: cursor ?? null, limit }, page => {
    // Reject the entire response before it enters model context; no filtering/cursor advance.
    return checkedReviewEventPage(page, config.credential.clientId);
  }));
  server.registerTool('review.cancel', {
    description: 'Detach this requester only. Does not erase patch obligations or cancel another client\'s shared computation.',
    inputSchema: z.object({ reviewId: id }).strict(), annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false },
  }, ({ reviewId }, context) => call(context, 'review.cancel', { reviewId }));
  return server;
}
export function serveBridge(broker: BrokerPort, config: BridgeConfig, options: ServeStdioOptions = {}): StdioServerHandle {
  // The SDK owns framing, current-profile discovery, metadata validation and tool dispatch.
  // Legacy is rejected, not hand-emulated or silently represented as current MCP.
  return serveStdio(() => createBridge(broker, config), { ...options, legacy: 'reject' });
}
