import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parse, subscribe } from 'graphql';
import { createGateway, type Message } from './schema.js';

const message: Message = { id: '00112233-4455-4677-8899-aabbccddeeff', text: 'hello', createdAt: '2026-11-21T00:00:00Z' };

test('queries the API and validates mutation input before calling it', async () => {
  let calls = 0;
  const { yoga } = createGateway({ list: async () => [message], create: async (text) => { calls++; return { ...message, text }; } });
  const execute = async (query: string) => (await yoga.fetch('http://localhost/graphql', {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ query }),
  })).json() as Promise<{ data?: { messages?: Message[], createMessage?: Message }, errors?: unknown[] }>;
  assert.equal((await execute('{ messages { id text } }')).data?.messages?.[0]?.id, message.id);
  assert.ok((await execute('mutation { createMessage(text: " ") { id } }')).errors);
  assert.equal(calls, 0);
  assert.equal((await execute('mutation { createMessage(text: " trimmed ") { text } }')).data?.createMessage?.text, 'trimmed');
});

test('subscription forwards broker events to subscribers', async () => {
  const gateway = createGateway({ list: async () => [], create: async () => message });
  const result = await subscribe({ schema: gateway.schema, document: parse('subscription { messageCreated { id text } }') });
  assert.ok(Symbol.asyncIterator in result);
  const iterator = result[Symbol.asyncIterator]();
  try {
    const next = iterator.next();
    gateway.publish(message);
    const event = await next;
    assert.equal(event.value.data.messageCreated.id, message.id);
  } finally { await iterator.return?.(); }
});
