FROM node:22-bookworm-slim@sha256:f32b81066cde10a75dbac96646099533316d94bac4150c55da1636e1f0ffdc46

ENV NODE_ENV=production \
    PORT=3097 \
    PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
    CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium \
    HOME=/tmp/worker-home \
    XDG_CACHE_HOME=/tmp/otziv-cache \
    TESSERACT_CACHE_PATH=/tmp/tesseract-cache

RUN printf 'Acquire::Retries "5";\nAcquire::http::Timeout "60";\nAcquire::https::Timeout "60";\n' > /etc/apt/apt.conf.d/80-retries && \
    apt-get update && apt-get install -y --fix-missing \
    chromium \
    ca-certificates \
    fonts-liberation \
    fonts-dejavu \
    --no-install-recommends && \
    update-ca-certificates && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY package*.json ./
RUN npm ci --omit=dev && npm cache clean --force && \
    mkdir -p "$HOME" "$XDG_CACHE_HOME" "$TESSERACT_CACHE_PATH" && \
    chown -R node:node /app "$HOME" "$XDG_CACHE_HOME" "$TESSERACT_CACHE_PATH"

COPY --chown=node:node src ./src
COPY --chown=node:node README.md ./

USER node

# Build-time contract for the paths Chromium/Tesseract need at runtime.
RUN node -e "const fs=require('fs'); for (const p of [process.env.HOME, process.env.XDG_CACHE_HOME, process.env.TESSERACT_CACHE_PATH]) fs.accessSync(p, fs.constants.W_OK); fs.accessSync(process.env.CHROMIUM_EXECUTABLE_PATH, fs.constants.X_OK)"

EXPOSE 3097

HEALTHCHECK --interval=30s --timeout=5s --retries=3 --start-period=20s \
  CMD node -e "require('http').get('http://127.0.0.1:' + (process.env.PORT || 3097) + '/health', r => process.exit(r.statusCode === 200 ? 0 : 1)).on('error', () => process.exit(1))"

CMD ["node", "src/index.js"]
