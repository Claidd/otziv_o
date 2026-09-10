FROM node:22-trixie-slim@sha256:7b8a0c89c54499bee567618f96578e1a12a800f062fbdbfd1fb6a443fa6f6284

# Identical browser layer in both images; provenance: runtime-security/proofs/chrome153.
RUN printf 'Acquire::Retries "1"; Acquire::http::Timeout "20"; Acquire::https::Timeout "20"; Acquire::ForceIPv4 "true";\n' > /etc/apt/apt.conf.d/80-bounded-network && \
    test "$(dpkg --print-architecture)" = amd64 && \
    apt-get update && apt-get upgrade -y && apt-get install -y --no-install-recommends curl ca-certificates fonts-liberation fonts-dejavu && \
    curl --fail --location --retry 3 --connect-timeout 20 --max-time 300 'https://dl.google.com/linux/chrome/deb/pool/main/g/google-chrome-stable/google-chrome-stable_153.0.8010.36-1_amd64.deb' --output /tmp/chrome.deb && \
    printf '%s  /tmp/chrome.deb\n' '9bb44e33031c2f2857cf36b4343051a12f93058e4b781e3c76313df87f6c8d32' | sha256sum --check --strict && \
    apt-get install -y --no-install-recommends /tmp/chrome.deb && \
    test "$(dpkg-query -W -f='${Version}' google-chrome-stable)" = "153.0.8010.36-1" && \
    /usr/bin/google-chrome-stable --version | grep -Fx "Google Chrome 153.0.8010.36 " && \
    rm /tmp/chrome.deb && apt-get clean && rm -rf /var/lib/apt/lists/*

ENV OTZIV_BROWSER_VERSION=153.0.8010.36
ENV NODE_ENV=production \
    PORT=3097 \
    PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
    CHROMIUM_EXECUTABLE_PATH=/usr/bin/google-chrome-stable \
    HOME=/tmp/worker-home \
    XDG_CACHE_HOME=/tmp/otziv-cache \
    TESSERACT_CACHE_PATH=/tmp/tesseract-cache

WORKDIR /app

COPY package*.json ./
RUN npm ci --omit=dev && npm cache clean --force && \
    mkdir -p "$HOME" "$XDG_CACHE_HOME" "$TESSERACT_CACHE_PATH" && \
    chown -R node:node /app "$HOME" "$XDG_CACHE_HOME" "$TESSERACT_CACHE_PATH"

COPY --chown=node:node src ./src
COPY --chown=node:node README.md ./

# Installation tools are build-only; runtime entrypoints and probes invoke node directly.
RUN rm -rf /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/corepack /opt/yarn-* && \
    rm -f /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack /usr/local/bin/yarn /usr/local/bin/yarnpkg /usr/local/bin/pnpm /usr/local/bin/pnpx

USER node

# Build-time contract for the paths Chromium/Tesseract need at runtime.
RUN node -e "const fs=require('fs'); for (const p of [process.env.HOME, process.env.XDG_CACHE_HOME, process.env.TESSERACT_CACHE_PATH]) fs.accessSync(p, fs.constants.W_OK); fs.accessSync(process.env.CHROMIUM_EXECUTABLE_PATH, fs.constants.X_OK)"

EXPOSE 3097

HEALTHCHECK --interval=30s --timeout=5s --retries=3 --start-period=20s \
  CMD node -e "require('http').get('http://127.0.0.1:' + (process.env.PORT || 3097) + '/health', r => process.exit(r.statusCode === 200 ? 0 : 1)).on('error', () => process.exit(1))"

CMD ["node", "src/index.js"]
