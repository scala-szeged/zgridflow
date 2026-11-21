import { createServer } from 'node:http';
import { setTimeout as delay } from 'node:timers/promises';
import { Kafka, logLevel } from 'kafkajs';
import { GraphQLError } from 'graphql';
import { z } from 'zod';
import { createGateway, messageSchema, eventSchema } from './schema.js';
import { decodeEvent } from './avro.js';

const api = process.env.API_URL ?? 'http://localhost:8080';
const topic = process.env.KAFKA_TOPIC ?? 'zgridflow.message-created.avro.v1';
const port = Number(process.env.PORT ?? 4000);
const kafka = new Kafka({
  clientId: 'zgridflow-gateway',
  brokers: (process.env.KAFKA_BROKERS ?? 'localhost:9094').split(','),
  logLevel: logLevel.WARN,
  retry: { retries: 10 },
});
// Each gateway replica receives all events, then fans them out to its own SSE clients.
const consumer = kafka.consumer({ groupId: `zgridflow-gateway-${process.env.HOSTNAME ?? 'local'}` });
let kafkaReady = false;
consumer.on(consumer.events.GROUP_JOIN, () => { kafkaReady = true; });
consumer.on(consumer.events.DISCONNECT, () => { kafkaReady = false; });
consumer.on(consumer.events.CRASH, ({ payload }) => {
  kafkaReady = false;
  console.error('Kafka consumer crashed', payload.error);
  if (!payload.restart) process.exit(1);
});

async function apiRequest(path: string, init?: RequestInit): Promise<unknown> {
  const response = await fetch(`${api}${path}`, { ...init, signal: AbortSignal.timeout(5000) });
  if (!response.ok) throw new GraphQLError('API unavailable', { extensions: { code: 'UPSTREAM_ERROR' } });
  return response.json();
}

const gateway = createGateway({
  list: async () => z.array(messageSchema).parse(await apiRequest('/messages')),
  create: async (text) => messageSchema.parse(await apiRequest('/messages', {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ text }),
  })),
});

const server = createServer((request, response) => {
  if (request.url === '/health') {
    response.writeHead(200).end('ok');
  } else if (request.url === '/ready') {
    void apiRequest('/health').then(() => {
      response.writeHead(kafkaReady ? 200 : 503).end(kafkaReady ? 'ready' : 'waiting for Kafka');
    }).catch(() => { response.writeHead(503).end('waiting for API'); });
  } else {
    void gateway.yoga(request, response);
  }
});
server.listen(port, '0.0.0.0', () => console.info(`GraphQL POC listening on :${port}/graphql`));

async function startConsumer() {
  // The API owns table/topic initialization. Wait for it before subscribing.
  for (let attempt = 0; ; attempt++) {
    try { await apiRequest('/health'); break; }
    catch (error) { if (attempt >= 60) throw error; await delay(2000); }
  }
  await consumer.connect();
  await consumer.subscribe({ topic, fromBeginning: false });
  const seen = new Set<string>();
  await consumer.run({ eachMessage: async ({ message }) => {
    let parsed: z.infer<typeof eventSchema>;
    try { parsed = decodeEvent(message.value, message.headers); }
    catch (error) { console.error('Skipping invalid event', error); return; }
    if (seen.has(parsed.message.id)) return;
    seen.add(parsed.message.id);
    if (seen.size > 1000) seen.delete(seen.values().next().value!);
    gateway.publish(parsed.message);
  } });
}

void startConsumer().catch((error) => { console.error(error); process.exit(1); });
for (const signal of ['SIGTERM', 'SIGINT'] as const) {
  process.once(signal, () => {
    kafkaReady = false;
    server.close();
    server.closeAllConnections();
    void consumer.disconnect().finally(() => process.exit(0));
    setTimeout(() => process.exit(1), 10000).unref();
  });
}
