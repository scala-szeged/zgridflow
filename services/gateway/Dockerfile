FROM node:26-alpine AS build
WORKDIR /app
COPY services/gateway/package*.json ./
RUN npm ci
COPY services/gateway/tsconfig.json ./
COPY services/gateway/src ./src
RUN npm run build && npm prune --omit=dev

FROM node:26-alpine
WORKDIR /app
ENV NODE_ENV=production
ENV AVRO_SCHEMA_PATH=/app/contracts/avro/message-created-v1.avsc
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
COPY services/gateway/package.json ./
COPY contracts/avro/message-created-v1.avsc ./contracts/avro/message-created-v1.avsc
USER node
EXPOSE 4000
CMD ["node", "dist/server.js"]
