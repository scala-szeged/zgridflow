import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { avroType, avroHeaders, decodeEvent, schemaHash } from './avro.js';

const fixture = JSON.parse(readFileSync(new URL('../../../contracts/avro/fixtures/message-created-v1.json', import.meta.url), 'utf8'));
const bytes = Buffer.from(fixture.hex, 'hex');

test('TypeScript reader and writer agree with the shared Scala binary fixture', () => {
  assert.equal(schemaHash, fixture.schemaSha256);
  assert.deepEqual(decodeEvent(bytes, avroHeaders), fixture.event);
  assert.equal(avroType.toBuffer(fixture.event).toString('hex'), fixture.hex);
});

test('rejects absent or mismatched schema headers and malformed Avro data', () => {
  assert.throws(() => decodeEvent(bytes), /header/);
  assert.throws(() => decodeEvent(bytes, { ...avroHeaders, 'avro-schema-sha256': 'unknown' }), /header/);
  assert.throws(() => decodeEvent(null, avroHeaders), /Missing/);
  assert.throws(() => decodeEvent(bytes.subarray(0, 3), avroHeaders));
  assert.throws(() => decodeEvent(Buffer.concat([bytes, Buffer.of(0)]), avroHeaders));
});

test('Avro structural decoding still enforces the gateway domain contract', () => {
  const invalid = avroType.toBuffer({ ...fixture.event, message: { ...fixture.event.message, id: 'not-a-uuid' } });
  assert.throws(() => decodeEvent(invalid, avroHeaders));
});
