# ---- dependencies ---------------------------------------------------------
# Separate stage so a source-only change reuses the cached install.
FROM node:22-alpine AS deps
RUN corepack enable
WORKDIR /build
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile

# ---- build ----------------------------------------------------------------
FROM node:22-alpine AS build
RUN corepack enable
WORKDIR /build
COPY --from=deps /build/node_modules ./node_modules
COPY . .

# NEXT_PUBLIC_* values are inlined at build time, so they must be present here
# rather than only at runtime. These are the browser-facing URLs; the internal
# one used by route handlers is read at runtime and is not baked in.
ARG NEXT_PUBLIC_API_URL=http://localhost:8080/api/v1
ARG NEXT_PUBLIC_WS_URL=ws://localhost:8080/ws
ENV NEXT_PUBLIC_API_URL=$NEXT_PUBLIC_API_URL \
    NEXT_PUBLIC_WS_URL=$NEXT_PUBLIC_WS_URL \
    NEXT_TELEMETRY_DISABLED=1 \
    BUILD_STANDALONE=1

RUN pnpm build

# ---- runtime --------------------------------------------------------------
FROM node:22-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT=3000 HOSTNAME=0.0.0.0

RUN addgroup -S sprintiq && adduser -S sprintiq -G sprintiq

# `output: "standalone"` produces server.js plus only the modules it actually needs.
COPY --from=build --chown=sprintiq:sprintiq /build/.next/standalone ./
COPY --from=build --chown=sprintiq:sprintiq /build/.next/static ./.next/static
COPY --from=build --chown=sprintiq:sprintiq /build/public ./public

USER sprintiq
EXPOSE 3000

# Hits a real route rather than a synthetic endpoint: it proves rendering works,
# not just that the process is alive.
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD wget -qO- http://localhost:3000/login >/dev/null || exit 1

CMD ["node", "server.js"]
