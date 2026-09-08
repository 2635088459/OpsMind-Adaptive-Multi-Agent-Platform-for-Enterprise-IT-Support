# syntax=docker/dockerfile:1
#
# Build context is the REPO ROOT (pnpm workspace):
#   docker build -f apps/employee-portal/Dockerfile -t employee-portal .
FROM node:24-slim AS build
RUN corepack enable
WORKDIR /repo

COPY pnpm-lock.yaml pnpm-workspace.yaml package.json ./
COPY packages/ packages/
COPY apps/employee-portal/ apps/employee-portal/
RUN pnpm install --frozen-lockfile --filter employee-portal...
RUN pnpm --filter employee-portal build

FROM nginx:1.27-alpine AS runtime
COPY apps/employee-portal/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /repo/apps/employee-portal/dist /usr/share/nginx/html
EXPOSE 80
