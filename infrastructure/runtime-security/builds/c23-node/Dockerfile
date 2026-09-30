FROM ghcr.io/claidd/otziv-security@sha256:8917049917dc110930957c5d59cff66489d9a3e486ffdfe7b2221c40acc0f14c
ADD --checksum=sha256:ef8448ec78f20b692f04fa6d01f39b5ab34c66404bea3429f5a39c6c9e0be8b4 https://registry.npmjs.org/brace-expansion/-/brace-expansion-5.0.12.tgz /patches/brace-expansion-5.0.12.tgz
ADD --checksum=sha256:e18191aac9c0ff43dac7fe9b10b7041a22d07addb7b66a6e8ac14a52a5b69b74 https://registry.npmjs.org/undici/-/undici-6.28.1.tgz /patches/undici-6.28.1.tgz
COPY npm-patches.json patch-npm.mjs /patches/
RUN node /patches/patch-npm.mjs && rm -rf /patches && npm --version
