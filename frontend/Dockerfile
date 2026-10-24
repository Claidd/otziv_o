# syntax=docker/dockerfile:1

FROM node:24.22.1-alpine3.24@sha256:06d7adf3e0ef61a13f1d28682726366f97a15aa1888c934d3ecec86c33918b53 AS build
WORKDIR /app
COPY frontend/package*.json frontend/.npmrc ./
COPY shared/client-common /shared/client-common
RUN --mount=type=cache,target=/root/.npm npm ci
COPY frontend/ ./
RUN npm run build -- --configuration production

FROM nginx:1.27-alpine@sha256:65645c7bb6a0661892a8b03b89d0743208a18dd2f3f17a54ef4b76fb8e2f2a10
RUN apk del --no-cache nginx-module-image-filter \
    && apk upgrade --no-cache \
    && apk add --no-cache --upgrade --repository https://dl-cdn.alpinelinux.org/alpine/v3.21/main curl=8.14.1-r3 libcurl=8.14.1-r3 brotli-libs=1.1.1-r0 \
    && sha256sum /usr/sbin/nginx > /tmp/nginx.sha256 \
    && apk add --no-cache --repository https://dl-cdn.alpinelinux.org/alpine/v3.23/main libssl3=3.5.9-r0 libcrypto3=3.5.9-r0 pcre2=10.49-r0 \
    && sha256sum -c /tmp/nginx.sha256 && rm /tmp/nginx.sha256 \
    && apk --no-network info -v -e pcre2 | grep -Fx 'pcre2-10.49-r0' \
    && apk --no-network info -v -e curl | grep -Fx 'curl-8.14.1-r3' \
    && apk --no-network info -v -e libcurl | grep -Fx 'libcurl-8.14.1-r3' \
    && apk --no-network info -v -e brotli-libs | grep -Fx 'brotli-libs-1.1.1-r0' \
    && ! apk --no-network info -e tiff \
    && nginx -t \
    && nginx -v
COPY infrastructure/nginx/prod.conf /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist/frontend/browser /usr/share/nginx/html
EXPOSE 80 443
