FROM node:22-alpine AS deps
WORKDIR /app
RUN corepack enable && corepack prepare pnpm@latest --activate
COPY package.json pnpm-workspace.yaml pnpm-lock.yaml turbo.json ./
COPY apps/dashboard/package.json apps/dashboard/
COPY packages/ packages/
RUN pnpm install --frozen-lockfile

FROM node:22-alpine AS builder
WORKDIR /app
RUN corepack enable && corepack prepare pnpm@latest --activate
COPY --from=deps /app/node_modules ./node_modules
COPY --from=deps /app/apps/dashboard/node_modules ./apps/dashboard/node_modules
COPY . .
RUN pnpm --filter dashboard build

FROM node:22-alpine
RUN addgroup -S lynceus && adduser -S lynceus -G lynceus
WORKDIR /app
COPY --from=builder /app/apps/dashboard/.next/standalone ./
COPY --from=builder /app/apps/dashboard/.next/static ./apps/dashboard/.next/static
COPY --from=builder /app/apps/dashboard/public ./apps/dashboard/public
USER lynceus
EXPOSE 3000
ENV PORT=3000
# Next.js standalone output binds to $HOSTNAME if set, defaulting to 0.0.0.0
# otherwise — but Docker auto-injects HOSTNAME=<container-id> into every
# container's environment, which the standalone server then resolves and
# binds to instead of the wildcard address. Without this override, the
# server only accepts connections on the container's own IP, so
# `wget http://localhost:3000` (our healthcheck, and any other same-host
# caller) gets connection refused even though the process is up and ready.
ENV HOSTNAME="0.0.0.0"
ENTRYPOINT ["node", "apps/dashboard/server.js"]
