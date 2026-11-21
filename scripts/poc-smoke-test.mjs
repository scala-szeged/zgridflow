import assert from 'node:assert/strict';

const url = process.argv[2] ?? 'http://localhost:4000/graphql';
async function graphql(query, variables) {
  const response = await fetch(url, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ query, variables }), signal: AbortSignal.timeout(15000),
  });
  assert.equal(response.status, 200);
  return response.json();
}
const invalid = await graphql('mutation { createMessage(text: " ") { id } }');
assert.equal(invalid.errors?.[0]?.extensions.code, 'BAD_USER_INPUT');
const controller = new AbortController();
const timeout = setTimeout(() => controller.abort(), 45000);
try {
  const stream = await fetch(`${url}?query=${encodeURIComponent('subscription { messageCreated { id text createdAt } }')}`, {
    headers: { accept: 'text/event-stream' }, signal: controller.signal,
  });
  assert.equal(stream.status, 200);
  assert.match(stream.headers.get('content-type'), /text\/event-stream/);
  const text = `POC Avro smoke ${Date.now()} — Szeged 👋`;
  const mutation = await graphql('mutation($text: String!) { createMessage(text: $text) { id text createdAt } }', { text });
  assert.equal(mutation.errors, undefined);
  const saved = mutation.data.createMessage;
  assert.equal(saved.text, text);
  const query = await graphql('{ messages { id text createdAt } }');
  assert.ok(query.data.messages.some(message => message.id === saved.id), 'Saved message must be queryable from DynamoDB');
  const reader = stream.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let delivered = false;
  while (!delivered) {
    const { value, done } = await reader.read();
    assert.equal(done, false, 'Subscription ended before receiving the event');
    buffer += decoder.decode(value, { stream: true });
    let boundary;
    while ((boundary = buffer.indexOf('\n\n')) !== -1) {
      const frame = buffer.slice(0, boundary);
      buffer = buffer.slice(boundary + 2);
      const data = frame.split('\n').find(line => line.startsWith('data:'));
      if (!data) continue;
      const event = JSON.parse(data.slice(5).trim());
      if (event.data?.messageCreated?.id === saved.id) delivered = true;
    }
  }
  await reader.cancel();
  console.log(`POC passed: validation → Scala API → DynamoDB → Kafka → GraphQL subscription (${saved.id}).`);
} finally {
  clearTimeout(timeout);
  controller.abort();
}
