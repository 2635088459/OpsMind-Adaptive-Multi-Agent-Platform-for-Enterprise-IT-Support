# syntax=docker/dockerfile:1
#
# Build context is the REPO ROOT (pnpm workspace):
#   docker build -f apps/support-console/Dockerfile -t support-console .
FROM node:24-slim AS build
RUN corepack enable
WORKDIR /repo

COPY pnpm-lock.yaml pnpm-workspace.yaml package.json ./
COPY packages/ packages/
COPY apps/support-console/ apps/support-console/
RUN pnpm install --frozen-lockfile --filter support-console...

# Vite bakes VITE_* into the bundle at build time. Optional: pass a LangSmith
# workspace URL + org id (from the compose .env) so the Observability page's
# "View in LangSmith" deep link can be built. Empty → the link stays hidden.
ARG VITE_LANGSMITH_BASE_URL=https://smith.langchain.com
ARG VITE_LANGSMITH_ORG_ID=
ENV VITE_LANGSMITH_BASE_URL=$VITE_LANGSMITH_BASE_URL
ENV VITE_LANGSMITH_ORG_ID=$VITE_LANGSMITH_ORG_ID
RUN pnpm --filter support-console build

FROM nginx:1.27-alpine AS runtime
COPY apps/support-console/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /repo/apps/support-console/dist /usr/share/nginx/html
EXPOSE 80
