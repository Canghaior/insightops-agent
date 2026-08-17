FROM node:24-alpine AS build
WORKDIR /workspace
COPY insightops-web/package.json insightops-web/package-lock.json ./
RUN npm ci
COPY insightops-web/ ./
RUN npm run build

FROM nginx:1.29-alpine
COPY infra/nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=20s --timeout=5s --start-period=10s --retries=5 \
    CMD wget --quiet --tries=1 --spider http://127.0.0.1:8080/ || exit 1
