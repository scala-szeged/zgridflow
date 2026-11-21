import { GraphQLError } from 'graphql';
import { createPubSub, createSchema, createYoga } from 'graphql-yoga';
import { z } from 'zod';

export const messageSchema = z.object({ id: z.string().uuid(), text: z.string(), createdAt: z.string().datetime() });
export const eventSchema = z.object({ kind: z.literal('MessageCreated'), message: messageSchema });
export type Message = z.infer<typeof messageSchema>;
export interface Backend {
  list(): Promise<Message[]>;
  create(text: string): Promise<Message>;
}

const textSchema = z.string().trim().min(1).max(200);

export function createGateway(backend: Backend) {
  const pubSub = createPubSub<{ messageCreated: [message: Message] }>();
  const schema = createSchema({
    typeDefs: /* GraphQL */ `
      type Message { id: ID!, text: String!, createdAt: String! }
      type Query { messages: [Message!]! }
      type Mutation { createMessage(text: String!): Message! }
      type Subscription { messageCreated: Message! }
    `,
    resolvers: {
      Query: { messages: () => backend.list() },
      Mutation: {
        createMessage: (_: unknown, { text }: { text: string }) => {
          const validated = textSchema.safeParse(text);
          if (!validated.success) throw new GraphQLError('text must contain 1–200 characters', {
            extensions: { code: 'BAD_USER_INPUT' },
          });
          return backend.create(validated.data);
        },
      },
      Subscription: {
        messageCreated: {
          subscribe: () => pubSub.subscribe('messageCreated'),
          resolve: (payload: Message) => payload,
        },
      },
    },
  });
  const yoga = createYoga({ schema, graphqlEndpoint: '/graphql', maskedErrors: true });
  return { schema, yoga, publish: (message: Message) => pubSub.publish('messageCreated', message) };
}
