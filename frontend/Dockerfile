# syntax=docker/dockerfile:1

FROM node:24-alpine AS build
WORKDIR /app
COPY frontend/package*.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY frontend/ ./
RUN npm run build -- --configuration production

FROM nginx:1.27-alpine
COPY infrastructure/nginx/prod.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/frontend/browser /usr/share/nginx/html
EXPOSE 80 443
