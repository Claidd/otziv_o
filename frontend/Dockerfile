# syntax=docker/dockerfile:1

FROM node:24.22.1-alpine3.24@sha256:06d7adf3e0ef61a13f1d28682726366f97a15aa1888c934d3ecec86c33918b53 AS build
WORKDIR /app
COPY frontend/package*.json frontend/.npmrc ./
COPY shared/client-common /shared/client-common
RUN --mount=type=cache,target=/root/.npm npm ci
COPY frontend/ ./
RUN npm run build -- --configuration production

# Use the same reviewed NGINX runtime as the production ingress.
FROM ghcr.io/claidd/otziv-security@sha256:55e5b2dfa71bc889e854ef1b1f737afe155ab4f0244cc2bf4c5c517b1e1472f6
RUN nginx -t && nginx -v
COPY infrastructure/nginx/prod.conf /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist/frontend/browser /usr/share/nginx/html
EXPOSE 80 443
