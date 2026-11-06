/** Private JVM owner-session byte codec, not Codex/MCP and never an authority decoder.
 * Source-only import: no filesystem, child, owner, provider, host or model is opened.
 */
import { HostError } from '../src/journal.js';
import type { JsonObject } from '@ktchecker/mcp-preview/rpc';

export const OWNER_SESSION_PROTOCOL = 'ktchecker-owner-session/1';
export const OWNER_FRAME_BYTES = 16 * 1024 * 1024;
export const OWNER_FRAME_MILLIS = 15_000;
export const OWNER_REQUEST_LIMIT = 65_536;
export const OWNER_CHALLENGE_LIMIT = 65; // overall + export/review for unchanged maximum32 owner attempts.
export const OWNER_SESSION_ID = /^[0-9a-f]{64}$/;
export function ownerCheck(value: unknown, code: string): asserts value {
  if (!value) throw new HostError('current_owner_' + code);
}
export function ownerObject(value: unknown): JsonObject {
  ownerCheck(value !== null && typeof value === 'object' && !Array.isArray(value), 'object'); return value as JsonObject;
}
export function ownerKeys(value: JsonObject, keys: readonly string[]): void {
  const actual = Object.keys(value).sort(), expected = [...keys].sort();
  ownerCheck(actual.length === expected.length && actual.every((key, index) => key === expected[index]), 'unexpected_fields');
}
export function ownerInteger(value: unknown, maximum: number, minimum = 0): number {
  ownerCheck(Number.isSafeInteger(value) && (value as number) >= minimum && (value as number) <= maximum, 'integer'); return value as number;
}
function wellFormed(value: string): boolean {
  for (let i = 0; i < value.length; i++) {
    const code = value.charCodeAt(i);
    if (code >= 0xd800 && code <= 0xdbff) {
      const next = value.charCodeAt(++i); if (!(next >= 0xdc00 && next <= 0xdfff)) return false;
    } else if (code >= 0xdc00 && code <= 0xdfff) return false;
  }
  return true;
}
/** Match the JVM strict document boundary: depth64, values200000, decoded unique keys,
 * no malformed UTF8/BOM/surrogates/nonfinite numbers. JSON.parse alone loses duplicate keys.
 */
export function strictOwnerJson(bytes: Buffer): unknown {
  ownerCheck(bytes.length >= 1 && bytes.length <= OWNER_FRAME_BYTES, 'json_bound');
  let source: string;
  try { source = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes); }
  catch { throw new HostError('current_owner_utf8'); }
  let position = 0, values = 0;
  const fail = (): never => { throw new HostError('current_owner_strict_json'); };
  const whitespace = () => { while (position < source.length && ' \r\n\t'.includes(source[position]!)) position++; };
  const take = (character: string) => source[position] === character ? (position++, true) : false;
  const string = (): string => {
    const start = position++; // Opening quote already checked.
    while (position < source.length) {
      const character = source[position++]!;
      if (character === '"') {
        let value: unknown; try { value = JSON.parse(source.slice(start, position)); } catch { fail(); }
        if (typeof value !== 'string' || !wellFormed(value)) return fail(); return value;
      }
      if (character.charCodeAt(0) < 32) fail();
      if (character === '\\') {
        const escaped = source[position++];
        if (escaped === 'u') {
          for (let i = 0; i < 4; i++) if (!/^[0-9a-fA-F]$/.test(source[position++] ?? '')) fail();
        } else if (escaped === undefined || !'"\\/bfnrt'.includes(escaped)) fail();
      }
    }
    return fail();
  };
  const value = (depth: number): void => {
    if (depth > 64 || ++values > 200_000) fail(); whitespace();
    const character = source[position];
    if (character === '{') {
      position++; const keys = new Set<string>(); whitespace(); if (take('}')) return;
      for (;;) {
        whitespace(); if (source[position] !== '"') fail(); const key = string();
        if (keys.has(key)) fail(); keys.add(key); whitespace(); if (!take(':')) fail();
        value(depth + 1); whitespace(); if (take('}')) return; if (!take(',')) fail();
      }
    }
    if (character === '[') {
      position++; whitespace(); if (take(']')) return;
      for (;;) { value(depth + 1); whitespace(); if (take(']')) return; if (!take(',')) fail(); }
    }
    if (character === '"') { string(); return; }
    for (const literal of ['true', 'false', 'null']) {
      if (source.startsWith(literal, position)) { position += literal.length; return; }
    }
    if (character === '-' || character !== undefined && /^[0-9]$/.test(character)) {
      const start = position;
      while (position < source.length && !' \r\n\t,]}'.includes(source[position]!)) position++;
      const token = source.slice(start, position);
      if (!/^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?$/.test(token) || !Number.isFinite(Number(token))) fail();
      return;
    }
    fail();
  };
  value(0); whitespace(); if (position !== source.length) fail();
  try { return JSON.parse(source) as unknown; } catch { return fail(); }
}
export function encodeOwnerFrame(value: JsonObject): Buffer {
  const bytes = Buffer.from(JSON.stringify(value), 'utf8'); strictOwnerJson(bytes);
  const header = Buffer.alloc(4); header.writeUInt32BE(bytes.length); return Buffer.concat([header, bytes]);
}

/** Constant bounded partial frame state. One deadline starts at its FIRST header byte;
 * later fragments never refresh it. Caller owns timers and dispatch, never this decoder.
 * Explicit now is only monotonic DATA for decoder controls, not a model/owner clock permit.
 */
export class OwnerFrameDecoder {
  private readonly header = Buffer.alloc(4);
  private headerBytes = 0; private payload: Buffer | null = null; private payloadBytes = 0;
  private deadline: bigint | null = null; private failed = false;
  private frames = 0;
  get partialDeadlineNanos(): bigint | null { return this.deadline; }
  poison(): void { this.failed = true; this.payload = null; }
  checkpoint(now: bigint): void {
    ownerCheck(!this.failed, 'frame_poisoned');
    if (this.deadline !== null && now >= this.deadline) { this.poison(); throw new HostError('current_owner_frame_fragment_timeout'); }
  }
  eof(): never { this.poison(); throw new HostError('current_owner_unexpected_eof'); }
  feed(bytes: Buffer, now: bigint, emit: (value: JsonObject, originalFrameDeadline: bigint) => void): void {
    this.checkpoint(now); let offset = 0;
    try {
      while (offset < bytes.length) {
        this.checkpoint(now);
        if (this.deadline === null) this.deadline = now + BigInt(OWNER_FRAME_MILLIS) * 1_000_000n;
        if (this.headerBytes < 4) {
          const count = Math.min(4 - this.headerBytes, bytes.length - offset);
          bytes.copy(this.header, this.headerBytes, offset, offset + count); this.headerBytes += count; offset += count;
          if (this.headerBytes < 4) continue;
          const length = this.header.readUInt32BE(); ownerCheck(length >= 1 && length <= OWNER_FRAME_BYTES, 'frame_length');
          this.payload = Buffer.allocUnsafe(length); this.payloadBytes = 0;
        }
        const payload = this.payload!;
        const count = Math.min(payload.length - this.payloadBytes, bytes.length - offset);
        bytes.copy(payload, this.payloadBytes, offset, offset + count); this.payloadBytes += count; offset += count;
        if (this.payloadBytes !== payload.length) continue;
        ownerCheck(++this.frames <= OWNER_REQUEST_LIMIT + OWNER_CHALLENGE_LIMIT + 2, 'frame_count');
        const decoded = ownerObject(strictOwnerJson(payload));
        const originalFrameDeadline = this.deadline;
        ownerCheck(originalFrameDeadline !== null, 'frame_deadline_missing');
        this.headerBytes = 0; this.payloadBytes = 0; this.payload = null; this.deadline = null;
        emit(decoded, originalFrameDeadline); // Actual caller dispatches only bounded protocol/clock DATA, no owner/user callback.
      }
    } catch (failure) { this.poison(); throw failure; }
  }
}
Object.freeze(OwnerFrameDecoder.prototype); Object.freeze(OwnerFrameDecoder);
