import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import avro from 'avsc';
import type { IHeaders } from 'kafkajs';
import { eventSchema } from './schema.js';

const schemaBytes = readFileSync(process.env.AVRO_SCHEMA_PATH ?? new URL('../../../contracts/avro/message-created-v1.avsc', import.meta.url));
const schema = JSON.parse(schemaBytes.toString('utf8'));
export const avroType = avro.Type.forSchema(schema);
export const schemaId: string = `${schema.namespace}.${schema.name}`;
export const schemaHash = createHash('sha256').update(schemaBytes).digest('hex');
export const avroHeaders = {
  'content-type': 'application/avro',
  'avro-schema': schemaId,
  'avro-schema-sha256': schemaHash,
};

export function decodeEvent(value: Buffer | null, headers?: IHeaders) {
  for (const [name, expected] of Object.entries(avroHeaders)) {
    const actual = headers?.[name];
    if (Array.isArray(actual) || actual?.toString() !== expected) {
      throw new Error(`Unsupported or missing Avro header: ${name}`);
    }
  }
  if (!value) throw new Error('Missing Avro event value');
  // fromBuffer rejects truncated records and trailing bytes; Zod checks the domain contract.
  return eventSchema.parse(avroType.fromBuffer(value));
}
